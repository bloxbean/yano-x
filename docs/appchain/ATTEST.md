# Attest: document attestation certificates

Attest records the SHA-256 digest of a document on a Yano app chain that runs the stock
`doc-trail` state machine and hands out a portable certificate. Anyone holding the certificate can
verify, offline, that a chain member signed an append carrying exactly that digest, that the message
sits in a block the chain's threshold finalized, what revision the series had at that block, and,
when the chain anchors to Cardano, that a Cardano transaction commits to that block.

Attest is a product built entirely from existing Yano primitives: the evidence bundle, the compact
message proof, the height-pinned state proof, and the script-anchor datum. It adds no plugin and no
new proof format. Design and rationale: [ADR-047](../../adr/047-attestation-and-certificate-service.md).

Yano is pre-release. Attest is `preview` and inherits the devnet and showcase posture of the rest
of Yano X: use disposable data and non-production credentials unless your deployment says otherwise.

## What a certificate proves

| # | Check | Question it answers | Where it runs |
|---|---|---|---|
| C1 | Digest | Does the file hash to the attested SHA-256? | UI, CLI |
| C2 | Command binding | Did a chain member sign a doc-trail append carrying this digest, series id, and reference? | UI, CLI |
| C3 | Message inclusion | Is that message at a fixed position of the stated block? | UI, CLI |
| C4 | Finality | Did the chain's threshold of members sign that block? | CLI |
| C5 | Anchor linkage | Does a Cardano state-thread datum commit to that block, state root, membership, and application id? | CLI, with the datum |
| C6 | Trail head | Did the series have the stated revision count and head digest at that block? | CLI |

The result carries an ADR-037 trust level, never a bare "valid":

| Trust input | Trust level | Meaning |
|---|---|---|
| none | `INTERNAL_CONSISTENCY_ONLY` | The certificate agrees with itself and with the members it declares. Not accepted. |
| `--members keys.json` | `CALLER_PINNED_ROOT` | Finality verified against member keys you obtained independently. |
| `--anchor-datum-hex <cbor>` | `INDEPENDENTLY_VERIFIED_L1_ANCHOR` | The evidence segment matches the datum you read from Cardano. |

What it does not prove: who the person behind the file was (the signer is the ingress node's
member key; put authorship into the reference, which the command binds on chain), anything about
the content beyond its digest, or a calendar time (ordering comes from block height and, when
anchored, the Cardano slot).

## Modules

| Artifact | Path | Role |
|---|---|---|
| `yano-x-attest-client` | `products/attest/client` | `AttestCertificate`, strict codec, `AttestClient`, offline `AttestVerifier` |
| `yano-x-attest-cli` | `products/attest/cli` | `yano-attest attest`, `certificate`, `verify`, `trail`, `status` |
| `yano-x-attest-ui` | `products/attest/ui` | Static SvelteKit application, ADR-040 style; files never leave the browser |

## Setup

### Option A: the showcase

The light showcase starts three members with a `documents-chain` that runs `doc-trail`.

```bash
cd examples/showcase/src/main/showcase
./showcase.sh quickstart --instance demo
```

Node REST bases are `http://127.0.0.1:7070/api/v1`, `7071`, and `7072` (one per member). The API
key defaults to `yano-local-cluster-full-key` and can be changed with `YANO_CLUSTER_API_KEY`. The
chain id is `documents-chain`. If port 7070 is taken, add `--http-base 7170 --server-base 9170`
(any free ranges) and use those ports below. `./showcase.sh down --instance demo` stops the
instance.

### Option B: your own chain

Any chain whose configuration selects the stock state machine works:

```yaml
app-chain:
  chain-id: documents
  state-machine: doc-trail
```

See the [`doc-trail` reference](state-machines/doc-trail.md) and
[Your first app chain](tutorials/01-first-app-chain.md). Composite chains that embed a `documents`
component are not supported by this version of Attest.

### Build the CLI

```bash
./gradlew :products:attest:cli:installDist
export PATH="$PWD/products/attest/cli/build/install/yano-attest/bin:$PATH"
yano-attest --help
```

The Yano X JVM distribution ships the same launcher under `tools/yano-attest/bin/yano-attest` and
the built UI under `product-ui/attest`.

### Build and serve the UI

```bash
cd products/attest/ui
npm ci
npm run build          # writes build/site
npx --yes serve build/site
```

Or from the repository root: `./gradlew :products:attest:ui:frontendBuild`. Serve `build/site` from
any static host. Edit `attest-ui-config.json` next to `index.html` to preconfigure endpoints, pin a
default chain, or require a Cardano network. A node that is not same-origin with the UI must allow
the UI origin through CORS, including `GET`, `POST`, `Content-Type`, and `X-API-Key`; hosting the
UI behind the node's reverse proxy avoids CORS entirely. For local development `npm run dev` serves
the UI on `http://127.0.0.1:4173`.

## CLI walkthrough

Every command that talks to a node takes `--url <REST base including /api/v1>` and `--chain <id>`.
Optional `--api-key` sends `X-API-Key`.

