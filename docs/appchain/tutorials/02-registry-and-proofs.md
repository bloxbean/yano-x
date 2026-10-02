# Tutorial 2 — A Provable Shared Registry

[Open this outcome in App-Chain Studio](../../../tooling/studio/src/main/web/index.html#recipe=owned-registry&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=host&name=shared-registry&chainId=shared-registry)

- **Level:** beginner to intermediate
- **Time:** about 15 minutes
- **Outcome:** write owner-controlled data, show that an unauthorized write is
  a final no-op, and prove the current value with an MPF proof.

The default cluster hosts `registry-chain`, which runs the stock
[`kv-registry`](../state-machines/kv-registry.md) state machine. The first
member to write a key owns it; only that member can update or delete it.

You need `curl`, `jq` and `python3`. Run every command from the top-level
directory of the extracted release, the directory that contains `yano.sh`.

<!-- illustration: registry-proofs -->
1. **Write as node 1.** The launcher writes `supplier-42` through node 1, which
   becomes its owner.
2. **Wait for the proof.** Poll the proof endpoint until the key is present.
3. **Write as node 2.** Node 2 writes the same key. The message is final, but
   the decision is `KV_NOT_OWNER`.
4. **Compare.** The proven entry is unchanged; the state root has moved,
   because every block changes it.
5. **Update as owner.** Node 1 writes a new value, and the proof shows it.
<!-- /illustration -->

## 1. Start the default cluster

Skip this section if Tutorial 1's cluster is still running.

```bash
export YANO_CLUSTER_DIR=/tmp/yano-tutorial-registry
./yano.sh appchain cluster start 3
```

## 2. Create a registry entry

Write through node 1, so node 1 becomes the owner:

```bash
./yano.sh appchain cluster kv registry-chain set supplier-42 active --node 1
```

The command body is CBOR, but the launcher builds it for you. `202` means only
that node 1 queued it. Wait until the key appears in a proof:

```bash
BASE=http://127.0.0.1:7070/api/v1/app-chain/chains/registry-chain
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
followed by node 1's 32-byte member key, and `46616374697665` is the 6-byte
value `active`. The response also carries `proofWireHex`, an MPF proof that
this value sits under `stateRoot`. Showing JSON from one node is not
independent verification; a verifier checks the proof against a root it
trusts.

## 4. Try an unauthorized write

Record the proven entry and the root, then write the same key through node 2
over REST, so you can wait for exactly that message:

```bash
ENTRY_BEFORE=$(curl -s "$BASE/state/proof/$KEY_HEX" | jq -r .valueHex)
ROOT_BEFORE=$(curl -s "$BASE/state/proof/$KEY_HEX" | jq -r .stateRoot)

TOOL=docs/appchain/tutorials/tools/stdlib_command.py
PUT_HEX=$(python3 "$TOOL" kv-registry put supplier-42 --value-text suspended)

MESSAGE_ID=$(curl -sS -X POST \
  http://127.0.0.1:7072/api/v1/app-chain/chains/registry-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"kv-registry.command.v1\",\"bodyHex\":\"$PUT_HEX\"}" | jq -r .messageId)

until curl -sf "$BASE/messages/$MESSAGE_ID" >/dev/null; do sleep 1; done
curl -s "$BASE/messages/$MESSAGE_ID" | jq '{height, sender}'
```

The message is final at the printed height, sent by node 2. Now compare:

```bash
ENTRY_AFTER=$(curl -s "$BASE/state/proof/$KEY_HEX" | jq -r .valueHex)
ROOT_AFTER=$(curl -s "$BASE/state/proof/$KEY_HEX" | jq -r .stateRoot)

[ "$ENTRY_BEFORE" = "$ENTRY_AFTER" ] && echo "entry unchanged"
[ "$ROOT_BEFORE" != "$ROOT_AFTER" ] && echo "state root changed"
```

You should see both lines. The entry did not change: node 2 does not own the
key, so the decision was `KV_NOT_OWNER`. The root did change, and it would
have changed anyway: every block also records its own messages root in state.
So compare the proven entry, never the root, to decide whether a command had
an effect. A final message, a `202`, or a growing height is not the
authorization decision; the resulting state is.

## 5. Update as the owner

```bash
./yano.sh appchain cluster kv registry-chain set supplier-42 suspended --node 1

until [ "$(curl -s "$BASE/state/proof/$KEY_HEX" | jq -r .valueHex)" != "$ENTRY_AFTER" ]; do
  sleep 1
done
curl -s "$BASE/state/proof/$KEY_HEX" | jq '{committedHeight, stateRoot, valueHex}'
```

The value now ends in `73757370656e646564`, `suspended`. The owner key is the
same.

## 6. Clean up

```bash
./yano.sh appchain cluster clean
unset YANO_CLUSTER_DIR
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
