# Verifiable Explorer

A derived index over stock Yano app chains that verifies every block before it writes a row,
decodes the finalized commands of the stock state machines, serves timelines, search, and
per-subject views with a proof-backed state check, and hands out row and state bundles that
verify offline. It is Product 4 of the ADR-046 portfolio; the decision record is
[ADR-050](../../adr/050-verifiable-explorer.md). The index is never an authority: nothing in
consensus, proof verification, or accounting reads it, and any node can rebuild it.

This guide covers what ships, how to set it up against the showcase or your own node, the CLI
walkthrough, the console, the trust levels, and troubleshooting.

## What it does

| Journey | What happens | What is proven |
|---|---|---|
| Index | `yano-explorer serve` (or `index`) follows a node's blocks over the public REST API; for each block with a message it fetches the node's evidence bundle, verifies it, captures the authenticated block record proof, decodes the messages, and writes the block in one transaction | The canonical block hashes and links, its message ids recompute, and its finality certificate verifies under declared or pinned members before any row exists |
| Browse | Chains with checkpoint, tip, lag, and levels; a timeline of blocks; each block's messages with their decoded rows | Every row names the message, height, and index it came from and the level of its block |
| Search | A message id, a height, a topic, a sender prefix, or a subject prefix | Hits open the indexed block, message, or subject |
| Subjects | A doc-trail entity, registry key, account, approval item, or map entry: its rows, the view derived from them, and the authenticated value read with a proof at the tip | `MATCH` or `DIVERGES` says whether every finalized command applied |
| Prove a row | `row` (or *Verify this row* in the console) assembles an `explorer-row-proof-v1` bundle from captured material; `verify` checks it offline | Finality under the caller's trust input, inclusion at the index, the block record at the certified root, the envelope copy and its id |
| Prove state | `subject ... --proof` builds an `explorer-state-proof-v1` bundle with the subject's proof at a height and the evidence of that block | The typed value at a certified root, decoded by the contract |
| Archive | `archive add --file` or an allow-listed `archive fetch` stores a body under its SHA-256 after checking it against the committed entry hash | A trail revision becomes `CONTENT_VERIFIED` and its body is served by hash |

## Modules

| Module | Path | What it is |
|---|---|---|
| `yano-x-explorer-core` | `products/explorer/core` | Follower, index store (SQLite), stock modules, subjects, archiver, bundles, verifier, read service |
| `yano-x-explorer-cli` | `products/explorer/cli` | `yano-explorer` (`tools/yano-explorer` in the JVM distribution) |
| `yano-x-explorer-ui` | `products/explorer/ui` | Static SvelteKit console (`product-ui/explorer` in the JVM distribution) |
| launcher | `products/explorer/harness/explorer.sh` (`examples/explorer` in the distribution) | Runs the service against a node, by default a showcase instance |

## What the index holds

| Table | Content |
|---|---|
| `chains` | The pinned identity per chain (application id, commitment profile, state genesis id, manifest digest) and the checkpoint |
| `blocks` | Header fields, level, canonical block bytes, declared members and threshold, anchor reference, the block record proof envelope |
| `messages` | Every finalized envelope with its body (captured before retention can strip it), or a tombstone |
| `subject_rows` | One row per decoded command (or per mutation of a map action): module, kind, subject, operation, fields |
| `subjects` | Aggregates per subject for listing and search |
| `content` | Archived bodies by SHA-256 and Blake2b-256 with their match status |

Modules decode by the chain's capability manifest: a plain stock chain applies its machine to
every message whatever the topic, a composite routes by the topics its components declare, and
composite components' state keys carry the component prefix.

| Module | Subject | Rows | State subject |
|---|---|---|---|
| `doc-trail` | entity id | `APPEND` with entry hash, reference, author | document head (revision count, head digest) |
| `kv-registry` | key (hex) | `PUT`, `DELETE` with value | registry entry (owner, value) |
| `balances` | account | `MINT`, `TRANSFER` with amount | account balance |
| `approvals` | item id | `PROPOSE`, `APPROVE`, `REJECT` | approval outcome (status, approvers, payload digest) |
| `authenticated-map` | `collection/keyHex` | one row per mutation with authorization kind and action commitment | map entry (status, revision, controller, value) |

