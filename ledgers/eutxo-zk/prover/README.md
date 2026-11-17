# EUTxO ZK prover

> **Excluded from the build.** Like every module under `ledgers/eutxo-zk`, this
> module is left out of `settings.gradle`: Gradle does not compile, test, or
> publish it, and no distribution includes it. See the
> [parent README](../README.md) for what restoring it requires.

Node-local, restart-safe proof pipeline for the optional validity capability:
it schedules finalized batches, runs one proof job at a time, and persists job
state. It is never called by consensus apply. See
[Z2 prover pipeline](../Z2_PROVER_PIPELINE.md).

## Artifact

Not published.

## Key contents

Package `org.yanoproject.x.eutxo.zk.prover`: `EutxoProverService`,
`EutxoFinalizedBatchScheduler`, `EutxoProverStore`, `EutxoProverJob`,
`EutxoProverFailoverCoordinator`, the pluggable `EutxoProofBackend` with
`ZerojEutxoProofBackend`, and `EutxoDevelopmentBatchIngestor`.

## Dependencies

API dependency on `:ledgers:eutxo-zk:contracts`; implementation dependency on
`:ledgers:eutxo-zk:zeroj`.

## Build

None while the module is excluded. After restoring the build entries listed
in the [parent README](../README.md), the project path is `:ledgers:eutxo-zk:prover`.
[Build and test Yano X](../../../docs/BUILD_AND_TEST.md) describes the
required Yano version arguments.

Tests (`test`, `cryptoTest`) set `zeroj.allowInsecureTrustedSetup=true`.
