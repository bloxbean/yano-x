---
title: "Products"
description: "A product is a step above a recipe. Where a recipe selects capabilities, a product assembles a state machine or composite profile, a domain contract, a…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/products-overview.md"
---
A **product** is a step above a recipe. Where a recipe selects capabilities, a
product assembles a state machine or composite profile, a domain contract, a
read API, a client, and often a CLI into one installable thing with an opinion
about a use case. Every "Try it" below runs from an extracted
`yano-x-jvm-<version>.zip`.

| Product | What it does | For | Try it | Maturity |
|---|---|---|---|---|
| [Evidence](/products/evidence/) | Publishes a document through threshold approval, stores it in object storage and IPFS, notifies Kafka, and proves each step. | Teams releasing records several organizations approve | `examples/evidence/demo.sh` (Docker) | `preview` |
| [Cardano History](/products/cardano-history/) | Proves historical Cardano parameters, epoch stake, DRep distribution, and proposals instead of trusting an indexer. | Governance, reward, and validator tooling | Showcase `cardano-history-chain` | `preview` |
| [eUTxO ledger](/products/eutxo/) | A Cardano-shaped UTxO ledger with an optional federated bridge, for test funds only. | Researchers and protocol teams | `./yano.sh appchain eutxo demo` | `experimental` |

## Choosing one

<!-- illustration: product-chooser -->

Products are not mutually exclusive with recipes; a product *is* the recipe for
its domain. Start from [choosing a recipe](/recipes/choosing-a-recipe/) when no
product matches your problem, and reach for [the plugin framework](/plugins/)
when no recipe does either. Check first whether a composite of existing
components gets you there. The [use cases](/start-here/use-cases/) page maps
common problems to the building blocks.

## What products have in common

Products reuse the platform's state machines, authenticated state, and proof
verification, but they are packaged differently:

Evidence, Cardano History, and the eUTxO ledger each add runtime plugin
behavior through the plugin catalog, and each ships with a client or CLI and,
for the eUTxO ledger, a browser UI. Plain clients, CLIs, static UIs, and
configuration-only products do not need a new runtime plugin. Product
services can expose their own APIs; `/api/v1/plugins/<bundle-id>/` is the host
route for plugin-contributed APIs, not the route for every product.

## What products deliberately do not claim

A proof establishes a specific recorded claim under a stated trust policy. It
does not establish the truth of the underlying business event. The platform
proves what identified participants finalized and what they authorized; it does
not decide what counts as a valid product event, an acceptable inspection, or a
correct settlement. That stays with the domain.

Most product verifiers report one of Yano's five trust levels, from
`INTERNAL_CONSISTENCY_ONLY` (consistent with itself) through `CALLER_PINNED_ROOT`
(members you pinned) to `INDEPENDENTLY_VERIFIED_L1_ANCHOR` (an anchor you read
from Cardano yourself). Treat only the pinned and anchored levels as verified.
[Keys and trust](/concepts/trust-model/) explains what each one rests on.

:::note[Pre-release]
Yano is pre-release, and the products above are `preview` or `experimental`. Their contracts, wire formats, and configuration may still
change. Use a devnet or a Cardano test network with disposable data.
:::
