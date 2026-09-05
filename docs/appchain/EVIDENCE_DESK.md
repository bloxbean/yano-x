# Evidence Desk

The Evidence Desk is a browser workbench for role-gated release on a Yano app chain. Issuers
propose a release, auditors from independent organizations approve or reject it with keys that
never leave their browser, the approved release is applied once, and every record the desk shows
is read back from the chain's authenticated state with its proof. It is the UI for the role
workflow (`domain-actors` and `role-approvals`) and for the evidence product, per ADR-048.

Yano is pre-release. The desk is `preview` and inherits the devnet and showcase posture of the
rest of the repository.

## What the desk does

| Journey | What happens | What is proven |
|---|---|---|
| Connect | Reads node identity and lists chains whose composite carries `domain-actors` and `role-approvals`, with a release adapter chosen from the manifest | Chain id, tip, members, threshold, from the node |
| Directory | Organizations, actors, and policies resolved through their current pointer; approval statistics | Each record read through the state proof endpoint and bound to the root of the block it was committed in |
| Actor key | A 32-byte Ed25519 seed is imported into WebCrypto as a non-extractable key and matched against the actor's `ACTIVE` key epoch | The seed never leaves the tab; signing is refused unless the key matches |
| Propose | The release inputs (entity id, document digest, reference) are fixed, hashed with blake2b-256 into the statement's payload hash, and the signed statement is submitted | The chain's per-command result record, read back with its proof |
| Decide | Approve or reject a pending proposal; every bound field is copied from the proposal record; the desk predicts the chain's answer before signing | The result record and the updated proposal with its accepted decisions |
| Release | On a document-review chain, the approved release is submitted only when its inputs re-derive the proposal's payload hash | Consumption receipt and document head, bound to one root |
| Export | A JSON bundle of the proposal, policy, receipt, head, statistics, and directory records with their state proofs | Offline verification with the JVM verifier |

Release adapters:

| Adapter | Selected when the manifest lists | Release topic | Status in v1 |
|---|---|---|---|
| `document-review` | `documents` and `document-review-receipts` (the light showcase's `document-review-chain`) | `document-review.release.v1` | Propose, decide, release, receipt, head |
| `role-evidence` | `evidence` (the evidence product's `role-evidence` profile) | `evidence.release.v1` | Decide and inspect; propose and release with `demo.sh publish` (ADR-048 §7) |
| `approvals-only` | only the two role components | none | Decide and inspect |

## Modules

| Module | Path | What it is |
|---|---|---|
| `yano-x-evidence-ui` | `products/evidence/ui` | Static SvelteKit site, packaged in the JVM distribution under `product-ui/evidence` |
| golden fixture | `examples/showcase` test `EvidenceDeskGoldenTest` | Bytes produced by the Java contracts that the browser port is tested against |

## Setup

### Option A: the light showcase

The light showcase starts three members with a `document-review-chain` whose genesis registers
three organizations, four actors, and the `document-release` policy (an `issuer` proposes, two
`auditor`s from distinct organizations approve).

```bash
cd examples/showcase/src/main/showcase
./showcase.sh quickstart --instance demo
```

Node REST bases are `http://127.0.0.1:7070/api/v1`, `7071`, and `7072`. The API key defaults to
`yano-local-cluster-full-key`. If port 7070 is taken, add `--http-base 7170 --server-base 9170`.
`./showcase.sh stop --instance demo` stops the instance.

The showcase actors sign with deterministic demo seeds, showcase-only material derived as
`sha256("yano-showcase-demo-actor:" + actorId)`:

```bash
python3 -c 'import hashlib,sys;print(hashlib.sha256(("yano-showcase-demo-actor:"+sys.argv[1]).encode()).hexdigest())' issuer-a
```

| Actor | Organization | Roles |
|---|---|---|
| `issuer-a` | `acme-manufacturing` | `issuer` |
| `auditor-a` | `auditor-guild-a` | `auditor` |
| `auditor-b` | `auditor-guild-b` | `auditor` |
| `registry-admin-a` | `acme-manufacturing` | `registry-admin` |

### Option B: the evidence harness

`products/evidence/harness/demo.sh up --machine role --continuation direct` starts the
`role-evidence` profile with its connectors (see the harness README and tutorial 5). The desk
connects to any member and works in `role-evidence` mode: directory, proposals, and decisions on
the `evidence-release` policy; releases are made with `demo.sh publish`. Actor seeds are the
owner-only files the harness generates for its demo actors; never copy them into the node or the
runtime configuration.

### Build and serve the UI

```bash
cd products/evidence/ui
npm ci && npm run check && npm test && npm run build
npx --yes serve build/site
```

Or from the repository root: `./gradlew :products:evidence:ui:frontendBuild`. Serve `build/site`
from any static host. `evidence-ui-config.json` next to `index.html` may pin endpoints, a default
chain, an expected network, and directory hints:

```json
{
  "schemaVersion": 1,
  "productId": "evidence",
  "endpoints": [{ "id": "showcase", "nodeUrl": "http://127.0.0.1:7070", "apiPrefix": "/api/v1", "label": "Showcase" }],
  "defaultChainId": "document-review-chain",
  "expectedNetwork": "",
  "allowEndpointOverride": true,
  "directoryHints": {
    "document-review-chain": {
      "organizations": ["acme-manufacturing", "auditor-guild-a", "auditor-guild-b"],
      "actors": ["issuer-a", "auditor-a", "auditor-b", "registry-admin-a"],
      "policies": ["document-release"]
    }
  }
}
```

A node that is not same-origin with the UI must allow the UI origin through CORS, including
`GET`, `POST`, `Content-Type`, and `X-API-Key`; hosting the UI behind the node's reverse proxy
avoids CORS entirely. For local development `npm run dev` serves the site on `127.0.0.1:4173`.

## Walkthrough on the showcase

1. **Connect.** Enter `http://127.0.0.1:7070`, prefix `/api/v1`, and the API key. Pick
   `document-review-chain` in the chain picker; the adapter reads `document-review`.
2. **Load the issuer's key.** Open *Actor key*, enter `issuer-a` and the demo seed printed by the
   Python one-liner above. The desk derives the public key, reads the actor record with its proof,
   and confirms the key is `issuer-a-k1`.
3. **Propose.** Open *Propose a release*, choose a file (hashed in the browser) or paste a SHA-256,
   give a proposal id such as `review-2026-0042`, a document entity id, and a reference. *Prepare
   the statement* shows every field the signature binds, including the payload hash and how it was
   derived; *Sign and submit* waits for finality and shows the chain's result, `ACCEPTED`.
4. **Approve twice.** Release the key, load `auditor-a`, open the proposal under *Proposals and
   decisions*, choose the `independent-auditors` clause, *Prepare approval*, *Sign and submit*.
   Repeat with `auditor-b`; the clause shows two distinct organizations and the proposal becomes
   `APPROVED`. Try approving as `issuer-a` first: the desk predicts and the chain answers
   `ROLE_MISMATCH`; submitting the same auditor statement twice answers `EXACT_REPLAY`.
5. **Release.** Back as `issuer-a`, the proposal's *Release* form is pre-filled with the inputs this
   browser proposed (type them again from another browser). The desk checks that blake2b-256 of the
   command equals the proposal's payload hash before submitting. After finality it shows the
   consumption receipt, the document head at revision 1, and both proofs bound to the same root.
6. **Export.** *Build export bundle* downloads `<proposal>.evidence-desk.json` with every record
   and its state proof for offline verification.

## Chain outcomes

Finalized statements that fail the workflow's rules are deterministic no-ops. The chain records
the outcome of every finalized command; the desk reads it with a proof and explains it:

| Code | Meaning |
|---|---|
| `ACCEPTED` | Applied |
| `ROLE_MISMATCH` | The actor holds no role the clause or policy requires, or rejection is disabled |
| `DISTINCTNESS_DUPLICATE` | Another actor from the same organization already decided this clause |
| `EXACT_REPLAY` | The same statement was already applied |
| `CONFLICT` | The statement contradicts the proposal or an earlier decision by the same actor |
| `EXPIRED` | The proposal's deadline height has passed |
| `TERMINAL` | The proposal is no longer pending |
| `UNAUTHORIZED_ACTOR`, `WRONG_REVISION`, `INVALID_SIGNATURE` | Actor, revision, key, or signature problems |

## Proof states

`BOUND` on a record means the node's state proof names that key, that height, and that root, and
the finalized block at that height carries the same root. `MISMATCH` means one of those
disagreed; the desk still shows the record but flags it. The MPF proof path and threshold finality
are verified by the JVM verifier on the export bundle, as with Attest certificates; the browser
does not re-verify them.

## Security notes

- Seeds are imported into WebCrypto as non-extractable keys and dropped when you release them,
  change chain, or change node. They are never logged, stored, or sent.
- API keys stay in memory for the tab.
- Every statement is shown field by field before signing; the deadline of a proposal is
  `tip + min(policy maximum lifetime, 300)` and approvals copy the proposal's deadline.
- Query payloads are decoded with a bounded canonical CBOR reader and re-encoded before use.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| "no app chain carries the domain-actors and role-approvals components" | The chain runs another state machine; the desk needs a role composite. |
| "The seed's public key … is not …'s ACTIVE key" | Wrong seed or wrong actor id; on the showcase derive the seed for that actor id. |
| "This browser cannot sign with Ed25519 through WebCrypto" | Use a current Chrome, Firefox, or Safari, or sign with `yano appchain role sign`. |
| Proposals list is empty | Only the last 2,000 blocks are scanned; open a proposal by id. |
| Release form rejects the inputs | They must be byte-for-byte the proposal's inputs; the payload hash commits to entity id, digest, and reference. |
| `MISMATCH` on a record | The node's proof did not bind to the block at that height; refresh, and treat the record as unverified. |

## Tests

```bash
./gradlew :examples:showcase:test --tests EvidenceDeskGoldenTest
cd products/evidence/ui && npm ci && npm run check && npm test && npm run build
```

Add the Yano version properties the repository currently requires (see `docs/BUILD_AND_TEST.md`).
The golden test writes the fixture with `-PevidenceGoldenWrite=true` and verifies the committed
copy otherwise. The UI suite also carries an opt-in live run of the whole flow, including the
negative cases, against a running chain:

```bash
cd products/evidence/ui && EVIDENCE_DESK_LIVE_NODE_URL=http://127.0.0.1:7070 \
  EVIDENCE_DESK_LIVE_API_KEY=yano-local-cluster-full-key npx vitest run src/lib/live.test.ts
```
