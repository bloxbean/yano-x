# ADR-049: Trust and Status Registry

- **Status:** Accepted (2026-09-06), implemented on `feat/products-oob`; §11 records the
  implementation and its deviations
- **Scope:** `products/trust-registry/{profile,client,cli,ui,harness}`; a config-only chain on the
  stock `authenticated-map` composite, no consensus, contract, or showcase-identity change
- **Relates to:** ADR-046 §4.2, Yano ADR app-layer/029 (the design this ADR executes), ADR-047
  (trust input and trust levels, reused), ADR-048 (proof-bound UI conventions),
  `docs/appchain/state-machines/authenticated-map.md`

## 1. Context

Yano ADR app-layer/029 decided what a trust-and-status registry on Yano is: a governed
authenticated map whose entries are subjects, status, status-list versions, issuers, and schemas;
revocation leaves tombstones; inclusion, exclusion, and point-in-time answers are proofs against
threshold-certified roots; and a thin off-chain skin serves the standards the ecosystem already
consumes, W3C Bitstring Status List and ToIP TRQP, without ever putting JSON-LD or RDF on the
consensus path. ADR-046 §4.2 sequences it as the first product on the governed authenticated map
and the identity layer the DPP product needs.

The `authenticated-map` state machine already provides every on-chain property the design needs:
per-collection authorization (`open`, `owner`, `member`, `governed-role`, `approval`), canonical
CBOR values with genesis-bound schema validators, `COMPARE_AND_SET`, `REVOKE` tombstones with
`restoreAllowed`, one-use actor authorizations recorded as direct consumptions, receipts keyed by
message id, and exact, absence, and tombstone answers through the point query and the state proof
endpoint at any retained height. The SDK ships `AuthenticatedMapProofBundle`, which verifies an
entry together with its receipt, its direct consumption, and the actor, organization, and policy
records that authorized it, all under one root. What is missing is the product: the genesis that
makes a registry, the client and CLI that answer status questions with those proofs, the service
that speaks the standards, a console, and a way to run it.

Two facts found during design bound version one:

- The map has no enumeration query. Every read surface is exact-key. A status list therefore
  cannot be "assembled from the chain's `status` entries" by lookup; it is a projection replayed
  from finalized commands (§2.3).
- The `issuers` collection is approval-gated. Nothing in the v1 CLI writes it; issuers are seeded
  at genesis, and onboarding after genesis uses the stock role-workflow and map CLIs (§2.1).

## 2. Decision

Build `products/trust-registry` as four Gradle modules, a launcher, and a guide. Version one is
scoped so that every journey it claims runs end to end in unit tests, in an in-process cluster
test, and on a live three-member cluster started by the launcher; the pieces ADR-029 marks
strategic are deferred (§8).

### 2.1 Profile (`products/trust-registry/profile`, library and generator)

`TrustRegistryProfile` fixes the ADR-029 §5.2 collections. Every value is canonical CBOR checked
by a genesis-bound declarative schema, so a malformed value is filtered before finalization:

| Collection | Authorization | Policy | Key (≤ bytes) | Value (canonical CBOR) |
|---|---|---|---|---|
| `subjects` | `governed-role` | `registrar-write` (role `registrar`) | subject id, 128 | `[1, controllerOrganizationId, kind, metadataHash32]` |
| `status` | `governed-role` | `issuer-write` (role `issuer`) | `<listId>/<index>`, 128 | `[1, bit, reasonCode]`, `bit` ∈ {0, 1} |
| `status-lists` | `governed-role` | `issuer-write` | list id, 64 | `[1, purpose, bitLength, listSha256, publishedHeight]` |
| `issuers` | `approval` | `issuer-onboarding` (proposer `registrar`; two `registrar` approvals from distinct organizations) | entity id, 128 | `[1, framework, [authorization...], validFromHeight, validUntilHeight]` |
| `schemas` | `governed-role` | `registrar-write` | schema id, 64 | opaque bytes, ≤ 64 KiB |

