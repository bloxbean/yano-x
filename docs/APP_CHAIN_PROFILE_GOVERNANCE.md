# Composite profile governance

This runbook is for operators evolving the deterministic composite state machine
of a long-lived app ledger. The short rule is:

> Deploy the dormant target code everywhere first; authorize one exact profile
> and activation height on the ledger second.

Profile governance changes deterministic application composition. It is not a
plugin hot-reload mechanism and it does not dynamically assemble components
from YAML. A reviewed composite bundle owns a bounded catalog of executable
profiles. Governance can select only an exact canonical profile already in that
catalog.

## The lifecycle

A proposal moves through three statuses, `STAGING`, `SEALED`, and `SCHEDULED`.
Activation and voiding are events: when either happens, the proposal record is
deleted. Only one proposal can be open at a time.

<!-- illustration: profile-governance -->
1. **Deploy the target bundle.** Every member deploys the same bundle, whose
   catalog holds the active profile and the dormant target profile.
2. **Begin.** The author submits `BEGIN` with the active profile's digest, the
   membership digest, the target digest, the activation height `H`, and an
   expiry. The proposal is `STAGING`.
3. **Stage the chunks.** The author stages the target profile's canonical
   bytes, chunk by chunk.
4. **Seal.** The author seals the proposal; the staged bytes and the transition
   are checked, and the proposal is `SEALED` with a proposal hash.
5. **Approve.** A threshold of members approves the proposal hash.
6. **Attest readiness.** Every member attests that the target digest is in its
   local executable catalog.
7. **Scheduled.** With threshold approvals and readiness from every member
   before `H`, the proposal is `SCHEDULED`.
8. **Activate at H.** Block `H` runs the target profile; a new profile epoch is
   recorded and the proposal is deleted.
<!-- /illustration -->

## Start a governed chain

Governed mode is a consensus-affecting choice for a new chain:

```properties
yano.app-chain.chains[0].state-machine=composite
yano.app-chain.chains[0].machines.composite.preset=evidence-v1-gated
yano.app-chain.chains[0].membership.mode=governed
yano.app-chain.chains[0].machines.composite.profile-mode=governed
yano.app-chain.chains[0].machines.composite.profile-governance.min-activation-lag=20
yano.app-chain.chains[0].machines.composite.profile-governance.proposal-ttl-blocks=600
yano.app-chain.chains[0].machines.composite.profile-governance.max-epochs=1024
```

Do not turn a retained `fixed` chain into `governed` by editing this setting.
The authenticated governance configuration and epoch-0 marker make that fail
closed. Start a new chain or, while Yano remains preview and history is
disposable, reset that chain's app-state on every member.

The stock bundle currently contains one executable preset per selected stock
composition. It exercises governed genesis, proofs, status, packaging, and
operations, but it cannot authorize a profile that is not in its catalog. A
domain bundle enables real evolution by packaging the current and dormant
target `CompositeProfileCatalog.Entry` values together.

One v1 bundle may contain 1-64 distinct canonical profiles. `max-epochs` bounds
the total authenticated epoch history, including transitions back to an already
packaged profile; it does not raise that 64-profile catalog bound. Keep every
historical profile in the catalog. V1 has no authenticated replay floor that
would make removing old executable code safe.

## Prepare the target bundle

Before proposing anything:

1. Build a composite provider whose catalog contains both the active profile
   and the target profile, including every component/workflow generation
   present in retained epoch history or needed for late effect results.
2. Run replay, restart, snapshot, effect-result, quota-drain, and profile
   golden-vector tests for that catalog.
3. Export the exact `CompositeProfile.canonicalBytes()` for the target as a
   binary review artifact. Its domain-separated digest is the proposal target.
4. Deploy the same manifested bundle to every JVM member.
5. Rolling-restart one member at a time. A member without the target may keep
   following the old profile, but it cannot honestly attest readiness.

Plugin JARs are deployed through the documented JVM plugin-directory flow.
Yano X does not publish a native composite extension.

## Inspect and encode commands

The distribution includes a dependency-free operator helper,
`appchain-cluster/profile-governance.py`. It encodes each governance command as
canonical CBOR:

```bash
cd appchain-cluster

./profile-governance.py --chain evidence-chain begin --encode-only \
  --proposal-id "$PROPOSAL_ID" \
  --base-digest "$ACTIVE_PROFILE_DIGEST" \
  --membership-digest "$MEMBERSHIP_EPOCH_DIGEST" \
  --profile target-profile.cbor \
  --activation-height 1200 --expiry-height 1500 > begin.json
```

The output contains `targetProfileDigest`, `chunkCount`, `proposalHash`, and
`bodyHex`. Read the active profile digest and `currentMembershipDigest` from the
chain status. Always verify that the same membership epoch remains effective
through the planned activation height.

> **Known issue.** The helper's online mode, which runs without
> `--encode-only` (`status`, submit, and `--dry-run`), calls
> `/api/v1/app-chain/chains/{chain}/profile-governance` routes that the node
> does not serve. Use `--encode-only`, then send the encoded bytes to the
> composite plugin's route, as shown below.

The composite plugin serves governance under
`/api/v1/plugins/org.yanoproject.x.composite/chains/{chain}/profile-governance`:
a `GET` returns the governance status as CBOR, and a `POST` to `…/commands`
takes one command as a raw CBOR body. The `POST` is a privileged route, so it
needs the member's full API key, and `?dry-run=true` runs local validation
without submitting. The member's own node submits the command with its member
key:

```bash
NODE=https://member-a.example:7070
ROUTE=/api/v1/plugins/org.yanoproject.x.composite/chains/evidence-chain/profile-governance/commands

jq -r .bodyHex begin.json | xxd -r -p > begin.cbor
curl -sS -X POST -H "X-API-Key: $(cat /secure/member-a.key)" \
  -H 'Content-Type: application/octet-stream' --data-binary @begin.cbor \
  "$NODE$ROUTE?dry-run=true"          # {"validated":true}
curl -sS -X POST -H "X-API-Key: $(cat /secure/member-a.key)" \
  -H 'Content-Type: application/octet-stream' --data-binary @begin.cbor \
  "$NODE$ROUTE"                       # {"messageId":"…"}
```

Keep the key in an owner-only file, never on the command line or in shell
history. Use HTTPS for every non-loopback member endpoint and terminate it only
at an operator-trusted proxy.

Stage each reported chunk and seal the proposal, waiting for each command to
finalize before the next step:

```bash
./profile-governance.py --chain evidence-chain chunk --encode-only \
  --proposal-id "$PROPOSAL_ID" --profile target-profile.cbor --index 0

./profile-governance.py --chain evidence-chain seal --encode-only \
  --proposal-id "$PROPOSAL_ID"
```

Repeat `chunk` for indices `0 .. chunkCount-1`. Conflicting or duplicate
non-identical chunks void the proposal; identical replay is a no-op.

## Approve, attest readiness, and activate

Approval and readiness are deliberately separate:

- threshold member approvals authorize the exact intent;
- every member in the bound membership epoch must attest that the exact target
  digest exists in its local executable catalog.

Each approving member encodes its commands and submits them through its own
node, as above, so its own member key signs them:

```bash
./profile-governance.py --chain evidence-chain approve --encode-only \
  --proposal-hash "$PROPOSAL_HASH"

./profile-governance.py --chain evidence-chain ready --encode-only \
  --proposal-hash "$PROPOSAL_HASH" --target-digest "$TARGET_DIGEST"
```

A dry run of `READY` is the final local diagnostic. It fails when that node's
catalog lacks the target. Once the threshold and all-member readiness are
finalized, status becomes `SCHEDULED`. Every block before `H` uses the old
profile; block `H` uses the target for results, expiries, workflows, and normal
messages. There is no local break-glass override.

Cancel before activation when necessary:

```bash
./profile-governance.py --chain evidence-chain cancel --encode-only \
  --proposal-hash "$PROPOSAL_HASH"
```

Threshold cancellations void a proposal. It is also voided by expiry, by a
change of the bound membership epoch, by malformed or conflicting staging, by a
seal whose bytes or digest do not match, by invalid profile compatibility or
quota rules, and by approvals and readiness that complete only at or after `H`.
A `BEGIN` whose base digest is not the active profile is ignored rather than
recorded.

## Observe and verify

The chain status/UI projects the active epoch, digest, proposal state,
activation height, approvals, readiness, local readiness, and retired drains.
Prometheus exports:

- `yano.appchain.composite.profile.epoch`;
- `yano.appchain.composite.governance.proposal.state` (`0` none, `1` staging,
  `2` sealed, `3` scheduled);
- `yano.appchain.composite.governance.approvals`;
- `yano.appchain.composite.governance.readiness`;
- `yano.appchain.composite.governance.local.ready`; and
- `yano.appchain.composite.governance.retired.drains`.

Health reports `{chain}.scheduledProfileMissing=true` and goes down if a node
has a scheduled target absent from its local catalog. This is a last-resort
alarm, not permission to schedule before all members are ready.

Proof clients have two policies:

- strict clients verify an MPF proof for the active marker at a trusted
  finalized root, then pin its expected digest; and
- governance-aware clients verify finality and MPF inclusion for the current
  epoch pointer, every retained epoch record, and the active marker at the same
  root. They then check byte linkage with
  `CompositeProfileEpochChainVerifier.verifyStructure(...)` and independently
  enforce their membership/approval policy from finalized block history.

The dependency-free structural verifier does not fetch proofs or prove member
authorization; it only checks the already-proven bytes supplied by the caller.
For a complete offline trust-boundary check, use
`yano-x-composite-client`'s `GovernedCompositeVerifier`: it first verifies
portable block finality against caller-pinned membership, then verifies the
pointer, every epoch, and the marker as MPF inclusions at that exact root,
checks canonical linkage, and finally invokes the caller's mandatory
membership/approval-history policy. Applications with a fully reviewed
proposal schedule can use `requirePinnedProposalHashes(...)`; it fails unless
every non-genesis epoch has exactly the expected proposal hash, with no missing
or extra epoch. Neither policy makes unreviewed component code trustworthy.

## Recovery rules

- Missing bundle before readiness: deploy it and retry local dry-run/`READY`.
- Membership changes before activation: the proposal is void; repropose against
  the new membership epoch.
- Missed activation after falsely claiming readiness: restore the exact bundle
  and catch up from finalized history; do not edit profile state.
- Bad profile already activated: authorize a corrected future epoch. Never
  rewrite the marker, epoch records, or local database to create a hidden fork.
- Lost governance threshold: use the membership-governance recovery policy
  first; profile governance cannot bypass membership authority.

The complete consensus contract is recorded in the repository's ADR-015.
