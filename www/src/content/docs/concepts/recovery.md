---
title: "Restart, catch-up and snapshots"
description: "A member that restarts, falls behind, or joins from a snapshot never trusts the data it is given. It re-checks every block and certificate, and it…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/concepts-recovery.md"
---
A member that restarts, falls behind, or joins from a snapshot never trusts the
data it is given. It re-checks every block and certificate, and it re-executes
every block it applies.

- **You'll learn:** how catch-up works, what a restart keeps and loses, and how
  a snapshot restore is verified.
- **Before you start:** [Consensus and finality](/concepts/consensus-and-finality/)
  and [Where data lives](/concepts/where-data-lives/).

## Catch-up

Gossip never re-delivers a missed proposal, so a member that falls behind cannot
recover from live rounds. Instead, every 5 seconds it asks one connected peer
for the next range of up to 50 blocks and checks each one in order:

<!-- illustration: recovery-catch-up -->
1. **Fall behind.** A member is offline while the others finalize blocks.
2. **Request a batch.** Every 5 seconds it asks one peer for blocks tip + 1 to
   tip + 50.
3. **Check each block.** The block must extend the local tip, come from the
   right leader, and carry a matching messages root, consensus context,
   Cardano reference, and member signatures.
4. **Verify the certificate.** The finality certificate must carry a threshold
   of valid member signatures at that height.
5. **Re-execute.** The member applies the block and requires a byte-identical
   state root, then commits it.
6. **Rejoin consensus.** At the peers' tip, it votes in live rounds again.
<!-- /illustration -->

A failed check rejects the block and stops the batch; nothing from it is
applied. A Cardano reference ahead of the member's own view of Cardano pauses
the batch instead, until its view catches up. Message expiry is not checked
again, because those messages were finalized before they expired.

If a peer stays ahead and the member makes no progress for 60 seconds, the node
logs that the chain appears stalled, raises `AppChainStalledEvent`, and reports
`stalled` in its status. A member stalls this way when it cannot apply the
certified blocks, for example because its state machine computes a different
root. See [troubleshooting](/deployment/troubleshooting/).

## Restart

Everything a member needs to resume is written with each block, in one atomic
batch:

| Kept across a restart | Lost by design |
|---|---|
| Blocks and their finality certificates, tip metadata | The pending message pool |
| The state trie and state root | Gossip deduplication and counters |
| Message and query indexes, per-sender sequence floors | Pending anchor rounds |
| Prepare locks, prepared certificates and their proposals | Active round aggregation, rebuilt from persisted votes |
| Membership epochs and pending governance | |

A message that was accepted (`202`) but never reached another member is lost
if its member restarts first. Submit it again after checking that it was not
finalized.

At startup the member re-verifies rather than trusts:

1. If it is restoring a snapshot, the snapshot manifest's signature and file
   hashes, before the database is opened.
2. The committed state root equals the root recorded in the tip block.
3. Persisted membership epochs override the static configuration.
4. The tip block re-hashes to the stored hash, and its certificate carries a
   threshold of valid member signatures at that height.
5. The member's own sender sequence floor is restored, so it never reuses a
   finalized sequence number.

Prepare locks survive a restart, so a member never votes for two different
blocks in one view, even across a crash.

## Snapshots

A snapshot is a cheap copy of one ledger's database, taken with hard links,
plus a manifest signed by the member that took it. The manifest binds the chain
id, the tip height and block hash, the state root, the state identity, a hash of
the membership epochs, and a SHA-256 of every file.

```bash
./yano.sh appchain state snapshot --url http://127.0.0.1:7070/api/v1 --chain orders-chain \
  --path /var/lib/yano/snapshots/orders-chain-1   # a fresh directory on the node
```

To restore, copy the snapshot directory to `<yano.app-chain.storage.path>/<chain-id>/`
on the new or recovering member and start it. The node then refuses to start
unless:

- the manifest was signed by a configured member, and every file hash matches,
  checked before the database is opened;
- the restored tip height, block hash, state root, state identity, and
  membership epochs match the manifest; and
- the tip block's certificate carries a threshold of valid member signatures. A
  snapshot whose contents merely agree with themselves is rejected.

A restore does not replay history: it trusts the certified tip and the signed
manifest, then catches up the blocks after the snapshot height with full
verification. For onboarding a new member, this saves catching up from height 1.

Snapshots of a ledger's database are different from *authenticated snapshots*,
which are period datasets, such as epoch stake, proved under their own roots.

Next: [Keys and trust](/concepts/trust-model/).
