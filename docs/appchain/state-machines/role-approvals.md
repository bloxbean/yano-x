# role-approvals

`role-approvals` lets business actors approve things when those actors are not
the app ledger's member nodes. Governed organizations, actors, keys, roles and
policies decide whether an exact payload hash is approved. The proposal and
its decision trail can be proved against the state root.

## At a glance

| | |
|---|---|
| Machine id | `role-approvals`: the composite profile `role-approvals-v1`, with components `domain-actors` and `role-approvals` |
| Maturity | preview, JVM distribution |
| Topics | `role-approvals.command.v1` (actor statements and policy governance); `actors.command.v1` (organizations, actors and keys) |
| Actions | `PROPOSE`, `APPROVE`, `REJECT`, `CANCEL`, each an Ed25519-signed actor statement |
| Statuses | `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`, `EXPIRED` |
| State | proposal, policy, actor and organization records inside the composite; use the domain API's `proofKey` |
| Proof subjects | `role-approval-outcome-v1` (claims `status`, `payload-digest`); `actor-role-assignment-v1` (claims `status`, `has-role`) |
| Outcome codes | `RoleWorkflowResultCode`; this profile does not store them per command |
| Effects | none |

## How it works

<!-- illustration: role-approval-walkthrough -->
1. **Sign offline.** An actor signs a statement over the exact payload hash in
   its own wallet or HSM.
2. **Relay.** Any member relays the bytes. The member signs the envelope but
   gains no business authority.
3. **Propose.** In the final block the statement is evaluated and the proposal
   opens PENDING.
4. **First review.** A reviewer from one organization approves.
5. **Second review.** A reviewer from a second organization approves, and the
   proposal is APPROVED.
6. **Verify.** A verifier checks the proposal's proof and compares its payload
   hash with the bytes it holds.
<!-- /illustration -->

### Lifecycle

| Status | Reached by | Terminal |
|---|---|---|
| `PENDING` | an accepted `PROPOSE` | no |
| `APPROVED` | an accepted `APPROVE` that satisfies every clause | yes |
| `REJECTED` | an accepted `REJECT`, when the policy allows rejection | yes |
| `CANCELLED` | `CANCEL` by the proposer, or a governed cancellation | yes |
| `EXPIRED` | the first block whose height is above the deadline height | yes |

Expiry runs at the start of every block: a maintenance pass marks each pending
proposal whose deadline height is below the block height `EXPIRED`, whatever
the block contains. A block needs at least one message on the chain, so with
no traffic the status changes at the next block.

Each actor makes one decision per proposal. Sending the same statement again
is `EXACT_REPLAY`; sending a different one is `CONFLICT`. The proposer is not
an approver unless it also signs an `APPROVE`.

### Evaluation order and outcome codes

Every statement passes these checks first:

| Order | Check | Outcome when it fails |
|---:|---|---|
| 1 | The statement names this chain | `WRONG_GENESIS` |
| 2 | Its deadline height is not below the block height | `EXPIRED` |
| 3 | The block's signature-work budget has room | `CRYPTO_WORK_EXCEEDED` |
| 4 | The actor revision, its organization and its key are current and active | `UNAUTHORIZED_ACTOR` |
| 5 | The signature verifies | `INVALID_SIGNATURE` |

Then, by action:

| Action | Checks, in order |
|---|---|
| `PROPOSE` | no proposal with this id (`EXACT_REPLAY` or `CONFLICT`); deadline above the current height (`EXPIRED`); the policy exists (`UNKNOWN_RECORD`) and the revision is current (`WRONG_REVISION`); the policy is active (`UNAUTHORIZED_ACTOR`); the deadline is within the policy's lifetime (`LIMIT_EXCEEDED`); the actor holds a proposer role (`ROLE_MISMATCH`); pending capacity has room (`CAPACITY_EXCEEDED`) |
| `APPROVE`, `REJECT` | the proposal exists (`UNKNOWN_RECORD`) and is pending (`TERMINAL`); policy, payload and deadline match it (`CONFLICT`); the actor has not decided yet (`EXACT_REPLAY` or `CONFLICT`); the actor has the clause's role (`ROLE_MISMATCH`); no decision in this clause from the same organization when the clause is distinct by organization (`DISTINCTNESS_DUPLICATE`); for `REJECT`, the policy allows rejection (`ROLE_MISMATCH`) |
| `CANCEL` | the proposal exists (`UNKNOWN_RECORD`) and is pending (`TERMINAL`); it matches (`CONFLICT`); the sender is the proposer (`UNAUTHORIZED_ACTOR`) |

