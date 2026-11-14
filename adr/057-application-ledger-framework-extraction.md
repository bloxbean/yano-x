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

Review refinement: retain `framework` as the parent group (owner-confirmed), with
`core` naming the deterministic engine/storage module. Split Cardano-specific
legacy API carriers into `framework/cardano-api`, keeping their Java names and
reexporting them through Yano's integrated compatibility coordinate. The effect
factory and its context move together because their signatures reference each
other; generic effect execution stays in core. The generic API depends only on
Yano's SDK-free node/plugin foundation, not Host API. Remove the published strict
Host API version constraint; runtime API-level checks and exact compatibility
matrix inputs establish support without forcing matching repository releases.

Keep the parent name `framework` with `api`, `config`, `core`, and optional
`cardano-api` children. Preserve appchain Maven artifact names, Java names and
`yano.app-chain.*` tooling throughout the move.

The generic Yano foundation excludes the unused transaction SDK edges from Yaci
core/helper. Framework core excludes MPF's optional Plutus formatter dependency
from both MPF edges while keeping the exact native proof-wire implementation.
Cardano integration restores the SDK explicitly. `AppOnlyWireTest` runs real TCP
handshake 101, signed protocol-100 messages, protocol-103 persisted block bytes,
and keepalive with transaction/Plutus SDK classes demonstrably absent. Existing
snapshot/archive tests cover MPF proof bytes and reopening on the same classpath.
`ObserverConsensusProfile` is the one extracted copy of the existing canonical
observer/network identity codec, with fixed legacy digest vectors.

Refinement evidence: foundation `0.1.0-245.3`, Host API `1.0.0-245.3`, and framework
`0.1.0-245.4` are exact unpublished staged inputs, not a frozen release baseline.
The optional Cardano attachment, manifested host adapter, independent host and
oldest/current packaged compatibility matrix remain subsequent implementation work.

## Phase 2 runtime extraction refinement

The public signature audit supports a smaller shape than a new attachment SPI
plus compatibility façade: move the existing `AppChainSubsystem` to the optional
Cardano module as a specialization of one extracted `AppLedgerRuntime`. Keep all
four public constructors, `AppChainSubsystem.AnchorFeeParams` (including both
constructors), and existing Cardano wiring descriptors on that exact class.
Inherited gateway/lifecycle methods keep normal JVM linkage. Move the manager
implementation once into `AppLedgerManager`; preserve the old `AppChainManager`
constructor on its Cardano specialization. Existing gateway return types already
use `AppChainGateway`, so they need no redesign. This supersedes the earlier
suggestion of a separate attachment interface/object; no new public integration
SPI is needed.

Use finite protected hooks only at existing Cardano call sites. Preserve observer
identity validation before journal writes, observers before governance, anchors
after governance, delivery after sink/effect construction, and teardown barriers
before ledger closure. Keep the existing generation fences, workers, engine and
store owners in the shared runtime. Stable reference, reference verification,
anchor gates and gateway defaults retain existing no-L1 behavior. The standalone
runtime must reject configured L1 features rather than invent observations or
clocks. Constructor-time rotating-sequencer selection must use a supplied resolver
that does not read uninitialized subclass fields. The legacy effect factory and
Cardano effect context move with the optional specialization; generic effects,
webhooks and bridge diffusion remain common.

Activation continues through the existing manifested NodePlugin and shared
plugin service registry. A host-owned required embedded bundle selection must
activate the framework even when optional plugins are disabled, without scanning
plugin directories or constructing unrelated legacy providers. Requiredness is
assembly policy, not a new manifest field. Yano generates its existing native
catalog metadata from published framework artifacts; X remains JVM-only.

Independent X distributions must compose framework-free host artifacts with
their selected framework version. An older integrated uber-JAR cannot have its
embedded classes replaced by a parent-first plugin-directory overlay. The matrix
must exercise actual oldest/current host artifacts and packaged startup, not
only dependency resolution. Keep the integrated deployment unchanged and do not
add sibling checkout/build dependencies or a second runtime/plugin loader.

Validation extends the existing ABI and precompiled-plugin harness, including
legacy constructor/nested-record linkage when inheritance changes. Run focused
L1 delivery/observer/anchor/lifecycle tests per extraction slice, then physically
exclude Cardano artifacts for standalone startup/consensus/100/103/restart tests.
One final comprehensive Yano qualification still follows completion of both phases.


## Runtime ownership and manifested activation milestone

