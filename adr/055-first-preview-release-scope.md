# ADR-055 — First preview release scope: retire product apps, defer surrounding tooling

- **Status:** Accepted (2026-10-18). Phase 1 is implemented by the change that adds this ADR;
  Phases 2 and 3 are proposed.
- **Supersedes in part:** ADR-040 (product UIs other than eUTxO), ADR-046 (portfolio sequencing),
  ADR-047 (Attest), ADR-048 (Evidence Desk UI), ADR-049 (Trust Registry apps), ADR-050
  (Verifiable Explorer), ADR-051 (DPP Starter apps), ADR-052 (Attestation Feed apps), ADR-053
  (console signing modes).
- **Related:** ADR-030 (repository split), ADR-038 (documentation site), ADR-039 (deployment tool),
  ADR-045 (release readiness), app-layer ADR-031.2 (guided binding editor).

## 1. Context

Yano X is approaching its first preview release. Before this change the build had 63 inventoried
modules. The core of the release, the part users build on and the part that is tested end to end,
is a minority of that code:

| Area | Modules | Role |
|---|---|---|
| Stock state machines, capabilities, composition and bindings | `state-machines/*`, `capabilities/*`, `composition/*` | Core |
| Connectors and effects | `connectors/*`, `sdk/effects-testkit`, `sdk/integration-contracts` | Core |
| Client SDK and proofs | `sdk/client`, `sdk/proof-*` | Core |
| eUTxO ledger, bridge and indexer | `ledgers/eutxo/*`, `fixtures/eutxo-e2e` | Core (experimental), in the default plugin set and the showcase |
| Developer tooling | `tooling/devtools`, `tooling/studio`, `tooling/deployment`, `tooling/spring-boot-starter` | Surrounding |
| Showcase | `examples/showcase`, `examples/showcase-client` | Demonstration, smoke-gated |
| Products | 30 modules under `products/` | Mostly demonstration |

The `products/` tree alone held 30 modules: seven SvelteKit consoles of 2.3k–4.6k source lines
each, whose shared files (`app.css`, layouts, `CopyValue.svelte`, `cbor.ts`, `hash.ts`,
`message-proof.ts`, build configuration) were byte-identical copies, plus clients, CLIs, launchers
and a Maven aggregate (`yano-x-products-client`). Only a few of these modules are reached from the
core, the tutorials, CI or the release smoke test. The rest made the codebase harder to follow,
lengthened every distribution build (seven Node builds and SBOMs), and widened the Maven Central
surface, without being needed for the release.

The app-ledger feature has not had a public release, so per AGENTS.md there is no compatibility to
preserve for modules that are not part of it.

## 2. Decision drivers

1. Keep every core feature, component and state machine. They are tested and are the release.
2. Keep whatever a tutorial, the release smoke test, a required CI gate or the showcase needs.
3. Keep tooling in active use. The deployment tool runs the retained preprod cluster.
4. Remove what nothing in drivers 1–3 depends on. Git history keeps it; nothing is lost.
5. Where a surrounding tool is entangled with the core, plan the untangling first, then remove it in
   its own change.

## 3. Findings

How each group is reached (evidence collected from `settings.gradle`, `config/*.json`,
`distribution/jvm/*.gradle`, `.github/workflows/*`, the smoke script, the tutorials and the
docsite):

