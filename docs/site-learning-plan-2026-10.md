# Interactive learning plan for yano-x.io — October 2026

Scope: every page in `www/` (74 routes), the landing page, and the repository
documents and ADRs that the site does not publish yet. Branch:
`feat/docsite-interactive-illustrations`, stacked on PR #28.

This is a plan, not an implementation. It records what the site gets wrong today,
the illustration system we propose, the order in which to change pages, and the
pages that are missing.

## 1. Summary

- **The site is almost all text.** 74 pages carry 11 Mermaid diagrams on 7
  pages. No tutorial, state-machine, bindings, deployment, or most product pages
  has any visual. Mermaid renders in its default palette in light mode and is hard
  to read as a block diagram.
- **Correctness has to come first.** The two pages that introduce the core model
  describe a consensus protocol that the host no longer runs. Tutorials 1 and 2
  fail at the second command for anyone using a release ZIP. Roughly 40 other
  statements or commands are wrong or stale. An animation of a wrong model is more
  convincing than wrong prose, so every illustration ships with the text fix for
  its page.
- **One engine, five patterns.** Block diagram, step-through, simulator,
  explorer, and chooser. About 60 illustrations, many reused across pages, so the
  same "life of a message" or "effect lifecycle" appears identically wherever it
  is taught.
- **Mermaid is replaced by block diagrams.** All 11 diagrams become branded
  block diagrams rendered at build time. A block diagram is also the static first
  frame of each step-through, so readers without JavaScript see the same picture.
- **About 25 new pages** cover concepts the site uses but never explains: life of
  a message, thresholds and faults, chain identity, the proof trust ladder, where
  data lives, recovery, who signs what, a glossary, `ordered-log`, typed views,
  and more.
- **Recommended first pages:** What is an app chain?, Consensus and finality,
  State and proofs, Your learning path, Tutorial 1, Architecture, then the
  state-machine index and `kv-registry` as the first simulators.

## 2. Method

Six parallel reviews, one per section plus a coverage gap analysis, read every
page in full, then read the implementing source and ADRs. Every finding below has
a `path:line` reference in the review notes. These headline findings were
re-checked directly:

- the consensus engine is the ADR-036 two-phase protocol
  (`yano/runtime/.../appchain/AppChainEngine.java:38-44`);
- the release ZIP keeps the base Yano one-chain config
  (`distribution/jvm/jvm-distribution.gradle:211`;
  `tooling/devtools/src/test/scripts/final-distribution-stock-outcomes.sh:22-25`);
- the tutorial 8 `apply` signature (`yano/core-api/.../AppStateMachine.java:129`);
- `balances` stores `BigInteger.toByteArray()`
  (`state-machines/stdlib/.../BalancesTransitions.java:84`);
- `role-approvals` claims native packaging; `docs/core-host.md` is a 10-line stub
  that eight pages cite as the internals guide;
- the plugin ladder's "rung 3 never affects the state root"
  (`docs/site/plugins-overview.md:24-26`);
- chapter 7's stale "declare no facts" sentence
  (`docs/appchain/bindings/07-admission-rules.md:202`);
- `config explain --key` (`AppChainDevtoolsCli.java:46`);
- Cardano History routes (`CardanoHistoryDomainApiTest` uses
  `epochs/{e}/parameters`);
- the `role-evidence-v1` preset value in
  `appchain-first-party-metadata.json:156`.

## 3. Fix first: accuracy

### 3.1 Wrong on the core learning path

| Page | Today | Correct | Evidence |
|---|---|---|---|
| Consensus and finality; What is an app chain? (sequence diagram); `APP_CHAIN_OVERVIEW.md:93` | One vote round; the proposer publishes the certificate | PROPOSE → PREPARE → PreparedQC → COMMIT → FinalityCert. Any member reaching threshold assembles the QC and the certificate. Timeouts lead to a certified view change. | `AppChainEngine.java:38-44, 1749-1843`; Yano `docs/APP_CHAIN_CONSENSUS_GUIDE.md:117-203` |
| Consensus; What is an app chain? | Rotating leader follows L1-slot windows; "fixed: one member proposes every block" | `leader = sorted(members)[(initial + view) mod n]`; rotating uses `initial = blake2b(contextDigest‖parentHash)`. A fixed leader is replaced after a certified timeout. | `AppChainEngine.java:1702-1722`; consensus guide `:214-231` |
| Consensus; What is an app chain? | The client signs the message | The ingress member signs the envelope with its member key. REST takes `{topic, body}`. Business authority is an actor signature inside the body. | `AppChainSubsystem.java` (envelope signing); Yano `docs/appchain/submission.md:3-6`; `docs/APP_CHAIN_DOMAIN_ROLES.md:25-28` |
| Consensus; state-machines index (2 links); tutorial 3; kv-registry; doc-trail; reference shelf; SPI and manifest | "Deeper reading" and `ordered-log` links go to `docs/core-host.md` | Link the released Yano consensus guide and `ordered-log.md`, pinned to the Yano version | `docs/core-host.md` is 10 lines |
| Tutorials 1 and 2 | `registry-chain` and `effects-chain` steps | The release ZIP configures only `orders-chain`. Fix the distribution (see 3.3). | `jvm-distribution.gradle:211`; acceptance script `:22-25` |
| Tutorial 2 §4 | Compares the root before and after to show a no-op | Every block writes a block-message-root record, so the root always changes. Compare the proven entry instead. | `AppChainSubsystem.java:1014`; `FinalizedBlockMessageRootIndexTest` |
| Tutorial 8 | `apply(AppBlock, AppStateWriter)` | `apply(AppBlockExecutionContext, AppStateWriter, AppEffectEmitter)` | `AppStateMachine.java:129` |
| Plugin framework index; Choosing a recipe; AI starter pack | Ladder omits declarative bindings. "Rung 3 never affects the state root." "The order is code." | Add the bundled `declarative-composite` rung. I/O runs outside `apply`, but outputs re-enter as ordered, certified inputs. Order is committed profile data (Java composite or compiled IR). | `docs/appchain/bindings/README.md:3-6`; consensus guide `:100, 158, 277` |
| kv-registry, approvals, balances, doc-trail, tutorial 3 | Config snippets | They omit `state.commitment-profile`, `state.format-fingerprint` and `state.genesis-id`, which the host requires together | Yano `StateCommitmentIdentity.java:86-89` |

