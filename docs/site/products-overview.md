# Products

A **product** is a step above a recipe. Where a recipe selects capabilities, a
product assembles a state machine or composite profile, a domain contract, a
read API, a client, and often a CLI into one installable thing with an opinion
about a use case. Every "Try it" below runs from an extracted
`yano-x-jvm-<version>.zip`.

| Product | What it does | For | Try it | Maturity |
|---|---|---|---|---|
| [Evidence](/products/evidence/) | Publishes a document through threshold approval, stores it in object storage and IPFS, notifies Kafka, and proves each step. | Teams releasing records several organizations approve | `examples/evidence/demo.sh` (Docker) | `preview` |
| [Cardano History](/products/cardano-history/) | Proves historical Cardano parameters, epoch stake, DRep distribution, and proposals instead of trusting an indexer. | Governance, reward, and validator tooling | Showcase `cardano-history-chain` | `preview` |
| [Attest](/products/attest/) | Records a document's digest and hands out a certificate that anyone can verify offline. | Proof of existence; auditors | Showcase `documents-chain` with `tools/yano-attest` | `preview` |
| [Evidence Desk](/products/evidence-desk/) | Propose, approve with browser-held actor keys, release once, and read every record back with its proof. | Issuers, auditors, and reviewers | Showcase `document-review-chain` with `product-ui/evidence` | `preview` |
| [Trust Registry](/products/trust-registry/) | Answers credential status and issuer authorization with proofs, and serves status lists tied to the ledger. | Credential issuers and verifiers | `examples/trust-registry/registry.sh` | `preview` |
| [Verifiable Explorer](/products/explorer/) | Indexes stock ledgers after verifying each block; rows and states export as bundles that verify offline. | Operators, support, and auditors | `examples/explorer/explorer.sh` | `preview` |
| [DPP Starter](/products/dpp-starter/) | A prototype product passport registry: governed records, independent certification, and passports that verify offline. | Teams exploring Digital Product Passports | `examples/dpp/dpp.sh` | `reference` (prototype) |
| [Attestation Feed](/products/attestation-feed/) | Sources sign readings, every verifier recomputes each round, and two organizations approve the record. | Consortia combining readings from known sources | `examples/attestation-feed/feed.sh` | `experimental` (starter) |
| [eUTxO and ZK](/products/eutxo-and-zk/) | A Cardano-shaped UTxO ledger with an optional federated bridge and ZeroJ validity proofs, for test funds only. | Researchers and protocol teams | `./yano.sh appchain eutxo demo` | `experimental` |

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

- Evidence, Cardano History, and eUTxO and ZK add runtime plugin behavior.
- Attest is a client, CLI, and UI over the stock `doc-trail` state machine.
- Trust Registry, DPP Starter, and Attestation Feed configure the stock governed
  authenticated map and add application tooling outside consensus.
- Evidence Desk is a browser UI over the role workflow.
- Verifiable Explorer maintains a derived read index and its own service.

Runtime contributions use the plugin catalog. Plain clients, CLIs, static UIs,
and configuration-only products do not need a new runtime plugin. Product
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
Yano is pre-release, and the products above are `preview`, `reference`, or
`experimental`. Their contracts, wire formats, and configuration may still
change. Use a devnet or a Cardano test network with disposable data.
:::
