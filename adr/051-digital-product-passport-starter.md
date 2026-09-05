# ADR-051: Digital Product Passport Starter

- **Status:** Accepted (2026-09-06), implemented on `feat/products-oob`; §11 records the
  implementation and its deviations
- **Classification:** `REFERENCE`, labelled a prototype (ADR-046 §4.4 stage 1). It is not the DPP
  product ADR-026 §5.2 describes, it enforces no DPP lifecycle rule on chain, and it claims
  conformance with no DPP standard.
- **Scope:** `products/dpp/{profile,client,cli,ui,harness}`; a config-only chain on the stock
  governed `authenticated-map`, no consensus, contract, or showcase-identity change
- **Relates to:** ADR-046 §4.4, Yano ADR app-layer/026 (the design this starter previews) and
  app-layer/025 (the map and commitment profiles it runs on), ADR-049 (the map client, signer,
  verifier, and actor descriptor it reuses), ADR-047 (trust input and trust levels), ADR-048
  (actor statements and the approval round), ADR-050 (block-level browsing of the same chain)

## 1. Context

Yano ADR app-layer/026 decided what a DPP registry on Yano is: one governed chain per trust
domain, the finalized ledger for order, the authenticated map for current records, hashes and
bounded references instead of documents, domain-signed commands, aggregate anchoring, and a
proof-aware projection tier for scans. It also decided that the complete product is a custom
manifested provider (`dpp-registry`, §5.2) whose implementation waits for ADR-025 qualification,
and that in the meantime "a prototype may select `authenticated-map` and declare DPP-shaped
collections" (§5.1) as long as it is not presented as the product (§2, decision 3). ADR-046 §4.4
sequences exactly that prototype as the first stage of the DPP product and names its content:
authenticated-map collections for products, passport versions, claims, and issuer keys; role
approvals for certification; the evidence publication path for documents.

The gates ADR-046 lists for the product as a whole (ADR-025 qualification complete, ADR-026
accepted) are not met: ADR-025 is "accepted for phased implementation ... not production
qualification", ADR-026 is "proposed, implementation-deferred". This ADR therefore builds only
the stage-1 prototype the two ADRs explicitly permit, labels it as such everywhere a reader can
see it (module names, CLI banner, console, docs-site posture line), and records in §8 what the
gated stage owns. Nothing here freezes a DPP module, machine id, command id, or wire codec of the
full provider (ADR-026 §2, decision 18); the starter's own identifiers carry `starter` in their
names so they cannot be mistaken for it.

What the platform already provides, found while designing:

- The stock governed map gives every on-chain property the starter needs: per-collection
  authorization, canonical CBOR values checked by genesis-bound schemas, one-use direct-role
  authorizations recorded as consumptions, approval-gated collections consumed atomically with a
  terminal proposal, tombstones, receipts keyed by message id, and exact, absence, and tombstone
  proofs at any retained height.
- ADR-049 shipped a collection-generic map client: `TrustRegistryClient` reads any collection's
  entry with its receipt and, for governed writes, the actor, organization, policy, and
  consumption records under one root; `TrustRegistrySigner` builds governed-role and
  approval-routed commands and signed actor statements; `TrustRegistryVerifier` verifies an
  answer under the ADR-047 trust input; the ADR-049 cluster test proves the approval route end
  to end (propose, two approvals, map command with the approval reference). Its actor descriptor
  (organizations, actors with keys and key proofs, the administrator authority) is a governed map
  descriptor with two registry-specific lists that may stay empty.
- The map has no enumeration query, so "all claims of product P" is a projection replayed from
  finalized commands and confirmed through receipts (ADR-049 §2.3), after which each key is
  answered with an exact proof.
