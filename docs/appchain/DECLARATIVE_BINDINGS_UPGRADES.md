# Declarative binding upgrades and retained chains

Changing YAML, replacing plugin JARs, and activating a new profile are different
operations. None is an implicit migration of retained state. This guide concerns
the experimental `declarative-composite` provider; start with the
[binding guide](DECLARATIVE_BINDINGS.md) and [authoring CLI](DECLARATIVE_BINDINGS_CLI.md).

## What must remain pinned

Keep the exact Yano Maven version and matching ordinary JVM ZIP, the Yano X
distribution and manifested plugin bundles, the authored document, canonical IR,
canonical profile bytes and digest, and generated project lock. Preserve the
chain's genesis identity and retained stores independently of these artifacts.
The composite and stdlib bundles using stateless kernel admission require host
plugin API level 11. API compatibility alone does not promise consensus-profile
compatibility.

The profile is not determined by YAML text or IR bytes alone:

| Input | Consequence of changing it |
|---|---|
| Omitted authoring limits | Recompilation selects the compiler's current defaults; IR and profile identity can change |
| Kernel configuration descriptor | Defaults are materialized before hashing; newly required defaults can make old IR fail construction |
| Machine `applicationVersion` | Included in the component descriptor and therefore the profile commitment |
| Machine query subjects | Collected into the component descriptor; even an added query can change the profile commitment |
| Workflow execution version | Pins the binding execution semantics, independently of the IR wire layout |
| Component/workflow generation heights, routes and quotas | Committed parts of the executable profile, not local tuning settings |

Decoding existing IR does not substitute new limits. Conversely, recompiling
the same YAML with a different tool or bundle set is not evidence of identical
IR, descriptors or execution. Compare the actual canonical artifacts. Generated
`appchain.lock` pins IR/profile digests and the plugin catalog fingerprint;
validation recomputes them instead of accepting replaced bundles silently.

## Current execution-version boundary

The current provider constructs workflow and profile version **1.1.0**. Its
lazy baseline materialization, source-versus-derived work accounting, and
pre-kernel reservation rules differ from the earlier experimental 1.0.0 runtime.
Unchanged IR wire shape does not make these execution versions interchangeable.

The stock provider does **not** select an old workflow implementation from a
version field in IR. Supplying a 1.0.0 deployment's original IR in
`machines.composite.binding-ir-catalog[...]` still constructs a 1.1.0 profile;
it does not restore the old executable profile or its digest. Likewise, one
currently selected machine provider cannot automatically supply both its old
and new descriptor/implementation merely because both IR documents are present.

Do not upgrade a retained 1.0.0 chain in place with this release. Keep its exact
qualified runtime, or design and independently qualify an explicit migration.
A fresh chain with a new identity is a separate deployment, not preservation of
the old history. Do not delete state, rewrite profile markers, regenerate a
retained genesis identity, or change `fixed` to `governed` to bypass validation.

## Compatible evolution within an executable catalog

The provider supports a bounded catalog: the genesis IR in
`machines.composite.binding-ir` plus contiguous indexed dormant entries in
`machines.composite.binding-ir-catalog[0]`, `[1]`, and so on, with at most 64
profiles including genesis. This can support compatible binding changes when
the installed runtime can reproduce every required historical profile exactly.
It is not arbitrary plugin hot reload.

For a chain created in governed mode, the existing
[profile-governance runbook](../APP_CHAIN_PROFILE_GOVERNANCE.md) supplies the
proposal, chunk/seal, approval, all-member readiness and exact-height activation
protocol. Before using it for a declarative profile:

1. Reconstruct the retained active and historical profiles with the candidate
   bundle set and compare their canonical bytes and digests. Stop on a mismatch.
2. Include the target alongside all profiles required for replay and late
   effect results. Export and independently review its canonical profile bytes.
3. Use a new workflow generation height for changed binding IR. Changed
   component configuration requires a new component generation. A generation
   height does not migrate incompatible state or replace a leaf's stored genesis;
   such changes may need a new component instance and an explicit migration.
4. Qualify replay, restart, historical proofs, activation-height cutover and
   outstanding effect/result handling with the complete catalog.
5. Deploy the qualified catalog everywhere before proposing its exact target
   digest. Follow the existing approval/readiness protocol; do not infer
   readiness from a successful local compile.

Historical executable profiles must remain available. An on-chain approval
does not manufacture a missing implementation or authorize an unimplemented
state migration. In particular, this protocol alone cannot bridge the current
stock provider's 1.0.0-to-1.1.0 execution-version boundary.

## Check a candidate bundle set without touching retained state

The read-only `profile-check` command now tests exact reconstruction through the
installed candidate plugin catalog:

```bash
yano.sh appchain bindings profile-check \
  --profiles retained-profiles.json --context context.json \
  --plugins-directory /absolute/path/to/candidate/plugins
```

`retained-profiles.json` is a JSON array of canonical profile hex strings,
genesis first, followed by every distinct historical or target profile being
checked. Supply 1-64 entries, each at most 65,536 decoded bytes. Empty, duplicate,
noncanonical, schema-v1, or non-declarative profiles are rejected. Export these
artifacts from your independently trusted deployment records or qualified
profile query/proof tooling; this command does not authenticate their origin.

Use the explicit chain identity, consensus profile and membership context
described in the [CLI guide](DECLARATIVE_BINDINGS_CLI.md). More than one profile
requires `settings["membership.mode"]` to be `"governed"`. The checker selects
governed catalog construction itself; arbitrary `machines.*` overrides remain
rejected. This does not change any deployed chain's mode.

The checker extracts each profile's explicit IR, constructs the catalog through
the actual selected provider, and queries each expected profile using a
synthetic read-only profile marker. It reports expected digests and per-profile
results. Exit 0 and `reproducesProfiles: true` mean all supplied profiles are
byte-for-byte reconstructible. Exit 2 means invalid or incompatible input;
construction and missing-profile diagnostics identify failed checks.

No retained stores are opened, no machine initialization or block application
is performed, and no submission, network access or signing is requested. As
with other catalog tools, installed Java plugins are trusted executable code,
not sandboxed data. A successful check is **not** state migration, replay
qualification, proof verification, or proof of binary semantic equivalence.
Omitting a historical profile cannot establish that it is safe to remove.

## Authoring and migration tooling limits

The current `bindings compile`, `validate`, `graph`, and `dry-run` commands
construct a fixed genesis profile. They are useful for command and cascade
rehearsal, but do not qualify a governed epoch transition or migrate a retained
chain. Project lock validation detects changes; it does not make them compatible.

`profile-check` supplies a fail-closed reconstruction preflight, not an installer
or migration planner. There is no general declarative command that installs old
and new workflow implementations side by side, exports a complete historical
migration catalog, or proves arbitrary plugin upgrades compatible. Those capabilities require
explicit version-addressable implementations and additional tooling and tests.
Until then, treat bundle or execution-version changes as a compatibility review,
not a routine YAML edit.