No collection allows restore: a revoked subject, status entry, or issuer is terminal, as ADR-029
requires. A tombstoned `status` entry counts as bit 1 in every projection (§2.3); revocation of a
status index is terminal even for a `suspension` list. Identifiers are hashes or opaque ids; the
profile documents that no personal data belongs on chain (ADR-029 §6). `bitLength` is at least
131,072 (the Bitstring Status List minimum) and at most 1,048,576.

`TrustRegistryGenesis` builds the map genesis with the stock `AuthenticatedMapGenesisFactory.mpf`
from a JSON descriptor (organizations; actors with their Ed25519 public keys, roles, and key
proofs; the administrator authority; initial `issuers` and `schemas` entries) and emits the same
four properties the showcase's map generator emits (`state.commitment-profile`,
`state.format-fingerprint`, `state.genesis-id`, `machines.authenticated-map.genesis-cbor-hex`)
under `yano.app-chain.chains[N].`. The same descriptor always yields the same genesis id. A
`--demo` descriptor produces the demo set (`registry-admin-a`, `registrar-a` in
`registry-operator`; `registrar-b` in `registrar-guild-b`; `issuer-a` in `issuer-org-a`) with the
deterministic demo seeds `sha256("yano-trust-registry-demo-actor:" + actorId)`, showcase-only
material, and seeds `issuer-a` as a genesis `issuers` entry so the TRQP journey works without an
onboarding round.

Because the descriptor takes public keys and key proofs, an operator's real actors are created
with `yano-trust genesis actor-key` from a seed file that never leaves the operator's machine.

### 2.2 Client and CLI (`products/trust-registry/client`, `products/trust-registry/cli`)

`TrustRegistryClient` reads a pinned node through the endpoints every Yano node exposes, using
the SDK `AppChainClient`:

- `POST .../query/authenticated-map/capabilities-v1` returns the genesis (CBOR), used for discovery
  (§3) and for the genesis id every proof and authorization is scoped to.
