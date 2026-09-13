---
title: "Cardano anchoring"
description: "Anchoring periodically commits the app chain's position — height, block hash, and state root — onto Cardano. The node builds, signs, and submits the…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/concepts-anchoring.md"
---
Anchoring periodically commits the app chain's position — height, block hash,
and **state root** — onto Cardano. The node builds, signs, and submits the
transaction through its own mempool and tx diffusion, and confirms it through
its own L1 sync. No external API or provider is involved.

## Two modes

`yano.app-chain.anchor.mode` selects one:

| | `metadata` (default) | `script` |
|---|---|---|
| Anchor transaction | A plain tx with the anchor payload in tx metadata | A Plutus V3 thread-NFT UTxO; a validator enforces the datum chain on-chain |
| What L1 enforces | Nothing — a data-only commitment | Monotonic height, stable chain id, and m-of-n member signatures on every advance |
| Signing | Anchor wallet only | Wallet plus threshold co-signed member witnesses |
| Setup | Fund the wallet | Fund, then one-time `admin/anchor/bootstrap` per chain |
| Fees | Depend on the transaction size and current protocol parameters | Also include script execution costs |

Both modes work on the devnet and on public networks. Script anchors are proven
on preprod with a real Plutus V3 mint and validator-enforced co-signed
advances.

Choose `metadata` when you want cheap public timestamping of a root that
verifiers will check against the chain's own certificates. Choose `script` when
L1 itself should refuse an advance that lacks the member threshold.

## The anchor wallet

The anchor wallet is a raw **32-byte Ed25519 seed** (`anchor.signing-key`, in
hex). The node derives its enterprise address, logs it at startup
(`anchor wallet address: addr...`), and exposes it in `/status` under
`anchor.walletAddress`. Fund **that** address.

```bash
# Devnet
curl -X POST http://127.0.0.1:7070/api/v1/devnet/fund \
  -H 'Content-Type: application/json' \
  -d '{"address":"addr...","ada":100}'

# Generate a dedicated seed
openssl rand -hex 32
```

:::caution[A wallet mnemonic will not work]
A CIP-1852 mnemonic from Eternl, Lace, or the devkit **cannot** be converted
into this seed — HD payment keys are extended keys, not seeds. Generate a
dedicated seed and fund its address.

Treat it as a hot wallet holding fee money only. The key sits in configuration
on the node box.
:::

Only the anchor **leader** needs `anchor.*` configuration — typically node 0.
In script mode, members co-sign and adopt the on-chain identity with **zero**
anchor configuration; they verify each advance against their own ledger and L1
view before signing.

## The anchor leader is not a trust point

The leader is the node that drives anchoring: it watches finalized progress,
builds the anchor tx once `every-blocks` accumulate, pays fees and collateral
from the anchor wallet, submits through the node's own tx path, and tracks L1
confirmation.

This is a different axis from the sequencer. The proposer orders app blocks and
may rotate; anchor leadership is fixed to the `anchor.enabled` node and does not
rotate, because there is exactly one thread UTxO to spend and one wallet paying
fees. Concurrent leaders would simply race on the same UTxO.

In script mode the leader has no unilateral power:

- every advance needs `threshold` member co-signatures;
- each member verifies the proposed range against its own ledger and L1 view
  before signing; and
- the on-chain validator independently re-enforces the member threshold and
  monotonic height.

An unavailable leader can **stop anchoring** while app-chain finality
continues. Threshold checks prevent unilateral script-state advances, but a
compromised leader can also endanger its fee wallet and disrupt operations. The app chain keeps finalizing and
`lagBlocks` climbs visibly. Recovery is operational: enable `anchor.*` with the
wallet key on another member and restart it. The on-chain identity is persisted
on L1 and members adopt it from sign requests, so the new leader resumes where
the old one stopped.

Metadata mode is the same minus co-signing. Its commitment is data-only and the
leader alone signs, so the trust statement is correspondingly weaker.

## Configuration

```yaml
yano:
  app-chain:
    anchor:
      enabled: true
      mode: metadata                                   # or: script
      signing-key: "<anchor wallet Ed25519 seed hex>"  # separate from member keys
      every-blocks: 10                                 # anchor cadence
```

Keep the anchor seed separate from member signing keys, API keys, and effect
credentials. These credentials serve different roles and should have separate access controls.

## What an anchor proves — and what it does not

An anchor binds a certified app-chain root to public L1 history at a
Cardano-observable time. That gives an auditor an independent reference point
they can check without asking any app-chain node.

It does **not** prove:

- that the application's business claim is true — only that the members
  committed to this exact state;
- that the data behind a hash is still retrievable — durable availability is a
  separate property;
- anything at all about heights after the anchored one.

A node-reported anchor is labelled `NODE_CONFIRMED_L1_REFERENCE` in proof
output. That is the node's claim about L1, not independently verified L1 truth.
An independent verifier should check the Cardano output itself. See
[State and proofs](/concepts/state-and-proofs/).

## Try it

```bash
export YANO_CLUSTER_API_KEY="$(openssl rand -hex 32)"
./yano.sh appchain cluster start 3 --network preprod
./yano.sh appchain cluster anchor-bootstrap
./yano.sh appchain cluster status
```

[Tutorial 7 — anchors and verification](/tutorials/07-anchors-and-verification/)
walks through bootstrapping a threshold-enforced script anchor, advancing it,
and following the independent verification chain end to end.

:::note[Preprod spends real test ADA]
Anchoring on a public test network submits real transactions from a funded
wallet. Use disposable keys and non-production credentials.
:::