- Immutability of a passport version cannot be enforced configuration-only: a governed writer
  can `PUT` over an existing key. The entry's revision shows it, which is the honest thing a
  prototype can do (ADR-026 §5.1: it "does not prove that clients obeyed DPP lifecycle ...
  rules").
- ADR-026 §4.2 places a "DPP gateway" between enterprise systems and the chain: canonicalize,
  authorize, sign, stage content, submit. The starter's operator write path is that gateway in
  miniature, which keeps actor seeds out of the browser and defers browser signing (§8).

## 2. Decision

Build `products/dpp` as four Gradle modules, a launcher, and a guide. Version one is scoped so
that every journey it claims runs end to end in unit tests, in an in-process cluster test, and on
a live three-member chain started by the launcher.

### 2.1 Profile (`products/dpp/profile`, library and generator)

`DppStarterProfile` (`dpp-starter-v1`) fixes five collections on the governed map. Every value
is canonical CBOR checked by a genesis-bound schema; no collection allows restore.

| Collection | Authorization | Policy | Key (≤ bytes) | Value (canonical CBOR) |
|---|---|---|---|---|
| `products` | `governed-role` | `manufacturer-write` (role `manufacturer`) | product id, 64 | `[1, manufacturerOrganizationId, status, currentVersion, successorProductId, passportProfileId]` |
| `product-versions` | `governed-role` | `manufacturer-write` | `<productId>/<version>`, 128 | `[1, documentSha256, mediaType, reference, byteLength]` |
| `claims` | `governed-role` | `claim-issuer-write` (role `claim-issuer`) | `<productId>/<claimType>/<claimId>`, 128 | `[1, visibility, value, issuerOrganizationId, validFromHeight, validUntilHeight, evidenceSha256]` |
| `events` | `governed-role` | `operator-write` (role `operator`) | `<productId>/<eventId>`, 128 | `[1, eventType, actorOrganizationId, observedAt, location, evidenceSha256, note]` |
| `certificates` | `approval` | `certification` (proposer `certifier`; clause `independent-auditors`: two `auditor` approvals from distinct organizations) | certificate id, 128 | `[1, productId, certificateType, issuerOrganizationId, evidenceSha256, validFromHeight, validUntilHeight]` |

Layout rules:

- Identifiers use ADR-049's `IDENTIFIER` alphabet (`[A-Za-z0-9._:~-]`), which excludes `/`, the
  key separator; a product id is at most 64 bytes, a claim type 24, a claim id 36, an event id 63,
  and a certificate id 128, so that every composite key stays within the map's 128-byte
  application-key bound. A GS1-identified product uses `gtin:<14 digits>`, optionally
  `gtin:<14 digits>:21:<serial>`; other schemes use `<scheme>:<id>`. The starter never mints
  identifiers.
- `status` is ADR-026 §7.3's set as an unsigned integer: `0 DRAFT`, `1 ACTIVE`, `2 INACTIVE`,
  `3 REPLACED` (with `successorProductId`), `4 RETIRED`; `REVOKED` is the map tombstone.
  `currentVersion` names the `product-versions` entry that is the passport's current document.
- A version record is ADR-026 §7.5's content record: the SHA-256 of the document bytes, its
  media type, a bounded reference (URL, CID, or object key, ≤ 512 bytes), and its length. The
  document itself never enters consensus state.
- `visibility` is `0 PUBLIC` (`value` is the claim's UTF-8 text, ≤ 4,096 bytes) or `1 COMMITTED`
  (`value` is the 32-byte commitment `SHA-256("yano-dpp-claim-commitment-v1" || salt32 ||
  utf8(text))`; the salt and text stay with the issuer and are handed to a verifier as a
  `dpp-disclosure-v1` document). `evidenceSha256` is empty or 32 bytes.
- `eventType` is free text of at most 32 bytes; the console and CLI know `MANUFACTURED`,
  `SHIPPED`, `RECEIVED`, `INSPECTED`, `REPAIRED`, `RECYCLED`. `observedAt` is the signed business
  time in epoch seconds; it is never compared with a clock on chain. Ordering is the ledger's
  (block height, then position), never `observedAt` (ADR-026 §3.4).
- Heights are unsigned; `validUntilHeight` `0` means open-ended.

Roles: `manufacturer`, `operator` (any lifecycle party: logistics, repairer, recycler),
`claim-issuer`, `certifier`, `auditor`, and `dpp-admin` (the administrator authority). Direct
authorizations live at most 100 blocks; a certification round at most 600.

`DppGenesis` builds the map genesis with the stock `AuthenticatedMapGenesisFactory.mpf` from the
ADR-049 actor descriptor (organizations, actors with roles and key proofs, the authority; its
`issuers` and `schemas` lists are unused here) and emits the same four `yano.app-chain.chains[N].`
properties ADR-049 emits. The one change outside `products/dpp` is a public overload in the
trust registry profile, `TrustRegistryGenesis.governedGenesis(descriptor, directPolicies,
approvalPolicies)`, so the two products share one descriptor parser and one governed-genesis
builder; the existing call sites keep their behaviour. The same descriptor always yields the same
genesis id. The `--demo` descriptor holds `acme-manufacturing` (`maker-a`: `manufacturer`,
`operator`), `swift-logistics` (`logistics-a`: `operator`), `green-labs` (`issuer-a`:
`claim-issuer`), `cert-body-a` (`certifier-a`: `certifier`; `auditor-a`: `auditor`),
`audit-guild-b` (`auditor-b`: `auditor`), and `dpp-consortium` (`dpp-admin-a`: `dpp-admin`),
with deterministic demo seeds `sha256("yano-dpp-starter-demo-actor:" + actorId)`, showcase-only
material. Actor keys for a real deployment come from `yano-dpp actor-key`, seeds never leaving the
actor's machine.

### 2.2 Client (`products/dpp/client`)

`DppClient` wraps a `TrustRegistryClient` pinned to one chain (§3) and adds the DPP shape:

- Writes: `registerProduct`, `publishVersion`, `putClaim`, `appendEvent`, `setStatus`, and
  `revokeProduct` build the profile's mutations and submit them through
  `TrustRegistrySigner.governedCommand` as the named actor (one-use authorization, `tip +
  min(policy lifetime, 100)` expiry), then await the receipt. Registration and a version record
  use `PUT_IF_ABSENT`; `publishVersion` and `setStatus` update the product entry with
  `COMPARE_AND_SET` on the revision they read (ADR-026 §7.2), so a concurrent rewrite is
  rejected by the map instead of silently winning; `revokeProduct` issues the `REVOKE` mutation
  (ADR-026's `passport.revoke`), leaving the tombstone every later answer shows. Certification is the approval round of ADR-049 §7: `proposeCertification` builds the
  approval-routed action for a `PUT` (or, for revocation, a `REVOKE`) on `certificates`, signs
  the certifier's `PROPOSE` statement, and returns a `dpp-certification-request-v1` document
  (chain, genesis id, policy id and revision, proposal id, action bytes, payload hash, deadline);
  `approveCertification` and `rejectCertification` sign an auditor's `APPROVE` or `REJECT` on
  that request; `applyCertification` submits the map command carrying the approval reference and
  awaits the receipt, which the map rejects unless the proposal is terminal and its payload hash
  binds exactly this action.
- Reads: `passport(productId, height)` assembles a snapshot at one height. The projection
  (`PassportProjection`) replays finalized map commands from height 1 through the block endpoint,
  confirms each through its receipt, and keeps, per product, the keys touched in each collection
  and the ledger-ordered list of applied mutations (height, block position, message id,
  collection, key, operation). Every key the projection names is then answered with
  `TrustRegistryClient.answer` at the snapshot height, so the passport is a set of proof-bound
  answers over one root: the product entry, every version, claim, event, and certificate
  (tombstones included, which is how a revoked claim or certificate is shown), and, for governed
  writes, who wrote each one. A certificate answer additionally carries the state proof of the
  proposal's one-use approval consumption (`approvalConsumptionKey(proposalId)`, the proposal id
  read from the applied command's approval reference), so the passport proves that the entry was
  applied under the approval-gated collection and that its proposal was consumed exactly once;
  the decisions themselves are Evidence Desk's to show (ADR-049 §8 defers the `APPROVAL` proof
  bundle). The projection remembers which product each certificate id belongs to from its `PUT`,
  since a later `REVOKE` carries no value. It is incremental and bounded (5,000 commands per
  assembly); it is a cache, never a source of truth.
- `Passport` derives what a consumer wants to see without adding trust: identity and status,
  the current version and whether its document is available and hash-verified, public claims and
  committed claims (commitment shown, value withheld), the timeline in ledger order, active and
  revoked certificates, and flags the prototype cannot prevent but can expose: a version entry
  with revision above 1 (`REWRITTEN`), a `currentVersion` with no version entry (`DANGLING`), a
  claim or certificate whose validity heights exclude the snapshot height (`EXPIRED` or
  `NOT_YET_VALID`), and a product, version, or claim whose `DIRECT_ROLE` provenance names an
  organization other than the one the record itself names (`FOREIGN_WRITER`): under a
  config-only profile any `manufacturer` can write any product, and the passport says so.
- `PassportBundle` is the `dpp-passport-v1` document: chain, profile, genesis id, height, root,
  block hash, product id, the answers (each an ADR-049 `trust-registry-answer-v1` document), and
  the timeline. `PassportVerifier.verify(bundle, AttestTrust)` verifies every answer with
  `TrustRegistryVerifier` under the trust input, requires that all answers name the bundle's
  chain, genesis, height, root, and block, that every answered key belongs to the bundle's
  product, that the timeline's heights agree with the answered entries' creation and mutation
  heights, and reports the lowest trust level reached; a bundle with any inconsistent answer is
  invalid.
- `Disclosure` recomputes a committed claim from a `dpp-disclosure-v1` document (product, claim
  type and id, salt, text) and compares it with the claim answer in a verified passport bundle.
- `DocumentStore` keeps published documents under a content directory keyed by SHA-256 (the
  ADR-050 archiver rule: a body whose hash differs from the committed digest is refused). The
  publication path is: stage the document (hash, length, media type), commit the version record,
  serve the bytes by hash; the guide points at the explorer for the same bytes browsed from the
  block side.

### 2.3 CLI (`yano-dpp`, `products/dpp/cli`)

| Command | What it does |
|---|---|
| `genesis` | `--demo` or `--descriptor <json>`, `--members`, `--threshold`, `--chain-index`: prints the four node properties |
| `descriptor` | `--demo` prints the demo descriptor to start an operator's own from |
| `actor-key` | `--actor --seed-file [--chain --key-id]` prints an actor's public key and key proof |
| `register` | `--product --manufacturer-org --profile [--status]` as a `manufacturer` |
| `publish-version` | `--product --version --document <file> [--media-type --reference]`: stages the document, commits the version record, and advances `currentVersion` in one batch |
| `set-status` | `--product --status ACTIVE|INACTIVE|REPLACED|RETIRED [--successor]` |
| `revoke` | `--product --revision`: the `REVOKE` mutation on the product entry |
| `claim` | `--product --type --id --text [--committed] [--valid-from --valid-until --evidence <file>]` as a `claim-issuer`; `--committed` writes the commitment and writes the disclosure document to `--disclosure-output` |
| `event` | `--product --type --observed-at --location [--note --evidence <file>]` as an `operator`; the event id is `--id` or derived from the observed time and a random suffix |
| `certify propose` | `--product --certificate --type --evidence <file> [--valid-from --valid-until]` as a `certifier`, or `--revoke --certificate` (the current revision is read from the chain); writes the request document to `--output` |
| `certify approve`, `certify reject` | `--request <json>` as an `auditor` |
| `certify apply` | `--request <json>`; submits the approval-referencing map command, prints the receipt |
| `passport` | `--product [--height] [--output <json>]`; assembles, verifies with the trust input given (`--members`, `--anchor-datum-hex`, or bundle-declared), prints the passport view, exits with the codes below |
| `verify` | `--passport <json>` offline with `--members` or `--anchor-datum-hex` |
| `disclose` | `--passport <json> --disclosure <json>`: verifies the passport and checks the commitment |
| `document` | `--sha256 <hex> --output <file>` from the content directory |
| `serve` | the public portal (§2.4) |
| `gateway` | the operator gateway (§2.4) |

Every write takes `--actor` and `--seed-file` (owner-only; the file is never echoed) and
`--genesis-id` for the first write on a chain that has no block yet. Exit codes follow ADR-047
and ADR-049: 0 verified with an independent anchor (or a write applied), 2 usage, 3 unavailable,
4 invalid (a rejected receipt included), 5 verified with caller-pinned members, 6 consistent
without caller trust.

### 2.4 Services

**Portal** (`yano-dpp serve`): read-only, public, beside a node. `GET /passports/{productId}
[?height=]` returns the passport view with its answers; `GET /passports/{productId}/proof` the
`dpp-passport-v1` bundle; `GET /01/{gtin}[/21/{serial}]` resolves a GS1 Digital Link path to
the product id (the GTIN zero-padded to 14 digits, `gtin:<14 digits>[:21:<serial>]`) and returns
the same view (a browser that scanned a label lands here; the console's Passport view accepts
the same path); `GET /documents/{sha256}` serves an archived
document with its hash in `X-Content-Sha256`; `GET /healthz` reports the chain and the replayed
height. Responses carry `Access-Control-Allow-Origin: *`; nothing is written and no seed is
loaded.

**Operator gateway** (`yano-dpp gateway`): the ADR-026 §4.2 gateway in miniature, for the
operator console. It binds to `127.0.0.1` by default, loads the seeds of the actors it is
started with from an owner-only directory (`<actorId>.seed`), generates a random bearer token
at start (written to an owner-only file, required in `X-Gateway-Token` on every request), and
exposes `GET /operator/actors` (the actors it can sign for, their roles) and JSON `POST` routes
that mirror the CLI writes one to one: `/operator/products`, `/operator/versions` (the document
as base64 in the JSON body), `/operator/status`, `/operator/revoke`, `/operator/claims`,
`/operator/events`, `/operator/certifications/propose|approve|reject|apply`. Every response returns the message id
and the receipt (or the request document for a proposal). The gateway signs only what an
authenticated operator asked it to sign and never returns a seed.

### 2.5 Console (`products/dpp/ui`)

A static SvelteKit site on the ADR-049 scaffold with three views:

- **Passport** (consumer and regulator): reads only the public portal named by the runtime
  config's `serviceUrl` (`/passports/{id}/proof`, `/documents/{sha}`); it holds no node API key
  and no secret, because a consumer who scanned a label has neither. Enter a product id or paste
  a Digital Link (`.../01/<gtin>`), or arrive on `#/01/<gtin>`; the view shows identity and status, the current
  document with its availability (`FINALIZED` when only the record exists, `CONTENT_VERIFIED`
  when the portal serves bytes matching the digest), public claims, committed claims with a
  "check a disclosure" control that recomputes the commitment in the browser from a pasted
  `dpp-disclosure-v1` document, the ledger-ordered timeline, certificates with their approval
  consumption proven, and one proof row per fact showing presence, provenance, height, root binding, and
  the certificate signature count; "download passport bundle" exports what `yano-dpp verify`
  accepts. The browser checks, on the bundle it fetched, that every fact names one chain,
  genesis, height, root, and block and that every key belongs to the product; MPF paths and
  finality are verified by the CLI on the export.
- **Operator**: connect to a gateway (URL and token, kept in memory), pick an actor, and run the
  forms: register, publish a version (file picker, hash shown before submit), set status, attach
  a claim (public or committed; the disclosure document is offered for download), append an
  event, and the certification round (propose, approve, apply) with the request document passed
  between roles; every result shows the message id and the receipt. The view shows the Digital
  Link a label would encode. The gateway token is the only secret the console ever holds.
- **About**: what a passport proves and does not prove (§4), and the prototype posture.

### 2.6 Launcher (`products/dpp/harness/dpp.sh`)

On the packaged cluster launcher, as ADR-049's launcher: `up` derives the demo member keys,
generates the demo genesis with `yano-dpp genesis --demo`, writes a home with a single
`dpp-starter-chain` (`authenticated-map`, governed membership, HTTP base 7470, server base 9470
by default) with per-node overlays carrying the four genesis properties and disabling the L1
history projection, and starts three members; `seeds` writes the demo seeds; `portal` and
`gateway` start the two services (8580 and 8590 by default) with the instance's node, API key,
content directory, and seeds; `demo` runs the whole journey through the CLI (register, publish a
version, a public and a committed claim, three lifecycle events, and a certification round with
two auditors) so the portal has a passport to show; `env`, `status`, `stop`, and `clean`
complete it.

## 3. Discovery and data access

A chain is eligible when its status manifest names `authenticated-map` as the application and
the genesis returned by `capabilities-v1` declares the five profile collections under the
profile's policy ids with the `certification` approval policy present. Reads use the ADR-049
endpoints only: `capabilities-v1`, `/state/proof/{keyHex}?height=`, `/blocks/{height}`,
`/blocks?from=&limit=`, `receipt-v1`, `/evidence/{messageId}`, `/state/identity`. Physical keys
are derived locally with `CompositeCommitmentV1.componentKey` and compared with the key each
envelope returns.

## 4. Proof story

A passport says what the consortium agreed about product P at height H: that these records
existed with these revisions under this root, which governed actor wrote each one under which
policy and key, that a certificate was applied under the approval-gated collection with its
proposal's one-use consumption proven (the approvals themselves are Evidence Desk's to show),
that a claim's commitment is what the issuer disclosed, that
the root was certified by the pinned members, and, when anchored, that Cardano carries it. Each
fact is shown on its own row, per ADR-026 §1.4; the console and CLI never merge them into one
badge. It never says that a physical event occurred, that a measurement is true, that an issuer
is accredited beyond this chain's genesis (that is the trust registry's question, ADR-046 §4.4),
that a document remains available, or that anything conforms to a DPP standard.

