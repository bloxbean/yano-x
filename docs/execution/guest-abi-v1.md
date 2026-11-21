# Guest ABI v1

Status: **ABI v1, ADR-058 (Accepted).** This is what a state-machine author writes against, in any
guest language. The data definitions are in
[`guest-abi-v1.cddl`](../../state-machines/execution-abi/spec/guest-abi-v1.cddl), and
[`vectors/abi-v1.json`](../../state-machines/execution-abi/spec/vectors/abi-v1.json) has golden
vectors that every SDK must reproduce. The Java reference is `yano-x-execution-abi`
(`state-machines/execution-abi`), which also packages both files under
`org/yanoproject/x/execution/abi/v1/spec/`.

The key words MUST, MUST NOT and SHOULD are used as in RFC 2119.

## 1. Model

A program is a set of exported **entry points**. The host calls an entry point with one input byte
string and gets one output byte string back. During the call, the program reaches state, effects
and hashing only through **host functions**. Inputs, outputs and structured host arguments are
canonical CBOR (section 6).

| Entry point | Required | Called for | Host functions allowed |
|---|---|---|---|
| `apply_message` | yes | each application message of a finalized block | all |
| `on_effect_result` | no | each committed effect outcome, before the block's messages | all |
| `validate` | no | mempool admission, advisory | read-only |
| `query` | no | committed-state queries, off consensus | read-only |

A program without `apply_message` MUST be rejected at load. A missing optional entry point means:
`on_effect_result` does nothing, `validate` accepts, and `query` answers `unsupported`.

## 2. Execution of a block

1. For each committed effect result in the block, the host calls `on_effect_result`, in the order
   Yano delivers them.
2. For each message of the block in block order whose topic does not start with `~`, the host
   calls `apply_message`. Framework topics never reach the guest.
