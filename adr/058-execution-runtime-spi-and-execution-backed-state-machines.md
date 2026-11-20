# ADR-058 — Execution runtime SPI and execution-backed state machines

- **Status:** Proposed (2026-11-21).
- **Issue:** [#103](https://github.com/bloxbean/yano-x/issues/103) (ExecutionBackedStateMachine and
  GraalVM polyglot plugins).
- **Evidence:** the incubator repository
  [bloxbean/yano-x-executors](https://github.com/bloxbean/yano-x-executors) at commit `f578f94`
  (private for now, to be made public). This ADR restates everything a reviewer needs; the
  incubator holds the code, the tests and the full specifications.
- **Related:** ADR app-layer/011 (plugin architecture and trust tiers), ADR app-layer/031
  (composable state machines), ADR app-layer/031.3 §13.1 (no second policy engine in consensus),
  ADR app-layer/013.1 (effect runtime), ADR app-layer/010.1 (height-gated activation), ADR-057
  (framework extraction, `org.yanoproject.x.framework.*`).
- **Risk:** high. The design adds a new path that decides state roots, and it runs programs that
  the node operator did not write.

## 1. Context

Today a Yano X state machine is a Java `AppStateMachine`, created by an `AppStateMachineProvider`
and packaged as a catalog plugin. ADR-011 treats in-process plugins as fully trusted Java code and
explicitly does not sandbox them. So a chain whose logic is written in another language, or whose
program should be replaceable without shipping a new trusted Java bundle, is not possible.

Issue #103 asks for an `ExecutionBackedStateMachine`: one generic state machine that delegates
program execution to a pluggable execution runtime, with GraalJS first. It sketches runtimes for
state machines, effects, observers and policies, and lists eight questions for a later ADR. This
is that ADR, for the state-machine part only.

The maintainers set three constraints for this work (2026-11-20):

1. The SPI must be **real and runtime-neutral**, so third parties can add runtimes. A wrapper around
   GraalJS does not meet that.
2. One implementation cannot show that an abstraction is neutral. A second runtime, Chicory
   (WebAssembly in pure Java), is built to validate the SPI against GraalJS.
3. The work is incubated outside yano-x. yano-x removed its experimental ZK modules for the same
   reason (commit `11e68ae0`), and GraalJS adds about 35 MB that must stay out of the Central
   footprint.

ADR-031.3 §13.1 rejected a CEL runtime or language-neutral engine for **policy** in consensus,
because a second engine doubles the determinism surface, and said WASM or another language "would
need its own ADR and budget model". That rejection stands: policy scripting is out of scope here.
This ADR proposes such an engine for **state transitions** only, with the budget model §13.1 asks
for.

### 1.1 What incubation has shown so far

Phase 0 (feasibility) and Phase 1 (specifications, SPI, TCK, adapter) are done. Both built only
against yano-x `main` at `00274933`, published to Maven Local, and needed **no yano-x change**.

Phase 0, on one Apple M4 Max machine (numbers are for comparing options, not performance claims):

| Spike | Result |
|---|---|
| GraalJS in a plugin bundle | A JS counter loaded through the production directory catalog passes `StateMachineConformance` (40 blocks, 3 runs including a restart) and gives the same state roots as a Java counter at all 40 heights, on Oracle GraalVM 25.0.2, on Liberica 25.0.2 (interpreter-only Truffle) and on Liberica with the Graal compiler added through JVMCI flags. The bundle is 35 MB, 15,783 entries; it needs `Multi-Release: true`. |
| GraalJS cost | 200-message block: 0.45 ms with a fresh context, 0.31 ms reusing one, 0.27 ms in plain Java (p50, optimizing runtime). Context creation is about 0.11 ms. Interpreter-only Truffle is about 70 times slower (31 ms). |
| GraalJS metering | A statement limit of 2,000,000 stopped an endless loop after exactly 390,241 host ticks, cold, after JIT warm-up, over five repeats and on all four JVM setups. |
| Chicory fuel | Fuel counted in Chicory 1.7.5's `ExecutionListener` is exact (2,900,018 for one workload, every run, on both JVMs and from an isolated class loader), and an endless loop stops at exactly the budget. Metering costs about 10% over the plain interpreter. The AOT compiler is about 25 times faster but bypasses the listener. 1,000 host calls cost only 12,016 guest instructions, so instruction fuel does not cover host work. |

Two consequences shaped the SPI. First, the engines meter in different units and differ in what
they can report, so the SPI describes a runtime's metering instead of pretending it is uniform.
Second, host work must be charged by the host, in units of its own.

Phase 1 produced the three contracts below, each executable: the guest ABI with a strict codec and
golden vectors, the runtime SPI, a TCK of 16 programs and 25 checks, and a draft adapter. The TCK
passes on a reference runtime whose guests are Java classes, and three deliberately broken
runtimes each fail exactly the check meant to catch them. The adapter passes
`StateMachineConformance` with the same roots as a plain Java machine.

## 2. Goals and non-goals

Goals:

1. A state machine can be written in a guest language and run by any runtime that passes the TCK,
   with the same state roots on every node.
2. One hostile or buggy message can neither stop the chain nor change the outcome of other
   messages.
3. Everything that can change a result is pinned in genesis. Nothing node-local can.
4. A runtime author implements a small SPI that knows nothing about Yano.

Non-goals:

- The OBSERVER and EFFECT profiles of #103 (scripted observers and effect handlers). The SPI
  reserves the names; neither is designed here.
- Policy scripting (ADR-031.3 §13.1).
- GraalPy, MoveVM and Plutus/UPLC. Each would be a further runtime behind the same SPI, if its
  semantics fit; none is evaluated here.
- Atomic participation in composite cascades (transition kernels, ADR-031.1). An execution-backed
  machine is a plain `AppStateMachine` in v1 (§12, open question Q7).
- Any yano-x change before promotion (milestone M4).
- Performance claims. Phase 0 numbers compare options on one machine.

## 3. Threat model and trust assumptions

| Party | Trust | What it can do |
|---|---|---|
| Message senders | Untrusted. | Submit any bytes to any topic the topic registry admits, as often as admission allows. |
| Program author | Trusted for business logic, not for node safety. The program is chosen by the chain's members and pinned in genesis by digest. | Write a program that loops, recurses, allocates, floods host calls, catches exceptions, or reads every ambient source of nondeterminism the language offers. |
| Runtime implementation (GraalJS, Chicory adapters) | Trusted Java code, a catalog plugin under ADR-011. | Bugs here can break determinism; the TCK and the pinned fingerprint are the controls. |
| Engine (Truffle/GraalJS, Chicory) | Trusted, pinned by version in the engine fingerprint. | Its semantics, including statement counts, may change between versions. |
| Node operators | As in the consensus profile. Honest members agree on genesis. | Set node-local runtime options, JVM flags and stack sizes. These must never change a result. |

What must hold: all honest nodes compute the same state root for every block (safety); no message
content can stop block production (liveness); a program cannot read anything not in its inputs or
reachable through host functions. There are no secrets in the guest: it holds no keys, and every
input is already known to every member.

Trust boundaries: guest ↔ runtime (the language sandbox), runtime ↔ host (`HostCalls`, byte
strings only, copied both ways), adapter ↔ framework (`AppStateWriter`, `AppEffectEmitter`).

Out of the model: a malicious majority of members, which consensus already covers; a trusted
runtime plugin that is malicious, which ADR-011 already treats as fully trusted code.

## 4. Pinned references

| Reference | Version | Used for |
|---|---|---|
| RFC 8949, CBOR | December 2020, §4.2.1 "Core Deterministic Encoding Requirements" | All structured data (D3). §4.2.1 requires shortest heads, no indefinite lengths, and map keys "sorted in the bytewise lexicographic order of their deterministic encodings". |
| RFC 7693, BLAKE2 | November 2015 | `blake2b_256` host function, program digest, engine fingerprint |
| FIPS 180-4, SHA-256 | August 2015 | `sha2_256` host function |
| RFC 2119 | March 1997 | MUST, MUST NOT, SHOULD in the ABI text |
| GraalVM / GraalJS (`org.graalvm.polyglot:polyglot`, `js-community`) | 25.0.2 | First runtime (M2) |
| Chicory | 1.7.5 | Second runtime (M3) |
| yano-x framework API | `main` at `00274933` (ADR-057 packages) | Adapter contract |
| Incubator specifications | `bloxbean/yano-x-executors@f578f94`: `docs/guest-abi-v1.md`, `spec/guest-abi-v1.cddl`, `spec/vectors/abi-v1.json`, `docs/runtime-spi.md`, `docs/tck.md`, `docs/phase-0-results.md`, `docs/phase-1-results.md` | Normative text for D2 to D11 |

## 5. Decisions

### D1. Three contracts, and a runtime that knows nothing about Yano

The design is split into three contracts with one owner each:

1. **Guest ABI** (D3). What a state-machine author writes against, in any language.
2. **Runtime SPI** (D2). What an engine author implements. A runtime loads a program, creates
   isolated instances, calls an exported entry point with input bytes under a compute budget, binds
   host functions from a table the host supplies, and classifies the outcome. It knows nothing
   about blocks, state, effects or Yano.
3. **`ExecutionBackedStateMachine`** (D4 to D10). The generic `AppStateMachineProvider` (provider id
   `execution`). It reads the genesis-pinned descriptor, resolves the runtime, implements the host
   functions and turns results into `AppStateWriter` writes, `AppEffectEmitter` effects and
   receipts.

So a runtime does not change when the ABI gains a host function, and the ABI does not change when
a runtime is added.

*Rejected:* one interface per language (`JsStateMachine`), which makes every runtime re-implement
Yano semantics; and a runtime that implements host functions itself, which makes host-function
behaviour and cost differ between runtimes.

### D2. The runtime SPI describes differences instead of hiding them

Package `org.yanoproject.x.executors.spi` during incubation, no dependencies, not even on Yano:

| Type | Role |
|---|---|
| `ExecutionRuntimeFactory` | `ServiceLoader` entry point: `runtimeId()`, `open(RuntimeOptions)` |
| `ExecutionRuntime` | `descriptor()`, `readiness(ExecutionProfile)`, `load(ProgramArtifact, InstanceConfig)`, `close()` |
| `RuntimeDescriptor` | runtime id, engine name and version, `MeteringModel`, `MemoryModel`, ABI versions, media types, fingerprint components |
| `MeteringModel` | compute unit name (for example `graaljs.statement`, `wasm.instruction`), exact exhaustion, whether usage can be reported |
| `MemoryModel` | `DETERMINISTIC_LIMIT` or `NOT_ENFORCED` |
| `Readiness` | `READY`, `DEGRADED` (for example interpreter-only Truffle) or `UNAVAILABLE`, with a reason |
| `ProgramArtifact`, `InstanceConfig` | media type, ABI version and bytes; required exports, host function table, memory limit |
| `LoadedProgram`, `ProgramInstance` | `exports()`, `instantiate(HostCalls)`; `invoke(export, input, computeBudget)`, poisoned after any result other than `Completed` |
| `HostFunction`, `HostCalls`, `HostAbort` | host function shape (index, name, arity, return kind); the host side of the binding; the uncatchable abort a host function throws |
| `ExecutionResult` | sealed: `Completed(output)`, `Trapped(TrapCode)`, `OutOfCompute`, `HostAborted(HostAbort)` |
| `TrapCode` | `GUEST_ERROR`, `GUEST_FAULT`, `MEMORY_LIMIT`, `CALL_DEPTH`, `BAD_RETURN` |
| `ExecutionEngineException`, `ProgramRejectedException` | any non-deterministic failure; a load failure with a deterministic code (`malformed`, `unsupported-abi`, `unsupported-media-type`, `forbidden-feature`, `unknown-import`, `missing-export`, `too-large`) |
| `ExecutionProfile` | `CONSENSUS`; `OBSERVER` and `EFFECT` reserved |

The runtime contract has seven rules: results are deterministic; metering is exact; everything
that can change a result is in the fingerprint; host aborts end the invocation even if the guest
caught them; instances are isolated; recursion is bounded deterministically; load-time validation
is deterministic. Invariants I1 to I7 and I11 restate them testably.

*Why these types:* GraalJS meters statements and cannot bound memory; Chicory meters instructions
and bounds memory exactly but its AOT compiler skips metering. `MeteringModel`, `MemoryModel` and
`Readiness` exist because the two engines differ in exactly these ways.

*Rejected:* a capability bit set ("supports metering: yes"), which hides the unit and whether
usage is observable; and a universal gas unit across runtimes, which neither engine can provide
(Phase 0).

### D3. Guest ABI v1: four entry points, seven host functions, strict canonical CBOR

**Entry points.** `apply_message` (required), and optionally `on_effect_result`, `validate` and
`query`. Each takes one byte string and returns one. A missing optional entry point means: no-op,
accept, and `unsupported`.

**Host functions,** namespace `yano`, append-only table (later ABI versions may add, never
renumber, rename or reshape):

| # | Name | Arguments | Returns | Cost on top of 100 + 1 per argument byte |
|---|---|---|---|---|
| 0 | `state_get` | key | value or absent | 1 per value byte returned |
| 1 | `state_put` | key, value | nothing | 400 + 2 per value byte |
| 2 | `state_delete` | key | nothing | 400 |
| 3 | `effect_emit` | `effect-intent` | nothing | 2000 |
| 4 | `blake2b_256` | data | 32 bytes | 200 + 32 |
| 5 | `sha2_256` | data | 32 bytes | 200 + 32 |
| 6 | `log` | level, UTF-8 message | nothing | 0 |

`state_get` sees committed state, plus earlier committed invocations of the block, plus the
invocation's own writes. Fuel is charged before a call takes effect. A call that breaks a limit
ends the invocation as an ABI violation with a fixed code:

| Limit | Value | Code |
|---|---|---|
| Key length | 1 to 1024 bytes | `invalid-key` |
| Key prefix | not `~` (0x7E) | `reserved-key` |
| Value length | at most 65,536 bytes | `value-too-large` |
| Distinct keys written per invocation | at most 4096 | `write-limit` |
| Writes and effects | only in `apply_message` and `on_effect_result` | `read-only` |
| Effect intent | canonical and valid | `invalid-effect` |
| Effects enabled on the chain | `effects.enabled` | `effects-disabled` |
| Effects | at most 64 per invocation and within the chain's `effects.*` limits | `effect-limit` |
| Hash input | at most 1 MiB | `input-too-large` |
| Log | level 0 to 3, message at most 1024 bytes of UTF-8 | `invalid-log` |

Outputs: `transition-outcome` (`{0:0}` ok, or `{0:1, 1:code}` rejected), at most 4096 bytes;
`verdict` of the same shape; `query-outcome` (`{0:0, 1:payload}` with payload at most 1 MiB, or
`{0:1, 1:"unsupported" / "invalid-request"}`). Codes match `[a-z0-9][a-z0-9._-]{0,63}`.

**Encoding.** RFC 8949 §4.2.1 core deterministic CBOR, restricted to int64 integers, byte strings,
UTF-8 text, arrays, maps with integer or text keys, true, false and null. Maps are integer-keyed;
unknown keys are errors; an optional key is omitted at its default, and present-with-default is an
error. Decoders accept exactly the encoder's bytes, so every value has one encoding. The codec is
the project's own (about 400 lines, no dependency) so that its behaviour is under the project's
control; golden vectors (15 valid, 14 invalid with the expected reason) bind every SDK to it.

Inputs carry the block info (chain id, height, timestamp, L1 slot and hash, previous hash,
proposer), the message (id, topic, sender, sequence, expiry, body, original block index) or the
effect result, and the program config bytes from the descriptor.

**Language bindings.** JavaScript: one ECMAScript script evaluated in a fresh realm per instance;
global functions over `Uint8Array`; a frozen global `yano`; a `Promise` or any non-`Uint8Array`
return is a `bad-return` trap; `Math.random` throws; `Date`, `Intl`, `performance`,
`SharedArrayBuffer`, `Atomics`, `WeakRef` and `FinalizationRegistry` are absent; no I/O, host
classes, threads or polyglot access. WebAssembly: exports `memory`, `yano_alloc(len) -> ptr` and
each entry point as `(ptr, len) -> i64` returning `(ptr << 32) | len`; imports only from `yano`;
a load-time gatekeeper rejects unknown imports, floats, SIMD, threads and atomics, memory64,
multiple memories, a start function and a memory maximum above the descriptor limit.

Ed25519 verification, L1 observations, transition kernels, privileged messages and events are not
in v1. Each needs only new entry points or host functions, never an SPI change.

*Rejected:* JSON (no canonical form, and number handling differs between engines); an existing
CBOR library (common decoders accept non-canonical input, so two SDKs could accept different byte
sets for the same value; a strict decoder of our own makes "accepted" mean the same in every
language); writes returned in the output instead of through host functions (no read-your-writes
inside an invocation, and the output size grows with the writes).

### D4. One invocation per message on a fresh instance; failures are isolated

For each block the adapter calls `on_effect_result` for each committed effect result, in Yano's
delivery order, then `apply_message` for each message whose topic does not start with `~`, in
block order. Every invocation runs on a fresh instance, and a guest must not rely on memory
carrying between invocations. Writes of a committed invocation are visible to later invocations in
the block.

| Outcome | Writes and effects | Receipt kind, code |
|---|---|---|
| `transition-ok` | committed | none |
| `transition-rejected` | discarded | 1 rejected, the guest's code |
| Output not a canonical `transition-outcome` | discarded | 5 ABI violation, `bad-output` |
| Trapped | discarded | 2 trapped, the trap code |
| Compute exhausted | discarded | 3 out of compute, `compute` |
| Host fuel exhausted | discarded | 4 out of host fuel, `host-fuel` |
| Host function rejected a call | discarded | 5 ABI violation, the host's code |
| Non-deterministic engine failure | the block is not applied; the node stops | none |

Every outcome but the last is a deterministic function of program, input, budgets and state.

*Rejected:* one invocation per block, where any sender could make a whole block fail; and a
long-lived instance per chain, where guest memory becomes hidden, unhashed state. A per-block
instance replaced after any failure is also deterministic and may be cheaper; Phase 2 measures it
(Q1). Phase 0 puts the per-invocation cost at about 0.11 ms of context creation, so roughly 22 ms
for a 200-message block, before any reuse of the parsed program.

### D5. Two budgets per invocation; usage is not consensus data

**Compute** is in the pinned runtime's own unit, and the runtime stops the guest at exactly the
budget. **Host fuel** is in ABI units (D3), identical for every runtime. Budgets are set separately
for transitions, `validate` and `query`.

How much compute a guest used is not portable: GraalJS can report only exhaustion, not statements
used. So receipts record the kind of failure, never the amount used, and no consensus data depends
on usage.

*Rejected:* wall-clock timeouts on the consensus path (non-deterministic); a single budget with the
runtime converting host work into its own units (neither engine can add work to its own meter
portably; Phase 0 showed 1,000 host calls cost only 12,016 WASM instructions).

### D6. Everything consensus-relevant is pinned in genesis

The execution descriptor is canonical CBOR in the chain settings
(`machines.execution.descriptor-cbor-hex`):

| Key | Field |
|---|---|
| 0 | descriptor version (1) |
| 1 | runtime id (`graaljs`, `chicory`) |
| 2 | engine fingerprint: blake2b-256 of the canonical CBOR map of the runtime's fingerprint components |
| 3 | program media type (`text/javascript`, `application/wasm`) |
| 4 | program digest: blake2b-256 of the program bytes |
| 5 | ABI version |
| 6 | budgets: compute and host fuel for transitions, `validate` and `query` |
| 7 | guest memory limit in bytes, for runtimes that enforce one |
| 8 | program config bytes, passed to every entry point |
| 9 | whether receipts are written |

Fingerprint components must include the runtime id, engine version, metering unit, and every
option or internal limit that can change a result (for example a call-depth limit). At startup the
adapter recomputes the fingerprint from the installed runtime, hashes the program (from
`program-path` or `program-hex`) and refuses to start if either differs from the descriptor, or if
the runtime reports `UNAVAILABLE` (or `DEGRADED` without the node-local `allow-degraded`).

`RuntimeOptions` (`machines.execution.runtime-options.*`) are node-local and may tune performance
only, never semantics.

*Consequence:* upgrading the engine (for example GraalJS 25.0.2 to a later release) changes the
fingerprint, so it is a chain upgrade. A height-gated schedule for descriptors is needed before
graduation (Q5).

*Rejected:* pinning only a runtime name and version string (misses semantic options and internal
limits); a program referenced by URL (fetch is not deterministic; the digest is what matters).

### D7. Engine failures stop the node; they never become results

JVM stack overflow, heap exhaustion and engine bugs depend on the node, not on the inputs. A
runtime throws `ExecutionEngineException` for them, and the adapter lets it fail the block, which
stops the chain role under the CONSENSUS trust tier of ADR-011. Turning such a failure into a
discarded transition would let nodes with different stack sizes or heaps compute different roots.

Because of this rule, a runtime must bound recursion itself (I11): otherwise one deeply recursive
message stops every node. The TCK runs the same recursion on 512 KiB, 4 MiB and 64 MiB thread
stacks and requires the same deterministic result on all three.

### D8. Effects go through the host, under mirrored limits

`effect_emit` records an intent. The adapter emits it through `AppEffectEmitter` only if the
invocation commits, and sets the effect's source message id itself (the current message in
`apply_message`, none in `on_effect_result`); the guest cannot set it. An intent without a
finality gate gets the chain's default gate.