A failed check makes the command a final no-op. This profile keeps no
per-command result, so read the proposal to see what happened: its status and
its accepted decisions.

## When to use it

Use `role-approvals` when:

- an employee, auditor, regulator, service or device must sign independently
  of the member node that relays its command;
- a policy needs roles, minimum counts or distinct organizations;
- actor keys and policies must rotate through governed revisions; and
- the application can act on a provably approved payload hash.

Use [`approvals`](approvals.md) when member nodes themselves are the approvers.
Use `role-evidence` for the complete evidence release and publication flow, and
a reviewed composite when an approval must atomically drive another transition.

The machine stores no payload, runs nothing and emits no effect. An approval
proves authorization of `(payloadDomain, payloadHash)`; your application binds
those bytes to its next action.

## Identity model

```text
outer envelope sender = the member that relayed the command
signed actor identity = the business actor authorizing the payload hash
```

REST authentication controls access to an endpoint. It is neither a member
vote nor an actor signature. An actor statement binds its action, chain,
proposal, policy and revision, payload domain and hash, deadline, actor
revision, key and clause, so a signature cannot be reused for another chain,
payload or decision.

## Create a project

From the top-level directory of the extracted release:

```bash
./yano.sh appchain init --non-interactive \
  --recipe role-approval --network devnet --members 3 \
  --runtime jvm --deployment host \
  --name role-approval-chain --chain-id role-approval-chain \
  --output role-approval-chain
```

Add the reviewed public member keys to the generated blueprint, then render and
validate it:

```bash
./yano.sh appchain render role-approval-chain
./yano.sh appchain config validate --mode project role-approval-chain
./yano.sh appchain doctor role-approval-chain --distribution .
```

`bootstrap/role-approvals-plan.yaml` is a non-secret plan for organizations,
actors, proof-of-possession, policies, governance and verification. Fill it
with public values only; actor private keys belong in the actor's application,
KMS, HSM or vault.

Organizations, actors and policies are created through member governance.
Each record is proposed, approved by members up to the chain's genesis
threshold, then activated. Bootstrap is fail-closed and idempotent:

1. Query the committed record and verify its proof.
2. If the exact revision already exists, record the proof and skip it.
3. If it is absent, submit `PROPOSE`, collect member approvals, then `ACTIVATE`.
4. If an existing revision differs, stop; never replace it silently.

`./yano.sh appchain role govern-propose`, `govern-approve` and
`govern-activate` encode those governance commands. The release has no
command that encodes the organization, actor or policy record itself; your
tooling builds it with the role-workflow contracts.

## Sign and submit a decision

Hash the exact canonical application bytes before actors sign them. The payload
domain names that byte contract, for example `com.example.order.v1`:

```bash
PAYLOAD_HASH=$(openssl dgst -sha256 -binary approved-order.cbor | xxd -p -c 256)

COMMAND_HEX=$(./yano.sh appchain role sign \
  --action approve \
  --chain role-approval-chain \
  --proposal order-a-1001 \
  --policy order-release \
  --policy-revision 1 \
  --payload-domain com.example.order.v1 \
  --payload-hash "$PAYLOAD_HASH" \
  --deadline-height 1000 \
  --actor reviewer-a \
  --actor-revision 1 \
  --key reviewer-key-v1 \
  --clause reviewers \
  --seed-file /owner-only/reviewer.seed)
```

Submit the bytes through any member:

```bash
curl -sS -X POST \
  http://127.0.0.1:7070/api/v1/app-chain/chains/role-approval-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"role-approvals.command.v1\",\"bodyHex\":\"$COMMAND_HEX\"}" | jq .
```

