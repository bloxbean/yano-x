# ADR-038 implementation and qualification

This ledger accompanies [ADR-038](038-topic-policy-integration-schemas-rules-and-tooling.md)
and Yano X PR #80. Qualification was performed in isolated worktrees before PR publication. This ledger records local qualification;
it does not publish a Yano or Yano X release.

## Inputs and workspaces

- Yano X: `feat/pr80-topic-policy-integration`, based on
  `522edf94afe7a35552d6d94958d6d9579baf3bc1`, in
  `/private/tmp/yano-x-pr80-implementation`. PR #80's ADR was applied to this base.
- Host: untouched Yano main at `7f92fbe6a150fa1320ac089a88f1a3334efd71e1`, built in
  `/private/tmp/yano-pr80-host`, published to Maven Local as
  `0.1.0-pre19-7f92fbe6a-SNAPSHOT`. Its matching ordinary JVM ZIP is
  `app/build/distributions/yano-0.1.0-pre19-7f92fbe6a.zip` in that worktree.
- Reference example: `feat/pr80-delegated-game-center` in
  `/private/tmp/yano-x-pr80-examples`, based on examples commit
  `552c38fdb51d203b7b1bca332d0d44ba01cb4af2`. The existing untracked
  `21-anchor-game-center` source was copied into this isolated worktree before
  implementation. Original examples, runtime data, and user edits are preserved.
- Java 25. X uses explicit Maven Local opt-in and the matching ZIP, without a
  source dependency, composite build, or task invocation into the host checkout.
- Host publication needed a local init script disabling Javadoc doclint because
  an existing runtime comment references the removed `CanonicalTip.epoch()` method.
  Host production sources were not changed. The exact version was passed explicitly
  because Git version inference did not identify the detached worktree's hash.

## Milestones

| Milestone | Implementation | Qualification |
|---|---|---|
| MX1 | Single core schema evaluator; neutral CDDL compiler; API level 13 manifests | Existing schema corpus and independent vectors pass; bounded-work regression added |
| MX2 | Java and dependency-free browser clients, frame/delegation signing, sessions, refusal codes | 91 Java SDK tests and 78 JavaScript tests pass, including frozen core wire and signature vectors |
| MX3 | Topics CLI, portable plans, owner signing, governance export, resolver, offline verifier and doctor | Focused CLI/proof/doctor tests and the final packaged three-node gate pass |
| MX4 | Committed payload schema definitions, writer/provenance views, deterministic crossing checks and budgets | Runtime/compiler tests and the final activation/mempool/restart gate pass |
| MX5 | Topics Studio, pending diagnostics, lossless binding edits, docs, delegation service and game integration | 124 Studio Node tests, all 30 browser tests, and all 23 game-reference checks pass on the final ZIP |

## Independent review and iterations

Each milestone was examined by an agent other than its implementer. Findings were
reproduced and corrected before broad qualification:

- **MX1:** the hardened core evaluator charges repeated literal comparison bytes
  more strictly. A legacy 64,400-byte IR and 1,001,503-byte value reproduced the
  difference. A regression records the core refusal. ADR r6 explicitly requires
  fresh genesis and does not claim universal retained-chain replay compatibility.
- **MX2:** an external asynchronous signer could observe caller mutation of a byte
  buffer. Signing inputs are now copied, with a regression covering the race. The
  merged host's additional proof/entry `generationId` is accepted and checked
  against the canonical profile/genesis tuple, rather than treated as an unknown field.
- **MX3:** plan submission now rechecks ancestor authority epochs and stale heads;
  proof resolution binds the returned candidate height. Offline verification uses
  independently pinned roots and full policy/replay/revocation evidence.
- **MX4:** permission to cross a writer policy must still enforce the target's
  schema and payload limit. Derived steps now enforce both, and reserve the core
  evaluator's maximum work in the committed cascade and block budgets. Retirement
  and unregistered rejection always apply. Static program validation remains
  independent of current registry state.
