# kv-registry

`kv-registry` is a shared key/value registry with one simple rule: the member
that first writes a key owns it. Only the owner can update or delete the key.
Every current entry can be proved against the state root.

The machine id is exactly `kv-registry`. A chain id such as `registry-chain`
names one ledger that uses it.

## At a glance

| | |
|---|---|
| Machine id | `kv-registry` |
| Maturity | stable |
| Commands | `[0, key, value]` PUT; `[1, key, h'']` DELETE (canonical CBOR) |
| Setting | `machines.kv-registry.value-format`: `raw` (default), `utf8` or `cbor` |
| State | key bytes → `[owner, value]`, where owner is the 32-byte member key |
| Proof subject | `registry-entry-v1`: coordinate `key` (hex); claims `owner-equals`, `value-digest-equals` |
| Result codes | `KV_NOT_OWNER` |
| Events | `kv-registry.entry-put.v1`, `kv-registry.entry-deleted.v1`, in composites only |

## How it works

<!-- illustration: kv-ownership -->
1. **A puts.** Member A writes a key that has no entry, so A becomes its owner.
2. **B puts.** Member B writes the same key. The message is final, but the
   decision is `KV_NOT_OWNER` and nothing changes.
3. **A updates.** The owner replaces the value.
4. **A deletes.** The owner deletes the entry. A proof now shows the key as
   absent.
5. **B puts again.** The key has no entry, so B creates a new one and owns it.
   This is not a transfer.
<!-- /illustration -->

In short:

- A PUT on an absent key creates `[sender, value]`.
- A PUT or DELETE by the owner applies. By anyone else it is `KV_NOT_OWNER`.
- A DELETE of an absent key does nothing.
- On a standalone chain `KV_NOT_OWNER` is a final no-op. Inside a declarative
  composite it rejects the whole cascade instead.
- Events are emitted only in composites, and only when state changes.

### Admission rules

Before answering `202`, the receiving member decodes the body. It refuses the
body with HTTP `400` and the code `APPLICATION_REJECTED` unless, in this order:

1. the body is one canonical CBOR array of three items;
2. the key has 1 to 256 bytes;
3. the operation is 0 or 1, and a DELETE carries an empty value;
4. a PUT value is not empty and matches `value-format`.

Admission does not check ownership: that needs the state of the block that
finally includes the command.

<!-- illustration: wire-builder -->
1. **Write the command.** A PUT is `[0, key, value]`.
2. **Encode.** The helper prints canonical CBOR as hex.
3. **Admit.** The receiving member decodes it and answers `202`.
4. **Apply.** In the final block the ownership rule decides.
<!-- /illustration -->

## When to use it

Use `kv-registry` when you need a provable current value and “the first writer
owns this key” is the right authorization:

- allow and deny lists;
- product, asset, credential or schema metadata;
- DID documents or current document pointers;
- shared configuration with one owner per record.

Choose [`authenticated-map`](authenticated-map.md), a composite, or a plugin
when ownership must move under governance, several parties must approve an
update, values need domain rules, or authority belongs to a business actor
rather than a member node.

## Try it

The stock local cluster hosts `registry-chain` with `value-format: utf8`. From
the top-level directory of the extracted release:

```bash
./yano.sh appchain cluster start 3
./yano.sh appchain cluster kv registry-chain set supplier-42 active --node 1
```

Node 1 now owns `supplier-42`. A write through node 2 becomes final but cannot
change the entry:

```bash
./yano.sh appchain cluster kv registry-chain set supplier-42 suspended --node 2
```

The owner can update or delete it:

```bash
./yano.sh appchain cluster kv registry-chain set supplier-42 suspended --node 1
./yano.sh appchain cluster kv registry-chain del supplier-42 --node 1
```

[Tutorial 2](../tutorials/02-registry-and-proofs.md) walks through the same
steps with proofs.

## Submit through REST

REST takes the command bytes as `bodyHex`. Encode them with the tutorial
helper, from the top-level directory of the release or a source checkout:

```bash
TOOL=docs/appchain/tutorials/tools/stdlib_command.py

PUT_HEX=$(python3 "$TOOL" kv-registry put supplier-42 --value-text active)
DELETE_HEX=$(python3 "$TOOL" kv-registry delete supplier-42)
```

Submit the PUT through node 1, so node 1 becomes the owner:

```bash
curl -sS -X POST \
  http://127.0.0.1:7071/api/v1/app-chain/chains/registry-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"kv-registry.command.v1\",\"bodyHex\":\"$PUT_HEX\"}" | jq .
```

For a binary value, use `--value-hex 010203ff` instead of `--value-text`; it
needs a chain whose `value-format` is `raw`. Send the DELETE through the same
member.

The topic is a label. `kv-registry` ignores it, and it does not create a
namespace: the key bytes alone identify the entry. Use prefixed keys such as
`suppliers/acme`, or separate chains when membership should also differ.

## Submit from Java

```groovy
implementation "org.yanoproject.x:yano-x-client:${yanoXVersion}"
```