## 5. Determinism firewall

Nothing in this product executes on chain except genesis construction with the stock contracts
and validators. `profile`, `client`, and `cli` carry no JSON-LD, RDF, or EPCIS processor; a unit
test in each module asserts that the runtime classpath holds no artifact whose name contains
`jsonld`, `json-ld`, `rdf4j`, `jena`, or `titanium` (ADR-049 §5).

## 6. Security and privacy

- Seeds are read from owner-only files (CLI) or an owner-only directory (gateway), never logged
  or echoed. The gateway binds to loopback by default, requires its token on every request,
  answers `OPTIONS` preflights for the console's origin, and refuses to start on a non-loopback
  bind without `--allow-remote`.
- Direct-role authorizations are one-use, chain- and genesis-scoped, and expire within 100
  blocks; a certification proposal expires within 600.
- On-chain values are identifiers, hashes, commitments, and bounded references. The profile's
  documentation and the console's forms say that no personal data and no document belongs on
  chain. Committed claims hide the value from every member; the disclosure document is
  distributed by the issuer, out of band.
- Documents are stored by hash outside consensus; a body is stored only when its hash equals a
  committed digest. The portal serves read routes only, with bounded response sizes and a bounded
  replay.
- ADR-040 §7 applies to the console; the Passport view holds no secret, and the Operator view
  keeps the gateway token in memory only.

