# authenticated-map

`authenticated-map` is a proof-oriented registry with several named
collections. Each collection has its own authorization, key and value bounds,
value encoding and optional validator, all fixed at genesis. Every active entry
and every revocation tombstone can be proved against the state root.

It is an authenticated data structure, not a database. It has no secondary
indexes, joins, range queries, confidentiality or rules that relate two
entries.

## At a glance

| | |
|---|---|
| Machine id | `authenticated-map`, a composite of the map and the role-workflow components |
| Maturity | preview |
| Topic | `authenticated-map.command.v1`, enforced |
| Command | `[1, action, evidence]`: an action of one mutation or a batch, plus evidence for governed collections |
| Operations | `PUT`, `PUT_IF_ABSENT`, `COMPARE_AND_SET`, `TRANSFER_CONTROLLER`, `REVOKE`, `RESTORE` |
| Authorization | per collection: `open`, `owner`, `member`, `governed-role` or `approval` |
| State | one record per collection and key: status, revision, controller, value, logical value hash, heights |
| Proof subject | `authenticated-map-entry-v1`: coordinates `collection`, `key` (hex); claims `status`, `revision-exact`, `value-digest` |
| Result codes | receipt codes 3 to 27; see [the code table](authenticated-map-validation.md#result-and-receipt-codes) |
| Events | `authenticated-map.entry-updated.v1`, `authenticated-map.batch-applied.v1`, in composites only |

## How it works

<!-- illustration: authmap-lab -->
1. **A creates.** A PUT on an absent key creates revision 1. In an `owner`
   collection the sender becomes the controller.
2. **A updates with a precondition.** COMPARE_AND_SET applies only if the
   expected revision or value hash matches.
3. **A hands control to B.** TRANSFER_CONTROLLER works only in `owner`
   collections.
4. **B revokes.** REVOKE leaves a tombstone: the value is removed, the last
   value hash and the revision history remain.
<!-- /illustration -->

For each mutation the machine checks, in order:

1. **An absent entry** accepts only `PUT` or `PUT_IF_ABSENT`; anything else is
   `ABSENT` (5).
2. **Authorization.** `owner`: the sender must be the controller. `member`:
   the sender must be a member at this height. `governed-role` and `approval`:
   verified evidence must cover the mutation. Otherwise `UNAUTHORIZED` (3).
3. **A tombstone** accepts only `RESTORE` (`REVOKED`, 6), and only if the
   collection sets `restoreAllowed` (`RESTORE_FORBIDDEN`, 9).
4. **The operation.** `PUT_IF_ABSENT` on an active entry is `ALREADY_EXISTS`
   (4). `RESTORE` on an active entry is `ACTIVE` (7). `COMPARE_AND_SET`,
   `TRANSFER_CONTROLLER` and `REVOKE` check their optional expected revision
   and value hash (`PRECONDITION`, 8); `COMPARE_AND_SET` must carry at least one.
   `TRANSFER_CONTROLLER` outside an `owner` collection is `UNAUTHORIZED` (3).
5. **The value**, for `PUT`, `PUT_IF_ABSENT`, `COMPARE_AND_SET` and `RESTORE`:
   encoding (10), then schema (11), then plugin validator (12).

A batch is all or nothing. It cannot touch the same collection and key twice,
and if any mutation fails, none of its entries change. Every command that
reaches apply leaves a **receipt** under its message id: APPLIED with the
results, or REJECTED with one code. A command refused at admission has no
receipt.

<!-- illustration: authmap-presence -->

The **logical value hash** is Blake2b-256 over the ASCII bytes
`yano-authenticated-map-value-v1`, a zero byte, the value length as a 4-byte
big-endian integer, and the value. A tombstone keeps the hash of its last value.

## When to use it

Use `authenticated-map` when an application needs several independently
configured key/value collections with verifiable current state:

- product, asset, credential or configuration registries;
- owner-controlled records with controller transfer and revocation;
- member-maintained reference data;
- opaque hashes, attachments or encrypted values;
- atomic updates to several keys; or
- values that must pass a deterministic schema or validator.

Use [`kv-registry`](kv-registry.md) when one owner-guarded namespace is enough,
and [`ordered-log`](ordered-log.md) when order matters more than current state.
Write a composite or plugin when a transition must read several entries, keep
secondary indexes, relate collections or emit effects.

## Collections

Genesis defines up to 64 collections:

| Option | Meaning |
|---|---|
| `id` | Stable lowercase collection id, at most 64 bytes |
| `authorization` | `open`, `owner`, `member`, `governed-role` or `approval` |
| `authorizationPolicy` | Stable policy id; only for the two governed modes |
| `restoreAllowed` | Whether a tombstone may become active again |
| `maxKeyBytes` | Maximum application-key size, at most 128 |
| `maxValueBytes` | Maximum value size, at most 1 MiB |
| `valueEncoding` | `opaque` or `canonical-cbor` |
| `validator` | Optional schema or plugin validator id |

- `open` lets any member's message write.
- `owner` makes the first successful creator the 32-byte controller; only the
  controller can change the entry, and only `owner` supports transfer.
- `member` requires the sender to be an active member at the block's height.
- `governed-role` requires a current actor with the policy's role and a
  one-use signature over the complete action.
- `approval` requires a terminal role-approval proposal for exactly this
  action, and consumes it together with the mutation.

Member keys stay consensus identities. Governed modes use separate actors,
organizations, rotatable actor keys and immutable policy revisions. Any member
may relay actor-signed evidence; the relay gains no authority and cannot
change the signed action.

## Configure

Start from the project recipe. Membership, consensus settings, collections,
validators and the state-commitment profile are all part of genesis, so the
member keys must be known up front:

```bash
./yano.sh appchain init --non-interactive \
  --recipe authenticated-map --network preprod --members 3 \
  --member-key <member-0-64-hex> \
  --member-key <member-1-64-hex> \
  --member-key <member-2-64-hex> \
  --name product-registry --chain-id product-registry \
  --output product-registry
```

Then edit `spec.chains[0].authenticatedMap` in `appchain.yaml`. This example
combines an opaque collection, a canonical-CBOR collection and a schema:

```yaml
authenticatedMap:
  profile: mpf-blake2b256-v1
  anchorPolicyCommitment: "0000000000000000000000000000000000000000000000000000000000000000"
  maxBatchItems: 32
  maxBatchBytes: 65536
  collections:
    - id: attachments
      authorization: owner
      restoreAllowed: false
      maxKeyBytes: 64
      maxValueBytes: 1048576
      valueEncoding: opaque
    - id: canonical-events
      authorization: member
      restoreAllowed: false
      maxKeyBytes: 64
      maxValueBytes: 16384
      valueEncoding: canonical-cbor
    - id: products
      authorization: owner
      restoreAllowed: false
      maxKeyBytes: 64
      maxValueBytes: 4096
      valueEncoding: canonical-cbor
      validator: product-v1
  schemas:
    - id: product-v1
      root: product
      source: |
        product = {
          sku: tstr .size (1..32),
          quantity: uint .le 1000000,
          status: "active" / "held" / "retired",
          ? note: tstr .size (0..256)
        }
```

Render and check the exact release:

```bash
./yano.sh appchain render product-registry
./yano.sh appchain doctor product-registry --distribution /path/to/yano-release.zip
```

The renderer writes the canonical genesis and the three state-identity
settings, `state.commitment-profile`, `state.format-fingerprint` and
`state.genesis-id`. Do not edit them by hand. Every member must use the same
generated configuration and validator artifacts.

`anchorPolicyCommitment` is part of the chain identity. The all-zero value is a
development placeholder; replace it with the reviewed 32-byte commitment
before you create an anchored production chain. The commitment profiles are
`mpf-blake2b256-v1` and `jmt-blake2b256-v1`; the Poseidon JMT profile is
reserved.

Encoding, schemas and validator plugins are described in
[value validation](authenticated-map-validation.md).

## Run the showcase

The light showcase includes `authenticated-map-chain` with four collections:

| Collection | Authorization | Encoding and validation |
|---|---|---|
| `attachments` | owner | opaque bytes |
| `canonical-events` | member | canonical-CBOR array |
| `products` | owner | canonical-CBOR map with the `product-v1` schema |
| `gtins` | owner | canonical-CBOR text with the first-party `gs1-gtin-v1` plugin |

From `examples/showcase` in the extracted release:

```bash
./showcase.sh quickstart --profile light --nodes 3 --instance authmap-demo
./demos/submit-authenticated-map.sh authmap-demo
```

The scenario writes to every collection, shows the product schema and the GTIN
plugin refusing bad values at admission, runs a root-attested point query and
fetches a native proof.

## Submit through REST

The chain admits only the final command envelope: an action that assigns each
mutation its collection's authorization kind, plus evidence. For the basic
kinds (`open`, `owner`, `member`) the evidence list is empty. From
`examples/showcase`, with the showcase running:

```bash
BASE=http://127.0.0.1:7070/api/v1
CHAIN=authenticated-map-chain
CODEC=tools/showcase_codec.py
DOMAIN=$BASE/plugins/org.yanoproject.x.stdlib

# Wrap a codec-built mutation in the final envelope.
# kind is the collection's authorization: open, owner or member.
wrap() {
  local action
  action=$(yano/yano.sh appchain authenticated-map action \
    --command-hex "$2" --assignments "0:$1::0")
  yano/yano.sh appchain authenticated-map command --action-hex "$action" --evidence-hex ''
}

VALUE=$(python3 "$CODEC" authmap-value product sku-42 5 active 'demo product')
BODY=$(wrap owner "$(python3 "$CODEC" authmap put products sku-42 "$VALUE")")

MESSAGE_ID=$(curl -fsS -X POST "$BASE/app-chain/chains/$CHAIN/messages" \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"authenticated-map.command.v1\",\"bodyHex\":\"$BODY\"}" | jq -r .messageId)
```

A value that breaks the encoding, the schema or a plugin is refused at once
with HTTP `400` and `APPLICATION_REJECTED`; nothing is pooled and no receipt
exists. `202` means only that the command was queued. Wait until it is final,
then read the receipt and the entry:

```bash
until curl -fsS "$BASE/app-chain/chains/$CHAIN/messages/$MESSAGE_ID" >/dev/null 2>&1; do sleep 1; done

curl -fsS "$DOMAIN/authenticated-map/receipts/$MESSAGE_ID?chain=$CHAIN" | jq .record
KEY_HEX=$(printf 'sku-42' | xxd -p)
curl -fsS "$DOMAIN/authenticated-map/entries/products/$KEY_HEX?chain=$CHAIN" | jq .
```

To prove the entry, use the `proofKey` that the entry route returns. It is the
map's key inside the composite state; never build it by hand:

```bash
PROOF_KEY=$(curl -fsS "$DOMAIN/authenticated-map/entries/products/$KEY_HEX?chain=$CHAIN" | jq -r .proofKey)
curl -fsS "$BASE/app-chain/chains/$CHAIN/state/proof/$PROOF_KEY" \
  | jq '{committedHeight, stateRoot, presence, valueHex}'
```

Verify the proof locally, and at an audit boundary bind its root to trusted
finality or a Cardano anchor.

## Submit from Java

Use `yano-x-stdlib-contracts` for the wire contract and `yano-x-client` for
submission, queries and proofs. Build the final envelope with
`AuthenticatedMapAuthoring`; a basic assignment has no policy and no evidence:

```java
AppChainClient raw = AppChainClient.builder("http://127.0.0.1:7070/api/v1")
        .chainId("product-registry")
        .build();
StdlibAppChainClient map = new StdlibAppChainClient(raw);

byte[] key = "sku-42".getBytes(StandardCharsets.UTF_8);
byte[] value = productCbor(); // one canonical CBOR value matching product-v1

var mutation = AuthenticatedMapContract.Mutation.put("products", key, value);
var action = AuthenticatedMapAuthoring.action(AuthenticatedMapContract.Command.single(mutation),
        List.of(new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                0, AuthenticatedMapContract.AUTH_OWNER, "", 0)));
var submitted = map.authenticatedMapGovernedCommand(
        AuthenticatedMapAuthoring.command(action, List.of()));

var point = map.authenticatedMapEntry("products", key);
var proof = map.authenticatedMapProof("products", key);
var receipt = map.authenticatedMapReceipt(HexFormat.of().parseHex(submitted.messageId()));
```

`authenticatedMapProof` targets the composite physical key, the same key the
domain API reports as `proofKey`. The client's `authenticatedMapMutate`,
`authenticatedMapBatch` and `authenticatedMapCommand` methods send the older
mutation-only encoding, which this chain refuses at admission; use the
envelope above.

For race-safe writes, use `compareAndSet` with the revision or value hash from
the last trusted entry. Use `Command.batch(...)` for an atomic list of distinct
keys.

## Advanced

### Governed collections

A `governed-role` or `approval` collection names a stable policy, and genesis
provides a closed set of organizations, actors, keys and policies. This
abridged direct-role example shows the shape; substitute the generated
lowercase hex values before rendering:

```yaml
authenticatedMap:
  collections:
    - id: regulated-products
      authorization: governed-role
      authorizationPolicy: issuer-write
      restoreAllowed: false
      maxKeyBytes: 64
      maxValueBytes: 4096
      valueEncoding: canonical-cbor
  authorizationGovernance:
    authorityId: registry-admins
    initialRevision: 1
    administratorActors: [admin-a]
    threshold: 1
    maximumMutationLifetimeBlocks: 1000
  genesisRecords:
    organizations:
      - id: manufacturer-a
        revision: 1
        status: active
    actors:
      - id: admin-a
        revision: 1
        organization: manufacturer-a
        status: active
        roles: [registry-admin, issuer]
        keys:
          - id: admin-a-v1
            algorithm: ed25519
            publicKey: "${ADMIN_PUBLIC_KEY}"
            proofOfPossession: "${ADMIN_POP_SIGNATURE}"
            validFromHeight: 1
            validUntilHeight: 0
            status: active
    directPolicies:
      - id: issuer-write
        revision: 1
        status: active
        requiredRole: issuer
        maximumAuthorizationLifetimeBlocks: 100
    approvalPolicies: []
```

Generate the public key and proof-of-possession offline. The seed stays in a
file you control and is never written to the project:

```bash
ADMIN_PUBLIC_KEY="$(./yano.sh appchain role public-key \
  --seed-file /owner-only/admin-a.seed)"
ADMIN_POP_SIGNATURE="$(./yano.sh appchain role key-proof-signature \
  --chain product-registry \
  --actor admin-a --actor-revision 1 --key admin-a-v1 \
  --public-key "$ADMIN_PUBLIC_KEY" \
  --valid-from-height 1 --valid-until-height 0 \
  --seed-file /owner-only/admin-a.seed)"
```

An approval policy has `proposerRoles` and one or more clauses. Each clause
sets a role, a minimum count and `distinctBy: actor|organization`. For example,
two `auditor` actors from distinct organizations:

```yaml
approvalPolicies:
  - id: product-release
    revision: 1
    status: active
    proposerRoles: [issuer]
    clauses:
      - id: independent-auditors
        role: auditor
        minimumCount: 2
        distinctBy: organization
    rejectionMode: any-eligible
    maximumLifetimeBlocks: 500
```

Every referenced organization, actor, key and policy must be revision 1,
canonical, active, proof-of-possession valid and inside the configured bounds;
the renderer refuses an invalid set. `authorizationLimits` can lower the
committed evidence, genesis, pending-index, expiry, query-page and crypto-work
maxima.

For a policy you plan to activate after genesis, list an `onboarding` item
instead:

```yaml
onboarding:
  - kind: approval-policy
    id: product-release
    note: activate before enabling release submissions
```

The collection stays fail-closed until governance activates that policy.
`appchain doctor` reports `GOVERNED_COLLECTION_NOT_BOOTSTRAPPED`, and the
renderer writes `bootstrap/authenticated-map-onboarding.yaml` as an
operational plan, not consensus state.

### Governed authoring and external signing

A governed command commits to the complete ordered action: every collection,
key, operation, value, precondition, controller, authorization kind, policy id,
evidence handle and covered mutation index. Evidence handles start at 1; `0`
means no evidence and is used for `open`, `owner` and `member`.

The CLI assembles canonical bytes and never reads an actor's private key. For
one `governed-role` mutation whose first evidence item is the actor's
authorization:

```bash
ACTION_HEX="$(./yano.sh appchain authenticated-map action \
  --command-hex "$BASIC_COMMAND_HEX" \
  --assignments '0:governed-role:issuer-write:1')"

PREIMAGE_HEX="$(./yano.sh appchain authenticated-map direct-preimage \
  --action-hex "$ACTION_HEX" \
  --authorization-id "$UNIQUE_32_BYTE_ID_HEX" \
  --chain product-registry --genesis-id "$GENESIS_ID_HEX" \
  --indexes 0 --policy issuer-write --policy-revision 1 \
  --actor issuer-a --actor-revision 1 --key issuer-a-v1 \
  --public-key "$ISSUER_PUBLIC_KEY" \
  --issued-height "$CURRENT_HEIGHT" --deadline-height "$DEADLINE_HEIGHT")"

# Sign PREIMAGE_HEX with the actor's Ed25519 key in the caller's wallet or HSM.
SIGNATURE_HEX="$(external-ed25519-signer "$PREIMAGE_HEX")"

EVIDENCE_HEX="$(./yano.sh appchain authenticated-map direct-complete \
  --action-hex "$ACTION_HEX" \
  --authorization-id "$UNIQUE_32_BYTE_ID_HEX" \
  --chain product-registry --genesis-id "$GENESIS_ID_HEX" \
  --indexes 0 --policy issuer-write --policy-revision 1 \
  --actor issuer-a --actor-revision 1 --key issuer-a-v1 \
  --public-key "$ISSUER_PUBLIC_KEY" \
  --issued-height "$CURRENT_HEIGHT" --deadline-height "$DEADLINE_HEIGHT" \
  --signature "$SIGNATURE_HEX")"

GOVERNED_COMMAND_HEX="$(./yano.sh appchain authenticated-map command \
  --action-hex "$ACTION_HEX" --evidence-hex "$EVIDENCE_HEX")"
```

`direct-complete` verifies the signature against the claimed public key before
emitting evidence. A successful action consumes `(actorId, authorizationId)`
once; reusing it is rejected with `DIRECT_AUTHORIZATION_REPLAY` (19), even
through another member.

For an `approval` collection, derive the proposal payload with
`authenticated-map approval-payload --action-hex ... --genesis-id ...`. Actors
sign `PROPOSE` and `APPROVE` statements for payload domain
`yano.authenticated-map.action.v1` with `appchain role sign`, and submit them
on `role-approvals.command.v1`. When the proposal is APPROVED, create its
evidence item with `authenticated-map approval-reference`, assemble the map
command, and submit it. Execution consumes the proposal id together with every
mutation; an approval never executes by itself.

In Java, `AuthenticatedMapAuthoring` keeps signing outside the client:

```java
var command = AuthenticatedMapContract.Command.single(mutation);
var action = AuthenticatedMapAuthoring.action(command, List.of(
        new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                0, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, "issuer-write", 1)));

var request = AuthenticatedMapAuthoring.directSigningRequest(
        authorizationId, "product-registry", genesisId, action, List.of(0),
        "issuer-write", 1, "issuer-a", 1, "issuer-a-v1", publicKey,
        currentHeight, deadlineHeight);
byte[] signature = externalSigner.sign(request.signingPreimage());
var evidence = AuthenticatedMapAuthoring.completeDirectSignature(request, signature);

map.authenticatedMapGovernedCommand(AuthenticatedMapAuthoring.command(action, List.of(evidence)));
```

### Domain API and composite proofs

The first-party bundle serves read-only routes below
`/api/v1/plugins/org.yanoproject.x.stdlib/`. Add `chain=<id>` when a node hosts
more than one app ledger.

| Route | Result |
|---|---|
| `authenticated-map` | Genesis identity, profile, collections, authorization capabilities |
| `authenticated-map/entries/{collection}/{keyHex}` | Exact entry, absence or tombstone, with `proofKey` |
| `authenticated-map/receipts/{messageIdHex}` | Receipt and complete action commitment |
| `authenticated-map/direct-consumptions/{actor}/{authorizationIdHex}` | One-use actor claim |
| `authenticated-map/approval-consumptions/{proposal}` | One-use proposal claim |
| `authenticated-map/direct-policies/{id}` | Current or `?revision=` direct policy |
| `authenticated-map/administrator-authorities/{id}` | Current or historical authority |
| `authenticated-map/pending/approvals` | Bounded `?after=&limit=` pending page |
| `authenticated-map/pending/actor-governance` | Bounded actor-governance page |
| `authenticated-map/pending/policy-governance` | Bounded policy-governance page |

The bundle also serves the organization, actor, approval-policy, proposal and
statistics routes of the role workflow. An exact-record response names the
chain, state machine, height and root, the physical proof key, the canonical
leaf value and, when it claims currency, the current-pointer key and value.
Pending pages instead return `sourceIndexProofKey` and a canonical
`queryValue`, labelled as an index-derived view. JSON is presentation only;
verify canonical values and native proofs.

`AuthenticatedMapProofBundle` verifies a bounded same-root assembly for
`BASIC`, `DIRECT_ROLE`, `APPROVAL` or `ADMINISTRATOR_GOVERNANCE`. It rejects
mixed chain, profile, genesis, root or height facts, wrong namespaces,
receipt-to-entry substitution, missing current pointers, wrong revisions,
invalid signatures, unsatisfied clauses and consumption mismatches. Use
`verify(trustedRoot)` with an independently trusted root, or
`verifyCertified(...)` with pinned finality membership. A proof at a later
root shows retention, not that a pointer was current at decision time.

### Custom validator plugins

Implement `AuthenticatedMapValueValidatorFactory` from `core-api`, register it
through `ServiceLoader` and a Yano plugin manifest, and return only `ACCEPT` or
`REJECT` from a total, deterministic validator. The descriptor in genesis pins
the provider id, the SPI contract version, the canonical parameters and the
exact `ARTIFACT_CLOSURE` SHA-256. The bundle must be in the runtime catalog
and allow-listed on every node.

Validator plugins are trusted in-process consensus code, not sandboxed uploads.
They must not depend on files, network, clock, randomness, locale, environment
or node-local configuration. The showcase's `gs1-gtin-v1` validator is a
working reference. Attaching another plugin to an existing chain creates a new
chain generation. See [value validation](authenticated-map-validation.md).

### Admission-rule views, write view and coverage

In a declarative composite, admission rules can read this map and judge every
write of a command (ADR-031.4, see [admission rules](../bindings/07-admission-rules.md)):

- **Value views**: one per collection (the namespace is the collection id, the
  key the application key): `status` (`ACTIVE`, `REVOKED`), `revision`,
  `createdHeight`, `lastMutationHeight`, `valueLength`, `controller`, and, for a
  canonical-CBOR collection whose schema root is a text-keyed map, its first 32
  top-level scalar members whose keys are CEL identifiers, as `value.<member>` in
  canonical member order. A revoked entry exposes no members.
- **Write view**: one element per mutation, in command order: `index`,
  `collection`, `key`, `keyText` (valid UTF-8 only), `op`, `hasValue`,
  `valueLength`, `expectedRevision`, and `value.<member>` for writes that carry a
  value in a schema-typed collection (a member two collections type differently
  is omitted). Rules quantify over it with `writes.all(w, …)` and
  `writes.exists(w, …)`.
- **Coverage**, only after the map verified and approved the command: `direct`
  with the covering actor's `actorId`, `actorOrganizationId` and `actorRoles`,
  `approval`, or `none` in open, owner and member collections. Rules that read
  coverage run in the verified-fact slot, so unverified evidence never reaches
  them. See `examples/bindings/dpp-namespace-isolation.yaml` and
  `feed-slot-rules.yaml`.

In a composite, a rejected map command rejects the cascade with the code
`MAP_<n>`, where `n` is the receipt code.

## Operational boundaries

Collections, validation rules, batch limits, the commitment profile, the
bootstrap membership digest and the anchor-policy commitment are all part of
the chain identity. Review and archive the rendered genesis before launch,
keep plugin artifacts byte-identical across nodes, and fail the deployment
when a digest or provider is missing.

Authenticated state proves which bytes were finalized under those rules. It
does not prove that a real-world claim is true, make values confidential, or
keep old command bodies forever. Archive evidence separately when you need it
later.

## Related documentation

- [Value validation](authenticated-map-validation.md)
- [role-approvals](role-approvals.md)
- [State machines](README.md)
- [Showcase walkthrough](../../../examples/showcase/DEMO_SHOWCASE.md)
