# ADR-029: Yano repository split — analysis and options

**Status:** Analysis / proposal — no decision taken, no code changed
**Date:** 2026-08-06
**Method:** four independent reviewers (architecture seam, build & release engineering,
devil's advocate / cost-of-split, product & naming) over the working tree at
`feat/app_layer_authenticated_map`, plus direct measurement.

---

## 0. Summary

The proposal was: keep the *core app-chain foundation* in `yano`, move everything else to a new
downstream project, because the build is slow and there are too many modules.

Measurement does not support the stated motivation, and the proposed seam is the weakest of the
available ones.

1. **"Too many modules" costs ~1 second.** Configuring all 66 Gradle projects and building the
   840-task `build` graph takes **0.99 s**. Module count is not a build-time problem.
2. **The build is slow for reasons a repo split does not fix.** `org.gradle.parallel` is not set,
   the configuration cache is off, `maxParallelForks` is at the default of 1, there is no remote
   build cache, a 270 MB uber-jar is produced on every `build`, ~394 MB of archives are written per
   build, two 189 MB Node toolchains are re-downloaded on every CI run, and a GraalVM native image
   plus a 240-minute E2E job run on every PR.
3. **The slow half is the app-chain half — and that is where 88% of commits land.** App-chain owns
   71.9% of the build task graph, 84% of `:runtime:test` wall time, and 2410 of 4700 file-touches
   in recent history. A split hands all the relief to the node repo, which received **1 commit** in
   the last month.
4. **The proposed seam has 33.7% mixed commits; the seam that actually separates the two products
   has 48.6%.** Both are far above the level at which a two-repo boundary is comfortable.
5. **The architectural seam is real and clean — but it is one layer deeper than proposed.** The
   app-chain engine in `runtime/appchain` has **zero** imports of node-domain code. It belongs
   downstream, not in yano.

**Recommendation:** fix the build first (days of work, most of the relief), then split at the
*leaf tier* (Option 4), architected toward the full platform split (Option 2) once the SPI settles.
Reject the seam as originally proposed. Name the downstream repo **`bloxbean/yano-appchain`**.

---

## 1. Corrections to the initial fact base

Two reviewers independently found that the first LOC pass was inflated ~1.5–1.9× because three
`.claude/worktrees/*` checkouts contain full duplicate source trees. Authoritative `git ls-files`
numbers:

| Area | First pass | Actual (git-tracked, main only) |
|---|---|---|
| runtime | 135,506 / 470 files | **73,933 / 273 files** |
| appchain/** | 153,631 / 818 files | **101,118 / 565 files** |
| ledger-state | 25,084 | **16,534** |
| app | 23,607 | **12,272 / 63 files** |
| core-api | 20,889 / 280 | **15,650 / 253 files** |
| **repo total** | — | **240,116 main / 148,010 test** |

Test-side numbers were correct. Other corrections:

- `settings.gradle` has **48** `appchain*` includes of 65 (not ~44); 66 Gradle projects with root.
- `core-api/api/appchain` = **6,737** LOC (43% of core-api). `runtime/appchain` = 21,630 main +
  18,304 test. `runtime/plugins` = 13,091. Together **47% of runtime's main code**.
- The runtime→appchain coupling count was 7; it is **9**. The two missed files are the important
  ones: `runtime/assembly/Yano.java:45` and `RuntimeYano.java:193` declare
  `default AppChainGateways appChains()` — the node's **public facade** returns app-chain SPI types.
  They were missed because they use fully-qualified names instead of imports.
- `console-ui` is **not** a node-side module. **81% of its route LOC is app-chain**
  (3,119 vs 734), 20 of 46 `lib/` files are app-chain, and it is a single npm package.
- The "298 MB console-ui / 189 MB studio" figures are downloaded Node toolchains, not source.
  Actual source: console-ui **516 KB**, appchain-studio **60 KB in 6 files**.
- `plugin-catalog` is not app-chain-free: **8 of its 11 contribution kinds are app-chain concepts**
  and `ContributionKind.java:3-9` imports seven `api.appchain` types.
- `nonLibraryModules = ['app', 'console-ui']` (CLAUDE.md documents a stale value). `:app` is
  therefore **not published to Maven at all** — only as a distribution zip.
- `version = 0.1.0-pre11` is **not** a SNAPSHOT, so `daily.yml`'s "nightly SNAPSHOT push" is
  actually publishing to the release staging URL, and the git-hash suffix logic never fires.
- **No `yano-*` artifact has ever been released to Maven Central.**

---

## 2. Where the build time actually goes

Measured from 823 JUnit result XMLs already on disk plus the `build`/`fullBuild` task graphs.

| Rank | Hotspot | Cost | App-chain share |
|---|---|---|---|
| 1 | `:runtime:test` — 293.4 s | ~60% of all test time | **247.1 s (84%) is `runtime.appchain.*`** (57 suites). Node tests: 46.3 s / 963 tests |
| 2 | Zero parallelism anywhere | 66 compile + 66 test tasks strictly serial on a 16-core box | self-inflicted |
| 3 | `:app:quarkusBuild` on default `build` → 270 MB uber-jar | est. 60–120 s + 270 MB write | partly |
| 4 | 19 `@QuarkusTest` classes in `:app:test` | unmeasured; 5–30 s per boot | 15 of 67 files |
| 5 | 13 `shadowJar` tasks, ~130 MB shaded | est. 60–120 s | **100%** |
| 6 | `distZip` **and** `distTar` on 5 modules | ~394 MB archives/build, ~180 MB of unused tars | mostly |
| 7 | Two Node 22.12.0 toolchains, uncached in CI | 378 MB download + `npm ci` every run | half |
| 8 | Heavy verification wired into `check` (bash suites, 8 shadowJars, 2 forked JVMs) | pulled into every dev build | ~90% |
| 9 | `verifyAdr013Jackson/Slf4jAlignment` resolving 8 projects' classpaths at execution time | seconds, but a config-cache blocker | 7 of 8 scopes |
| 10 | **Gradle configuration of 66 projects** | **0.99 s — not a hotspot** | — |
| 11 | GraalVM native image + `effect-failover-e2e` (240 min timeout) **on every PR** | dominates PR wall clock | app-chain-motivated |

Task-graph distribution of `build` (840 tasks): appchain 604 (71.9%), node foundation 174 (20.7%),
app 29, console-ui 20. `fullBuild` = 1,126 tasks.

Six of the seven CI jobs in `build.yml` are app-chain jobs, and none of them share a build cache.

---

## 3. Change velocity across the seam

The "last 500 commits" framing is invalid: the repo has only 412 non-merge commits, and **app-chain
did not exist here before 2026-07-08**. The valid window is 2026-07-08 → 2026-08-05: **267 commits,
208 touching code.**

| Seam | MIXED | node-only | app-only |
|---|---|---|---|
| Option 1 (only `appchain/` + `spring-starters/` move) | 70 = **33.7%** | 74 | 64 |
| Option 2 (SPI + engine + REST + console move too) | 101 = **48.6%** | **24** | 83 |
| Option 3 (Option 2 + `app` and `console-ui` move whole) | 61 = 29.3% | **12** | 135 |
| Option 4 (leaf tier only) | 56 = **26.9%** | 112 | 40 |

Only 14 of the 101 mixed commits (13.9%) are trivial build-file edits. Over the whole app-chain era
only **24 of 208 code commits (11.5%)** touch the node without touching app-chain, and in the last
month that fell to **1**.

The SPI is being *authored*, not maintained: all 87 files in `core-api/api/appchain` were created or
modified in the last 4 weeks (+6,987 / −250 lines over 44 commits). **93% of SPI commits also change
`runtime/appchain` in the same commit**; 48% also change `appchain/` modules. Repo-wide churn in the
window: +447,965 / −60,472 lines. **45 distinct ADR files** appear inside cross-seam commits — every
recurring campaign ADR (`app-layer/013`, `dx/0001`, `utxo/001`, `006`, `011.x`, `025.x`, `008.4`) is
cross-seam.

---

## 4. The architectural seam

The app-chain engine is far more separable than the module layout suggests.

**`runtime/appchain` (21,630 LOC, 46 files) has zero imports of `runtime.chain`, `runtime.sync`,
`runtime.consensus`, `runtime.blockproducer`, `runtime.ledger`, `runtime.apply`, `runtime.genesis`,
`runtime.utxo`, `ledger-state`, or `ledger-rules`.** Its only yano-internal dependencies are:

- `runtime.kernel.Subsystem` — a **21-line** interface
- `p2p.peer.PeerClientFactory` — a **9-line** `@FunctionalInterface`
- `runtime.util.LifecycleFailures` — one stateless utility
- `runtime.plugins.PluginProviderRegistry` — a generic `<P> find(Class<P>, String)` registry
- public `core-api` types (`UtxoState`, `RollbackCapableStore`, `YanoConfig`, events)

It opens **its own RocksDB** (`AppLedgerStore.java:118`). The entire host↔engine contract is six
function-shaped calls in `RuntimeNode` — submit tx, read UTxOs, read protocol params, read anchor
fees, install two agent factories, register one `Subsystem`. `NodeUtxoSupplier` is literally an
adapter to cardano-client-lib's public `UtxoSupplier` — the same interface a Blockfrost backend
implements. **The engine already treats the node as a replaceable backend.**

Confirming this: every appchain module that declares `project(':runtime')` uses only
`runtime.appchain` and `runtime.plugins` from it. **Not one uses node-domain code.** And the
app-chain wire protocols (`appmsg` protocol 100, `appchainsync` protocol 103) already ship from
**yaci**, a different repo.

### Seam violations that must be fixed before any split

| # | Violation | Location |
|---|---|---|
| V1 | `runtime` compile-depends on an appchain project | `runtime/build.gradle:3` `implementation project(':appchain-config')` (3 call sites), `:37` `runtimeOnly :appchain-anchor-onchain`, `:44` `testImplementation :appchain-stdlib` |
| V2 | `plugin-catalog` cannot compile without `api.appchain` | `ContributionKind.java:3-9` — fix by returning a `String` class name instead of `Class<?>`; ~40 LOC |
| V3 | `api/plugin/domain` is an app-chain SPI wearing a generic name | `DomainQueryService.java:8` — *"Query-only view of the app chains hosted by this node"* |
| V4 | Host extension points are ad-hoc package-visible methods, not an SPI | `ServeSubsystem.java:131`, `AppChainManager.java:116`, `RuntimeNode.java:623-626` |
| V5 | App-chain leaks into the node's **public facade** | `Yano.java:45`, `RuntimeYano.java:193` |
| V6 | Build-level cycle `:app` ⇄ `:appchain-devtools` / `:appchain-showcase` | devtools consumes `:app:yanoDistZip` + `:app.configurations.runtimeClasspath`; showcase copies `:app/config/*`. **`:app` is not Maven-published** — this is the single largest split blocker |
| V7 | `verifyAdr013{Jackson,Slf4j}Alignment` resolves `:app` + 6 appchain bundles in one graph | `build.gradle:56-65` — dies at a repo boundary; its failure mode is a mixed Jackson in a native image |
| V8 | All 5 of `runtime`'s integration tests are app-chain tests | `runtime/src/integrationTest/` |
| V9 | `console-ui` is one npm package, 47–81% app-chain | cannot be module-split |
| V10 | `runtime`'s `application` mainClass is an app-chain benchmark | `runtime/build.gradle:124` → `ClassicJmtRuntimeBenchmark` |

---

## 5. The four options

### Option 1 — "Foundation stays" (as originally proposed)

`appchain/**` + `spring-starters/` move. `core-api/api/appchain` (6,737), `runtime/appchain`
(21,630), `app/api/appchain` (4,018) and `console-ui` stay in yano.

- Moves ~101k main / ~48k test LOC. Mixed-commit rate **33.7%**.
- **Keeps 247 s of the 293 s `:runtime:test`, all 5 runtime integration tests, and both of
  runtime's heaviest packages in yano.** Barely dents the stated motivation.
- Puts the **fastest-churning code in the repo** (100% of the SPI touched in 4 weeks; 93% of SPI
  commits also change the engine) on the **published-artifact** side of the boundary. Every
  app-chain feature would need a yano release first.
- The line is arbitrary: `appchain-stdlib`, `-composite`, `-role-workflow` are application
  libraries, yet `appchain-eutxo-ledger` — also a state machine — would leave.

**Verdict: reject.** Least build relief, highest coupling friction per unit of benefit.

### Option 2 — Full platform split (the clean architectural seam)

Everything app-chain leaves: `api/appchain` + `api/plugin/domain` + `runtime/appchain` +
`appchain/**` + `app/api/appchain` + the app-chain console routes. yano = Cardano node, ledger
state, plugin framework, testkit, devnet toolkit, and a node-only `:app`.

- yano keeps ~102k main LOC; ~137k moves. Mixed-commit rate **48.6%** today.
- Requires V1–V10, plus promoting `Subsystem`/`LifecycleFailures` into core-api (~600 LOC) and
  publishing a `NodeExtensionPoints` contract.
- **This is the right end state.** It is also the highest-friction one to execute *now*.

### Option 3 — Option 2, with `:app` and `console-ui` moving too

yano publishes libraries only; the deployable distribution and console ship from downstream.

- Mixed rate drops to **29.3%**; node-only commits drop to **12** — i.e. almost nobody would work
  in yano. Reflects reality: the shipped `yano-<v>.zip` is already an app-chain product that
  contains a node.
- Cleanest packaging story, but concedes that yano-the-repo becomes a library project.

### Option 4 — Leaf tier only (staged first step)

Move `examples/` (showcase, showcase-client, evidence-demo-runner), `products/evidence/` (4),
`extensions/eutxo/` (10), `extensions/eutxo-zk/` (11), `appchain-studio`, `appchain-devtools`,
`fixtures/`.

- **Removes 375 of 840 tasks (44.6%)** and ~1.2 GB of the 4.4 GB build output, at the **lowest
  mixed-commit rate (26.9%)**.
- Removes the two heaviest CI items: `appChainCryptoTest` (serialized Groth16 matrix) and the
  distribution-acceptance suites.
- Requires V6 first (publish the dist zip as an artifact).
- Does not foreclose Option 2 later; it is a rehearsal for it.
- Downside: the boundary crosses the `AppStateMachine` SPI, so each new product needs an upstream
  release.

---

## 6. Naming

Decisive constraint: **zero `yano-*` artifacts exist on Maven Central**, so Maven coordinates are
free to change *right now* — but `@bloxbean/yano-testkit` (npm) and `bloxbean/yano` (DockerHub, 48
tags) are published, so those renames are not free. `bloxbean/yano` has 3 stars; there is no brand
equity to protect. `artifactId = 'yano-' + project.name`, and the modules that would move are
**already** named `yano-appchain-*`.

| Rank | Name | Story | Downsides |
|---|---|---|---|
| **1** | **`yano-appchain`** | Mirrors `yaci` → `yaci-store`. **Zero coordinate churn** — packages (`org.yanoproject.x`, 958 files), config keys (`yano.app-chain.*`, 131 files), the `yano.sh appchain` CLI, `docs/appchain/`, and the artifact prefix all already agree | "Appchain" is mentally owned by Cosmos SDK / Polygon CDK — permissionless rollups. This is a **permissioned consortium ledger**. Fight that in the tagline, not the coordinates |
| 2 | `yano-suite` | "The batteries" — fits Option 1's framing | Enterprise-bland; repo name ≠ artifact prefix; invites scope creep |
| 3 | `yano-apps` | Shortest honest name | Undersells a 10-module EUTxO ledger; **collides in speech with the existing `app/` module** |
| 4 | `yano-labs` | Honest about eutxo-zk maturity | Kills the enterprise conversation; the pilot-readiness work is explicitly not lab work |
| 5 | `yano-ext` / `-extensions` | Accurate for Kafka/S3/IPFS | Wrong for products — the evidence registry is an application, not an extension |
| 6 | Standalone brand (Musubi, Torii, Kanso…) | Detaches from the Cardano node story | Every good short name is taken (Musubi = Kinto; Torii = Qovery; Kanso = CouchApp). Costs 958 Java files + 131 consensus-scoped config-key files + lock files + gitops exports + 60 ADRs. Buys nothing unless detaching from BloxBean entirely. Note `Zano` is an existing chain, one letter away |

**Recommendation: `bloxbean/yano-appchain`.** If a product brand is ever wanted, put it on the
**vertical** (the evidence/DPP registry), not the framework.

### What ships from where

| Surface | Allocation |
|---|---|
| Quarkus distribution | **Split.** `yano-node-<v>.zip` from yano; `yano-appchain-<v>.zip` downstream. Never publish two things named `yano.jar` |
| console-ui | **Split.** Node console (734 LOC) stays; app-chain console (3,119 LOC) moves; publish a shared `yano-console-kit` |
| appchain-studio | Moves (60 KB, 6 files) |
| npm testkit | **Stays in yano, and shrinks** — its API is `faucet/time/queries/tip`. This is the clearest user-visible win: JS devs stop downloading an app-chain CLI |
| Docker | `bloxbean/yano` = node; new `bloxbean/yano-appchain`. **Mandatory** — otherwise `latest-jvm` silently gains/loses app-chain capability depending on which repo released last |
| Spring Boot starter, scaffolds, `yano.sh appchain` CLI, AI skill | Move |
| Java testkit / testkit-ccl / devnet-toolkit | Stay |
| plugin-catalog / `yano-plugins` CLI | **Stays** — and becomes the cross-repo contract enforcer |
| Showcase / evidence / EUTxO / ZK demos, `adr/app-layer/**`, `docs/appchain/**`, both `.pptx` decks | Move |

**Compatibility:** do not hand-maintain a matrix. `plugin-catalog`'s manifest already requires
`yanoApi: {min, max, minLevel}` with a monotonic additive API level. Make `yanoApi.minLevel` the
**only** compatibility statement between the repos, enforced offline by `yano-plugins`.

---

## 7. Do these first — they require no split

| # | Fix | Expected effect |
|---|---|---|
| **F1** | **Split `:runtime:test`.** Tag the 57 `runtime.appchain.*` suites (multi-node, `freePort()`, `Thread.sleep`) as integration tests | Fast gate **293 s → 46 s**; repo-wide unit tests **490 s → ~145 s**. One build-file change |
| **F2** | **`org.gradle.parallel=true` + `maxParallelForks` + `--parallel` in CI.** `build.gradle:343` already says *"Keep these suites serialized even when the rest of the build uses `--parallel`"* and registers `cryptoTestLock` — the author designed for it and never switched it on | 2–4× wall clock on 16 cores. ~10 lines |
| **F3** | **Add `nodeBuild` / `appchainBuild` aggregates.** `:runtime:build` is 70 tasks vs root `build`'s 840 | Delivers the entire claimed benefit of the split, today, with zero restructuring |
| **F4** | **Move heavy verification off `check`** into a `releaseCheck` run only by `distribution-check` | Removes 8 shadowJars, 2 forked JVMs, 5 bash suites from every dev build |
| **F5** | **Stop packaging on `build`** — fast-jar instead of the 270 MB uber-jar; disable the 4 unused `distTar`s | ~394 MB → ~50 MB of archives per build |
| **F6** | **Fix CI**: cache the two Node toolchains + `node_modules`, collapse them into one shared `workDir`, add a **remote build cache**, and gate `effect-failover-e2e` (240 min), `connector-fault-matrix` (60 min) and the native image behind a label or nightly | Hours → tens of minutes of PR wall clock |
| **F7** | Enable the configuration cache. One reported blocker (`plugin-catalog/build.gradle:31-42`, a serialized `Zip` task) plus ~8 execution-time `project(...)` accesses, all read-only classpath resolutions that convert cleanly to `Provider`s | — |

---

## 8. Prerequisites for any split

1. **Publish yano to Maven Central first.** Zero `yano-*` artifacts exist today; a downstream repo
   cannot release against nothing.
2. **Move mainline to a real `-SNAPSHOT`.** `0.1.0-pre11` makes `daily.yml` publish to the release
   staging URL, and the git-hash suffix never fires.
3. **Publish the `:app` distribution zip as an artifact** (`yano-dist`), or move `:app` downstream.
   Four hard task references depend on it (V6).
4. **Publish a `yano-bom` and a `yano-catalog`.** Alignment is currently enforced by
   `resolutionStrategy.eachDependency` inside `app/build.gradle:28-46`, which cannot cross a repo
   boundary; and four fast-moving pre-releases (yaci pre12, ccl pre5-dev1, julc pre14, zeroj pre10)
   would otherwise drift between two copies of `libs.versions.toml`.
5. **Composite build with explicit `dependencySubstitution`.** Artifacts are renamed to
   `yano-<project.name>` while project paths stay `:<name>`, so Gradle's *automatic* substitution
   **silently will not match** — you get a mixed classpath. CI must assert against it.
6. **Split `verifyAdr013*`** into a yano-side check plus a downstream check against `yano-bom`.
7. **Fix `@bloxbean/yano-testkit` dist-tags** — `latest` currently points at
   `0.0.0-oidc-bootstrap.0`, so `npm i` installs a stub.
8. **Link-check gate** — docs cross the seam in both directions today.

---

## 9. Recommendation

**Target state: Option 2/3.** The evidence for it is strong — the engine has no node-domain
coupling, its protocols already live in yaci, and the node's own facade leaking `AppChainGateways`
is the anomaly, not the seam.

**But not now.** At 48.6% mixed commits, a 4-week-old SPI growing 7,000 lines a month, 93% of SPI
commits also touching the engine, and 45 ADRs spanning the seam, a two-repo boundary would convert
~25 commits a week into cross-repo PR pairs with a publish step in between — during peak
construction.

Sequence:

1. **F1–F7 now** (days). Re-measure. If the pain is gone, stop — the module count was never the
   problem.
2. **If not: Option 4** — move the leaf tier (examples, evidence products, eutxo, eutxo-zk, studio,
   devtools, fixtures) to **`bloxbean/yano-appchain`**. 44.6% of the task graph at 26.9% seam
   friction, after fixing V6.
3. **Fix V2, V4, V5 opportunistically** — small, independently valuable, and they are what makes
   Option 2 possible later.
4. **Revisit Option 2 in 8–12 weeks** against the tripwires below.

### Tripwires for the full split

| # | Signal | Today | Threshold |
|---|---|---|---|
| T1 | Mixed-commit rate at the Option 2 seam, sustained ≥8 weeks | **48.6%** | < 10% |
| T2 | Node-only share of code commits | **11.5%** | > 30% |
| T3 | SPI churn in `core-api/api/appchain` | **+6,987 lines / 4 weeks, 100% of files touched** | < 200 lines/month, no breaking changes across 2 releases |
| T4 | SPI commits that also change `runtime/appchain` | **93%** | < 25% |
| T5 | `Yano`/`RuntimeYano` no longer expose `api.appchain` types | leaking | clean |
| T6 | `:app` ⇄ devtools/showcase task references replaced by artifact deps | 4+ | zero |
| T7 | `console-ui` app-chain surface extracted or moved wholesale | 47–81% app-chain, one package | resolved |
| T8 | ADR-013 alignment enforceable across the boundary | whole-graph only | BOM exists |
| T9 | ≥1 external consumer builds against published `yano-*` artifacts | **none exist on Central** | ≥1 |
| T10 | Separate maintainers / release cadence | single owner | true |

**None of the ten hold today.** T1–T4 are load-bearing.

---

## 10. Where the reviewers disagreed

- **Architecture** concluded the engine must move and that Option 1 should be rejected outright,
  arguing the split is overdue as an *architectural* matter.
- **Devil's advocate** agreed on the target but measured the friction and concluded *not yet* —
  and found that the stated motivation has an untried one-property fix.
- **Build engineering** concluded the split is "a legitimate modularity/ownership decision and a
  poor build-time decision," and that extracting `runtime/appchain` into modules — the prerequisite
  for any split — *already* delivers the build isolation without a second repo.
- **Product** was seam-agnostic on naming but found the deeper issue: the repo ships an app-chain
  product wearing a Cardano-node README, and the split is the moment to fix that positioning.

All four agreed on the naming recommendation and on the prerequisite list.