## 7. Testing

- Unit (`profile`): genesis determinism (same descriptor, same id; demo round-trips; a foreign
  descriptor is refused), collection and policy matching, value codecs and schema acceptance and
  rejection (a version record with a 31-byte digest, a claim with visibility 2), key parsing,
  commitment vector, firewall. Unit (`client`): passport bundle and request document round-trips,
  the derived flags on synthetic answers, disclosure check. Unit (`cli`): usage, argument
  handling, exit codes.
- Cluster (`client` tests): an in-process three-member `authenticated-map` cluster with the demo
  genesis behind the ADR-049 REST bridge. `maker-a` registers `gtin:09506000134352` and publishes
  version 1 from a document; `issuer-a` attaches a public claim and a committed claim;
  `maker-a` and `logistics-a` append events; `certifier-a` proposes a certificate, `auditor-a`
  and `auditor-b` approve, the command is applied; `maker-a` rewrites version 1 with a deliberate raw
  `PUT` (`REWRITTEN`) and publishes version 2; a stale `COMPARE_AND_SET` is rejected; the
  certificate is revoked through a second round; the product is revoked last and the tombstone
  answers. The passport at the tip
  verifies `CALLER_PINNED_ROOT` under the members and `INTERNAL_CONSISTENCY_ONLY` bundle-declared;
  a wrong member set fails; a tampered answer fails; the disclosure matches and a wrong salt does
  not; the portal routes (passport, proof, Digital Link, document, 404s, GET-only) and the gateway
  routes (token required, every write, the certification round) are exercised over HTTP. The test
  writes the goldens (passport bundle, members file, disclosure) the CLI and console tests pin.
