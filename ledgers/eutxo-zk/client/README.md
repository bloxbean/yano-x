# EUTxO ZK client

> **Excluded from the build.** Like every module under `ledgers/eutxo-zk`, this
> module is left out of `settings.gradle`: Gradle does not compile, test, or
> publish it, and no distribution includes it. See the
> [parent README](../README.md) for what restoring it requires.

Typed client for the optional validity capability: reads validity statements,
batch data, keys, and proofs, submits L2 transactions, and plans recovery
when proof-gated withdrawals stall. See
[Z5 data availability and recovery](../Z5_DATA_AVAILABILITY.md).

## Artifact

Not published.

## Key contents

Package `org.yanoproject.x.eutxo.zk.client`: `EutxoZkClient`,
`EutxoL2Client`, `EutxoL2TransactionBuilder`, `EutxoL2SessionKey`,
`EutxoRootFixedUtxoSupplier`, `EutxoDataAvailabilityMonitor`,
`EutxoExitRecoveryPlanner`, and `EutxoZkWithdrawalReconciler`.

## Dependencies

API dependencies on `:ledgers:eutxo-zk:contracts` and `:ledgers:eutxo:client`;
uses cardano-client-lib and the ZeroJ circuit library.

## Build

None while the module is excluded. After restoring the build entries listed
in the [parent README](../README.md), the project path is `:ledgers:eutxo-zk:client`.
[Build and test Yano X](../../../docs/BUILD_AND_TEST.md) describes the
required Yano version arguments.
