# DPP Starter

> **Prototype.** The DPP Starter is the configuration-only prototype that ADR-046 §4.4 and Yano
> ADR app-layer/026 §5.1 permit before the full Digital Product Passport provider is built. It
> shows the infrastructure a DPP registry needs on the stock governed `authenticated-map`; it is
> not the DPP product of ADR-026, it enforces no DPP lifecycle rule on chain, and it claims
> conformance with no DPP standard. The decision record is
> [ADR-051](../../adr/051-digital-product-passport-starter.md).

The starter gives a consortium a chain where manufacturers register products and publish
passport versions, claim issuers attach public or committed claims, operators append lifecycle
events, and certifiers obtain certificates through an approval round with two independent
auditors. A passport is a set of proof-bound answers at one height, verifiable offline; a public
portal serves it with a GS1 Digital Link resolver; an operator gateway signs for the console; the
`yano-dpp` CLI does everything from a terminal.

## What it does

| Journey | How |
|---|---|
| Register a product, publish a passport document, set its status | Governed-role writes as a `manufacturer`; the document is hashed and stored outside consensus, its digest, media type, length, and reference are committed |
| Attach a claim | As a `claim-issuer`: public text on chain, or a salted SHA-256 commitment with the salt and text handed to verifiers as a `dpp-disclosure-v1` document |
| Append lifecycle events | As an `operator` (manufacturer, logistics, repairer, recycler); the ledger orders them, never a clock |
| Certify | A `certifier` proposes through the map's approval route, two `auditor`s from distinct organizations approve, the map command carrying the approval reference is applied; revocation uses the same round |
| Read a passport | `yano-dpp passport`, the portal (`/passports/{id}`, `/01/{gtin}`), or the console; every record with its proof, provenance, and the flags a prototype cannot prevent but exposes |
| Verify offline | `yano-dpp verify --passport bundle.json [--members keys.json | --anchor-datum-hex …]` at the ADR-047 trust levels; `yano-dpp disclose` checks a committed claim |
| Operate from a browser | The console's Operator view drives the gateway on the operator's machine; the Passport view needs no secret |

## Modules

| Module | Role |
|---|---|
| `products/dpp/profile` | `DppStarterProfile` (collections, policies, schemas, keys), `DppValues` (canonical CBOR codecs, claim commitment), `DppGenesis` (demo descriptor, genesis from the ADR-049 actor descriptor) |
| `products/dpp/client` | `DppClient` (writes, certification round, projection, passport assembly), `PassportBundle` and `PassportVerifier`, `PassportView`, `Disclosure`, `DocumentStore`, `PortalService`, `GatewayService` |
| `products/dpp/cli` | `yano-dpp` |
| `products/dpp/ui` | The console (SvelteKit static site) |
| `products/dpp/harness/dpp.sh` | The launcher: a three-member starter chain, the demo journey, the portal, the gateway |

The map client, signer, and verifier come from the Trust Registry (`products/trust-registry/client`,
ADR-049); the trust input from Attest (ADR-047).

## Data model

Five collections on the governed map, every value canonical CBOR checked by a genesis-bound
schema, no restore:

| Collection | Who writes | Key | Value |
|---|---|---|---|
| `products` | `manufacturer` (policy `manufacturer-write`) | product id (≤ 64 bytes, e.g. `gtin:09506000134352`) | `[1, manufacturerOrganizationId, status, currentVersion, successorProductId, passportProfileId]` |
| `product-versions` | `manufacturer` | `<productId>/<version>` | `[1, documentSha256, mediaType, reference, byteLength]` |
| `claims` | `claim-issuer` (`claim-issuer-write`) | `<productId>/<claimType>/<claimId>` | `[1, visibility, value, issuerOrganizationId, validFromHeight, validUntilHeight, evidenceSha256]` |
| `events` | `operator` (`operator-write`) | `<productId>/<eventId>` | `[1, eventType, actorOrganizationId, observedAt, location, evidenceSha256, note]` |
| `certificates` | approval policy `certification`: proposer `certifier`, two `auditor`s from distinct organizations | certificate id | `[1, productId, certificateType, issuerOrganizationId, evidenceSha256, validFromHeight, validUntilHeight]` |

Product statuses are `DRAFT`, `ACTIVE`, `INACTIVE`, `REPLACED` (with a successor), `RETIRED`;
a revoked passport is the map tombstone. A GS1-identified product is `gtin:<14 digits>`,
optionally `gtin:<14 digits>:21:<serial>`; the portal's `/01/{gtin}[/21/{serial}]` path maps to
it with the GTIN zero-padded. A committed claim carries
`SHA-256("yano-dpp-claim-commitment-v1" || salt32 || utf8(text))`.