3. **Instance scope.** The block's transitions (steps 1 and 2) share one instance. The host
   creates a fresh instance for the block's first transition, and again for the next transition
   after any transition whose outcome is not `transition-ok` (see the table below). So guest memory
   (JS globals, WASM linear memory and globals) carries from one transition to the next only
   between committed transitions of the same block. It never carries across blocks, and a discarded
   transition leaves nothing behind, not even in guest memory. `validate` and `query` each run on a
   fresh instance of their own. Every node applies the same rule, so state that depends on guest
   memory is still deterministic; the TCK checks that runtimes keep memory between invocations of
   one instance and meter each invocation from zero. Durable data belongs in state: memory is
   for caches and scratch space that the program could rebuild. A guest whose allocator never
   frees (such as the Rust SDK's bump allocator) must reset it between invocations, or a long
   block exhausts memory for its later messages.
4. Writes of a committed invocation are visible to later invocations in the same block.
5. The outcome of each invocation decides what happens to its writes and effects:

| Outcome | Writes and effects | Receipt kind and code |
|---|---|---|
| Completed, output `transition-ok` | committed | none |
| Completed, output `transition-rejected` | discarded | 1 rejected, the guest's code |
| Completed, output not a canonical `transition-outcome` | discarded | 5 ABI violation, `bad-output` |
| Trapped | discarded | 2 trapped, `guest-error` (an unhandled guest error: a JS `throw`, a WASM `unreachable`), `guest-fault` (a machine fault), `memory-limit`, `call-depth` or `bad-return` |
| Compute exhausted | discarded | 3 out of compute, `compute` |
| Host fuel exhausted | discarded | 4 out of host fuel, `host-fuel` |
| Host function rejected a call | discarded | 5 ABI violation, the host's code (section 4) |
| Non-deterministic engine failure | the node stops applying the block | none |

Every outcome except the last is a deterministic function of the program, the input, the budgets
and the state. So one bad message is discarded on every node alike, and it neither stops the chain
nor affects other messages. When the descriptor enables receipts, a discarded transition leaves a
`receipt` at `~exec/r/<message id>` (for `apply_message`) or `~exec/fx/<blake2b-256 effect id
hash>` (for `on_effect_result`).

An engine failure (JVM stack or heap exhaustion, engine bugs) is not deterministic. It MUST never
become a result; the node stops instead of diverging.

## 3. Budgets

Each invocation gets two budgets from the genesis-pinned execution descriptor:

- **Compute**, in the pinned runtime's own unit (GraalJS statements, WASM instructions). Units are
  not comparable across runtimes, which is why the descriptor also pins the engine fingerprint. The
  runtime MUST stop the guest at exactly the budget.
- **Host fuel**, in ABI host units (section 4). It is the same for every runtime: a program's host
  fuel depends only on the calls it makes.

How much compute a guest used is not portable (not every engine can report it), so it is never
consensus data. Only exhaustion is.

## 4. Host functions

Host functions are imported from the `yano` namespace, in this table order. Every argument is a
byte string. The table is append-only: later ABI versions may add functions but never renumber,
rename or reshape one.

| # | Name | Arguments | Returns | Extra cost |
|---|---|---|---|---|
| 0 | `state_get` | key | value or absent | 1 per value byte returned |
| 1 | `state_put` | key, value | nothing | 400 + 2 per value byte |
| 2 | `state_delete` | key | nothing | 400 |
| 3 | `effect_emit` | `effect-intent` | nothing | 2000 |
| 4 | `blake2b_256` | data | 32 bytes | 200 + 32 |
| 5 | `sha2_256` | data | 32 bytes | 200 + 32 |
| 6 | `log` | level (1 byte, 0 to 3), UTF-8 message | nothing | 0 |

**Charging.** Every call first costs 100 plus 1 per argument byte, then the extra cost. Fuel is
charged before the call takes effect. When a charge would exceed the budget, the call does nothing
and the invocation ends as host fuel exhausted.

**Limits and violations.** A call that breaks one of these rules ends the invocation as an ABI
violation with the listed code:

| Rule | Code |
|---|---|
| Key is 1 to 1024 bytes | `invalid-key` |
| Key does not start with `~` (0x7E); those belong to the framework and the execution state machine (receipts under `~exec/`) | `reserved-key` |
| Value is at most 65,536 bytes | `value-too-large` |
| At most 4096 distinct keys written per invocation | `write-limit` |
| `state_put`, `state_delete` and `effect_emit` only in `apply_message` and `on_effect_result` | `read-only` |
| `effect-intent` is canonical and valid, with a scope of at most 256 bytes and a payload of at most 65,536 bytes. These ABI v1 caps apply whatever the chain allows (Yano accepts `effects.max-payload-bytes` up to 16 MiB) | `invalid-effect` |
| Effects are enabled on the chain (`effects.enabled`) | `effects-disabled` |
| At most 64 effects per invocation and within `effects.max-per-block`, payload within `effects.max-payload-bytes`, expiry within `effects.max-expiry-blocks` and, for result policy 1, `effects.result-window-blocks` | `effect-limit` |
| Hash input at most 1 MiB | `input-too-large` |
| Log level 0 to 3, message at most 1024 bytes of valid UTF-8 | `invalid-log` |

**Aborts cannot be caught.** When a host function ends the invocation, the runtime MUST stop the
guest, even in languages where guest code could catch a host exception. A guest that catches the
abort and returns normally is still reported as aborted.

**Semantics.**
- `state_get` sees the committed state, plus earlier committed invocations of this block, plus this
  invocation's own writes.
- `effect_emit` records intent; the host emits it only if the invocation commits. The host sets
  the effect's source message id to the current message in `apply_message` and to none in
  `on_effect_result`. The guest cannot set it.
- `log` never affects results. Nodes may drop log lines.

Ed25519 verification is planned for a later ABI version, once its verification rule (cofactored or
not, how non-canonical encodings are treated) is pinned precisely enough to be identical on every
engine.

## 5. Outputs and codes

- `apply_message`, `on_effect_result`: `transition-outcome`, at most 4096 bytes.
- `validate`: `verdict`. Anything other than a canonical accept (including traps and exhaustion)
  rejects the message.
- `query`: `query-outcome`, payload at most 1 MiB. Error codes are only `unsupported` and
  `invalid-request`, matching Yano's query contract.

Rejection codes match `[a-z0-9][a-z0-9._-]{0,63}`.

## 6. Encoding

All structured data is RFC 8949 core deterministic CBOR, restricted to integers in the int64
range, byte strings, UTF-8 text, arrays, maps with integer or text keys, and true, false and null.
Decoders MUST reject anything that is not the canonical encoding of its value. Maps are
integer-keyed, and keys not in the CDDL are errors. An optional key is omitted when it has its
default value; a present optional key holding its default is an error. So every value has exactly
one encoding.

The `invalid` section of the golden vectors lists inputs that every decoder MUST reject, with the
expected reason.

## 7. Language bindings

### 7.1 JavaScript (`text/javascript`)

- The program is one UTF-8 ECMAScript 2024 script, not a module, of at most 4 MiB. It is evaluated
  once in each new instance, in a fresh realm, on the first invocation and under that invocation's
  budget.
- **Exports** are the function-valued global properties the program adds, which in practice means
  its top-level `function` declarations. `const` and `let` bindings are not global properties, so
  helpers declared with them are not exports. Entry points take a `Uint8Array` and return a
  `Uint8Array`. Any other return value, including the `Promise` of an `async` function, is a
  `bad-return` trap.
- **Host functions** are methods of a frozen, non-configurable global `yano`. `yano.state_get(key)`
  returns a fresh `Uint8Array` or `undefined`; `yano.log(level, message)` takes a one-byte
  `Uint8Array` and a UTF-8 one. Every argument must be a genuine `Uint8Array` (a subarray is fine;
  a view of a detached buffer reads as empty), and the argument count must match; anything else
  throws a `TypeError` before the host is reached. Bytes are copied in both directions.
- Host functions work only while an entry point runs. Top-level code that calls one throws a
  `TypeError`; the load-time evaluation then fails and the program is rejected (`malformed`).
  Promise jobs never reach the host: they do not run while the entry point runs, and a job that
  runs later finds the host functions closed.
- **Ambient nondeterminism is removed.** Locale-sensitive built-ins (`toLocaleLowerCase`,
  `toLocaleString`, `localeCompare`) use the pinned locale `en-US`, never the node's. `Math.random` throws. `Date`, `Intl`, `performance`,
  `SharedArrayBuffer`, `Atomics`, `WeakRef`, `FinalizationRegistry` (the last two expose
  garbage-collection timing), `Graal`, `console`, `print`, `load` and the polyglot and Java globals
  are absent. No I/O, host classes, threads or polyglot access.
- **Compute** is metered in GraalJS statements, counted in every non-internal source, including
  code created with `eval` or `new Function`. Statement counts are deterministic for a pinned
  GraalJS version (incubation Phase 0, S2).
- **Call depth.** More than 2,000 guest function frames is a `call-depth` trap that guest code cannot
  catch. A JVM stack overflow or heap exhaustion inside a built-in (for example `String()` of a
  deeply nested array, which recurses through `join`) is an engine failure even if the guest catches
  the resulting `RangeError`: the node stops rather than diverge. `JSON.parse` and
  `JSON.stringify` have their own nesting limits and fail with a deterministic `RangeError`.
- The SDK in `sdk-js/yano.js` defines one global constant, `Yano` (strict canonical CBOR, ABI
  records, outputs, effect intents), and no global functions, so it adds no exports.

### 7.2 WebAssembly (`application/wasm`)

- **Exports.** The module exports its one linear memory as `memory`, an allocator `yano_alloc(len:
  i32) -> i32`, and each entry point as a function `(ptr: i32, len: i32) -> i64`. Exported
  functions of any other type are not entry points, and other exports (such as the `__heap_base`
  and `__data_end` globals Rust emits) are ignored. A missing `memory` or `yano_alloc` is
  `missing-export`; a `yano_alloc` of another type is `malformed`.
- **Input.** The host passes an empty input as `(0, 0)` without calling `yano_alloc`. Otherwise it
  calls `yano_alloc(len)`, copies the input into the buffer, and passes `(ptr, len)`; the guest
  owns the buffer.
- **Output.** The entry point returns `(ptr << 32) | len` of its output in its memory. A range
  outside memory is a `bad-return` trap.
- **Host functions** are imported from module `yano` by their table name, with exactly the type
  the table implies: each byte-string argument a `(ptr: i32, len: i32)` pair; functions that
  return bytes return an `i64` and the others nothing. A function that returns bytes returns `-1`
  for absent and otherwise `(ptr << 32) | len` of a buffer the host obtained from `yano_alloc`
  and the guest now owns; an empty result is `0`, without a call to `yano_alloc`. An import from
  another module, of a name not in the table, or of a memory, table or global is `unknown-import`;
  a table name with the wrong type is `malformed`.
- **Guest memory faults.** A byte range passed to the host that lies outside memory is a
  `guest-fault` trap, and the host is never called. `yano_alloc` returning 0 means the guest has
  no memory left: a `memory-limit` trap. `yano_alloc` returning a range outside memory is a
  `guest-fault` trap.
- **Load-time gatekeeper** (`forbidden-feature`). The instruction set is an allowlist: the MVP
  integer instructions, sign extension, multi-value, mutable globals, `memory.copy`,
  `memory.fill`, `memory.init`, `data.drop` and `call_indirect` through function tables. Value
  types are `i32` and `i64` only, and tables hold function references only. Everything else is
  rejected, including every float instruction and value type, SIMD, threads, atomics and shared
  memory, exception handling, tail calls, typed function references, GC types, table
  instructions, a start function, more than one memory, and a memory whose declared initial size
  or maximum is above the descriptor's memory limit. The allowlist's SHA-256 is a fingerprint
  component.
- **Memory.** Linear memory is capped at `floor(memory limit / 64 KiB)` pages, or at the module's
  declared maximum if that is lower. Growing past the cap makes `memory.grow` return -1, which the
  guest observes; nothing else happens.
- **Traps.** `unreachable` (where a Rust panic ends) is `guest-error`. Every other interpreter
  trap (integer divide by zero or overflow, out-of-bounds memory access, a bad indirect call) is
  `guest-fault`.
- **Call depth.** The interpreter's call stack, including host import frames and the entry point
  itself, holds at most 10,000 frames; the call that would exceed it is a `call-depth` trap.
- **Compute** is metered in executed instructions, exactly, on the Chicory interpreter: each
  instruction, including a function's final `end`, costs one unit, and the instructions of
  `yano_alloc` calls the host makes during the invocation count too. An invocation that would
  execute instruction `budget + 1` ends out of compute. Chicory's AOT compiler bypasses the
  meter, so it is not used until modules are instrumented with gas (milestone M5).
- **Guest SDK.** `sdk-rust` (crate `yano-guest`, `no_std`) provides the CBOR codec, ABI records,
  host function wrappers, a small allocator, and an `entry_points!` macro that exports the entry
  points and `yano_alloc`.

## 8. Not in v1

L1 observations, observation results and observation requests; transition kernels for atomic
composition; snapshot series; privileged system messages; Ed25519; events. Each is a candidate for a
later ABI version. None needs a runtime SPI change, only new entry points or host functions.
