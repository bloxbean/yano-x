# ADR-047 — Attestation and Certificate Service (`products/attest`)

- **Status:** Accepted and implemented on `feat/products-oob` (2026-09-06); §15 records the
  deviations from the proposal that implementation required.
- **Date:** 2026-09-05
- **Owners:** Yano X product maintainers
- **Scope:** The first product selected by [ADR-046](046-out-of-the-box-product-portfolio.md) §4.1:
  a client library, a command-line verifier, a separately deployable browser UI, and a user guide
  that turn a stock `doc-trail` app chain plus Cardano anchoring into a tamper-evidence and
  timestamping service with portable, offline-verifiable certificates.
- **Related:** [ADR-046 portfolio](046-out-of-the-box-product-portfolio.md),
  [ADR-040 product UIs](040-product-specific-user-interfaces.md) (brought forward verbatim from the
  feature branch so this decision can cite it),
  [ADR-037 proof lab](app-layer/037-generic-appchain-proof-lab-and-subject-discovery.md),
  [ADR-031 portable proofs](app-layer/031-composable-state-machine-foundation-and-portable-proofs.md),
  [ADR-033 showcase catalog](app-layer/033-showcase-reference-catalog-and-capability-discovery.md),
  [ADR-035 Cardano History product](app-layer/035-cardano-history-product-console-and-distribution.md)
  (packaging precedent), the ADR-006 E3.4 evidence bundle, use case A4 in the
  [use-case catalog](../docs/APP_CHAIN_USE_CASES.md), and the Yano uVerify research note.

## 1. Context

Proof of existence and tamper evidence is the simplest problem the platform already solves
completely, and it has no product surface. Today a user who wants "prove these bytes existed by
this time and were attested by these parties" must hash a file by hand, encode a `doc-trail`
command with a tutorial script, submit it with `curl`, and then assemble a message proof, an
evidence bundle, and an anchor reference from three endpoints before anyone can verify anything.

Every ingredient exists in the pinned Yano release and in Yano X:

| Ingredient | Where it lives | Offline decoder |
|---|---|---|
| `doc-trail` state machine, command codec, entity key | `state-machines/stdlib`, `stdlib-contracts` | `DocTrailContract` |
| Compact message inclusion proof | `GET .../messages/{id}/proof` | `AppChainClient.decodeMessageProof` |
| Portable evidence bundle: signed block segment, member set, threshold, anchor reference | `GET .../evidence/{id}` | `EvidenceBundleCodec.fromJson`, `EvidenceVerifier.verify` |
| Profile-tagged state proof of the trail head | `GET .../state/proof/{key}?height=` | `AppChainClient.decodeProofEnvelope`, `ProofVerifier.verify` |
| Node-confirmed Cardano anchor | `GET .../anchor/commitment` | parsed as in the Cardano History client |
| Independently obtained anchor datum | Cardano explorer, Koios, or db-sync | `AnchorDatumV1.decode`, `ProofVerifier.trustedRootFromCardanoAnchor` |

What is missing is one certificate that binds these documents to a subject, a verifier that
evaluates them with the ADR-037 vocabulary, a CLI, and a UI. ADR-046 sized this as S because no
consensus code changes.

## 2. Decision summary

1. Add `products/attest` with three modules: `client` (library), `cli` (tool), and `ui`
   (web application). No runtime plugin, no new state machine, no host change.
2. The product runs on any standalone `doc-trail` chain. Version one does not require a new
   chain profile, recipe, or showcase script; the guide uses the light showcase's
   `documents-chain` and its anchor bootstrap.
3. A certificate is a JSON document, schema `yano-x-attest-certificate-v1`, that embeds the
   node's evidence bundle, message proof, optional trail-head state proof, and optional anchor
   reference verbatim, plus a subject section describing the attested bytes.
4. Verification composes existing verifiers and reports six independent checks with ADR-037
   trust levels. It never collapses them into one flag.
5. Trust inputs come from the caller: nothing, a pinned member set and threshold, or an
   independently obtained Cardano anchor datum. The CLI never fetches L1 data in version one.
