# EUTxO ZK runtime plugin

> **Excluded from the build.** Like every module under `ledgers/eutxo-zk`, this
> module is left out of `settings.gradle`: Gradle does not compile, test, or
> publish it, and no distribution includes it. See the
> [parent README](../README.md) for what restoring it requires.

Integrated runtime plugin that replaces the standard EUTxO ledger bundle when
the validity capability is wanted. It registers the `eutxo-ledger` state
machine with the ZeroJ validity provider built in, plus its domain API. The
[e2e README](../e2e/README.md) describes how its devnet test used the bundle.

## Artifact

Not published. Its plugin bundle was `yano-x-eutxo-zk-runtime-bundle` (bundle id
`org.yanoproject.x.eutxo.zk.runtime`).

## Key contents

Package `org.yanoproject.x.eutxo.zk.runtime`: `EutxoZkStateMachineProvider`
and `EutxoZkDomainApiProvider`, declared in
`META-INF/yano/plugins/org.yanoproject.x.eutxo.zk.runtime.json`. The shadow
bundle drops the standard ledger's plugin descriptor so only this one is
installed, and `verifyEutxoZkRuntimeBundle` checks that both providers are
present.

## Dependencies

Compiles against `:framework:api`, cardano-client-lib, Scalus, and CBOR as
`compileOnly`. The bundle embeds `:ledgers:eutxo:ledger`,
`:ledgers:eutxo:contracts`, `:ledgers:eutxo-zk:contracts`, and
`:ledgers:eutxo-zk:zeroj`.

## Build

None while the module is excluded. After restoring the build entries listed
in the [parent README](../README.md), the project path is `:ledgers:eutxo-zk:runtime`.
[Build and test Yano X](../../../docs/BUILD_AND_TEST.md) describes the
required Yano version arguments.
