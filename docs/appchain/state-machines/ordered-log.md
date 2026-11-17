# ordered-log

`ordered-log` is the state machine built into the app-chain framework
(`framework/core`). It gives a group of members
one agreed, final order of opaque events, and a proof of where each event was
finalized. It never reads payloads and never rejects a message for business
reasons.

## At a glance

| | |
|---|---|
| Machine id | `ordered-log`, built into the framework |
| Maturity | stable |
| Commands | none: any non-empty body, on any topic that does not start with `~` |
| State | per message, `sha256("~yano/finalized-message/v1/" ‖ message id)` → `[1, height, index, topic, sender]`; plus one tip record |
| Proof subject | `finalized-message-v1`: coordinate `message-id`, claim `recorded` |
| Result codes | none |
| Events | `ordered-log.message-finalized.v1`, declared by its transition kernel for composition |

The topic is a label for routing and filtering. It does not create a separate
log; one chain can carry many topics.

## How it works

<!-- illustration: ordered-log-journey -->
1. **Submit.** Your application posts a topic and a body to one member, which
   signs the envelope and answers `202` with a message id.
2. **Order and certify.** The leader puts the message in a block, members
   re-execute it, and a threshold certifies the block.
3. **Record the position.** `ordered-log` writes the message's height, index,
   topic and sender under a key derived from its id.
4. **Find the message.** A lookup by id answers `404` until the message is
   final.
5. **Prove it.** The typed proof subject `finalized-message-v1` proves the
   record under a state root.
<!-- /illustration -->

## Try it

The `audit-log` recipe runs `ordered-log`. From the top-level directory of the
extracted release, rehearse a three-member application with one `orders`
chain, which is what `try` does by default:

```bash
./yano.sh appchain try
```

It starts alice's, bob's and carol's nodes under `./appchain-try/`. Read
their HTTP ports:

```bash
ALICE=$(jq -r .httpPort appchain-try/alice/yano-node/node.json)
BOB=$(jq -r .httpPort appchain-try/bob/yano-node/node.json)
CAROL=$(jq -r .httpPort appchain-try/carol/yano-node/node.json)
```

Submit an event through bob's node and keep the message id:

```bash
MESSAGE_ID=$(curl -sS -X POST \
  "http://127.0.0.1:$BOB/api/v1/app-chain/chains/orders/messages" \
  -H 'Content-Type: application/json' \
  -d '{"topic":"order-created","body":"{\"orderId\":\"A-1001\"}"}' \
  | jq -r .messageId)
```

Use `"bodyHex"` instead of `"body"` for binary payloads. `202` means queued,
not final. Wait until the lookup succeeds:

```bash
until curl -sf \
  "http://127.0.0.1:$ALICE/api/v1/app-chain/chains/orders/messages/$MESSAGE_ID" \
  | jq '{height, index, topic, sender}'; do sleep 1; done
```

Then request the typed proof:

```bash
curl -sS -X POST \
  "http://127.0.0.1:$ALICE/api/v1/app-chain/chains/orders/proof-subjects/finalized-message-v1/proof" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg id "$MESSAGE_ID" \
    '{coordinates:{"message-id":$id}, view:"latest",
      claim:{claimId:"recorded",operands:{}}, includeEvidence:false}')" \
  | jq '{stateRoot:.proof.stateRoot, presence:.proof.presence, claim:.claimResult.satisfied}'
```

The proof binds the record to the returned state root. For an audit, check that
root against evidence you trust, such as a Cardano anchor, rather than the node
that served it.