6. The UI follows ADR-040: a separately built static artifact, runtime connection, discovery by
   capability, public APIs only, no plugin JavaScript. It hashes files locally and never uploads
   them. Local browser checks cover the digest, the command binding, and the message path;
   block, finality, and anchor checks are node-confirmed in the browser and independent in the CLI.
7. A certificate has two lifecycle states, `FINALIZED` and `ANCHORED`, and can be upgraded by
   re-fetching with the message id.
8. Classification per ADR-022: `FIRST_PARTY_OPTIONAL` artifacts at maturity `preview`, on the
   devnet and showcase posture that ADR-045 supports.

## 3. Goals and non-goals

### 3.1 Goals

- Attest a file or an arbitrary digest in one command or one browser action.
- Produce a certificate a third party can verify without contacting any Yano node.
- Distinguish, in every result, what was verified locally, what was pinned by the caller, what
  the node confirmed, and what remains unproven.
- Reuse every existing codec and verifier; add no new proof wire format.

### 3.2 Non-goals for version one, each recorded as a follow-up in §14

- Composite chains that embed a `documents` component. Those need composite physical keys and
  a different discovery rule.
- A Studio recipe. None exists for `doc-trail`, and adding one touches devtools and catalog
  verification.
- Showcase script changes or a product chain.
- QR codes, shareable verification links, and hosted certificate pages.
- A uVerify publication effect or per-record Cardano assets.
- Independent finality verification inside the browser.
- Evidence segments that span a governed membership change. `EvidenceVerifier` pins one member
  set for the whole segment.
- Legal notarization claims. The product name is attestation.

## 4. Module layout and artifacts

| Module | Artifact | Publication type | Content |
|---|---|---|---|
| `products/attest/client` | `yano-x-attest-client` | `library` | certificate record and strict JSON codec, `AttestClient`, `AttestVerifier`, trust inputs |
| `products/attest/cli` | `yano-x-attest-cli` | `tool` | `yano-attest` command, Cardano History exit-code contract |
| `products/attest/ui` | `yano-x-attest-ui` | `web-application` | SvelteKit static application, runtime config, SBOM, zip |

`settings.gradle` and `config/artifacts-v1.json` gain one entry per module. The client depends on
`sdk:client` and `state-machines:stdlib-contracts` only, exactly like the Cardano History client.
The CLI depends on the client and Jackson. The UI has no Java dependency.

## 5. Certificate format

```text
AttestCertificateV1 = {
  "schema":      "yano-x-attest-certificate-v1",
  "generator":   "yano-x-attest-client/<version>",
  "issuedAt":    "<UTC ISO-8601, informational only>",
  "chainId":     "<app-chain id>",
  "applicationId": "<capability manifest application id at issue time, or null>",
  "status":      "FINALIZED" | "ANCHORED",
  "subject": {
    "entityId":      "<doc-trail entity id>",
    "entryHashHex":  "<64 lowercase hex>",
    "hashAlgorithm": "sha-256",
    "fileName":      "<optional>",
    "sizeBytes":     <optional uint>,
    "mediaType":     "<optional>",
    "reference":     "<optional doc-trail reference>",
    "label":         "<optional free text, at most 256 characters>"
  },
  "message": {
    "messageIdHex": "<64 hex>", "height": <uint>, "index": <uint>,
    "topic": "doc-trail.command.v1", "senderHex": "<64 hex>", "senderSeq": <uint>,
    "expiresAt": <uint>, "bodyHex": "<canonical doc-trail command CBOR>",
    "authScheme": 0, "authProofHex": "<128 hex Ed25519 signature>"
  },
  "messageProof":    { <verbatim GET .../messages/{id}/proof> },
  "evidence":        { <verbatim GET .../evidence/{id}> },
  "trailHead":       { "stateProof": { <verbatim GET .../state/proof/{key}?height=H> },
                       "revision": <uint>, "headDigestHex": "<64 hex>" } | null,
  "anchorReference": { "chainId", "mode", "anchoredHeight", "stateRootHex", "blockHashHex",
                       "transactionHash", "l1Slot" } | null
}
```

Rules:

- Embedded node documents are re-decoded by their existing strict decoders at verification time.
  The certificate does not reinterpret them.
