# Application-ledger host adapter

Adapts the application-ledger framework to Yano's Host API 1.2. It binds the X
contribution kinds into the host plugin catalog, registers the ledger assembly
with the host lifecycle, owns the domain-API and local read-model registries, and
publishes the running chains as a plugin service for embedders. It carries no
plugin manifest itself: the activation roots
[`framework/standalone`](../standalone/README.md) and
[`framework/cardano`](../cardano/README.md) subclass its plugin.

## Artifact

`org.yanoproject.x:yano-x-appchain-host-adapter` (`library`).

## Key contents

Package `org.yanoproject.x.framework.host`:

- `AppLedgerHostPlugin`: the `NodePlugin` base class. During `init` it registers its
  assembly step and the `AppLedgers` service; the host runs the assembly after every
  plugin has initialized.
- `AppLedgers`: embedder access, for example `AppLedgers.of(yano).appChains()`,
  `appChain()`, `domainApis()` and `localReadModels()`. Each call resolves the
  service again, so do not cache results across a restart.
- `AppLedgerKindBindings`: `ContributionKindBinding`s for the kinds declared by
  `framework/api` (registered in
  `META-INF/services/org.yanoproject.api.plugin.spi.ContributionKindBinding`),
  routing every plugin callback through the host's callback scope. The
  `effect-executor` kind is bound by `framework/cardano` instead.
- `DomainApiRegistry`, `LocalReadModelRegistry`, `LocalReadModelContributionRegistry`.
- `META-INF/yano/app-ledger-version.properties`: the framework version, expanded at build.

## Dependencies / boundaries

`api`: `framework/core` and Yano's published `yano-host-api`; `implementation`:
`framework/config`. Used by `framework/standalone`, `framework/cardano` and
`framework/rest`. Plain-Java embedders start Yano with `YanoAssembly`, set
`yano.plugins.required=org.yanoproject.x.app-ledger` and use `AppLedgers`; Quarkus is
not required. See the [ADR-064 migration notes](../../docs/appchain/MIGRATION-ADR-064.md)
and [the host boundary](../../docs/core-host.md).

## Build & test

```bash
./gradlew :framework:host-adapter:test
```

`frameworkCheck` includes these tests (they also use Yano's published
`yano-plugin-catalog`). Add the Yano inputs from
[Build and test](../../docs/BUILD_AND_TEST.md) when the pinned Yano version is
local or staged.
