# ADR-050 — Verifiable Explorer

**Status:** Accepted (2026-09-06), implemented on `feat/products-oob`; §11 records the implementation and its deviations
**Scope:** Product 4 of ADR-046 (§4.5): a derived, verifiable index with a read service, a CLI, and
a console over stock app chains
**Related:** ADR-046 (portfolio), ADR app-layer/032 (indexer components), ADR app-layer/031 (typed
subjects, availability vocabulary), ADR app-layer/037 (proof lab, message packages), ADR-040
(product UIs), ADR-047 (Attest trust levels and exit codes), ADR-049 (runtime findings)

---

## 1. Context

Nodes commit truth. The REST surface returns one block, one message, one proof, one evidence
bundle at a time, and the generic console shows the same one-at-a-time view. Every product in
ADR-046 needs search, timelines, and per-entity history with evidence attached; auditors need a
browsable view that can still prove what it shows. ADR-032 designed the components (an indexer
core, per-machine modules, a content archiver, and a verifiable read API) and shipped none of them
beyond the product-local EUTxO indexer.

What exists today and is reused as-is:

- Node REST: `/blocks?from&limit`, `/blocks/{h}`, `/messages/{id}`, `/messages/{id}/proof`
  (compact inclusion proof), `/messages/{id}/proof-package` (ADR-037 message package),
  `/evidence/{id}` (block segment with finality certificates and declared members),
  `/state/proof/{key}?height=` (proof envelope with a certified header view and certificate),
  `/state/identity`, `/state/oldest-provable`, `/status` with the capability manifest, and the
  SSE `/stream`.
- The runtime wraps every application in the block-message-root index, so every chain holds an
  authenticated `[height, messagesRoot, messageCount]` record per block under a reserved key
  (`FinalizedBlockMessageRootIndex.blockKey`). This is the bridge from "message in block" to
  "authenticated state at a root" for any chain, stock or custom.
- Contract-owned codecs: `KvRegistryContract`, `BalancesContract`, `DocTrailContract`,
  `ApprovalsContract`, `AuthenticatedMapContract` and `AuthenticatedMapAuthorizationContract`,
  `SignedActorCommandV1` and `ApprovalProposalV1` for role approvals, `FinalizedMessageIndex` for
  the ordered log. All are public, deterministic, and dependency-light.
- SDK verifiers: `EvidenceVerifier` (block hashes, certificates, members, anchor linkage),
  `ProofVerifier.verify(proof, TrustedStateRoot)` (root-bound native proof), `AttestTrust`
  (caller-pinned members and independent anchor datum) from ADR-047.
- Browser helpers written for Attest: strict CBOR, Blake2b-256, WebCrypto Ed25519, canonical
  envelope extraction from a block, message id recomputation, compact inclusion-proof
  verification, and block-record decoding.
- ADR-049 findings that still hold on pre14: the SDK's `verifyCertified` recomputes a v2-shaped
  block hash and fails on v3 blocks, so finality is established through the evidence bundle; the
  state genesis id is the application-profile-bound derivative of the machine genesis; chains
  produce blocks only when messages arrive.

## 2. Decision

Ship `products/explorer` as a standalone read-side product, never an authority, made of:

| Module | Artifact | Role |
|---|---|---|
| `products/explorer/core` | `yano-x-explorer-core` (library) | indexer core, per-machine modules, content archiver, row verifier, read model, HTTP service |
| `products/explorer/cli` | `yano-x-explorer-cli` (tool `yano-explorer`) | `chains`, `index`, `serve`, `status`, `search`, `subject`, `trail`, `row`, `verify`, `archive`, `rebuild`, `export` |
| `products/explorer/ui` | `yano-x-explorer-ui` (web application) | the console (ADR-040) over the service and the node |
| `products/explorer/harness/explorer.sh` | in `examples/explorer` | runs the service against a showcase instance; `traffic` drives the showcase scenarios |