`FxKernel` throws `EffectLimitExceededException` at `emit`, possibly after earlier effects of the
same call are already in. So the host session checks the chain's `effects.*` settings first
(`effects.max-per-block` 256, `effects.max-payload-bytes` 16384, `effects.max-expiry-blocks`
100000 and `effects.result-window-blocks` 100000 by default) and turns a violation into a discarded transition. An `emit` failure after that check
means host and framework disagree, and the block fails as a whole (D7).

### D9. Receipts under `~exec/`; guests may not write any `~` key

When the descriptor enables receipts, a discarded transition writes
`receipt = {0: 1, 1: height, 2: kind 1..5, 3: code}` at `~exec/r/<message id>` (for
`apply_message`) or `~exec/fx/<blake2b-256 effect id hash>` (for `on_effect_result`). Guests are
forbidden every key that starts with `~`. Today only `~fx/` is reserved in the trie
(`FxKeys.RESERVED_PREFIX`); after promotion `~exec/` should be reserved next to it (Q3).

*Rejected:* no receipts at all (a sender cannot learn why a message had no effect without trusting
one node's logs); receipts with compute used (not portable, D5).

### D10. Admission and query are read-only and fail closed

`validateForBlock` calls `validate` in read-only mode against committed state. Anything other than
a canonical accept, including traps and exhaustion, rejects with `AdmissionResult.reject`, using
the guest's code or `execution-<outcome>`. Admission is advisory: the transition still runs in the
block. `query` maps the guest's error to `AppQueryException` `UNSUPPORTED` or `INVALID_REQUEST`.
Neither may write or emit (`read-only` violation).

### D11. The TCK is the conformance gate for a runtime

A runtime is conformant when it passes the TCK and Yano's `StateMachineConformance` through the
adapter. The TCK drives the runtime with the same host session class the adapter uses in a node.
The runtime author writes 16 programs in the guest language with normative behaviour (counter,
effects, echo, reject, trap after write, spin, host flood, catch host abort, oversize value,
reserved key, write in validate, deep recursion, missing export, unknown import, static state,
environment probe). The 25 checks cover descriptor validity and stability, readiness, load
validation, byte transparency, exact outputs, exact exhaustion of both budgets (host fuel aborts on
exactly call `floor(100000 / 101) + 1` for a 100,000 budget), uncatchable aborts, ABI violations,
recursion on three stack sizes, memory bounds, instance isolation, environment stability across
instances, time and runtime opens, a 120-message determinism corpus and eight concurrent threads.

A result is PASS, WARN or FAIL. WARN marks a known, documented gap: `memory.bounded` for runtimes
with `NOT_ENFORCED` memory, and `load.unknown-import` for dynamic languages that cannot declare
imports (an unknown host function then traps as a guest error when called).

The TCK also tests itself: a guest that leaks `System.nanoTime`, a runtime without a depth limit
and a non-canonical output each fail exactly the intended check.

### D12. Two runtimes before promotion

GraalJS is the first runtime (M2) and Chicory the second (M3). M3 must not change the SPI without
re-running the GraalJS TCK, and the same example programs written in JavaScript and Rust must give
identical roots for the same message log. The SPI is promoted only after both pass (M4).

*Rejected:* promote after GraalJS alone (the abstraction would encode GraalJS's shape; Phase 0
already found three places where the engines differ).

### D13. Incubate outside yano-x; promote by contribution kind

During incubation the modules (`execution-spi`, `guest-abi`, `tck`, `adapter`, the runtimes, guest
SDKs and examples) live in `bloxbean/yano-x-executors` and build only against published yano-x
artifacts, like an outside developer. If the design needs a host change, it shows up as a specific
yano-x PR.

At promotion (M4):

- The SPI and the ABI contracts move into yano-x, the SPI under `framework/api`.
- A new `execution-runtime` contribution kind, trust tier CONSENSUS, is declared in
  `framework/api`'s `META-INF/yano/contribution-kinds/org.yanoproject.x.framework.api.json` and
  bound in `framework/host-adapter` (`AppLedgerKindBindings`). This is a yano-x change, not a Yano
  core change.
- The adapter resolves runtimes through a bridge like `AuthenticatedMapValidatorResolver`, with
  the engine fingerprint as the pinned digest, instead of `ServiceLoader` in its own class loader.
- The adapter moves to `state-machines/`, the guest SDKs to `sdk/`, the TCK next to
  `StateMachineConformance`.
- The incubator keeps only the runtimes, guest SDKs and examples.

*Rejected:* experimental modules inside yano-x (the ZK precedent); a runtime registry outside the
plugin catalog (ADR-011 requires every optional behaviour to cross the catalog boundary; AGENTS.md
forbids raw `ServiceLoader` activation in yano-x).

### D14. GraalJS runtime requirements (M2)

- `SandboxPolicy.CONSTRAINED`, no host class lookup, I/O, threads, native or polyglot access; the
  prelude of D3 removes ambient nondeterminism.
- Compute is a statement limit, which Phase 0 showed deterministic across JIT, interpreter and
  JVMs for a pinned GraalJS version. The GraalJS version is a fingerprint component.
- `readiness(CONSENSUS)` is `DEGRADED` on interpreter-only Truffle (about 70 times slower). A stock
  JDK gets the optimizing runtime with `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI
  --upgrade-module-path=<Graal compiler jars>` through `JAVA_OPTS`, which `yano.sh` and the Docker
  entrypoint already honour.
- A deterministic call-depth limit of its own, in the fingerprint. GraalJS turns Java stack
  overflow into a JS `RangeError` at a depth that depends on stack size and compilation, which
  fails `recursion.deterministic`. Candidate techniques: source instrumentation of function entry,
  or a guarded counter in an instrument; M2 picks one.
- `MemoryModel.NOT_ENFORCED`: GraalJS cannot bound guest heap under `CONSTRAINED`. A guest can
  exhaust the node heap. That is an engine failure (D7), so the node stops rather than diverges,
  but it is a denial of service by a program author (R1).

### D15. Chicory runtime requirements (M3)

- The load-time gatekeeper of D3, which Chicory's parser makes simple (Phase 0 found all four
  float opcodes in a test module).
