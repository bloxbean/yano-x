# ADR-045 (Report): Yano X release readiness

## Status

Informational report. It records the readiness assessment, the gate evidence, and the blockers
found by [ADR-044](044-yano-x-independent-review-and-gaps.md) on 2026-09-05. It does not itself
make an architectural decision, and it does not change any positioning gate in
[`open_item.md` §12](app-layer/open_item.md).

## Date

2026-09-05

## Author

Claude (Fable 5.1), acting as an independent reviewer.

## Scope

Two questions:

1. Is Yano X ready for its **first public pre-release** — Maven Central artifacts, the plugin pack
   ZIP, and the combined JVM ZIP — from the reviewed tree or from `main`?
2. Which **deployment postures** the current code supports, using the four tiers ADR-024 §6.2
   introduced so the delta is legible: devnet and showcase; trusted consortium pilot with no real
   value; public or adversarial exposure; real funds.

Trees assessed: `main` at `d0c8929e` (what CI last certified) and the review branch
`issue-85-observer-consensus-identity` at `6f665631` with its uncommitted changes (what the
program is about to merge). Evidence labels (READ, SCAN, NOT CHECKED, executed) follow ADR-044.

---

## 1. Verdict

| Posture | `main` (`d0c8929e`, Yano pre13) | Review branch (needs Yano pre14) |
|---|---|---|
| Devnet, showcase, internal evaluation | **Ready.** Release-acceptance workflow green on the 2026-08-23 push; unit, distribution, connector, and E2E gates all passed (scheduled re-runs red since 2026-08-24, see ADR-044 R-06) | **Not ready** until Yano publishes pre14 (does not compile against pre13, ADR-044 R-01) |
| First public pre-release of the artifacts | **Not ready.** Six blockers (§3, B1–B6) | **Not ready.** Same, plus R-01 and R-02 |
| Trusted consortium pilot, no real value | **Not ready.** ADR-024 Wave 0/1 exit criteria unmet; ADR-024's own "ready after Wave 1" condition has not been reached | **Not ready.** Same |
| Public or adversarial exposure | **Not ready.** Renewable proposer stall open in both Yano refs (ADR-044 R-04) | **Not ready** |
| Real funds (mainnet settlement) | **Not ready.** FX-002, DX-001/002, CON-006, W2-4, ADR-036 Phase 5, R-02, R-03 all open | **Not ready** |

In one line: **the devnet and showcase posture is ready and well evidenced; nothing above it is,
and the distance has not shrunk since ADR-024 because effort went to new capability and to the
Preprod incident rather than to the pilot exit criteria.**

---

## 2. Gate evidence

Each row states what I actually saw. "CI" means a GitHub Actions result read via the API, not a
local re-run.

### 2.1 Build and unit gates

| Gate | Evidence | Status | Blocks |
|---|---|---|---|
| Source compiles against the pinned, released Yano (`0.1.0-pre13`) | Executed on the branch: 3 modules fail on `L1ObserverConsensusIdentity` (ADR-044 R-01). CI `commit-build` on `main` 2026-08-23: pass | **FAIL on branch / PASS on `main`** | any release from the branch |
| Unit tests | Executed with `--rerun-tasks` against `0.1.0-pre14-ba9ac62-SNAPSHOT` (Maven Local): **1,228 tests, 0 failures, 0 errors, 2 skipped, 47 modules, 2 min 25 s** | **PASS** | — |
| Unit tests with the compiler resolved from Maven Central | Executed: 1 failure, `SettlementArtifactBundleTest.bundledArtifactsMatchTheSourceCompile` (ADR-044 R-02) | **FAIL** | release build |
| `verifyArtifactInventory`, `verifyJvmOnlyBuild` | Executed in both runs | **PASS** | — |
| `distributionCheck` (plugin pack + combined JVM ZIP) | Executed with `--rerun-tasks` against the locally built `yano-0.1.0-pre14-ba9ac62.zip` and Maven Local: 194 tasks executed, build successful, 1 min 24 s. Caveat: Maven Local supplied the shadowed julc jar (ADR-044 R-02), so this is not a release-configuration run | **PASS (local inputs)** | release |
| `integrationTest`, `cryptoTest`, showcase script and distribution contracts | Not run in this review; PR #4 reports the showcase contracts were run locally | **NOT RUN** | pilot |
| Release-candidate static and packaged-JVM acceptance (`appChainReleaseCandidate*`) | Tasks exist in `tooling/devtools/build.gradle`; not run | **NOT RUN** | release |

