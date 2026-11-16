# EUTxO ZK on-chain validators

> **Excluded from the build.** Like every module under `ledgers/eutxo-zk`, this
> module is left out of `settings.gradle`: Gradle does not compile, test, or
> publish it, and no distribution includes it. See the
> [parent README](../README.md) for what restoring it requires.

Julc-authored Plutus validators that verify EUTxO validity proofs on Cardano:
the proof-gated validity-root thread and the restricted proof-withdrawal vault.
See [Z3 validity root](../Z3_VALIDITY_ROOT.md) and
[Z4 withdrawals](../Z4_PROOF_WITHDRAWALS.md).

## Artifact

Not published.

## Key contents

Package `org.yanoproject.x.eutxo.zk.onchain`: `EutxoValidityRootValidator`
(permissionless proof advancement plus an authority-signed, root-preserving
handoff), `EutxoProofWithdrawalVaultValidator` (one aggregate withdrawal per
proof-advanced root), and `EutxoValidityOnChainAbi` (the canonical Plutus-data
ABI for the Groth16 verifier).

## Dependencies

API dependency on `:ledgers:eutxo-zk:contracts`; implementation dependency on
the ZeroJ Julc on-chain module, with Julc constrained to the Yano X version.

## Build

None while the module is excluded. After restoring the build entries listed
in the [parent README](../README.md), the project path is `:ledgers:eutxo-zk:onchain`.
[Build and test Yano X](../../../docs/BUILD_AND_TEST.md) describes the
required Yano version arguments.

Tests (`test`, `cryptoTest`) set `zeroj.allowInsecureTrustedSetup=true`.