- Fuel through the interpreter's `ExecutionListener`, one unit per instruction, exact. AOT is not
  used for metered execution until modules are instrumented with gas at basic-block boundaries
  (Parity `wasm-instrument` or NEAR `finite-wasm` style), which is M5 work.
- `MemoryModel.DETERMINISTIC_LIMIT`: `memory.grow` past the descriptor limit returns -1.

### D16. CONSENSUS profile only

Only the CONSENSUS profile is built. `OBSERVER` and `EFFECT` remain reserved names in
`ExecutionProfile` so that a later ADR can add them without an SPI break. External data still
enters only through Yano's authenticated observation protocol, never through a guest.

## 6. Invariants

Each invariant names the decisions it constrains and the check that tests it (TCK check names, or
adapter tests in the incubator).

| ID | Invariant | Decisions | Test |
|---|---|---|---|
| I1 | An invocation's result depends only on program digest, engine fingerprint, input, budgets and the host-call results. | D2, D6 | `determinism.corpus`, `concurrency.instances`, `environment.stable` |
| I2 | A guest stops at exactly its compute budget, with or without JIT and on any JVM. | D5 | `compute.exhaustion` (three repeats) |
| I3 | Host fuel is charged before a call takes effect, identically for every runtime, and exhaustion happens on the same call. | D3, D5 | `host.fuel-exhaustion`; `HostSessionV1Test` |
| I4 | A host abort ends the invocation even if the guest caught it. | D2, D3 | `host.abort-uncatchable` |
| I5 | A failed invocation leaves no writes and no effects, except its receipt when receipts are on. | D4, D9 | `trap.guest-error`, `transition.reject`; adapter isolation and receipts tests |
| I6 | The adapter refuses to start on a fingerprint or digest mismatch, or an `UNAVAILABLE` runtime. | D6 | adapter pinning tests |
| I7 | A non-deterministic engine failure never becomes a result. | D7 | `recursion.deterministic` (engine failure on every stack size also fails) |
| I8 | Node-local options and JVM settings never change a result. | D6 | `descriptor.stable`; `recursion.deterministic` across stack sizes |
| I9 | Guests cannot write any `~` key and cannot set an effect's source message id. | D8, D9 | `host.reserved-key`; adapter effects test |
| I10 | Every decoder accepts exactly the canonical encoding of a value. | D3 | golden vectors (14 invalid inputs), 2,000 random round trips, rejection tests |
| I11 | Recursion is bounded deterministically by the runtime. | D2, D7, D14 | `recursion.deterministic` on 512 KiB, 4 MiB, 64 MiB stacks |
| I12 | `validate` and `query` never write or emit, and `validate` fails closed. | D10 | `host.read-only`; adapter admission and query tests |
| I13 | Within a block, an invocation sees the writes of earlier committed invocations; a retried block sees none of its failed attempt. | D4 | adapter read-your-writes and retried-block tests |
| I14 | New instances share no guest state. | D2, D4 | `isolation.instances` |