- `status` is `ANCHORED` only when the evidence bundle carries an anchor reference. The node
  omits the anchor when the segment to the next anchored block would exceed the bundle bounds;
  the certificate then stays `FINALIZED` and can be upgraded later.
- `trailHead` is optional because a height-pinned state proof depends on the node's retained
  proof horizon.
- `message` is the complete signed envelope copied from the message's position in the first
  evidence block, never from the node's message endpoint, which omits the expiry and the auth
  proof. The envelope is what the browser needs to recompute the message id and check the
  sender signature without a block codec. Verification requires the section to equal the
  envelope found at `message.index` in the first evidence block, field for field.
- `anchorReference` is derived from the evidence bundle's own anchor reference (height, block
  hash, transaction hash, slot) and the signed segment's last block state root, with `mode`
  copied from the chain status at issue time. It is never taken from the node's latest anchor
  commitment, which may name a later anchor than the one the evidence segment reaches. It is
  informational until an independent anchor check is performed.
- `applicationId` is copied from the chain status capability manifest at issue time so that
  independent anchor verification can compare it to the datum. Stdlib chains report the state
  machine id, `doc-trail`. It is `null` when the node reports no manifest; independent anchor
  verification then needs the caller's `--application-id`.
- The JSON document is bounded at 44 MiB, above the 40 MiB evidence bundle bound.
- Entity ids: the caller may supply one to build a revision trail for a series. When none is
  supplied the client derives `sha256:<entryHashHex>`, which makes a single-document existence
  proof whose trail has exactly one entry per attestation.

## 6. Verification model

`AttestVerifier.verify(certificate, suppliedBytes?, trust)` returns a result with six checks.

| # | Check | Local computation | Outcomes |
|---|---|---|---|
| C1 | Digest | SHA-256 of the supplied bytes equals `subject.entryHashHex` | `NOT_SUPPLIED`, `MATCH`, `MISMATCH` |
| C2 | Command binding | The envelope at `message.index` in the first evidence block equals the certificate's `message` section field for field, its message id recomputes as Blake2b-256 over the canonical signed body `[chainId, topic, sender, senderSeq, expiresAt, body]`, its auth scheme is Ed25519 and the auth proof verifies under `senderHex` over that signed body, its topic is the doc-trail topic, and its decoded command carries `subject.entityId` and `subject.entryHashHex` | `BOUND`, `UNBOUND`; the browser splits this into envelope copy, message id, sender signature, and command binding, and reports the signature step as `UNAVAILABLE` when its runtime lacks WebCrypto Ed25519 |
| C3 | Message inclusion | The message proof verifies to its messages root and matches the evidence block's chain id, height, block hash, and messages root | `INCLUDED`, `NOT_INCLUDED` |
| C4 | Finality | `EvidenceVerifier.verify` against the trust context of the selected mode | `VALID` with the signature count, or `INVALID` with the verifier's reason |
| C5 | Anchor linkage | The evidence anchor reference binds the last block hash, and `anchorReference`, when present, matches it and the last block's state root; with an anchor datum, the datum's chain id, height, block hash, state root, sorted member keys, and threshold bind the segment, its genesis id, commitment profile id, and format fingerprint equal the bundle's `stateCommitment`, and its application id equals the certificate's `applicationId` or the caller's override | `NONE`, `NODE_REFERENCE`, `INDEPENDENTLY_VERIFIED`, `INVALID` |
| C6 | Trail head | The state proof verifies against the message block's certified state root and decodes to the revision and head digest | `NOT_INCLUDED`, `VERIFIED`, `INVALID` |

Trust modes and resulting `ProofLabVocabulary.TrustLevel`:

| Mode | Input | Trust level |
|---|---|---|
| Bundle-declared | none | `INTERNAL_CONSISTENCY_ONLY` |
| Caller-pinned | member keys and threshold, optionally profile and genesis id | `CALLER_PINNED_ROOT` |
| Independent anchor | canonical anchor datum CBOR obtained from a Cardano source | `INDEPENDENTLY_VERIFIED_L1_ANCHOR` |

`accepted` is true when C1 is not `MISMATCH`, C2 is `BOUND`, C3 is `INCLUDED`, C4 is `VALID`,
C5 is not `INVALID`, C6 is not `INVALID`, and the trust level is above
`INTERNAL_CONSISTENCY_ONLY`. A result at `INTERNAL_CONSISTENCY_ONLY` is reported as consistent,
never as accepted.

