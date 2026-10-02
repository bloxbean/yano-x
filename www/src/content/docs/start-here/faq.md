---
title: "FAQ"
description: "Short answers to the questions people ask most, with links to the full explanation."
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/start-here-faq.md"
---
Short answers to the questions people ask most, with links to the full
explanation.

## The basics

**Is an app ledger the same as an app chain?**
Yes. These guides say *app ledger*; Yano's command line, configuration, and APIs
say *app chain* (`./yano.sh appchain`, `yano.app-chain.*`, `/api/v1/app-chain/`).
See [What is an app ledger?](/start-here/what-is-an-app-ledger/)

**Is it a public blockchain?**
No. Membership is a configured list of member keys. There is no stake, no
slashing, and no open participation. The ledger replicates only to its members;
outsiders get proofs and, optionally, roots anchored on Cardano.

**Does every block wait for Cardano?**
No. Finality comes from the members' threshold signatures. Anchoring a root on
Cardano is optional and happens later. See [Cardano anchoring](/concepts/anchoring/).

**Why are no blocks being produced?**
A ledger produces a block only when there are pending messages. An idle ledger
stays at the same height.

## Messages and results

**My submission returned 202. Did it succeed?**
Not yet. 202 means one member accepted the message into its pending pool. Wait
until the message is final, then read the application's result. See the
[life of a message](/start-here/what-is-an-app-ledger/#life-of-a-message).

**The message is final. Did my command succeed?**
Not necessarily. A command that breaks a business rule when the block runs is
recorded as a deterministic no-op on every member, and its message id is used up.
Read the result, and submit a new message to retry.

**Do my applications sign messages?**
No. The member that accepts a message signs its envelope with the member key.
When a person or organization must approve something, their actor signature
travels inside the message body. See [Keys and trust](/concepts/trust-model/).

**How large can a message be?**
64 KiB by default (`max-message-bytes`). Store large content elsewhere and
record its hash.

**Do effects run inside consensus?**
No. A transition emits an effect record; an executor performs the external
action after finality, outside consensus, at least once. Receivers should use the
effect id to ignore duplicates. See [Effects](/concepts/effects/).

## Faults and recovery

**How many members can fail?**
With the default fault bound of 0, up to `n − t` members can be offline, where
`t` is the threshold, but no member is assumed to lie. A threshold of 2 of 3
survives one member being down, not one dishonest member. See
[Keys and trust](/concepts/trust-model/#how-many-members-can-fail).

**What happens if the leader is down?**
The round times out, a threshold of members certifies the timeout, and the next
member in order leads. No configuration change is needed.

**What happens when a member restarts or falls behind?**
It keeps its blocks, state, and vote locks, loses its pending message pool, and
catches up by verifying every missed block and certificate itself. See
[Restart, catch-up and snapshots](/concepts/recovery/).

**Can I delete `appchain-indexers/`?**
Yes. It holds rebuildable read indexes, which rebuild from finalized blocks.
Never restore it as authoritative state, and never delete `chainstate/` or
`appchain-chainstate/` to fix an error. See [Where data lives](/concepts/where-data-lives/).

## Identity and proofs

**Can I change the commitment profile or genesis id later?**
No. They are fixed when the ledger is created, together with the format
fingerprint. Changing them means a new ledger. See
[Chain identity](/concepts/chain-identity/).

**MPF or JMT?**
MPF, `mpf-blake2b256-v1`, unless you have a reason. Only MPF proofs can be
checked on Cardano. The choice is made at genesis.

**Does an exclusion proof show that something never happened?**
No. It shows that a key was absent from this dataset under this root. A pruned
height is unavailable, which is different from absent. See
[State and proofs](/concepts/state-and-proofs/).

**A verifier said `INTERNAL_CONSISTENCY_ONLY`. Is that bad?**
It means the proof agrees with itself and nothing more. Pin the members or check
an anchor to reach a level you can rely on. See
[Keys and trust](/concepts/trust-model/#trust-levels).

## Operating

**Are plugins sandboxed? Does the node check their signatures?**
No, and no. Plugins are trusted in-process code, and publisher signatures are
checked by Yano X tooling, not by the node at load time. Install only plugins you
trust. See [Keys and trust](/concepts/trust-model/#plugins-are-trusted-code).

**`cluster status` says MISMATCH. Is the ledger broken?**
Not necessarily. It compares the current state roots the members report, without
aligning heights, so a member that is one block behind shows MISMATCH. Run it
again. See [Troubleshooting](/deployment/troubleshooting/).

**Can I run the showcase in production?**
No. The showcase runs every member under one operator with demonstration keys.
Yano is pre-release; see [Production deployment](/deployment/production/) for
what changes.
