---
title: "State and proofs"
description: "Application state lives in an authenticated tree. Every final block produces one state root that commits to all of it, identical on every member. A client…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/concepts-state-and-proofs.md"
---
Application state lives in an authenticated tree. Every final block produces
one state root that commits to all of it, identical on every member. A client
can check a single record against that root without trusting the node that
served it.

**You'll learn:** what a state root commits to, what a proof contains, how to
read a proof result precisely, and how much a verification actually proves.

**Before you start:** read [Consensus and finality](/concepts/consensus-and-finality/).

## The state root

Every write a state machine makes is an entry in one authenticated tree. The
tree's type is chosen when the ledger is created and never changes:

| Profile | Tree | Proofs verify |
|---|---|---|
| `mpf-blake2b256-v1` | Merkle Patricia Forestry, the same construction as Aiken's `merkle-patricia-forestry` | Off-chain, and inside a Cardano validator |
| `jmt-blake2b256-v1` | Jellyfish Merkle tree | Off-chain only |

The profile, its exact `state.format-fingerprint`, and a fresh
`state.genesis-id` are configured together as `state.commitment-profile`,
`state.format-fingerprint`, and `state.genesis-id`. Together they are the
chain's state identity. The stock cluster configuration uses MPF.

After each block, the root is:

- **identical** on every honest member: each one re-executes the block and
  compares the root byte for byte before it votes;
- **provable**: it supports inclusion and exclusion proofs; and
- **anchorable**: the same root can be published to Cardano.

Effect records are committed through an `effectsRoot` entry, so an authorized
but not yet executed external action is as provable as ordinary state. New
chains also record each block's `[height, messagesRoot, messageCount]` in state
by default, which ties a message inclusion proof to the state root.

## What a proof contains

<!-- illustration: proof-path -->

A state proof carries your record, its canonical key and value, and the hashes
of the siblings along its path to the root. The verifier hashes the record
itself and recomputes each node up to the root. If the recomputed root matches,
the proof is consistent with that root, and nothing more. Whether the record is
really in the ledger depends on where that root came from.

An exclusion proof shows that a key is absent from the state under that root.
It does not show that the underlying business fact never happened: the data
set might simply be incomplete. Do not expose a business-level absence check
unless a marker, a paired subject, or an authenticated snapshot descriptor
proves that the data set is complete.

## Proof subjects: ask in application terms

Raw key proofs force the caller to know the physical storage layout. A **proof
subject** is a typed descriptor that turns an application-level identity into
its canonical state key, decodes only the value the verified proof carries, and
evaluates only the claims it declares.

```bash
curl -s -X POST \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/proof-subjects/finalized-message-v1/proof" \
  -H 'Content-Type: application/json' \
  -d '{
        "coordinates": {"message-id": "<id>"},
        "view": "latest",
        "claim": {"claimId": "recorded", "operands": {}},
        "includeEvidence": false
      }' | jq
```

You supply coordinates in your own vocabulary: a message id, an account, a
registry key, a document id. The response includes a `trust` field with the
result's trust level; see [How much does a proof prove?](#how-much-does-a-proof-prove).

`finalized-message-v1` is built into `ordered-log`, which `orders-chain` uses.
Other state machines provide it only when the chain is created with
`machines.finalized-message-index.enabled=true`.

Stock v1 subjects cover finalized block messages, finalized message records,
balances, registry entries, document heads, basic approval outcomes,
authenticated-map entries, actor roles, role approval outcomes, and composite
profile markers. Composite subjects are rebound to their component namespace
automatically and get component-qualified ids.

## Read the result precisely

Each of these statements means something different, and a proof response keeps
them separate:

| Result | What it asserts |
|---|---|
| Message inclusion | The message id is a leaf under one app block's `messagesRoot`. |
| Finality certificate | A membership threshold the caller pinned signed the block. |
| Authenticated block record | Final state contains `[height, messagesRoot, messageCount]`. |
| State recording | The application wrote a typed fact under its canonical state key. |
| Anchor binding | A trusted Cardano output commits the selected application identity or root. |
| Claim satisfied | The value carried by the proof satisfies the selected bounded predicate. |
| Locally retained | This node has the bytes now. Durable availability is separately `NOT_PROVEN`. |

## How much does a proof prove?

A proof always recomputes a root. Its **trust level** names where the root it
was checked against came from. There are five levels:

<!-- illustration: trust-ladder -->

