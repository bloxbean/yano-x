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