### 2.2 Continuous integration

| Gate | Evidence | Status | Blocks |
|---|---|---|---|
| `commit-build`, `distribution-check` | CI pass on `main` push 2026-08-23 and on PR #3 2026-08-30 | **PASS** | — |
| `connector-fault-matrix` (restart/fault matrix, Kafka TLS and SASL) | CI pass on every run read, including the two failing scheduled runs | **PASS** | — |
| `effect-failover-e2e` (failover, deployment parity, role workflow, catch-up) | CI pass on push 2026-08-23 and PR 2026-08-30; **fail on schedule 2026-08-24 and 2026-08-31** at the deployment-parity scenario (ADR-044 R-06) | **FLAKY / RED ON SCHEDULE** | release |
| `release-acceptance` aggregate | Green on 2026-08-23 and 2026-08-30; skipped on scheduled runs because prerequisites failed | **RED ON SCHEDULE** | release |
| Publication staging into an isolated repository | Every CI run stages all publications first (pass) | **PASS** | — |
| Tag-triggered publish to Sonatype and GitHub release | No such workflow; publication is a manual invocation | **MISSING** | release |
| Dependency vulnerability scanning | None configured; SBOM produced but unconsumed | **MISSING** | pilot |
| APP-009 (retain CI certification evidence for the record) | Open since ADR-014; not closed by any run I could find | **OPEN** | pilot positioning |

### 2.3 Runtime and cluster evidence

| Gate | Evidence | Status | Blocks |
|---|---|---|---|
| Three-node devnet cluster: height, root, genesis, capability-manifest digest agreement, proofs, catch-up, restart | Retained `devnet-x` deployment exists and was not touched; skill `validate-yano-x-appchain` describes the procedure; no run recorded for this tree | **NOT RUN** | pilot |
| Five-node Preprod cluster (ADR-039 Phase 5 / ADR-036 Phase 5) | One live run on record (ADR-041 §2) with Yano pre13: two chains stalled at height 1 and a stable deposit expired before finality; ADR-039 status "live-provider qualification pending" | **FAIL** (the run is the incident) | pilot |
| Power-loss or abrupt-restart safety | Never run (ADR-024 §6.3); pre13 vote locks unsynced (ADR-044 R-05) | **NOT RUN** | pilot |
| Adversarial conformance harness (poison messages, hostile L1 outputs) | Does not exist (ADR-044 G-5) | **MISSING** | adversarial |
| Load and soak envelope (APP-010) | Not run for the packaged profiles | **NOT RUN** | pilot |

### 2.4 Host dependency and migration

| Gate | Evidence | Status | Blocks |
|---|---|---|---|
| Yano release matching the branch | pre13 tagged 2026-08-18; `L1ObserverConsensusIdentity` and ADR-036 only on `main`; no pre14 tag | **BLOCKED ON YANO** | any release from the branch |
| Plugin API level alignment | All 18 manifests pin `yanoApi {min:3, max:3, minLevel:4}`; host-side level constants not located (NOT CHECKED whether ADR-036 bumped the level) | **NOT CHECKED** | release |
| Retained-chain migration for ADR-036 | ADR-036 §7: fresh chain only; all 13 showcase chains, anchors, and settlement must be re-bootstrapped; no migration note in Yano X for `devnet-x` or Preprod | **UNPLANNED** | pilot |
| Observer identity v2 and showcase genesis rotation | In the working tree (ADR-044 R-08); requires the same reset | **UNPLANNED** | pilot |

### 2.5 Distribution check note

The forced `distributionCheck` against the matching pre14 ZIP passed with every task executed. It
proves the plugin pack, bundle isolation, license and SBOM checks, and the combined ZIP identity
contract for a **locally published** Yano snapshot and a **locally shadowed** compiler. It does not
prove the same for a released Yano and Maven Central inputs; that run is only possible after B1 and
B2 close.

