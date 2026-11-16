# EUTxO client

Typed Java client for the optional EUTxO state machine. It wraps Yano X's
generic submit, query, and MPF proof endpoints and also implements the
`./yano.sh appchain eutxo ...` product commands. See the
[EUTxO product family](../README.md) for the query surface and CLI examples.

## Artifact

`org.yanoproject.x:yano-x-eutxo-client` (library, released to Maven Central).

## Key contents

Package `org.yanoproject.x.eutxo.client`:

- `EutxoClient` — typed facade over submit, query, and proof APIs; every
  query has a `*Snapshot` form returning an `EutxoSnapshot` bound to the
  committed height and state root;
- `EutxoPaymentTransactionBuilder` and `EutxoKeyWallet` — builder for the
  bounded key-controlled, ADA-only L2 transaction subset;
- `EutxoCli` — the product CLI (`transaction`, `utxo`, `proof`, `deposit`,
  `withdrawal`), wired into the `yano.sh appchain` launcher by
  `:tooling:devtools`; and
- settlement helpers `NullifierShardMirror`, `CrankPlanner`, and
  `ExitTransactionBuilder` for the off-chain nullifier mirror and
  permissionless exit transactions.

## Dependencies

API dependencies are `:ledgers:eutxo:contracts` and `:sdk:client`; it also uses
cardano-client-lib and CBOR. It has no bridge, custody, ZeroJ, or Julc
dependency (enforced by `:ledgers:eutxo:ledger:verifyEutxoVirtualLedgerDependencyGraph`).
Consumers include `:ledgers:eutxo:bridge-cardano`, `:ledgers:eutxo:demo`, and
`:tooling:devtools`.

## Build and test

```bash
./gradlew :ledgers:eutxo:client:test
```

See [Build and test Yano X](../../../docs/BUILD_AND_TEST.md) for the
required Yano version arguments.
