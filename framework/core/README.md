# Application-ledger core

The deterministic application-ledger engine: two-phase consensus and finality,
the sequenced message pool, RocksDB storage, genesis-selected authenticated state
(MPF or classic JMT), the built-in OrderedLog state machine, effects and
observations runtimes, authenticated snapshots and the app-message transport over
Yaci protocols 100 and 103. It does not depend on the Yano Host API, the Yano
runtime or Cardano transaction SDKs; the host adapter and the optional Cardano
module assemble it.

## Artifact

`org.yanoproject.x:yano-x-appchain-core` (`library`), plus a Gradle
`testFixtures` variant (`AppLedgerTestFixtures`) with setup and fault-injection
helpers for tests across the module boundary.

## Key contents

Package `org.yanoproject.x.framework.runtime.appchain`:

- `AppLedgerManager`: hosts one `AppLedgerRuntime` per configured chain behind a
  single set of inbound agents (multi-chain per node).
- `AppLedgerRuntime`: per-chain subsystem (authenticated diffusion, sequenced
  ledger, `AppChainGateway`).
- `AppChainEngine`: the two-phase consensus event loop; commits each finalized
  block, state root and cursor update in one RocksDB batch.
- `AppLedgerStore`: append-only ledger store; there is no rollback path after finality.
- `OrderedLogStateMachine`, `MpfAuthenticatedStateBackend`,
  `ClassicJmtAuthenticatedStateBackend`, `EffectRuntime`, `ObservationRuntime`,
  `AuthenticatedSnapshotRuntime`, `WebhookSink`, `WebhookEffectExecutor`.
- `StateMachineConformance`: replay-stability harness for state-machine upgrades.

Package `org.yanoproject.x.framework.runtime.plugins`:
`CatalogAuthenticatedMapValidatorResolver`.

Public engine types are collaboration surfaces for `framework/cardano` and the
host adapter within one framework release, not plugin contracts. Plugins implement
[`framework/api`](../api/README.md).

## Dependencies / boundaries

`api`: `framework/api`; `implementation`: `framework/config`, Yaci core,
cardano-client MPF/JMT/crypto, RocksDB and Jackson. `verifyFrameworkBoundary`
(part of `check`) resolves the real runtime closure and fails on any
`org.yanoproject` artifact other than `yano-node-api`, on Julc, and on the
cardano-client transaction/backend/Plutus SDKs; its report is
`build/reports/framework-boundary.json`. See
[ADR-057](../../adr/057-application-ledger-framework-extraction.md) and
[the host boundary](../../docs/core-host.md).

## Build & test

```bash
./gradlew :framework:core:test
./gradlew :framework:core:verifyFrameworkBoundary
./gradlew frameworkCheck
```

Add the Yano inputs from [Build and test](../../docs/BUILD_AND_TEST.md) when the
pinned Yano version is local or staged; see the
[framework gate](../../qa/framework/README.md). Crash-recovery tests launch a
child JVM with the test classpath. Opt-in benchmarks:
`benchmarkAdr025ClassicJmtRuntime`, `benchmarkAdr028AuthenticatedSnapshotMpf` and
`benchmarkAdr028AuthenticatedSnapshotJmt` (the latter two write their reports under
`adr/app-layer/benchmarks/`).
