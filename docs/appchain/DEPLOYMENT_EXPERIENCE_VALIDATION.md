# Deployment experience implementation and validation

Date: 2026-09-06. Branch: `docs/deployment-experience-review`.

This records the implementation of [ADR-047](../../adr/047-cohesive-application-deployment.md)
and the first delivery from the [deployment experience review](DEPLOYMENT_EXPERIENCE_REVIEW_AND_PLAN.md).
The user-facing entry point is [Deploy Yano X](deployment/README.md).

## Delivered

| Goal | Implementation | Boundary |
|---|---|---|
| Beginner demonstration | Release-first showcase quickstart, data submission, verification, and restart instructions | Requires a qualified matching release; public-release blockers in ADR-045 remain |
| Own application | Studio and CLI author one profile containing 1–32 chains; local `prepare` pins independent member identities | Shared member placement; stock and custom selections still pass catalog validation |
| Add a chain | `chain add` → digest-bound `plan` → stop → journaled `apply` → restart | Existing consensus/genesis changes and removals fail closed; no restart-free registration |
| Existing VMs | Ansible export of the same application configuration, pinned archive, private environment delivery, systemd, and readiness | Initial deployment and same-revision reconciliation; requires live VM qualification |
| Operator infrastructure | Existing cloud/showcase deployment route remains documented alongside custom-profile exports | General cloud application input and coordinated remote upgrades remain follow-up work |
| Clear documentation | Canonical deployment guides imported into the docsite; beginner quickstart and sidebar updated | Site built locally; no website publication or domain migration performed |

The implementation caught and fixed a real local-cluster defect: the host's default
history archive directory was shared by members. Generated overlays now isolate
history and log files under each node's data directory. L1, app-chain, and derived
index stores remain separate.

## Build identity

Validation consumed published local Maven inputs and the matching ordinary JVM
ZIP for Yano `0.1.0-pre14-ba9ac62-SNAPSHOT`. No sibling source dependency, build
invocation, or sibling source edit was introduced. Yano X is `0.1.0-SNAPSHOT`.

The exercised Yano X archive was `yano-x-jvm-0.1.0.zip`, SHA-256:

```text
82358e41c314148e6407b7603c087eb3ea9523efe5e93f9ce1ae07957ed669bc
```

These are local qualification inputs, not evidence of a published pre13-compatible
release. Final source-only formatting and documentation follow-ups do not change
the exercised deployment behavior.

## Automated checks

- All 90 devtools tests passed, including multi-chain determinism, stable chain
  identities under reordering, placement rejection, private-key reuse, stale-plan
  rejection, active-process rejection, interrupted-apply recovery, and VM exports.
- Root `test`, `verifyArtifactInventory`, `verifyJvmOnlyBuild`, and
  `distributionCheck` passed.
- Showcase script and distribution contracts passed.
- Studio's nine tests passed. A DOM exercise checked adding, editing, switching,
  removing, and exporting multiple chains. No browser-based visual qualification
  was available in this environment.
- The actual packaged Ansible export passed `ansible-playbook --syntax-check`.
  Its lock verifier accepts original generated files and rejects tampering.
- The documentation site built 54 pages; all 81 distinct internal targets across
  55 checked pages resolved. Mermaid checks passed.

## Repeatable live acceptance

**Passed:** the complete three-node run, including additive activation, all-chain
drift, registry insertion, follower catch-up, and another restart. Old-chain
profile/genesis, capability-manifest digest, consensus digest, historical root,
record value, and inclusion-proof bytes stayed unchanged. Each checked block had
two certificate signatures and matching roots on all three members.

The retained evidence from this run is at
`/private/var/folders/9x/p4g24d210kq8hngwdmh5mwrm0000gn/T/yano-x-additive-acceptance-5c_fymej`.
All test nodes were stopped at completion.

Run the repository-owned test against an extracted matching JVM release:

```bash
python3 tooling/devtools/src/test/scripts/additive-deployment-acceptance.py \
  /absolute/path/to/yano-x-jvm-<version> --http-base 19080 --server-base 24437
```

