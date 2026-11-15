# Yano host and application-ledger framework boundary

Yano owns the node runtime, Cardano synchronization and ledger services, the
plugin catalog, public node facades, operations API, and console UI. Yano-X's
`framework` modules own the application-ledger APIs, configuration parser,
consensus/finality engine, storage, OrderedLog, query and effect execution, plus
the optional Cardano observer and anchoring implementation.

The deterministic framework core depends on the generic node contracts and Yaci,
not Yano runtime implementation classes, the Host API, or Cardano settlement
SDKs. Protocols 100 and 103 stay in Yaci. The optional host adapter uses the
versioned Yano Host API for N2N registration and lifecycle ownership; it reuses
the existing schema-v1 plugin catalog and service registry. All plugin `init()`
callbacks finish before the host invokes framework assembly, and assembly
finishes before sessions can start.

The integrated Yano distribution continues to bundle compatible framework
artifacts. Existing `yano.app-chain.*` properties, provider identifiers,
consensus hashes and storage formats remain compatible. Framework Java types
now use `org.yanoproject.x.framework.*`; plugins and embedding clients must
recompile. Host-owned node and plugin SPI packages remain unchanged. The Cardano root retains the existing observer/anchor services;
the standalone root uses the same core without Cardano runtime activation.
Standalone app-ledger mode requires fresh standalone genesis and explicitly
rejects L1-dependent settings. Application finality and state proofs do not by
themselves provide public L1 anchoring or settlement guarantees.

Ordinary Maven embedding deployments may independently select compatible X
artifacts and restart against an unchanged supported Yano host. Do not overlay
new engine classes on an older integrated uber-JAR. The
[packaged compatibility matrix](../qa/framework/host-matrix/README.md) freezes a
compiled client and host artifact hashes, then tests later X versions on that
oldest namespace-compatible host as well as the current host. The namespace
migration requires a new host implementation baseline and a newly frozen client;
historical old-package fixtures remain evidence for their original candidates.
This package migration does not change the Host API version. A new host capability may require a
higher Host API level; consensus changes may separately require coordinated
application-node upgrades.

See [ADR-057](../adr/057-application-ledger-framework-extraction.md) for the
module boundaries and the single coordinated release plan with
[Yano issue #245](https://github.com/bloxbean/yano/issues/245).

The prerelease `yano-core-api` aggregate has been removed. Application contracts are
owned by `org.yanoproject.x:yano-x-appchain-api`; the Cardano application
contracts are in `yano-x-appchain-cardano-api`. Host/plugin contracts remain in
`org.yanoproject:yano-node-api`, and Cardano-specific host contracts remain in
`yano-cardano-api`. Shared plugin hooks keep their existing node-api ownership.
Plugin scaffolding accepts independent `--yano-version` and `--yano-x-version`
inputs; the packaged launcher defaults each to its corresponding distribution version.
