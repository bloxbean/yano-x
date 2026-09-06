# ADR-047 — Cohesive application authoring and additive local deployment

Status: implementation in review. Date: 2026-09-06.

## Context

The [deployment experience review](../docs/appchain/DEPLOYMENT_EXPERIENCE_REVIEW_AND_PLAN.md)
identified disconnected entry points: a capable showcase, a single-chain project
compiler and Studio, and a remote deployer specialized in a showcase profile.
Users need an explicit path from first demonstration to their own application,
with state preservation when another independent chain is added.

## Decision

Extend the existing project compiler and Studio. An application contains 1–32
independent chains on shared member nodes. Compile each chain through the same
recipe, capability, metadata, and runtime-parser validation as a single-chain
project. Preserve its genesis derivation independently of other chains and list
position. Merge process-wide requirements and validate shared placement. Keep
per-chain bootstrap and verification documents separate.

The public CLI adds local `prepare`, `chain add`, `plan`, and `apply`. Preparation
pins public identities and stores private devnet keys in owner-only files;
reruns reuse them. Chain addition edits intent only. A plan binds the original
lock and exact proposed blueprint, compares old consensus settings by chain ID,
and rejects replacement/deletion or incompatible release/catalog changes.

The first apply path is a controlled stop/render/restart of a same-machine host
project. It checks prior generated file hashes, node-local settings, a process
operation lease, running-node records, and the reviewed digest. It installs a
staged revision under a pending-operation journal and writes the lock last.
Startup refuses incomplete operations or unreviewed lock changes. Recover by
replaying the same reviewed revision; never reset L1 or application storage.

The journal is configuration recovery, not a distributed consensus transaction,
backup of chain state, or automatic binary downgrade. New-chain finality and
business verification remain explicit after restart. Optional/custom plugin
installation is not performed by local apply. Every runtime contribution still
crosses PluginProviderRegistry and the schema-v1 manifest boundary.

## Delivery paths

The release showcase is the beginner entry point. Studio and CLI share the
application compiler. Generated host overlays and an Ansible export automate initial custom-profile
deployment to existing VMs, while ADR-039 provides the cloud showcase path.
The Ansible exporter verifies derivative locks, archive checksums, Java and
platform prerequisites, and retained identity before installing services and
private environment files. It rejects changed installed revisions; live VM
qualification and coordinated remote upgrades remain separate milestones.
The docs site imports the new deployment guides from `docs/`, including the
quickstart, instead of maintaining a second copy.

General application-lock input to the cloud provisioner and restart-free registration
remain subsequent milestones. The host owns dynamic lifecycle, transport, and
plugin handling; no source dependency or sibling changes are introduced here.

## Validation and limits

See the [implementation validation record](../docs/appchain/DEPLOYMENT_EXPERIENCE_VALIDATION.md)
for exact inputs, executed checks, and remaining qualification boundaries.

Require deterministic multi-chain rendering, duplicate/placement rejection,
per-chain identity invariance, local preparation idempotence, stale-plan and
running-process rejection, interrupted-apply recovery, and live three-node
addition with retained-record/proof checks and restart. Qualify the exact host
Maven version and JVM ZIP. Repository default pre13 and broader public-release
readiness blockers are tracked by ADR-045; this work does not claim to resolve them.


The retained validation procedure is
`tooling/devtools/src/test/scripts/additive-deployment-acceptance.py`. Given an
extracted matching JVM archive, it creates its own disposable three-node devnet,
checks old-chain records and caller-pinned inclusion proofs after an additive
revision, exercises follower catch-up, and checks another restart. It leaves data
and evidence in its printed temporary directory and stops only its own nodes.

```bash
python3 tooling/devtools/src/test/scripts/additive-deployment-acceptance.py \
  /absolute/path/to/yano-x-jvm-<version> --http-base 19080 --server-base 24437
```

A live test caught shared host history archive paths across local members. Generated
node overlays now isolate history and log files under each node's data root, in
addition to the existing L1, app-chain, and derived-index store separation.