It creates a fresh private devnet and retains its printed evidence directory.
It never adopts or resets an existing deployment. It starts orders and documents
on three members, saves records and inclusion proofs, adds a registry through a
reviewed stopped revision, checks old records again, finalizes a registry write,
checks every chain through drift, exercises follower catch-up, and repeats restart.
Proofs are checked against caller-pinned roots agreed by the three members;
this does not claim independent public-network anchoring.

No retained showcase cluster was modified, no public-network transaction was
submitted, and no remote VM was provisioned or changed. Remote firewall, service,
backup/restore, and cross-node qualification must be completed before an operator
uses the new export as a production deployment procedure.

## Yano main revalidation — 2026-09-08

The follow-up build consumed Yano main commit
`310b9f37bae6fb3f23b9026d2f6b115ab7c79a68`, published locally as
`0.1.0-pre14-310b9f37b-SNAPSHOT`, with its matching ordinary JVM ZIP.
The host was built in a clean detached worktree, preserving existing sibling
checkout changes. Java 25 and an 8 GB Gradle heap were used. The repository's
default dependency version was not changed to this unpublished snapshot.

Validation exposed and fixed three compatibility gaps:

- The SDK and CLI now retain the complete version-3 certified block header and
  verify the host's canonical, domain-separated commit digest. Trust inputs
  include the independently pinned consensus-context digest for the target
  height. A captured public certificate and header-substitution regressions
  exercise the real main-branch wire format.
- Configuration metadata expectations include the new `consensus.` and
  `observations.` namespaces. The existing document-trail recipe is now listed
  in the capability guide.
- The showcase now assigns each JVM its own `nodeN/history/` directory. Main's
  enabled devnet projections otherwise cause a shared DuckLake writer lock.
  The generated-overlay contract covers all three paths.

The full `build integrationTest cryptoTest` invocation passed: 1,239 unit tests,
one integration test, and 14 crypto tests passed; two unit tests and 16 opt-in
integration tests were skipped. External Kafka, S3, and IPFS integrations were
not qualified without their configured services. Artifact inventory, JVM-only,
distribution checks, isolated publication staging, and the docsite build passed.
After the showcase fix, its unit, script, and distribution contracts also passed.

The packaged three-node additive acceptance passed again, including preservation
of existing records/proofs, registry addition, catch-up, and restart. The packaged
SDK separately verified the captured main-branch certificate. A fresh showcase
passed `doctor`, `quickstart`, resume, an additional orders submission, and
`verify all`: all 13 chains agreed, while orders, workflow, and authenticated map
reached heights 1, 6, and 11 with 2-of-3 certificates. The workflow's local devnet
L1 anchor was confirmed. Quickstart does not submit traffic to every chain.

### Remaining host issue

Follower logs reported a durable host projection capture failure at block 309 /
slot 336: `address-transaction projection could not resolve consumed output`.
Canonical L1 application continued, but the history projection drain paused.
The app-chain results above do not qualify follower L1 history completeness.
This upstream issue remains unresolved; no sibling source changes or disabling
of history were used to hide it.

### Reproduction and artifacts

After publishing the exact host inputs, run:

```bash
./gradlew build integrationTest cryptoTest \
  -PyanoVersion=0.1.0-pre14-310b9f37b-SNAPSHOT \
  -PyanoJvmDist=/absolute/path/to/yano-0.1.0-pre14-310b9f37b.zip \
  -PuseMavenLocal=true -PskipSigning=true --offline --no-parallel \
  '-Dorg.gradle.jvmargs=-Xmx8g -XX:MaxMetaspaceSize=1g'
```

| Archive | SHA-256 |
|---|---|
| `yano-x-jvm-0.1.0.zip` | `752fd77814649b7707e2ee1f9e20058f1d783796bc613f6fa75328156a1109b2` |
| `yano-showcase-0.1.0-SNAPSHOT.zip` | `be6c7edfaa82c4af01b660496990c1eadc6c5ebd7f0c7be83066c348a24be005` |

Local evidence and the test runbook are retained at
`/Users/satya/Downloads/yano-cluster/yano-x-main-310b9f37b-q3u2qjo1/VALIDATION.md`.
The corrected extraction is under `showcase-qualified/`; its `main-check`
instance uses HTTP 19180–19182 and N2N 24537–24539. The older retained showcase
was untouched. No public-network transactions or remote VM changes occurred.