- Live: the launcher on the Yano X distribution; the guide's walkthrough runs `demo`, the CLI,
  the portal, the gateway, and the console (headless browser) against it.

## 8. Deferred

- The full provider of ADR-026 §5.2 (`dpp-core-v1`, `role-approvals`, `dpp-publication-v1`,
  named workflows, enforced lifecycle and sequence rules, product heads, recovery claims,
  policies): gated on ADR-025 qualification and ADR-026 acceptance.
- Browser signing of map commands in the operator view (the ADR-048 §4 path, extended to
  `MapActorAuthorizationV1`); v1 signs in the gateway.
- Accreditation lookups against a trust registry chain from the passport view.
- An explorer module that decodes the starter's collections (the ADR-050 map module already
  lists the rows as `collection/keyHex`).
- Per-product Cardano publication (CIP-68 locator assets), IPFS or S3 document stores, Kafka
  and webhook effects, GS1 resolver conformance, QR rendering, and ZK selective disclosure.
- Showcase catalog integration; a Studio recipe.

## 9. Acceptance criteria

1. `DppGenesis` yields the same genesis id for the same descriptor, and a node started with its
   four properties finalizes governed writes from the demo actors.
2. Every collection accepts its documented value and rejects a malformed one before finality.
3. The certification round succeeds only through the approval route with two auditors from
   distinct organizations; the map rejects the command without it.