---

## 3. Blockers for a first public pre-release, in order

| # | Blocker | Exit criterion | Owner |
|---|---|---|---|
| B1 | Yano pre14 is untagged; the branch needs it; adopting it is a fresh-chain cutover (ADR-044 R-01, R-05) | Yano tags pre14 with ADR-036 phases 0–4 and an explicit plugin API level; Yano X bumps `yanoVersion` in the same change that consumes it; retained deployments are migrated under explicit authorisation | Yano, then Yano X |
| B2 | Settlement vault validator bytes do not reproduce from the pinned julc; Maven Local shadows a released version (ADR-044 R-02) | Artifacts regenerated with the published compiler or julc bumped and the vault change explained; `mavenLocal()` restricted to snapshots; compiler digest verified in the release gate | Yano X |
| B3 | Deposit observer griefing on the value bridge (ADR-044 R-03) | Structurally invalid vault outputs skipped deterministically; adversarial observer tests; host isolation semantics agreed | Yano X (Yano for isolation) |
| B4 | Scheduled release-acceptance red for two weeks (ADR-044 R-06) | Root cause fixed or a reviewed quarantine recorded; two consecutive green scheduled runs | Yano X |
| B5 | No tag-triggered publish workflow; ADR-030 Phase F rehearsal not repeated from a tag; version still `0.1.0-SNAPSHOT` | Workflow that stages, verifies, signs, and publishes from a tag; rehearsal to an empty repository from that tag; release notes with the Yano compatibility table | Yano X |
| B6 | Documentation drift: `docs-deploy.yml` reverts the domain to `yanox.dev`; README and site config disagree with the CNAME (ADR-044 R-10) | One domain everywhere; site deployed once after the fix | Yano X |

Blockers for the **trusted consortium pilot** posture are B1–B4 plus ADR-024's Wave 0/1 exit
criteria (W1-1 EUTxO duplicate-input and address-cap fixes and the framework no-throw backstop,
W1-2 durability audit — now partly delivered by ADR-036 on Yano `main`, W1-3 evidence owner
binding, W1-5 deployment key hygiene, plus W1-4/W1-6 if the ZK or standalone-approvals profiles
are in scope), a three-node cluster validation from the released artifacts, and a closed APP-009.

Blockers for **real funds** additionally include FX-002 (Cardano transaction safety), DX-001
(API-key scopes and audit log), DX-002 (KMS/HSM signer), CON-006 (split-vote and break-glass
runbook), W2-4 (state-format versioning), ADR-036 Phase 5 qualification, and a recorded
power-loss test.

---

## 4. Recommended sequence

1. **Land PR #3 on `main` against pre13.** It passed the full release-acceptance workflow on
   2026-08-30 and does not need the new host symbols. Fix B6 in the same window.
2. **Fix B2 and B3 on the branch now**; both are Yano X-only and independent of the host release.
3. **Wait for Yano pre14**; bump `yanoVersion`, retarget and land PR #4, verify plugin API levels,
   re-run `distributionCheck` and the showcase contracts against the released ZIP.
4. **Execute ADR-024 Wave 0/1's Yano X half** (AC-01, AC-05, AC-06/07, AC-08, AC-09, W1-4) and
   file the host half (T1 backstop, AC-03, AC-04, codec pinning) as Yano issues with the
   ADR-044 references.
5. **Diagnose B4**, then require two green scheduled runs before tagging.
6. **Add the publish workflow (B5)**, tag `v0.1.0-pre1` of Yano X, rehearse to an empty repository
   from the tag, publish.
7. **Migrate the retained deployments** to fresh chains under explicit authorisation, then run the
   ADR-036 / ADR-039 Phase 5 five-node Preprod qualification from the released artifacts. Only
   after that should positioning move from "developer preview" to "permissioned pilot".

---

## 5. Not verified in this assessment

- No node was started and no cluster was touched; all runtime rows are from records or CI logs.
- Integration, crypto, and distribution-test source sets and the showcase contracts were not run.
- The two scheduled CI failures were not root-caused.
- The plugin API level constants on the host were not located; alignment is asserted from the
  manifests only.
- Every row in ADR-044 marked SCAN or NOT CHECKED.
