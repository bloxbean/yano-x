# Runtime SPI

Status: **ADR-058 (Accepted).** This is what an engine author implements to run Yano X state
machines written in a new guest language. The SPI is the `org.yanoproject.x.framework.api.execution`
package of `yano-x-appchain-api` (`framework/api`). A runtime ships as a plugin bundle that
contributes the `execution-runtime` kind; the `execution` state machine (`yano-x-execution`)
runs programs on it.

## Responsibilities

A runtime moves bytes between a guest and the host. It:

- loads and validates a program, rejecting anything it cannot run deterministically;
- creates isolated instances;
- calls an exported entry point with an input, under a compute budget it meters exactly;
- exposes host functions to the guest by name, from a table the host supplies;
- classifies the outcome as one of four deterministic results, or throws for a non-deterministic
  failure.

It knows nothing about blocks, state, effects or Yano. Entry-point names, encodings and host
function meaning are defined by the guest ABI and implemented by the host. So a runtime does not
change when the ABI gains a host function, and the ABI does not change when a runtime is added.

## Types

| Type | Role |
|---|---|
| `ExecutionRuntimeFactory` | the `execution-runtime` contribution; `runtimeId()` and `open(RuntimeOptions)` |
| `ExecutionRuntime` | an open engine: `descriptor()`, `readiness(profile)`, `load(artifact, config)` |
| `RuntimeDescriptor` | id, engine name and version, `MeteringModel`, `MemoryModel`, ABI versions, media types, fingerprint components |
| `MeteringModel` | compute unit name; exact exhaustion; whether usage can be reported |
| `MemoryModel` | `DETERMINISTIC_LIMIT` or `NOT_ENFORCED` |
| `Readiness` | `READY`, `DEGRADED` (for example interpreter-only Truffle) or `UNAVAILABLE`, with a reason |
| `ProgramArtifact` | media type, ABI version, bytes |
| `InstanceConfig` | required exports, host function table, memory limit; fixed at load |
| `LoadedProgram` | validated program; `exports()`, `instantiate(HostCalls, computeBudget)` |
| `ProgramInstance` | `invoke(export, input)` under the instance's `computeBudget()`, metered from zero each time; poisoned after any non-completed result |
| `HostFunction`, `HostCalls` | host function shape (name, arity, return kind) and the host side of the binding |
| `ExecutionResult` | `Completed`, `Trapped(TrapCode)`, `OutOfCompute`, `HostAborted(HostAbort)` |
| `HostAbort` | thrown by the host to end an invocation; must not be catchable by the guest |
| `ExecutionEngineException` | any non-deterministic failure |
| `ProgramRejectedException` | load failure with a deterministic code |
| `ExecutionProfile` | `CONSENSUS`; `OBSERVER` and `EFFECT` are reserved |

## Contract

1. **Results are deterministic.** A result depends only on the program, the input, the compute
   budget and the host-call results. JVM stack depth, heap exhaustion and engine bugs are not
   results; throw `ExecutionEngineException`.
2. **Metering is exact.** The guest stops at exactly the budget, with or without JIT compilation
   and on any JVM. Report usage only if the engine can (`MeteringModel.reportsUsage`); usage is
   diagnostic and never consensus data. The budget is fixed when an instance is created and every
   invocation of the instance gets all of it: the meter restarts at zero. (GraalJS sets its
   statement limit when a context is built, so a per-invocation budget would not be portable.)
3. **Everything that can change a result is in the fingerprint.** Put engine version, metering
   unit, semantic options and internal limits (such as a call-depth limit) in
   `fingerprintComponents()`. The execution state machine hashes them into the fingerprint pinned in genesis and
   refuses to start on a mismatch. `RuntimeOptions` are node-local and may tune performance only.
4. **Host aborts end the invocation.** When `HostCalls.call` throws `HostAbort`, stop the guest and
   return `HostAborted` with that exact object, even if guest code caught it.
5. **Instances are isolated.** A new instance shares no guest state with any other. Within one
   instance, guest memory persists across invocations until the instance is poisoned or closed.
6. **Recursion is bounded deterministically.** A guest that recurses without limit must get the
   same result on every thread stack size, so the runtime enforces its own call-depth limit
   (`TrapCode.CALL_DEPTH`) or meters frames.
7. **Load-time validation is deterministic.** The same artifact is accepted or rejected with the
   same code on every node with the same fingerprint. Reject unknown imports when the language
   declares imports statically, and reject a program without the required exports.

## Writing a runtime

1. Implement `ExecutionRuntimeFactory` and contribute it from a schema-v1 plugin bundle: a manifest
   entry `{"kind": "execution-runtime", "name": "<runtime id>", "provider": "<factory class>"}`
   and `META-INF/services/org.yanoproject.x.framework.api.execution.ExecutionRuntimeFactory`. Do not
   embed `org.yanoproject.x.framework.api` classes in the bundle; the host provides them.
2. Bind each `HostFunction` of the `InstanceConfig` table to the guest by name, and route calls to
   `HostCalls.call(index, args)`. Copy bytes in both directions; never hand guest memory to the
   host or keep host arrays.
3. Write the TCK programs in your guest language (see [tck.md](tck.md); `MEMORY_HOG` only if
   the runtime claims `DETERMINISTIC_LIMIT`), and run
   `RuntimeTck.builder(factory, programs).build().dynamicTests()` from a JUnit `@TestFactory`.
4. Run Yano's `StateMachineConformance` on the execution state machine with your runtime and a
   real program.
5. Operators install the bundle in `yano.plugins.directory` and add its bundle id to
   `yano.plugins.allow-list`. The host resolves runtimes only from explicitly allow-listed bundles
   (`CatalogExecutionRuntimeResolver`), and the state machine refuses to start unless the open
   runtime's engine fingerprint equals the one pinned in the chain's descriptor.

## Why the SPI looks like this

- **Two runtimes shape it.** GraalJS meters statements and cannot bound memory; Chicory meters
  instructions and bounds memory exactly, but its AOT compiler skips metering. `MeteringModel`,
  `MemoryModel` and `Readiness` exist because the two engines differ in exactly these ways
  (incubation [Phase 0](https://github.com/bloxbean/yano-x-executors/blob/main/docs/phase-0-results.md)).
- **Generic host binding.** Host functions are described by shape (arity, return kind), with every
  argument a byte string. A runtime binds a table, not a fixed list, so host functions belong to
  the host.
- **Separate host fuel.** Neither engine can charge host work to its own compute meter in a
  portable way (GraalJS has no API to add statements). Host fuel is therefore a second budget in
  ABI units that the host counts identically for every runtime.
- **A budget per instance, not per call.** Incubation Phase 2 found that GraalJS fixes a context's statement
  limit when the context is built and can only reset the count. The state machine already uses one
  budget per entry-point kind, so the budget moved from `invoke` to `instantiate`.
- **Pin the engine version from the jars.** An engine's own version API may not report the
  release: GraalJS's `Engine.getVersion()` says `Development Build` on a stock JDK. Both runtimes
  read the version from their jars' metadata and refuse to open with any version but the one
  they were verified against.
- **Unchanged by the second runtime.** Chicory (incubation Phase 3) needed no SPI change; see
  [phase-3-results.md](https://github.com/bloxbean/yano-x-executors/blob/main/docs/phase-3-results.md).
- **Results, not exceptions, for guest failures.** A trap, exhaustion or abort is an ordinary,
  deterministic outcome that the state machine turns into a discarded transition. Only non-deterministic
  failures are exceptions.