Who attests: the node's REST submission signs the envelope with the ingress node's member key,
so `senderHex` identifies the chain member that admitted the append, and the person behind it is
whoever held that node's API key. C2 therefore proves which member recorded the digest, not
which person supplied the file. Deployments that need per-person attribution put the author into
the doc-trail `reference` field or into the `subject.label`, both of which the certificate binds
through the command bytes.

CLI exit codes extend the Cardano History convention with one code per trust outcome, so a
script can never mistake an unpinned certificate for a verified one:

| Exit | Meaning |
|---|---|
| `0` | accepted at `INDEPENDENTLY_VERIFIED_L1_ANCHOR` |
| `2` | usage error |
| `3` | node, file, or input unavailable |
| `4` | invalid: any check failed, or the trust inputs were rejected |
| `5` | accepted at `CALLER_PINNED_ROOT` |
| `6` | consistent only: no trust input was supplied, so the bundle-declared members were used |

The result JSON names every check and the trust level, so a caller never has to infer them
from the exit code.

## 7. Client and CLI

Java API on `AttestClient` (built from a base URL and chain id, optional API key):

- `attest(bytes, options)` hashes, submits a doc-trail append, waits for finality, and returns the
  message id.
- `attestDigest(entryHash, options)` for callers that hash elsewhere.
- `awaitFinalized(messageId, timeout)`.
- `certificate(messageId, subject)` fetches the four node documents and assembles the certificate.
- `trail(entityId)` returns the current revision and head digest.

`AttestVerifier` is static and offline. `AttestCertificateCodec` is strict: exact field sets,
canonical hex, bounded sizes, and rejection of trailing tokens, like the Cardano History bundle.

`yano-attest` commands:

```text
yano-attest attest      --url <node> --chain <id> --file <path> [--entity <id>] [--reference <ref>]
                        [--label <text>] [--output <certificate.json>] [--api-key <key>]
yano-attest certificate --url <node> --chain <id> --message-id <hex> --output <certificate.json>
yano-attest verify      --certificate <certificate.json> [--file <path>]
                        [--members <keys.json>] [--anchor-datum-hex <cbor>]
                        [--application-id <id>] [--json]
yano-attest trail       --url <node> --chain <id> --entity <id>
yano-attest status      --url <node> --chain <id>
```

`--members` names a JSON file `{ "chainId", "memberKeysHex": [...], "threshold", "profile"?,
"genesisIdHex"? }` that the caller obtained independently. `--anchor-datum-hex` is the canonical
inline datum of the chain's state-thread output, obtained from an independent Cardano source.
`--application-id` overrides or supplies the application id compared against the datum when the
certificate recorded none.

## 8. Browser UI

The UI is a SvelteKit static site built with the same toolchain, lockfile discipline, and runtime
configuration contract as the EUTxO UI in ADR-040, with `productId: "attest"` and
`attest-ui-config.json`.

Journey:

1. Connect to a node, read its identity, list chains, and keep those whose status reports the
   `doc-trail` state machine or a `doc-trail` capability component.
2. Attest: drop a file. The browser computes SHA-256 with WebCrypto, shows the digest, lets the
   user choose a series id or keep the derived one, encodes the canonical CBOR command, submits it
   through `POST .../messages`, polls `GET .../messages/{id}` until finalized, fetches the proof,
   evidence, and optional trail head, reads the signed envelope out of the first evidence block
   with a bounded read-only CBOR walker (arrays, byte strings, text strings, and unsigned
   integers only, no block hashing and no certificate checks), assembles the certificate, and
   offers it for download.
3. Verify: drop a certificate and optionally the file. Locally the browser recomputes the digest,
   decodes the doc-trail command from `message.bodyHex`, recomputes the message id from the
   canonical signed body, verifies the auth proof with WebCrypto Ed25519 where the runtime
   supports it, checks that the envelope at `message.index` in the first evidence block equals
   the `message` section, and recomputes the message path with a verbatim port of the console's
   `message-proof.ts`. It then asks the connected node for the block at the message height and
   the anchor commitment and labels those two checks `NODE_CONFIRMED_L1_REFERENCE`. The page
   states that independent finality and anchor verification is the CLI's job.
