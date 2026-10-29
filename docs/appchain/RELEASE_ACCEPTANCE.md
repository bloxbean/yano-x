# App-chain release acceptance

This page records the ADR-022 M6 release decision and the checks behind the
out-of-box app-chain catalog. It deliberately separates a capability being
packaged from its business outcome being exercised.

## Current decision

The component-catalog, metadata-trust, and release-acceptance-index schemas
remain `v1alpha1`. Internal clean-room exercises cover the supported release
matrix, but no independently maintained third-party catalog has yet supplied
field compatibility evidence. Renaming an alpha schema to stable without that
usage would create a compatibility promise that has not been tested.

The machine-readable source of this decision is
`appchain-release-acceptance-index.json`. It is shipped with the CLI, Studio,
and the final distribution (`config/schema/`). Every evidence reference in the
index names a repository file and a concrete test, script, or task marker;
missing references fail the build.

## Recipe support

| Recipe | Maturity | Acceptance level | What is exercised |
|---|---|---|---|
| `audit-log` | stable | packaged runtime | Finalized record, proof, evidence, restart catch-up with the same tip and root, and members agree |
| `owned-registry` | stable | packaged runtime | Owner-controlled value and state proof |
| `approval-workflow` | stable | packaged runtime | Terminal approval plus generic effect completion |
| `declarative-composite` | preview | module outcome + packaged provider | Bindings compiled and executed through the real manifested catalog; restart with authenticated receipts |
| `document-trail` | preview | module outcome + packaged provider | The chain seals with its stock provider; live release qualification remains required |
| `authenticated-map` | preview | module outcome + packaged provider | The chain seals with its derived genesis id; packaged plugin catalog smoke |
| `role-approval` | preview | module outcome + packaged provider | Eligible/ineligible actor decisions and provider discovery |
| `evidence-ledger` | preview | module outcome + packaged provider | Role-authorized release workflow and provider discovery |
| `eutxo-ledger` | experimental | operator-owned reference | Identical roots across three-member replay, restart and snapshot; runtime bundle verification |
| `eutxo-cardano-bridge` | experimental | operator-owned reference | Withdrawal, relay, on-chain validator and devnet settlement tests |
| `custom-plugin` | experimental | operator-owned reference | Reviewed JVM artifact visible through its service-loader boundary, and digest pinning at `seal` |

Preview recipes remain preview because their full business outcome has not
yet been run by an external operator from the final archive. Experimental
recipes remain experimental because their implementation and lifecycle are
owned by the operator.

No catalog capability was removed in M6: each has a concrete owner,
documentation, packaging declaration, and at least one resolvable behavior or
conformance reference. This does not upgrade preview or experimental entries
to stable.

## Setup ceremony matrix

`AppChainFinalDistributionAcceptanceTest#finalDistributionSealsAndJoinsEverySelectableRecipe`
(`tooling/devtools/src/distributionTest/`) extracts the final archive and, with
its own `yano.sh`, takes every selectable JVM recipe through the setup
ceremony: `new` (with `--answer` for recipes that need answers, and a bindings
file for `declarative-composite`), three member cards from three node
directories with `request`, `seal`, and a founding `join` with
`--expect-fingerprint`. It checks that `join` wrote the sealed chain id and
genesis id into `config/consensus.yaml`, the peers into `config/node.yaml`, and
no member key into either; and that the release acceptance index lists every
catalog recipe.

The core-only `audit-log` recipe remains valid for Yano's native host. Native
build and execution acceptance belongs to the Yano repository; Yano X does not
publish or test a native extension distribution.

## Packaged runtime gate

`tooling/devtools/src/test/scripts/final-distribution-runtime-acceptance.sh`
(Gradle task `finalDistributionRuntimeAcceptance`) runs the ceremony on a
devnet from the extracted final archive and proves that:

- a two-operator application is drafted, carded, sealed, and joined; `join`
  refuses a file that does not match the compared fingerprint;
- after `start`, `status --wait live` reports `Live` with every chain's members
  agreeing, and the chain identity carries a consensus profile digest and a
  `sha256:` plugin catalog fingerprint;
- a submitted message finalizes at the same height and state root on both
  nodes, with two certificate signatures, a state proof, and evidence;
- a stopped member restarts and catches up to the same tip and root;
- a third operator joins later and both founders `approve` the card, after
  which the application is live with three members;
- the lead anchors on devnet with `anchor`, and a first anchor is recorded;
- no member, API, or anchor key appears in a node log or a process listing.

`final-distribution-stock-outcomes.sh` exercises the stable recipe outcomes
(audit, registry, approval with effect) on the archive's stock chains, and
`final-distribution-plugin-catalog-acceptance.sh` matches the packaged Maven
bundles to the directory-loaded JVM plugin catalog.

## Release-candidate commands

Run the static and packaged JVM gates with:

```bash
./gradlew :tooling:devtools:appChainReleaseCandidateStaticCheck
./gradlew :tooling:devtools:appChainReleaseCandidateJvmAcceptance
```

The packaged JVM gates start real multi-node devnet clusters and are not part
of `build` or CI. Before tagging, run them together with a live showcase
restart against the exact release ZIP:

```bash
.agents/skills/smoke-yano-x-release/scripts/smoke_release_zip.sh \
  distribution/jvm/build/distributions/yano-x-jvm-<version>.zip
```

Run Yano's native release gates in the matching Yano checkout or staged release
pipeline. They validate the core host and OrderedLog; they do not include Yano X
bundles.

The M6 live devnet regressions additionally verified two-node finality,
state-root parity, proofs, L1 anchoring, multi-chain behavior, query/SSE
surfaces, webhooks, authenticated administration, member rotation, evidence,
snapshots, and metrics. The tracked packaged-runtime scripts remain the
repeatable release gate; platform release jobs should repeat the live devnet
exercise before publishing.

## What is still needed before schema stabilization

Before declaring a v1 schema, collect at least one independently maintained
plugin/catalog exercise and record any compatibility changes it exposes.
Until then, additive alpha evolution is allowed, and `seal` continues to pin
the exact digests of the plugin bundles an application runs.
