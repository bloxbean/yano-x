# authenticated-map value validation

Each `authenticated-map` collection chooses a value encoding and, optionally, a
validator: a declarative schema or a plugin. These choices are compiled into
genesis and are identical on every member. This page covers validation, the
offline preflight, and every result code.

Start with the [authenticated-map guide](authenticated-map.md) for the
collection model, operations, configuration and submission.

## At a glance

| Setting | Default | Effect |
|---|---|---|
| `valueEncoding` | `opaque` | Any byte string within the value bound |
| `validator` | none | No schema or plugin check |
| `authorization` | `open` | Any member's message may write |
| `restoreAllowed` | `false` | Revoked entries stay revoked |

Validation is optional. Opting into an encoding, schema or plugin changes
consensus validity and therefore the genesis id.

## How it works

<!-- illustration: authmap-validation-pipeline -->
1. **Preflight.** Check the value offline against the exact genesis. The result
   is advisory.
2. **Ingress.** The receiving member applies the same value rules before
   answering `202`.
3. **Candidate block.** The command is checked again for the height of the
   block that would include it.
4. **Apply.** Every member applies the command and validates the value once
   more. The receipt records the outcome.
<!-- /illustration -->

The value rules run in a fixed order: the collection's bounds, then the
encoding, then the schema, then the plugin. A value refused at ingress gets
HTTP `400` with `APPLICATION_REJECTED`, is not pooled, and has no receipt.

## Create a project

Genesis commits the initial membership, so supply every member public key when
you initialize:

```bash
./yano.sh appchain init --non-interactive \
  --recipe authenticated-map --network preprod --members 3 \
  --member-key <member-0-64-hex> \
  --member-key <member-1-64-hex> \
  --member-key <member-2-64-hex> \
  --name product-registry --chain-id product-registry \
  --output product-registry
```

For a disposable local devnet, use `--network devnet` and replace the
`--member-key` options with `--generate-local-member-keys`. It keeps the private
keys in the project's owner-only `secrets/` directory and pins only the public
keys. On public networks, operators always supply their own keys.

The generated project starts with one open, opaque `records` collection. Edit
`spec.chains[0].authenticatedMap` in `appchain.yaml`, then render again:

```yaml
authenticatedMap:
  profile: mpf-blake2b256-v1
  # All-zero is the initializer's development/no-policy placeholder.
  # Replace it with the reviewed 32-byte commitment before an anchored chain
  # generation is created.
  anchorPolicyCommitment: "0000000000000000000000000000000000000000000000000000000000000000"
  maxBatchItems: 128
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
      maxKeyBytes: 64
      maxValueBytes: 16384
      valueEncoding: canonical-cbor
    - id: products
      authorization: owner
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

Run the renderer and doctor after every edit:

```bash
./yano.sh appchain render product-registry
./yano.sh appchain doctor product-registry --distribution /path/to/yano-x-jvm-<version>
```

Doctor reports `authenticated-map-schema-encoding`. A collection with a schema
must use `canonical-cbor`; `opaque` plus a schema is rejected before genesis is
written.

The generated outputs include:

- `config/shared-consensus.yaml`, with the exact genesis and state-identity
  settings the nodes use;
- `config/authenticated-map-genesis.hex`, the canonical genesis for offline
  tooling; and
- `docs/VALUE_VALIDATION.md`, with project-specific validation commands.

## Encodings and schemas

- **`opaque`** checks only the byte length. Use it for formats that are
  already canonical, encrypted payloads, large attachments, or record shapes
  that will change during the chain's life.
- **`canonical-cbor`** accepts exactly one bounded, deterministic CBOR item. It
  rejects indefinite lengths, non-minimal integers, duplicate or misordered map
  keys, trailing bytes, invalid UTF-8, and unsupported tags or simple values.
  Equal logical values then have equal bytes and equal value hashes.
- **A schema** is written in `cddl-yano-subset-v1`. Devtools compile it to
  canonical `yano-cbor-schema-ir-v1`, and nodes evaluate the IR; they never
  parse CDDL. It supports exact text-keyed maps, bounded arrays, integer
  ranges, bounded text and byte strings, literals, choices and optional fields.
  External references, recursion, unbounded repetition, regular expressions
  and host-dependent extensions are rejected.

Schemas are exact: undeclared map fields are rejected. A schema is immutable
for the chain generation, so leave generous bounds for fields that may grow.

The commitment profiles are `mpf-blake2b256-v1` and `jmt-blake2b256-v1`. The
Poseidon JMT profile is unavailable until ADR-025 Phase 4 and its pinned ZeroJ
dependency are complete; blueprints refuse it.

## Offline preflight and inspection

Inspect the collections and validators committed by genesis:

```bash
./yano.sh appchain state validators \
  --genesis-file product-registry/config/authenticated-map-genesis.hex
