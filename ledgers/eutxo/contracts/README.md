# EUTxO contracts

Public identifiers, state keys, records, and canonical CBOR codecs shared by
every module of the optional EUTxO product family. It contains no state
machine, plugin metadata, or service registration: adding it to an application
provides types and codecs only. See the [EUTxO product family](../README.md)
for the ledger, bridge, and trust model.

## Artifact

`org.yanoproject.x:yano-x-eutxo-contracts` (library, released to Maven
Central).

## Key contents

Package `org.yanoproject.x.eutxo.contracts`:

- `EutxoContract` — stable state-machine and wire identifiers;
- `EutxoProfile` — the immutable, versioned ledger profile and its digest;
- `EutxoStateKeys` — versioned deterministic keys committed by the app-chain
  MPF;
- `EutxoQueryCodec` — frozen query paths and bounded request/response codecs;
- ledger records such as `EutxoOutpoint`, `EutxoRecord`, `EutxoReceipt`, and
  `EutxoTransactionSummary`;
- bridge and settlement records such as `EutxoDepositClaim`,
  `EutxoDepositRecord`, `EutxoWithdrawalClaim`, `EutxoWithdrawalRecord`,
  `EutxoFederatedRoot`, `EutxoVaultDatum`, and `EutxoSettlementBatch`; and
- the provider-neutral validity SPI `EutxoValidityCommitmentProvider` /
  `EutxoValidityCommitmentEngine` used by the optional
  [validity modules](../../eutxo-zk/README.md).

## Dependencies

API dependencies are cardano-client-lib core and `:sdk:proof-contracts`. Every
other EUTxO module depends on this one; it depends on no plugin, bridge, Julc,
or ZeroJ code.

## Build and test

```bash
./gradlew :ledgers:eutxo:contracts:test
```

Every Gradle command needs a resolvable Yano version; see
[Build and test Yano X](../../../docs/BUILD_AND_TEST.md).

The governing design is
[ADR-UTXO-001](../../../adr/app-layer/utxo/001-eutxo-state-machine-and-cardano-settlement.md).
