# Tutorial 5 — Generic Domain Actors and Role-Aware Approval

[Open generic role approval in App-Chain Studio](../../../tooling/studio/src/main/web/index.html#recipe=role-approval&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=host&name=role-approval&chainId=role-approval)

- **Level:** application developer; advanced for identity governance
- **Time:** about 30 minutes, plus the first image build
- **Outcome:** run a release that business actors from different
  organizations approve under a governed policy, watch deliberate mistakes
  become final no-ops, and rotate and revoke an actor key.

Role-aware approval separates two identities:

- **member identity:** which member node relayed an envelope; and
- **actor identity:** which business actor signed the exact statement inside it.

This tutorial runs the evidence demo's `role-evidence` profile, which builds on
the same actor registry and approval rules as the generic
[`role-approvals`](../state-machines/role-approvals.md) machine.

## How the policy decides

The demo's policy, `evidence-release`, needs a `manufacturer` to propose, two
`auditor` actors from distinct organizations, and one `regulator`. The demo
sends these statements for one release, in this order:

<!-- illustration: role-policy -->
1. **Manufacturer proposes.** `manufacturer-a` opens the proposal.
2. **Wrong role.** `manufacturer-a` tries to approve as an auditor:
   `ROLE_MISMATCH`, a final no-op.
3. **Wrong payload.** `auditor-a1` signs another hash: `CONFLICT`, a final
   no-op.
4. **First auditor.** `auditor-a1` from `audit-org-a` approves.
5. **Same organization.** `auditor-a2`, also from `audit-org-a`, approves:
   `DISTINCTNESS_DUPLICATE`, a final no-op.
6. **Second auditor.** `auditor-b` from `audit-org-b` approves; the regulator
   clause is still open.
7. **Regulator approves.** `regulator-a` approves, and the proposal is APPROVED
   with three accepted decisions.
<!-- /illustration -->

The three mistakes are final, like every other message, but they change
nothing. Only eligible, signed decisions count toward the policy.

## 1. Start the role profile

You need JDK 25, Docker with Compose v2, `curl`, `jq`, `openssl` and Python 3.
From the top-level directory of the extracted release:

```bash
cd examples/evidence

./demo.sh up \
  --instance tutorial-roles \
  --machine role \
  --continuation direct
```

`up` prepares the instance first: it generates its secrets and builds the Yano
and runner images, which takes a while the first time. `./demo.sh prepare` runs
only that preparation, if you want to do it ahead of time. Then `up` starts
three members and the publication services, funds and bootstraps a devnet
anchor, and runs a read-only readiness probe.

The profile commits its organization and actor registry, policy, component
order, routes, effect workflow, administrator threshold and limits at genesis.
It is not a setting you can switch on for an existing chain. Pass the same
`--machine role --continuation direct` to every command for this instance.

## 2. Publish actor-authorized evidence

```bash
./demo.sh publish \
  --instance tutorial-roles \
  --machine role \
  --continuation direct \
  --evidence-id regulated-product-001 \
  --sample-file samples/inspection-certificate.json
```

The runner submits the statements shown above, including the three negative
controls, then releases the evidence once the proposal is APPROVED.

## 3. Inspect and verify

```bash
./demo.sh verify \
  --instance tutorial-roles \
  --machine role \
  --continuation direct \
  --evidence-id regulated-product-001
```

Open <http://127.0.0.1:7080/>. The report separates the relay member, actor,
organization, role, policy revision, clause and signed decision. Current actor
and policy projections include both the revision proof and the same-root
current-pointer proof, so “this revision exists” is never confused with “this
is the current revision”.

## 4. Rotate and revoke an actor

```bash
./demo.sh role-lifecycle \
  --instance tutorial-roles \
  --machine role \
  --continuation direct
```

The exercise uses a dedicated recovery actor and shows:

1. governed onboarding with proof-of-possession;
2. signing-key rotation;
3. the old actor revision and key are refused;
4. the new revision and key are accepted;
5. revocation;
6. refusal after revocation; and
7. proofs of the historical revisions and decisions.

This is not member rotation. Business credentials and member nodes are
governed separately.

## 5. Stop and retain

```bash
./demo.sh stop \
  --instance tutorial-roles \
  --machine role \
  --continuation direct
```

`stop` keeps the instance's data and secrets.

## Use the generic machine

The generic [`role-approvals`](../state-machines/role-approvals.md) machine
approves any payload hash and emits nothing. Its reference page shows how to
create a project with the `role-approval` recipe, sign statements with
`./yano.sh appchain role sign`, submit them on `role-approvals.command.v1`,
and query and prove the result. It also lists the lifecycle, the order of
checks and every outcome code.

Its organizations, actors and policies are created through member governance.
`./yano.sh appchain role bootstrap` encodes them from the project's generated
`bootstrap/role-approvals-plan.yaml`; the reference page walks through the
bootstrap, from actor keys to a read-back of every record.

## Reuse levels

- **Configuration only.** Use a stock profile when its action and terminal
  transition already match. Governed data defines actors, organizations,
  roles, policies, counts, organization distinctness, deadlines and connector
  targets.
- **Small composite plugin.** Use the existing registry, approval, evidence or
  payment components in a new order, or connect approval to a different
  terminal action. Order and cross-component transitions affect consensus, so
  they stay reviewed Java composition.
- **Custom state-machine plugin.** Only for new state or business rules.

## Production boundaries

- The demo uses deterministic actor seeds held locally. Production signing
  belongs in a KMS, HSM, vault or an actor-owned signing service.
- Yano proves that registered keys authorized exact bytes under a governed
  policy. Legal identity and real-world truth remain onboarding and audit
  duties.
- An actor record keeps at most 16 key epochs. Plan a successor identity before
  you reach that limit.
- Administrator authority is part of the profile; changing it is governed
  profile evolution, not an ordinary actor change.

## Go deeper

- The [`role-approvals` reference](../state-machines/role-approvals.md), with
  an interactive walkthrough of every outcome code.
- The complete [domain-role guide](../../APP_CHAIN_DOMAIN_ROLES.md).
- The [evidence demo README](../../../products/evidence/harness/README.md), for
  other profiles, ports and cleanup.
