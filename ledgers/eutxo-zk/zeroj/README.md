# EUTxO ZeroJ validity

> **Excluded from the build.** Like every module under `ledgers/eutxo-zk`, this
> module is left out of `settings.gradle`: Gradle does not compile, test, or
> publish it, and no distribution includes it. See the
> [parent README](../README.md) for what restoring it requires.

ZeroJ implementation of the optional EUTxO validity capability: Poseidon
validity commitments, the fixed-shape Groth16 batch circuits, development
trusted setups, and ceremony-bundle verification. The circuit and its
profiles are described in [Z1](../Z1_BATCH_CIRCUIT.md) and
[D3](../D3_BATCH_PROFILES.md).

## Artifact

Not published. The build file names its POM *Yano EUTxO ZeroJ Validity*.

## Key contents

Package `org.yanoproject.x.eutxo.zk.zeroj`: `ZerojPoseidonValidityProvider` /
`ZerojPoseidonValidityEngine` (the `EutxoValidityCommitmentProvider`
implementation), circuits such as `EutxoKeyPaymentBatchCircuit`,
`EutxoJubjubBatchCircuit`, and `EutxoKeyPaymentSettlementCircuit`,
`EutxoBatchProofEngine`, the `*DevelopmentSetup` classes, and
`EutxoCeremonyBundleVerifier` / `EutxoCeremonyManifest`.

## Dependencies

API dependency on `:ledgers:eutxo-zk:contracts`; implementation dependencies
on the ZeroJ circuit, crypto, BLS12-381, MPF-Poseidon, and Julc on-chain
modules, with Julc aligned to the Yano X version.

## Build

None while the module is excluded. After restoring the build entries listed
in the [parent README](../README.md), the project path is `:ledgers:eutxo-zk:zeroj`.
[Build and test Yano X](../../../docs/BUILD_AND_TEST.md) describes the
required Yano version arguments.

Tests (`test`, `cryptoTest`) set `zeroj.allowInsecureTrustedSetup=true`.

## Notes

Development Groth16 setups are single-participant and test-only; they are not
a production trusted setup.
