# ADR-048: Evidence Desk

- **Status:** Accepted and implemented on `feat/products-oob` (2026-09-06); §12 records the
  deviations. Implements ADR-046 §4.3.
- **Scope:** `products/evidence/ui`, one browser UI over the role workflow and the evidence
  product; no consensus, contract, or node change
- **Relates to:** ADR-046 §4.3, ADR-040 (product UIs), ADR-047 (Attest, whose UI scaffold and
  proof conventions this reuses), Yano ADR-013 (evidence effects), Yano ADR-015 (profile
  activation), `docs/APP_CHAIN_DOMAIN_ROLES.md`, tutorial 5

## 1. Context

The evidence product is the most complete first-party workflow in the repository: domain actors,
role-aware approvals, an approval-gated document trail, the evidence registry, and three connector
effects. It is reachable today only through the demo runner and `demo.sh`, and its report page is
a static viewer of runner output. The light showcase carries a connector-free sibling,
`document-review-chain`, whose composite reuses the same `domain-actors` and `role-approvals`
components and gates a doc-trail append on the same governed approval workflow.

ADR-046 §4.3 asks for one UI that makes this visible: an actor signs decisions with a key that
never leaves the browser, reviewers from different organizations approve, the release is submitted,
and every proof is shown separately. It also requires that the same UI handle the connector-free
showcase chain by capability discovery.

## 2. Decision

Build `products/evidence/ui`, the Evidence Desk, as a static SvelteKit application following the
Attest UI scaffold and ADR-040. Version one is scoped as follows.

**Chains it serves.** Any chain whose capability manifest lists both a `domain-actors` component
and a `role-approvals` component. Two release adapters are selected by manifest:

| Adapter | Selected when the manifest has | Release topic | Payload domain | Command |
|---|---|---|---|---|
| `document-review` | components `documents` and `document-review-receipts` | `document-review.release.v1` | `document.review.release.v1` | `DocumentReviewCommandV1` (7-item CBOR array) |
| `role-evidence` | component `evidence` | `evidence.release.v1` | `evidence.release.v1` | `EvidenceReleaseCommandV1`; v1 decides and inspects the role workflow only (§7) |

Chains with the role components but neither adapter are served in "approvals only" mode: directory,
proposals, and signed decisions work; the release step explains that no release adapter matches.

**Journeys.**

1. **Connect and discover.** Runtime config, connection panel, and node identity checks are the
   Attest ones. Eligible chains are listed with their adapter, tip, members, and threshold.
2. **Directory.** Organizations, actors (current pointer plus revision), policies, and statistics,
   read straight from the authenticated state through the state proof endpoint and decoded from
   canonical CBOR in the browser. Every record is shown with the committed height and state root
   it was read at.
3. **Actor key.** The user pastes or loads a 32-byte Ed25519 seed. The desk derives the public key
   with WebCrypto, matches it against the selected actor's `ACTIVE` key epoch from the directory,
   and keeps the key in memory only. Without a match the desk refuses to sign. Without WebCrypto
   Ed25519 the desk shows the equivalent `yano appchain role sign` command instead.
4. **Propose.** The proposal form is the release form: for `document-review` the issuer names
   the proposal id, the document entity id, the document (hashed locally with SHA-256, or a
   pasted digest), and the reference. The desk encodes `DocumentReviewCommandV1` from those
   inputs, takes its blake2b-256 as the statement's payload hash, builds `ActorStatementV1`
   (`PROPOSE`, no clause), signs its frozen preimage (`yano:role-approval:v1\0` + 4-byte
   big-endian length + statement), wraps it as `SignedActorCommandV1`, and submits it on
   `role-approvals.command.v1`. Everything the payload hash commits to is therefore fixed at
   proposal time; the desk keeps the encoded command in memory and can re-derive it from the same
   inputs later.
5. **Approve and reject.** A reviewer selects a pending proposal; the desk copies policy id,
   policy revision, payload domain, payload hash, and deadline height from the fetched proposal
   record, lets the reviewer pick a clause the actor's roles satisfy, signs, and submits. It
   refuses to sign when the proposal is not `PENDING`, when the tip is past its deadline, or when
   the connected chain id differs from the proposal's chain.
6. **Outcome.** After each statement finalizes, the desk reads the workflow's result record
   `r/c/<messageId>` through the state proof endpoint and shows the deterministic outcome
   (`ACCEPTED`, `ROLE_MISMATCH`, `DISTINCTNESS_DUPLICATE`, `EXACT_REPLAY`, `CONFLICT`,
   `EXPIRED`, ...) rather than only "finalized".
