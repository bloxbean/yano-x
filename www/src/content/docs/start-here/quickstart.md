---
title: Quickstart
description: Start a three-member app chain on a self-contained devnet, submit a business event, confirm every member agrees, and pull a proof — in about ten minutes.
sidebar:
  order: 4
---

Ten minutes, three members, one agreed state root, and one verifiable proof.
No external Cardano node, no wallet, no funds, no Kafka, no plugin of your own.

This page assumes you have already
[built and unpacked the distribution](/start-here/build-from-source/). Every
command runs from the directory that contains `yano.sh`.

```bash
cd ~/yano-x/yano-x-jvm-*
./yano.sh appchain help
```

## 1. Start three members

```bash
export YANO_CLUSTER_DIR=/tmp/yano-quickstart
./yano.sh appchain cluster start 3
```

:::caution[Always set `YANO_CLUSTER_DIR` explicitly]
Without it the launcher uses its default location, which may already hold a
cluster you care about. Setting it makes this quickstart's state disposable and
keeps `stop`, `clean`, and `reset` pointed somewhere harmless.
:::

The launcher starts a self-contained Cardano devnet and a three-member app
chain:

- node 0 is the local L1 block producer and the app-chain proposer;
- nodes 1 and 2 are app-chain voting members;
- three chains come up — `orders-chain` (`ordered-log`), `registry-chain`
  (`kv-registry`), and `effects-chain` (`approvals`).

The expected HTTP ports are `7070`, `7071`, and `7072`. If those are busy the
launcher prints the range it chose instead; use that range below.

## 2. Confirm the members agree

```bash
./yano.sh appchain cluster status
```

Look for:

```text
orders-chain: AGREED (...)
registry-chain: AGREED (...)
```

`AGREED` means every member exposes the **same authenticated application
root** — not merely that three processes are alive. That distinction is the
whole point.

There is a status page per node if you prefer a UI:

- `http://127.0.0.1:7070/ui/app-chain/`
- `http://127.0.0.1:7071/ui/app-chain/`
- `http://127.0.0.1:7072/ui/app-chain/`

## 3. Submit a business event

Submit through member 1, not the proposer, so the gossip path is exercised:

```bash
./yano.sh appchain cluster submit orders-chain orders \
  '{"event":"order-created","orderId":"A-1001","quantity":4}' \
  --node 1
```

Member 1 authenticates the envelope and gossips it. The proposer orders it into
a block, a threshold of members signs that block, and all three apply the same
bytes.

Wait a couple of seconds, then look at the finalized history and agreement:

```bash
curl -s http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/blocks | jq .
./yano.sh appchain cluster status
```

The tip advances on every member and the roots stay equal.

## 4. Prove it

Submit through the public API so you capture a message id, then ask for a proof
bound to the committed root:

```bash
RESPONSE=$(curl -s -X POST \
  http://127.0.0.1:7072/api/v1/app-chain/chains/orders-chain/messages \
  -H 'Content-Type: application/json' \
  -d '{"topic":"orders","body":"{\"event\":\"packed\",\"orderId\":\"A-1001\"}"}')

MESSAGE_ID=$(echo "$RESPONSE" | jq -r .messageId)
sleep 3

curl -s -X POST \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/proof-subjects/finalized-message-v1/proof" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg id "$MESSAGE_ID" '
    {coordinates:{"message-id":$id}, view:"latest",
     claim:{claimId:"recorded",operands:{}}, includeEvidence:false}')" \
  | jq '{stateRoot:.proof.stateRoot,presence:.proof.presence,position:.fact.fields,claim:.claimResult.satisfied}'
```

Two things worth noticing:

- The proof was requested from node **0** for a message submitted to node
  **2**. Any member can serve it, and the proof is checkable without trusting
  the one that did.
- The typed subject `finalized-message-v1` resolved the public message id to
  its namespaced physical state key. You did not need to know the trie layout.

## 5. Restart without losing agreement

```bash
./yano.sh appchain cluster stop     # preserves state
./yano.sh appchain cluster start 3
./yano.sh appchain cluster status
```

Members reload their retained history, re-verify hash chains, certificates, and
re-executed state roots, and return to `AGREED`. Recovery never means trusting
a database copy.

:::caution
`stop` preserves state. `clean` is `stop` plus a wipe, and
`reset --yes` is destructive. Do not point either at a deployment you care
about.
:::

## 6. Optional: load, effects, and membership

```bash
# A bounded load test: 500 messages, 10 concurrent submitters, ~256-byte bodies.
./yano.sh appchain cluster loadtest orders-chain -n 500 -c 10 -s 256

# Spread submissions across every member's ingress.
./yano.sh appchain cluster loadtest orders-chain -n 1000 -c 20 -s 256 --spread

# Emit and externally execute one effect, with no broker or credentials.
./yano.sh appchain cluster effect demo

# Govern, start, and catch up a fourth member.
./yano.sh appchain cluster node join 3
```

The load test reports a **SUBMIT rate** (how fast a REST ingress accepts
messages) and a **FINALIZE rate** (how fast messages enter threshold-certified
blocks). The second one is the meaningful chain-throughput number. Entries
under `dropped (429 pool)` mean backpressure worked; `errors` are real failures
worth investigating.

## What you just proved

- Three independent processes finalized the same ordered history and derived
  the same authenticated root.
- A message submitted to one member was ordered, threshold-signed, and applied
  identically by all of them.
- A record can be proved against a committed root by any member, and the proof
  is verifiable without trusting the server.
- State survives a restart and is re-verified rather than re-trusted.

## Where to go next

| You want to… | Go to |
|---|---|
| Understand what just happened | [Architecture](/concepts/architecture/) |
| Store owner-controlled data and prove it | [Tutorial 2 — registry and proofs](/tutorials/02-registry-and-proofs/) |
| Pick a state machine for your own use case | [Recipe catalog](/recipes/) |
| Connect a finalized decision to an ERP or webhook | [Tutorial 6 — webhook effects](/tutorials/06-webhook-effects/) |
| Settle a root on Cardano and verify it | [Tutorial 7 — anchors and verification](/tutorials/07-anchors-and-verification/) |
| Write business rules Yano does not ship | [The plugin framework](/plugins/) |
| Turn a demo into a pilot | [Tutorial 9 — from demo to pilot](/tutorials/09-from-demo-to-pilot/) |

:::note[This is a single-host launcher]
`appchain cluster` runs every member as a process on one machine. Real keys and
a public network make it capable, but one host is not three failure domains.
Distributed deployments use generated per-machine project overlays and your
normal orchestration layer — see
[Tutorial 9](/tutorials/09-from-demo-to-pilot/).
:::
