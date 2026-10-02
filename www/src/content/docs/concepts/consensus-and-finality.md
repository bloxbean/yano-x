---
title: Consensus and finality
description: How members certify each app block in two voting phases, what happens when a leader fails, how to choose a safe threshold, and what catch-up and restart guarantee.
sidebar:
  order: 2
---

An app block is final when a threshold of members has signed COMMIT for it,
after each of them re-executed the block and got the same state root. A final
block is never rolled back. The rest of this page explains how Yano gets there.

**You'll learn:** the two-phase round, what a member checks before it votes,
how a failed leader is replaced, how to choose a threshold, and what catch-up
and restart guarantee.

**Before you start:** read [What is an app ledger?](/start-here/what-is-an-app-ledger/),
especially "Life of a message".

## Terms

| Term | Meaning |
|---|---|
| Member | A node identified by an Ed25519 key that checks and votes on every block. A ledger has at most 32 members. |
| Threshold `t` | How many member signatures each step of the round needs. |
| View | One attempt to finalize a height. Every height starts at view 0; a failed attempt moves to the next view. |
| Leader | The member that proposes the block for a height and view. |
| PREPARE, COMMIT | The two signed votes of a round. |
| PreparedQC | A threshold of PREPARE votes for one block. |
| Finality certificate | A threshold of COMMIT signatures. It makes the block final. |

## The round