- `GET .../state/proof/{physicalKeyHex}?height=` returns the canonical leaf, the native proof,
  the certified block header, and the finality certificate; the entry itself is read from that
  proof, so historical answers need only proof retention. `GET .../blocks/{height}` returns the
  block's messages with their bodies; `GET .../blocks?from=&limit=` pages block summaries for
  the replay; `POST .../query/authenticated-map/receipt-v1` returns a command's receipt; `GET
  .../evidence/{messageId}` returns the evidence bundle carrying the certified block; `GET
  .../state/identity` returns the state commitment identity.
- Physical keys are the map's canonical key under the map component's composite namespace,
  derived locally with `CompositeCommitmentV1.componentKey`, never taken from a response.

`TrustRegistryVerifier` produces one `StatusAnswer`:

| Field | Meaning |
|---|---|
| `presence` | `ACTIVE`, `REVOKED` (tombstone that retains the last logical value hash), or `ABSENT` (exclusion) |
| `entry` | revision, controller, value, logical value hash, created and last-mutation heights |
| `provenance` | `GENESIS` (seeded at height 0, no receipt exists), `RECEIPT` (bound to the applied receipt of the command that produced this revision), or `DIRECT_ROLE` (additionally bound to the actor, organization, policy, and consumption records: who wrote it, under which key and policy revision) |
| `facts` | the state proofs the answer rests on, all at the answer height, in the SDK proof envelope format |
| `trustLevel` | ADR-047's `ProofLabVocabulary.TrustLevel`: `INTERNAL_CONSISTENCY_ONLY` (the proofs verify against the root the node served and the node's certificate is internally consistent), `CALLER_PINNED_ROOT` (the finality certificate verifies under a member set and threshold the caller pinned), or `INDEPENDENTLY_VERIFIED_L1_ANCHOR` (the answer height, root, members, threshold, and genesis match an `AnchorDatumV1` the caller read from Cardano) |

Finality is established as Attest establishes it: the answer carries the evidence bundle of a
message in the answer block, and the SDK `EvidenceVerifier` checks its finality certificate
under the trust input; the certified block's root is then the trusted root every state proof is
verified against. `RECEIPT` and `DIRECT_ROLE` answers go through the shipped
`AuthenticatedMapProofBundle` (`BASIC` kind: entry and receipt under one root); `DIRECT_ROLE`
answers additionally bind the consumption, actor, organization, and policy facts with the map
genesis id read from the proven genesis marker (§11 explains why the bundle's own `DIRECT_ROLE`
kind is not used); `ABSENT` and `GENESIS` answers are single proofs verified by the SDK
`ProofVerifier`. The trust input is ADR-047's `AttestTrust` (bundle-declared, caller-pinned
members file, or independent anchor datum), so the members file format and the anchor path are
shared with Attest rather than duplicated; the client depends on `products/attest/client` for
that type and nothing else. Entries written through the approval route receive `RECEIPT`
provenance in v1; assembling the `APPROVAL` bundle is deferred (§8).

An answer is serialized as JSON (`trust-registry-answer-v1`) so it can be exported by the CLI or
the console and verified offline.

`yano-trust` commands:

| Command | What it does |
|---|---|
| `genesis` | `--demo` or `--descriptor <json>`, `--members`, `--threshold`, `--chain-index`: prints the four node properties |
| `descriptor` | `--demo` prints the demo descriptor to start an operator's own from |
| `actor-key` | `--actor --seed-file [--chain --key-id]` prints an actor's public key and genesis key proof; the seed stays on the actor's machine |
| `status` | one entry (`--subject`, `--issuer`, `--list`, `--list --index`, `--schema`, or `--collection` with `--key`/`--key-hex`) at the tip or `--height`; verifies the answer with the trust input given (`--members`, `--anchor-datum-hex`, or the bundle's own signers) and exits with the codes below; optional `--output` of the answer document |
| `list` | replays the chain (§2.3) and prints a list's bitstring hash, the chain's `status-lists` entry and whether they agree, optional `--output` of the Bitstring document |
| `trqp` | `--entity --authorization --framework` at the tip or `--height`; prints the TRQP-shaped answer and verifies the issuer entry like `status` |
| `verify` | `--answer <json>` offline with `--members` or `--anchor-datum-hex` |
| `put`, `revoke` | governed-role writes signed with `--seed-file` as `--actor`: the stock action, direct-role authorization, and command from `AuthenticatedMapAuthorizationContract`, submitted and awaited; prints the receipt; `--genesis-id` for the first write on a chain without a block |
| `publish-list` | replays the chain at the tip, hashes the bitstring, writes `status-lists/<listId>` as the issuer |
| `serve` | the read-only service (§2.3) |

Exit codes follow ADR-047: 0 verified with an independent anchor (or a write applied), 2 usage,
3 unavailable, 4 invalid, 5 verified with caller-pinned members, 6 consistent without caller
trust.

### 2.3 Service (`yano-trust serve`)

A read-only HTTP service beside a node, configured with the node URL, chain id, an API key from a
file or the environment, and a bind address. It is a projection:

- It replays finalized `authenticated-map.command.v1` messages from height 1 through the block
  endpoint, decodes each command's action, confirms each through `receipt-v1` (a finalized
  command may have been rejected; a candidate-filtered command leaves no receipt), and applies
  the applied mutations of the `status` collection to an in-memory bitstring per list id: `PUT`,
  `PUT_IF_ABSENT`, and `COMPARE_AND_SET` set the bit from the value, `REVOKE` sets the bit to 1.
  The replay is incremental (new blocks only) and bounded (at most 5,000 commands per list scan).
- `GET /status-lists/{listId}` returns a `BitstringStatusListCredential`-shaped JSON document as of
  the list's `publishedHeight`, so its hash equals the hash the chain holds: `credentialSubject`
  with `type: BitstringStatusList`, `statusPurpose`, `statusSize: 1`, and `encodedList`, the
  multibase base64url (`u` prefix, no padding) of the GZIP of the bitstring. The list hash is
  SHA-256 of the raw, uncompressed bitstring; GZIP output is not canonical and is never hashed.
  An `x-yano` block names the chain, the replay height, the `status-lists` entry with its proof
  envelope, and whether the projection matched the chain's hash. `?height=` serves the projection
  as of a retained height (Bitstring Status List §3.2 point-in-time) with the list entry at that
  height, if any.
- `GET /trqp/entities/{entityId}/authorizations/{authorizationId}?framework=` answers the TRQP
  question from the `issuers` entry (TRQP-shaped; conformance with a specific TRQP revision is
  not asserted): `authorized`, the validity heights, and the answer envelope.
- `GET /entries/{collection}/{keyHex}?height=` returns the answer envelope.
- `GET /healthz` reports the chain and replay height.

`publish-list` derives the bitstring by the same replay code so issuer and service hash identical
bytes. The service never signs, never writes, and never processes JSON-LD; it is presentation over
proofs. Herd privacy holds because verifiers fetch whole lists.

### 2.4 Console (`products/trust-registry/ui`)

A static SvelteKit site on the Attest and Evidence Desk scaffold for verifiers and auditors:
connect and discover eligible chains (§3); look up a subject, status entry, issuer, or status list
with inclusion, tombstone, or exclusion shown separately and the proof binding shown on its own
row; ask "as of height"; check a served status list against the chain's list entry by decoding
`encodedList` and hashing the bitstring in the browser; export the answer for `yano-trust
verify`. Issuer writes stay with the CLI in v1 (§8). It is the smallest of the four modules; Yano
already ships a generic map console.

### 2.5 Launcher (`products/trust-registry/harness/registry.sh`)

The light showcase pins its chain set to exact permutations in its identity script, its catalog,
and its contract tests; adding an optional fourteenth chain would change all of them. Instead the
product ships a launcher on the packaged cluster launcher, as the evidence harness does:
`TRUST_REGISTRY_YANO_HOME` names an extracted Yano X JVM distribution (or the showcase's `yano`
directory); `registry.sh up` derives the launcher's demo member keys, generates the demo genesis
with `yano-trust genesis --demo`, writes a home with a single `trust-registry-chain`
(`state-machine: authenticated-map`, governed membership) and per-node overlays that carry the
four genesis properties and disable the devnet profile's L1 history projection, and starts three
nodes; `env`, `seeds`, `status`, `stop`, and `clean` complete it. Adding the chain to the showcase
catalog is a follow-up (§8).

## 3. Discovery and data access

A chain is eligible when its status manifest names `authenticated-map` as the application and
the genesis returned by `capabilities-v1` declares the five profile collections with the profile's
policy ids. Components answer queries only once the chain has finalized a block, so a fresh
registry is listed with its profile unverified until its first write; the stdlib domain API is
not relied on because it is mounted only where the stdlib bundle is. Reads are listed in §2.2;
every proof is requested by a locally derived physical key and compared to the key the envelope
returns.

## 4. Proof story

The registry says what the consortium agreed about an identifier: that this entry existed with
this revision and controller at this height under this root, that this key had no entry, or that
it was revoked at this revision; for governed writes, who wrote it (actor, organization, key id,
policy revision) and that the actor's authorization was consumed exactly once; that the root was
certified by the pinned members; and, when anchored, that Cardano carries it. It never mints
identifiers and never asserts that a credential's claims are true. Each fact is shown on its own
row; the console and CLI never merge them into one indicator.

## 5. Determinism firewall

Nothing in this product executes on chain except genesis construction, which uses the stock
contracts and validators. The consensus classpath is unchanged. `profile`, `client`, and `cli`
carry no JSON-LD or RDF dependency; a unit test in each module asserts that the runtime classpath
holds no artifact whose name contains `jsonld`, `json-ld`, `rdf4j`, `jena`, or `titanium`, the
ADR-029 §5.5 dependency-lock check in its smallest form.

## 6. Security and privacy

- Seeds for `put`, `revoke`, `publish-list`, and `genesis actor-key` are read from files that
  must be owner-only and are never logged or echoed.
- Direct-role authorizations are one-use, scoped to the chain and genesis id, and expire at
  `tip + min(policy lifetime, 100)` blocks.
- The service exposes read routes only, bounded response sizes, bounded replay, and no query that
  maps a verifier to a credential beyond whole-list fetches.
- On-chain values are identifiers and hashes; the profile's documentation and the console's forms
  say so.
- ADR-040 §7 applies to the console; API keys stay in memory.

## 7. Testing

- Unit: genesis determinism (same descriptor, same genesis id; demo descriptor round-trips),
  value codecs and schema acceptance, bitstring projection and encoding (hash of raw bits, `u`
  multibase, GZIP), TRQP evaluation, answer JSON round-trip, CLI argument handling, classpath
  firewall.
- Cluster (`client` tests): an in-process three-member `authenticated-map` cluster with the demo
  genesis behind the ADR-047 REST bridge extended with `POST /query/{path}` and `GET
  /blocks/{height}`. The client writes status and a subject as `issuer-a` and `registrar-a`,
  publishes a list, and answers inclusion (`DIRECT_ROLE`), tombstone, exclusion, and a genesis
  issuer (`GENESIS`) with `CALLER_PINNED_ROOT`; a wrong member set fails; the projection serves the
  list and the hash matches the chain; a second issuer is onboarded through the approval route
  with the Java role-workflow contracts (propose, two registrar approvals, map command with the
  approval reference) and then answered with `RECEIPT` provenance.
- Golden: the cluster test writes an answer, a members file, and a list document that the CLI
  and console tests re-verify.
- Live: the launcher on the Yano X distribution; the guide's walkthrough runs the CLI, the
  service, and the console (headless browser) against it.

## 8. Deferred

- Showcase catalog integration (`showcase.sh up --trust-registry`).
- `APPROVAL` proof bundles and registrar onboarding through the console; v1 documents the stock
  CLIs for approval-gated writes.
- Browser signing of map actions; v1 signs in the CLI.
- `did:webvh` witnessing (ADR-029 §5.4) and Oblivious HTTP serving.
- Recipe and Studio entry beyond the docs-site page.
- Concurrent projections in the service: a point-in-time request replays under the same lock
  as the tip projection, so a long historical replay delays other requests. Acceptable for a
  registry whose command count is small; a per-height replay queue is a follow-up.

## 9. Acceptance criteria

- `./gradlew :products:trust-registry:profile:test :products:trust-registry:client:test
  :products:trust-registry:cli:test verifyArtifactInventory verifyJvmOnlyBuild` and the console's
  `npm run check`, `npm test`, `npm run build` pass with the Yano version `main` requires.
- The guide's walkthrough runs on the launcher: generate, start, write status as `issuer-a`,
  publish a list, answer inclusion, tombstone, and exclusion with proofs bound to one root, serve
  the Bitstring list and a TRQP answer, and verify an exported answer offline with pinned members.
- The JVM distribution carries `tools/yano-trust`, `product-ui/trust-registry`, and the launcher.
- `docs/appchain/TRUST_REGISTRY.md` explains setup, the data model, key handling, and what each
  proof means.

## 10. Consequences

The first product on the governed authenticated map ships as a genesis, a verifier over the
shipped proof bundle, a standards skin that is an explicit projection, a console, and a launcher,
all outside consensus, and gives the DPP product (ADR-046 §4.4) its issuer and status layer. The
determinism firewall is enforced by tests rather than by review. The showcase's identity contract
is left untouched at the cost of a second launcher script.

## 11. Implementation record (2026-09-06)

Gates, all against the Yano pre14 snapshot `main` requires (`-PuseMavenLocal=true
-PyanoVersion=0.1.0-pre14-ba9ac62-SNAPSHOT` with the local pre14 distribution zip):

- `:products:trust-registry:profile:test` 11 tests, `:products:trust-registry:client:test`
  9 tests (in-process three-member governed map: governed writes from height 0, tombstone,
  exclusion, genesis issuer, historical answer, wrong members and tampered entries rejected,
  the service's list and TRQP routes, issuer onboarding through the approval route with the Java
  contracts, goldens), `:products:trust-registry:cli:test` 5 tests, console `npm run check`
  (0 errors), `npm test` (17), `npm run build`; `verifyArtifactInventory`,
  `verifyJvmOnlyBuild`, and `verifyYanoXJvmDistribution` pass (the zip carries the three
  product artifacts and the launcher).
- Live: the launcher on the pre14 showcase distribution (three members, threshold 2); the CLI
  walkthrough of §9 ran every step with the documented exit codes (first write with
  `--genesis-id` at height 0, writes, revoke, `publish-list`, `status` at 6/5, exclusion, genesis
  issuer, `list` match, `trqp`, offline `verify` at 5, historical answer, wrong seed refused);
  `yano-trust serve` served the list (matching the chain), a point-in-time list (marked as
  differing), TRQP, and 404s; the console ran the connect, lookup, historical, exclusion, list
  check, and TRQP journeys in headless Chrome against the live node, and an answer exported from
  the console verified offline with the CLI and pinned members.

Deviations from §2, each found by running against the real runtime:

| Topic | Decision | Reason |
|---|---|---|
| Finality | The answer carries the evidence bundle of a message in its block, and the SDK `EvidenceVerifier` checks the certificate; `ProofVerifier.verifyCertified` is not used | The pre14 node's certified block view in a state proof envelope still renders the nine v2 header fields, and the SDK recomputes a v2 header hash with `version=3`; a v3 block hash also commits to `consensusContextDigest`, `view`, proposer, and justification, so `verifyCertified` cannot succeed against a pre14 node. A core finding for Yano's `AppChainResource` and the SDK, recorded here rather than patched |
| Direct-role binding | The `BASIC` bundle binds entry and receipt; `DirectRoleBinding` in the client binds consumption, actor, organization, and policy facts, with the map genesis id taken from the proven genesis marker (a ninth fact) | On a composite runtime the state commitment identity in every proof is the application-profile-bound derivative of the map genesis id, while actor authorizations are signed with the map genesis id; the bundle's `DIRECT_ROLE` kind requires the two to be equal. A second SDK finding |
| Entry reads | Answers read the entry from the state proof instead of the point query | The gateway's historical point query executes only at the committed height; state proofs are retained per height |
| Height 0 | The first write on a fresh chain signs with the genesis revisions and `--genesis-id`; `Identity` tolerates an unqueryable genesis | A Yano chain produces a block only when a message arrives; component queries and record reads fail until height 1 |
| Members' home | The launcher disables `yano.history.projection` per node | The devnet profile projects L1 history into `./history` under the shared home; the second member refuses an archive another member already advanced |
| Service | Responses carry `Access-Control-Allow-Origin: *` | Verifiers and the console fetch lists cross-origin; the data is public and read-only |
| Onboarding | Exercised in the cluster test with the Java contracts only | No stock CLI walkthrough for the approval route was run; documented as the executable reference |

Two guides written before this product named a `showcase.sh down` command that does not exist;
`docs/appchain/ATTEST.md` and `docs/appchain/EVIDENCE_DESK.md` now say `stop`.