```bash
export NODE=http://127.0.0.1:7070/api/v1
export KEY=yano-local-cluster-full-key

# 1. Confirm the chain runs doc-trail and see its membership
yano-attest status --url $NODE --chain documents-chain --api-key $KEY

# 2. Attest a file: hash, submit, wait for finality, write the certificate
yano-attest attest --url $NODE --chain documents-chain --api-key $KEY \
  --file contract.pdf --entity contract-2026-0042 --reference "signed by legal, ticket 8812" \
  --label "Supply contract" --output contract.pdf.attest.json

# 3. Verify offline, pinning the member keys you obtained independently
yano-attest verify --certificate contract.pdf.attest.json --file contract.pdf --members keys.json

# 4. Look up the series
yano-attest trail --url $NODE --chain documents-chain --api-key $KEY --entity contract-2026-0042
```

`--entity` is optional. Without it the client derives `sha256:<digest>`, a one-entry series per
document. Reuse a series id to chain revisions: the second `attest` of the same series yields a
certificate whose trail head says revision 2, and the head digest chains both entries with the
member keys that recorded them.

`certificate` re-issues a certificate for a message that was attested earlier, from the browser or
from another client:

```bash
yano-attest certificate --url $NODE --chain documents-chain --api-key $KEY \
  --message-id <64 hex> --file contract.pdf --output contract.pdf.attest.json
```

### The members file

`keys.json` pins the chain's exact membership for finality verification. Obtain it from the
chain's genesis or deployment configuration, from an operator you trust, or from your own nodes,
never from the certificate itself. Each node reports only its own key as `memberKey` in
`/app-chain/chains/{id}/status`, so ask every member or read the deployment record; on the
showcase the instance's `data/showcase/<instance>/cluster/cluster-appchain-identity.json` lists
`members` and `threshold`, and `status` reports `stateCommitment.profile` and `genesisId`:

```json
{
  "chainId": "documents-chain",
  "memberKeysHex": ["<32-byte hex key>", "<32-byte hex key>", "<32-byte hex key>"],
  "threshold": 3,
  "profile": "mpf-blake2b256-v1",
  "genesisIdHex": "<32-byte hex genesis id>"
}
```

`profile` and `genesisIdHex` are optional and must be given together; they additionally pin the
state commitment identity reported by `status`.

### Verifying against the Cardano anchor

When the chain anchors in script mode (ADR-008.4), the node includes the next anchored block in
the evidence bundle once that block exists, and `attest` or `certificate` produces an `ANCHORED`
certificate whose `anchorReference` names the Cardano transaction and slot. Read the inline datum
of the chain's state-thread output from a Cardano source you trust and pass it as canonical CBOR
hex:

```bash
yano-attest verify --certificate contract.pdf.attest.json --file contract.pdf \
  --anchor-datum-hex <cbor hex> [--application-id doc-trail]
```

The datum must name the same chain id, genesis id, commitment profile, format fingerprint, height,
block hash, state root, sorted member set, threshold, and application id as the evidence segment.
`--application-id` is only needed when the certificate recorded none; stdlib chains record
`doc-trail`. On the showcase, `./showcase.sh anchor bootstrap documents-chain --instance demo`
funds and bootstraps devnet anchoring; a certificate issued before the next anchor stays
`FINALIZED` and can be re-issued later with `certificate`.

### Exit codes

| Exit | Meaning |
|---|---|
| `0` | accepted at `INDEPENDENTLY_VERIFIED_L1_ANCHOR` |
| `2` | usage error |
| `3` | node, file, or input unavailable, or the message is not finalized |
| `4` | invalid: a check failed, the certificate is malformed, or a trust input was rejected |
| `5` | accepted at `CALLER_PINNED_ROOT` |
| `6` | consistent only: no trust input was supplied |

`verify --json` prints every check, the trust level, `accepted`, `consistent`, and the failure
reasons, so scripts never have to infer them from the exit code. Treat only `0` and `5` as
verified.

## Java usage

```java
var client = AttestClient.builder("http://127.0.0.1:7070/api/v1", "documents-chain")
        .apiKey(System.getenv("YANO_CLUSTER_API_KEY")).build();

byte[] document = Files.readAllBytes(Path.of("contract.pdf"));
var submission = client.attest(document, new AttestClient.AttestRequest("contract-2026-0042", "ticket 8812"));
client.awaitFinalized(submission.messageIdHex(), Duration.ofSeconds(60));
AttestCertificate certificate = client.certificate(submission.messageIdHex(),
        new AttestClient.SubjectMetadata("contract.pdf", (long) document.length, "application/pdf", null));
Files.writeString(Path.of("contract.pdf.attest.json"), AttestCertificateCodec.toJson(certificate));

// Anywhere, later, without a node:
AttestCertificate decoded = AttestCertificateCodec.fromJson(Files.readString(Path.of("contract.pdf.attest.json")));
AttestTrust trust = AttestTrust.CallerPinned.fromJson(Files.readString(Path.of("keys.json")));
AttestVerification result = AttestVerifier.verify(decoded, document, trust);
if (result.accepted()) { /* result.trustLevel() is CALLER_PINNED_ROOT */ }
```