The subsystem/manager extraction now supersedes the earlier core-only milestone.
`framework/core` owns `AppLedgerRuntime`, `AppLedgerManager`, the existing engine,
store, query workers, observation/effect execution and validator resolver.
`framework/cardano` owns the exact legacy `AppChainSubsystem` and
`AppChainManager` names, their public constructors and nested fee record, plus
Cardano delivery, observer and anchor implementations. The old Yano on-chain
coordinate reexports `yano-x-appchain-anchor-onchain`; checked validator bytes,
Java/Aiken authoring sources and conformance tests are owned by
`framework/cardano-onchain`. No validator regeneration or persisted format change
is involved. Host-only epoch and transaction-builder implementations stay in
Yano. Remove unused host production MPF/JMT/julc dependencies so Maven nearest-wins
resolution cannot constrain a later framework release through obsolete host pins.

Activation reuses schema-v1 `NodePlugin`, `PluginContext` services and the existing
Host API. `framework/host-adapter` is a descriptor-free shared implementation;
`framework/standalone` and `framework/cardano` are mutually exclusive manifested
roots for `org.yanoproject.x.app-ledger`. They publish ordinary Maven libraries,
without the existing product bundle task that strips classes against a particular
Yano ZIP. This small standalone root keeps provider class, SPI declaration and
manifest together for the existing artifact scanner; there is no second loader.

The host supplies copied `YanoConfig` and immutable `RuntimeOptions` snapshots.
One optional, concrete `CardanoRuntimeServices` record groups only callbacks
already used by the existing assembly: submission, UTXOs, canonical chain reads,
body-retention registration, evaluation and the two existing parameter suppliers.
This is an adapter-owned data carrier, not a new generic L1 interface. Preserve its
constructor as part of the embedding compatibility surface. The two parameter
suppliers stay distinct because linear fees and script pricing have different
fallback behavior. Epoch-state wiring retains its existing later assembly point.
The plugin registers its concrete assembly entry point through the existing
`PluginContext` service registry during `init()`. After every plugin has initialized
successfully, the host invokes `assemble()` before sealing the N2N and lifecycle
registries. This preserves the original ordering: user state-machine, signer and
sequencer factories may rely on their own plugin's completed initialization.
The assembly method constructs one existing manager, registers N2N agents and
decorators, transfers lifecycle ownership exactly once, and returns that manager
for the existing public gateway facade. No new interface, service-registry phase,
or plugin start/stop runtime is introduced. Provider callbacks execute outside
the assembly monitor. Failed construction or rejected ownership closes the same
chain resources in reverse order. A failure in any plugin's initialization prevents
factory construction entirely. The preliminary unpublished host `.4` embedding
candidate is superseded by host `.5`; the Host API `.4` interface stays unchanged.

Required embedded roots are host assembly policy, not a manifest schema change.
They activate even when optional plugins are disabled, including restrictive
allow/deny lists. Disabled mode never scans directory artifacts or constructs or
resolves unrelated optional providers. Required SPI entries and artifact origins
are still checked. Library mode without an aggregate index uses qualified
manifests and the existing artifact scanner; packaged/native mode keeps its
exactly-one-index requirement. Integrated standalone deployments retain the
optional Cardano specialization without L1 wiring so legacy context-aware effect
factories continue to work. The standalone root uses the SDK-independent core.

Runtime extraction validation includes a frozen pre19 client JAR compiled only
against checksum-verified original artifacts. Its unchanged bytecode exercises
old constructors, nested fee records, Cardano wiring, inherited gateway/protocol
methods, finality and reopen. Both public API comparisons and this runtime fixture
pass against candidate framework `0.1.0-245.5`. The lean X test executes real
startup, consensus, query-worker access, restart and database reopen with Cardano
runtime/SDK classes absent. Cleanup-hook failure tests cover concurrent and repeat
close, self-close rejection and reopening after failed teardown. Candidate `.6`
adds manifested activation; integrated packaging and the oldest-host matrix must
pass before declaring Phase 2 complete.

The first supported Host API baseline must be an immutable artifact with these
capabilities; pre19 is only a compatibility fixture and cannot be represented as
a Host API host. A QA-only thin packaged host fixture will exercise the ordinary
library embedding path against baseline/current host artifacts and one selected
X artifact set. It must exclude embedded old framework code, retain original
fixture bytecode, verify class/resource ownership and record resolved hashes.
The integrated Quarkus deployment remains the existing default. Do not overlay
new framework classes onto its parent-first uber-JAR. One comprehensive Yano
release-qualification cycle follows integration and these incremental gates;
there is no intermediate Yano release or public candidate publication.
