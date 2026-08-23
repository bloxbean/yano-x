---
title: Consensus and finality
description: How a submitted message becomes a threshold-certified app block — sequencing modes, the fail-closed verification chain, membership governance, and catch-up.
sidebar:
  order: 2
---

An app block is final when the configured member threshold has signed the state
root each of those members computed **themselves**. Everything else on this
page is detail around that sentence.

## The round

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Ingress as Any member (ingress)
    participant Proposer
    participant Members
    Client->>Ingress: Signed app message
    Ingress->>Ingress: Verify envelope signature and membership
    Ingress->>Members: Gossip into the pending pool
    Proposer->>Members: Propose block (ordered messages + post-state root)
    Members->>Members: Re-execute apply, derive own root
    Members->>Members: Compare byte-for-byte with the proposed root
    Members-->>Proposer: Ed25519 vote, only on their own root
    Proposer->>Members: Finality certificate (threshold signatures)
    Members->>Members: Commit; tip advances
```

Any member can be the ingress point. Submitting through a non-proposer is the
normal path and exercises gossip — that is why the
[Quickstart](/start-here/quickstart/) submits through node 1.

## Fail closed

Every node verifies, always:

| Check | Failure behavior |
|---|---|
| Envelope signature | The message is dropped. |
| Sender is a current member | The message is dropped. |
| The block came from the current sequencer | The block is never finalized. |
| Re-executed post-state root matches the proposed root | The vote is withheld. |
| Vote signatures are valid member signatures | Invalid votes are discarded. |
| Certificate meets the threshold | The block is not final. |
| Hash link to the previous block | The block is rejected. |

A member signs only a root it derived itself. The proposer orders; it never
decides state.

## Sequencing modes

| Mode | How the proposer is chosen | Use it when |
|---|---|---|
| `fixed` | One configured member proposes every block. | A clear operational owner exists; simplest to reason about. |
| `rotating` | The proposer changes deterministically over L1-slot windows. | No participant should hold the ordering role permanently. |

Rotation is derived from the Cardano L1 slot, so every member computes the same
schedule with no extra coordination. The choice does not affect safety — only
who holds the ordering role and therefore liveness responsibility.

## Threshold and membership

- Members are identified by Ed25519 public keys. The v1 profile supports at
  most **32** members.
- `threshold` is how many member signatures a finality certificate needs.
- The initially supplied members form the **immutable bootstrap epoch**.
- Later membership changes are themselves finalized in the chain's history, so
  the member set at any height is derivable from the chain rather than from a
  configuration file.

Governed membership means adding a member is a chain event, not an edit:

```bash
# High level: derive or load the identity, collect approvals, record the epoch,
# start the node, catch up, and verify tip and root parity.
./yano.sh appchain cluster node join 3

# Lower level, for an externally managed node.
./yano.sh appchain cluster member add <64-hex-ed25519-public-key>
```

`member add` records the member across configured chains but does not create
configuration, copy state, or start a process. The operator must configure that
node with the same immutable bootstrap members, chain definitions, threshold,
and network identity; its verified catch-up then derives the later governed
epoch.

:::caution[One pending epoch at a time]
Do not schedule a second membership or threshold epoch while the first is still
delayed — the later epoch could replace the pending member set. Advance
ordinary application traffic to the printed activation height first.
:::

## Catch-up and restart

A member that joins late or restarts behind fetches finalized blocks from peers
and verifies everything before committing: the hash chain, the finality
certificates, and the re-executed state roots. Recovery never means trusting a
database copy.

This is why `stop` followed by `start` returns the cluster to `AGREED` rather
than to "probably fine".

## Chain identity

Three values together pin a chain's identity:

```text
(commitment-profile, format-fingerprint, genesis-id)
```

A retained `genesis-id` is never regenerated. Changing a commitment profile or
the deterministic state encoding produces a **different chain**, not an
upgraded one — which is why any change to consensus semantics is a versioned
upgrade rather than a rolling code change. See
[Consensus rules for plugins](/plugins/consensus-rules/).

## What "AGREED" means

```bash
./yano.sh appchain cluster status
```

```text
orders-chain: AGREED (...)
```

`AGREED` means every member exposes the same authenticated application root at
the same height. It does not merely mean the processes are running. If members
disagree, the cause is almost always one of:

- a consensus-affecting configuration value that differs between members
  (effects caps, state-machine id, composite profile digest);
- a different plugin bundle version installed on one member; or
- non-deterministic code in a custom state machine — see
  [Determinism rules](/concepts/determinism-rules/).

## Deeper reading

- [`core-host.md`](https://github.com/bloxbean/yano-x/blob/main/docs/core-host.md)
  — the consensus round check by check, vote locks, rotation math, and
  catch-up/restart semantics.
- [Tutorial 1](/tutorials/01-first-app-chain/) — observe agreement on a running
  three-member cluster.