7. **Release.** For `document-review`, once the proposal is `APPROVED`, the issuer re-derives the
   command from the inputs (or the desk reuses the encoded bytes), the desk checks that
   blake2b-256 of the command equals the proposal's payload hash, and submits on the release
   topic. After finality it reads the receipt and the document head through the state proof
   endpoint and verifies both against the same state root.
8. **Proposal view.** Status, proposer, decisions per clause with organization and key id, the
   policy clause satisfaction as the desk computes it from the policy and the decisions, deadline
   against the tip, and the proof of the proposal record.
9. **Export.** A JSON bundle of the proposal record, its state proof, the receipt or evidence
   record and their proofs, and the block header used, so a script can replay the checks
   offline. The bundle format is documented but has no CLI in v1.

**What the desk never does.** It never sends a seed to the node, never stores keys or API keys in
browser storage, never merges proofs into one indicator, and never trusts a JSON projection: each
record it shows carries the outcome of its proof binding, `BOUND` or `MISMATCH` (§3).

## 3. Discovery and data access

The desk uses only endpoints the pinned node already exposes:

| Need | Endpoint |
|---|---|
| Chains and manifests | `GET /app-chain/chains`, `GET /app-chain/chains/{id}/status` |
| Records by id | `GET .../state/proof/{physicalKeyHex}?height=` for every record: current pointers, revisions, policies, proposals, statistics, receipts, and document heads. The component query router (`POST .../query/components/{component}/{subject}`) serves the same records without proofs and is not used. |
| Command outcome | The same endpoint for `role-approvals` / `r/c/<messageIdHex>`; the query router does not expose `command-result`, the trie does |
| Proposal listing | `GET .../blocks?from=&limit=` over a bounded recent window, then `GET .../blocks/{height}` for blocks with messages, decoding `role-approvals.command.v1` bodies to proposal ids; plus a direct look-up field. `pending-approvals` is not declared by either composite |
| Proofs | `GET /app-chain/chains/{id}/state/proof/{physicalKeyHex}?height=` |
| Submit | `POST /app-chain/chains/{id}/messages` (`topic`, `bodyHex`) |
| Finality | `GET /app-chain/chains/{id}/messages/{id}`, `GET .../blocks/{height}` |

Physical keys are `yano-composite-state-v1\0` + component id (1-byte length) + local key (2-byte
big-endian length), the same derivation the showcase codec uses; local keys follow
`RoleWorkflowKeys` (`o/<id>/current`, `o/<id>/r/<n>`, `a/...`, `p/...`, `q/<proposal>`,
`s/proposals/v1`, `r/c/<messageIdHex>`), doc-trail's entity key for `documents`, and
`approval/<proposalId>` for `document-review-receipts`. Current pointers are 8-byte big-endian
revisions. All of this was confirmed against the pinned node on the showcase instance.

The plugin domain APIs (`/api/v1/plugins/...`) are not used in v1: the showcase bundle does not
mount them and nothing they return is needed beyond what queries plus proofs provide. Query
responses carry `committedHeight` and `stateRoot`; the desk requests the proof at that height and
refuses to display a record whose proof does not bind to the same root.

blake2b-256 for message ids and action commitments reuses the TypeScript port that the Attest UI
carries in `message-proof.ts`, checked against `showcase_codec.py blake2b-hex` in the goldens.

## 4. Browser signing

`ActorStatementV1` is a 13-item canonical CBOR array (version, action code, chain id, proposal id,
policy id, policy revision, payload domain, payload hash, deadline height, actor id, actor
revision, key id, clause id). The signing preimage is domain-separated as in §2. The signature is
plain Ed25519 over the preimage; the node verifies with a strict provider and a second verifier,
so the browser must produce a canonical signature, which WebCrypto does.

Keys: WebCrypto imports the seed as PKCS#8 (`302e020100300506032b657004220420` + seed) and
exports the public coordinate to derive the 32 raw bytes. The seed bytes are zeroed right after
import and the pasted text field is cleared; the resulting key lives in a closure of the signer
module, is released when the user disconnects or changes actor, and is never logged. For a
proposal the deadline is `tip + min(policy.maximumLifetimeBlocks, 300)`; approvals and rejections
copy the proposal's deadline, policy revision, payload domain, and payload hash. Every statement
field is displayed before signing, satisfying ADR-040 §7's "never sign unexplained CBOR".

