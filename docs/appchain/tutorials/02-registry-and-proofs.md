# Tutorial 2 — A Provable Shared Registry

To run this outcome with other operators, follow [Set up an application](../deployment/configure.md).

- **Level:** beginner to intermediate
- **Time:** about 15 minutes
- **Outcome:** write owner-controlled data, show that an unauthorized write is
  a final no-op, and prove the current value with an MPF proof.

This tutorial rehearses an application with one chain, `registry`, which runs
the stock [`kv-registry`](../state-machines/kv-registry.md) state machine
through the `owned-registry` recipe. The first member to write a key owns it;
only that member can update or delete it.

You need `curl`, `jq` and `python3`. Run every command from the top-level
directory of the extracted release, the directory that contains `yano.sh`.

<!-- illustration: registry-proofs -->
1. **Write as bob.** You write `supplier-42` through bob's node, so bob
   becomes its owner.
2. **Wait for the proof.** Poll the proof endpoint until the key is present.
3. **Write as carol.** Carol's node writes the same key. The message is final,
   but the decision is `KV_NOT_OWNER`.
4. **Compare.** The proven entry is unchanged; the state root has moved,
   because every block changes it.
5. **Update as owner.** Bob's node writes a new value, and the proof shows it.
<!-- /illustration -->

## 1. Start a registry rehearsal

If Tutorial 1's rehearsal still runs, remove it first with
`./yano.sh appchain try --clean`. Then rehearse an application whose one chain
uses the `owned-registry` recipe:

```bash
./yano.sh appchain try --chain registry=owned-registry
```

> **✓ You should see** the numbered steps end with `members online 3/3` and
> `chain registry … members agree`.

## 2. Create a registry entry

Each node's HTTP port is in its `node.json`. Read bob's and carol's ports into
your shell:

```bash
BOB=$(jq -r .httpPort appchain-try/bob/yano-node/node.json)
CAROL=$(jq -r .httpPort appchain-try/carol/yano-node/node.json)
```

The command body is CBOR. The tutorial helper builds it for you. Write through
bob's node, so bob becomes the owner:

```bash
TOOL=docs/appchain/tutorials/tools/stdlib_command.py
PUT_HEX=$(python3 "$TOOL" kv-registry put supplier-42 --value-text active)

curl -sS -X POST "http://127.0.0.1:$BOB/api/v1/app-chain/chains/registry/messages" \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"kv-registry.command.v1\",\"bodyHex\":\"$PUT_HEX\"}" | jq .
```

> **✓ You should see** a JSON object with a `messageId`.

The message ID is bob's node's acceptance: the REST API answered HTTP `202`
and returned that ID. It means only that bob's node queued the message.
Wait until the key appears in a proof:

```bash
BASE=http://127.0.0.1:$BOB/api/v1/app-chain/chains/registry
KEY_HEX=$(python3 -c 'print("supplier-42".encode().hex())')

until curl -sf "$BASE/state/proof/$KEY_HEX" | jq -e '.presence == "PRESENT"' >/dev/null; do
  sleep 1
done
```

## 3. Prove the current value

The state key is the UTF-8 business key itself:

```bash
curl -s "$BASE/state/proof/$KEY_HEX" | jq '{committedHeight, stateRoot, presence, valueHex}'
```

`valueHex` is CBOR `[owner, value]`: `82` opens an array of two, `5820` is
followed by bob's 32-byte member key, and `46616374697665` is the 6-byte
value `active`. The response also carries `proofWireHex`, an MPF proof that
this value sits under `stateRoot`. Showing JSON from one node is not
independent verification; a verifier checks the proof against a root it
trusts.

## 4. Try an unauthorized write

Record the proven entry and the root, then write the same key through carol's
node, so you can wait for exactly that message:

```bash
ENTRY_BEFORE=$(curl -s "$BASE/state/proof/$KEY_HEX" | jq -r .valueHex)
ROOT_BEFORE=$(curl -s "$BASE/state/proof/$KEY_HEX" | jq -r .stateRoot)

PUT_HEX=$(python3 "$TOOL" kv-registry put supplier-42 --value-text suspended)

MESSAGE_ID=$(curl -sS -X POST \
  "http://127.0.0.1:$CAROL/api/v1/app-chain/chains/registry/messages" \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"kv-registry.command.v1\",\"bodyHex\":\"$PUT_HEX\"}" | jq -r .messageId)

until curl -sf "$BASE/messages/$MESSAGE_ID" >/dev/null; do sleep 1; done
curl -s "$BASE/messages/$MESSAGE_ID" | jq '{height, sender}'
```

The message is final at the printed height, sent by carol's node. Now compare:

```bash
ENTRY_AFTER=$(curl -s "$BASE/state/proof/$KEY_HEX" | jq -r .valueHex)
ROOT_AFTER=$(curl -s "$BASE/state/proof/$KEY_HEX" | jq -r .stateRoot)

[ "$ENTRY_BEFORE" = "$ENTRY_AFTER" ] && echo "entry unchanged"
[ "$ROOT_BEFORE" != "$ROOT_AFTER" ] && echo "state root changed"
```

You should see both lines. The entry did not change: carol does not own the
key, so the decision was `KV_NOT_OWNER`. The root did change, and it would
have changed anyway: every block also records its own messages root in state.
So compare the proven entry, never the root, to decide whether a command had
an effect. A final message, an accepted (HTTP `202`) submission, or a growing height is not the
authorization decision; the resulting state is.

## 5. Update as the owner

Send the same `suspended` command, this time through bob's node:

```bash
curl -sS -X POST "$BASE/messages" \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"kv-registry.command.v1\",\"bodyHex\":\"$PUT_HEX\"}" | jq .

until [ "$(curl -s "$BASE/state/proof/$KEY_HEX" | jq -r .valueHex)" != "$ENTRY_AFTER" ]; do
  sleep 1
done
curl -s "$BASE/state/proof/$KEY_HEX" | jq '{committedHeight, stateRoot, valueHex}'
```

The value now ends in `73757370656e646564`, `suspended`. The owner key is the
same.

## 6. Clean up

```bash
./yano.sh appchain try --clean
```

## Where this pattern fits

- allow-lists shared by several organizations;
- product, asset, credential or DID-document registries;
- shared configuration with explicit ownership;
- a current pointer whose exact value must be proved to a third party.

It does not provide organization roles, multi-party governance or document
indexing. Use [`authenticated-map`](../state-machines/authenticated-map.md),
the role workflow, or a plugin when the authorization is richer than “first
writer owns this key”.

## Go deeper

- The [`kv-registry` reference](../state-machines/kv-registry.md) covers
  configuration, typed proofs, Java encoding and design choices.
- Verify `proofWireHex` with the Java client and a trusted root instead of
  trusting the serving node; [Tutorial 7](07-anchors-and-verification.md)
  connects a root to a Cardano anchor.
- Continue with [choosing a stock state machine](03-stock-state-machines.md).