## 7. Findings about the Yano API

Found while building the adapter. None blocks incubation; all affect promotion.

1. **`AppStateWriter` has no read-your-writes.** It shows committed state only, so the adapter keeps
   a block overlay. The overlay must be cleared when `apply` ends and when a callback throws, or a
   retried block reads its own failed attempt. A test covers this, and a mutation check showed the
   test fails without the reset.
2. **`AppEffectEmitter.pendingCount()` counts open CHAIN effects, not effects emitted this block.**
   The adapter tracks per-block emission itself. In a composite chain several components share the
   block's budget, which the adapter cannot see (Q4).
3. **Effect limits must be mirrored** (D8), because `FxKernel` throws at `emit`.
4. **Only `~fx/` is reserved in the trie** (Q3).
5. **`validate` reasons.** A guest's rejection code becomes the admission reason. Yano already
   bounds what remote callers see, and codes are restricted to `[a-z0-9][a-z0-9._-]{0,63}`, so a
   guest cannot leak free text.

## 8. Compatibility

- **During incubation:** no change to yano-x wire formats, storage, APIs or builds.
- **State:** an execution-backed chain writes guest keys (never starting with `~`) and receipts
  under `~exec/`. The keyspace layout is the guest's.
- **Settings:** `machines.execution.descriptor-cbor-hex`, `program-path` or `program-hex`,
  `runtime-options.*` (node-local), `allow-degraded` (node-local). The `effects.*` settings are
  read, not changed.