The demo consortium: `acme-manufacturing` (`maker-a`: manufacturer and operator),
`swift-logistics` (`logistics-a`: operator), `green-labs` (`issuer-a`: claim issuer),
`cert-body-a` (`certifier-a`, `auditor-a`), `audit-guild-b` (`auditor-b`), and `dpp-consortium`
(`dpp-admin-a`, the administrator authority). Demo seeds are
`sha256("yano-dpp-starter-demo-actor:" + actorId)`, showcase-only material.

## Setup

### Option A: the launcher

The launcher needs an extracted Yano X JVM distribution (`yano.jar`, `plugins/`, `config/`,
`appchain-cluster/cluster.sh`). From the repository, build and extract the distribution and build
the CLI once (add the Yano version properties the repository
currently requires, see `docs/BUILD_AND_TEST.md`):

```bash
./gradlew :distribution:jvm:yanoXJvmDistZip :products:dpp:cli:installDist
unzip -qo distribution/jvm/build/distributions/yano-x-jvm-*.zip -d build/yano-x
export DPP_YANO_HOME=$(echo "$PWD"/build/yano-x/yano-x-jvm-*)
products/dpp/harness/dpp.sh up
products/dpp/harness/dpp.sh demo
products/dpp/harness/dpp.sh portal
products/dpp/harness/dpp.sh gateway
```

From the JVM distribution, `examples/dpp/dpp.sh up` finds the distribution and `tools/yano-dpp`
by itself. `up` derives the launcher's demo member keys, generates the demo genesis with
`yano-dpp genesis --demo`, writes a home with one `dpp-starter-chain`, and starts three members
on `http://127.0.0.1:7470..7472/api/v1` (`--http-base`, `--server-base`, `--nodes`,
`--threshold`, `--instance` change that). `demo` runs the whole journey through the CLI:
`maker-a` registers `gtin:09506000134352`, publishes version 1, and activates it; `issuer-a`
attaches a public and a committed claim; three events are appended; `certifier-a` proposes
`cert-eco-1`, both auditors approve, the certificate is applied; the passport is written to the
instance directory and verified under the members file. `portal` and `gateway` start the two
services on 8580 and 8590 (`--portal-port`, `--gateway-port`); the gateway prints its token.
`dpp.sh env` prints shell exports:

```bash
eval "$(products/dpp/harness/dpp.sh env)"
# YANO_DPP_URL, YANO_DPP_CHAIN, YANO_API_KEY, YANO_DPP_GENESIS_ID, YANO_DPP_SEEDS,
# YANO_DPP_MEMBERS, YANO_DPP_CONTENT_DIR, DPP_PORTAL_URL, DPP_GATEWAY_URL, DPP_GATEWAY_TOKEN
```

`status`, `seeds`, `stop`, and `clean` complete the launcher. Each member disables the devnet
profile's L1 history projection: a starter member needs no L1 history, and members sharing one
home must not share one archive.

### Option B: your own chain

1. Write a descriptor. `yano-dpp descriptor --demo --output dpp.json` prints the demo descriptor
   to start from; it is the ADR-049 actor descriptor (organizations, actors with roles and key
   proofs, the administrator authority) with no issuers or schemas. For real actors, each actor
   runs `yano-dpp actor-key --actor <id> --seed-file <owner-only seed> --chain <chain id>` and
   sends back the public key and key proof; the seed never leaves the actor's machine. Give
   manufacturers the `manufacturer` role, lifecycle parties `operator`, claim issuers
   `claim-issuer`, certification bodies `certifier`, and auditors `auditor` in at least two
   organizations.
2. Generate the genesis: `yano-dpp genesis --descriptor dpp.json --members <key,...> --threshold
   <n> --chain-index <i>` prints the four `yano.app-chain.chains[i].` properties
   (`state.commitment-profile`, `state.format-fingerprint`, `state.genesis-id`,
   `machines.authenticated-map.genesis-cbor-hex`). Every member must use the same descriptor,
   members, and threshold: the genesis id is part of the chain's identity.
3. Configure the chain as `state-machine: authenticated-map` with `membership.mode: governed`
   and the four properties on every node; the launcher's `write_home` and `write_node_configs`
   show the shape.
4. Point the CLI at a node: `--url http://<node>/api/v1 --chain <chain id> --api-key-file
   <file>` (or `YANO_DPP_URL`, `YANO_DPP_CHAIN`, `YANO_API_KEY`). The first write on a chain
   that has no block yet passes `--genesis-id <state.genesis-id>`.

### Build and serve the console

```bash
./gradlew :products:dpp:ui:frontendBuild   # build/site
```