4. `yano-dpp passport` verifies at exit 5 under the members file and exit 6 bundle-declared, and
   `yano-dpp verify` reproduces the result offline from the exported bundle; a tampered bundle
   exits 4.
5. A committed claim's disclosure verifies in the CLI and in the browser; a wrong salt fails.
6. The portal serves a passport, its proof, a Digital Link path, and a hash-verified document;
   the gateway performs every write from the console with the receipt shown.
7. The launcher brings up a live chain, `demo` populates it, and the console journey passes in
   a headless browser.
8. The distribution carries `tools/yano-dpp`, `product-ui/dpp`, and `examples/dpp`; the
   distribution gate passes; core is unchanged.

## 10. Consequences

**Positive.** The DPP portfolio entry arrives as a composition of shipped pieces (the governed
map, the ADR-049 client and signer, the ADR-047 trust input, the ADR-048 approval round) with a
consumer portal, an operator path, and offline verification, without a line of consensus code.
Its findings feed the full provider's ADR.

**Costs and risks.** The prototype label must survive contact with marketing: every surface says
"starter" and "prototype", and the docs-site posture line names the gate. The gateway holds
seeds for the operator; the guide says whose machine it belongs on. Rewrites and dangling
versions are exposed, not prevented.

## 11. Implementation record