`AttestVerifier` is static and offline. It re-decodes the embedded node documents with the strict
decoders of the pinned Yano release and composes `EvidenceVerifier`, `MessageInclusionProof`,
`AppBlockCodec`, `DocTrailContract`, `ProofVerifier`, and `AnchorDatumV1`.

## UI walkthrough

1. **Connect.** Enter the node origin (for example `http://127.0.0.1:7070`), the API prefix, and
   an optional API key. The UI reads the node identity and lists only chains that report the
   `doc-trail` state machine. Or choose *Verify a certificate without connecting*.
2. **Attest a document.** Drop a file. The digest is computed with WebCrypto and shown before
   anything is sent. Optionally set a series id, a reference (bound on chain), and a label
   (certificate only). *Record on chain* submits the canonical append, waits for finality, collects
   the message proof, evidence bundle, and trail head, reads the signed envelope out of the evidence
   block, and offers the certificate for download.
3. **Verify a certificate.** Drop a certificate and optionally the original file. The browser runs
   the digest, envelope copy, message id, sender signature (WebCrypto Ed25519 where the browser
   supports it), command binding, inclusion path, and evidence identity checks. When a node is
   connected and its selected chain matches, it also compares the node's block at that height and
   its latest anchor, labelled `NODE_CONFIRMED_L1_REFERENCE`. Finality and anchor verification
   are the CLI's job, and the page says so.
4. **Look up a trail.** Enter a series id to read its revision count and head digest, proven
   against the node's finalized state root.

## Certificate format

`yano-x-attest-certificate-v1` is a JSON document with these sections. Embedded node documents
are kept verbatim so verification uses the same decoders that produced them.

| Field | Content |
|---|---|
| `chainId`, `applicationId`, `status` | Chain, the manifest application id at issue time, `FINALIZED` or `ANCHORED` |
| `subject` | `entityId`, `entryHashHex`, `hashAlgorithm`, optional `fileName`, `sizeBytes`, `mediaType`, `reference`, `label` |
| `message` | The complete signed envelope copied from the evidence block: id, height, index, topic, sender, sequence, expiry, body, auth scheme, auth proof |
| `messageProof` | `GET .../messages/{id}/proof` verbatim |
| `evidence` | `GET .../evidence/{id}` verbatim |
| `trailHead` | `GET .../state/proof/{key}?height=H` verbatim plus the decoded revision and head digest, or `null` |
| `anchorReference` | Derived from the evidence anchor reference and the segment's last state root, or `null` |

The Java codec enforces exact field sets, canonical lowercase hex, and a 44 MiB bound.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `status` exits 3 and reports `docTrail: false` | The chain runs another state machine. Point at a chain with `state-machine: doc-trail`. |
| `attest` times out waiting for finality | The chain is stalled or below threshold. Check `status` (`members`, `threshold`) and node logs. |
| `verify` exits 6 | No `--members` or `--anchor-datum-hex`. The certificate is consistent but unpinned. |
| `verify` reports `finality evidence invalid: bundle trust context mismatch` | The members file names another membership or threshold than the evidence. Re-check the source of your keys. |
| `verify --anchor-datum-hex` reports `evidence segment carries no anchor reference` | The certificate is `FINALIZED`. Re-issue it with `certificate` after the next anchor lands. |
| `trailHead` is `null` in a fresh certificate | The node no longer retains a height-pinned proof (proof pruning). The certificate remains valid without C6. |
| UI: `could not be reached` | CORS or TLS. Remote nodes need HTTPS and must allow the UI origin; loopback nodes may use HTTP. |
| UI: `C2c Sender signature` is `UNAVAILABLE` | The browser has no WebCrypto Ed25519. Run `yano-attest verify`. |

## Tests

```bash
./gradlew :products:attest:client:test :products:attest:cli:test verifyArtifactInventory verifyJvmOnlyBuild
cd products/attest/ui && npm ci && npm run check && npm test && npm run build
```

The client test starts a real three-node doc-trail cluster in process, attests through the same
REST paths the node exposes, verifies in every trust mode, and mutates each section to show that
only the matching check fails. `./gradlew :products:attest:client:test -PattestGoldenWrite=true`
regenerates the committed golden certificate, members file, and document that the CLI and UI
tests reuse.

The UI suite also carries an opt-in live run of the browser attest and verify sequence, skipped
unless a node is named:

```bash
cd products/attest/ui && ATTEST_LIVE_NODE_URL=http://127.0.0.1:7070 \
  ATTEST_LIVE_API_KEY=yano-local-cluster-full-key npx vitest run src/lib/live.test.ts
```

`ATTEST_LIVE_CHAIN` (default `documents-chain`) and `ATTEST_LIVE_API_PREFIX` (default `/api/v1`)
select the chain and prefix.