- **ABI versioning:** the host table is append-only. A new entry point, host function or field
  needs a new ABI version; a v1 decoder rejects unknown keys. A runtime declares the ABI versions it
  supports.
- **Engine upgrades** change the fingerprint and are chain upgrades until a descriptor schedule
  exists (Q5).
- **Java packages:** `org.yanoproject.x.executors.*` during incubation, moved to
  `org.yanoproject.x.framework.*` at promotion. The framework is prerelease, so this is an
  intentional break without forwarding classes, as in ADR-057.
- **Plugin manifest:** M4 adds one contribution kind to the framework's kind descriptor.

## 9. Milestones

| ID | Work | Entry gate | Exit criteria |
|---|---|---|---|
| M0 | Phase 0 spikes | — | Done (`9d0a8ba`): §1.1 |
| M1 | Phase 1: ABI v1, SPI, TCK, adapter draft | M0 | Done (`f578f94`): build passes; TCK passes on the reference runtime with only `memory.bounded` as WARN; broken runtimes fail the intended checks; adapter conformance roots equal a Java machine |
| M2 | GraalJS runtime, JS SDK, counter and escrow examples | this ADR reviewed (Proposed is enough to start incubator work) | TCK passes (WARN only `memory.bounded`, `load.unknown-import`), I1–I14; `StateMachineConformance` passes with roots identical across two class loaders and a restart; two-node devnet agrees on roots; per-invocation vs per-block instance measured (Q1) |
| M3 | Chicory runtime, gatekeeper, Rust SDK, the same examples in Rust | M2 | TCK passes with no WARN for memory; JS and Rust examples give identical roots for one message log; any SPI change re-verified on GraalJS |
| M4 | Promotion into yano-x (D13) | M3; this ADR Accepted; Q3–Q6 decided | yano-x `check` passes with the new kind; catalog-loaded runtime passes the TCK; `~exec/` reserved if decided |
| M5 | Graduation: AOT with injected gas, performance budgets, hostile multi-node tests | M4 | Considered for a yano-x plugin pack in a later ADR |

