---
title: "Cardano anchoring"
description: "Anchoring periodically commits the app ledger's position — height, block hash, and state root — onto Cardano. The node builds, signs, and submits the…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/concepts-anchoring.md"
---
Anchoring periodically commits the app ledger's position — height, block hash,
and **state root** — onto Cardano. The node builds, signs, and submits the
transaction through its own mempool and tx diffusion, and confirms it through
its own L1 sync. No external API or provider is involved.

**You'll learn:** the two anchor modes, how a script-anchor advance is checked
by members and by Cardano, how to fund and configure the anchor wallet, and what
an anchor does and does not prove.

## Two modes

`yano.app-chain.anchor.mode` selects one:

| | `metadata` (default) | `script` |
|---|---|---|
| Anchor transaction | A plain tx with the anchor payload in tx metadata | A Plutus V3 thread-NFT UTxO; a validator enforces the datum chain on-chain |
| What L1 enforces | Nothing — a data-only commitment | Increasing height, unchanged ledger identity, one continuing thread output, no value drain, and m-of-n member signatures on every advance |
| Signing | Anchor wallet only | Wallet plus threshold co-signed member witnesses |
| Setup | Fund the wallet | Fund, then one-time `admin/anchor/bootstrap` per chain |
| Fees | Depend on the transaction size and current protocol parameters | Also include script execution costs |
| Maturity | Stable | Preview |

Both modes work on the devnet and on public networks. Script anchors are proven
on preprod with a real Plutus V3 mint and validator-enforced co-signed
advances.

Choose `metadata` when you want cheap public timestamping of a root that
verifiers will check against the chain's own certificates. Choose `script` when
L1 itself should refuse an advance that lacks the member threshold.

## When an anchor fires

The anchor leader checks on every tick. It anchors the current tip when there
is at least one new block and either:

- `anchor.every-blocks` blocks have accumulated since the last anchor (default
  10); or
- `anchor.max-interval-minutes` have passed since the last attempt (default 60).

After the leader starts, its first anchor fires as soon as there is anything
to anchor. Blocks are produced only when there are messages, so a quiet ledger
anchors nothing new.

## How a script anchor advances

<!-- illustration: anchor-advance -->
1. **Trigger.** The anchor leader sees enough new blocks, or enough time, since
   the last anchor.
2. **Build.** It builds a transaction that spends the thread UTxO and writes the
   next datum, then asks the members to sign.
3. **Check.** Each member compares the datum with its own block at that height
   and its own Cardano view.
4. **Co-sign.** Members that agree return a witness signed with their member
   key.
5. **Submit.** The leader submits the transaction; the anchor wallet pays the
   fees.
6. **Validate.** The on-chain validator checks the member signatures, the datum
   shape, the increasing height, the unchanged identity, and the locked value.
   It does not check that the root is correct.
7. **Confirm.** Every member reads the new thread UTxO from its own Cardano view.
<!-- /illustration -->

The datum carries eleven fields: version, chain id, chain genesis id,
application id, commitment profile id, format fingerprint, height, block hash,
state root, member keys, and threshold. The validator authorizes an advance
against the **input** datum's members and threshold, so membership changes flow
into the next datum.

## The anchor wallet

The anchor wallet is a raw **32-byte Ed25519 seed** (`anchor.signing-key`, in
hex). The node derives its enterprise address and logs it at startup
(`anchor wallet address: addr...`). Fund **that** address. Where `/status`
shows it depends on the mode:

| Mode | `anchor.address` | `anchor.walletAddress` |
|---|---|---|
| `metadata` | The anchor wallet | Not present |
| `script` | The script address once bootstrapped; the wallet before that | The anchor wallet, which keeps paying fees |

```bash
# Devnet: fund an address from the self-contained faucet
curl -X POST http://127.0.0.1:7070/api/v1/devnet/fund \
  -H 'Content-Type: application/json' \
  -d '{"address":"addr...","ada":100}'

# Generate a dedicated seed
openssl rand -hex 32
```

The local cluster launcher's `anchor-bootstrap` command funds the demo wallet
from the devnet faucet for you.

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
builds the anchor tx when it is due, pays fees and collateral from the anchor
wallet, submits through the node's own tx path, and tracks L1 confirmation.

This is a different axis from the sequencer. The proposer orders app blocks and
may rotate; anchor leadership is fixed to the `anchor.enabled` node and does not
rotate, because there is exactly one thread UTxO to spend and one wallet paying
fees. Concurrent leaders would simply race on the same UTxO.

In script mode the leader has no unilateral power:

- every advance needs `threshold` member co-signatures;
- each member verifies the proposed datum against its own block at that height
  and its own L1 view before signing, and refuses a block hash or state root
  that differs from its own; and
- the on-chain validator independently re-enforces the member threshold and
  increasing height.

The validator does **not** know the app ledger's state, so it cannot check that
a root is correct. Root correctness comes from the members who signed.

An unavailable leader can **stop anchoring** while app ledger finality
continues. Threshold checks prevent unilateral script-state advances, but a
compromised leader can also endanger its fee wallet and disrupt operations. The
app ledger keeps finalizing and `lagBlocks` climbs visibly. Recovery is
operational: enable `anchor.*` with the wallet key on another member and
restart it. The on-chain identity is persisted on L1 and members adopt it from
sign requests, so the new leader resumes where the old one stopped.

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
      every-blocks: 10                                 # anchor cadence in blocks
      max-interval-minutes: 60                         # or after this long
```

Keep the anchor seed separate from member signing keys, API keys, and effect
credentials. These credentials serve different roles and should have separate
access controls.

## What an anchor proves — and what it does not

An anchor binds a certified app ledger root to public L1 history at a
Cardano-observable time. That gives an auditor an independent reference point
they can check without asking any app ledger node.

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

On the local devnet, with no wallet or test ADA of your own:

```bash
export YANO_CLUSTER_DIR=/tmp/yano-anchor-try
./yano.sh appchain cluster start 3 --anchor-mode script --anchor-every 2
./yano.sh appchain cluster anchor-bootstrap orders-chain
./yano.sh appchain cluster submit orders-chain audit '{"event":"anchored","id":"A-1"}'
./yano.sh appchain cluster submit orders-chain audit '{"event":"anchored","id":"A-2"}'
curl -s http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/status \
  | jq '.anchor | {bootstrapped, scriptAddress, lastAnchoredHeight, lastAnchorTx, lagBlocks}'
```

After about 20 seconds, `bootstrapped` is `true`, `lastAnchoredHeight` is above
0, and `lastAnchorTx` names the advance transaction. Clean up with
`./yano.sh appchain cluster clean`.

[Tutorial 7 — anchors and verification](/tutorials/07-anchors-and-verification/)
walks through the same sequence and then verifies a record against the anchor,
step by step.

:::note[Public networks spend real test ADA]
Anchoring on preview or preprod submits real transactions from a funded
wallet. Use a dedicated seed, fund its address, and use non-production
credentials.
:::
