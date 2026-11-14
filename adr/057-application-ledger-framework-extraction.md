# ADR-057: Application-ledger framework extraction

Status: accepted for incremental implementation; extraction is incomplete.

Coordinated with [Yano issue #245](https://github.com/bloxbean/yano/issues/245),
[Yano ADR-063](https://github.com/bloxbean/yano/blob/feat/245-app-ledger-host-api/adr/063-standalone-application-ledger-and-host-api.md)
and drafts [Yano #246](https://github.com/bloxbean/yano/pull/246) /
[X #102](https://github.com/bloxbean/yano-x/pull/102).

Issue #245 supersedes ADR-029's historical leaf-extension-only boundary. X now
owns the application-ledger framework as well as its optional products. This
change preserves established Java names, plugin signatures, `yano.app-chain.*`
configuration, persisted formats and wire encodings. The moved classes retain
`org.yanoproject.api.appchain` and `org.yanoproject.runtime.appchain` names to
avoid breaking existing binaries; new product APIs remain under
`org.yanoproject.x`. This is a specific migration exception to the old namespace
and leaf-only guidance, not permission to introduce node implementation imports.

The first milestone adds three inventoried libraries:

- `framework/api`: public application contracts, domain SPI, events and golden resources.
- `framework/config`: existing parser, schema registry and semantic validation.
- `framework/core`: existing consensus, storage, authenticated state, deterministic
  execution, effects/observations, snapshots and OrderedLog.

The core depends only on framework libraries, Yaci, preserved node/host API
contracts, storage/crypto/codec libraries and logging. It does not depend on Yano
runtime, P2P, ledger-state implementations, QuickTx or julc. Public legacy observer
and effect signatures still expose Cardano model types; those API compatibility
dependencies are retained. Protocols 100/103 remain implemented in Yaci.

Yano's old core-api and appchain-config coordinates become compatibility
aggregates with no duplicate definitions. Foundational Yano node/plugin APIs are
in `yano-node-api`, breaking the otherwise circular API dependency. Exact external
Maven inputs are mandatory; no sibling checkout, composite build or sibling task
invocation is added. Host API version selection is independent of X's version.

`frameworkCheck` runs copied wire-vector/configuration tests plus extracted
engine tests and an actual resolved dependency-boundary check. Test fixtures are
owned here and loaded from resources, not a Yano filesystem layout. The boundary
report records resolved artifact hashes. Yano also tests its aggregate API,
configuration coordinate and three-node consensus against the X JAR, and loads
fixed earlier plugin bytecode to catch binary linkage changes.

The large subsystem, Cardano integration and host transport assembly have not
moved at this milestone. Manifested framework activation, optional Cardano
capabilities, independently packaged standalone X and the frozen oldest/current
host matrix remain required. Do not call this milestone the complete Phase 2.
Use one final coordinated Yano release-qualification cycle after both phases;
there is no intermediate Yano release. Local staged candidate versions are not
public releases or a frozen compatibility promise.

The following incremental milestone moves the four existing Yaci transport wiring
classes into the framework artifact, retaining their Java names and behavior.
The manager/subsystem host assembly still remains in Yano. The API now exposes
`AppChainGateway.finalizedCommitment(height)` as an additive default method; it
exports existing canonical identity/root/context without publishing or changing
block bytes. Missing legacy identity/history returns empty. Current focused
counts are 217 API, 31 configuration, 57 core/transport and six proof-contract
tests, all passing against external host candidate `1.0.0-245.1`. A deliberately
injected transitive Yano runtime artifact is rejected by the boundary check.
This is candidate-artifact compatibility evidence, not the outstanding packaged
oldest-host/distribution matrix.