| Group | Reached from | Decision |
|---|---|---|
| Evidence: `contracts`, `registry`, `profile`, `client`, `demo-runner`, harness | Tutorials 4 and 5 (`examples/evidence/demo.sh`, `runner.jar`); `build.yml` release contracts and connector fault matrix; `evidence-e2e.yml`, which `tag-release.yml` requires | Keep |
| Evidence Desk UI (`products/evidence/ui`) | Only the ZIP, and a showcase golden test that writes the UI's own fixture. The tutorials' "Evidence Explorer" is the demo runner's built-in page, not this UI | Remove |
| `trust-registry:profile`, `dpp:profile`, `attestation-feed:profile` | Ten test classes in stdlib and devtools that use them as realistic governed data (admission rules, typed views, recipes), and one production use: `appchain bindings recipe dpp|feed`, a generator for the two product starters | Move to a test fixture; remove the generator |
| Trust Registry, DPP and Attestation Feed clients, CLIs, consoles and launchers | Only the ZIP, their own tests, product docs, and the production guide's use of `yano-trust` for actor keys (core equivalent: `appchain role key-proof`) | Remove |
| Attest client, CLI, console | Only the ZIP, product docs, and the product clients above | Remove |
| Verifiable Explorer core, CLI, console, launcher | Only the ZIP and product docs | Remove |
| Cardano History runtime, client, CLI, on-chain validators | The showcase's `cardano-history-chain` (one of the 13 smoke-tested chains); the CLI is the showcase's query and proof tool and the showcase distribution contract requires it | Keep |
| eUTxO console (`products/eutxo/ui`) | The ZIP and the showcase guide; the visual demonstration of the kept eUTxO ledger | Keep |
| `products/client` (Maven aggregate of eight product modules) | Nothing in the repository | Remove |
| Deployment tool (`tooling/deployment`, `deployment/`) | No tutorial, but it deploys and operates the retained Contabo preprod cluster on existing and cloud VMs | Keep |
| `examples/showcase-client` | Manual load and verified-read testing against the showcase (`DEMO_SHOWCASE.md` §8) | Keep |
| Project CLI (`appchain init`, `render`, `doctor`, `prepare`, `plan`, `apply`, `diff`, `drift`, `gitops`, `chain add`, `migrate`): about 7.8k of devtools' 19.2k main lines | Tutorial 9, part of tutorial 8, bindings chapters 1, 4 and 6, the devtools release acceptance gates, and **the only documented way to deploy a binding document** (`init --recipe declarative-composite --bindings`) | Phase 2 |
| Studio (`tooling/studio`, Studio ZIP, the site's `/studio`) | Bindings chapter 6 and part of chapter 1; devtools binding-editor tests and oracles, which read and write Studio's files; the binding-editor release acceptance gate | Phase 3 |
| `ObservationQualification*` in devtools (about 1.5k lines) | Its own Gradle task and one guide | Phase 2 candidate |
| `ledgers/eutxo-zk`, `state-machines/zk` sources | Nothing: excluded from the build since PR #71 | Phase 2 candidate |

## 4. Phase 1: this change

### Removed

- `products/attest` (client, CLI, console)
- `products/explorer` (core, CLI, console, launcher)
- `products/trust-registry` client, CLI, console and launcher
- `products/dpp` client, CLI, console and launcher
- `products/attestation-feed` client, CLI, console and launcher
- `products/evidence/ui` and the showcase's `EvidenceDeskGoldenTest`
- `products/client` (`yano-x-products-client`)
- `appchain bindings recipe dpp|feed`, the DPP and feed starter generator
- Their docs: `docs/appchain/{ATTEST,ATTESTATION_FEED,DPP_DEMO,DPP_STARTER,EVIDENCE_DESK,EXPLORER,TRUST_REGISTRY}.md`,
  `docs/site/products-{evidence-desk,trust-registry}.md`, the matching site pages and their six
  illustrations

That is 20 inventory entries (17 deleted, three folded into one test fixture) and about 104k lines.

### Kept, with consequences

- The governed genesis builders of the three product profiles move to one test-fixture module,
  `fixtures/governed-recipes`; the product-only classes (status lists, TRQP, the feed datum) and
  the products' classpath-firewall tests are dropped. The generator logic is a devtools test helper
  (`BindingRecipe`), and the distribution acceptance test still validates both generated recipes
  with the packaged launcher. Bindings chapter 3 and the CLI guide teach from the shipped
  `dpp-approval.yaml` and `feed-approval.yaml` examples instead. `products/` now holds only
  `evidence`, `cardano-history` and `eutxo/ui`.
- The Cardano History client is no longer folded into an aggregate; it becomes an internal library
  (`publishToCentral: false`) used by its CLI.
- Test fixtures are recognised by publication type (`test-fixture`) instead of by path in the root
  build, the SBOM wiring and the devtools release gate.
- The JVM ZIP no longer contains `tools/yano-{attest,trust,explorer,dpp,feed}`,
  `product-ui/{attest,evidence,trust-registry,explorer,dpp,attestation-feed}` and their SBOMs, or
  `examples/{trust-registry,explorer,dpp,attestation-feed}`. It still contains
  `tools/yano-appchain`, `tools/yano-deploy`, `tools/yano-cardano-history`, `product-ui/eutxo`,
  `examples/evidence`, `examples/showcase` and `studio/`. The release still publishes four archives.
- Every runtime plugin bundle is unchanged, so `config/jvm-distribution-v1.json` and the plugin pack
  are unchanged.
- `verifySourceExtractionProvenance` no longer compares `movedModuleCount` with the current
  inventory. The count is an immutable record of the split, not a floor: PR #71 already removed 11
  extracted ZK modules, and the inventory checks govern what exists now.
- The production guide (`docs/appchain/PRODUCTION_DEPLOYMENT.md`) now uses the core CLI instead
  of `yano-trust`: `appchain role public-key` and `role key-proof-signature` for actor keys,
  `genesisRecords` with `appchain render` for a governed map's genesis, and `role bootstrap` for
  later actors. The proofs concept page names `appchain state verify --trusted-root` and the Java
  client's `PortableProofBundle` as its examples.
- The site's Products section lists Evidence, Cardano History and the eUTxO ledger.

### Where the removed products go

The removed products move to [yano-x-products](https://github.com/bloxbean/yano-x-products), where
each builds on its own against published Yano X artifacts and the Yano X JVM distribution, with its
own deployment and test instructions. Dependency direction stays one way: products depend on Yano X,
never the reverse. Product-specific tooling, such as the governed genesis generators for DPP and the
feed, lives there with its product. ADRs 047–053 remain the design record.

## 5. Phase 2 (proposed): the project CLI and small dead code

The project lifecycle is the largest surrounding tool, but it cannot simply be cut:

1. **Give bindings a deployment path that does not need `init`.** Today `AppChainProjectResolver`
   compiles a binding document into the chain's IR setting, and the cluster launcher cannot load
   bindings. Options: a `--bindings` option on `appchain cluster start`, or a documented
   `bindings compile` output that the chain configuration takes directly. Rewrite bindings chapters
   1, 4 and 6 to use it.
2. **Separate the plugin, role and config commands from the project model.** They share only a hash
   helper, blueprint size constants and a few record types (`AppChainComponentCatalogLoader`,
   `AppChainMetadataTrustVerifier`, `RoleBootstrapPlan`).
3. **Rebase the release acceptance gates on the cluster launcher.** `distributionTest`,
   `finalDistributionRuntimeAcceptance` and the smoke script run `init`, `render`, `gitops` and
   `drift`.
4. **Then decide** between keeping a small `init`/`render` for pilots (tutorial 9) and removing the
   project lifecycle with GitOps export, doctor, drift and migrate. The deployment tool already
   covers VM deployment.

The same change can remove `ObservationQualification*` and the ZK source trees.

## 6. Phase 3 (proposed): Studio

Studio is experimental and unreleased (ADR-031.2) but deeply wired: devtools integration tests and
oracles read and write its files, the binding-editor acceptance gate runs its browser tests, and the
docsite mirrors it. Removing it needs:

- the devtools binding-editor tests and oracles moved off Studio's source tree;
- bindings chapter 6 removed and chapter 1 §7 rewritten;
- the Studio ZIP dropped from `release.yml` and `bloxbean-snapshot-dist.yml` (four archives to three);
- the docsite's `/studio` mirror, its Playwright suite and `docs-deploy.yml` check removed.

Decide after Phase 2, when the binding authoring story without a project is clear.

## 7. Consequences

- The first release ships the core plus three products, each reached from a tutorial or the
  showcase. Every remaining module has a consumer in the release.
- The distribution build drops six Node builds and SBOMs, and five CLIs. The JVM ZIP barely shrinks
  (from 513 MB to 511 MB, 900 entries): the removed consoles were small static sites, and the
  Yano host and plugin bundles dominate its size. The gain is in source, build and review surface.
- Maven Central drops `yano-x-products-client`. No core artifact changes.
- The removed products' designs and code remain in ADRs 040 and 046–053 and in git history.

## 8. Verification

Run on macOS arm64 with Java 25 against Yano `0.1.0-pre18`:

- `./gradlew clean build -PskipSigning=true`: passes, including `distributionCheck`,
  `verifyArtifactInventory`, `verifySourceExtractionProvenance` and `verifyJvmOnlyBuild`.
- The release smoke script on the built `yano-x-jvm-0.1.0-pre4.zip`: all 14 gates pass (runtime
  acceptance, stock outcomes, 13-chain showcase quickstart, `run all`, verify, restart and persisted
  tips).
- Docs site: `npm run build` with `YANO_X_REQUIRE_LISTED_DOCS=1` passes; 86 pages, every internal
  link resolves; 219 browser checks pass.
- The kept Cardano History chain was exercised live on a fresh showcase: it recorded L1 epochs,
  `yano-cardano-history status` and `query params` answered, and after
  `./showcase.sh anchor bootstrap cardano-history-chain` the `proof params` bundle was generated at
  the L1-confirmed anchor and `verify --bundle` returned `ROOT_VERIFIED_ANCHOR_UNCHECKED`.