From Java, use `AppChainClient` from `yano-x-client`, with the HTTP port of
the node you submit through (here bob's):

```java
var client = AppChainClient.builder("http://127.0.0.1:8081/api/v1")
        .chainId("orders")
        .build();
var submitted = client.submitText("order-created", "{\"orderId\":\"A-1001\"}");
```

## Configure

In an application set up with `./yano.sh appchain new`, the `audit-log`
recipe and `appchain seal` write these settings for you. Written by hand, a
full chain entry, including the three
[state-identity settings](README.md#before-you-configure-one), looks like this:

```yaml
yano:
  app-chain:
    chains[0]:
      chain-id: "orders"
      state-machine: ordered-log
      state:
        commitment-profile: mpf-blake2b256-v1
        format-fingerprint: 91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b
        genesis-id: c2b9c92a865dfa7c218a1a6e49f1dd88163372e40466009876458c01609d0d70
      membership:
        mode: governed
      block:
        interval-ms: 1000
```

`ordered-log` has no settings of its own. Capacity, expiry, retention and
anchoring use the common chain settings.

### Operational tuning

For example:

```yaml
yano:
  app-chain:
    chains[0]:
      chain-id: orders
      state-machine: ordered-log
      max-message-bytes: 65536
      default-ttl-seconds: 600
      max-ttl-seconds: 3600
      block:
        interval-ms: 1000
        max-bytes: 4194304
        max-messages: 5000
      pool:
        max-messages: 10000
      retention:
        enabled: true
        keep-blocks: 1000
```

Keep consensus-affecting settings identical across members. When retention is
enabled, eligible old message bodies below the confirmed anchor horizon may be
stripped, while headers, ids, roots, and certificates remain so inclusion
evidence is preserved. Archive bodies or evidence bundles separately when the
original content must remain independently verifiable.

Use multiple topics in one chain when the events share the same membership,
finality, retention, anchoring, and operational lifecycle. Use separate chains
when any of those boundaries should differ: each chain has its own blocks,
pending pool, certificates, state root, membership, storage, proofs and
anchor.

## Read and follow

Besides the lookup by id and the typed proof, all relative to
`/api/v1/app-chain`:

```text
GET /chains/{chainId}/messages/by-topic/{topic}?fromHeight=0&limit=100
GET /chains/{chainId}/stream?fromHeight=0&topic={topic}
GET /chains/{chainId}/blocks?from=1&limit=100
GET /chains/{chainId}/evidence/{messageId}
GET /chains/{chainId}/status
```

The Java client follows finalized messages with `subscribe(...)` and
`subscribeTyped(...)`. The message body stays in finalized block history and
the message index; it is not duplicated in state. Besides the per-message
records, the machine keeps a tip record at
`sha256("~yano/finalized-message/v1/" ‖ "tip")` → `cbor(block-height)`. The
lower-level `state/proof/{keyHex}` route takes the resolved physical key, not
the message id; `messages/{messageId}/proof` proves membership in the block's
compact `messagesRoot`.

## Bounds

The framework, not the machine, refuses a submission when:

- the body is empty or larger than `max-message-bytes` (65,536 by default);
- the topic is longer than 256 UTF-8 bytes, contains NUL, or starts with `~`;
- the member's pending pool is full (`429`).

Submitting the same bytes twice creates two messages with two ids. Business
deduplication, schemas and transitions need another machine or a plugin.

These checks protect the protocol; they do not validate any business meaning.
Every member receives the body and finalized history may be retained or
exported, so do not put secrets or unnecessary personal data in payloads.
Encrypt bodies before submission when confidentiality is required, and manage
the keys outside consensus.

## Advanced

- **Only `ordered-log` has these records by default.** Another machine gets
  them only when its chain sets
  `machines.finalized-message-index.enabled: true`. By default every chain,
  whatever its machine, also records each block's messages root, so the state
  root changes with every block.
- **Composition.** The transition kernel emits
  `ordered-log.message-finalized.v1` with `topic`, `sender`, `messageId`,
  `height`, `index` and `bodyHash`.
- **When to choose something else.** Use [`kv-registry`](kv-registry.md) for
  current values, [`doc-trail`](doc-trail.md) for a history per entity, or a
  [plugin](../tutorials/08-plugins-and-composites.md) for business rules.
  Admission hooks give early feedback, but the deterministic `apply()` remains
  the consensus authority and must handle invalid or stale commands without
  external I/O.
- **No in-place switch.** Do not change an existing `ordered-log` ledger to
  different application semantics in place; use a fresh chain id and storage,
  or a deliberately versioned migration.
- **External actions.** `ordered-log` calls no external system. A consumer can
  follow finalized messages and do idempotent off-chain work; when the action
  and its outcome must be part of the committed workflow, use an
  effect-emitting machine and executor plugin.

## Related documentation

- [Consensus guide](../../APP_CHAIN_CONSENSUS_GUIDE.md)
- [Message submission and HTTP outcomes](../submission.md)
- [Your first app ledger](../tutorials/01-first-app-chain.md)
- [State machines](README.md)
