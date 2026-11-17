# EUTxO indexer core

Storage-neutral contracts and logic for the optional node-local EUTxO
lifecycle index: projection of finalized transaction summaries, accounts,
bridge records, and optional validity batches into bounded read models, plus
checkpoint, health, and lineage tracking. The index is derived and
disposable; it never affects app-chain execution. Operations are covered in
the [EUTxO indexer runbook](../INDEXER_OPERATIONS.md) and the replay rules in
[INDEXER_REPLAY_CONTRACT.md](../INDEXER_REPLAY_CONTRACT.md).

## Artifact

`org.yanoproject.x:yano-x-eutxo-indexer-core` (library; not released to Maven
Central). The published module also carries `testFixtures`.

## Key contents

- `org.yanoproject.x.eutxo.indexer` — `EutxoIndexCoordinator` (finalized-block
  listener with durable checkpoint catch-up), `EutxoProjector`, the store SPI
  `EutxoIndexStore` / `EutxoIndexWrite` / `EutxoIndexStoreProvider`,
  `EutxoIndexReader`, `EutxoLocalReadModel`, and records such as
  `IndexCheckpoint`, `IndexHealth`, `IndexIdentity`, and `EutxoLineage`;
- `org.yanoproject.x.eutxo.indexer.api` — `EutxoIndexDomainApi` and its
  provider, the versioned read-only lifecycle API;
- `org.yanoproject.x.eutxo.indexer.memory` — `InMemoryEutxoIndexStore`, the
  deterministic reference store; and
- test fixtures `org.yanoproject.x.eutxo.indexer.testing` —
  `EutxoIndexStoreConformance` and `EutxoIndexFixtures`, the reusable
  conformance suite every store implementation must pass.

## Dependencies

API dependencies are `:framework:api` and `:ledgers:eutxo:contracts`. It has
no JDBC, HTTP, or ZeroJ dependency. Store implementations, such as
[indexer-jdbc](../indexer-jdbc/README.md), depend on it.

## Build and test

```bash
./gradlew :ledgers:eutxo:indexer-core:test
```

See [Build and test Yano X](../../../docs/BUILD_AND_TEST.md) for the
required Yano version arguments. Design:
[ADR-UTXO-005](../../../adr/app-layer/utxo/005-scalable-eutxo-lifecycle-indexer-and-unified-console.md).