Yano runs a two-phase protocol, described in Yano's
[ADR-036](https://github.com/bloxbean/yano/blob/main/adr/app-layer/036-certified-view-change-and-durable-l1-observation-delivery.md).
Step through one round on four members with a threshold of 3, then try the
"What if" scenarios.

<!-- illustration: consensus-round -->

In short:

1. **Propose.** The leader selects pending messages, applies the block itself,
   persists a prepare lock, and sends the block with its PREPARE vote.
2. **Check.** Each follower checks the block and re-executes it. It votes only
   if its own state root matches byte for byte.
3. **Prepare.** A threshold of PREPARE votes forms a PreparedQC. Any member
   that collects them can form it.
4. **Commit.** Holding a PreparedQC, members sign COMMIT.
5. **Final.** Any member that collects a threshold of COMMITs assembles the
   finality certificate, commits the block atomically, and shares the
   certificate. Every receiver verifies each signature itself.

The client does not sign anything in this round. The member that accepted a
message signs its envelope with its member key; a person's approval, when a
workflow needs one, is a signature inside the message body.

## Fail closed

A member signs only a root it computed itself, and it never prepares two
different blocks in one view. Every member checks, always:

| Check | If it fails |
|---|---|
| The block's proposer signed the proposal and is the leader for this height and view | No vote. |
| `prevHash` links to the local tip; `messagesRoot` recomputes; the consensus context matches | No vote. |
| Every message is unexpired and signed by a member at this height | No vote for the whole block. |
| Re-executing the block gives the same `stateRoot` | No vote. |
| A vote or certificate signature is from a member at that height, over this block | The vote is ignored. |
| A finality certificate has a threshold of valid, distinct member signatures | The whole certificate is rejected. |

If too few members can vote, no block becomes final. The ledger stops rather
than accept a block a quorum did not compute.

## Inside a block

The block hash covers the header. Messages are bound through `messagesRoot`,
and only the finality certificate sits outside the hash, so it can be attached
after finality.

<!-- illustration: block-anatomy -->

## Leaders and view changes

Every member computes the same leader from committed data, so no coordination
is needed:

```text
leader = sortedMembers[(initial + view) mod n]
```

| Sequencer mode | `initial` for view 0 | Use it when |
|---|---|---|
| `fixed` | The configured `sequencer.proposer`. | A clear operational owner exists. |
| `rotating` | Derived from the height's consensus context and the parent block hash. | No member should hold the ordering role permanently. |

Rotation does not follow Cardano slots, so members agree on the leader even
when their views of Cardano differ. When a round times out, members sign
TIMEOUT votes for the next view. A threshold of them forms a **new-view
certificate**, and the next member in sorted order leads. If any TIMEOUT
carried a PreparedQC, the new leader must re-propose the contents of the
highest one.

The round timeout defaults to five block intervals or 10 seconds, whichever is
longer, and doubles with each higher view up to a limit. Each new height starts
again at view 0. With a fixed sequencer, a failed proposer therefore costs one
timeout per block until it returns; with a rotating sequencer, only the heights
it would lead pay that cost. The mode affects who orders and how much time a
failure costs, not safety.

## Choosing a threshold

Three numbers describe a ledger's fault model:

- `n`: the number of members, at most 32;
- `t`: the threshold; and
- `f`: how many members may be Byzantine, that is, sign conflicting votes. It is
  the chain setting `consensus.max-byzantine-members`, default 0.

A node refuses to start a chain unless all three rules hold:

| Rule | Why |
|---|---|
| `1 ≤ t ≤ n` | The threshold is within the membership. |
| `2t − n > f` | Any two quorums share at least one honest member, so two conflicting blocks cannot both be certified. |
| `t ≤ n − f` | A quorum is still possible with `f` faulty members. |

With `f = 0`, the second rule means the threshold must be more than half the
members. The ledger keeps finalizing with up to `n − t` members offline.

<!-- illustration: quorum-calculator -->

Defaults to know:

- The host's default threshold is 1, which passes only for a single member. Set
  the threshold for any multi-member ledger.
- The cluster launcher defaults to a majority, `⌊n / 2⌋ + 1`: 2 of 3, 3 of 4,
  3 of 5.
- A 2-of-3 ledger is crash-safe but does not tolerate a Byzantine member. To
  tolerate one, use for example 3 of 4 with `f = 1`.

Choose `t` from the fault model you need, not from how many nodes are usually
online.

## Membership

Membership is selected per chain with `membership.mode`:

- **`static`** (the default): the members and threshold come from
  configuration, identical on every member.
- **`governed`**: membership and threshold changes are commands on reserved
  `~governance/` topics. A change is accepted once a threshold of current
  members has submitted the identical command. It is recorded in the chain's
  own history as a membership epoch with the height it applies from.

Every check uses the membership at the block's height, so old blocks verify
against the members of their time. The stock cluster configuration uses
governed membership.

## Catch-up and restart

A member that is behind asks one connected peer every 5 seconds for the next
50 blocks after its tip. It verifies each block with the checks of a live
round, except message expiry: hash links, `messagesRoot`, the leader for the
height and view, message signatures, the full finality certificate, and a
re-executed state root. A member that is behind but makes no progress for a
minute reports `stalled` in its status.

Blocks, certificates, state, prepare locks, PreparedQCs, and membership epochs
are persisted. The pending message pool is held in memory and is
lost on restart, so a message that no other member received must be submitted
again. On startup, a member checks that its committed root matches the tip
block and that the tip block carries a valid threshold certificate.

## What "AGREED" means

```bash
./yano.sh appchain cluster status
```

```text
Consistency (per chain, roots must match across nodes):
  orders-chain: AGREED (...)
```

`cluster status` reads each running node's current state root for each chain.
`AGREED` means the roots are equal; `MISMATCH` means at least one differs. It
compares roots only, not heights, and reads each node once, so a node that is
one block behind during traffic shows `MISMATCH`. Run it again when traffic
stops. A mismatch that persists usually means one of:

- a consensus-affecting configuration value differs between members;
- a different plugin bundle version is installed on one member; or
- a custom state machine is not deterministic. See
  [Determinism rules](/concepts/determinism-rules/).

## Chain identity

Three values pin the identity of a chain's state:

```text
(commitment-profile, format-fingerprint, genesis-id)
```

A retained `genesis-id` is never regenerated. In addition, every vote signs a
per-height consensus context that binds the chain, genesis, membership,
threshold, and consensus profiles. Changing consensus semantics is therefore a
versioned upgrade, not a rolling code change. See
[Consensus rules for plugins](/plugins/consensus-rules/).

## Deeper reading

- [Yano consensus and internals guide](https://github.com/bloxbean/yano/blob/main/docs/APP_CHAIN_CONSENSUS_GUIDE.md):
  the round check by check, vote locks, rotation, catch-up, and restart.
- [ADR-036](https://github.com/bloxbean/yano/blob/main/adr/app-layer/036-certified-view-change-and-durable-l1-observation-delivery.md):
  the safety and liveness model.
- [Tutorial 1](/tutorials/01-first-app-chain/): watch agreement on a running
  three-member cluster.

**Next:** [State and proofs](/concepts/state-and-proofs/).