**Gates.** `:products:dpp:profile:test` (11 tests: genesis determinism and the registry
descriptor refusal, schema acceptance and rejection, value codecs and canonical-form refusal,
key bounds, Digital Link mapping, commitment vector, firewall), `:products:dpp:client:test`
(12: codec round trips, the projection, the document store, the firewall, and the cluster test
on a real three-member governed map with the demo genesis: the whole §7 journey including the
premature apply rejected `APPROVAL_NOT_APPROVED`, the stale compare-and-set rejected, the raw
rewrite flagged `REWRITTEN`, certificate and product revocation, passports verified
`CALLER_PINNED_ROOT` and `INTERNAL_CONSISTENCY_ONLY`, wrong members, a forged version, a
relabelled product, a certificate stripped of its approval consumption, the disclosure in every
outcome, historical passports, the portal and gateway routes), `:products:dpp:cli:test` (6:
usage, genesis, actor keys, the golden passport at exit 6 and 5, the disclosure, tampering, a
tampered certification request refused before signing, firewall), the console (`npm run check`
0 errors, `npm test` 14, `npm run build`), `verifyArtifactInventory`, `verifyJvmOnlyBuild`, and
`verifyYanoXJvmDistribution` pass. The cluster test writes the goldens (`golden-passport.json`,
`golden-members.json`, `golden-disclosure.json`, `golden-certification-request.json`) the CLI
and console tests pin.