Rows are finalized commands. A command the machine rejected (an unauthorized registry put, an
underfunded transfer) still finalized, which is why the subject view also reads the authenticated
value. Role-approval commands are indexed as generic rows in this slice.

## Setup

### Option A: the showcase and the launcher

```bash
# 1. Start a showcase instance and give it traffic
cd yano-x-jvm-<version>                                  # the extracted Yano X JVM ZIP
examples/showcase/showcase.sh up --profile light --instance demo
examples/showcase/showcase.sh run all --instance demo

# 2. Start the explorer service against node 0 (default key: the cluster launcher's)
tools/yano-explorer/bin/yano-explorer chains --url http://127.0.0.1:7070
examples/explorer/explorer.sh up --url http://127.0.0.1:7070 --instance demo
examples/explorer/explorer.sh status --instance demo
eval "$(examples/explorer/explorer.sh env --instance demo)"
# EXPLORER_SERVICE_URL=http://127.0.0.1:8490, EXPLORER_DB=~/.yano-x/explorer/demo/explorer.db
```

From a source checkout the launcher finds `products/explorer/cli/build/install/yano-explorer`
after `./gradlew :products:explorer:cli:installDist`, and the showcase of a distribution
extracted to `build/yano-x` (see [Build distributions](../BUILD_DISTRIBUTIONS.md)). Pass `--api-key-file` for a node with its own key, and
`--members members.json` (`{"chainId", "memberKeysHex": [...], "threshold"}`) to label blocks
`VERIFIED_PINNED` instead of `VERIFIED_DECLARED`.

### Option B: your own node

```bash
export YANO_API_KEY=...                                   # or --api-key-file
yano-explorer chains --url https://node.example.com
yano-explorer index --url https://node.example.com --db ~/.yano-x/explorer/explorer.db \
  --chain orders-chain,documents-chain
yano-explorer serve --url https://node.example.com --db ~/.yano-x/explorer/explorer.db \
  --chain orders-chain,documents-chain --bind 127.0.0.1 --port 8490
```

`serve` binds to loopback by default; put a reverse proxy with TLS in front of it for the
console and other readers. The node API key never leaves the service.

### Build and serve the console

```bash
cd products/explorer/ui && npm ci && npm run build      # build/site
```

Copy `build/site` (or `product-ui/explorer` from the distribution) to any static host, and edit
`explorer-ui-config.json` next to `index.html`:

```json
{ "schemaVersion": 1, "productId": "explorer",
  "serviceUrl": "https://explorer.example.com", "endpoints": [], "defaultChainId": "",
  "allowServiceOverride": true }
```

The console talks to the service only (CORS is open on the read routes); remote services must
be HTTPS.

## Walkthrough on the showcase

```bash
X=tools/yano-explorer/bin/yano-explorer; URL=http://127.0.0.1:7070; DB=~/.yano-x/explorer/demo/explorer.db
$X status --url $URL --db $DB                                   # identity, checkpoint, tip, levels per chain
$X search 1 --url $URL --db $DB --chain documents-chain          # a height
$X export --url $URL --db $DB --chain documents-chain | head     # blocks, messages, rows as JSON lines
ENTITY=$($X export --url $URL --db $DB --chain documents-chain | python3 -c 'import sys,json
for l in sys.stdin:
    d=json.loads(l)
    if d["type"]=="row": print(d["subject"]); break')
$X trail $ENTITY --url $URL --db $DB --chain documents-chain      # revisions, recomputed head, state check MATCH
MSG=$($X export --url $URL --db $DB --chain documents-chain | python3 -c 'import sys,json
for l in sys.stdin:
    d=json.loads(l)
    if d["type"]=="message": print(d["messageId"]); break')
$X row $MSG --url $URL --db $DB --chain documents-chain --output row.json
$X verify --bundle row.json                                      # exit 6: consistent with declared members
$X verify --bundle row.json --members members.json               # exit 5: verified under pinned members
$X trail $ENTITY --url $URL --db $DB --chain documents-chain --proof --output state.json
$X verify --bundle state.json --members members.json             # exit 5
$X subject approvals <item> --url $URL --db $DB --chain workflow-chain   # a composite component
$X rebuild --url $URL --db $DB --chain documents-chain            # drop and index again; export is identical
```