Serve `products/dpp/ui/build/site` (or `product-ui/dpp` from the distribution) from any static
host. `dpp-ui-config.json` next to `index.html` names the portal the Passport view reads
(`serviceUrl`) and, for operators only, a gateway (`gatewayUrl`); remote URLs must be HTTPS,
HTTP is allowed on loopback. A visitor who scanned a label lands on `#/01/<gtin>` and the
console opens the passport at once.

## Walkthrough on the launcher

With `eval "$(products/dpp/harness/dpp.sh env)"` in the shell and `yano-dpp` on the path
(`products/dpp/cli/build/install/yano-dpp/bin/yano-dpp` or `tools/yano-dpp/bin/yano-dpp`):

```bash
# The passport at the tip, verified under the members file (exit 5), exported for offline use.
yano-dpp passport --product gtin:09506000134352 --members "$YANO_DPP_MEMBERS" --output passport.json
yano-dpp verify --passport passport.json                          # bundle-declared members: exit 6
yano-dpp verify --passport passport.json --members "$YANO_DPP_MEMBERS"   # exit 5
yano-dpp disclose --passport passport.json --disclosure ~/.yano-x/dpp/default/disclosure-cf-1.json \
  --members "$YANO_DPP_MEMBERS"                                  # MATCH, disclosed text shown

# A second version by the manufacturer; the product's current version advances in the same batch.
yano-dpp publish-version --product gtin:09506000134352 --version 2 --document passport-v2.json \
  --reference https://acme.example/passports/v2.json --actor maker-a --seed-file "$YANO_DPP_SEEDS/maker-a.seed"

# A repair event by an operator, then the passport as of an earlier height.
yano-dpp event --product gtin:09506000134352 --type REPAIRED --location "Service center Berlin" \
  --actor logistics-a --seed-file "$YANO_DPP_SEEDS/logistics-a.seed"
yano-dpp passport --product gtin:09506000134352 --height 12 --members "$YANO_DPP_MEMBERS"

# Certificate revocation through the same approval round.
yano-dpp certify propose --revoke --certificate cert-eco-1 --output revoke.json \
  --actor certifier-a --seed-file "$YANO_DPP_SEEDS/certifier-a.seed"
yano-dpp certify approve --request revoke.json --actor auditor-a --seed-file "$YANO_DPP_SEEDS/auditor-a.seed"
yano-dpp certify approve --request revoke.json --actor auditor-b --seed-file "$YANO_DPP_SEEDS/auditor-b.seed"
yano-dpp certify apply --request revoke.json

# End of life.
yano-dpp set-status --product gtin:09506000134352 --status RETIRED --actor maker-a --seed-file "$YANO_DPP_SEEDS/maker-a.seed"
yano-dpp revoke --product gtin:09506000134352 --actor maker-a --seed-file "$YANO_DPP_SEEDS/maker-a.seed"
```

The `passport` output lists the product, every version with its document availability
(`FINALIZED` when only the record exists, `CONTENT_VERIFIED` when the content directory holds
bytes matching the digest), claims, events in ledger order, certificates with "approval
consumption proven", the timeline, and the verification with its checks. Each row names who
wrote it (`DIRECT_ROLE actor (organization, role)`) or, for certificates, the applied message.

Portal routes: `GET /passports/{id}[?height=]`, `GET /passports/{id}/proof`,
`GET /01/{gtin}[/21/{serial}]`, `GET /documents/{sha256}`, `GET /healthz`. Gateway routes
(`X-Gateway-Token` on every request): `GET /operator/actors`, `POST /operator/products`,
`/operator/versions` (the document as base64 in the JSON body), `/operator/status`,
`/operator/revoke`, `/operator/claims`, `/operator/events`, and
`/operator/certifications/propose|approve|reject|apply` (the request document travels in the
`request` field). Every write answers with the message id and the receipt.

Running this in front of an audience: [DPP demonstration](DPP_DEMO.md) covers the cast, where the
roles come from, how the keys are held, and the browser walkthrough. Taking it further:
[Production deployment](PRODUCTION_DEPLOYMENT.md).

## The console

- **Passport** reads the portal only. Enter a product id, paste a Digital Link, or arrive on
  `#/01/<gtin>`; the view shows the status, the product with its writer, versions with document
  availability and a link to the served bytes, claims (public text, or the commitment with a
  "Check a disclosure" box that recomputes the commitment in the browser), the ledger-ordered
  timeline, certificates, and a proof-rows table with one row per record. Every record and every
  fact is checked to name the bundle's chain, genesis, height, root, and block, and every key to
  belong to the product; "Download passport bundle" exports what `yano-dpp verify` accepts.