When API authentication is enabled (`yano.app-chain.api.auth.enabled=true`),
add `-H "X-API-Key: $API_KEY"` to every request. A generated project keeps its
local key as `YANO_APPCHAIN_API_KEYS` in `secrets/node0.env`.

`202` means queued, not approved. Wait for finality, then read the proposal.

## Query and verify

The bundle exposes read-only routes:

```bash
BASE=http://127.0.0.1:7070/api/v1
BUNDLE=org.yanoproject.x.role-workflow

curl -sS "$BASE/plugins/$BUNDLE/proposals/order-a-1001?chain=role-approval-chain" | jq .
curl -sS "$BASE/plugins/$BUNDLE/stats?chain=role-approval-chain" | jq .
```

Organizations, actors and policies follow the same pattern:

```text
organizations/{id}?chain={chainId}[&revision=N]
actors/{id}?chain={chainId}[&revision=N]
policies/{id}?chain={chainId}[&revision=N]
```

A response includes `committedHeight`, `stateRoot`, `proofKey` and
`recordValue`. A route that resolves the current revision also returns the
current-pointer key and value. Verify both proofs at the same height and root:
the record proof shows that a revision exists, and the pointer proof shows it
was current. The physical keys are namespaced by the composite profile; always
prove the returned `proofKey`.

## Submit from Java

```groovy
implementation "org.yanoproject.x:yano-x-client:${yanoXVersion}"
implementation "org.yanoproject.x:yano-x-role-workflow-contracts:${yanoXVersion}"
```

```java
var statement = new ActorStatementV1(
        ActorStatementV1.Action.APPROVE,
        "role-approval-chain", "order-a-1001", "order-release", 1,
        "com.example.order.v1", payloadHash, 1000,
        "reviewer-a", 1, "reviewer-key-v1", "reviewers");

byte[] command = SignedActorCommandV1.sign(statement, actorSeed).encode();

var client = AppChainClient.builder("http://127.0.0.1:7070/api/v1")
        .chainId("role-approval-chain")
        .build();

var submitted = client.submit("role-approvals.command.v1", command);
```

In production, sign `ActorStatementV1.signingPreimage()` in a KMS or HSM rather
than loading a raw seed into application memory.

## Advanced

### Limits

- Roles are normalized strings, not a fixed enum.
- Policies have proposer roles, at most 16 clauses, minimum counts, distinctness
  by actor or organization, a rejection mode and a maximum lifetime.
- Actor and policy revisions are immutable; current pointers are governed.
- An actor keeps at most 16 key epochs.
- Pending proposals are bounded in total and per actor, policy and deadline.

### Acting on an approval

The profile emits no effect, because an approved hash is not executable bytes.
An application can:

1. query the terminal proposal and act idempotently off the ledger;
2. submit a separately validated command bound to the proposal; or
3. install a reviewed composite that consumes the approval atomically, as
   [`authenticated-map`](authenticated-map.md) does for `approval` collections.

Do not attach an arbitrary executor and assume it knows which bytes were
approved.

### Operations and recovery

- Monitor pending, rejected and expired proposal counts.
- Rotate a key by governing the actor's next revision with proof-of-possession.
- Suspend or revoke a compromised actor, then govern cancellation of its
  pending proposals.
- Approved decisions stay immutable after rotation or revocation.
- A changed profile digest, component order, route or state identity is a
  consensus upgrade: it needs governed activation or a new chain.

The full wire, governance and recovery model is in
[Domain Actors and Role-Aware Approvals](../../APP_CHAIN_DOMAIN_ROLES.md), the
[portable contracts](../../../capabilities/role-workflow-contracts/README.md),
and the [implementation guide](../../../capabilities/role-workflow/README.md).

## Related documentation

- [Tutorial 5: domain actors and role-aware approval](../tutorials/05-domain-role-approvals.md)
- [approvals](approvals.md)
- [authenticated-map](authenticated-map.md)
- [State machines](README.md)