```java
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.StdlibAppChainClient;

import java.nio.charset.StandardCharsets;

var ownerClient = AppChainClient.builder("http://127.0.0.1:7071/api/v1")
        .chainId("registry-chain")
        // .apiKey("secret")
        .build();
var registry = new StdlibAppChainClient(ownerClient);

byte[] key = "supplier-42".getBytes(StandardCharsets.UTF_8);
var submitted = registry.kvPut(key, "active".getBytes(StandardCharsets.UTF_8));
System.out.println(submitted.messageId());

registry.kvDelete(key); // later, through the same member
```

The node signs a REST submission, not your client object. A client pointed at
port 7072 uses node 2's member key and cannot update a key that node 1 owns.

## Read and prove an entry

The state key is the key bytes themselves:

```bash
KEY_HEX=$(python3 -c 'print("supplier-42".encode().hex())')

curl -sS \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/registry-chain/state/proof/$KEY_HEX" \
  | jq '{committedHeight, stateRoot, presence, valueHex}'
```

`valueHex` is canonical CBOR `[ownerPublicKey, value]`. After a delete,
`presence` is `ABSENT` and the proof is an exclusion proof with no value.

The typed proof subject checks a claim for you. This one asks whether the
current value is `active`; the digest is Blake2b-256 of the value bytes:

```bash
DIGEST=$(python3 -c 'import hashlib; print(hashlib.blake2b(b"active", digest_size=32).hexdigest())')

curl -sS -X POST \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/registry-chain/proof-subjects/registry-entry-v1/proof" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg key "$KEY_HEX" --arg d "$DIGEST" \
    '{coordinates:{key:$key}, view:"latest",
      claim:{claimId:"value-digest-equals",operands:{expected:$d}}, includeEvidence:false}')" \
  | jq '{presence:.proof.presence, fact:.fact, satisfied:.claimResult.satisfied}'
```

From Java, `kvEntry` fetches the proof, checks it, and decodes the entry:

```java
var entry = new StdlibAppChainClient(ownerClient).kvEntry(key).orElseThrow().value();
byte[] owner = entry.owner();
byte[] value = entry.value();
```

The single-argument `StdlibAppChainClient` constructor checks only that the
proof matches the root in the same response. For independent verification,
pass a `TrustedRootResolver` that supplies a root from pinned finality or a
Cardano anchor.

## Configure

`registry-chain` in the stock cluster file shows a complete entry, including
the three [state-identity settings](README.md#before-you-configure-one):

```yaml
yano:
  app-chain:
    chains[1]:
      chain-id: "registry-chain"
      state-machine: kv-registry
      state:
        commitment-profile: mpf-blake2b256-v1
        format-fingerprint: 91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b
        genesis-id: cd4f0bac8a0d8c7fd5700510eae8c69256172814b219e9e3b1a914fc89c358b3
      membership:
        mode: governed
      machines:
        kv-registry:
          value-format: utf8
```

A new chain needs its own genesis id; a generated project gets one from
`appchain render`.

| `value-format` | A PUT value must be |
|---|---|
| `raw` (default) | any non-empty bytes |
| `utf8` | valid UTF-8 |
| `cbor` | one bounded, well-formed CBOR item |

The format is a consensus setting. Every member must use the same value, and
changing it on an existing chain needs a governed profile activation or a new
chain.

## Design notes

- **Stable keys give idempotency.** Repeated owner PUTs replace one current
  entry. Earlier values remain only in block history; the proof shows the
  latest one.
- **Ownership is deliberately simple.** There is no transfer, expiry,
  multi-signature update or administrator override. Delete-and-recreate lets
  the next writer take the key, so it is not a safe transfer protocol.
- **Values are application data.** `utf8` and `cbor` check structure, not a
  schema. Every member sees the bytes; encrypt confidential values before
  submitting them.

## Advanced

### Declarative binding: delete

A binding's `delete` command still uses the positional wire
`[1, keyBytes, emptyBytes]`. Its mapping needs **both** `key` and `value`; give
`value` an empty byte literal, not empty text:

```yaml
bindings:
  - id: delete-record
    from: {component: requests, event: kv-registry.entry-put.v1}
    to:
      component: records
      command: delete
      map:
        key: {field: key}
        value: {literal: {bytesHex: ''}}
```

Here `requests` and `records` are `kv-registry` component instances. The
derived command retains the source sender's authority, so that sender must own
the target entry. A non-empty value is malformed for delete; it rejects the
derived command and rolls back the cascade's business writes.

### Admission-rule views and facts

In a declarative composite, admission rules can read this machine's entries
(ADR-031.4, see [admission rules](../bindings/07-admission-rules.md)):

- **Value view** (namespace `""`, key: the entry key): `owner`, `value`,
  `valueLength`, and `valueText` when `value-format` is `utf8`. A value over
  4096 bytes exposes only `valueLength`.
- **Post-state facts**, after an approved command: `existed` (the key had an
  entry before the command) and, for a put, `valueLength`.

## Related documentation

- [Registry and proofs tutorial](../tutorials/02-registry-and-proofs.md)
- [Choose a stock state machine](../tutorials/03-stock-state-machines.md)
- [State machines](README.md)
- [Java app ledger client](../../../sdk/client/README.md)
- [Consensus guide](https://github.com/bloxbean/yano/blob/main/docs/APP_CHAIN_CONSENSUS_GUIDE.md)
