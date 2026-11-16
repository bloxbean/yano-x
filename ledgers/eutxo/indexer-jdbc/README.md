# EUTxO JDBC indexer plugin

SQLite store and runtime plugin for the optional EUTxO lifecycle index. It
implements the [indexer-core](../indexer-core/README.md) store SPI as a
single-writer event journal plus a rollback-safe relational read projection,
and contributes the index's domain API, local read model, health, and metrics.
Configuration, recovery, and support guidance are in the
[EUTxO indexer runbook](../INDEXER_OPERATIONS.md).

## Artifact

`org.yanoproject.x:yano-x-eutxo-indexer-jdbc` (thin jar) and
`yano-x-eutxo-indexer-jdbc-bundle` (plugin bundle
`org.yanoproject.x.eutxo.indexer`). Runtime plugin; not released to Maven
Central. The bundle is part of the Yano X JVM distribution's default plugin
set.

## Key contents

Package `org.yanoproject.x.eutxo.indexer.jdbc`:

- `EutxoLifecycleIndexerProvider` — the `LocalReadModelProvider` that owns
  per-chain indexes;
- `SqliteEutxoIndexStore` / `SqliteEutxoIndexStoreProvider` — the SQLite store;
- `SqliteIndexRebuilder` — builds a complete index beside the active file and
  activates it atomically;
- `EutxoJdbcIndexDomainApiProvider`, `EutxoIndexerHealthProvider`, and
  `EutxoIndexerMetricsProvider`; and
- Flyway migrations under `db/migration/eutxo/sqlite/`.

## Dependencies and boundaries

API dependency is `:ledgers:eutxo:indexer-core`; it uses Flyway and the SQLite
JDBC driver. The bundle excludes host APIs, `:ledgers:eutxo:contracts`, Yaci,
cardano-client-lib, and CBOR classes, which the runtime supplies.

## Build and test

```bash
./gradlew :ledgers:eutxo:indexer-jdbc:test
./gradlew :ledgers:eutxo:indexer-jdbc:shadowJar             # plugin bundle
./gradlew :ledgers:eutxo:indexer-jdbc:benchmarkEutxoIndexer # writes build/reports/eutxo-indexer/benchmark.json
```

The benchmark accepts `-PeutxoBenchmarkOperations=<n>` (default 20000). See
[Build and test Yano X](../../../docs/BUILD_AND_TEST.md) for the required
Yano version arguments.