```

The result shows the profile, format fingerprint, `genesisId`, each
collection's encoding and bounds, each validator's kind and contract version,
the schema and parameter SHA-256, and a plugin's artifact-closure SHA-256.

Validate a candidate from a file or canonical lowercase hex:

```bash
./yano.sh appchain state validate \
  --genesis-file product-registry/config/authenticated-map-genesis.hex \
  --collection products --key 736b752d31 --value-file product.cbor

./yano.sh appchain state validate \
  --genesis-file product-registry/config/authenticated-map-genesis.hex \
  --collection products --key 736b752d31 \
  --value-hex a363736b7565736b752d316673746174757366616374697665687175616e7469747905
```

The answer is `ACCEPTED`, `REJECTED` with a code, or `UNAVAILABLE`.
`UNAVAILABLE` means the collection uses a plugin that the offline CLI cannot
run; it never means accepted. All three are advisory: every node validates
again.

`./yano.sh appchain state explain --code <0..12>` explains codes 0 to 12.

## Result and receipt codes

Preflight returns codes 0 to 12. A receipt carries the code of the first check
that rejected a command that reached apply.

| Code | Name | Meaning | Where it appears |
|---:|---|---|---|
| 0 | `NONE` | Applied without an error | receipt |
| 1 | `UNKNOWN_COLLECTION` | The collection is not in genesis | preflight only; a node refuses it at admission |
| 2 | `COLLECTION_BOUNDS` | Key or value exceeds the collection's bounds | preflight only; a node refuses it at admission |
| 3 | `UNAUTHORIZED` | The sender does not satisfy the collection's authorization, or a transfer outside an `owner` collection | receipt |
| 4 | `ALREADY_EXISTS` | `PUT_IF_ABSENT` on an active entry | receipt |
| 5 | `ABSENT` | The operation needs an existing entry | receipt |
| 6 | `REVOKED` | The entry is a tombstone and the operation is not `RESTORE` | receipt |
| 7 | `ACTIVE` | `RESTORE` on an active entry | receipt |
| 8 | `PRECONDITION` | Expected revision or value hash did not match | receipt |
| 9 | `RESTORE_FORBIDDEN` | The collection does not allow restore | receipt |
| 10 | `VALUE_ENCODING` | The encoding rejected the value | preflight; normally refused at admission |
| 11 | `VALUE_SCHEMA` | The schema rejected the value | preflight; normally refused at admission |
| 12 | `VALUE_VALIDATOR` | The plugin rejected the value; `UNAVAILABLE` in preflight without an adapter | preflight; normally refused at admission |
| 13 | `AUTHORIZATION_ASSIGNMENT` | A mutation's authorization kind or policy differs from its collection's genesis | receipt |
| 14 | `UNKNOWN_POLICY` | The direct-role policy has no current revision | receipt |
| 15 | `POLICY_INACTIVE` | The direct-role policy's current revision is not active | receipt |
| 16 | `ACTOR_INELIGIBLE` | Actor or organization not current and active, role missing, or key not active at this height | receipt |
| 17 | `ACTOR_SIGNATURE` | The actor's signature does not verify | receipt; normally refused at admission with `INVALID_SIGNATURE` |
| 18 | `AUTHORIZATION_DEADLINE` | Issued in the future, past its deadline height, or longer-lived than the policy allows | receipt |
| 19 | `DIRECT_AUTHORIZATION_REPLAY` | The actor's authorization id was already consumed | receipt |
| 20 | `APPROVAL_NOT_APPROVED` | The referenced proposal is missing, not approved, or past its deadline height | receipt |
| 21 | `APPROVAL_MISMATCH` | The approved proposal is for another action, payload or policy revision | receipt |
| 22 | `APPROVAL_REPLAY` | The proposal was already consumed | receipt |
| 23 | `CAPACITY_EXCEEDED` | Defined in the contract; the map does not produce it in this release | none |
| 24 | `CRYPTO_WORK_EXCEEDED` | The block's signature-work budget for governed evidence is used up | receipt |
| 25 | `GOVERNED_ROUTE_UNSUPPORTED` | A governed mutation on a chain whose genesis has no governed configuration | receipt |
| 26 | `WRONG_GENESIS` | The evidence was signed for another chain, genesis or action | receipt |
| 27 | `WRONG_REVISION` | The evidence names a policy revision that is not current | receipt |

Only a command that reached apply has a receipt. A preflight rejection, or a
command refused at admission or in candidate-block validation, has none and
makes no finality claim. In a declarative composite, a rejected map command
rejects the cascade with the code `MAP_<n>`.

## Java client preflight

The client can decode genesis and reuse the encoding and schema checks without
the runtime:

```java
byte[] genesisBytes = HexFormat.of().parseHex(Files.readString(
        Path.of("config/authenticated-map-genesis.hex")).strip());