## 10. Verification and test vectors

Values derived from the code under test prove little on their own, so verification leans on
independent evidence:

- **Golden vectors.** Today `spec/vectors/abi-v1.json` is generated by the Java reference codec, so
  it is not yet independent evidence. M2 and M3 must decode and re-encode every vector with
  independently written codecs (the JS SDK and the Rust SDK, each using its own CBOR code), and
  check the CDDL with an independent CDDL tool. Phase 1 could not run a CDDL validator (installing
  one was not possible in the sandbox); that check is outstanding.
- **Differential runs.** Same message log through the JS program, the Rust program and a plain Java
  machine; roots must be identical at every height (M2, M3). Phase 0 and Phase 1 already did this
  JS against Java and adapter against Java.
- **Hostile corpus.** The TCK programs spin, flood host calls, catch aborts, recurse without limit,
  write reserved keys and oversize values, and probe the environment.
- **Negative TCK self-tests.** Broken runtimes must fail; a TCK that passes everything proves
  nothing.
- **Cross-JVM and cross-stack.** Metering and recursion are checked on Oracle GraalVM and a stock
  JDK, with and without the optimizing runtime, and on three stack sizes.
- **Multi-node.** A two-node devnet in M2; hostile multi-node tests in M5.

Passing tests are not a security review. M5 includes one before any plugin-pack release.

