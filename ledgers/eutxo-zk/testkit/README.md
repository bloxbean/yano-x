# EUTxO ZK testkit

> **Excluded from the build.** Like every module under `ledgers/eutxo-zk`, this
> module is left out of `settings.gradle`: Gradle does not compile, test, or
> publish it, and no distribution includes it. See the
> [parent README](../README.md) for what restoring it requires.

Test support for the validity modules. Its main class is a small
reconstruction oracle that is deliberately independent of the production
circuit helpers and prover pipeline.

## Artifact

Not published.

## Key contents

Package `org.yanoproject.x.eutxo.zk.testkit`: `EutxoReferenceReconstructor`.
The module also defines `verifyEutxoZkDependencyPins`, which checks that the
zeroj, onchain, and prover modules resolve a single reviewed ZeroJ and Julc
version.

## Dependencies

API dependencies on `:ledgers:eutxo-zk:contracts` and `:ledgers:eutxo-zk:client`;
uses the ZeroJ circuit library.

## Build

None while the module is excluded. After restoring the build entries listed
in the [parent README](../README.md), the project path is `:ledgers:eutxo-zk:testkit`.
[Build and test Yano X](../../../docs/BUILD_AND_TEST.md) describes the
required Yano version arguments.

Tests (`test`, `cryptoTest`) set `zeroj.allowInsecureTrustedSetup=true`.
