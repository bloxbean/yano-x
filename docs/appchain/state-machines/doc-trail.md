# doc-trail

`doc-trail` keeps an append-only, tamper-evident history of document hashes
for each business entity, such as a product, case or shipment. Each append
chains the new hash into the entity's head. The documents themselves stay off
the ledger.

The machine id is exactly `doc-trail`. An entity id is any stable application
identifier.

## At a glance

| | |
|---|---|
| Machine id | `doc-trail` |
| Maturity | stable |
| Command | `[entityId, entryHash, reference]`: `entityId` text, `entryHash` non-empty bytes, `reference` text (`""` when none) |
| Settings | none |
| State | `e/<entityId>` → `[count, headHash]` |
| Head | `head' = Blake2b-256(head ‖ entryHash ‖ sender)`, starting from 32 zero bytes |
| Proof subject | `document-head-v1`: coordinate `entity-id`; claims `revision-exact`, `revision-minimum`, `digest-equals` |
| Result codes | none: every well-formed append applies |
| Events | `doc-trail.entry-appended.v1`, in composites only |

## How it works

<!-- illustration: doc-trail-chain -->
1. **Start empty.** An entity without a trail starts from a head of 32 zero
   bytes.
2. **A appends v1.** The head becomes the hash of the old head, the entry hash
   and A's member key.
3. **B appends v2.** The head now commits to both entries, their order and both
   authors.
4. **Verify.** A verifier recomputes the head from block history and compares
   it with the proven head.
<!-- /illustration -->

What the head covers, and what it does not:

- **Covered:** each entry hash, the order of entries, and the member key that
  submitted each one. Changing any of them changes the head.
- **Not covered:** the reference. It is kept in the finalized command in block
  history, but it is not hashed into the head. Authenticate a document by its
  hash, not by where the reference points.
- **No ownership.** Any member can append to any entity, and appending the same
  hash twice is simply two entries.

## When to use it

Use `doc-trail` when several members need an ordered, tamper-evident history per
entity without replicating whole documents: product passports, supply-chain
histories, case files, document revisions, certificate histories.

Choose [`ordered-log`](ordered-log.md) for one global order of events, or
[`kv-registry`](kv-registry.md) for a mutable current value. Use a composite or
a plugin when appends need owners, roles, approvals or domain validation.

## Configure

`doc-trail` has no settings of its own. The stock local cluster has no
`doc-trail` chain; select the `document-trail` recipe for a new project, or add
a chain entry with the three
[state-identity settings](README.md#before-you-configure-one) and a fresh
genesis id:

```yaml
yano:
  app-chain:
    chains[3]:
      chain-id: "document-trail-chain"
      state-machine: doc-trail
      state:
        commitment-profile: mpf-blake2b256-v1
        format-fingerprint: 91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b
        genesis-id: <64 lowercase hex characters, unique to this chain>
      membership:
        mode: governed
      block:
        interval-ms: 1000
```

To add a chain to a generated project, use the
[add-chain workflow](../deployment/add-chain.md). Do not reinterpret retained
data from another machine as `doc-trail` state.

## Hash and submit a document through REST

You choose the document hash algorithm; SHA-256 is common. The head itself
always uses Blake2b-256. From the top-level directory of the extracted release:

```bash
TOOL=docs/appchain/tutorials/tools/stdlib_command.py
ENTITY=product-42

ENTRY_HASH=$(python3 -c \
  'import hashlib; print(hashlib.sha256(b"quality certificate v1").hexdigest())')

APPEND_HEX=$(python3 "$TOOL" doc-trail "$ENTITY" "$ENTRY_HASH" \
  --reference 's3://evidence/product-42/certificate-v1.pdf')

curl -sS -X POST \
  http://127.0.0.1:7071/api/v1/app-chain/chains/document-trail-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"doc-trail.command.v1\",\"bodyHex\":\"$APPEND_HEX\"}" | jq .
```

Without `--reference`, the helper sends an empty reference, which the command
requires. `202` means queued; wait until the message is final before treating
the trail as advanced. Append the next revision the same way. Submitting it
through another member, such as port 7072, records a different author in the
head.

## Submit from Java

```groovy
implementation "org.yanoproject.x:yano-x-client:${yanoXVersion}"
```

```java
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.StdlibAppChainClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

var client = AppChainClient.builder("http://127.0.0.1:7071/api/v1")
        .chainId("document-trail-chain")
        .build();
var documents = new StdlibAppChainClient(client);

byte[] document = "quality certificate v1".getBytes(StandardCharsets.UTF_8);
byte[] entryHash = MessageDigest.getInstance("SHA-256").digest(document);

var submitted = documents.appendDocument("product-42", entryHash,
        "s3://evidence/product-42/certificate-v1.pdf");
```

Store which hash algorithm you used next to the document or in a versioned
envelope. `doc-trail` treats `entryHash` as opaque bytes.

## Read and prove a trail head

```bash
STATE_KEY_HEX=$(python3 -c 'print("e/product-42".encode().hex())')

curl -sS \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/document-trail-chain/state/proof/$STATE_KEY_HEX" \
  | jq '{committedHeight, stateRoot, presence, valueHex}'
```

`valueHex` is CBOR `[count, headHash]`. It proves the trail summary, not that
the referenced documents still exist. In Java:

```java
var trail = documents.documentTrail("product-42").orElseThrow().value();
System.out.println("entries=" + trail.count());
```

To verify the whole trail independently:

1. Collect the finalized commands for the entity from block history, in order,
   with each command's sender.
2. Recompute the head with `DocTrailContract.computeHead(entryHashes, authors)`.
3. Compare it with the proven head, and verify that proof against a state root
   from pinned finality or a Cardano anchor.
4. Fetch each document, hash it with your declared algorithm, and match it to
   its `entryHash`.

Useful history endpoints, relative to `/api/v1/app-chain`:

```text
GET /chains/{chainId}/blocks?from=1&limit=100
GET /chains/{chainId}/messages/by-topic/doc-trail.command.v1?fromHeight=0&limit=100
GET /chains/{chainId}/messages/{messageId}
```

## Design notes

- **Keep documents outside consensus.** Submit hashes and short references,
  not files. Every member receives every command.
- **Define canonical bytes.** Two equivalent JSON documents can hash
  differently. Canonicalize before hashing, or hash the stored file exactly.
- **Integrity is not availability.** The trail proves an ordered digest; your
  storage must keep the document available.

## Advanced

### Composites

In a declarative composite, an append emits `doc-trail.entry-appended.v1` with
`entityId`, `entryHash`, `reference`, `headHash`, `count` and `sender`. A
trail whose count is already at the maximum rejects the append with
`DOC_COUNT_OVERFLOW`. To require approval before an append, combine
`approvals` and `doc-trail` in a committed composite.

### Admission-rule views and facts

Admission rules can read a trail's head (ADR-031.4, see
[admission rules](../bindings/07-admission-rules.md)):

- **Value view** (namespace `""`, key: the entity id): `count`, `headHash`.
- **Post-state facts**, after an approved append: `countAfter` and `first`
  (the append created the trail).

## Related documentation

- [Choose a stock state machine](../tutorials/03-stock-state-machines.md)
- [Evidence publication tutorial](../tutorials/04-evidence-publication.md)
- [Plugins and composites](../tutorials/08-plugins-and-composites.md)
- [State machines](README.md)
- [Java app ledger client](../../../sdk/client/README.md)