No consensus, runtime, or node change. The EUTxO indexer stays as it is (ADR-032 IX-M1 allowed
either outcome); this product proves the generic core on the stock machines first.

**First slice.** This ADR ships: the follower with ingest verification; the generic, doc-trail,
kv-registry, balances, approvals, and authenticated-map modules (the map module serves the
registry and the DPP starter); the archiver with `add --file` and allow-listed HTTP fetch; the
service routes for chains, blocks, messages, search, trails, content, and the row and state proof
bundles; the CLI commands `index`, `serve`, `status`, `search`, `trail`, `row`, `verify`,
`rebuild`, and `export`; and a console whose subject view is driven by the module type rather
than one view per module. Role-approvals decoding, `follow` over SSE (the poll loop suffices for
a preview), and dedicated map and proposal views are deferred (§8).

### 2.1 Indexer core

**Source.** The node REST API only (ADR-040 rule 3). No node storage, no plugin, no privileged
bridge. The follower reads block summaries (`/blocks?from&limit`) and, per height, the block JSON
(`/blocks/{h}`, messages with bodies) plus the canonical block bytes and finality certificate from
the evidence bundle of the block's first message (`/evidence/{id}`). A bundle carries a consecutive
signed segment up to a covering anchor, so one fetch often covers several heights; the follower
harvests every block in the segment. The state-proof envelope cannot serve this purpose on pre14:
its certified block view carries only the nine v2 header fields (ADR-049 §11). The block record
proof at `h` (`/state/proof/{blockKey(h)}?height=h`) is captured separately as the root binding.
When the record is absent (the index can be disabled per chain), message rows bind to the verified
block header's `stateRoot` directly and keep their level; the record only matters for the
anchor-pinned path (anchor, state root, messages root).
Blocks without a message (no bundle) and heights whose evidence is no longer retained are recorded
from JSON alone and labelled `HEADER_ONLY` or `JSON_ONLY`.

**Verification on ingest (per block).**

| Check | What it establishes |
|---|---|
| V1 block hash | the canonical block deserializes, `AppBlockCodec.blockHash` recomputes, and the header fields equal the JSON view |
| V2 linkage | `prevHash` equals the stored hash of height `h-1` |
| V3 messages root | `messagesRoot(messages)` equals the header, and the captured block record `[h, root, count]` matches |
| V4 message ids | every message id recomputes from its canonical signed body |
| V5 finality | the certificate verifies against the trusted member set at threshold |