4. Trail: look up an entity's revision count and head digest.

Security rules from ADR-040 §7 apply: file bytes never leave the browser, API keys stay in memory,
domain responses are validated against bounded schemas, and no remote content is executed.

The scaffold, runtime configuration loader, connection panel, and styling are copied from the
EUTxO UI as committed on the feature branch that carries ADR-040. That branch merged to `main`
in pull request #4 on 2026-09-06, after this product was implemented, and `main` was merged back
into the product branch; the copies are byte-identical to what merged. AGENTS.md still says
product UIs stay in Yano; that sentence is out of date now that ADR-040 is on `main`, and this
product follows ADR-040.

## 9. Chain discovery

A chain is eligible when `GET .../chains/{id}/status` reports `stateMachine == "doc-trail"` or its
capability manifest lists a component with id `doc-trail` and no composite namespace. Composite
chains that embed a `documents` component are listed as unsupported in version one.

## 10. Documentation and showcase

- `docs/appchain/ATTEST.md`: what the product proves and does not, setup on the showcase and on
  a fresh chain, CLI walkthrough, Java usage, UI walkthrough, trust modes, exit codes, and
  troubleshooting.
- A row in `docs/appchain/README.md` and a link from the Cardano History guide's neighbor pages.
- Showcase usage without script changes: `./showcase.sh quickstart`, then
  `./showcase.sh anchor bootstrap documents-chain --instance <name>` to obtain anchored
  certificates on devnet.

## 11. Classification and posture

Client, CLI, and UI are `FIRST_PARTY_OPTIONAL` at maturity `preview`. The product inherits the
devnet and showcase posture from ADR-045. The guide says so on its first screen. Anchored
certificates on public networks require the operator to fund and bootstrap anchoring as ADR-008.4
and the showcase document describe.

## 12. Test plan

1. **Certificate codec.** Round trip, exact field sets, hex canonicality, size bounds, rejection
   of trailing tokens and unknown fields.
2. **Verifier on a real segment.** A `@AppChainCluster` test with `stateMachine = "doc-trail"` and
   three nodes submits an append, waits for finality, builds a certificate from the gateway's
   evidence, message proof, and state proof, and verifies it in caller-pinned mode using the
   cluster's member keys. Mutations of the digest, the command, a sibling, a signature, and the
   member set each fail the matching check only.
3. **Independent anchor mode.** A synthetic `AnchorDatumV1` bound to the segment's last block
   passes; a datum with another height, block hash, state root, or member set fails C5.
4. **HTTP client contract.** A local `HttpServer` serves the five endpoints; the client assembles a
   certificate; malformed and oversized responses are rejected.
5. **CLI.** `--help` offline, usage errors, verify against a committed golden certificate and
   members file, exit codes for each outcome. The cluster generates fresh keys on every run, so
   the golden pair is produced once by the cluster test and committed as a test resource; the
   cluster test asserts that the committed golden still verifies whenever it is regenerated.
6. **UI.** Vitest coverage for runtime config parsing, CBOR command encoding and decoding, the
   envelope walker against the committed golden certificate's evidence, message id
   recomputation, the message path port against the shared ADR-037 vectors, and API response
   validation; `svelte-check` clean; production build succeeds.

## 13. Acceptance criteria

- `./gradlew :products:attest:client:test :products:attest:cli:test verifyArtifactInventory
  verifyJvmOnlyBuild` passes against the pinned Yano version.
- `npm run check`, `npm test`, and `npm run build` pass in `products/attest/ui`.
- The guide's CLI walkthrough runs end to end against the light showcase, producing a
  `FINALIZED` certificate that verifies in caller-pinned mode; the anchored walkthrough is
  documented and validated where an anchored devnet is available.
- Every check in §6 is visible in CLI JSON output and in the UI.

## 14. Follow-ups