## 5. Chain-side outcomes

Finalized statements that fail the workflow's rules are deterministic no-ops recorded under
`r/c/<messageIdHex>` in the `role-approvals` namespace as `RoleCommandResultV1` (version, kind,
subject id, result code, applied height, message id, command digest). The desk reads that record
with a state proof at the message's height and shows the code with a short explanation; an
absent record after the block is applied is reported as `malformed or not a role command`.
The check order for approvals is: unknown proposal, terminal proposal, statement does not match
the proposal (`CONFLICT`), prior decision by the same actor (`EXACT_REPLAY` or `CONFLICT`),
clause or role mismatch (`ROLE_MISMATCH`), organization already decided the clause
(`DISTINCTNESS_DUPLICATE`), rejection disabled (`ROLE_MISMATCH`).

## 6. Evidence records and effects (deferred, see §7)

On a `role-evidence` chain v1 serves the role workflow only: directory, proposals on the
`evidence-release` policy, decisions, and outcomes. The evidence record, its per-effect terminal
outcomes, and the evidence-approval link are not rendered in v1. When the release path lands,
each effect is to be shown on its own row from the terminal outcome the chain committed
(`pending`, `CONFIRMED`, `FAILED`, `CANCELLED`, `EXPIRED`) with its result height and effect ref;
"executed" without a terminal result is not a state the chain proves. The derived business status
of `EvidenceStatus.derive` additionally decodes the S3, IPFS, and Kafka receipt contracts and is
deferred with the same slice.

## 7. Deferred to a later slice

- **Release submission and evidence views for `role-evidence`.** `EvidenceReleaseCommandV1`
  nests a `SubmitEvidenceCommandV1` whose object, IPFS, and Kafka commands and destination
  fingerprints come from the connector contracts. Building them in the browser needs TypeScript
  ports of three connector command codecs and a staging step that only the harness has. v1 signs
  and inspects approvals on such chains; the release stays with `demo.sh publish`, and the
  evidence record views of §6 come with the release path.
- **Governed mutations** (onboarding, rotation, revocation) remain CLI operations; the desk shows
  their results through the directory.
- **Offline verifier CLI** for the export bundle; v1 documents the format and verifies in-page.

## 8. Security

ADR-040 §7 applies. In addition: the seed input is a password field or a file picker, the desk
disables signing while the connected node's chain id differs from the statement's chain id, API
keys stay in memory, and the CSP of the Attest scaffold is kept. The desk decodes every record
with the bounded canonical CBOR reader shared with Attest (definite lengths only, depth 8, at most
20,000 items, 5 MiB), then applies the role-workflow bounds (16 KiB per record, identifier and
domain patterns) and re-encodes to reject non-canonical bytes before use.

## 9. Testing

- Unit tests with golden bytes produced by the Java contracts: `ActorStatementV1` encode and
  preimage, `SignedActorCommandV1` encode with a signature from a demo seed, record decoders for
  organization, actor, policy, proposal, stats, and command result, `DocumentReviewCommandV1`,
  the receipt, blake2b-256, and the composite key derivation. The goldens are written by a test
  in `examples/showcase`, which already has the command, receipt, genesis, and demo seed
  helpers, gated by a system property the way the Attest goldens are, and committed under
  `products/evidence/ui/src/lib/fixtures`.
- Signing test: the browser signer, run under Node's WebCrypto, reproduces the committed
  signature byte for byte (Ed25519 is deterministic), and the committed public key matches the
  one derived from the seed.
- Opt-in live test (`EVIDENCE_DESK_LIVE_NODE_URL`) that runs propose, two approvals, release,
  receipt and proof checks, and the negative cases of §10 against the light showcase
  `document-review-chain` with the showcase demo actor seeds.
- Headless browser check of the built site: connect, discover, load an actor key, sign a proposal.

## 10. Acceptance criteria

- `npm run check`, `npm test`, `npm run build` pass; the golden test in `examples/showcase`
  re-verifies the committed fixtures on every run.