`EvidenceVerifier.verify` on the fetched bundle establishes V1, V2 (within the segment), V4, and
V5 for every block the bundle carries; the follower does not reimplement them. It adds V2 across
segments (the first block's `prevHash` against the stored hash) and V3. A message stripped by
retention arrives as an empty body with an empty auth proof and no valid message id; it is stored
as a `TOMBSTONE` row, never reported as tampering. The trusted member set is caller-pinned
(`--members`) or bundle-declared (the evidence bundle's members and threshold, labelled as such). The level recorded per block is `VERIFIED_PINNED`,
`VERIFIED_DECLARED`, `HEADER_ONLY` (no message, so no bundle), or `JSON_ONLY` (evidence not
retained). Rows inherit the level of their block.

**Checkpoint and identity.** One SQLite transaction per block writes the block, its messages, the
module rows, the block record proof envelope (captured at ingest because proof material is pruned),
and the checkpoint. The index identity pins chain id, application id, commitment profile, state
genesis id (from `/state/identity`), and the capability manifest digest; a mismatch refuses to
index (`IDENTITY_MISMATCH`). Rebuild drops the database and replays; the golden replay test proves
two runs produce identical exports. The export orders rows by height and index and excludes
wall-clock columns (`fetched_at`, `updated_at`), which exist for operators, not for identity.

**Storage.** SQLite through `sqlite-jdbc` (already in the catalog), file or in-memory; schema v1 as
DDL in code with a `schema_version` table. PostgreSQL is deferred (§8).

### 2.2 Per-machine modules

Modules decode by topic, so plain and composite chains use the same table. The chain's capability
manifest maps topics to component ids; state keys for composite components use
`CompositeCommitmentV1.componentKey`.

| Module | Topics | Rows |
|---|---|---|
| generic | any | `blocks`, `messages` with bodies captured before retention strips them, topics, senders |
| ordered-log | any (machine `ordered-log`) | positions are messages; the finalized-message record subject backs verification |
| doc-trail | `doc-trail.command.v1` | `trail_entries(entity, revision, entryHash, reference, author, message, height, index)`; the head recomputed with `DocTrailContract.computeHead` |
| kv-registry | `kv-registry.command.v1` | `registry_commands(key, op, value, sender, message, height)` and the derived latest view |
| balances | `balances.command.v1` | `balance_commands(op, account, amount, sender, message, height)` and derived account activity |
| approvals | `approvals.command.v1` | `approval_commands(item, op, payloadHash, required, deadline, sender, ...)`; decision trail per item |
| role-approvals (deferred) | `role-approvals.command.v1` | generic rows now; `SignedActorCommandV1` decoding later |
| authenticated-map | the map command topic | actions decoded: mutations (op, collection, key, expected revision) and evidence kinds; per-entry revision history |

Rows are **finalized commands**. A finalized command is not a state change: an unauthorized
registry put, an underfunded transfer, or a malformed approval is a deterministic no-op. The
explorer therefore separates "what was finalized" (rows, always shown) from "what the state says"
(a proof-backed read at a height, fetched on demand and compared with the derived view:
`MATCH`, `DIVERGES`, or `UNCHECKED`). For doc-trail the derived head is compared with the
authenticated head; a divergence exposes a rejected append rather than hiding it.

### 2.3 Content archiver

For hash-plus-reference rows (doc-trail):

- `archive fetch` follows references that match caller allow-listed URL prefixes, with a size cap
  and a timeout; the body's SHA-256 and Blake2b-256 are compared with the entry hash; a match stores
  the body under its SHA-256 in a content-addressed directory and records `MATCHED`; a mismatch is
  recorded (`MISMATCH`) and the body is discarded.
- `archive add --file` attaches operator-supplied content with the same check, for references that
  are not fetchable.

Availability per ADR-031 §3.5: `FINALIZED` (command finalized), `STATE_RECORDED` (head
proof-backed at a height), `CONTENT_VERIFIED` (archived body hashes to the entry), `AVAILABLE`
(served by hash from this service). Coverage is reported, never assumed.

### 2.4 Verifiable read API

`yano-explorer serve` exposes GET-only JSON with `Access-Control-Allow-Origin: *`:

```text
/healthz
/chains                                       identity, checkpoint, tip, lag, verification levels
/chains/{c}                                   one chain
/chains/{c}/blocks?from&limit                 timeline
/chains/{c}/blocks/{h}                        block with its messages and their rows
/chains/{c}/messages/{id}                     message with its rows and evidence link
/chains/{c}/messages/{id}/proof               explorer-row-proof-v1 (see 2.5)
/chains/{c}/search?q=                         message id, height, topic, sender prefix, subject prefix
/chains/{c}/topics
/chains/{c}/subjects?module&prefix&limit      subjects the modules decoded
/chains/{c}/subjects/{module}/{subject}       rows, derived view, proof-backed state check
/chains/{c}/subjects/{module}/{subject}/proof explorer-state-proof-v1 (?height=)
/chains/{c}/trails/{entity}[/proof]           the doc-trail subject: revisions, head check, availability
/chains/{c}/content/{sha256}                  archived body; ?meta=1 for the record
```

One subject route serves every module (registry keys, accounts, approval items, map entries);
`/trails` is the doc-trail alias the first slice promised.

Every row carries `evidence: {messageId, height, index, stateKeyHex?}` so a reader can go from the
row to the node's own proof endpoints. The node API key stays server-side; the service never signs
or writes.

### 2.5 Verification

**Row proof bundle** `explorer-row-proof-v1`: chain and state identity, the message envelope copy
and position, the node's compact inclusion proof, the block record proof envelope at the row's
height, the node's evidence bundle for the row's message, and an explanatory `verification`
object that importers discard. **State proof bundle** `explorer-state-proof-v1`: subject id,
coordinates, canonical key, the proof envelope at the height, the decoded fact, and the evidence
bundle of a message finalized in that block.

The `RowVerifier` (core, CLI `verify`, and the service's explanatory object) checks, in order:

1. Evidence: `EvidenceVerifier` with caller-pinned members (`CALLER_PINNED_ROOT`) or the bundle's
   declared members (`INTERNAL_CONSISTENCY_ONLY`); certificates, block hashes, linkage.
2. Position: the inclusion proof recomputes the verified block's messages root at the row's index.
3. Root binding: the block record (or state) proof verifies with `ProofVerifier.verify` against
   the verified block's state root (`TrustedStateRoot`, source `FINALITY_CERTIFICATE`), and its
   value decodes to `[h, root, count]` matching the header (or to the typed fact).
4. Envelope copy: the row's message equals the canonical message in the verified block, and the
   message id recomputes from the signed body.
5. Anchor: a bundle anchor reference raises the level to `NODE_CONFIRMED_L1_REFERENCE`;
   `--anchor-datum-hex` (ADR-047 `AttestTrust.IndependentAnchor`) to `CALLER_PINNED_ANCHOR`.

Exit codes follow ADR-047: 0 verified with an independent anchor, 2 usage, 3 unavailable,
4 invalid, 5 verified with caller-pinned members, 6 consistent with declared members only.

The console repeats checks 2 and 4 in the browser with the Attest helpers (envelope copy, message
id, sender signature where WebCrypto Ed25519 exists, inclusion proof, block record decode) and
labels finality and anchor checks as CLI-verified, exactly as Attest does.

### 2.6 Console

`products/explorer/ui`, SvelteKit static build per ADR-040, runtime config
`explorer-ui-config.json` with `serviceUrl` and node endpoints. Views: Chains (identity,
checkpoint, lag, verification level), Blocks (timeline), Block, Message (decoded command, "verify
this row", bundle download for the CLI), Search, Trails (revisions, head check, availability, body
by hash), Registry, Accounts, Proposals, Map, Content. Discovery follows ADR-040 rule 5: the
service's `/chains` lists indexed chains and the node's status confirms identity.

### 2.7 Launcher

`products/explorer/harness/explorer.sh up|status|env|stop|clean|traffic --showcase <yano dir>
--instance <name> [--chains a,b]`: reads the showcase instance's HTTP base and API key, starts
`yano-explorer serve` with a data directory under `~/.yano-x/explorer/<instance>`, and prints the
service and console URLs. `traffic` delegates to `showcase.sh run all` so a fresh instance has
rows in every stock chain within a minute.

## 3. Trust, security, and bounds

- Never an authority: nothing in consensus, proof verification, or accounting reads the index.
- The service is GET-only, binds to `127.0.0.1` by default, holds the node API key in memory, and
  never echoes it.
- Bounds: block JSON and canonical block bytes capped at the node's maximum (8 MiB), evidence
  bundles at 40 MiB, archived bodies at 16 MiB, search input at 256 characters, page size at 200.
- Archiver: allow-listed prefixes only, no redirects across hosts, no local file references.
- Database files are created `0600`; the directory `0700`.
- Classpath firewall: no JSON-LD, RDF, or Jena on the runtime classpath (same test as ADR-049).

## 4. Core changes

None. Two SDK findings from ADR-049 shape the design: finality through the evidence bundle rather
than `verifyCertified`, and the state identity from `/state/identity` rather than the machine
genesis. Anything else discovered during implementation is recorded in §11.

## 5. Interactions with the other products

- Attest certificates and Evidence Desk publications are doc-trail rows; the trail view is their
  timeline, and the archiver closes the "where is the document" gap Attest leaves open.
- Trust registry entries are authenticated-map rows; the map view is the registry's public
  history. The registry's own service remains the standards surface.
- The DPP starter (Product 5) reads its passports through this service's map and trail views.

## 6. Rejected alternatives

- **In-node plugin indexer** (the EUTxO shape). Rejected for this product: it needs a plugin
  bundle per deployment, couples the explorer's release to the node's, and the public REST surface
  already carries everything the core needs.
- **Trusting `/blocks/{h}` JSON.** It has no certificate and no canonical bytes; nothing could be
  verified on ingest. It stays as the labelled fallback for pruned heights.
- **A new proof format.** The row bundle only aggregates node-issued objects (inclusion proof,
  proof envelope, evidence bundle); verification reuses SDK verifiers.
- **Showcase catalog integration.** Same reasons as ADR-049: the identity script pins chain-set
  permutations and the catalog count; a product-owned launcher avoids touching them.

## 7. Testing

- Unit (core): module decoders against the documented command layouts, archiver hash matching and
  allow-list, row and state bundle codecs, SQLite store round trip, checkpoint atomicity, rebuild
  determinism (index twice, identical export), search tokenization, classpath firewall.
- Cluster (core test, in-process three-member clusters from the Yano testkit): doc-trail,
  kv-registry, approvals, and balances chains driven with real commands; index from height 0;
  every row verifies; a tampered body, a wrong-root proof, and a wrong member set are rejected; the
  service routes; goldens for the CLI and the console.
- CLI: usage, exit codes, verify on the goldens.
- Console: golden-fixture tests, `npm run check`, build.
- Live: the launcher against the pre14 showcase after `run all`; a CLI walkthrough; the console in
  headless Chrome; a browser-exported row bundle verified by the CLI.
- Gates: module tests, UI gates, `verifyArtifactInventory`, `verifyJvmOnlyBuild`,
  `verifyYanoXJvmDistribution`.

## 8. Deferred

- PostgreSQL sink; SSE and webhook sinks from the service; IPFS and S3 fetchers.
- Role-approvals command decoding (the rows are indexed generically); `follow` over the node's SSE
  stream; dedicated map and proposal console views.
- Moving the EUTxO indexer onto this core (ADR-032 IX-M1's other branch).
- The node's typed proof endpoint (`/proof-subjects/{id}/proof`) as the state-row source.
- On-chain export of row bundles; showcase catalog and Studio entries; the DPP portal consumer.
- Proof capture at ingest for typed state rows (only the block record proof is captured now).

## 9. Acceptance criteria

1. A showcase instance running stock machines gets a working explorer from a URL and an API key;
   every chain is discovered and indexed with its verification level shown.
2. Rebuilding from the node reproduces identical rows (golden replay test).
3. A doc-trail entity trail serves a document by hash with a verification chain to the root, and to
   the anchor when the evidence carries one.
4. A tampered body, a wrong-root proof, and a wrong member set are rejected by `verify`.
5. A row bundle exported from the console verifies offline in the CLI with the documented exit
   codes; the console shows the same browser checks as Attest.
6. The distribution carries `tools/yano-explorer`, `product-ui/explorer`, and `examples/explorer`.

## 10. Consequences

Positive: every product gets search, timelines, and trails with evidence; the trust root stays at
the node and the anchor; rebuild is a command. Costs: a second database to operate; proof material
must be captured before pruning; the service's verification levels must be read, not assumed.

## 11. Implementation record

Implemented on `feat/products-oob` (2026-09-06) as `products/explorer/{core,cli,ui}` plus
`products/explorer/harness/explorer.sh`, the user guide `docs/appchain/EXPLORER.md`, and the
docs-site page. No consensus, runtime, node, or SDK change.

**Gates.**

- `:products:explorer:core:test` 19 tests: module decoders against the contract encoders, index
  store (identity pinning, successor-only writes, search, content, export order, owner-only
  file), archiver (SHA-256 or Blake2b match, mismatch not stored, allow-list), classpath firewall,
  and the cluster test on real three-member doc-trail and kv-registry clusters served through the
  node's REST shapes: index from height 0, every block `VERIFIED_DECLARED` with a captured block
  record proof, trail head `MATCH`, content-verified revisions, row and state bundles under
  declared, pinned, wrong, and tampered inputs (body, root, decoded fact, absent subject), two
  rebuilds exporting identically, pinned follower labels and refusal, identity mismatch refusal,
  topic routing on a kv-registry chain that submits on `kv.command.v1`, and the service routes.
- `:products:explorer:cli:test` 4 tests: usage and exit codes on the goldens (6 declared, 5
  pinned, 4 tampered or not a bundle), classpath firewall.
- Console: `npm run check` (0 errors), `npm test` (14, including the browser checks on the golden
  row bundle and three tampering cases), `npm run build`.
- `verifyArtifactInventory`, `verifyJvmOnlyBuild`, and `verifyYanoXJvmDistribution` pass (the
  zip carries `tools/yano-explorer`, `product-ui/explorer`, and `examples/explorer`).

**Live (pre14 showcase, `run all`).** The service indexed all 13 chains the node lists; every
block of the 9 with traffic (including `showcase-composite`, `document-review`, and
`cardano-history`) was `VERIFIED_DECLARED` with the block record proof captured, and the 4
chains without traffic stayed at checkpoint 0; subject views matched proof-backed state for a
doc-trail entity, a registry key, a composite approval item (component-prefixed keys), and a
governed map entry; row and state bundles verified at exit 6 and, with the showcase members
pinned, at exit 5; two rebuilds exported identically. The service answered every route from the
same index and its bundles equal the CLI's byte for byte. The console in headless Chrome listed
the chains, opened the timeline, block, and message, verified the row in the browser (certified
block, envelope copy, message id, Ed25519 sender signature, inclusion path, block record), and
downloaded row and state bundles that the CLI verified at exit 6 and 5.

**Deviations and findings.**

| Topic | Decision | Deviation from §2 |
|---|---|---|
| Canonical block source | evidence bundle of the block's first message; the state-proof envelope carries only the v2 header view on pre14 | none after the pre-implementation correction |
| Segment harvesting | implemented; `/evidence/{id}` on the unanchored showcase carries exactly one block, so harvesting pays only on anchored chains | §2.1's "often covers several heights" holds for anchored chains only |
| Topic routing | derived from the capability manifest: a plain stock chain applies its machine to every topic (the showcase's registry chain submits on `kv.command.v1`, not the contract's default), composites route by component topics with the contract defaults as fallback | §2.2 said "decode by topic" |
| Rebuilt evidence bundles | carry a `StateSnapshot` of the pinned identity, without which `EvidenceVerifier.verify` under a pinned profile and genesis fails | none; implementation detail |
| Composite keys | the component prefix applies when the manifest component's state namespace starts with `component/` (plain chains use `application/v1`) | none |
| Subject routes | one `/subjects/{module}/{subject}` route with `/trails` as the doc-trail alias, as the first slice promised | §2.4 realigned |
| Showcase on pre14 | member nodes failed to start because node 0 projects L1 history into the shared home (`RUNTIME_INITIALIZATION_FAILED: a fresh chainstate cannot adopt an archive`); `showcase.sh write_node_configs` now sets `yano.history.projection.enabled=false` for member nodes of the light profile. The `evidence` and `eutxo` profiles delegate to other scripts and were not checked | a defect fix outside the product, no core change |
| `JSON_ONLY` rows | never upgrade on their own; `rebuild` once the node retains the evidence (guide) | documented |
| Unknown chains | the service answers 404 for chains it does not index | none |
