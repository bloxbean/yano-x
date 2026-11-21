# Execution-backed state machines

[ADR-058](../../adr/058-execution-runtime-spi-and-execution-backed-state-machines.md) lets an app
chain run its state machine as a guest program (JavaScript, WebAssembly, ...) on a pluggable
execution runtime, instead of Java code compiled into a plugin. The program and the runtime's
engine fingerprint are pinned in the chain's genesis settings.

| Part | Where | For whom |
|---|---|---|
| Runtime SPI | `org.yanoproject.x.framework.api.execution` in `yano-x-appchain-api` | engine authors: [runtime-spi.md](runtime-spi.md) |
| Guest ABI v1 | `yano-x-execution-abi` (`state-machines/execution-abi`) | program and SDK authors: [guest-abi-v1.md](guest-abi-v1.md) |
| Runtime TCK | `yano-x-execution-tck` (`sdk/execution-tck`) | engine authors: [tck.md](tck.md) |
| Execution state machine | `yano-x-execution` (`state-machines/execution`), bundle `org.yanoproject.x.execution`, id `execution` | chain operators |

Runtimes are plugin bundles that contribute the `execution-runtime` kind (trust tier CONSENSUS). The
GraalJS (JavaScript) and Chicory (WebAssembly) runtimes, the guest SDKs and the examples are
developed in [`bloxbean/yano-x-executors`](https://github.com/bloxbean/yano-x-executors).

## Running a chain on a runtime

1. Install the runtime's bundle in `yano.plugins.directory` and add its bundle id to
   `yano.plugins.allow-list`. The host refuses runtimes from bundles that are not explicitly
   allow-listed, because a runtime decides state roots.
2. Select `state-machine = execution` for the chain and set, in its genesis settings:
   - `machines.execution.descriptor-cbor-hex`: the execution descriptor (runtime id, engine
     fingerprint, program digest, ABI version, budgets, receipts; ADR-058 D6);
   - `machines.execution.program-hex` or `machines.execution.program-path`: the program bytes.
3. Optional node-local settings: `machines.execution.runtime-options.*` (performance only) and
   `machines.execution.allow-degraded` (accept a runtime that reports `DEGRADED`, such as GraalJS
   without the Graal compiler).

The state machine refuses to start if the installed runtime's fingerprint or the program's digest
differs from the descriptor. Execution-backed machines run on standalone chains only; composites
require a transition kernel, which they do not offer.

## Choosing a runtime

GraalJS cannot bound a guest's heap deterministically (ADR-058 R1), so use it for programs whose
authors the chain's members trust. Use Chicory, whose memory limit is exact, where program authors
are not trusted.

## State

Guest keys never start with `~`. When the descriptor enables receipts (the default), a discarded
transition writes a receipt at `~exec/r/<message id>` or `~exec/fx/<effect id hash>`
(`ExecutionKeys`); `~exec/` is reserved for them.