`members.json` for the showcase is the member set the evidence bundle declares; a real
deployment gets it from the operator out of band. With a Cardano anchor,
`verify --anchor-datum-hex <hex>` binds the bundle to the anchored height, root, block hash,
genesis, and member set and exits 0.

## The console

Connect with the service URL. Sections: **Chains** (identity, checkpoint, tip, levels, topics),
**Blocks and messages** (timeline, block detail, message detail with decoded rows and *Verify
this row*), **Search**, **Subjects and trails** (module filter, subject detail with the derived
view, the authenticated state, revisions with availability and the archived body, *Build state
proof*), and **What this proves**.

*Verify this row* fetches the row bundle and runs the browser checks: certified block header,
envelope copy, message id, sender signature (WebCrypto Ed25519 where available), inclusion
path, block record. Finality under pinned members and the anchor are labelled as CLI checks; the
downloaded bundle is what `yano-explorer verify` reads.

## Verification levels and trust levels

| Label | Meaning |
|---|---|
| `VERIFIED_PINNED` | The block's evidence bundle verified under members and threshold the operator pinned with `--members` |
| `VERIFIED_DECLARED` | The evidence bundle verified under the members it declares itself: internal consistency at ingest |
| `HEADER_ONLY` | A block without a message has no evidence bundle; its header is the node's JSON view |
| `JSON_ONLY` | The node no longer retains the evidence for that height; rows come from its JSON view and cannot be proven from the index |
| `MATCH` / `DIVERGES` / `READ` | The subject's derived view equals the proof-backed value; at least one command did not apply; the value was read but the module derives nothing comparable |
| `FINALIZED` / `CONTENT_VERIFIED` | A trail revision is finalized; an archived body hashes to its entry hash and is served by hash |
| `INTERNAL_CONSISTENCY_ONLY` (exit 6) | `verify` with the bundle's own members |
| `CALLER_PINNED_ROOT` (exit 5) | `verify --members` |
| `CALLER_PINNED_ANCHOR` (exit 0) | `verify --anchor-datum-hex` |

A `JSON_ONLY` block never upgrades on its own: run `rebuild` for the chain once the node
retains evidence again (an archival node, or a node whose retention covers the height).

## Security notes

- The service is GET-only, binds to loopback by default, and holds the node API key in memory.
- Fetches follow only allow-listed URL prefixes, never redirects, and are bounded (16 MiB).
- The index file and content directory are created owner-only; SQLite's `-wal` and `-shm`
  sidecars inherit the umask when the directory already exists, so keep the directory private.
- Bounds everywhere: 8 MiB block JSON, 40 MiB evidence bundles, 48 MiB bundles on `verify`,
  256-character searches, pages of at most 200.
- `explorer.sh env` prints shell exports; `eval` it in a shell whose history you control.
- No JSON-LD or RDF processor is on the runtime classpath; a test in each module enforces it.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `index for <chain> was built for another identity` | The database was indexed from another chain or genesis; use another `--db` or `rebuild` |
| `evidence bundle for <id> does not verify` | With `--members`, the pinned set or threshold is wrong; without it, the node's evidence is inconsistent, which is worth reporting |
| A block is `JSON_ONLY` | The node returned no evidence for its first message (retention); `rebuild` against a node that retains it |
| `the row cannot be proven from the index` | The block was indexed `JSON_ONLY`; see above |
| `block record proof unavailable` in a block's diagnostic | The node's proof material for that height is pruned; the row still binds to the certified header |
| Showcase member nodes fail with `cannot adopt an archive that already covers blocks` | The pre14 devnet profile projects L1 history under the shared home; the showcase now disables the projection on member nodes (`write_node_configs`); older distributions need the same overlay |
| Console says `indexes no chain yet` | `serve` has not caught up; check `explorer.sh status` |

## Tests

```bash
./gradlew :products:explorer:core:test :products:explorer:cli:test
cd products/explorer/ui && npm ci && npm run check && npm test && npm run build
./gradlew :products:explorer:core:test -PexplorerGoldenWrite=true   # regenerate the goldens
```

The core test runs real three-member doc-trail and kv-registry clusters in process, indexes them
through the node's REST shapes, verifies rows and bundles under every trust input, tampers with
bundles, rebuilds twice, and exercises the service routes; it writes the goldens the CLI and
console tests pin.