## 11. Risks

| ID | Risk | Mitigation |
|---|---|---|
| R1 | GraalJS guest exhausts the node heap (D14). | Node stops instead of diverging (D7); documented WARN; program authors are chain members; Chicory has an exact bound. Investigate a Truffle allocation limit or an isolate in M2. |
| R2 | GraalJS statement counts change between releases. | Engine version in the fingerprint; upgrade is a chain upgrade (Q5). |
| R3 | Chicory `ExecutionListener` is marked experimental and unsupported. | Pin Chicory; gas instrumentation (M5) removes the dependency. |
| R4 | Interpreter-only Truffle is about 70 times slower. | `DEGRADED` readiness refuses CONSENSUS unless `allow-degraded`; documented JVMCI flags. |
| R5 | Per-invocation instances cost about 0.11 ms each. | Measure per-block instances in M2 (Q1); M2 shares parsed program source through one GraalJS `Engine`. |
| R6 | 35 MB GraalJS bundle. | Never in yano-x Central; separate plugin pack after graduation. |
| R7 | A runtime bug passes the TCK and diverges in production. | Two independent runtimes, differential examples, pinned fingerprints, multi-node tests (M5). |
| R8 | Effect budgets in composite chains are shared invisibly (finding 2). | Standalone chains only until Q4 is decided. |

