# EUTxO ZK index adapter

> **Excluded from the build.** Like every module under `ledgers/eutxo-zk`, this
> module is left out of `settings.gradle`: Gradle does not compile, test, or
> publish it, and no distribution includes it. See the
> [parent README](../README.md) for what restoring it requires.

Translates ZeroJ lifecycle artifacts into the provider-neutral validity
records of the common [EUTxO indexer](../../eutxo/INDEXER_OPERATIONS.md), so
the indexer core itself needs no ZeroJ dependency.

## Artifact

Not published. Its plugin bundle id was `org.yanoproject.x.eutxo.zk.indexer`.

## Key contents

Package `org.yanoproject.x.eutxo.zk.indexer`: `ZerojValidityReadModelProvider`,
registered as a `LocalReadModelProvider` and declared as a
`local-read-model` contribution in
`META-INF/yano/plugins/org.yanoproject.x.eutxo.zk.indexer.json`.

## Dependencies

API dependency on `:ledgers:eutxo:indexer-core`; implementation dependencies on
`:ledgers:eutxo-zk:contracts`, `:ledgers:eutxo-zk:zeroj`, and Jackson.

## Build

None while the module is excluded. After restoring the build entries listed
in the [parent README](../README.md), the project path is `:ledgers:eutxo-zk:indexer`.
[Build and test Yano X](../../../docs/BUILD_AND_TEST.md) describes the
required Yano version arguments.