var preflight = AuthenticatedMapPreflight.fromEncodedGenesis(genesisBytes);

var mutation = AuthenticatedMapContract.Mutation.put("products", skuBytes, canonicalProductCbor);
var result = preflight.validate(mutation);

if (result.accepted()) {
    var action = AuthenticatedMapAuthoring.action(AuthenticatedMapContract.Command.single(mutation),
            List.of(new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                    0, AuthenticatedMapContract.AUTH_OWNER, "", 0)));
    stock.authenticatedMapGovernedCommand(AuthenticatedMapAuthoring.command(action, List.of()));
}
```

The chain admits only this action-and-evidence envelope; the client's older
`authenticatedMapMutate(mutation, preflight)` overloads send a mutation-only
encoding that it refuses.

A custom plugin needs an application-supplied adapter, chosen from the exact
genesis descriptor:

```java
var preflight = AuthenticatedMapPreflight.fromEncodedGenesis(genesisBytes, descriptor -> {
    if (!descriptor.providerId().equals("gs1-gtin-v1")) {
        return Optional.empty();
    }
    verifyPinnedArtifactClosure(descriptor.definition());
    return Optional.of((collection, key, value) -> applicationGs1Check(key, value));
});
```

Returning `Optional.empty()` gives `UNAVAILABLE`, not acceptance. The adapter
is a convenience: a client can skip it, so the genesis-pinned validator always
runs on the nodes.

## Custom validator plugins

Schemas cover record shape. Use a plugin only for a deterministic,
self-contained rule over `(collectionId, applicationKey, value)` that the CDDL
subset cannot express. Through the SPI, a plugin cannot read state, sender,
height, membership, clock, randomness, environment, files or network.

Java bytecode is not sandboxed. A plugin can still call JDK APIs directly, so
validators are trusted consensus code: review them, allow-list them, test
them, and pin them by their exact `ARTIFACT_CLOSURE` digest. Never load
untrusted code.

The descriptor lives in the no-SPI contracts artifact; the factory SPI lives in
`core-api`. Programmatic genesis uses:

```java
var validator = AuthenticatedMapContract.ValidatorDescriptor.plugin(
        "gtin-v1",
        "gs1-gtin-v1",
        exactArtifactClosureSha256,
        canonicalCborParameterMap);

var collection = new AuthenticatedMapContract.CollectionDescriptor(
        "products",
        AuthenticatedMapContract.AUTH_OWNER,
        false,
        64,
        4096,
        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR,
        validator.id());

var genesis = AuthenticatedMapGenesisFactory.mpf(
        config,
        anchorPolicyCommitment,
        128,
        65536,
        List.of(collection),
        List.of(validator),
        List.of());
```

The first-party `appchain-authenticated-map-validators` module provides
`gs1-gtin-v1` as a worked example for GTIN-8, GTIN-12, GTIN-13 and GTIN-14.
Production resolution also requires the bundle in the runtime allow-list with
catalog digest mode `ARTIFACT_CLOSURE`.

Blueprints do not expose custom plugin descriptors in v1alpha1, so a generated
project never implies that an arbitrary bundle is safe or installed. Build
plugin genesis programmatically, or stay with canonical encoding and schemas.

## Choosing the right boundary

Value validation sees one key and one value. A rule that reads another key,
relates collections, checks membership or emits effects belongs in a composite
component or a custom state machine.

Validation proves that committed bytes satisfy the declared encoding and rule.
It does not prove the record is true, keep it confidential, or replace
authorization.

In v1, encodings, schemas, plugins and their parameters are immutable for the
chain generation. Changing one needs a new chain generation and an explicit
import plan. For a record shape likely to evolve, prefer `canonical-cbor`
without a validator.
