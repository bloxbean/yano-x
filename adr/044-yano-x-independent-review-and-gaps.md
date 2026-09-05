# ADR-044 (Report): Yano X and Yano app-chain support — independent review, gaps, and enhancement ideas

## Status

Informational report. It records findings, a closure audit of the previous external review, and
proposals. It does not itself make an architectural decision. The companion release-readiness
report is [ADR-045](045-yano-x-release-readiness.md).

## Date

2026-09-05

## Author

Claude (Fable 5.1), acting as an independent reviewer with no prior involvement in the code under
review.

## Reviewed tree

| Item | Value |
|---|---|
| Yano X branch | `issue-85-observer-consensus-identity`, HEAD `6f665631` |
| Ahead of `main` (`d0c8929e`) | `d4fd0fc2` deployment automation + EUTxO UI (PR #3, draft), `d2eabb07` observer consensus identity + `6f665631` docs (PR #4, draft, stacked on #3) |
| Uncommitted | 21 modified files (stdlib epoch observers, deployment renderer/schema/README, showcase settlement config and scripts, settlement vault validator blob, tests) plus untracked `adr/043-…` and `EpochProtocolParamsFixtures.java` |
| Pinned host | `yanoVersion=0.1.0-pre13` (Yano release `v0.1.0-pre13`, 2026-08-18) |
| Host refs read | Yano `v0.1.0-pre13` (what Yano X consumes) and Yano `main` at `c990af7a` (unreleased; contains the ADR-036 certified view-change implementation). The local Yano checkout on `fix/l1-epoch-observer-shelley-boundary` (`ba9ac629`) was used only for its locally published `0.1.0-pre14-ba9ac62-SNAPSHOT` artifacts and JVM ZIP |
| Prior reviews | [ADR-014](app-layer/014-appchain-adr013-external-review-readiness-and-feasibility-fable.md) (2026-07-17), [ADR-024](app-layer/024-appchain-full-support-review-readiness-and-roadmap.md) (2026-08-01) |

## Size of the reviewed surface

| Measure | Yano X | Yano host app-chain surface |
|---|---:|---:|
| Inventoried artifacts | 54 (20 libraries, 18 runtime plugins, 4 tools, 4 on-chain artifacts, 3 test libraries, 3 applications, 1 web application, 1 fixture) | — |
| Main Java files / lines | 653 / ~121,900 | 184 `core-api` + 135 `runtime` files |
| Unit test classes / lines | 293 / ~59,700 | 65 app-chain test classes in `runtime` |
| Other test source sets | 7 integration, 9 crypto, 3 distribution-test files | — |
| Largest host file | — | `AppChainSubsystem.java` 6,615 lines on `main` (6,352 on pre13) |
| Documentation | 37 `docs/` pages, 75 ADR files, Astro site under `www/` | — |

## Method and confidence labels

Single reviewer, direct reading, no sub-agents. Every verdict in this report carries one of three
labels so the reader can weigh it:

- **READ** — the execution path was read end to end.
- **SCAN** — grep-level evidence only (symbol present or absent, a line matched).
- **NOT CHECKED** — stated explicitly wherever a prior claim was not re-verified.

Executed during the review:

- Unit test suite against the released pre13: compile failure in three modules (finding R-01).
- Unit test suite, forced re-execution (`--rerun-tasks`), against `0.1.0-pre14-ba9ac62-SNAPSHOT`
  with Maven Local: 1,228 tests, 0 failures, 2 skipped, 47 modules, 2 min 25 s.
- Same suite with the compiler resolved from Maven Central instead of Maven Local: one failure,
  the settlement vault artifact drift pin (finding R-02).
- `verifyArtifactInventory`, `verifyJvmOnlyBuild`: pass. `distributionCheck` forced against the
  matching locally built pre14 ZIP: pass (194 tasks executed; Maven Local inputs, see R-02).
- GitHub Actions history and job-level results for the last twelve runs.

Not executed: integration and crypto source sets, showcase script and distribution contracts, any
live cluster, power-loss or crash tests, an adversarial harness. The retained `devnet-x` deployment
was not touched.

This review deliberately does not re-derive ADR-024's 65 findings. Its spine is the three things
ADR-024 could not do: audit ADR-024's closure five weeks later, review the surface that landed
after 2026-08-01, and separate what Yano X actually runs (pre13) from what is fixed only in
unreleased Yano.

---

## 1. Executive summary

**Verdict: still a strong, unusually disciplined pre-release, whose readiness has not advanced
since ADR-024 and whose value bridge now carries two new high-severity findings.**

What held up under a second independent read:

- The canonical-encoding, fail-closed, and determinism disciplines that ADR-014 and ADR-024
  praised are intact in the new code. The batch withdrawal observer, the settlement co-signer
  ("never sign what we cannot positively verify against our own committed view"), the reserve and
  custody accounting, and the deployment tool's confirmation and destruction gates are all written
  by people who expect adversaries.
- The ADR record is honest. ADR-042 labels every unfinished behaviour a "known gap", ADR-043 is
  design-only and says so, ADR-039 says live qualification is pending, and Yano's ADR-036 says
  graduation still depends on Phase 5.
- The unit suite is real and green: 1,228 executed tests across 47 modules with zero failures.

What did not hold up:

1. **The previous review's Wave 0 and Wave 1 were not executed.** Of ADR-024's ten adversarially
   verified findings (AC-01 to AC-10), eight are still open in the tree Yano X consumes (AC-01,
   AC-03, AC-04, AC-05, AC-06, AC-07, AC-08, AC-09), one is fixed only in unreleased Yano `main`
   (AC-02), and one is fixed (AC-10). Of the four further high findings, AC-11 is fixed only on
   Yano `main`, AC-12 is open but downgraded, AC-13 is fixed, and AC-14 is closed by prerelease
   policy. The renewable proposer stall (theme T1) is reachable on pre13 and on Yano `main` alike
   (§2, R-04). Five weeks of effort went to new capability (authenticated map, Cardano History,
   deployment automation, product UI, observation reliability) and to the Preprod incident, not to
   the pilot exit criteria.
2. **The value bridge has two new high findings.** The deposit observer lets any Cardano user drop
   or wedge a block's deposit observations for the price of one dust output (R-03), and the vault
   validator bytes in the working tree do not reproduce from the pinned compiler (R-02).
3. **Nothing on this branch can ship against a published Yano.** The branch requires symbols that
   exist only after Yano's ADR-036 work; Yano pre14 is untagged; and ADR-036 is a fresh-chain
   cutover that invalidates every existing chain (R-01, R-05).
4. **The release gate is red on `main`.** The scheduled release-acceptance workflow has failed two
   weeks in a row in the deployment-parity E2E while the same jobs pass on pull requests (R-06).

Finding volume in this review: **14 new findings — 5 high, 5 medium, 4 low** — plus a closure audit
of 14 verified and 12 selected medium ADR-024 findings.

---

## 2. New findings

Identifiers are `R-nn`. Severity follows ADR-024's scale. Each finding states its evidence label.

### R-01 — [HIGH] The branch does not build against the pinned, released Yano

**READ.** `gradle.properties` pins `yanoVersion=0.1.0-pre13`. Compiling the working tree against
that release fails in `:state-machines:stdlib:compileJava`, `:ledgers:eutxo:bridge-cardano:compileJava`,
and `:composition:client:compileTestJava` on `L1ObserverConsensusIdentity`, a `core-api` type
introduced by Yano commits `3ec2621c`/`c7f4d1fb` (ADR-036 work, after the pre13 tag). The branch
compiles only against a locally published `0.1.0-pre14-*-SNAPSHOT`. PR #4's body acknowledges the
dependency on "bloxbean/yano#112 and its next published Yano version".

**Why it matters.** AGENTS.md's central invariant is "consume an exact published Yano version". The
branch currently satisfies it with a snapshot that exists only on one developer machine. The
observer-identity work is also the prerequisite for the ADR-041 fix; until Yano publishes pre14 the
fix cannot be qualified or released from Yano X.

**Fix.** Keep the branch draft and stacked. Add the nightly compatibility lane described in E-1 so
the gap is visible in CI rather than discovered at merge time. Bump `yanoVersion` only in the same
change that consumes a tagged Yano release.

### R-02 — [HIGH] The uncommitted settlement vault validator is not reproducible from the pinned toolchain

**READ + executed.** The working tree changes `SettlementVaultValidator.plutus.json` (cborHex
`591a49…` → `591a93…`), the parameterised `settlement-vault.script` and `settlement-shard.script`,
the vault address and script hash in `application-appchain.yml` (three places), the distribution
contract grep, and the settlement chain `genesis-id`. The Java validator source has not changed since
2026-08-06. `SettlementArtifactBundleTest.bundledArtifactsMatchTheSourceCompile` **fails** when
`julc-compiler:0.1.0-pre16` resolves from Maven Central and **passes** when it resolves from
`~/.m2`. The two jars carry the same released version string and different bytes (sha256 `8782…`
locally, `04c9…` on Central). Because `-PuseMavenLocal=true` lists `mavenLocal()` before
`mavenCentral()`, a locally published jar with a release version silently shadows the release.

**Why it matters.** The vault validator defines the Preprod address that holds user ADA. Its bytes
were produced by a compiler build that no release identity names. A release build (Maven Local
disabled) fails the drift pin; anyone rebuilding from source gets a different script hash and
therefore a different vault address than the checked-in showcase configuration.

**Fix.** Regenerate the four artifacts with the published julc, or bump `julc` to the version that
actually produced the new bytes and record why the vault changed. Filter `mavenLocal()` so that only
`-SNAPSHOT` versions may resolve from it. Add the resolved compiler jar digest to
`verifyPublishedContracts` and to the `description` field of every `*.plutus.json`.

### R-03 — [HIGH] The deposit observer drops or wedges a block's deposit observations on any hostile vault output

**READ.** `AcceptedVaultDepositObserver.claim()` throws `IllegalArgumentException` when a vault
output has no inline datum, carries a datum that is neither a deposit nor a settlement datum, holds
any non-lovelace asset, is outside the lovelace bound, targets a different chain id, or is the
second vault output in one transaction. Outputs at a script address can be created by anyone.

Host consequence on the consumed release (`v0.1.0-pre13`, `L1ObservationService.onL1Block`,
READ): the exception is caught per observer and logged; **every observation that observer produced
for that L1 block is discarded**, and the block continues. A legitimate deposit accepted in the same
Cardano block is never credited: the ADA stays in the vault with no L2 mirror, which is the ADR-041
§2.4 failure, now triggerable at will for one dust output.

Host consequence on Yano `main` (READ): the same exception sets `healthy=false`, records
`callbackFailureSlot`, marks the journal, and rethrows `L1_OBSERVER_CALLBACK_FAILED`; every later
block throws `L1_OBSERVER_REPLAY_REQUIRED_AT_SLOT_n`. Delivery is fail-closed, so nothing is lost,
but observation delivery for that node halts until an operator replays.

The sibling `BatchWithdrawalConfirmationObserver` already documents and guards against exactly this
("anyone can pay the vault address with an arbitrary inline datum … SKIP it deterministically —
throwing would let one crafted output drop the whole block's observations"). The deposit observer's
tests pin only the two-vault-output throw; nothing covers the no-datum, foreign-datum, or
native-asset shapes.

**Fix.** Skip structurally invalid vault outputs deterministically, exactly as the batch observer
does; keep fail-closed only for an output that decodes as this chain's deposit datum and still
violates a bound, and prefer a typed rejected claim there too. Add the adversarial corpus to the
observer tests. Host ask: per-observer isolation that quarantines the failing observer's claims with
a typed status instead of dropping siblings (pre13) or halting delivery (main).

### R-04 — [HIGH] The renewable proposer stall (ADR-024 theme T1) is open in both the consumed release and Yano `main`

**READ.** Yano `doProposeTick` on pre13 wraps `applyBlock(candidate)` in `catch (Throwable)`, calls
`discardRoundAfterFailure`, logs, and returns. `selectMessages` removes only admission rejections
from the pool; `AppMsgPool.drainCandidates` is non-destructive. Yano `main` has the same structure
at the same catch site. Nothing removes a message whose `apply()` throws, so it is re-selected every
tick.

Yano X still supplies the throw paths ADR-024 verified: `KeyPaymentTransitionEngine` accepts
duplicate inputs and sums conservation over the multiset (AC-01, READ lines 109–140 and 595–612),
`EutxoStateMachine.validateForBlock` discards the committed state it is handed and `putRecord`
throws on the per-address cap (AC-08, READ), `ApprovalsStateMachine` emits uncounted with no
`tryEmit` in `core-api` (AC-05, SCAN), and `EutxoStateMachine.importDeposit` throws on conflicting
replay by design.

**Why it matters.** Any authenticated submitter can still stall an EUTxO chain renewably with one
zero-fee message, and the multiset conservation bug is still guarded only by that crash.

**Fix.** ADR-024 W1-1 unchanged: input distinctness and conservation over a set in `transition()`,
stateful `validateForBlock`, deterministic `ADDRESS_UTXO_BOUND` rejection, a framework backstop that
records a per-message failure and removes the poison message from the pool, and an adversarial
conformance suite in CI.

### R-05 — [HIGH] The consumed Yano release lacks fsynced vote locks and the certified recovery protocol; adopting them is a fresh-chain cutover

**READ.** On `v0.1.0-pre13`, `AppLedgerStore.putVoteLock` is an unsynced `db.put` (ADR-024 AC-02
open). Yano `main` writes a view-aware lock with `WriteOptions.setSync(true)` as part of ADR-036,
which is "Accepted — implemented as a fresh-chain preview cutover; graduation still depends on the
Phase 5 multi-node qualification gates". ADR-036 §7 states that existing preview chainstate is
incompatible and must be archived or discarded.

**Why it matters.** Every current Yano X deployment runs the engine that stalled on Preprod
(ADR-041 §2) and that can double-vote after power loss. Upgrading means a new genesis for all
thirteen showcase chains, new script-anchor identities, and re-bootstrapped settlement. This is the
largest single dependency on the release path and it is outside Yano X's control.

**Fix.** Track the Yano pre14 tag and ADR-036 Phase 5 as explicit Yano X release blockers
(ADR-045 B1). Plan and authorise the fresh-chain migration of the retained deployments as a
separate, reviewed operation.

### R-06 — [MEDIUM] The scheduled release-acceptance workflow is red on `main`

**Observed.** Runs `32688936706` (2026-08-24) and `33379775884` (2026-08-31), both `schedule`
triggers on `main`, fail in job `effect-failover-e2e` at step "Run mandatory composite deployment
parity E2E" with `FAIL: compose-first scenario command failed`. `connector-fault-matrix` passes in
both. The same jobs passed on the `main` push of 2026-08-23 and on PR #3 on 2026-08-30. The failure
message is a generic wrapper and the log is dominated by node INFO lines; the root cause was not
diagnosed in this review.

**Why it matters.** `release-acceptance` requires this job. A gate that fails only on schedule is a
non-deterministic gate, which is exactly the condition APP-009 has been asking the program to close
since ADR-014.

**Fix.** Diagnose or quarantine with a reviewed exception; retain artefacts from the scenario
harness; add the parity scenario's own diagnostic output to the failure line.

### R-07 — [MEDIUM] The tracker no longer indexes the review record or the newest decisions

**READ.** `adr/app-layer/open_item.md` ("Last updated 2026-08-23") indexes no ADR-024 finding or
wave, and none of ADR-025.x, 028, 035–038, 040, 041, 042, or 043; only ADR-039 rows were added.
ADR-024's Wave 0/1, the stated exit criteria for a consortium pilot, have no owner, state, or
revival trigger anywhere.

**Fix.** Apply the ADR-017 closure pattern to ADR-024 and to this report: every accepted finding
becomes a tracker row in the same pull request. E-12 proposes a test that enforces it.

### R-08 — [MEDIUM] Observer identity v2 and the in-flight epoch-observer change

**READ.** The stake and governance observer identities move from `…-observation-v1` to `-v2`, the
observers now emit a canonical empty dataset for pre-Shelley and pre-Conway epochs (era detected by
the presence of `key-deposit` / `drep-deposit` in the canonical parameter encoding), and the showcase
`genesis-id` and vault addresses change. That is correct per ADR-043 §5 and PR #4 says so. Three
review comments:

- The change means every retained Cardano History and settlement chain must be reset; there is no
  migration note for the retained `devnet-x` deployment or the Preprod cluster, and ADR-043's
  additive-generation path is design-only, so reset is the only path.
- `EpochStakeObserver.stakeEra` now throws `IllegalStateException` when
  `state.previousEpoch() != epoch`; previously that case raised the `NoSuchElementException`
  prefixed `L1_EPOCH_DATASET_UNAVAILABLE:` that the host classifies as retryable
  (`L1EpochObservationCoordinator.causedByDatasetUnavailable`, READ). The host validates the
  precondition before calling, so the path should be unreachable, but the class change is a
  behavioural change and deserves a comment and a test.
- `ObserverConsensusIdentity` now exists twice (stdlib and bridge-cardano). Extract it to a shared
  contracts module before a third copy appears.

The era-detection tests (Byron, pre-Conway, Conway fixtures) are good and the "does not read the
stake snapshot outside the stake era" assertion is exactly the right pin.

### R-09 — [MEDIUM] Deep-rollback halt sequencing is advisory

**READ (callers).** `BridgeRollbackGuard.assess` returns a decision and documents that "the caller
must persist/sequence the returned halt; this class does not mutate app-chain state from a
node-local callback". A search of every Yano X main source set finds **no production caller**;
the only references are two assertions in `AcceptedVaultDepositObserverTest`. ADR-042 §15.1.9
requires that a rollback behind finalized value state halts rather than rewrites; in Yano X that
invariant is documented and unit-tested as a pure function, but nothing sequences the halt. Whether
the host's generic L1 rollback handling halts the chain independently was NOT CHECKED.

**Fix.** Wire the decision into the reconciliation path that can sequence a `bridgeHalt` through
consensus (or document the host mechanism that makes it unnecessary), and cover it with a test.

### R-14 — [MEDIUM] ZK verification backends are discovered from the classpath on a consensus path

**READ.** `ZkVerificationService` builds its orchestrator with `VerifierRegistry.withServiceLoader()`,
so the Groth16 and PlonK backends that decide whether a proof verifies are whatever the member's
classpath happens to provide. Two members with different bundle closures can reach different
verdicts for the same finalized bytes, which is the same class of nondeterminism ADR-024 flagged
for `findAndRegisterModules`. Scope: the three ZK machines are `EXPERIMENTAL` and opt-in, so no
default deployment is affected; the dependency-complete bundle also makes the closure identical in
practice. It is still a consensus-path dependency on discovery rather than on declaration.

**Fix.** Pin the backend set explicitly (a `VerifierRegistry.of(...)` built from the manifest or
chain settings) and commit the backend identities into the machine's consensus settings digest.

### R-10 — [LOW] Documentation site domain drift

**READ.** `.github/workflows/docs-deploy.yml` deploys with `cname: yanox.dev`, while the repository
`www/public/CNAME`, the `gh-pages` branch, and commit `d0c8929e` moved the site to `yano-x.io`.
`README.md` and `www/astro.config.mjs` still say `yanox.dev`. The next `dv*` tag deploy reverts the
custom domain.

### R-11 — [LOW] Two ADR-024 medium findings unchanged

**READ.** `KafkaStreamSink.deliver` still returns `false` on every exception without logging, so a
wrong topic or rotated credential still head-of-line-blocks the finalized stream silently.
`EutxoIndexCoordinator.schedule` still reschedules immediately on a persistent failure with no
backoff.

### R-12 — [LOW] Dependency hygiene

**SCAN.** No Dependabot or Renovate configuration; the CycloneDX SBOM is produced but nothing in CI
consumes it; test frameworks are pinned to 2021 releases (JUnit 5.8.1, Mockito 3.7.7, AssertJ
3.21.0). Not a runtime risk; a maintenance signal.

### R-13 — [LOW] Launcher anchor-seed guard still applies only on `start`

**SCAN.** `scripts/appchain-cluster/cluster.sh` requires an operator anchor key off devnet only in
`cmd_start`; `anchor_signing_seed` falls back to the repository-public demo seed on other paths
(ADR-024 AC-12). Downgraded from high because ADR-039's `yano-x-deploy` is now the public-network
path and the launcher is positioned as a devnet tool; either apply the guard in `launch_node` or say
"devnet only" in the launcher's usage text.

---

## 3. Closure audit of ADR-024

Status legend: **open**, **fixed**, **fixed on Yano main only** (not in pre13), **closed by
policy**, **not checked**.

### 3.1 Verified findings

| ID | ADR-024 severity | Status | Evidence | Note |
|---|---|---|---|---|
| AC-01 duplicate EUTxO inputs | Critical | open | READ | No distinctness check in `validateShape` or `transition`; conservation over the multiset |
| AC-02 vote locks not fsynced | High | fixed on Yano main only | READ | pre13 `db.put`; main `setSync(true)` with view-aware key (ADR-036) |
| AC-03 same-block governance activations | High | open | READ | `activate` still reads `group.membersAt(height)`; identical on both refs |
| AC-04 approval window outside consensus profile | High | open | SCAN | `AppChainConsensusProfile` schema 2 has no membership fields on either ref |
| AC-05 approvals emit past effect cap | High | open | SCAN | `effects.emit` uncounted; no `tryEmit` in `core-api` |
| AC-06 release not bound to executor | High | open | READ | `EvidenceRegistryStateMachine:255` still sets the head owner from `message.getSender()`; `isIssuer(sender)` at `:74`; no executor field in the release command |
| AC-07 stock release approval covers nested hash only | High | open | READ | `EvidenceReleaseWorkflow:117` still compares `evidenceCommandHash()` |
| AC-08 per-address cap enforced by throwing | High | open | READ | `putRecord:1575` throws; `validateForBlock` ignores committed state |
| AC-09 ZK nullifier non-canonical bytes | High | open | READ | `MembershipProofBody.decode` still rejects only an empty nullifier |
| AC-10 typed reads throw on absent keys | High | fixed | READ | `ProofVerifier.verifyAgainstRoot` dispatches on `presence`; exclusion path reachable |
| AC-11 console `?api=` key exfiltration | High | fixed on Yano main only | READ | Credentials bound to the `apiBase` they were saved with; `normalizeApiBase` still accepts any origin, but no key follows it. pre13 not checked |
| AC-12 join path uses public demo seed | High | open (low) | SCAN | See R-13 |
| AC-13 ADR-023 and showcase untracked | High | fixed | READ | Both tracked; showcase in `settings.gradle` |
| AC-14 storage-root relocation without guard | High | closed by policy | READ | Yano `e7d7493c`; prerelease, no retained-state promise; deployment layout `/var/lib/yano/appchain-chainstate` |

### 3.2 Selected medium findings

| Finding | Status | Evidence |
|---|---|---|
| `JacksonCborCodec.findAndRegisterModules` (host) and client `CborCodec` | open on both refs | SCAN |
| `AppChainConfig` record prints the signing seed | open | SCAN (no custom `toString`) |
| Governed composite epoch-bound throw at activation | open | SCAN `CompositeProfileGovernanceRuntime:188` |
| Role-workflow administrators pinned to genesis membership | open | SCAN |
| Kafka sink silent head-of-line block | open | READ (R-11) |
| EUTxO indexer drain without backoff | open | READ (R-11) |
| SSE subscribe hides failures | partially checked | non-200 still sleeps 2 s and retries forever; cursor ordering not re-read |
| Snapshot manifest races commits | not checked | — |
| Stdlib settings not committed on chain | not checked | — |
| Catalog provenance by self-asserted bundle id | improved per docs | `RELEASE_ACCEPTANCE.md` and the plugin tutorial describe signed catalogs and digest pinning; code not re-verified |
| Generated compose project for `settlement:zeroj-validity` unstartable | not checked | — |
| Testkit temp-ledger and port hygiene | not checked | — |

---

## 4. Review of the surface added since ADR-024

### 4.1 Observation reliability — ADR-041, ADR-042, ADR-043 and Yano ADR-036

Design quality is high and unusually candid. ADR-041 §6 enumerates nine infrastructure gaps and
selects the journal-plus-certified-view design with an explicit trust rule; ADR-042 labels every
unfinished behaviour a known gap and gives operators a "forbidden recovery shortcuts" section;
ADR-043 refuses in-place manifest mutation and makes additive chain generations the only no-reset
path. Yano ADR-036 implements phases 0–4 on `main` (32 files, +3,349/−437 lines in the app-chain
packages since pre13: a 631-line `L1ObservationJournal`, `HistoricalObservationReconciler`,
certified consensus codec, mandatory observation prefix in `selectMessages`, SCAN).

Gaps: R-01, R-05, R-08; ADR-036 Phase 5 (five-node Preprod qualification with skew, partition,
restart, rollback, and pool pressure) has not run; the Yano X observers have never executed against
the journal; the plugin API level implications of the new observer SPI were not checked (all 18
manifests pin `yanoApi {min:3, max:3, minLevel:4}` and I could not locate the host-side level
constants by grep — verify before pre14).

### 4.2 EUTxO settlement bridge — ADR-UTXO-009, ADR-042 §8–11

Strengths (READ): exact-once deposit keyed by the accepted L1 outpoint; reserve credit and mirror
creation in one write set; tracked vault custody that a batch confirmation must consume;
seven distinct halt reasons; co-signer verification against the member's own committed view;
HTTPS-only external signer with a bounded timeout; a domain API that only builds unsigned
transactions; on-chain validators written in Java and compiled with julc, with source-compile drift
pins and conformance tests for every artifact.

Weaknesses: R-02, R-03, R-04 (AC-01 and AC-08 still open), R-09; `validateForBlock` is stateless;
the deposit observer accepts any well-formed deposit datum regardless of staging provenance (by
design per ADR-042 §9.2, noted only).

### 4.3 Deployment automation — ADR-039

Strengths (READ): `apply` requires the immutable cluster id; saved plans containing delete or
replace actions are rejected; `bootstrap-anchors` requires the network to be restated; member and
anchor seeds must be owner-only (`0400`/`0600`) on the controller and checks run with `no_log`;
reset playbook requires cluster id and an explicit scope; provider versions pinned; OpenTofu state
remote; API exposure behind TLS, keys, allow-lists and rate limits.

Observations: member signing keys and API keys are templated in plaintext into
`/etc/yano/node.properties` (`0640`) on every host — acceptable for a pilot, and the reason DX-002
(KMS/HSM signer) matters; the manifest schema is being extended in place (working-tree
`cardanoHistory` block) without a schema version bump; "live-provider qualification pending" is
accurate, and the one live Preprod run on record (ADR-041 §2) is the one that stalled.

### 4.4 Product UI — ADR-040

SvelteKit static application; API key held in memory only, DEV-only environment default,
discovery from the capability manifest, four vitest files. No UI test job is visible in the CI
workflow (NOT CHECKED whether Gradle runs `npm test` for `:products:eutxo:ui`).

### 4.5 Cardano History, authenticated map, docs site

Cardano History (ADR-028, 035–037) and the authenticated-map programme (ADR-025.x) were exercised
only through the build and the unit suite; ADR-025.3 is an unaccepted placeholder. The docs site
(ADR-038) builds from `docs/`, pins pre13 in three places, and has the domain drift in R-10.

---

## 5. Host assessment — Yano app-chain support

| Aspect | Observation |
|---|---|
| Delta pre13 → `main` | ADR-036 phases 0–4; view-aware synced vote locks; durable journal; historical reconciler; mandatory observation prefix; `admin/unlock-stale-round` retained for v1 only |
| Activation model | Fresh chain only (ADR-036 §7); no retained-chain transition certificate |
| Structural debt | `AppChainSubsystem` grew to 6,615 lines; REV-010 split still deferred |
| Open host-side ADR-024 items | AC-03, AC-04, codec auto-discovery, config `toString`, propose-failure backstop (R-04); snapshot race not checked |
| Observer isolation | pre13 drops the failing observer's block; `main` fails closed and wedges (R-03) |
| Release cadence | pre13 tagged 2026-08-18; roughly 530 commits on `main` since 2026-08-01; no pre14 tag at review time |
| Test surface | 65 app-chain test classes in `runtime`; Phase 5 qualification outstanding |

Yano X's dependency direction, plugin-only activation, and JVM-only boundary are respected in the
reviewed tree (READ: of the fourteen main-source mentions of `ServiceLoader`, twelve are Javadoc on
catalog-registered provider classes; the two real loads are the demo scenario registry in
`ledgers/eutxo/demo` and the ZeroJ verifier backend discovery discussed in R-14; no
`yaci.plugins.directory`; no native tasks).

---

## 6. Current gaps, consolidated and prioritised

| # | Gap | Blocks | Source |
|---|---|---|---|
| G-1 | Release depends on an untagged Yano pre14; adopting it is a fresh-chain migration for every existing chain | any release | R-01, R-05 |
| G-2 | ADR-024 Wave 0/1 unexecuted: T1 poison path, EUTxO duplicate inputs and address cap, evidence owner binding and approval scope, ZK nullifier canonicalisation and backend pinning, secret hygiene | consortium pilot | §3, R-04, R-14 |
| G-3 | Deposit observer griefing and no adversarial observer corpus | any value on Preprod | R-03 |
| G-4 | On-chain artifact and toolchain provenance; Maven Local can shadow released versions | release | R-02 |
| G-5 | No adversarial conformance suite in CI (only `RoleWorkflowAdversarialContractTest` and on-chain conformance) | adversarial exposure | ADR-024 §6.3 |
| G-6 | CI: scheduled gate red; no tag-triggered publish; no dependency scanning; no compatibility lane against staged Yano; UI tests not visible | release | R-06, R-12 |
| G-7 | Tracker and closure discipline; APP-009 (CI certification of the record) still open since ADR-014 | pilot positioning | R-07 |
| G-8 | Operator security posture: plaintext keys on hosts (DX-002), no API-key scopes or audit log (DX-001), split-vote runbook only partly covered by `unlock-stale-round.yml` (CON-006) | pilot | §4.3 |
| G-9 | No retained-state upgrade path: every consensus-affecting change is a new chain; ADR-043 additive generations are design-only; DX-006 state-format marker unbuilt | long-lived deployments | R-08 |
| G-10 | No read-only observer node role (CON-011) — UI and external verifiers hit validators | operations | ADR-039 §10.3 |
| G-11 | Docs: domain drift; no Yano X ↔ Yano compatibility matrix | release | R-10 |

---

## 7. Enhancement ideas

ADR-024 §7 proposed six state machines (S1–S6), four sequencer profiles (C1–C4), four connectors
(P1–P4) and five platform asks; they remain valid and are not repeated. The items below are new and
follow from this review.

**E-1. Yano compatibility lane.** A nightly job that builds and tests Yano X against a staged Yano
snapshot repository (the workflow already accepts `YANO_MAVEN_REPOSITORY_URL` and
`YANO_JVM_DIST_URL`) and publishes `config/yano-compatibility.json` (Yano X version, Yano version,
plugin API level) that `verifyPublishedContracts` enforces. Closes G-1's visibility problem.

**E-2. Toolchain provenance gate.** Restrict `mavenLocal()` to `-SNAPSHOT` versions; pin and verify
the sha256 of the resolved julc, zeroj, and CCL jars; write compiler identity and source digest into
every `*.plutus.json` description; promote `SettlementArtifactBundleTest` into the release gate.

**E-3. Observer hardening kit.** A small `yano-x-observer-support` library holding the shared
`ObserverConsensusIdentity`, a structurally-invalid-output policy helper, and an adversarial
fixture corpus (no datum, foreign datum, native tokens, dust, duplicate outputs, chain-id mismatch)
that every observer test must pass. Host ask: per-observer isolation with a typed quarantine status.

**E-4. Adversarial conformance suite in CI.** ADR-024 W1-1 step 4, extended to observers and to the
settlement state machine (duplicate inputs, address-cap overflow, effect-cap flood, oversized
entries), asserting "rejected, never thrown".

**E-5. Additive chain generations in `yano-x-deploy`.** Implement ADR-043: `chain add-generation`,
per-chain reset with exact chain-id confirmation, and the committed coverage record surfaced by the
Cardano History client, CLI, and UI (`OUTSIDE_COVERAGE`).

**E-6. Bridge observability.** Expose ADR-042 §16 (oldest stable unfinalized observation, halt
reason, reserve versus tracked custody parity, deposit and withdrawal lifecycle counts) through
Prometheus and the EUTxO UI, and wire the existing `BridgeDoctor` into `yano-x-deploy status`.

**E-7. Independent deposit auditor.** An off-consensus, read-only process (APP-011 in spirit) that
re-runs the deposit observer against a second Cardano source and diffs against the L2 deposit index,
alerting on any accepted vault output without a credit. It would have caught both the ADR-041
incident and R-03.

**E-8. Read-only observer node type.** Add CON-011 as a node role in the ADR-039 manifest
(verify-and-serve, never vote) so UI and API traffic leaves the validators.

**E-9. Key custody.** Ship DX-002: one production-shaped `SignerProvider` (KMS/HSM), and a
deployment option that keeps member keys out of `node.properties` (systemd `LoadCredential=` or a
`0600` env file owned by the runtime user).

**E-10. Release engineering.** Tag-triggered publish workflow with SBOM attestation and plugin
bundle signing (PLG-001, reusing `appchain plugin sign`), a re-run of the ADR-030 Phase F rehearsal
from the tag, and release notes carrying the compatibility table.

**E-11. Dependency hygiene.** Dependabot or Renovate for Gradle and npm; refresh JUnit, Mockito, and
AssertJ; scan the produced SBOM in CI.

**E-12. Review-closure ritual.** Every informational review ADR gets a closure ADR (the ADR-017
pattern) and tracker rows in the same change; add a documentation test that fails when an accepted
ADR number is absent from `open_item.md` §10.

**E-13. In-place capability-manifest transition.** File ADR-043 §6 as a Yano host ask so a retained
chain can adopt a new observer under a threshold-certified manifest transition instead of a new
genesis.

**E-14. Stateful admission for EUTxO.** Make `validateForBlock` read committed state (input
existence and distinctness, address cap) so the S5 rate-limiter pattern from ADR-024 has a stdlib
precedent and the T1 class shrinks.

---

## 8. What this review did not verify

- Any live cluster behaviour: no node was started; the retained `devnet-x` deployment and the
  Preprod cluster were not touched.
- Integration, crypto, and distribution-test source sets; showcase script and distribution
  contracts; the effect-failover, deployment-parity, and role-workflow E2E harnesses (CI results
  were read, not re-run).
- Power-loss or abrupt-restart safety; there is still no recorded power-loss test (ADR-024 §6.3).
- The Cardano History, authenticated-map, ZK, Kafka, S3, and IPFS modules beyond build, unit tests,
  and the specific lines cited.
- Every row marked SCAN or NOT CHECKED above.
