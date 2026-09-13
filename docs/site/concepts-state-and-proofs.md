# State and proofs

Application state lives in an authenticated trie. Every finalized block
produces one root that commits to all of it, identical on every member. A
client can then verify an individual record against that root without trusting
the node that served it.

## The state root

State is stored in an **MPF** (Merkle Patricia Forestry, Aiken-compatible)
trie. After applying a block, the resulting root is:

- **identical** on every honest member — that is what members sign;
- **anchorable** — the same bytes go into a Cardano metadata or script anchor;
  and
- **provable** — it supports both inclusion and exclusion proofs.

Effect records are committed transitively through a count-bound `effectsRoot`
leaf, so an authorized-but-not-yet-executed external action is as provable as
ordinary state.

## Proof subjects: application language, not trie internals

Raw key/value proofs exist, but they force the caller to know the physical
layout. A **proof subject** is a typed descriptor that resolves an
application-level identity to its canonical state key and evaluates declared
claims against the proof-carried value.

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

You supply coordinates in your own vocabulary (`message-id`, an account, a
registry key, a document id). The subject derives the canonical key,
decodes only the value the verified proof carries, and evaluates only the
claims it declares.

Stock v1 subjects cover finalized block messages, finalized message records,
balances, registry entries, document heads, basic approval outcomes,
authenticated-map entries, actor roles, role approval outcomes, and composite
profile markers. Composite subjects are automatically rebound to their
component namespace and get component-qualified ids.

## Read the result precisely

This is the part people get wrong. Each of these statements means something
different, and a proof response keeps them separate:

| Result | What it actually asserts |
|---|---|
| Message inclusion | The message id is a leaf under one app block's `messagesRoot`. |
| Finality certificate | A caller-pinned membership threshold signed the block. |
| Authenticated block record | Finalized state contains `[height, messagesRoot, messageCount]`. |
| State recording | The application wrote a typed fact under its canonical state key. |
| Anchor binding | A trusted Cardano output commits the selected application identity or root. |
| Claim satisfied | The proof-carried canonical value satisfies the selected bounded predicate. |
| Locally retained | This node has the bytes now. Durable availability is separately `NOT_PROVEN`. |

:::caution[A reconstructed root is not a trusted root]
A proof that merely reconstructs a root is `INTERNAL_CONSISTENCY_ONLY` until
the root is pinned by the caller, by a pinned finality policy, or by an
independently checked Cardano script output. A node-reported anchor is labelled
`NODE_CONFIRMED_L1_REFERENCE` — that is the node's claim about L1, not
independently verified L1 truth.
:::

## Absence is harder than presence

An exclusion proof shows a key is absent from the trie. It does **not** show
that the underlying business fact never happened — the dataset might simply be
incomplete.

Do not expose a business-level absence predicate unless a marker, a paired
subject, or an authenticated snapshot descriptor proves dataset completeness.

## Retention and pruning

Proof history is retained, not infinite. Monitor `oldestProvableHeight`:

```bash
./yano.sh appchain state identity --url http://node:8080/api/v1 --chain registry
./yano.sh appchain state oldest   --url http://node:8080/api/v1 --chain registry
```

A pruned proof is **unavailable**. It is not evidence that the fact was absent.

New chains enable the `state-index:finalized-block-messages-v1` subject by
default. Its enabled flag and configuration digest are part of the application
identity, so an existing database with a different configuration fails startup
rather than silently drifting. Disable it only in a new genesis profile.

Rough raw growth for that index, before MPF/JMT node and RocksDB
amplification — one 32-byte key plus a ~38–47 byte canonical CBOR value per
block:

| Block interval | Records/day | Raw logical growth/day |
|---|---:|---:|
| 1 second | 86,400 | 5.8–6.5 MiB |
| 5 seconds | 17,280 | 1.15–1.30 MiB |
| 20 seconds | 4,320 | 0.29–0.33 MiB |

Capacity planning must measure backend amplification, compaction, snapshots,
and retained proof history on the intended workload.

## Verifying independently

Proofs are portable. The Java client SDK (`yano-x-client`) verifies them
client-side, and the composite client (`yano-x-composite-client`) additionally
verifies governed-profile finality, one-root MPF, epoch chains, and
authorization policy.

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

Do not take the trusted root from the unverified proof itself. Obtain it from
an independently authenticated block, finality policy, or Cardano commitment.

For custom subjects, implement `ProofSubjectProvider` next to the module that
owns the canonical key and value codec. Each descriptor is closed data —
coordinates, fact fields, claims, completeness, verification targets, retention
hints, and fixed bounds — and its `descriptorDigest` goes into the capability
manifest. The runtime activates the provider only when subject id, version,
component id, and digest all match. Resolution must be deterministic and
side-effect free.

## Deeper reading

- [Proof Lab](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/PROOF_LAB.md)
  — message, typed-state, imported, and on-chain proof workflows, plus the
  independent-verifier and Cardano-validator guides.
- [Tutorial 2](/tutorials/02-registry-and-proofs/) — retrieve and read a proof.
- [Tutorial 7](/tutorials/07-anchors-and-verification/) — bind a root to L1.
- [Authenticated snapshots](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/AUTHENTICATED_SNAPSHOTS.md)
  — archiving and proving large immutable period datasets.