## 12. Open questions

| ID | Question | Options | Lean |
|---|---|---|---|
| Q1 | Fresh instance per invocation, or one instance per block replaced after any failed invocation? | Per invocation (strictest isolation, about 0.11 ms each); per block (cheaper, also deterministic because failures are) | Decide on M2 numbers; keep per invocation unless the cost is material at realistic block sizes. |
| Q2 | Receipts on by default, and pruned? | On by default; off by default; on with a retention window | On by default, no pruning in v1; pruning needs a deterministic rule and belongs with snapshot work. |
| Q3 | Reserve `~exec/` in the framework next to `~fx/`? | Reserve in `FxKeys`-like framework code; leave it adapter-private | Reserve at promotion, once the adapter is framework code. |
| Q4 | How do execution-backed machines share effect budgets in composite chains? | Framework exposes per-block emission count; per-component budget split in settings; standalone only | Ask the framework for a per-block count (small API addition at promotion); standalone only until then. |
| Q5 | How are engine upgrades scheduled? | Chain upgrade only; height-gated descriptor schedule like `ActivationSchedule` | Height-gated schedule before graduation (M5 gate). |
| Q6 | Where do runtimes live after promotion? | Separate `execution-runtime` kind (D13); bundled with the adapter | Separate kind, so third-party runtimes do not depend on the adapter's release. |
| Q7 | Should execution-backed machines become transition kernels in composite cascades? | Plain `AppStateMachine` only; add a kernel entry point in ABI v2 | Plain machine in v1; revisit after M3 with a concrete use case. |

## 13. Alternatives considered (overall)

| Alternative | Why not |
|---|---|
| A GraalJS-specific state machine | Not an SPI; third parties could not add runtimes (constraint 1). |
| Build the SPI with only GraalJS | The abstraction would encode one engine's shape (D12). |
| Extend CEL or the binding IR to cover transitions | ADR-031.3 §13.1 keeps the IR closed and versioned; general transitions need a general language. |
| Sandboxed Java plugins | ADR-011 explicitly does not sandbox Java; the JVM offers no deterministic metering. |
| Wall-clock limits | Not deterministic (§5, D5). |
| Experimental modules inside yano-x | ZK precedent; 35 MB footprint; incubation tests the published API as an outsider would (D13). |
| Scripted policies | ADR-031.3 §13.1. |

## 14. Related findings (out of scope)

- `adr/README.md` has no row for ADR-057.
- The issue's OBSERVER and EFFECT profiles, GraalPy, MoveVM and Plutus/UPLC each need their own
  evaluation.

## Revision history

- r1 (2026-11-21): initial version.
