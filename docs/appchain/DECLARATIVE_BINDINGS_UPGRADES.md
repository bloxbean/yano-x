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
The composite, stdlib and role-workflow bundles declare `minLevel: 12`: they need
plugin API level 12 with the ADR-031.3 and ADR-031.4 host contract (stateless
kernel admission, kernel-declared rule facts, value views and write views). No
Yano release contains that contract yet, and level 12 alone does not identify
it, because the typed-view API was added to level 12 without a new level. API
compatibility alone does not promise consensus-profile compatibility.

Admission rules make kernel facts consensus inputs. A bundle that changes which
facts a kernel declares, or how it computes them, changes the outcome of rules
that read them, even when the profile still reconstructs; treat it as a
consensus change and qualify it with replay, not only `profile-check`.

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

The current provider constructs workflow and profile version **1.2.0**. ADR-031.3
(admission rules) amended the version-one binding IR, expression dialect and
receipt layouts in place: every amended structure changed its array arity, so IR
and receipts written by the 1.1.0 or 1.0.0 runtimes fail decode with an explicit
"predates ADR-031.3" error instead of being misread. Lazy baseline
materialization, source-versus-derived work accounting, and pre-kernel
reservation rules also differ from the earlier experimental 1.0.0 runtime.

ADR-031.4 (typed views) then amended the rule layout in place again, **without
changing the version**: each rule gained its reads, and a receipt's rule failure
gained the index of the deciding write. IR whose admission rules were written
before ADR-031.4, and receipts that record a rule failure in the earlier layout,
fail decode with an explicit "predates ADR-031.4" error. Documents without rules
and receipts without a rule failure are unaffected. Because the version still
reads 1.2.0, the version alone does not tell you which layout a retained chain
wrote; run `profile-check` against the candidate bundles.

The stock provider does **not** select an old workflow implementation from a
version field in IR, and it cannot decode pre-ADR-031.3 IR at all: supplying an
earlier deployment's original IR in `machines.composite.binding-ir-catalog[...]`
fails construction. No ADR-015 epoch from pre-ADR bytes is supported. Likewise,
one currently selected machine provider cannot automatically supply both its old
and new descriptor/implementation merely because both IR documents are present.

Declarative composition is experimental. Re-create a 1.1.0 or 1.0.0 chain, or a
1.2.0 chain whose rules predate ADR-031.4, from its YAML with this release; do
not upgrade it in place. Keep a retained chain on
its exact qualified runtime, or design and independently qualify a migration.
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
state migration. In particular, this protocol alone cannot bridge any of the
stock provider's earlier boundaries: versions 1.0.0 and 1.1.0, or rules written
before ADR-031.4.

## Check a candidate bundle set without touching retained state

The read-only `profile-check` command now tests exact reconstruction through the
installed candidate plugin catalog:

```bash
./yano.sh appchain bindings profile-check \
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
construction and missing-profile diagnostics identify failed checks. Step
through a passing check and the ways it fails:

<!-- illustration: upgrade-preflight -->
1. **Gather the profiles.** Export the retained canonical profiles from records
   you trust, genesis first: 1 to 64 hex strings.
2. **Decode each profile.** Each must be a declarative schema-v2 profile whose IR
   decodes with this build. Duplicates are refused. IR whose rules predate
   ADR-031.4 fails here.
3. **Check the context.** More than one profile needs `membership.mode` set to
   `governed` in the context's settings.
4. **Build the candidate catalog.** The checker constructs the real
   `declarative-composite` provider from the candidate plugin directory with
   every profile's IR, running the installed plugins' code.
5. **Compare byte for byte.** For each profile, the candidate answers
   `composite/active-profile-v1` from a synthetic marker, and its bytes must equal
   the supplied bytes.
6. **Read the verdict.** Exit 0 and `reproducesProfiles: true` only when every
   profile matches. Anything else exits 2.
<!-- /illustration -->

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