- Against the light showcase: an issuer proposes from the desk, two auditors approve from the desk
  with their demo seeds, the issuer releases from the desk, and the desk shows the receipt, the
  document head, and both proofs bound to one root. Negative cases the showcase genesis supports:
  the issuer approving its own proposal shows `ROLE_MISMATCH` (issuer-a holds no `auditor`
  role), and an auditor approving the same proposal twice shows `EXACT_REPLAY`. (The genesis has
  one auditor per guild, so `DISTINCTNESS_DUPLICATE` cannot be produced there; it is covered by
  a unit test on the desk's clause evaluation.)
- The built UI is packaged in the JVM distribution under `product-ui/evidence` with its SBOM.
- `docs/appchain/EVIDENCE_DESK.md` explains setup on the showcase and on the harness, key
  handling, and what each proof means.

## 11. Consequences

The desk makes the role workflow and the evidence product usable without the runner, on any chain
that composes the two role components, and it is the second product to reuse the Attest UI
scaffold, which fixes that scaffold as the pattern ADR-040 expects. The connector path stays with
the harness until a follow-up ports the connector contracts to the browser.

## 12. Implementation record

Implemented as `products/evidence/ui` with the golden writer `EvidenceDeskGoldenTest` in
`examples/showcase`, the user guide `docs/appchain/EVIDENCE_DESK.md`, and a docs-site page.
Status of the §10 criteria on 2026-09-06:

- `npm run check`, `npm test` (24 unit tests plus the opt-in live test), `npm run build`, the
  Gradle `frontendCheck`, `frontendTest`, and `uiZip` tasks, the golden test, `verifyArtifactInventory`,
  `verifyJvmOnlyBuild`, and `verifyYanoXJvmDistribution` pass; the JVM distribution carries
  `product-ui/evidence` and `sbom/evidence-ui.cdx.json`.
- The live test ran against the light showcase built from this branch (Yano 0.1.0-pre13,
  `document-review-chain` on instance `attest`, `--http-base 7170`): propose `ACCEPTED`, issuer
  approving `ROLE_MISMATCH`, first auditor `ACCEPTED`, the same statement again `EXACT_REPLAY`,
  second auditor `ACCEPTED` and the proposal `APPROVED`, release finalized, receipt and document
  head `BOUND` to one root, wrong release inputs refused locally, and the block scan listing the
  proposal with its release.
- The built site was served same-origin next to that node and driven in headless Chrome over the
  DevTools protocol through the whole journey: connect, discover four role chains, select
  `document-review-chain` (adapter `document-review`), load `issuer-a`'s demo seed (actor record
  `BOUND`, key `issuer-a-k1`), prepare and sign a proposal, read `ACCEPTED` at the finalized
  height; open the proposal as the issuer and see the desk predict `ROLE_MISMATCH` with the
  decision buttons disabled (the chain's own code for that case was observed by the live test);
  load `auditor-a` and `auditor-b` in turn, prepare and sign approvals, read `ACCEPTED` with the
  result record `BOUND` and the proposal `APPROVED`; reload `issuer-a`, find the release form
  pre-filled, submit, and read the consumption receipt `BOUND`, commitment `MATCH`, and document
  head `BOUND` at revision 1; build the export bundle. Those runs caught two defects the library
  tests could not see, the key loader clearing the seed before parsing it and the outcome notice
  being reset by the proposal reload, both fixed before this record.
- Not run: the evidence harness (`role-evidence` profile, Docker Compose). Its adapter is
  exercised only by the manifest-selection unit test; the decide and inspect paths on that chain
  reuse the same role components and codecs.

Deviations from the proposal:

| Topic | Decision |
|---|---|
| Proof state labels | Records carry `BOUND` or `MISMATCH` rather than "verified": the desk binds the envelope's key, height, root, and block hash to the finalized block header the node serves, and leaves the MPF path and finality to the JVM verifier on the export bundle, the same division ADR-047 made for the browser. |
| Proposal listing | No query lists proposals, so the desk scans the last 2,000 block summaries and fetches at most 200 blocks with messages per pass, decoding role and release commands; a look-up field covers older ids. |
| Command outcomes | `command-result` is not a declared query subject on either composite; the desk reads `r/c/<messageId>` through the state proof endpoint at the message's height. |
| Directory identifiers | The chain has no listing query for organizations, actors, or policies. The runtime configuration accepts optional `directoryHints` per chain (bounded, identifier-validated) and the desk also learns identifiers from scanned commands. |
| Chains discovered | Any chain with both role components is listed; on the light showcase that includes the authenticated-map chains as `approvals-only`, where the desk decides and inspects but offers no release. |
| Signing | WebCrypto Ed25519 only; the seed is imported as a non-extractable PKCS#8 key after one extractable import to derive the public key, and the browser reproduces the Java signatures byte for byte in the unit test. |
| Effects (§6) | Deferred with the `role-evidence` release path; the desk shows that adapter's proposals, decisions, and directory, and points to `demo.sh publish` for release. |