- **MX5:** CDDL controls no longer mutate cached named types; governance recovery
  can handle pending revisions and cannot silently discard an expiry; form values
  round-trip through lossless YAML. Binding editing preserves schema contracts and
  explicit crossing flags. The site mirror includes the SDK modules and plan fixture.
  The example's encoded-separator and symlink aliases are rejected. Its trusted
  profile reader pins the separate profile chain generation before and after the
  record read, in addition to the game chain generation. Deeply expanded named
  CDDL rules now fail with a bounded diagnostic in Java and the browser, before
  stack exhaustion; a 128-rule alias chain remains valid. Unknown/malformed freed
  revision responses fail closed. The rule form handles schema-derived field
  suggestions without assuming their names are available in a catalog.
  The final ADR audit also closed Studio's missing owner/issuer revocation path.
  Eight complete Java/Studio plan fixtures now agree, including retired-owner
  revocation. Credential signatures bind scope, chain and generation; asynchronous
  verification copies its inputs and refuses export if the draft or snapshot changes.

The full source tests and artifact/JVM boundaries passed. The first full integration
run exposed two host-upgrade fixture issues: a tutorial omitted the new committed
clock-skew field, and three historical CLI outputs carried old execution-identity
hashes. The three outputs were compared byte for byte; only `executionIdentity`
changed. Both integration classes passed after these narrow corrections. All
other integration tests in that run passed. Fourteen external connector tests
were skipped because their S3/IPFS/Kafka services were not provisioned. The
`cryptoTest` aggregate has no separate sources in this tree; cryptographic
coverage is in ordinary Java/JavaScript tests and live certified-proof verification.

## Qualification commands

Run from the X worktree with the exact properties above:

```bash
./gradlew test verifyArtifactInventory verifyJvmOnlyBuild integrationTest cryptoTest \
  -PuseMavenLocal=true -PskipSigning=true --offline \
  -PyanoJvmDist=/private/tmp/yano-pr80-host/app/build/distributions/yano-0.1.0-pre19-7f92fbe6a.zip
./gradlew distributionCheck :tooling:studio:testStudio :tooling:studio:testTopicClients \
  -PuseMavenLocal=true -PskipSigning=true --offline \
  -PyanoJvmDist=/private/tmp/yano-pr80-host/app/build/distributions/yano-0.1.0-pre19-7f92fbe6a.zip
```

The initial distribution check passed. The schema microbenchmark (10,000 measured
iterations, 20,000 warmup) reported 144.2 ns/value for encoding and 647.9 ns/value for
schema validation, ratio 4.49. This single-process in-memory measurement is not a
production throughput bound. The documentation site build and its 148 distinct
internal link targets passed. Final packaged multi-node evidence is recorded below.

Final archive: `/private/tmp/yano-x-pr80-reviewed.7hw45v5z/yano-x-jvm-0.1.0-pre4.zip`,
506,075,362 bytes, SHA-256
`1cc44e322d7eec6d59f3ec81b747cf906cff2ab122bb4543b133a2024107e407`.
The host ZIP SHA-256 is
`9e661e653fe304f2274ecf9e52da768e5a23dabb68356d7a0c2406dc5a94c577`.
Independent archive review matched the CLI/SDK JARs, runtime bundles, 102 relevant
compiled classes, five JavaScript modules, and current Studio assets to the source
and build outputs. All 24 deployment tests in five suites passed with this final
archive supplied, including both normally conditional archive/genesis cases.

The first full smoke passed the generated-project and stock-cluster gates, then
found a real devnet anchor configuration issue: a 7,200-slot default expiry lay
beyond the fast network's Plutus forecast horizon. A scratch-only diagnostic
confirmed `UTXOS.CollectErrors: BadTranslation TimeTranslationPastHorizon`.
The showcase now derives `min(299, floor(3*k/f)-1)` from its selected Shelley
genesis for devnet anchor transactions. Public-network defaults, ledger validation,
host sources and retained deployments are unchanged. Contracts cover stock,
custom, fractional and retained genesis selection, invalid geometry, and public
network exclusion. The final smoke passed after the following review iterations.