### 3.2 Wrong commands, references and details

**Tutorials and deployment**

- **Tutorial 6:**
  - Remove the `app/` paths; docs ship under `<dist>/docs/`.
  - `--node-config-dir` does not exist; the setting is the env var `YANO_CLUSTER_NODE_CONFIG_DIR`.
  - Say which config form applies: single-chain or `chains[i]`.
- **Tutorial 1:**
  - "Change `--threshold 3`" fails on a retained cluster.
  - The fixed `sleep 3` before the proof is flaky; use the poll loop from configure.
- **Tutorial 3:**
  - `app/config` should be `$YANO_HOME/config`.
  - `evidence-v1-gated` is a composite preset, not a machine.
  - `authenticated-map` is missing.
- **Tutorials 4 and 5:**
  - `products/evidence/harness` should be `examples/evidence` in the ZIP.
  - Docker Compose and `demo.sh prepare` are unstated prerequisites.
  - Tutorial 5 uses an undefined `$YANO_APPCHAIN_API_KEY`.
  - Tutorial 5 Track A is not runnable.
- **Operators:** use `--file yano-x-jvm-<version>.zip`, not `yano-showcase.zip`.
- **Configure:** `set -a; . secrets/node0.env` also exports the signing key.

**Concepts**

- **Effects:**
  - The `l1-anchored` gate checks the anchor high-water mark minus a margin; it has no stability depth.
  - The default `ResultPolicy` is `NONE`.
  - Identity is `EffectId(chain, height, ordinal)`.
  - Fencing is node-local.
  - `cardano.payment` must say "no material funds until FX-002".
- **Anchoring:**
  - "Try it" lacks `--anchor-mode script` and the `<chain>` argument, and starts on preprod; use the tutorial 7 devnet sequence.
  - `anchor.walletAddress` exists in script mode only.
  - Mention the `max-interval-minutes` trigger.
- **Determinism:**
  - Use `block.timestamp()`; `l1Slot` is 0 unless `l1.stability-depth > 0`.
  - The HashMap example is wrong.
  - A minority divergence stalls only that member.
- **Consensus:**
  - `AGREED` compares current roots only, not heights.
  - Threshold rules are absent. The default is 1; startup requires `2t − n > f` and `t ≤ n − f`.
- **State and proofs:**
  - The profile is chosen at genesis (MPF default, JMT off-chain only).
  - The trust vocabulary has five levels.
  - `finalized-message-v1` is intrinsic only to `ordered-log`.

**State machines**

- **`balances`:**
  - The value is `BigInteger.toByteArray()` (200 is stored as `00c8`).
  - The Java example's transfer is a no-op.
  - Receive-only accounts are not explained.
- **`approvals`:**
  - `0` means no deadline, and expiry is a strict `>`.
  - A REJECT after the deadline gives EXPIRED.
  - The `ae/p/<id>` staging key and `effects.max-payload-bytes` are undocumented.
- **`kv-registry`:**
  - Keys over 256 bytes are rejected at admission.
  - A non-owner write rejects the cascade inside a composite.
- **`doc-trail`:** the reference slot is required text (`""` when absent).
- **`role-approvals`:**
  - It is not native.
  - Expiry runs in each block's maintenance pass.
  - CANCEL, the status table, the result-code table and one-decision-per-actor are missing.
- **`authenticated-map-validation`:**
  - The error table stops at code 12 of 27.
  - Admission-only codes should be marked.
- **`authenticated-map`:**
  - PUT_IF_ABSENT on a tombstone returns `REVOKED(6)`.
  - CAS needs at least one precondition.
  - The topic is enforced.
  - Define `logicalValueHash`.
- **Client artifact:** `yano-x-client`, not `appchain-client`.

**Bindings**

- The host pin (`0.1.0-pre17`, API level 11) is below the composite manifest's `minLevel: 12`. Current features need the unreleased host.
- "API level 12" alone is not sufficient: typed-view host API was added without a level bump.
- The ADR-031.4 in-place rule-layout break is undocumented while `EXECUTION_VERSION` stays `1.2.0`.
- Chapter 7 "other stock kernels declare no facts" is stale.
- Chapter 6 omits rule `reads` and write views.
- **CLI reference:**
  - The catalog fields `ruleValueViews` / `ruleWriteFields` / `ruleWriteCoverageFields` are missing.
  - The source forms `{context}`, `{command}`, `{param}`, `{config}` and `{fact}` are missing.
- The chapter 5 ingress row is stale: REST now returns `details{rule, deny, write}`.

**Plugins**

- The manifest has `yanoApi {min, max, minLevel}`; there is no "max level".
- The activation order differs from the code.
- The node does not verify publisher signatures.
- Runtime loading uses one shared parent-first loader, which is not a sandbox.

**Products**

- **Cardano History:**
  - `params/{epoch}/document` is a state key, not a route. The real routes include `epochs/{e}/parameters[/fields/{id}]`, `stake/...`, `dreps/...` and `proposals/...`.
  - The artifact is `yano-x-cardano-history`.
- **Evidence:** the recipe in "Run it" selects `role-evidence`, not the stated default profile.
- **Trust levels:**
  - They are cited as ADR-037 on one page and ADR-047 on others.
  - The pages list three of the five values.
- **Attestation Feed:** the `REVOKED` disposition is missing.
- **Trust Registry:** run-it needs `yano-trust` on PATH, or use `examples/trust-registry/registry.sh`.
- **Evidence Desk and landing page:** "showcase archive" is stale; it is now `yano-x-jvm-<version>.zip`.

**Reference**

- **CLI:**
  - `reset --yes` is not a cluster command.
  - `anchor-bootstrap` needs `<chain-id>`.
  - Missing: `chain add`, `plan`/`apply --plan`, `start-check`, `config effective|explain`, `role`, `authenticated-map`, `state verify|integrity|snapshot`, and cluster `kv|logs|keys|chains|threshold set`.