| Item | Trigger |
|---|---|
| Composite `documents` component support with composite physical keys | first composite deployment that wants certificates |
| `attest` Studio recipe: `doc-trail` plus anchor | when the catalog gains a doc-trail recipe |
| Showcase `run attest` and a product-UI deployment role | after ADR-039 lands on `main` |
| Shareable verification links and QR codes | UI feedback |
| Independent finality verification in the browser | a maintained browser port of the block codec |
| uVerify-compatible publication effect | measured demand, per the research note |
| Multi-epoch evidence segments | a governed chain that rotates members between attestation and anchor |

## 15. Implementation record

Implemented as `products/attest/client`, `products/attest/cli`, and `products/attest/ui`, with the
user guide in `docs/appchain/ATTEST.md`. Status of the §13 criteria on 2026-09-06:

- Gradle and npm gates pass (client 22 tests, CLI 10, UI 29 plus one opt-in live test).
- The CLI walkthrough ran end to end against the light showcase built from this branch
  (`:examples:showcase:installDist`, Yano 0.1.0-pre13, instance `attest` on `--http-base 7170`
  because 7070 was occupied by an unrelated process): `status`, `attest`, `trail`, unpinned
  `verify` (exit 6), and `verify --members` with keys taken from the instance's
  `cluster-appchain-identity.json` (exit 5, `ACCEPTED`, `CALLER_PINNED_ROOT`).
- The built UI was served same-origin next to that node and driven in headless Chrome over the
  DevTools protocol: it connected, discovered `documents-chain`, and offered the attest form. The
  attest and verify sequence the page runs was executed against the same node by
  `src/lib/live.test.ts` (`ATTEST_LIVE_NODE_URL`), which passed. The full JVM distribution and its
  verify list were built and checked with `verifyYanoXJvmDistribution`.
- The anchored walkthrough is documented but was not run: the showcase's script-mode anchoring
  needs `anchor bootstrap` against the devnet, which was out of scope for this pass. The verifier's
  independent-anchor path is covered by a synthetic datum bound to a real evidence segment.

Deviations from the proposal:

| Topic | Decision |
|---|---|
| Cluster tests | The pinned testkit's `@AppChainCluster` resolves state machines through an empty plugin registry and can only run `ordered-log`. The client tests therefore carry a small `DocTrailTestCluster` that boots three `AppChainSubsystem` nodes with a direct doc-trail registry, and a `GatewayHttpBridge` that serves one gateway over the node's REST paths, rendering each document the way the node resource does. No core change was needed. |
| Message section source | The node's message endpoint omits the expiry and the auth proof, so the client copies the signed envelope out of the first evidence block, and the browser reads it with a bounded CBOR walker instead of a block codec (§5, §8). |
| Anchor reference | Derived from the evidence bundle's own anchor reference plus the segment's last state root; the node's latest commitment only supplies the mode and the UI's node-confirmed display. |
| Exit codes | `6` distinguishes an unpinned, consistent-only result from `5`, accepted with caller-pinned members. |
| Golden fixtures | The cluster test writes a certificate, members file, and document once (`-PattestGoldenWrite=true`); the CLI and UI tests verify the committed copies, and the cluster test re-verifies them on every run. |
| Packaging | The attest CLI joins the Cardano History CLI in the showcase zip under `tools/attest`; the JVM distribution carries the CLI under `tools/yano-attest` and the built UI under `product-ui/attest`, following the feature branch's EUTxO UI wiring. |
| Discovery (§9) | A chain is eligible when `stateMachine == "doc-trail"` or when its manifest lists a `doc-trail` component whose `stateNamespace` is `application/v1`, the namespace of a standalone stdlib component. Composite embeddings carry their own namespace and are excluded, as §9 requires. |
| Yano pre14 (merge of `main` after PR #4, 2026-09-06) | `main` now needs the unreleased Yano pre14 (`L1ObserverConsensusIdentity`), built with `-PuseMavenLocal=true -PyanoVersion=0.1.0-pre14-ba9ac62-SNAPSHOT -PyanoJvmDist=<local yano-0.1.0-pre14-ba9ac62.zip>` as ADR-045 records. Pre14 blocks are version 3 (15 items, with consensus context digest, view, and justification); the JVM verifier follows the core codec, and the browser walker now selects the layout by block version (v2 and v3). The golden fixtures were regenerated under pre14 and all gates re-run against it; the pre13 showcase walkthrough above was not repeated on pre14. |
