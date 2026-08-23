---
title: Cardano History
description: Query and prove historical Cardano protocol parameters, epoch stake, DRep distribution, and proposal history — as compact, root-fixed proofs rather than trusted API responses.
sidebar:
  order: 3
---

Cardano History is an optional app-chain plugin that assembles reusable epoch
observers and state-machine components into one installable product. It answers
questions such as "what was `key-deposit` in epoch 512?" with a **proof**, not
with an API response you have to trust.

It does not copy Cardano-specific logic into the Yano core API, and it does not
define another proof format.

## Presets

| Preset | Protocol parameters | Epoch stake | Proposals and DRep distribution |
|---|---:|---:|---:|
| `params-only-v1` (default) | yes | no | no |
| `params-stake-v1` | yes | yes | no |
| `params-governance-v1` | yes | no | yes |
| `full-v1` | yes | yes | yes |

Stake and governance traversal is **never** enabled implicitly — those datasets
are large, and enabling them changes what the chain commits to.

:::caution[The preset is genesis-time]
The selected preset and the commitment identity are genesis-time inputs. A
preview chain starts fresh when either changes. Choose the preset you actually
need before bootstrapping.
:::

Stake and governance presets require `authenticated-snapshots-v1`. They do not
fall back to storing the full dataset in the primary map: each complete stake
epoch and DRep distribution epoch gets its own immutable descriptor and
secondary authenticated root. Protocol parameters stay small primary-MPF facts
and need no redundant secondary snapshot.

## Compact, field-level proofs

The read API contributes bounded, read-only routes below:

```text
/api/v1/plugins/com.bloxbean.cardano.yano.appchain.cardano-history/
```

Every request requires `chain=<chain-id>`. Routes cover status, epochs,
protocol parameters, stake, DRep distribution, and proposal history.

The design detail worth knowing: protocol-parameter responses expose a sorted
`fields` catalog, and a single named field is addressable.

| Route | Returns |
|---|---|
| `params/{epoch}/document` | The complete parameter document. |
| `params/{epoch}/fields/{field-id}` | One named canonical leaf. |
| `epochs/{epoch}/parameters/fields/{field-id}` | The typed canonical leaf plus root-fixed proof coordinates. |

That makes a proof like `key-deposit == 2_000_000 lovelace` compact, and — more
importantly — verifiable **without parsing a hard-fork-specific positional
array**. A verifier written today keeps working across eras.

## Startup and data availability

A fresh Cardano History generation never synthesizes a fact from mutable
current-epoch state. On startup, every member deterministically reconciles the
completed boundaries still retained by its local L1 account-state store, so
retained facts can seed prior epochs with no external indexer.

If no completed boundary is retained, the first fact arrives after the next
stable L1 epoch transition. Until a fact finalizes:

- generic app-chain status, capability discovery, and anchor endpoints remain
  available;
- product routes return HTTP **404**; and
- the CLI reports unavailable data with **exit code 3**.

This is deliberate. "Not yet observed" is reported as absence of data, never as
a fabricated value.

At the `E-1 → E` boundary, protocol parameters and DRep distribution are
labelled for `E`.

## Modules

| Artifact | Repo path | Role |
|---|---|---|
| `yano-x-cardano-history-runtime` | `products/cardano-history/runtime` | The plugin: observers, components, and read routes. |
| `yano-x-cardano-history-client` | `products/cardano-history/client` | Typed client with proof verification. |
| `yano-x-cardano-history-cli` | `products/cardano-history/cli` | Command-line queries and proof export. |
| `yano-x-cardano-history-onchain` | `products/cardano-history/onchain` | On-chain artifacts for validator-side verification. |

## Who this is for

- **Governance tooling** that must show what the rules were at the time an
  action was taken, not what they are now.
- **Reward and stake analysis** that has to be reproducible and auditable years
  later.
- **Smart contracts and validators** that need a compact, era-stable proof of a
  historical parameter.
- **Anyone** currently trusting a chain-indexer API for historical values and
  wanting evidence instead.

## Deeper reading

- [Cardano History product guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/CARDANO_HISTORY.md)
  — full route reference, presets, and operational guidance.
- [Authenticated snapshots](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/AUTHENTICATED_SNAPSHOTS.md)
  — how large immutable period datasets are archived and proved.
- [State and proofs](/concepts/state-and-proofs/) — how to read a proof result
  correctly.