A subsequent smoke exposed an L1 follower stall; history correctly refused an
observation ahead of that follower's L1 view. Recovery then exposed a separate,
deterministic showcase configuration bug: initial overlays hashed the stock
Shelley genesis, but restart regenerated them from the host's time-shifted genesis.
A read-only inspection reproduced the persisted block's exact context with the
first hash and the rejected context with the second. The public calculations are
in [showcase-genesis-diagnosis.json](038-qualification/showcase-genesis-diagnosis.json).
The showcase now pins the complete devnet genesis before generating its first
observer overlay and supplies its timestamp through the supported cluster launcher.
Every retained node genesis and observer identity is checked before configuration
changes. Conflicting custom input and unpinned legacy state refuse without rewriting
retained data; `stop` remains available. Eleven helper tests and the full facade
contract passed independently. No host source or consensus validation was changed.
The final rebuilt archive passed its fresh complete smoke run.
The first pinned-genesis candidate also exposed a cadence regression: selecting
the pin as a custom genesis bypassed the packaged launcher's stock-only 500-slot
epoch normalization and retained the template's 1,200 slots. The bounded history
wait failed, and its nodes stopped cleanly. The stock pin now applies that same
500-slot normalization before publication; explicit custom and retained genesis
bytes remain unchanged. Full JSON comparison confirmed that the stock timestamp
and epoch length are the only fields changed by the existing launch behavior.
The correction passed both focused and independent contracts; its failure evidence
is in [showcase-cadence-diagnosis.json](038-qualification/showcase-cadence-diagnosis.json).
The next run passed every scenario and cross-node history verification, then
restart preflight incorrectly treated live PID sidecars as node directories.
Exact numeric directory/overlay matching fixes this while retaining file and
symlink refusal at actual node names. The facade restart test now creates the
launcher's real PID/meta layout. This finding is recorded in
[showcase-live-layout-diagnosis.json](038-qualification/showcase-live-layout-diagnosis.json).

## Final packaged evidence

- [Release smoke](038-qualification/release-smoke.json): all 16 gates passed on
  the final archive, including stock/generated clusters, real anchor confirmation,
  all showcase scenarios, cross-node history, restart and persisted roots.
- [Topic-policy V3](038-qualification/topics-v3-final.json): all three members
  agree at height 38, including actual schema activation and held-message
  re-admission, target-policy refusals, certified proofs, catch-up and restart.
- [Genesis parity](038-qualification/showcase-genesis-parity.json): the 500-slot
  pin and every member's genesis remained byte-identical through restart.
  [Historical roots](038-qualification/showcase-persisted-tips.txt) also persisted.
- [Deployment tests](038-qualification/deployment-tests.json): 24 passed, none skipped.
- [Game-reference V4](038-qualification/game-v4-final.json): all 23 checks passed
  against the same archive, including real profile authentication, host-side
  refusals, issuer rotation, ownership recovery, catch-up and full restart.
  The copied [authorship proof](038-qualification/game-v4-offline-verification.json)
  verified offline after every member stopped. It uses caller-pinned certified
  roots and explicitly makes no Cardano anchoring or durable-availability claim.
  The full public bundle and runbook are in the companion examples worktree at
  `21-anchor-game-center/delegation/VERIFICATION.md`.
- [Cleanup](038-qualification/release-cleanup.json): all 24 task-selected smoke
  ports were unused after completion. Failed candidates were also stopped and
  preserved as diagnostic evidence; no retained user deployment was changed.

## Preview boundaries

This host is unpublished. Retained clusters are neither reset nor migrated.
The host's new consensus-profile clock-skew field and schema work accounting
require the fresh-genesis qualification described in the ADR. The generic entry
proof is labeled as entry-only; no nonexistent `topic-entry-v1` REST API is assumed.
The example keeps issuer material outside the node and static site. Browser ride
replay remains necessary and does not prove that a player is human.