**Live (pre14, the launcher on the showcase's Yano home).** `dpp.sh up` started three members
(threshold 2, HTTP 7470); `dpp.sh demo` drove the journey through the CLI in twelve blocks
(register, version with `currentVersion` advanced in the same batch, status, a public and a
committed claim, three events, propose, two approvals, apply); `yano-dpp passport` verified
`CALLER_PINNED_ROOT` (exit 5) and the export re-verified offline at exit 5 under the members
file and 6 bundle-declared; `yano-dpp disclose` answered `MATCH`. The portal resolved
`/01/9506000134352` to the passport, served the bundle (verified at exit 5) and the version-1
document as `CONTENT_VERIFIED`; the gateway refused a request without its token (401), listed
the seven demo actors with their chain roles, and applied an event. The console, served as a
static site in headless Chrome, opened the passport from a Digital Link (`ACTIVE`, proofs
`BOUND`, nine proof rows `BOUND`, approval consumption proven), checked the disclosure (`MATCH`,
and `MISMATCH` with a wrong salt), downloaded a bundle the CLI verified at exit 5, connected the
Operator view to the gateway (seven actors), appended a `REPAIRED` event as `logistics-a`
(`APPLIED`), and showed it on the reopened passport.

**Deviations from the text above.**

| Section | Deviation |
|---|---|
| §2.1 | Product ids are bounded to 64 bytes (claim types 24, claim ids 36, event ids 63) so composite keys fit the map's 128-byte key; the table and bullet now say so. |
| §2.1 | The change outside `products/dpp` is two additive methods in the trust registry: `TrustRegistryGenesis.governedGenesis(descriptor, directPolicies, approvalPolicies)` and `TrustRegistryClient.approvalPolicy(policyId)` (the certification round reads the policy's current revision and lifetime from the chain). |
| §2.2 | A passport embeds the evidence bundle of the answer block once per answer, as the ADR-049 answer format does; the demo passport of nine answers is about 300 KiB. Hoisting the evidence to the bundle level is a follow-up. |
| §2.3 | `set-status` refuses `DRAFT` (a registered product does not return to draft). `claim`, `event`, and `certify propose` derive the organization from the actor's chain record when `--org` is not given. Proposal ids are `cert-<8 random bytes>` because the role workflow requires lowercase identifiers. |
| §2.4 | The gateway's `/operator/actors` reads each actor's organization and roles from the chain once it has a block; `/operator/claims` returns the disclosure document once in its response. `dpp.sh demo` is the launcher's journey (the ADR listed `demo` under §2.6, implemented as described). |
| §2.5 | The Passport view keeps the passport's location in the URL hash (`#/01/<gtin>` or `#/passports/<id>`) so a scanned link opens it directly. |

**Findings.** The approval route needs no product-specific code: the ADR-049 signer's
`approvalAction`, `approvalPayloadHash`, `signedStatement`, and `approvalCommand` carry the
starter's certification round unchanged, and the map rejects a premature apply with
`APPROVAL_NOT_APPROVED` and a replay with `APPROVAL_REPLAY`. The approval consumption record
(`approvalConsumptionKey(proposalId)`) is present under the same root as the entry and binds the
applied message id and action commitment, which is what lets a certificate answer prove its
round was consumed once without the `APPROVAL` proof bundle ADR-049 deferred. Nothing in core
changed.
