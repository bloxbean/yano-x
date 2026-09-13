# Trust and Status Registry

The Trust and Status Registry is a config-only product on the stock governed `authenticated-map`
state machine (ADR-049, executing Yano ADR app-layer/029): a registry chain whose entries are
subjects, credential status, published status lists, issuers, and schemas; a client and CLI that
answer status questions with proofs bound to a certified block; a read-only service that serves
W3C Bitstring Status Lists and TRQP-shaped authorization answers; a browser console; and a
launcher that starts a three-member registry on the packaged cluster launcher. Nothing in it
executes on chain; consensus is unchanged.

Yano is pre-release. The registry is `preview` and inherits the devnet posture of the rest of
the repository.

## What it does

| Journey | What happens | What is proven |
|---|---|---|
| Genesis | `yano-trust genesis` builds the map genesis from a descriptor (organizations, actors with key proofs, policies, seeded issuers) and prints the four node properties | Same descriptor, members, and threshold always give the same genesis id |
| Write | An issuer or registrar signs a one-use actor authorization for a `put`, `revoke`, or `publish-list`; the node applies it and records a receipt and the consumption | The receipt names the applied revision; the consumption names actor, organization, key, and policy revision |
| Status | `yano-trust status` answers inclusion, tombstone (revoked), or exclusion at the tip or at a height | Every fact is a state proof at one height under one root, and the certified block carries that root |
| Lists | `publish-list` replays the chain's applied status writes into a bitstring and writes its SHA-256; `serve` serves the same list as a Bitstring Status List | The served list hashes to the value the chain holds, with the list entry's proof |
| Authorization | `trqp` and `GET /trqp/...` answer "is entity E authorized for A under framework F at height H" from the `issuers` entry | The entry's proof and the evaluation inputs |
| Verify | `yano-trust verify` checks an exported answer offline with pinned members or a Cardano anchor datum | The finality certificate under the caller's trust input, then every proof against the certified root |
| Console | Look up entries, check served lists, ask TRQP questions, export answers for offline verification | Proof binding shown per fact; verification by the CLI |

## Modules

| Module | Path | What it is |
|---|---|---|
| `yano-x-trust-registry-profile` | `products/trust-registry/profile` | Collections, schemas, policies, value codecs, genesis generator, bitstring projection, TRQP evaluator |
| `yano-x-trust-registry-client` | `products/trust-registry/client` | Node client, answer assembly, signer, verifier, standards service |
| `yano-x-trust-registry-cli` | `products/trust-registry/cli` | `yano-trust` (`tools/yano-trust` in the JVM distribution) |
| `yano-x-trust-registry-ui` | `products/trust-registry/ui` | Static SvelteKit console (`product-ui/trust-registry` in the JVM distribution), read views plus the guided write view |
| launcher | `products/trust-registry/harness/registry.sh` (`examples/trust-registry` in the distribution) | Three-member registry on the packaged cluster launcher |

## Data model

The profile (`trust-registry-v1`) declares five collections on one governed map. Every value is
canonical CBOR checked by a genesis-bound schema, so a malformed value is filtered before it is
finalized. No collection allows restore: a revoked entry is terminal.

| Collection | Written by | Key | Value |
|---|---|---|---|
| `subjects` | role `registrar` (policy `registrar-write`) | subject id, ≤ 128 bytes of `[A-Za-z0-9._:~-]` | `[1, controllerOrganizationId, kind, metadataHash32]` |
| `status` | role `issuer` (policy `issuer-write`) | `<listId>/<index>` | `[1, bit, reasonCode]`; `bit` is the value of that list position |
| `status-lists` | role `issuer` | list id, ≤ 64 bytes | `[1, purpose, bitLength, listSha256, publishedHeight]` |
| `issuers` | two `registrar` approvals from distinct organizations (policy `issuer-onboarding`), or genesis | entity id | `[1, framework, [authorization...], validFromHeight, validUntilHeight]`; `0` = no end |
| `schemas` | role `registrar` | schema id, ≤ 64 bytes | opaque bytes, ≤ 64 KiB |

Status lists follow the W3C Bitstring Status List: index 0 is the most significant bit of byte 0,
`statusSize` is 1, and `encodedList` is the multibase base64url (`u` prefix, no padding) of the
GZIP of the raw bitstring. The hash the chain holds is the SHA-256 of the raw, uncompressed
bitstring; GZIP output is never hashed. The minimum list length is 131,072 bits. A tombstoned
status index counts as bit 1 in every projection: revoking an index is terminal even on a
`suspension` list.

