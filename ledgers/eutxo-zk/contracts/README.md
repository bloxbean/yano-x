# EUTxO ZK contracts

> **Excluded from the build.** Like every module under `ledgers/eutxo-zk`, this
> module is left out of `settings.gradle`: Gradle does not compile, test, or
> publish it, and no distribution includes it. See the
> [parent README](../README.md) for what restoring it requires.

ZeroJ-neutral public inputs, proof envelopes, verification-key records, and
validity settlement contracts for the optional EUTxO validity capability
(Phase C of
[ADR-UTXO-001](../../../adr/app-layer/utxo/001-eutxo-state-machine-and-cardano-settlement.md)).

## Artifact

Not published. The build file names its POM *Yano EUTxO ZK Contracts*.

## Key contents

Package `org.yanoproject.x.eutxo.zk.contracts`: `EutxoZkProfile` (circuit
family identity and bounds), `EutxoZkPublicInputs` and
`EutxoZkSettlementPublicInputs` (ordered public inputs; the order is consensus
and on-chain ABI), `EutxoZkStatement`, `EutxoZkBatchProof`,
`EutxoZkBatchProfile`, `EutxoZkVerificationKey`, `EutxoZkReleaseManifest`,
`EutxoZkRecoveryBundle`, and the readiness and budget assessments.

## Dependencies

API dependency on `:ledgers:eutxo:contracts`; uses cardano-client-lib crypto.
No ZeroJ dependency. Every other `eutxo-zk` module depends on it.

## Build

None while the module is excluded. After restoring the build entries listed
in the [parent README](../README.md), the project path is `:ledgers:eutxo-zk:contracts`.
[Build and test Yano X](../../../docs/BUILD_AND_TEST.md) describes the
required Yano version arguments.
