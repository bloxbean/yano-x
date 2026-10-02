---
title: Cardano History
description: Query and prove historical Cardano protocol parameters, epoch stake, DRep distribution, and proposal history — as compact, root-fixed proofs rather than trusted API responses.
sidebar:
  order: 3
---

Cardano History is an optional plugin that records Cardano epoch facts on an app
ledger. It answers questions such as "what was `key-deposit` in epoch 512?" with
a **proof**, not with an API response you have to trust.

## Problem

Tools that need historical Cardano values, such as the protocol parameters, the
stake distribution, or a governance proposal's history at a past epoch, read
them from an indexer API. The answer is only as good as that indexer, and
nothing ties it to what Cardano actually recorded at the time.

## Who it is for

- **Governance tooling** that must show what the rules were when an action was
  taken, not what they are now.
- **Reward and stake analysis** that has to be reproducible and auditable later.
- **Smart contracts and validators** that need a compact, era-stable proof of a
  historical parameter.
- **Anyone** who trusts an indexer for historical values today and wants
  evidence instead.

## Actors and flow

Every member observes the same completed Cardano epoch boundaries from its own
Cardano data, and the ledger records the epoch's facts once a stable L1
reference is available. Reads go through bounded, read-only routes below
`/api/v1/plugins/org.yanoproject.x.cardano-history/`, and every request names the
ledger with `chain=<chain-id>`:

| Route | Returns |
|---|---|
| `status`, `epochs` | The latest recorded epoch and the epochs available |
| `epochs/{epoch}/parameters` | The protocol parameters of an epoch, with a sorted `fields` catalog |
| `epochs/{epoch}/parameters/fields/{field_id}` | One named parameter as a typed canonical leaf, with proof coordinates |
| `epochs/{epoch}/stake/{credential_type}/{credential_hash}`, `epochs/{epoch}/stake-address/{stake_address}` | Epoch stake for one credential |
| `epochs/{epoch}/dreps/{drep_type}/{drep_hash}` | DRep distribution for one DRep |
| `proposals/{transaction_id}/{index}` | One governance proposal's history |

Each answer names the `committedHeight` and `stateRoot` it was read at. A single
named field is its own leaf, so a proof such as `key-deposit == 2_000_000
lovelace` stays valid without parsing a hard-fork-specific positional array:

<!-- illustration: history-field-proof -->

## Presets

The preset is chosen when the ledger is created:

| Preset | Protocol parameters | Epoch stake | Proposals and DRep distribution |
|---|:---:|:---:|:---:|
| `params-only-v1` (default) | yes | no | no |
| `params-stake-v1` | yes | yes | no |
| `params-governance-v1` | yes | no | yes |
| `full-v1` | yes | yes | yes |

Stake and governance datasets are large, and enabling them changes what the
ledger commits to, so they are never enabled implicitly. Those presets require
`authenticated-snapshots-v1`: each complete stake epoch and DRep distribution
epoch gets its own immutable descriptor and secondary authenticated root.
Protocol parameters stay small facts in the primary state.

:::caution[The preset is part of the ledger's identity]
The preset and the commitment identity are fixed at genesis. A preview ledger
starts fresh when either changes, so choose the preset you need before you
start.
:::

## What it proves, and what it does not

A verified answer proves that the ledger recorded this value for this epoch,
under a root that a threshold of members certified and, with an anchor, that
Cardano carries. An incomplete stake or DRep dataset answers `complete=false`;
it is never presented as zero stake or as non-membership.

It does not invent data. A fresh generation never builds a fact from the
current, still-changing epoch. Until the first fact is final, generic status,
capability discovery, and anchor endpoints work, product routes report no data,
and the CLI exits with code 3. "Not yet observed" is reported as missing data,
never as a value.

At the boundary from epoch `E−1` to `E`, protocol parameters and the DRep
distribution are labelled `E`, while the stake dataset is the end-of-epoch
snapshot labelled `E−1`. Do not present it as the active stake distribution for
`E`.

`yano-cardano-history verify` exits 0 for a fully L1-authenticated proof and 5
when the proof is valid against a pinned root but the Cardano anchor was not
checked independently (`ROOT_VERIFIED_ANCHOR_UNCHECKED`). It ignores any trust
source embedded in a bundle.

## Try it

The light showcase runs a `cardano-history-chain` with the `params-only-v1`
preset. From an extracted `yano-x-jvm-<version>.zip`:

```bash
cd examples/showcase
./showcase.sh quickstart --profile light --nodes 3 --instance demo

HISTORY=../../tools/yano-cardano-history/bin/yano-cardano-history
$HISTORY status --url http://127.0.0.1:7070/api/v1 --chain cardano-history-chain \
  --api-key yano-local-cluster-full-key
```

The first fact arrives after the next stable epoch transition of the showcase's
devnet; until then the CLI exits with code 3. The node console shows the same
data at `/ui/app-chain/cardano-history/?chain=cardano-history-chain`. The
[product guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/CARDANO_HISTORY.md)
covers `query` and `proof` commands, other presets, and retention.

## Modules

| Artifact | Repo path | Role |
|---|---|---|
| `yano-x-cardano-history` | `products/cardano-history/runtime` | The plugin: observers, components, and read routes |
| `yano-x-cardano-history-client` | `products/cardano-history/client` | Typed client with proof verification |
| `yano-x-cardano-history-cli` | `products/cardano-history/cli` | `yano-cardano-history`, shipped as `tools/yano-cardano-history` |
| `yano-x-cardano-history-onchain` | `products/cardano-history/onchain` | On-chain validators for parameter, stake, DRep, and proposal predicates |

The plugin bundle is in the JVM distribution's default `plugins/`. It adds no
Cardano-specific logic to the Yano core API and no new proof format.

## Status

`preview`. The decision records are ADR-028, ADR-035, and ADR-036. Read
[State and proofs](/concepts/state-and-proofs/) for how to read a proof result.
