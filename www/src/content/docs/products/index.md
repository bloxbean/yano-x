---
title: Products
description: The complete, opinionated applications Yano X ships on top of the app-chain platform — Evidence, Cardano History, and the experimental eUTxO and ZK ledgers.
sidebar:
  order: 1
---

A **product** is a step above a recipe. Where a recipe selects capabilities, a
product assembles a state machine or composite profile, a domain contract, a
read API, a client, and often a CLI into one installable thing with an opinion
about a use case.

| Product | What it does | Maturity |
|---|---|---|
| [Evidence](/products/evidence/) | Publish an immutable document through a threshold-approved workflow, preserve it in object storage and IPFS, notify Kafka, and prove the whole chain. | `preview` |
| [Cardano History](/products/cardano-history/) | Query and prove historical Cardano protocol parameters, epoch stake, DRep distribution, and proposal history. | `preview` |
| [Attest](/products/attest/) | Record a document digest on a doc-trail chain and hand out a portable certificate that verifies offline against member keys or a Cardano anchor. | `preview` |
| [Evidence Desk](/products/evidence-desk/) | Browser workbench for the role workflow and the evidence product: propose, approve with in-browser actor keys, release once, and read every record back with its proof. | `preview` |
| [Trust Registry](/products/trust-registry/) | Trust and status registry on the governed authenticated map: proof-bound status answers, Bitstring Status Lists and TRQP answers served from a replayed projection, offline verification, and a console. | `preview` |
| [Verifiable Explorer](/products/explorer/) | Verify-on-ingest index over stock app chains: timelines, decoded commands, entity trails with a proof-backed state check, search, a content archiver, and row bundles that verify offline. | `preview` |
| [DPP Starter](/products/dpp-starter/) | Digital Product Passport prototype on the governed authenticated map: governed product, version, claim, and event records, a certification round with independent auditors, committed claims with out-of-band disclosure, a public portal with a GS1 Digital Link resolver, an operator gateway, and passports that verify offline. Not the DPP product of ADR-026. | `reference` (prototype) |
| [eUTxO and ZK](/products/eutxo-and-zk/) | A deterministic Cardano-shaped UTxO ledger, an optional Cardano bridge, and an optional ZK validity/rollup path. | `experimental` |

## What products have in common

Each one:

- ships as one or more **runtime plugin bundles** in the Yano X distribution or
  under `optional-plugins/`;
- selects a **state machine or composite profile** whose identity is part of
  chain identity;
- publishes a **no-SPI contracts library** so off-chain code can build and
  decode the same canonical bytes the chain uses;
- contributes **bounded, read-only domain routes** under
  `/api/v1/plugins/<bundle-id>/`; and
- defines **proof subjects** so its facts are provable in application language
  rather than trie keys.

That last point matters most. A product's value is not that it stores data —
it is that it makes a specific claim provable to someone who does not trust the
node that served it.

## Choosing one

Products are not mutually exclusive with recipes; a product *is* the recipe for
its domain. Start from
[choosing a recipe](/recipes/choosing-a-recipe/) and let the outcome table point
you here.

Reach for a product when your problem is recognizably the one it models. Reach
for [the plugin framework](/plugins/) when it is not, and check first whether a
composite of existing components gets you there.

## What products deliberately do not claim

The reusable platform proves what identified participants finalized and what
publication instruction they authorized. It does not decide what counts as a
valid product event, an acceptable inspection, or a correct settlement — that
stays with the domain.

Evidence, in particular, proves that specific members approved specific bytes at
a specific point in a verifiable order, and that a connector reported storing
those bytes. It does not prove the document's content is true.

:::note[Pre-release]
Yano is pre-release, and the products above are `preview` or `experimental`.
Their contracts, wire formats, and configuration may still change. Use a devnet
or a Cardano test network with disposable data.
:::
