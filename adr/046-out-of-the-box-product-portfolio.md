# ADR-046 — Out-of-the-box product portfolio for the Yano X app-chain platform

- **Status:** Proposed; for discussion. No module, wire format, catalog row, or runtime behavior
  changes with this document.
- **Date:** 2026-09-05
- **Owners:** Yano X product and platform maintainers
- **Scope:** Which additional products should live under `products/`, why, in what order, and
  what each one exercises and proves. Each selected product gets its own decision ADR before
  implementation; this document only chooses candidates and sequencing.
- **Related:**
  [ADR-022 capability catalog and product model](app-layer/022-out-of-box-appchain-capabilities-and-extensible-product-catalog.md),
  [ADR-026 DPP registry](app-layer/026-dpp-authenticated-registry-appchain.md),
  [ADR-032 verifiable indexer components](app-layer/032-out-of-box-verifiable-indexer-components.md),
  [ADR-033 showcase catalog](app-layer/033-showcase-reference-catalog-and-capability-discovery.md),
  [ADR-035 Cardano History product](app-layer/035-cardano-history-product-console-and-distribution.md),
  [ADR-037 proof lab](app-layer/037-generic-appchain-proof-lab-and-subject-discovery.md),
  [ADR-039 deployment automation](039-geographically-distributed-deployment-automation.md),
  [ADR-040 product UIs](040-product-specific-user-interfaces.md),
  [ADR-045 release readiness](045-yano-x-release-readiness.md),
  Yano [ADR app-layer/029 trust and status registry](https://github.com/bloxbean/yano/blob/main/adr/app-layer/029-w3c-did-vc-trust-and-status-registry.md),
  Yano [uVerify research](https://github.com/bloxbean/yano/blob/main/adr/app-layer/uverify-product-and-appchain-integration-research.md),
  and the [use-case catalog](../docs/APP_CHAIN_USE_CASES.md).

## 1. Context

### 1.1 What a product is here

ADR-022, ADR-035, and ADR-040 already define the product pattern, so this document does not
re-specify it. A Yano X product is some subset of:

| Surface | Defined by | Example today |
|---|---|---|
| Chain profile: a recipe, or a manifested bundle with a state-machine provider and read-only domain API | ADR-022, ADR-035 | `cardano-history` bundle; `role-evidence` composite |
| Typed client and CLI, including offline proof verification | ADR-035 §8, ADR-037 | `yano-cardano-history` CLI |
| On-chain consumers: Aiken validators that verify product proofs | ADR-035 §9 | `products/cardano-history/onchain` |
| Separately deployable browser UI that discovers chains by capability manifest | ADR-040 | `products/eutxo/ui` |
| Showcase presence: one product chain or quickstart flag, never a permutation matrix | ADR-033 §3 | `cardano-history-chain`, `--cardano-history-profile` |
| Studio recipe and deployment role | ADR-022 §6, ADR-039 | `eutxo-ledger` recipe |
| Documentation-site page and tutorial | ADR-038 | `CARDANO_HISTORY.md` |

### 1.2 Inventory today

Three products exist under `products/`, not two:

| Product | Modules | UI | Posture |
|---|---|---|---|
| Cardano History | `runtime`, `client`, `cli`, `onchain` | Native capability-gated page in the Yano console (ADR-035 amendment); a separate ADR-040 UI is anticipated | preview |
| EUTxO | Ledger, bridge, indexer, and contracts live under `ledgers/eutxo`; only `ui` is under `products/eutxo` | Separate UI, ADR-040 version one | experimental |
| Evidence | `contracts`, `registry`, `client`, `profile`, `demo-runner`; Compose harness | None. The report application and Compose harness are `REFERENCE` per ADR-022 §4.3 | preview |

The EUTxO split sets the layout precedent this document extends: reusable engine code lives in its
own top-level area, and `products/<name>/` holds the product assembly, client, and UI.

### 1.3 Why choose now

ADR-040 §1 already anticipates "a document workflow, historical-ledger explorer, evidence
application, and EUTxO payment system". The platform has more reviewed capability than shipped
product. Evaluators see the pieces in tutorials and the showcase but cannot install one thing that
solves one recognizable problem, except for Cardano History and the experimental EUTxO bridge.

### 1.4 Constraints this document honors

- **Vocabulary.** Every candidate is classified with ADR-022 availability and maturity terms.
- **Posture.** ADR-045 finds the devnet and showcase posture ready and nothing above it. No
  candidate may imply pilot or production readiness; each one names its gates.
- **No product matrix.** ADR-033 §3.1 forbids approval, role, effect, and observer variants of
  every foundation chain. A product gets at most one product chain or a quickstart flag.
- **Proof boundary.** The overview's section 10 lists what the platform does not prove: physical
  truth, permanent external availability, exactly-once execution, domain compliance without domain
  rules, and unhardened payment safety. Each verification story below stays inside that boundary.
- **Known limits reused, not rediscovered.** `balances` accounts are member-owned only. The shipped
  `credential-registry` reads issuer keys from node-local settings inside `apply()` and is a
  consensus-safety defect to route around, not a base to build on. ADR-026 is gated on ADR-025
  qualification, and ADR-025's status says its runtime phase records are not final v1
  qualification. The x402 facilitator is a Yano core concern.

## 2. Selection criteria

Candidates were scored against eight questions. The first three are hard requirements.

1. **Real buyer.** A named organization type has this problem today and pays to solve it.
2. **Consortium-shaped.** A single-party database provably fails the problem, and a per-record L1
   transaction is too costly, too slow, or too public.
3. **Honest verification story.** What the product proves fits the platform's proof boundary.
4. **Config-first.** Bundled capabilities cover the chain profile; custom providers are bounded
   and justified.
5. **Coverage.** The product exercises capabilities no shipped product demonstrates today.
6. **UI-worthy.** The product has a coherent user journey that a browser application improves.
7. **Bounded build.** The first shippable slice fits one of the size bands below.
8. **Reuse chain.** Later products can reuse its modules.

Size bands: **S** is client and UI over existing chain profiles with no new consensus code;
**M** adds a product profile, a non-consensus service, or an indexer module; **L** adds a
manifested custom provider with its own composite profile.

## 3. Capability coverage today

| Capability group | Catalog IDs | Demonstrated by a shipped product today |
|---|---|---|
| Ordered log, registry, document trail | `state:ordered-log`, `state:kv-registry`, `state:doc-trail` | Showcase foundation chains only; no product |
| Governed authenticated map with inclusion and exclusion proofs | `state:authenticated-map` | Showcase `authenticated-map-chain` only; no product |
| Domain actors and role approvals | `state:role-approvals`, `state:role-evidence` | Evidence, without a UI |
| Composite runtime and governed profile activation | `runtime:composite`, ADR-015 | Evidence profile; governance only as a runbook |
| Effects and connectors | `effects:runtime`, `executor:webhook`, `executor:kafka`, `executor:objectstore-s3`, `executor:ipfs`, `executor:cardano-payment` | Evidence (Kafka, S3, IPFS); EUTxO settlement (Cardano) |
| Anchors and portable proofs | `anchor:metadata`, `anchor:script`, ADR-037 proof packages | Cardano History, EUTxO, console proof lab |
| Block observers | `observer:address-deposit`, `observer:metadata-label` | EUTxO settlement bridge |
| Epoch observers and authenticated snapshots | ADR-028 stdlib observers | Cardano History |
| On-chain MPF verification | Aiken validators | Cardano History `onchain`, EUTxO bridge |
| Membership and sequencing governance | `membership:governed`, `sequencer:rotating` | Runbooks and tests only; no product |
| Read-side indexing | `indexer:eutxo-lifecycle` | EUTxO only |
| ZK | `state:zk-gate`, `state:zk-membership` | None; experimental |

The uncovered groups that matter most to buyers are the governed authenticated map, domain actors
with a UI, and effects with a UI. The portfolio below is chosen to close those first.

## 4. Candidate products

### 4.1 Attestation and Certificate Service (`products/attest`) — size S

**Classification.** `FIRST_PARTY_OPTIONAL` client and UI artifacts over a `BUNDLED` recipe;
maturity `preview`.

**Problem and buyer.** Prove that specific bytes existed by a time, were attested by known parties,
and have not changed, without publishing the bytes, and hand the holder a certificate anyone can
verify offline. Buyers: legal and compliance teams, research groups publishing datasets and model
weights, software vendors preparing release and SBOM attestations for the EU Cyber Resilience Act,
and internal audit functions that need tamper evidence across departments.

**Why a consortium chain.** One organization's database proves nothing to a counterparty. One L1
transaction per document is slow, costly, and public. A two- or three-member chain, even inside one
company, gives co-signed ordering and batches thousands of digests into one anchor with L1 block
time as the bound. This is use case A4 in the use-case catalog and the P0 option in the uVerify
research: a public proof viewer over the existing anchor.

**Capabilities exercised.** `state:doc-trail` keyed by a document or series identifier so a
revision history is one provable chained head; `anchor:metadata` or `anchor:script`; ADR-037 proof
packages; `membership:static` or `membership:governed`; optional `executor:objectstore-s3` and
`executor:ipfs` when the deployment also wants to keep the bytes.

**Config-only or custom.** Config-only. No new state machine and no domain API in version one; the
generic message, state-proof, and anchor APIs suffice.

**Modules.**

| Module | Content |
|---|---|
| `products/attest/client` | Certificate bundle format: ADR-037 proof package plus anchor reference and human metadata; `yano-attest verify` CLI with the same exit-code contract as Cardano History |
| `products/attest/ui` | Hash locally in the browser, submit, watch finality and anchoring, render and export the certificate with a QR link, and a public verify page that accepts a file and a bundle |

**UI journey.** Select a compatible chain by its `doc-trail` proof subject; drop a file, which is
hashed in the browser and never uploaded; choose or create a subject id; submit; watch the entry
finalize and the next anchor land; export the certificate; open the verify page with the file and
the certificate and see four independent checks: digest matches, inclusion under the state root,
finality certificate authorizes the root, Cardano anchor commits the same chain and root.

**Proof story.** Proves who finalized the digest, in what order, and bounds the time by the anchor
block. It does not prove the content is true, lawful, or still available. The product name says
attestation, not notarization, to avoid a legal claim it cannot make.

**Out-of-the-box path.** Studio recipe `attest` equal to the `documents-chain` foundation plus an
anchor; the showcase reuses `documents-chain` through a quickstart flag rather than adding a chain;
an ADR-039 product-UI role serves the static artifact.

**Dependencies and gates.** ADR-037 proof packages are implemented. Golden vectors for the
certificate bundle; anchor verification on Preprod; content archiver from ADR-032 is optional later.

**Risks.** Overlap with uVerify: position as complementary and viewer-first, exactly as the research
recommends, and consider a `uverify.notarize.v1` effect only after measured demand.

### 4.2 Trust and Status Registry (`products/trust-registry`) — size M

**Classification.** `BUNDLED` chain profile on `state:authenticated-map`; `FIRST_PARTY_OPTIONAL`
service, client, and UI; maturity `preview`, inheriting the authenticated map's own preview status.

**Problem and buyer.** Which organizations and keys are authorized to act in a role, and is this
credential or entry still valid, with provable absence and point-in-time answers. Today every
status mechanism ends at "trust the issuer's web server". Yano ADR app-layer/029 names the buyers:
pharma DSCSA authorized-trading-partner registries, payer and provider credentialing exchanges,
regulated-workforce licence status, DPP economic-operator and claim-issuer trust, CRA firmware
release and revocation, and vLEI-anchored entitlement registries. Its lighthouse is a Cardano
ecosystem attestation registry for Catalyst milestone sign-offs, audit attestations, and DRep and
SPO identity claims.

**Why a consortium chain.** Authority is inherently distributed across independent regulators,
payers, or operators, so no single hosted registry is politically possible. Exclusion proofs and
retrievable historical roots are properties no incumbent status service offers.

**Capabilities exercised.** `state:authenticated-map` with governed-role collections, compare-and-set
revisions, revoke tombstones, and inclusion and exclusion proofs; `state:role-approvals` and the
ADR-019 domain-actor registry for issuer onboarding, key epochs, and proof of possession;
`anchor:script`; `membership:governed`; ADR-015 governed profile activation for schema evolution;
on-chain MPF verification for a status check inside a Cardano validator.

**Config-only or custom.** The chain is config-only, following the ADR-029 §5.2 genesis sketch:
collections for subjects, status, status-list versions, issuers, and schemas with per-collection
policies. The product code is a non-consensus standards skin, never a state machine:

- a W3C Bitstring Status List publisher that serves whole lists from a CDN while the chain holds
  each list version's hash and revision, preserving herd privacy and adding point-in-time
  retrieval from historical roots;
- a ToIP Trust Registry Query Protocol endpoint for "does entity X hold authorization Y under
  framework Z";
- a proof-envelope verifier reusing the shipped `appchain-client` verifier.

The determinism firewall from ADR-029 §5.5 is mandatory: no JSON-LD, RDF canonicalization, or
context resolution on the consensus classpath, enforced in CI.

**Modules.**

| Module | Content |
|---|---|
| `products/trust-registry/profile` | Genesis collections, policies, bootstrap plan, Studio recipe, profile digest tool |
| `products/trust-registry/service` | Status-list publisher, TRQP endpoint, point-in-time queries, projections; runs beside a node |
| `products/trust-registry/client` | Verifier library and `yano-trust verify` CLI |
| `products/trust-registry/ui` | Registrar and issuer console; public status lookup |

**UI journey.** A registrar onboards an organization and its actors through role approvals; an
issuer registers subjects and sets status with revision checks; a published status-list version is
hashed on chain; a verifier queries TRQP or fetches the list and optionally validates the proof
envelope; an auditor asks "status as of this height or date" and receives a proof against the
historical root.

**Proof story.** Inclusion or exclusion of an entry, its revision and controller, the finality
certificate, and the anchor. The registry says what the consortium agreed about an identifier; it
never mints identifiers and never asserts that a credential's claims are true.

**Out-of-the-box path.** Recipe `trust-registry`; the showcase starts a fresh instance of
`authenticated-map-chain` with the product collections through a quickstart profile, since
collections are genesis-committed and change the genesis id, exactly as the Cardano History profile
flag does; the Cardano ecosystem lighthouse registry is the public demo deployment.

**Dependencies and gates.** ADR-025.2 governed-authorization qualification; acceptance of Yano
ADR app-layer/029 as the design; `retention.enabled: false` for verifiability; a privacy review
covering identifiers and hashes only, no personal data on chain.

**Risks.** Standards drift in status-list and TRQP versions; the temptation to reuse the shipped
`credential-registry`, which is excluded; per-key status lookups that would leak verifier activity,
which the whole-list design avoids.

### 4.3 Evidence Desk (`products/evidence/ui`) — size S to M

**Classification.** `FIRST_PARTY_OPTIONAL` UI over the existing `preview` evidence product; the
Compose harness stays `REFERENCE`.

**Problem and buyer.** The evidence product is the most complete first-party workflow in the
repository and the only one exercising domain actors, role approvals, three connectors, and profile
governance together, but it is reachable only through a demo runner and CLI. Inspection bodies,
laboratories, and manufacturers with auditors and regulators, the exact scenario of tutorial 5,
cannot see it work.

**Why a consortium chain.** Evidence release must be authorized by independent roles from different
organizations, preserved outside consensus, and provably announced. That is the ADR-013 argument and
it already holds.

**Capabilities exercised.** `state:role-evidence`, which composes `state:evidence-registry` and
`state:role-approvals`; `executor:objectstore-s3`, `executor:ipfs`, `executor:kafka`,
`sink:webhook`; ADR-015 profile activation; anchors and proof export.

**Config-only or custom.** No chain change. One new module, `products/evidence/ui`, over the
existing `evidence/{id}` domain route, generic approval and effect status APIs, and proof export.
The client module may gain typed models the UI needs.

**UI journey.** An actor loads a local signing key that never leaves the browser; submits evidence
as a hash plus staging reference; reviewers with the configured roles approve; the desk shows each
effect's lifecycle separately as finalized, executed, incorporated, or failed; the derived business
status appears only when the fail-closed receipt checks pass; the user exports the evidence report
and proof bundle; a verifier page replays the report checks.

**Proof story.** Unchanged from ADR-013 and the evidence demo: state proof, threshold finality, S3
version, exact CID bytes, Kafka acknowledgement, and the Cardano state-thread datum, shown
separately and never merged into one green light.

**Out-of-the-box path.** The Compose harness under `products/evidence/harness` for the full
connector path; the light showcase `document-review-chain` for a connector-free path the same UI
must handle by capability discovery.

**Dependencies and gates.** ADR-040 §7 browser and wallet rules applied to actor keys; the E-9 key
custody item from ADR-044 for any production-shaped signer.

**Risks.** Actor key handling in a browser; showing connector outcomes honestly when an external
service is down.

### 4.4 Digital Product Passport Registry (`products/dpp`) — size L, gated

**Classification.** The starter is `REFERENCE` and labelled a prototype so it cannot be read as a
product; the full provider is `FIRST_PARTY_OPTIONAL` at maturity `preview` once its gates close.

**Problem and buyer.** Manufacturers, certifiers, logistics, repairers, and recyclers must attach a
non-repudiable lifecycle to products under the EU Ecodesign regulation, and consumers, customs, and
regulators must verify single facts cheaply. ADR-026 already decided the shape: one governed chain
per trust domain, an authenticated map for current records and receipts, the finalized ledger for
order, hashes and references rather than documents, domain-signed commands, aggregate anchoring by
default, and a proof-aware projection tier for scans.

**Why a consortium chain.** The Cardano Foundation blueprint's scenarios all reduce to shared
authenticated state with role-authorized transitions across companies that do not trust each
other's databases, at a volume where one L1 transaction per event is not viable.

**Capabilities exercised.** Almost the whole platform: composite runtime, domain actors and role
approvals, authenticated map, trail heads, S3 and IPFS and Kafka effects, script anchors, portable
proofs and on-chain verification, governed profile evolution for schema versions, and the ADR-032
indexer as the projection tier. This is the flagship demonstration.

**Config-only or custom.** Two stages, exactly as ADR-026 §5 permits:

1. **DPP starter**, config-only and clearly labelled a prototype: authenticated-map collections for
   products, passport versions, claims, and issuer keys; role approvals for certification; the
   evidence publication path for documents. It demonstrates infrastructure, not DPP rules.
2. **Full provider** `dpp-registry` with the `dpp-core-v1`, `role-approvals`, and
   `dpp-publication-v1` components, its own manifested identity, and named workflows for
   accreditation, publication, revoke and replace, and recovery.

The trust registry of §4.2 is the identity sub-layer for economic operators and claim issuers, as
ADR-029 use case 4 positions it, not a separate DPP identity product.

**Modules.** `products/dpp/{contracts, registry, client, profile, projection, portal-ui,
operator-ui}`; `onchain` only if the optional per-product CIP-68 publication profile is selected.

**UI journey.** Operator console: register a product and passport version, append manufacturing,
shipping, inspection, repair, and recycling events, request certification, publish; consumer
portal: scan a GS1-compatible QR, see public data, the lifecycle timeline, current status, and proof
and anchor details; regulator view: selective disclosure of a private claim.

**Proof story.** What the consortium agreed happened to product P at boundary B, with the
document hash and publication receipts. Not that the physical event occurred, and not conformance to
any DPP standard until a conformance review says so.

**Out-of-the-box path.** The reference stack from the DPP research: three members plus object
store, IPFS, Kafka, projector, and portal, with profile-driven schema, namespaces, roles, targets,
and anchor cadence.

**Dependencies and gates.** ADR-025 qualification complete; ADR-026 accepted; §4.2 and §4.5
available for identity and projections; a standards review before any conformance claim.

**Risks.** Scope: the blueprint is a scenario catalogue and invites a product matrix. Sequencing:
starting the full provider before the starter and the registry exist repeats work.

### 4.5 Verifiable Explorer (`products/explorer`) — size M, enabling

**Classification.** `FIRST_PARTY_OPTIONAL` indexer modules, read service, and UI with `scope: node`;
maturity `preview`.

**Problem and buyer.** Nodes commit truth and do not promise rich queries or long-term bodies; the
Yano console is generic by decision. Every product above needs search, timelines, and history with
evidence attached, and auditors need a browsable view that can still prove what it shows. ADR-032
already decided the components; this document treats them as a product with a UI.

**Why this shape.** A derived index that verifies on ingest and can attach a proof bundle to any
row keeps the trust root at the node and the anchor while making products usable. It is never an
authority, per ADR-032 §4.

**Capabilities exercised.** Indexer core generalized from the EUTxO indexer; per-machine modules
for ordered-log, doc-trail, authenticated-map, and approvals over ADR-031 typed subjects; the
content archiver for hash-plus-reference data; the verifiable read API with proof bundle v2; SSE
and webhook sinks.

**Config-only or custom.** No consensus code. Indexer modules and a read service.

**Modules.** `products/explorer/{indexer, api, ui}`; the indexer core may instead live beside
`ledgers/eutxo/indexer-core` if the team decides it is shared infrastructure (open question 1).

**UI journey.** Choose a chain; browse blocks, messages, and typed state per machine; open an
entity's trail, an entry's revision history, or a proposal's decision trail; click "verify this
row" to fetch the proof bundle and check it against the anchor; open archived content by hash with
its availability state shown as finalized, state-recorded, content-verified, or available.

**Proof story.** Every displayed row links to the message id or state key it came from and can
carry its proof. The explorer adds convenience, never trust.

**Out-of-the-box path.** Runs beside the showcase against SQLite or PostgreSQL; the DPP portal and
the trust-registry public lookup are its first product consumers.

**Dependencies and gates.** ADR-031 Phase 5 typed subjects and codecs are complete, and ADR-037
already discovers them at runtime; ADR-032 milestones IX-M1 through IX-M4 remain.

**Risks.** Becoming an implicit authority if a product reads it on a consensus or accounting path;
schema churn as machines evolve.

### 4.6 Consortium Data Attestation Feed (`products/attestation-feed`) — size M to L, later

**Classification.** `EXPERIMENTAL` until the Cardano publication executor is hardened and the
aggregation component passes composite review.

**Problem and buyer.** Reference values that several organizations must agree on and that
downstream systems and Cardano contracts consume: FX fixings, index and commodity prices, meter and
sensor readings, weather and settlement events. Today one vendor's feed is the source of truth.
Buyers: data consortia, utilities and grid operators, and Cardano applications that want a
consortium-governed, self-hosted feed with provable history. The overview lists this as the oracle
observation ledger.

**Why a consortium chain.** Threshold agreement on each round, a provable observation history per
source, and a single aggregated value the members co-sign before it reaches L1.

**Capabilities exercised.** Domain-actor-signed observations; a bounded custom composite component
for deterministic per-round aggregation with outlier rules; `effects:runtime` with a new Cardano
datum-publication executor; script anchors; proofs; an on-chain consumer validator that reads the
feed value for a round.

**Config-only or custom.** Custom: aggregation is domain logic and must be a reviewed component;
the datum publisher is a new executor that needs the payment-grade hardening the overview's
section 10 warns about.

**Modules.** `products/attestation-feed/{contracts, registry, client, onchain, ui}`.

**UI journey.** Source operators submit signed observations; the round view shows submissions,
quorum, outliers, and the aggregate; publication status to Cardano; consumers look up a round with
its proof.

**Proof story.** Who observed what and what the consortium agreed to publish. Not that the price
or reading is true; source selection and outlier governance are the feed operator's domain rules.

**Out-of-the-box path.** A sample three-source feed on devnet with a mock source adapter.

**Dependencies and gates.** A hardened Cardano publication executor, likely owned in Yano core;
ADR-013.2 composite review of the aggregation component.

**Risks.** Competes with established Cardano oracles on public data; the differentiation is private
or consortium data with provable history, so the product must not be pitched as a public price
oracle.

## 5. Considered and deferred

| Candidate | Why not now |
|---|---|
| Usage metering and receipt netting, x402 style (use case B3) | `balances` accounts are member-owned, so actor-owned accounts need a custom composite; L1 payout needs hardening; the x402 facilitator is a Yano core module; overlaps the EUTxO settlement product. Revisit as an EUTxO or feed extension. |
| Loyalty points and internal credits | Same `balances` limitation; no consortium-shaped buyer without the settlement half. |
| Stake and voting-power eligibility proofs for token distributions | A consumer application of Cardano History stake snapshots and its on-chain validators; belongs in that product's roadmap and the explorer, not a new product. |
| Sealed bids, anonymous voting, whistleblowing | Requires the ZK extension, which is experimental and carries ADR-044 finding R-14 on classpath-discovered verifiers. |
| A `did:yano` DID method | Rejected by Yano ADR app-layer/029 as the weak product and the expensive build. |
| Academic credentials, carbon and ESG registries, financial KYB | Discarded in ADR-029 with reasons: near-zero revocation volume, sovereign authority politics, terminal incumbent network effects. |
| Document review and case management | Folded into Evidence Desk; the `document-review-chain` foundation is its connector-free profile. |
| Escrow and dispute settlement with L1 as arbiter | Yano ADR app-layer/035 is an exploration with no decision; wait for it. |
| A general workflow engine | ADR-022 §5.4 keeps the evidence claim narrow; a generic engine is the product matrix ADR-033 forbids. |

## 6. Coverage matrix

`x` means the product exercises the capability in its first shippable slice; `o` means a later
slice; blank means not exercised. CH is Cardano History, EX is EUTxO, EV is Evidence.

| Capability group | CH | EX | EV | 4.1 Attest | 4.2 Trust registry | 4.3 Evidence Desk | 4.4 DPP | 4.5 Explorer | 4.6 Feed |
|---|---|---|---|---|---|---|---|---|---|
| Ordered log, registry, doc-trail | | | | x | | | x | x | x |
| Governed authenticated map, exclusion proofs | | | | | x | | x | x | |
| Domain actors and role approvals with a UI | | | | | x | x | x | | x |
| Composite and ADR-015 profile activation | | | x | | o | x | x | | x |
| Effects: S3, IPFS, Kafka, webhook | | | x | o | | x | x | | |
| Cardano publication effect | | x | | | | | o | | x |
| Anchors and portable proofs | x | x | x | x | x | x | x | x | x |
| Block observers | | x | | | | | | | |
| Epoch observers and snapshots | x | | | | | | | | |
| On-chain MPF verification | x | x | | | o | | o | | x |
| Governed membership and rotation | | | | | x | | o | | o |
| Read-side indexing and archiver | | x | | o | o | | x | x | |
| Separate ADR-040 UI | o | x | | x | x | x | x | x | x |

Governed membership and rotating sequencing remain thin: only the trust registry exercises them
in its first slice. If the team wants a product that makes governance visible, the trust registry
is the place, because issuer onboarding and schema activation are governance actions users can
watch.

## 7. Recommended sequence

| Wave | Products | Why this order |
|---|---|---|
| 1 | 4.1 Attest, 4.3 Evidence Desk | Both are size S, need no consensus change, and give the platform two installable UIs within one release. Attest delivers the certificate bundle and verify page that every later product reuses. Evidence Desk makes roles and effects visible. |
| 2 | 4.2 Trust registry, 4.5 Explorer core and modules | The registry is the first product on the governed authenticated map and the identity layer DPP needs. The explorer's doc-trail and authenticated-map modules serve both Attest and the registry. |
| 3 | 4.4 DPP starter, then full provider | Reuses the registry, the explorer as projection tier, Evidence Desk's publication path, and Attest's verify page. Starts only when ADR-025 qualification and ADR-026 acceptance are done. |
| 4 | 4.6 Attestation feed | Needs the hardened Cardano publication executor and a reviewed aggregation component; do not start before core owns the executor. |

Packaging rules for every wave:

- `products/<name>/` holds what one product owns: its assembly as a provider bundle or a config
  profile, contracts, clients and CLIs, non-consensus services, on-chain consumers, and one or more
  UIs. An engine or indexer core shared by more than one product lives in its own top-level area,
  as the EUTxO ledger does under `ledgers/eutxo`.
- One Studio recipe per product, one showcase quickstart flag or product chain, one docs-site page,
  and one ADR-039 role entry where a UI or service is deployed.
- The capability catalog gains one row per product bundle or service with ADR-022 classification;
  version one of each product is `preview` at most, and its documentation names its ADR-045 posture.

## 8. Open questions for discussion

1. **Explorer: product or shared infrastructure?** ADR-032 describes components; §4.5 proposes a
   product with a UI. If it is infrastructure, its modules move beside the EUTxO indexer core and
   the DPP portal and registry lookup become its only UIs.
2. **Evidence: promote or fold?** Evidence Desk is the cheapest visible win, but DPP's publication
   path is the same machinery. Ship the desk now, or reserve the UI effort for the DPP starter?
3. **Trust registry timing.** Its chain is config-only today, but the governed authorization it
   relies on is not fully qualified. Start the non-consensus service and UI against the showcase
   chain now, or wait for ADR-025.2 closure?
4. **DPP starter now?** A clearly labelled prototype on the authenticated map could ship in wave 2
   as a demo. ADR-026 permits it but warns it must not be presented as the product. Is the
   marketing value worth the risk of being mistaken for one?
5. **Naming.** "Attest" versus "notary"; "trust registry" versus "status registry". Names become
   plugin and recipe identifiers and are hard to change.
6. **One UI shell or several?** ADR-040 rejects one monolithic Yano X console and one UI per plugin.
   A shared component library and connection module across product UIs is compatible with it; a
   single shell that hosts every product is not. Confirm the boundary before wave 1 builds two UIs.
7. **Console page or separate UI for Cardano History?** ADR-035's amendment gave it a native
   console page; ADR-040 anticipates a separate UI. New products follow ADR-040; decide whether
   Cardano History migrates in wave 2 so the portfolio has one pattern.
8. **Who owns the Cardano publication executor?** The feed and the optional DPP per-product
   publication both need it; it is payment-grade code. Yano core, Yano X, or a joint ADR?

## 9. Consequences

**Positive.** Two installable products in the next release without consensus changes; the governed
authenticated map, domain actors, and effects each gain a product with a UI; DPP arrives as a
composition of shipped pieces instead of a monolith; every product carries an offline verifier.

**Costs and risks.** Six UIs is a maintenance surface; the shared UI library in question 6 is the
mitigation. Each product adds catalog rows, docs, recipes, and deployment roles that must stay in
lockstep with releases. Products advertised before their gates close would contradict ADR-045; the
posture line in each product page is the control.

**Not decided here.** Wire formats, identifiers, module names, and UI frameworks. Each selected
product receives its own ADR with acceptance criteria before code is written.
