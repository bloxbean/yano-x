---
title: "Where data lives"
description: "A Yano node keeps three separate stores. Two are authoritative and one is derived. Knowing which is which tells you what to back up, what you can delete…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/concepts-where-data-lives.md"
---
A Yano node keeps three separate stores. Two are authoritative and one is
derived. Knowing which is which tells you what to back up, what you can delete,
and what a proof covers.

- **You'll learn:** what each store holds, which data the state root commits
  to, and the rules for read indexes.
- **Before you start:** [State and proofs](/concepts/state-and-proofs/).

<!-- illustration: where-data-lives -->

## Three sibling stores

| Store | Holds | Authoritative? | Set by |
|---|---|---|---|
| `chainstate/` | The node's Cardano data | Yes, for Cardano | `yano.storage.path` |
| `appchain-chainstate/` | Every hosted app ledger, one database per chain under `<path>/<chain-id>/` | Yes, for the app ledger | `yano.app-chain.storage.path` |
| `appchain-indexers/` | Local read indexes derived from finalized blocks | No: rebuildable | The indexer's own setting |

The launchers place all three side by side in each node's directory, for
example `node0/chainstate`, `node0/appchain-chainstate`, and
`node0/appchain-indexers`. App ledger data never goes below `chainstate/`, and
derived indexes never go into either authoritative store.

## Inside an app ledger's store

One ledger's database holds two kinds of data, written together in one atomic
batch per block, so a crash mid-block leaves the previous height intact.

**Under the state root.** Every key the state machine writes, plus records the
framework authenticates: the [height-1 identity markers](/concepts/chain-identity/)
and, on new ledgers by default, a record of each block's messages
(`[height, messagesRoot, messageCount]`). `ordered-log` also writes a record for
every finalized message. These are what members re-execute and compare, what the
state root commits to, and what proofs cover.

**Outside the state root.** Blocks with their finality certificates, tip
metadata, the message, topic, and sender indexes, per-sender sequence floors,
vote locks, and membership epochs. They are authoritative and persist across
restarts, and blocks are checked through their certificates and hash links, but
an index entry is not provable on its own. To prove something, prove a record
that is under the root.

## Read indexes

Read indexes, such as the eUTxO lifecycle index, make queries convenient. They
follow three rules:

- **Never an authority.** Nothing in consensus, state roots, finality, or proof
  verification reads them.
- **Never ahead.** An index never advances beyond the authoritative ledger's
  tip. The eUTxO indexer reports itself failed with "index checkpoint is ahead
  of authoritative app-chain tip" if it finds that it has.
- **Rebuildable.** Delete the index and restart the node, and it rebuilds from
  the retained finalized blocks. If old blocks were pruned, it does not invent
  the missing rows.

Never restore `appchain-indexers/` as if it were authoritative state, and never
restore it into either authoritative store. The Verifiable Explorer's index
follows the same rules from outside the node: it is a separate database that any
node can rebuild.

## Stopping, cleaning, and backing up

- `./yano.sh appchain cluster stop` stops the nodes and keeps all three stores;
  `clean` stops them and deletes the whole cluster directory.
- `./showcase.sh stop --instance <name>` keeps the instance;
  `./showcase.sh reset --yes --instance <name>` deletes it and cannot be undone.
- Back up the authoritative stores, never the read indexes in their place. For
  an app ledger, a [snapshot](/concepts/recovery/) gives you a signed,
  verifiable copy.

Retained data carries the ledger's identity. Do not regenerate the genesis id or
mix stores from different ledgers.

Next: [Restart, catch-up and snapshots](/concepts/recovery/).