- **Operator** connects to a gateway with its token (kept in memory) and guides the lifecycle
  step by step, so an operator does not need this document open to run a demo. Enter a product id
  and press *Read progress*: the console reads that product's public passport from the portal and
  marks each step `DONE`, `READY`, `NEEDS <role>`, or `BLOCKED`, names the role the step needs,
  says which step to do next, and offers a *Sign as* shortcut to the actors that hold the role.
  The steps are register, publish a version (the document is hashed in the browser before it is
  sent), attach a claim (the disclosure document comes back once for committed claims), append an
  event, run a certification round, and change status or revoke. Certification shows where the
  round stands: propose, then approve until two distinct organizations have signed, then apply.
  Every write refreshes the progress, and every result shows the receipt. The guide reflects what
  the chain already holds; it never authorizes anything, because the chain checks the actor's
  signature and policy on every write and refuses a step the role does not permit.
- **What this proves** explains the rows.

## Proof story, flags, and trust levels

A passport says what the consortium agreed about product P at height H: these records existed
with these revisions under this root; which governed actor wrote each one under which policy
and key; that a certificate was applied under the approval-gated collection with its proposal's
one-use consumption proven; that a claim's commitment is what the issuer disclosed; that the root
was certified by the pinned members; and, when anchored, that Cardano carries it. It never says
that a physical event occurred, that a measurement is true, that an issuer is accredited beyond
this chain's genesis (the Trust Registry's question), that a document remains available, or that
anything conforms to a DPP standard.

Flags a configuration-only profile cannot prevent but the passport exposes: `REWRITTEN` (a
version record with revision above 1), `DANGLING` (a current version with no active version
record), `FOREIGN_WRITER` (the writer's organization differs from the one the record names),
`EXPIRED` and `NOT_YET_VALID` (validity windows against the snapshot height), `MALFORMED`.

| Exit | Meaning |
|---|---|
| 0 | verified with an independent anchor (or a write applied) |
| 2 | usage |
| 3 | node unavailable or malformed response |
| 4 | invalid: a failed verification, a mismatching disclosure, or a rejected write |
| 5 | verified with caller-pinned members (`--members`) |
| 6 | consistent under the bundle's own signers (no caller trust) |

## Security notes

- Seeds are read from owner-only files (CLI) or an owner-only directory (gateway) and never
  logged. The gateway belongs on the operator's machine: it binds to loopback by default,
  refuses a non-loopback bind without `--allow-remote`, requires its random token on every
  request, and never returns a seed. The console keeps the token in memory only.
- Direct-role authorizations are one-use, chain- and genesis-scoped, and expire within 100
  blocks; a certification proposal within 600. A stale compare-and-set is rejected by the map.
- On-chain values are identifiers, hashes, commitments, and bounded references; no personal
  data and no document belongs on chain. Committed claims hide the value from every member; the
  disclosure document is distributed by the issuer, out of band, and `yano-dpp claim --committed`
  writes it to an owner-only file.
- Documents are stored by hash under the content directory (`--content-dir`,
  `YANO_DPP_CONTENT_DIR`, default `~/.yano-x/dpp/documents`) and served only when the bytes still
  hash to their name. The portal is read-only with bounded responses and a bounded replay
  (5,000 commands); it exposes no node API key.
- `dpp.sh env` prints the node API key and the gateway token; treat its output as a secret.

## Troubleshooting

- `the chain has no block yet; pass the generated state.genesis-id as --genesis-id`: the first
  write on a fresh chain cannot read the map genesis; pass `--genesis-id` (and `--key-id` when
  the actor's key id is not `<actor>-k1`). The launcher's `demo` does this.
- A write is `REJECTED` with `UNAUTHORIZED` or `ACTOR_INELIGIBLE`: the actor lacks the
  collection's role; `WRONG_REVISION` or `PRECONDITION`: a concurrent write changed the product,
  read it again; `APPROVAL_NOT_APPROVED`: the certification proposal has fewer than two auditor
  approvals from distinct organizations, or it expired.
- `the request's payload hash does not match its command`: the certification request document
  was edited; regenerate it with `certify propose`.
- The portal answers 404 for a product: it was never written on this chain; 400: the id or
  Digital Link is malformed.
- `--members` verification exits 4 with "finality certificate": the members file does not name
  this chain's members or threshold; `dpp.sh up` writes the right one to the instance directory.
- The console cannot reach the portal from a page served over HTTPS: the portal must be served
  over HTTPS too, or the console over HTTP on loopback.

## Tests

- `./gradlew :products:dpp:profile:test`: genesis determinism, schema acceptance and rejection,
  value codecs, keys, Digital Link mapping, commitment vector, firewall.
- `./gradlew :products:dpp:client:test`: codec round trips, the projection, the document store,
  and the cluster test on a real three-member governed map (the whole journey, tampering, the
  disclosure, the portal and gateway routes); `-PdppGoldenWrite=true` regenerates the goldens the
  CLI and console tests pin.
- `./gradlew :products:dpp:cli:test`, `npm test` in `products/dpp/ui`.
