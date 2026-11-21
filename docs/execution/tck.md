# Runtime TCK

`yano-x-execution-tck` (`sdk/execution-tck`) checks an `ExecutionRuntimeFactory` against the runtime
SPI contract and guest ABI v1. It drives the runtime with the same host session class the execution
state machine uses in a node
(`HostSessionV1`), so a runtime is tested against the host behaviour it will meet in production.

```java
@TestFactory
List<DynamicTest> graalJsConforms() {
    return RuntimeTck.builder(new GraalJsRuntimeFactory(), new GraalJsTckPrograms()).build().dynamicTests();
}
```

A result is PASS, WARN or FAIL. WARN marks a known gap that does not fail conformance: `memory.bounded`
for runtimes that cannot bound guest memory, `load.unknown-import` for languages without static
imports, and `readiness.consensus` for a `DEGRADED` runtime.

## Programs

The runtime author writes these in the runtime's guest language. Their behaviour is normative; the
Javadoc of `TckProgram` has the same text. `be64(n)` is an 8-byte big-endian integer.

| Program | Behaviour |
|---|---|
| `COUNTER` | `apply_message`: `k = "c/" ++ body`, store `be64(n + 1)`. `validate`: reject `empty-body` for an empty body. `query`: path `count` returns `be64(n)` for `"c/" ++ params`, other paths `unsupported`. |
| `EFFECTS` | `apply_message`: emit type `tck.echo`, payload body, scope hex(message id), result policy 1, expiry 10. `on_effect_result`: store `[outcome]` at `"r/" ++ scope`. |
| `ECHO` | `apply_message` returns the body bytes unchanged. |
| `REJECT` | store body at `x`; reject `tck.reject` if the body starts with byte 0. |
| `TRAP_AFTER_WRITE` | store body at `t`, then stop with an unhandled guest error. |
| `SPIN` | loop forever without host calls. |
| `HOST_FLOOD` | loop forever calling `state_get("k")`. |
| `CATCH_HOST_ABORT` | 100,000 times `state_put("k", 1024 zero bytes)`, catching and ignoring every error where the language allows it. |
| `OVERSIZE_VALUE` | `state_put("k", 65,537 zero bytes)`. |
| `RESERVED_KEY` | `state_put("~fx/x", "v")`. |
| `WRITE_IN_VALIDATE` | `validate` calls `state_put("k", "v")`. |
| `DEEP_RECURSION` | unbounded non-tail recursion. |
| `MISSING_EXPORT` | exports only `validate`. |
| `UNKNOWN_IMPORT` | calls host function `net_fetch`. |
| `STATIC_STATE` | increments a global counter; a body starting with byte 0 is rejected (`tck.static-reject`), any other stores `be64(counter)` at `g`. Adapter tests use it for the instance-scope rule. |
| `ENVIRONMENT_PROBE` | reads every ambient source of nondeterminism the language offers, stores what it saw at `env`. |
| `MEMORY_HOG` | only for `DETERMINISTIC_LIMIT` runtimes: grows memory until it cannot, stores the final size in 64 KiB pages as `be64` at `m` (or ends with a memory-limit trap). |

## Checks

| Check | Requirement |
|---|---|
| `descriptor.valid` | id matches the factory; ABI v1 and the programs' media type declared; `engine.version` in the fingerprint; exact metering |
| `descriptor.stable` | two opens report the same fingerprint components |
| `readiness.consensus` | not `UNAVAILABLE` for CONSENSUS (`DEGRADED` is a WARN) |
| `load.exports` | exports are reported exactly; invoking a missing export throws `IllegalArgumentException` |
| `load.missing-export` | rejected with `missing-export` |
| `load.unknown-import` | rejected with `unknown-import`, or (dynamic languages) traps as a guest error without reaching the host, which is a WARN |
| `bytes.transparent` | empty, single-zero, all-256 and 64 KiB bodies come back unchanged |
| `counter.apply`, `counter.validate`, `counter.query` | exact writes and outputs |
| `effects.emit-and-result` | the emitted intent and the result write are exact |
| `transition.reject` | canonical `transition-rejected` with the guest's code |
| `trap.guest-error` | `Trapped(GUEST_ERROR)` (a JS `throw`, a WASM `unreachable`); the instance is poisoned |
| `compute.exhaustion` | `OutOfCompute` at the budget, three times; usage equals the budget when reported; poisoned |
| `compute.per-invocation` | the budget is fixed per instance and the meter restarts for every invocation: with the smallest budget that completes one `COUNTER` apply, five applies on one instance all complete |
| `host.fuel-exhaustion` | `HostAborted(HOST_FUEL_EXHAUSTED)` on exactly call `floor(100000 / 101) + 1`, three times |
| `host.abort-uncatchable` | a guest that swallows host errors is still aborted |
| `host.value-limit`, `host.reserved-key`, `host.read-only` | `HostAborted(ABI_VIOLATION)` with the right code |
| `recursion.deterministic` | the same deterministic result on 512 KiB, 4 MiB and 64 MiB thread stacks; an engine failure on every stack size also fails, because one message could stop every node |
| `memory.bounded` | WARN if the runtime does not bound memory (`NOT_ENFORCED`); otherwise `MEMORY_HOG` must stop at exactly `floor(limit / 64 KiB)` pages in three fresh instances |
| `isolation.instances` | new instances start fresh; one instance keeps guest memory between invocations |
| `environment.stable` | `ENVIRONMENT_PROBE` writes the same bytes across instances, 60 ms apart, and across two runtime opens, the second under another JVM default locale (`tr-TR`) and time zone |
| `determinism.corpus` | 120 messages: fresh instances, one shared instance and a second runtime open give identical state |
| `concurrency.instances` | eight threads running the corpus concurrently match the sequential result |

## Self-test

The TCK tests itself against the reference runtime (`org.yanoproject.x.execution.tck.reference`,
Java guests; for tests only, never a production runtime):

- `ReferenceRuntimeTckTest`: every check passes, and `memory.bounded` is the only WARN.
- `TckDetectsBrokenRuntimesTest`: a guest that leaks `System.nanoTime`, a runtime without a depth
  limit, a non-canonical output and a meter that does not restart per invocation each fail exactly
  the check meant to catch them.

`fixtures/execution-runtime-conformance` packages the reference runtime as a manifested plugin
bundle and loads it through the host's directory catalog: the catalog-resolved runtime passes the
TCK through the host's facades, and the execution state machine on it gives the same roots as on
the runtime used directly.

The GraalJS and Chicory runtimes live in the incubator, [`yano-x-executors`](https://github.com/bloxbean/yano-x-executors). The GraalJS
runtime passes (`runtime-graaljs`, `GraalJsRuntimeTckTest`), with the TCK programs in JavaScript;
its WARNs are `memory.bounded` and `load.unknown-import`. The Chicory runtime passes every check
with no WARN (`runtime-chicory`, `ChicoryRuntimeTckTest`), with the TCK programs in Rust.
`TckDetectsBrokenChicoryTest` shows that a Chicory runtime giving guests more memory than
configured fails exactly `memory.bounded`.