Identifiers are hashes or opaque ids. No personal data belongs on chain.

## Setup

### Option A: the launcher

The launcher needs an extracted Yano X JVM distribution holding `yano.jar`, `plugins/`,
`config/`, and `appchain-cluster/cluster.sh`. From the repository, build and extract the
distribution and build the CLI once (add the Yano version properties the
repository currently requires, see `docs/BUILD_AND_TEST.md`):

```bash
./gradlew :distribution:jvm:yanoXJvmDistZip :products:trust-registry:cli:installDist
unzip -qo distribution/jvm/build/distributions/yano-x-jvm-*.zip -d build/yano-x
export TRUST_REGISTRY_YANO_HOME=$(echo "$PWD"/build/yano-x/yano-x-jvm-*)
products/trust-registry/harness/registry.sh up
```

From the JVM distribution, `examples/trust-registry/registry.sh up` finds the distribution and
`tools/yano-trust` by itself. `up` derives the launcher's demo member keys, generates the demo
genesis, writes a home with one `trust-registry-chain`, and starts three members on
`http://127.0.0.1:7270..7272/api/v1` (`--http-base`, `--server-base`, `--nodes`, `--threshold`,
`--instance` change that). It prints the API key file, the map genesis id, the members file, and
the demo seed directory. `registry.sh env` prints shell exports:

```bash
eval "$(products/trust-registry/harness/registry.sh env)"
# YANO_TRUST_URL, YANO_TRUST_CHAIN, YANO_API_KEY, YANO_TRUST_GENESIS_ID, YANO_TRUST_SEEDS, YANO_TRUST_MEMBERS
```

`status`, `stop`, and `clean` complete the launcher. Each member disables the devnet profile's L1
history projection (`yano.history.projection.enabled=false`): a registry member needs no L1
history, and members sharing one home must not share one archive.

The demo registry has three organizations and four actors: `registry-admin-a` and `registrar-a`
in `registry-operator`, `registrar-b` in `registrar-guild-b`, and `issuer-a` in `issuer-org-a`.
`issuer-a` is seeded as an `issuers` entry under framework `yano-demo-framework-v1` with the
authorizations `issue:credential` and `revoke:credential`. The demo seeds are showcase-only
material, `sha256("yano-trust-registry-demo-actor:" + actorId)`; the launcher writes them to
owner-only files under the seed directory.

### Option B: your own chain

1. Write a descriptor. `yano-trust descriptor --demo --output registry.json` prints the demo
   descriptor to start from. For real actors, each actor runs
   `yano-trust actor-key --chain <chain-id> --actor <id> --seed-file <seed>` on its own machine and
   hands the printed `publicKeyHex` and `keyProofHex` to the operator; seeds never travel.
2. Generate the node properties from the member keys the chain will run with:
   ```bash
   yano-trust genesis --descriptor registry.json --members <key,key,key> --threshold 2 \
     --chain-index N --output registry.properties
   ```
   The same descriptor, members, and threshold always give the same genesis id. `--chain-index` is
   the position of the chain in the node's `yano.app-chain.chains[N]` list.
3. Add the chain to `application-appchain.yml` and the four generated properties to every node:
   ```yaml
   yano:
     plugins:
       allow-list:
         - org.yanoproject.x.stdlib
         - org.yanoproject.x.role-workflow
         - org.yanoproject.x.composite
     app-chain:
       chains[N]:
         chain-id: "trust-registry-chain"
         state-machine: "authenticated-map"
         membership:
           mode: "governed"
         block:
           interval-ms: "1000"
   ```
   The generated `block.interval-ms` and the default message size are part of the genesis
   identity; nodes must run with the values the genesis was generated with.
4. Start the nodes. A Yano chain produces a block only when a message arrives, so a fresh
   registry sits at height 0 until its first write; until then the map genesis cannot be queried
   and actor records cannot be read. The first write therefore passes `--genesis-id` (the
   generated `state.genesis-id`) and signs with the genesis revisions; every later write reads
   the chain.

### Build and serve the console

```bash
cd products/trust-registry/ui
npm ci && npm run check && npm test && npm run build
npx --yes serve build/site
```

`trust-registry-ui-config.json` next to `index.html` may pin endpoints, a default chain, an
expected network, `serviceUrl`, the base URL of a `yano-trust serve` instance whose lists the
console checks, and `gatewayUrl`, the base URL of a `yano-trust gateway` the write view signs
through. A node that is not same-origin with the console must allow the UI origin through
CORS; hosting the console behind the node's reverse proxy avoids CORS entirely.

