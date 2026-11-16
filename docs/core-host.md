# Yano host and application-ledger framework boundary

Yano owns the Cardano node runtime, Cardano synchronization and ledger
services, the plugin catalog, public node facades, the Host API, the generic
operations API and its generic console. It contains no application-ledger code
and never depends on Yano X (ADR-064). Yano-X's `framework` modules own the
application-ledger APIs, contribution kinds and their facades, configuration
parser and projection, consensus/finality engine, storage, OrderedLog, query
and effect execution, the app-chain REST API and console, and the optional
Cardano observer and anchoring implementation. Yano X also composes the
integrated JVM product.

The deterministic framework core depends on the generic node contracts and Yaci,
not Yano runtime implementation classes, the Host API, or Cardano settlement
SDKs. Protocols 100 and 103 stay in Yaci. The host adapter uses Host API 1.2:
the X root plugin is activated through `yano.plugins.required`, reads its
configuration through `HostApi.nodeConfig()/runtimeOptions()`, registers its
assembly, restart readiness and plugin products with the host lifecycle, and
publishes its chains as a plugin service that `AppLedgers.of(yano)` resolves.
All plugin `init()` callbacks finish before the host runs assembly callbacks,
and assembly finishes before sessions can start. In Cardano mode the adapter
reads `CardanoHostServices`, including the epoch snapshot reader.

X declares its contribution kinds in one `META-INF/yano/contribution-kinds.json`
descriptor per framework jar (the owner is named inside the JSON) and binds them with `ContributionKindBinding` implementations. Yano reads
descriptors only from trusted host artifacts, so X kinds exist only when the X
framework is on the host class path, as in the integrated distribution. A bare
Yano with X bundles in `plugins/` fails closed.

Existing `yano.app-chain.*` properties, provider identifiers, consensus hashes,
REST paths and storage formats remain compatible. Framework Java types use
`org.yanoproject.x.framework.*`. The standalone root uses the same core without
Cardano runtime activation; `yano.mode=app-ledger` requires the X plugin that
supplies the app-only network. Standalone app-ledger mode requires fresh
standalone genesis and explicitly rejects L1-dependent settings. Application
finality and state proofs do not by themselves provide public L1 anchoring or
settlement guarantees.

Ordinary Maven embedding deployments start Yano with `YanoAssembly` and
`yano.plugins.required`, select compatible X artifacts, and do not need Quarkus.
Do not overlay new engine classes on an older integrated jar. X requires Host
API 1.2; the [packaged compatibility matrix](../qa/framework/host-matrix/README.md)
runs X against a frozen Host API 1.2 baseline host and the current host. A new
host capability may require a higher Host API level; consensus changes may
separately require coordinated application-node upgrades.

Contracts: application contracts are owned by
`org.yanoproject.x:yano-x-appchain-api`; the Cardano application contracts are
in `yano-x-appchain-cardano-api`. Host/plugin contracts remain in
`org.yanoproject:yano-node-api` and `yano-host-api`, and Cardano-specific host
contracts in `yano-cardano-api`. Plugin scaffolding accepts independent
`--yano-version` and `--yano-x-version` inputs; the packaged launcher defaults
each to its corresponding distribution version.

See [ADR-057](../adr/057-application-ledger-framework-extraction.md) for the
module boundaries, Yano
[ADR-064](https://github.com/bloxbean/yano/blob/main/adr/064-yano-x-depends-on-yano-never-the-reverse.md)
for the dependency direction, and the
[migration notes](appchain/MIGRATION-ADR-064.md).
