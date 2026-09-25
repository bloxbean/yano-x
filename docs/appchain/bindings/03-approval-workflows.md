# 3. Approval workflows

[Previous: Conditions and mappings](02-conditions-and-mappings.md) ·
[Learning path](README.md) · [Next: Java integration](04-java-integration.md)

An approval workflow pauses in persisted state while people decide. Proposing
an item does not wait inside a running cascade for votes. The proposal commits,
each vote arrives as a later message, and the threshold-reaching vote triggers
the next action. Only that final vote and its derived actions share one atomic
cascade.

Declarative bindings remain experimental. This page first rehearses the small
consensus-member `approvals` machine offline, then explains the separate
actor-signed DPP/feed path. The public fixture identities below are rehearsal
data, not credentials or an authorization scheme for a live application.

## Rehearse a proposal and two approvals

You need a matching extracted Yano X JVM distribution, its `plugins` directory,
Java 25, and `jq`. Run the documented commands from that distribution directory,
using new files for this exercise. They execute offline; they do not start a
node or submit messages. This is the exact worked scenario from the
[canonical CLI guide](../DECLARATIVE_BINDINGS_CLI.md#worked-proposal-and-two-approvals-through-the-packaged-launcher),
whose YAML and fixtures are exercised by the packaged distribution acceptance
test.

Save `approval.yml`:

```yaml
composite:
  components:
    - {id: reviews, machine: approvals}
    - {id: audit, machine: doc-trail}
  bindings:
    - id: record-approved
      from: {component: reviews, event: approvals.item-approved.v1}
      to:
        component: audit
        command: append
        map:
          entityId: {field: itemId}
          entryHash: {field: payloadHash}
          reference: {literal: approved}
```

The `reviews` component receives ordinary proposal and vote commands at
`reviews.command.v1`. Only its approved event appends to `audit`. No baseline
event is needed, and there is no external effect to configure.

Save the complete offline context as `approval-context.json`:

```json
{
  "chainId": "offline-test",
  "settings": {
    "state.commitment-profile": "mpf-blake2b256-v1",
    "state.format-fingerprint": "91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b",
    "state.genesis-id": "0000000000000000000000000000000000000000000000000000000000000000"
  },
  "consensusProfile": {
    "schemaVersion": 2,
    "maxMessageBytes": 65536,
    "maxBlockMessages": 100,
    "maxBlockBytes": 1048576,
    "l1StabilityDepth": 0,
    "epochStabilityDepth": 0,
    "enforceSenderSeq": false,
    "effectsEnabled": false,
    "effectsMaxPerBlock": 0,
    "effectsMaxPayloadBytes": 0,
    "effectsMaxExpiryBlocks": 0,
    "effectsResultWindowBlocks": 0,
    "effectsDefaultGate": "APP_FINAL",
    "effectsOutcomeCommitment": "PER_EFFECT",
    "effectsStrictReservedPrefix": true,
    "effectResultSigners": []
  },
  "membership": {
    "fromHeight": 0,
    "members": [
      "2222222222222222222222222222222222222222222222222222222222222222",
      "3333333333333333333333333333333333333333333333333333333333333333"
    ],
    "threshold": 2
  }
}
```

The context's membership threshold describes consensus membership. The
proposal command separately asks for two business approvals. Both are 2 in
this exercise, but they represent different decisions. Do not substitute this
zero genesis ID or these public identities into a retained deployment.

Save `propose.json`:

```json
{
  "height": 1,
  "timestamp": 100,
  "stateRootHex": "0000000000000000000000000000000000000000000000000000000000000000",
  "pendingEffects": 0,
  "state": [],
  "messages": [{
    "messageIdHex": "1111111111111111111111111111111111111111111111111111111111111111",
    "senderHex": "2222222222222222222222222222222222222222222222222222222222222222",
    "senderSeq": 1,
    "expiresAt": 999999999,
    "topic": "reviews.command.v1",
    "bodyHex": "8500616141010200",
    "authProofHex": "00"
  }]
}
```

The body is the public codec output of
`ApprovalsContract.propose("a", new byte[]{1}, 2, 0)`: item `a`, payload `01`,
two approvals required, and no business deadline. The envelope expiry is a
different field. The synthetic `authProofHex` is allowed only because dry-run
assumes envelope authentication has already happened.

Create two distinct vote fixtures. The first voter is also the proposer;
proposing does not itself cast that voter's approval. The second vote comes
from the other member. `82016161` is `ApprovalsContract.approve("a")`.

```bash
jq '.height = 2 | .timestamp = 200 | .messages[0] |=
  (.messageIdHex = ("44" * 32) | .senderSeq = 2 | .bodyHex = "82016161")' \
  propose.json > approve-1.json
jq '.height = 3 | .timestamp = 300 | .messages[0] |=
  (.messageIdHex = ("55" * 32) | .senderHex = ("33" * 32) | .senderSeq = 1)' \
  approve-1.json > approve-2.json
```

Validate, then execute each block while carrying the previous result:

```bash
./yano.sh appchain bindings validate approval.yml --plugins-directory plugins \
  --context approval-context.json
./yano.sh appchain bindings dry-run approval.yml --plugins-directory plugins \
  --context approval-context.json --fixture propose.json > proposed.json
./yano.sh appchain bindings dry-run approval.yml --plugins-directory plugins \
  --context approval-context.json --fixture approve-1.json \
  --prior-result proposed.json > voted.json
./yano.sh appchain bindings dry-run approval.yml --plugins-directory plugins \
  --context approval-context.json --fixture approve-2.json \
  --prior-result voted.json > approved.json
jq '[.receipts[].receiptHex], .stateChanges' approved.json
```

Expect this progression:

| Block | New source message | Result | Receipt steps |
|---|---|---|---|
| 1 | Propose `a` | Pending, no approvals | Source only |
| 2 | First member approves | Pending, one approval | Source only |
| 3 | Second member approves | Approved, audit appended | Source plus derived append |

The third result's complete `postState` retains the approved item, two distinct
voters, the audit state, and all three receipts. The result's `receipts` list
describes the current block's messages; it is not the complete receipt history.
These are fresh commands acting on carried state, not three replays of one ID.

An idle block still has a lifecycle. Advance exactly one height:

```bash
jq '.height = 4 | .timestamp = 400 | .messages = []' approve-2.json > idle.json
./yano.sh appchain bindings dry-run approval.yml --plugins-directory plugins \
  --context approval-context.json --fixture idle.json \
  --prior-result approved.json > idle-result.json
```

There are no source receipts in this idle result. A jump directly from height
3 to height 5 is rejected. With `--prior-result`, keep the new fixture's
`state: []`; the CLI carries the full previous `postState`, not just its last
change set. Context and executable identity must match. For larger rehearsals,
see [compact continuation](../DECLARATIVE_BINDINGS_CLI.md#consecutive-block-rehearsal).

Dry-run neither verifies signatures nor authenticates the predecessor root.
The explicit zero roots above are assumptions, and no post-state root is
computed. Results are not backups, proofs, finality certificates, or an import
format for a running chain.

## Understand the atomic boundary

If the third block's derived append rejects, that source cascade rolls back
both the threshold-reaching vote and the append. Earlier committed messages
remain: the proposal and first vote are still pending state. The rejection
receipt survives, and work already attempted remains charged.

This gives the application a useful guarantee: approval and its automatic
follow-up cannot partially commit within that cascade. It does not make the
entire human conversation one transaction. An approved event is also not an
external HTTP call. For external delivery, use an explicit effect binding and
the [effect/outbox model](../tutorials/06-webhook-effects.md); delivery is a
separate boundary, not an exactly-once promise. Do not combine a composable
approval leaf with its legacy on-approved effect activation path.

For a larger example, the [procurement recipe](../../../examples/bindings/procurement.yaml)
starts with an order put, checks supplier registration, derives a proposal,
then waits for ordinary vote messages before auditing and planning an outbox
intent. The [attestation recipe](../../../examples/bindings/attestation.yaml)
extracts a document hash from the approved payload. A derived trail append is
not a separately signed finalized message, so it cannot be passed off as an
ADR-047 signed-message attestation certificate. Prove its receipt and trail
state instead.

## Generate real governed DPP or feed bindings

Consensus-member voting is useful for the small exercise. Business actors in
different organizations need the actor-signed policy path. The DPP and feed
generators use the existing product profile builders and real authenticated-map
schemas; they do not replace authorization with a boolean binding condition.

Prepare `actors.json`, a schema-version-1 public descriptor containing your
`chainId`, organizations, actors, and authority. Each actor includes `id`,
`organizationId`, `roles`, `keyId`, `publicKeyHex`, and a full encoded
`keyProofHex`. Authority includes `id`, `administratorActorIds`,
`distinctActorThreshold`, and `maximumLifetimeBlocks`. Optional `issuers` and
`schemas` must be empty for these starters. `members.json` is an array of the
actual consensus member public-key hex strings.

Obtain each chain-bound proof of key possession through the documented offline
role CLI in [recipe generation](../DECLARATIVE_BINDINGS_CLI.md#generate-governed-recipes-for-your-own-chain).
The descriptor contains public material only; the generator never reads seeds.
A signature alone is not the encoded key-proof object, and changing the chain
ID requires new proofs. Do not reuse the exercise's assumed envelope proof.

```bash
./yano.sh appchain bindings recipe dpp \
  --descriptor actors.json --members members.json --threshold 2 > recipe.json
jq '.document' recipe.json > governed-bindings.yml
jq '.context' recipe.json > governed-context.json
./yano.sh appchain bindings validate governed-bindings.yml \
  --plugins-directory plugins --context governed-context.json
```

Use `feed` in place of `dpp` for the round-approval starter. The extracted JSON
document is valid YAML input. The generator substitutes no demo identities;
review the emitted members, proposer, threshold, and block interval when
configuring your chain. Regenerate if these genesis inputs change instead of
editing opaque genesis CBOR.

DPP starter roles are `manufacturer`, `operator`, `claim-issuer`, `certifier`,
and `auditor`, with `dpp-admin` administration. Feed uses `source`,
`feed-operator`, and `publisher`, with `feed-admin` administration. Actors need
not be consensus members. Approval quorums require the policy's distinct
organizations; two keys from one organization do not create two organizations.
Generation checks usable role holders, eligible proposers, and voting quorums
at height 1, not every future business prerequisite or arbitrary custom policy.

The generated assembly connects three catalog leaves:

1. `domain-actors-component` supplies genesis-fixed actor authority.
2. `governed-role-approvals` accepts actor-signed proposals/votes and stages the
   proposed action through `StagedActorCommandV1`.
3. `authenticated-map-component` verifies and applies the approved action.

The generated binding is small because the target owns the hard checks:

```yaml
- id: apply-approved
  from: {component: reviews, event: role-approvals.proposal-approved.v1}
  to:
    component: registry
    command: apply-action
    map:
      action: {field: action}
      approvalReference: {field: proposalId}
```

This excerpt belongs inside the generated document; its genesis configuration
must come from your descriptor. A proposal stages at most 32,768 action bytes.
At approval, the map recomputes the signed scoped action hash and verifies the
approval reference. A mismatched action rolls back the threshold vote and map
write together, preserves the pending proposal/action, and retains the crypto
work charge. Selecting `apply-basic-action` does not bypass a collection's
authorization policy.

## Know what the recipes do not supply

The initial actor leaf is genesis-fixed: it has no actor rotation or derived
governance-command route. It is not feature-equivalent to every standalone
governed preset. Existing portals and list projections that replay original
map envelopes do not automatically discover derived map writes; exact known-key
proofs are the qualified read path. Feed aggregation stays off chain and
independently recomputable, not an on-chain price oracle or Cardano publication.

Continue with the [DPP starter](../DPP_STARTER.md),
[attestation-feed guide](../ATTESTATION_FEED.md), and
[domain-role tutorial](../tutorials/05-domain-role-approvals.md) for the product
commands and policies. Before evolving the configuration, read
[operations and upgrades](05-operations-and-upgrades.md): a new generation does
not migrate genesis-bound state.

Source coverage for this exercise is
`AppChainFinalDistributionAcceptanceTest.packagedProposalAndTwoVotesCarryStateAndRejectSkippedEmptyHeights`.
`BindingRecipesIT` covers the checked-in recipes through live/offline receipt
parity; `BindingCliTest` and `BindingCliIT` cover caller-specific generation and
catalog validation. These are existing verification sources, not newly run tests.

[Next: Submit commands and inspect receipts from Java](04-java-integration.md)