## Walkthrough on the launcher

Every command takes `--url $YANO_TRUST_URL --chain $YANO_TRUST_CHAIN`; the API key is read from
`YANO_API_KEY`. `S=$YANO_TRUST_SEEDS`.

1. **First write, before any block.** issuer-a sets index 5 of `list-1`:
   ```bash
   yano-trust put --url $YANO_TRUST_URL --chain $YANO_TRUST_CHAIN --actor issuer-a \
     --seed-file $S/issuer-a.seed --list list-1 --index 5 --bit 1 --reason 3 \
     --genesis-id $YANO_TRUST_GENESIS_ID
   ```
   Prints the message id and `Applied at height 1: status/list-1/5 revision 1 ACTIVE`.
2. **More writes, records read from the chain.** Index 8 as `--bit 0`; a subject by registrar-a
   (`--subject did:example:subject-1 --controller registry-operator --kind product
   --metadata-hash <64 hex>`); then `yano-trust revoke ... --actor issuer-a --list list-1 --index 8`,
   which answers `revision 2 REVOKED`.
3. **Publish the list.** `yano-trust publish-list ... --actor issuer-a --list list-1 --purpose
   revocation` replays the applied status writes, prints the set count and the bitstring hash,
   and writes `status-lists/list-1`.
4. **Answer with proofs.** `yano-trust status ... --list list-1 --index 5 --output answer.json`
   prints presence, revision, the decoded value, and the provenance (`DIRECT_ROLE, by issuer-a
   (issuer-org-a, role issuer, key issuer-a-k1) under issuer-write revision 1`), then the
   verification rows. Without a trust input the exit code is 6 (consistent, no caller trust); add
   `--members $YANO_TRUST_MEMBERS` for exit 5 (`CALLER_PINNED_ROOT`). `--list list-1 --index 8`
   answers `REVOKED`; `--subject did:example:nobody` answers `ABSENT` with an absence proof;
   `--issuer issuer-a` answers `GENESIS` provenance; `--height 2` answers as of height 2.
5. **Serve and check the list.** `yano-trust list ... --list list-1 --output status-list.json`
   compares the replayed bitstring with the chain's entry (`matches chain: true`, exit 0).
   `yano-trust serve ... --port 8480` serves `GET /status-lists/list-1` (as published),
   `GET /status-lists/list-1?height=2` (point in time, marked as differing from the published
   hash), `GET /trqp/entities/issuer-a/authorizations/issue:credential?framework=yano-demo-framework-v1`,
   `GET /entries/status/<keyHex>`, and `GET /healthz`.
6. **Verify offline.** `yano-trust verify --answer answer.json --members $YANO_TRUST_MEMBERS`
   exits 5; a wrong member set or a tampered entry exits 4.
7. **Console.** Connect to `http://127.0.0.1:7270`, pick `trust-registry-chain` (marked
   `registry profile`), look up `list-1` index 5, build the export and verify it with the CLI;
   under *Status lists* enter `list-1` and the service URL to see `matches chain`.
8. **Write from the browser.** `registry.sh gateway` starts the operator gateway on port 8481 with
   the demo seeds and prints a token. In the console's *Write entries* view, enter the gateway URL
   and that token, then pick the actor to sign as. The view guides the writes: record a subject,
   set a credential status, publish the list, revoke an index, register a schema. Each step shows
   the role it needs, what the chain answers for the list and index you entered, and a *Sign as*
   shortcut to an actor that holds the role. Steps stay clickable when the role is wrong, because
   the chain is the authority: the write is signed, submitted, and refused with its error code.
9. **Sign in the browser instead.** The write view has two signing modes, and shows which is
   active. *Gateway* is the demo path above: the gateway holds every seed it was started with and
   signs for any of them, so a proof says the gateway signed as that actor. *Browser key* unlocks
   one actor's key in the tab, from pasted hex or a file holding that hex. The seed is imported through
   WebCrypto and zeroed at once, never stored and never sent anywhere; the console reads the
   actor's record, active key, and policy revision from the chain, refuses to continue if the
   unlocked key is not the one the chain holds for that actor, signs the authorization in the tab,
   and submits only the finished command bytes. Closing the tab locks the key. Browser signing
   covers the status write in this version; the other steps use a gateway.