- **Configuration:**
  - The syntax is `config explain [--format] [--metadata] <property>`.
  - Profile-mode and lag/TTL keys are new-chain-only, not `GOVERNED_ACTIVATION`; this is a metadata JSON fix.
- **REST:**
  - These endpoints are chain-scoped only: `proof-subjects/*`, `admin/members*`, `admin/threshold`, `snapshots/*`, `identity` and `query`.
  - About 40% of endpoints are undocumented.
  - Plugin routes accept POST and can be PRIVILEGED.
- **Modules:**
  - The inventory has 8 publication types; the page explains 2.
  - The generator order list names types that do not exist.

**Site-wide**

- **Landing hero:**
  - "SIGNED APPLICATION EVENT" implies the caller signs.
  - The "Certify" stage describes proofs rather than threshold certification.
  - It starts at stage 2.
- **AI page:**
  - The Cursor rule needs `alwaysApply` frontmatter.
  - `curl -o CLAUDE.md` overwrites an existing file.
- **Sidebar:** state-machine labels render literal backticks (`` `kv-registry` State Machine ``). Tutorial labels repeat "Tutorial N —".
- **Studio:** `/studio/` is not in the sidebar.

### 3.3 Found outside the docs

These need code or config changes, not documentation edits. They are listed here so the docs fixes do not paper over them.

