# EUTxO testkit

Deterministic wallets, transaction fixtures, and in-memory state for authors
testing EUTxO plugins and clients. It is test material: its keys are public
and must never hold real funds. See the [EUTxO product family](../README.md).

## Artifact

`org.yanoproject.x:yano-x-eutxo-testkit` (test library, released to Maven
Central).

## Key contents

Package `org.yanoproject.x.eutxo.testkit`:

- `EutxoTestWallet` — deterministic test wallet;
- `EutxoTransactionFixtures` — small Cardano transaction builder for
  deterministic conformance tests;
- `MemoryAppState` — read-your-writes `AppStateWriter` / `AppQueryContext`
  for state-machine unit and replay tests; and
- `EutxoNoFundsDemo` — prints the fixed public address used by the no-funds
  quick start.

## Dependencies

API dependencies are `:framework:api`, `:ledgers:eutxo:contracts`, and
cardano-client-lib core. It has no bridge, custody, ZeroJ, or Julc dependency.
The ledger, client, and bridge modules use it in their tests.

## Build and run

```bash
./gradlew :ledgers:eutxo:testkit:build
./gradlew :ledgers:eutxo:testkit:runNoFundsDemo   # print the demo address
```

See [Build and test Yano X](../../../docs/BUILD_AND_TEST.md) for the
required Yano version arguments.