A wrong seed is refused before submission (`the seed does not match an active key`), and a
finalized command the chain rejects is reported with its error code (`REJECTED with error code
17 (ACTOR_SIGNATURE)` and the like).

## Onboarding an issuer after genesis

`issuers` is approval-gated: a registrar proposes, two registrars from distinct organizations
approve, and the map command carries the approval reference. Version one ships no CLI for that
route; the cluster test `TrustRegistryClusterTest.issuerOnboardingThroughTheApprovalRouteAnswersWithReceiptProvenance`
drives it with the Java contracts (`TrustRegistrySigner.approvalAction`, `approvalPayloadHash`,
`signedStatement`, `approvalCommand`) and is the executable reference. An entry written that way
answers with `RECEIPT` provenance.

## Proof states and trust levels

| Row | Meaning |
|---|---|
| `ACTIVE` / `REVOKED` / `ABSENT` | Inclusion with the entry; tombstone that retains the last logical value hash; exclusion proof |
| provenance `GENESIS` | Seeded before any block; no receipt exists |
| provenance `RECEIPT` | Bound to the applied receipt of the command that produced this revision (approval-routed writes) |
| provenance `DIRECT_ROLE` | Additionally bound to the one-use consumption and the actor, organization, and policy records at the revisions the consumption names; the actor authorization was signed under the map genesis id the chain's genesis marker holds |
| `INTERNAL_CONSISTENCY_ONLY` (exit 6) | The finality certificate is consistent with the members the evidence bundle itself declares |
| `CALLER_PINNED_ROOT` (exit 5) | The certificate verifies under a member set and threshold the caller pinned in a members file (`{"chainId", "memberKeysHex": [...], "threshold"}`) |
| `INDEPENDENTLY_VERIFIED_L1_ANCHOR` (exit 0) | The answer height, root, block hash, genesis, profile, members, and threshold match an `AnchorDatumV1` the caller read from Cardano (`--anchor-datum-hex`) |

Proofs carry the chain's state commitment identity. On a composite runtime that identity is the
application-profile-bound derivative of the map genesis id, which actor authorizations are signed
with; the verifier reads the map genesis id from the proven genesis marker rather than assuming
the two are equal. The console's `BOUND` means every fact names the answer's chain, genesis,
height, root, and certified block and the finalized block at that height agrees; the MPF paths
and the certificate are verified by the CLI.

## Security notes

- Seeds are read from owner-only files (`chmod 600`) and never logged. Demo seeds are derived
  from public strings; never reuse them outside a local demo.
- Direct-role authorizations are one-use, scoped to the chain and genesis, and expire within 100
  blocks.
- The service exposes read routes only, bounded responses, a bounded replay (5,000 status writes;
  a longer chain answers 503 rather than a truncated list), and allows any origin to read lists.
- API keys are read from `YANO_API_KEY` or `--api-key-file`; the console keeps them in memory.
  `registry.sh env` prints the demo API key as a shell export; `eval` it in a shell whose
  history and transcripts you control, or read the key file with `--api-key-file` instead.
- The service replays point-in-time projections under the same lock as the tip projection; a
  long historical replay delays other requests (ADR-049 §8).
- No JSON-LD or RDF processor is on the runtime classpath; a test in each module enforces it.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `the chain has no block yet; pass --genesis-id` | First write on a fresh chain; pass the generated `state.genesis-id` |
| `the seed does not match an active key of <actor>` | Wrong seed or actor id; on the launcher use the seed directory files |
| `REJECTED with error code 18 (AUTHORIZATION_DEADLINE)` | The authorization expired before finalization; retry |
| `status list ... is not published at height H` | `publish-list` has not run, or the height predates publication |
| `matches chain: false` | Status writes were applied after publication; run `publish-list` again |
| node 1 fails with `cannot adopt an archive that already covers blocks` | Members share a projection archive; disable `yano.history.projection` per node as the launcher does |
| Console shows `profile unverified` | The chain is a map but not a registry, or it has no block yet |

## Tests

```bash
./gradlew :products:trust-registry:profile:test :products:trust-registry:client:test \
  :products:trust-registry:cli:test
cd products/trust-registry/ui && npm ci && npm run check && npm test && npm run build
```

Add the Yano version properties the repository currently requires (see `docs/BUILD_AND_TEST.md`).
The client test starts a three-member governed map in process, drives every journey including
issuer onboarding through the approval route, and writes the golden fixtures with
`-PtrustGoldenWrite=true`; the CLI and console tests re-verify the committed copies.