| Issue | Repository | Status |
|---|---|---|
| The release ZIP lacks the stock `registry-chain` and `effects-chain` config; the acceptance test copies it in | yano-x | Verified; root cause added to [yano-x#22](https://github.com/bloxbean/yano-x/issues/22) |
| `machines.composite.preset` metadata offers `role-evidence-v1`, which the composite machine rejects; this reaches Studio and `/ai/catalog.json` | yano-x | Verified; [yano-x#29](https://github.com/bloxbean/yano-x/issues/29) |
| `cluster.sh` has no single-node stop, which blocks an outage and catch-up tutorial | yano-x | Reported |
| `PRODUCTION_DEPLOYMENT.md`: "2-of-3 tolerates one failure and one dishonest member" (crash-safe only, `f = 0`) | yano-x | Reported |
| Typed-view host API added without a plugin API level bump | yano | Reported |
| The `l1-anchored` effect gate has no stability-depth check, while ADR-010 F7 promises one | yano | Verified in code; [yano#164](https://github.com/bloxbean/yano/issues/164) asks code or ADR |
| Governed threshold change validates only `1 ≤ t ≤ n`; `node join` may break `2t − n > f` | yano | Suspected from code; [yano#163](https://github.com/bloxbean/yano/issues/163) has repro steps |
| The consensus guide says duplicate signers are "ignored", but the code voids the certificate. The user guide lists 7 anchor datum fields; the CDDL has 11. | yano | Reported |

## 4. The illustration system

### 4.1 Five patterns

| Pattern | Teaches | Interaction | Example |
|---|---|---|---|
| **Block diagram** | Structure: what exists and how it connects | Static. Zones, labelled blocks, straight connectors, legend. | Two-plane design, node stores |
| **Step-through** | A flow over time across actors | Actor lanes, numbered step chips, back / Play / next, view toggle (for example *Client's view / Member's view*), Present (full screen) | Life of a message, effect lifecycle |
| **Simulator** | Rules | Build an input. Each rule shows ✓/✗ in evaluation order with its exact code, and you see the state change. Includes "Try to break it" presets. | `kv-registry` ownership, quorum calculator |
| **Explorer** | A structure you can inspect | Click a key, header field, YAML line or directory to see what it binds and what checks it | Proof path, chain identity |
| **Chooser** | A decision | Questions lead to a recommendation, drawn from the repository catalogs | Which state machine? Which extension rung? |

### 4.2 Visual language

- **Brand:** ink and mint, matching the landing page.
- **Panel title:** mono uppercase, for example `KV REGISTRY · STEP 2/5`.
- **Data label:** every panel carries `ILLUSTRATIVE DATA · rules match <class>`.
- **One actor palette site-wide:** client, member, leader, executor, external system, Cardano, business actor/organization.
- **One status vocabulary:** `ADMITTED`, `PENDING`, `FINAL`, `ANCHORED`, `REJECTED`, `NO-OP`.
- **Stage labels:** every rule names its stage, **Admission** (no state, no receipt) or **Apply** (finalized outcome), because "finalized ≠ changed" is the most repeated misconception.
- **Themes:** light and dark tokens; no Mermaid default palette.

### 4.3 How illustrations get onto pages

- **Marker:** a page carries `<!-- illustration: kv-ownership -->`. It works in authored `www` pages and in imported `docs/` markdown. The comment is invisible on GitHub and in the JVM ZIP, so `docs/` stays plain markdown.
- **Build-time render:** a remark plugin, modelled on `scripts/remark-mermaid.mjs`, renders the illustration at build time. The output is the block diagram plus the numbered steps, so the content works without JavaScript, is searchable, and appears in `llms-full.txt`.
- **Interactivity:** a client script adds it as dependency-free custom elements. It loads only on pages that have an illustration, like the current Mermaid loader. It respects reduced motion: no autoplay, and Play advances one step at a time.
- **Data:** one file per illustration under `www/src/illustrations/`, holding lanes, steps, narrative, rules, scenarios and sources.
- **Mermaid removal:** each Mermaid diagram is replaced, and the Mermaid loader and dependency are removed when none remain.

### 4.4 Keeping illustrations correct

- **Source anchors.** Each illustration lists `sources`: a repository path and an identifier that must appear there (operation, result code, event, field, CLI flag). A build test fails when an identifier disappears from its cited file. A rename then breaks the build, not the explanation.
- **Golden scenarios for rule simulators.** A JUnit test runs each simulator scenario through the real transition code (`decide` / `evaluate` / `Aggregation`) and writes golden JSON. The browser displays the recorded outcomes; it does not re-implement the rules. Bindings simulators use `dry-run --report` output, as Studio's scenario fixtures already do.
- **Text before animation.** An illustration does not ship before its page's text fix.
- **Example data is labelled.** Real identifiers are used only where they are stable contract names.

### 4.5 Quality bar

- **Keyboard:** chips and controls are native buttons; arrow keys move between steps.
- **Screen readers:** step changes are announced through a polite live region.
- **Mobile:** no horizontal scroll at 360 px; lanes stack.
- **Themes:** correct in both, including switches mid-animation.
- **Accessibility audit:** axe-clean.
- **No network calls.**
- **Tests:** Playwright extends `tests/docs.spec.mjs`, covering every illustration in both themes, keyboard navigation, reduced motion and mobile.

## 5. Which pages to change first

### Wave 0 — foundation (first PR in this stack)

1. **Illustration engine:** the five patterns, the marker plugin, the static fallback, the client enhancement, source-anchor tests and Playwright coverage.
2. **Mermaid replacement:** replace all 11 diagrams with block diagrams:
   - Architecture ×4: four layers, two-plane design, components, composite profile.
   - What is an app chain? ×2: organizations, end-to-end flow.
   - Why Yano X: the boundary.
   - Scaffold, sign, install: seven steps.
   - Evidence: product flow.
   - eUTxO and ZK: three layers.
   - Consensus: the round.
3. **Site polish:**
   - Clean sidebar labels.
   - A standard page header for tutorials and concepts: level · time · you'll learn · prerequisites.
   - "✓ You should see" callouts.
   - Studio in the sidebar.
4. **Text fixes:** the section 3.1 fixes for the wave 1 pages.

### Wave 1 — the core mental model (first pages)

These pages sit at the top of the learning path. They hold the most wrong content, and their illustrations define the lanes and vocabulary that every later illustration reuses.

| Order | Page | Changes | Illustrations |
|---|---|---|---|
| 1 | What is an app chain? | Rewrite the flow; vocabulary before flow; split the table into "guarantees" and "plumbing" | `app-chain-overview` (block), `message-lifecycle` (step-through) |
| 2 | Consensus and finality | Rewrite around the two-phase round, view change, quorum rules and static vs governed membership. Move membership CLI to Deployment. | `consensus-round`, `quorum-calculator`, `block-anatomy` |
| 3 | State and proofs | Show what a proof is; move storage and SPI notes to Reference | `proof-path`, `trust-ladder` |
| 4 | Your learning path | Time estimates; a visual for "what you verified" | `what-did-you-verify`, `concept-map` (first version) |
| 5 | Tutorial 1 | After the ZIP fix: split off the load test and onboarding, add checkpoints | `first-chain-finality` (synced to the terminal), `where-data-lives` |
| 6 | Architecture | Enumerate the layers; replace 4 diagrams; move data separation and deployment shape to Deployment | `two-plane-sorter` |
| 7 | Landing | Fix hero copy and start at step 1; add a "Learn by exploring" strip and a products strip | Updated `HeroPipeline` linking to the full explainers |

### Wave 2 — state machines

These are the most rule-shaped pages, so they get the most from simulators.

- **Page skeleton** for every state-machine page:
  1. *At a glance:* id, maturity, commands, state key, proof subject and claims, result codes, events.
  2. *How it works:* the illustration.
  3. *Usage.*
  4. *Advanced:* typed views, governed flows, composites.
- **Index:** `sm-chooser` plus an "Other state machines" table (preview, experimental, providers).
- **`kv-registry`:** `kv-ownership`.
- **`approvals`:** `approvals-lifecycle`.
- **`balances`:** `balances-ledger`.
- **`doc-trail`:** `doc-trail-chain`.
- **`authenticated-map`:**
  - `authmap-lab` and `authmap-presence`.
  - Split governed authoring into its own page.
- **Authenticated-map validation:** `authmap-validation-pipeline` plus the complete code table.
- **`role-approvals`:** rewrite (lifecycle, status, result codes) plus `role-approval-walkthrough`.
- **New `ordered-log` page:** `ordered-log-journey`.
- **Tutorial 3:** reuse `sm-chooser`, add `wire-builder`.

### Wave 3 — effects, anchoring, observations and their tutorials

| Page | Illustrations |
|---|---|
| Effects; tutorial 6; tutorial 1 §7 | `effect-lifecycle` |
| Anchoring | `anchor-advance` |
| Tutorial 7 | `verify-ladder` plus runnable verification commands |
| Observations (expanded to effects-page depth) | `observation-round` |
| Tutorial 2 | `kv-ownership` in its tutorial variant (the root changes every block) |
| Tutorial 4 and the Evidence product | `evidence-pipeline` with the status simulator |
| Tutorial 5 | `role-approval-walkthrough` in its policy variant |
| Determinism rules; tutorial 8 | `divergence-sim` |
| Tutorial 9 | `pilot-secrets-and-render` |

### Wave 4 — compose and extend

- **Sidebar:** reorder so bindings come before plugins ("smallest extension first").
- **Bindings index:** `cascade-anatomy`.
- **Chapter 1:** `receipt-anatomy`, with the context JSON folded into a collapsible block.
- **Chapter 2:** `binding-playground`.
- **Chapter 3:** `approval-across-blocks`.
- **Chapter 4 (renumbered):** admission rules, with `rule-slots-timeline` and `rule-evaluator`.
- **Chapter 5 (new): typed views.** Split out of chapter 7; carries `typed-view-explorer` and `quantifier-stepper`.
- **Operations:** `where-did-my-command-stop`.
- **Upgrades reference:** `upgrade-preflight`.
- **Plugin framework index:** `extension-ladder`, merged with the recipe chooser.
- **SPI and manifest:** `plugin-activation`.
- **Tutorial 8:** hands-on only; link the plugin pages instead of duplicating them.

### Wave 5 — new Learn pages

See section 6.

### Wave 6 — operate, products, reference

- **Deployment:**
  - `config-layers` (configure)
  - `chain-add-lifecycle` (add-chain)
  - `member-onboarding` (operators, tutorial 1)
  - `topology-ports` (deployment index)
  - `showcase-quickstart` (local showcase)
- **Products:**
  - Apply one template to every product page: Problem → Who it is for → Actors and flow → Proves / does not prove → Try it → Modules → Status, with ADR numbers moved into Status.
  - Illustrations: `attest-lab`, `desk-approvals`, `status-registry`, `history-field-proof`, `explorer-ingest`, `passport-explorer`, `feed-aggregation-sim`, `eutxo-tx-lab`, `product-chooser`.
- **Reference:**
  - `rest-explorer`, built from a checked-in OpenAPI snapshot.
  - `config-explorer`.
  - `capability-graph`, with requires/implies/conflicts.
  - `cli-explorer`, built from a devtools-emitted CLI surface file.
  - Each needs a generator change; none is hand-edited.

## 6. New pages

| Route | Title | Level | Covers | Sources | Illustration |
|---|---|---|---|---|---|
| `/learn/` | Learn by exploring | all | Hub for every explainer, plus the concept map | `concepts.json` | `concept-map` |
| `/learn/life-of-a-message` | Life of a message | beginner | Admission vs sequencing vs finality vs apply; 202 ≠ final; a finalized rejection is a no-op that consumes the id; pool TTL; no messages, no block | Yano `submission.md`; consensus guide §2–4 | `message-lifecycle` |
| `/learn/thresholds-and-faults` | Thresholds, faults and liveness | beginner → intermediate | n, t, f; `2t − n > f`; `t ≤ n − f`; crash vs Byzantine; fixed-leader handover | Yano ADR-036 `:170-200`; `ConsensusQuorum.java:16-21` | `quorum-calculator` |
| `/learn/consensus-round` | Inside a round | advanced | 13 follower checks, PreparedQC, COMMIT, locks, view change | Consensus guide `:117-203` | `consensus-round` (full) |
| `/learn/chain-identity` | What makes a chain this chain | intermediate | Commitment profile, format fingerprint, genesis id, application id, height-1 markers, consensus-context digest, capability manifest, anchor datum | yano-x app-layer ADR-025 §4; `StateCommitmentIdentity.java`; app-layer ADR-033 `:154-190` | `chain-identity` |
| `/learn/proof-trust-ladder` | How much does this proof prove? | intermediate / advanced | The five trust levels; absent vs pruned vs revoked; independent anchor verification | `COMPOSABLE_STATE_AND_PROOFS.md:75-163`; `PROOF_LAB.md`; `ProofLabVocabulary.java:22-28` | `trust-ladder` |
| `/learn/where-data-lives` | Stores and indexes | intermediate / operator | `chainstate` / `appchain-chainstate` / `appchain-indexers`; trie vs framework column families; authenticated vs rebuildable indexes | User guide `:880-904`; app-layer ADR-033 `:286-296` | `where-data-lives` |
| `/learn/recovery` | Restart, catch-up and snapshots | intermediate / operator | Catch-up batches and checks; restart invariants; the pool is lost by design; snapshot restore | Consensus guide `:294-345`; `SnapshotManifest.java` | `recovery` |
| `/learn/who-signs-what` | Keys, identities and the trust model | beginner → advanced | Member, actor, API, anchor, connector and publisher keys; proven vs trusted; plugins are not sandboxed and not signature-checked at runtime | `APP_CHAIN_DOMAIN_ROLES.md`; `APP_CHAIN_OVERVIEW.md:366-390`; app-layer `open_item.md` PLG-001 | `who-signs-what` |
| `/learn/authenticated-snapshots` | Authenticated snapshots | advanced | Period datasets and nested proofs | `AUTHENTICATED_SNAPSHOTS.md` | `snapshot-proof` |
| `/reference/glossary` | Glossary | all | Generated from `concepts.json`; term hovercards across the site | All of the above | — |
| `/start-here/faq` | FAQ | beginner | Common questions, linked to Learn | Section 3 misconceptions | — |
| `/start-here/use-cases` | Use cases | beginner | Port of `APP_CHAIN_USE_CASES.md` | Same | `product-chooser` |
| `/state-machines/ordered-log` | `ordered-log` | beginner | Summary of the built-in machine plus an upstream link (no import from the sibling repo) | Yano `ordered-log.md:291-330` | `ordered-log-journey` |
| `/tutorials/operate-your-cluster` | Operate your cluster | intermediate | Load test, member onboarding, outage and catch-up, restart (moved out of tutorial 1). The catch-up part needs a single-node stop in `cluster.sh`. | `cluster.sh` | `member-onboarding`, `recovery` |
| `/tutorials/observe-external-data` | Observe external data | advanced | The shipment reference workflow | `shipment-observation-reference.md` | `observation-round` |
| `/tutorials/verify-from-java` | Verify independently from Java | intermediate | `AppChainClient` and proof verification with pinned inputs | `sdk/client` | `trust-ladder` |
| `/tutorials/settle-on-cardano` | Settle on Cardano (devnet) | advanced | Settlement vs anchoring | Showcase `SETTLEMENT_CHAIN.md` | `settlement-flow` |
| `/bindings/typed-views` | Typed views and state reads | advanced | Reads, value views, write view, coverage, post-state facts | yano-x app-layer ADR-031.4; `BindingRules.java` | `typed-view-explorer`, `quantifier-stepper` |
| `/bindings/how-cascades-run` | How a cascade runs | intermediate | Breadth-first order, overlays, budgets, receipt anatomy | `EventBindingWorkflow.java`; `BindingReceiptV1.java` | `cascade-anatomy`, `receipt-anatomy` |
| `/reference/bindings-dsl` | Bindings DSL quick reference | reference | Every YAML key, source form, operator, function and scope | `BindingDocumentCompiler.java` | — |
| `/plugins/make-your-machine-bindable` | Make your machine bindable | advanced | The `TransitionKernel` contract | Composition contracts | — |
| `/plugins/how-plugins-load` | From JAR to running provider | advanced | Tooling-time vs node-time checks, the shared loader, trust boundaries, diagnostics | Yano ADR-011.1/011.2; `PluginCatalogBuilder.java` | `plugin-activation` |
| `/deployment/production` | Production deployment | operator | Import `PRODUCTION_DEPLOYMENT.md` after fixing its quorum claim; genesis ceremony | Same | `genesis-ceremony` |
| `/deployment/connectors` | Connectors | operator | Import `OPTIONAL_CONNECTORS.md` | Same | — |
| `/deployment/profile-governance` | Changing a live application | advanced / operator | STAGING → SEALED → READY → SCHEDULED → ACTIVATED; VOID | `APP_CHAIN_PROFILE_GOVERNANCE.md`; yano-x app-layer ADR-015 | `profile-governance` |
| `/deployment/troubleshooting` | Troubleshooting | operator | Symptom → cause → fix: port busy, not final yet, MISMATCH, chain missing, 429, stall | User guide §19; product troubleshooting sections | `troubleshoot-tree` |

`docs/site/concepts.json` is one data file holding concept nodes, glossary terms
and prerequisite edges. It drives:

- the concept map and the glossary;
- the "Before this / Next" footers;
- maturity badges, taken from `CAPABILITIES.md`;
- the `llms.txt` grouping.

## 7. Information architecture

1. **Start here:** your learning path, what is an app chain, why Yano X, local showcase, release downloads, build from source, use cases, FAQ.
2. **Learn** (replaces Concepts), in this order:
   1. life of a message
   2. thresholds and faults
   3. chain identity
   4. state and proofs
   5. proof trust ladder
   6. where data lives
   7. recovery
   8. effects
   9. observations (preview)
   10. anchoring
   11. who signs what
   12. determinism
   - Advanced subgroup: architecture, inside a round, authenticated snapshots.
3. **Tutorials:**
   1. first chain
   2. registry and proofs
   3. approvals and effects
   4. anchors and verification
   5. business roles
   6. evidence capstone
   7. plugins
   8. demo to pilot
   9. operate your cluster
   10. verify from Java
   11. observe external data
   12. settle on Cardano
4. **State machines:** chooser, `ordered-log`, the stock machines, and other machines.
5. **Compose (declarative bindings):** index, first workflow, conditions and mappings, approval workflows, admission rules, typed views, how cascades run, Java integration, operations and upgrades, Studio.
6. **Extend (plugins):** extension ladder, scaffold and sign, SPI and manifest, how plugins load, make your machine bindable, consensus rules, testing.
7. **Operate:** deployment, configure, add a chain, operators, production, connectors, profile governance, troubleshooting.
8. **Products:** unchanged, with maturity badges.
9. **Reference:** CLI, REST, configuration, capabilities, modules, bindings references, bindings DSL, glossary, shelf.
10. **AI agents, Contributing.** Contributing gains the illustration authoring rules.

The tutorial order follows the tutorial review:

- Today's tutorial 3 cookbook moves into State machines.
- Tutorial 3 becomes a runnable approvals and effects lesson.
- Anchors move earlier.
- The Docker evidence capstone comes after the launcher-only lessons.
- Route changes get redirects. AGENTS.md permits removing prerelease compatibility, but inbound links to yano-x.io are worth keeping.

## 7a. Explainer catalog

Each entry gives the pattern, the content in brief, the "Try to break it" scenarios, the source anchors, and the effort (S/M/L). Full step text is written when an explainer is built, and is checked against the cited lines then.

### Learn

- **`message-lifecycle`** — step-through, L. Lanes: Client · Ingress member · Pool · Leader · Followers · State.
  - Steps:
    1. POST `{topic, body}`; the member signs the envelope; `~` topics are refused.
    2. Admission (`validateForBlock`) returns 202, 400 or 429.
    3. Gossip and deduplication.
    4. The leader ticks (no messages, no block).
    5. Selection drops duplicates, stale sequence numbers and messages rejected at the real height.
    6. The round.
    7. Apply: a business rejection is a finalized no-op.
    8. Indexed, provable, later anchored.
  - Toggle: Client's view.
  - Break it: fill the pool; a rule fails after 202; resubmit the same id; restart the ingress node before gossip (the message is lost).
  - Sources: Yano `submission.md:3-66`; consensus guide `:61-90, 142`.
- **`consensus-round`** — step-through, L. Lanes M0–M3, t = 3.
  - Steps:
    1. PROPOSE with lock and PREPARE.
    2. 13 checks ending in re-execution.
    3. PREPARE votes.
    4. PreparedQC.
    5. COMMIT.
    6. FinalityCert; APP_FINAL.
  - Toggle: fixed / rotating leader formula.
  - Branch: timeout → NewViewCertificate carrying the highest PreparedQC.
  - Break it: corrupt one message (no vote); leader offline; wrong root.
  - Sources: consensus guide `:117-203`; `AppChainEngine.java:1702-1843`.
- **`quorum-calculator`** — simulator, S.
  - Inputs: n (1–32), t, f.
  - Checks: `1 ≤ t ≤ n`, `2t − n > f`, `t ≤ n − f`; readout of tolerated offline members.
  - Presets: 1-of-3 and 2-of-4 are rejected.
  - Sources: `ConsensusQuorum.java:16-23`; `AppChainConfig.java:147-148`; `cluster.sh:2450`.
- **`block-anatomy`** — explorer, M.
  - Click a v3 header field to see what it binds and which check uses it.
  - Tamper buttons fail checks 4, 5, 6 and 13. One bad certificate signature voids the certificate.
  - Sources: Yano `AppBlock.java:12-27`; `AppChainEngine.java:1942-1947`.
- **`proof-path`** — explorer, M.
  - A simplified 8-key trie labelled "simplified MPF": inclusion path and siblings; an absent key gives exclusion ("absent from this dataset ≠ never happened").
  - Root-source selector maps to the trust level.
  - Break it: a forged value; a pruned height (unavailable, not absent).
  - Sources: `ProofLabVocabulary.java:22-28`; `ProofVerifier.java`.
- **`trust-ladder`** — simulator, S/M. Shared by state and proofs, the ladder page, tutorial 7 (`verify-ladder`) and five product pages.
  - Rungs: internal consistency → trusted root → certified finality → anchor binding (script address, thread token, every datum field) → claim.
  - Tamper controls show which rung fails.
  - Sources: `COMPOSABLE_STATE_AND_PROOFS.md:75-163`; tutorial 7 `:84-93`.
- **`chain-identity`** — explorer, M.
  - Genesis triple → application id → height-1 markers → context digest → capability manifest → anchor datum.
  - Break it: a different genesis id on node 3 (2-of-3 continues, 3-of-3 halts).
  - Sources: `StateCommitmentIdentity.java:25-144`; `AppChainEngine.java:1147-1151`.
- **`where-data-lives`** — explorer, S. Node directory tree.
  - A stop / clean toggle shows what survives.
  - Break it: restore `appchain-indexers` as authoritative (refused); delete it (rebuilds).
  - Sources: `cluster.sh:994-995, 1690-1694`; user guide `:880-904`.
- **`recovery`** — step-through, M.
  - Steps: catch-up batch → hash chain and `messagesRoot` → L1 reference → certificate → re-execution → restart invariants.
  - Break it: a forged root from a peer; a snapshot without a tip certificate; an edited snapshot.
  - Sources: consensus guide `:294-345`.
- **`who-signs-what`** — matrix plus simulator, M.
  - Compromise one key to see what an attacker can and cannot do.
  - A sorter places claims under Proven / Trusted / Not provided.
- **`two-plane-sorter`** — simulator, S.
  - Sort operations (write state, emit effect, POST a webhook, `block.timestamp()`, `Instant.now()`) into the consensus or execution plane, with the rule for each.
- **`divergence-sim`** — simulator, M.
  - Pick a forbidden line and who runs it differently.
  - Outcomes: leader or quorum diverges → view change and stall; one follower diverges → only that member stalls. A fix button applies the "Instead" column.
- **`effect-lifecycle`** — step-through, L.
  - Steps:
    1. `emit` with `EffectId(chain, height, ordinal)`.
    2. The block finalizes.
    3. The gate.
    4. PENDING → SUBMITTED.
    5. POST with `Idempotency-Key`.
    6. `~fx/result`; first result wins.
    7. `onEffectResult`.
  - Toggles: result policy NONE / CHAIN; receiver 2xx / 4xx / 5xx.
  - Break it: crash after the POST (duplicate POST, receiver deduplicates); a late or non-designated result is a no-op.
  - Sources: `FxKernel.java:273-318`; `ApprovalsStateMachine.java:200-243`.
- **`observation-round`** — step-through, M.
  - Steps: payment fact → `watch` → adapters fetch the signed root and leaf → inclusion check → quorum → certificate → `~obs/result/v1` → `onObservationResult` → payment effect.
  - Outcome toggle: VALUE / other / EXPIRED.
  - Sources: `ShipmentWorkflowReferenceStateMachine.java:132-203`; Yano ADR-037.
- **`anchor-advance`** — step-through, M.
  - Steps: trigger → leader builds the thread-UTxO spend → members check and sign → submit → validator checks.
  - Toggle: metadata / script mode.
  - Break it: a fake root (fewer than t signatures); leader offline (finality continues, lag climbs).
  - Note on screen: the validator checks signatures, not root correctness.
  - Sources: Yano `AnchorValidator.java:27-143`; `ScriptAnchorService.java:750-864`.
- **`what-did-you-verify`** — explorer, S.
  - Cards: 202, finalized, proof, anchor; toggle Establishes / Does not establish.
- **`concept-map`** — explorer, M.
  - A graph from `concepts.json`; click a node to open its page, prerequisites highlighted.

### State machines

All scenarios and codes are generated from stdlib goldens.

- **`sm-chooser`** — chooser, M.
  - Questions: events or state? Who authorizes? Value, history, quantity or decision? Collections and revisions?
  - Output: machine, maturity, recipe, state key, proof subject, and what it proves and cannot.
- **`wire-builder`** — simulator, S/M.
  - Shows the command array, hex, state key and verdict.
  - Break it: amount 0; a DELETE carrying a value; a 300-byte key; non-canonical CBOR.
- **`kv-ownership`** — step-through plus simulator, M.
  - Steps: A puts → B puts (finalized `KV_NOT_OWNER`, nothing changes) → A updates → A deletes (exclusion proof) → B puts (B now owns it; this is not a transfer).
  - Toggle: Writer / Verifier view.
  - Tutorial 2 variant: the root moves every block while the entry does not.
- **`approvals-lifecycle`** — step-through, M.
  - Steps: propose (0/2; proposing is not approving) → approve → duplicate approve is a no-op → approve → APPROVED, effect PENDING → a late reject is a no-op → effect CONFIRMED and the item stays APPROVED.
  - A clock slider shows the deadline semantics.
- **`balances-ledger`** — simulator, S/M.
  - Break it: `BALANCE_NOT_MINTER`; `BALANCE_INSUFFICIENT`; spend from `alice` (receive-only); mint to an uppercase hex account (stranded); drain to zero (key deleted).
- **`doc-trail-chain`** — explorer, S/M.
  - Shows the head chain `Blake2b(head ‖ entryHash ‖ sender)`.
  - Tamper: change bytes, reorder, swap the author; editing the reference leaves the head unchanged.
- **`authmap-lab`** — simulator, L.
  - Break it: stale CAS (8); non-controller (3); PUT_IF_ABSENT on an active entry (4) or a tombstone (6); restore forbidden (9); TRANSFER in a member collection (3); a batch whose second item fails writes nothing.
- **`authmap-presence`** — explorer, S.
  - ABSENT / ACTIVE / REVOKED, the physical-key layout, exclusion vs tombstone inclusion.
- **`authmap-validation-pipeline`** — simulator, M.
  - Canonical CBOR → `product-v1` → `gs1-gtin-v1`, along a preflight → ingress → block → receipt timeline.
- **`role-approval-walkthrough`** — step-through plus simulator, L.
  - Views: Actor / Relay member / Verifier.
  - Policy: buyer proposes; 2 reviewers, distinct by organization.
  - Break it: `DISTINCTNESS_DUPLICATE`, `CONFLICT`, `UNAUTHORIZED_ACTOR`, `INVALID_SIGNATURE`, `WRONG_GENESIS`, `LIMIT_EXCEEDED`.
  - Variants: tutorial 5 (`role-policy-sim`) and Evidence Desk (`desk-approvals`).
- **`ordered-log-journey`** — step-through, S. Reuses `message-lifecycle`, ending at the `~yano/finalized-message/v1/` record.

### Compose and extend

Data comes from `dry-run --report` goldens.

- **`cascade-anatomy`** — step-through, M.
  - Steps: ingress checks in order → replay check → step 0 → event → condition true → mapping, `derivedId`, depth 1 → derived step → preflight and claim → commit both → ACCEPTED receipt.
  - Toggle: skip (condition false) vs reject (target fails; nothing written).
- **`receipt-anatomy`** — explorer, S. Click a receipt position to see its meaning.
- **`binding-playground`** — simulator, L.
  - Break it, each marked compile-time or runtime: `BINDING_TYPE_MISMATCH`, `UNKNOWN_EVENT_FIELD`, `EXPRESSION_DIVISION_BY_ZERO`, `FUNCTION_MISSING_FIELD`, `BINDING_EVIDENCE_UNSATISFIABLE`, CEL error masking.
- **`approval-across-blocks`** — step-through, M.
  - Steps: propose → vote → vote, approved, derived append → idle block.
  - Toggle: the append fails, so the vote rolls back.
- **`rule-slots-timeline`** — step-through, M.
  - A static rule gives HTTP 400 with `{rule, deny}`; a depth-1 refusal rolls back; crypto work is kept only for fact-slot refusals.
- **`rule-evaluator`** — simulator, L.
  - Break it: `RULE_EVIDENCE_READ`, `RULE_SCOPE_INVALID`, `RULE_FACT_UNKNOWN`, `RULE_PARAMETER_MISSING`, `ADMISSION_RULE_ERROR`; adding `context.derived` loses static status.
- **`typed-view-explorer`** — explorer, L.
  - Click a YAML line to see its part of the flow; reads run in name order.
  - Break it: drop the `present` guard (`ERROR`); a fifth read.
- **`quantifier-stepper`** — step-through, M.
  - `writes.all` stops at index 1, so `writeIndex = 1`; coverage applies only in the fact slot.
- **`where-did-my-command-stop`** — explorer, M.
  - Code tree: ingress → 202 → replay → step codes → post-queue codes. Each shows its meaning, the receipt field that records it, and whether a retry can help.
- **`upgrade-preflight`** — explorer, M.
  - Diff mode shows which identity changes and the `profile-check` verdict.
  - Chips: not replay, not migration, not semantic equivalence.
- **`extension-ladder`** — chooser, S.
  - Rungs: config / recipe → declarative bindings → Java composite → state-machine plugin → executor / sink / observer.
  - Each shows what joins chain identity and how it changes.
  - Merged with the recipe chooser.
- **`plugin-activation`** — step-through, M.
  - Steps: scan → API range → isolated validation → policy → shared loader → ServiceLoader correlation → dependency order → fingerprint → registry → lifecycle.
  - Break it: an embedded host class; `minLevel` 13; a different JAR on one member (only `doctor` / `drift` catches it).

### Tutorials and operations

- **`first-chain-finality`** — synced to the terminal, L.
  - Commands map to lanes: start (threshold `⌊n/2⌋+1`) → submit → propose → certificate → AGREED.
  - Break it: node 2 offline (still final); node 0 offline (stall); threshold 3 with one node down.
- **`verify-ladder`** — explorer, L. `trust-ladder` plus the endpoints used at each rung and an anchor timeline (first anchor, then every N blocks).
- **`evidence-pipeline`** — step-through plus simulator, L.
  - Pipeline: publish → approve → release → `object.put` + `ipfs.pin` → results → `kafka.publish` → verify → anchor.
  - The status simulator derives PARTIAL / STORAGE_FAILED / EXPIRED / READY.
  - Shared with the Evidence product.
- **`pilot-secrets-and-render`** — simulator, M.
  - Secret sorter, plus the init → render → validate → doctor rail with its status codes.
- **`config-layers`** — explorer, M.
  - `appchain.yaml` → shared consensus / node / secrets / lock.
  - Click a value to see its owner layer and consensus impact; a manual edit is refused.
- **`chain-add-lifecycle`** — step-through, M.
  - Steps: plan → stop → apply (lock written last) → start → verify.
  - Break it: changing a consensus value (BLOCKED); a digest mismatch; an interrupted apply.
- **`member-onboarding`** — step-through, M.
  - Steps: join → approvals → epoch from height H → catch-up → peer refresh.
  - Break it: a second add while an epoch is pending; a static chain.
- **`topology-ports`** — explorer, S. Nodes × chains, with HTTP and N2N port rules for the launcher vs generated projects.
- **`showcase-quickstart`** — synced to the terminal, M. doctor → quickstart → submit → verify → stop, across 3 nodes × 13 chains.

### Products and reference

- **`attest-lab`** — simulator, M.
  - Checks: Digest / Binding / Inclusion / Finality / Anchor / TrailHead → trust level and exit code.
- **`status-registry`** — explorer, M. A bit-status timeline queried at any height.
- **`history-field-proof`** — explorer, S/M. Shows route → state key → leaf → proof.
- **`explorer-ingest`** — step-through, M. The index adds convenience, not trust.
- **`passport-explorer`** — explorer, M. Shows REWRITTEN / DANGLING / FOREIGN_WRITER.
- **`feed-aggregation-sim`** — simulator, M.
  - Lower median, tolerance, outliers, quorum before and after outliers.
  - A pure function, so it is the template for golden-backed simulators.
- **`eutxo-tx-lab`** — simulator, L. Labelled with a "test keys only" banner.
- **`product-chooser`** — chooser, S.
- **`rest-explorer`**, **`config-explorer`**, **`capability-graph`**, **`cli-explorer`** — reference explorers, M/L. Each is generated from repository data.

## 8. Decisions

Taken on 2026-10-02:

1. **Terminology.** Prose on the site and in the imported `docs/` pages says
   *app ledger*. Identifiers keep their names: the `appchain` CLI,
   `yano.app-chain.*` configuration, `/api/v1/app-chain/` routes, Java types,
   file names, and App-Chain Studio. "What is an app chain?" becomes
   "What is an app ledger?" with a redirect from the old route.
2. **Source problems.** Docs only for now. Pages describe current behaviour,
   and the code issues in section 3.3 are filed as GitHub issues.
3. **First page.** "What is an app ledger?" ships first, with the illustration
   engine it needs.

Still open, with the recommendation in brackets:

- Remove each Mermaid fence when its block diagram lands (yes).
- Rename Concepts to Learn, reorder the sidebar as in section 7, and add
  redirects for moved routes (yes, in a later wave).
- One PR per wave after the first page (yes).