| Trust level | The root came from | For example |
|---|---|---|
| `INTERNAL_CONSISTENCY_ONLY` | The same response as the proof | A node's proof response, by default |
| `CALLER_PINNED_ROOT` | A root you pinned, or a block certified by member keys and a threshold you pinned | Attest `--members keys.json`; `yano-explorer verify --members` |
| `NODE_CONFIRMED_L1_REFERENCE` | The node's report of its latest confirmed Cardano anchor | A proof request with `"view": "latest-confirmed-anchor"` |
| `CALLER_PINNED_ANCHOR` | An anchor datum you supplied; the verifier did not read Cardano | `yano-explorer verify --anchor-datum-hex` |
| `INDEPENDENTLY_VERIFIED_L1_ANCHOR` | Anchor data read from Cardano, independently of any member or node | Attest or Trust Registry `--anchor-datum-hex` with a datum you read from Cardano |

<!-- /illustration -->

:::caution[A reconstructed root is not a trusted root]
A proof that only reconstructs a root is `INTERNAL_CONSISTENCY_ONLY` until the
root is pinned by the caller, by a pinned finality policy, or by an
independently checked Cardano script output. `NODE_CONFIRMED_L1_REFERENCE` is
the node's claim about Cardano, not something you checked.
:::

Availability is reported separately, with its own two values:
`LOCALLY_RETAINED` (this node has the bytes now) and `NOT_PROVEN` (nothing
proves the data will stay available).

## Retention and pruning

A node keeps proof material for past heights so it can answer historical proof
requests. Optional MPF proof pruning is experimental and off by default; when
enabled, it removes material below a retained horizon. Check where retained
history starts with `oldestProvableHeight`:

```bash
./yano.sh appchain state identity --url http://node:8080/api/v1 --chain registry
./yano.sh appchain state oldest   --url http://node:8080/api/v1 --chain registry
```

A pruned proof is **unavailable**. It is not evidence that the fact was absent.

## Verifying independently

Proofs are portable. The Java client SDK (`yano-x-client`) verifies them in your
application, and the composite client (`yano-x-composite-client`) also verifies
governed-profile finality, one-root MPF, epoch chains, and authorization
policy.

Retrieve a proof from a node, then verify the saved proof offline:

```bash
# Online: save the proof for a canonical key at a retained height.
./yano.sh appchain state proof --url http://node:8080/api/v1 \
  --chain registry --key <canonical-key-hex> --height <height> > proof.json

# Offline: supply a root and identity authenticated independently of that file.
./yano.sh appchain state verify --proof-file proof.json \
  --trusted-root <root-hex> --profile <profile-id> \
  --genesis-id <64-hex-genesis-id> --chain registry --height <height> \
  --root-source caller-pinned
```

Never take the trusted root from the proof itself. Obtain it from an
independently authenticated block, a finality policy you pinned, or a Cardano
commitment.

## Reference

### The finalized block-message index

New chains enable `state-index:finalized-block-messages-v1` by default. Its
enabled flag and configuration digest are part of the chain's state identity,
so an existing database with a different configuration fails to start instead
of drifting silently. Disable it only in a new genesis profile.

Rough raw growth for that index, before MPF or JMT node and RocksDB
amplification, is one 32-byte key plus a canonical CBOR value of about 38–47
bytes per block:

| Block interval | Records per day | Raw logical growth per day |
|---|---:|---:|
| 1 second | 86,400 | 5.8–6.5 MiB |
| 5 seconds | 17,280 | 1.15–1.30 MiB |
| 20 seconds | 4,320 | 0.29–0.33 MiB |

Capacity planning must measure backend amplification, compaction, snapshots,
and retained proof history on the intended workload.

### Custom proof subjects

For a custom subject, implement `ProofSubjectProvider` next to the module that
owns the canonical key and value codec. Each descriptor is closed data:
coordinates, fact fields, claims, completeness, verification targets, retention
hints, and fixed bounds. Its `descriptorDigest` goes into the capability
manifest, and the runtime activates the provider only when the subject id,
version, component id, and digest all match. Resolution must be deterministic
and free of side effects.

## Deeper reading

- [Proof Lab](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/PROOF_LAB.md):
  message, typed-state, imported, and on-chain proof workflows, plus the
  independent-verifier and Cardano-validator guides.
- [Composable state and portable proofs](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/COMPOSABLE_STATE_AND_PROOFS.md):
  the verification trust boundary and MPF proofs in a Cardano validator.
- [Tutorial 2](/tutorials/02-registry-and-proofs/): retrieve and read a proof.
- [Tutorial 7](/tutorials/07-anchors-and-verification/): bind a root to
  Cardano.
- [Authenticated snapshots](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/AUTHENTICATED_SNAPSHOTS.md):
  archive and prove large immutable period data sets.

**Next:** [Effects](/concepts/effects/).
