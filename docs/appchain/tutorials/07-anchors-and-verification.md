# Tutorial 7 — Connect an App Proof to Cardano

[Open this anchored audit log in App-Chain Studio](../../../tooling/studio/src/main/web/index.html#recipe=audit-log&network=devnet&members=3&finality=majority&sequencing=fixed&membership=governed&runtime=jvm&deployment=host&name=anchored-audit&chainId=anchored-audit&capabilities=anchor:script)

- **Level:** intermediate to advanced
- **Time:** about 20 minutes on local devnet
- **Outcome:** bootstrap a threshold-enforced script anchor, advance it after app
  blocks finalize, prove one record against the anchored root, and know which
  checks need your own Cardano source.

**Before you start:** finish [Tutorial 1](01-first-app-chain.md) and stop or
clean its cluster. You need the extracted Yano X JVM distribution, `curl`, and
`jq`. Every command runs from the distribution's top-level directory. In
Studio, the script anchor asks for reviewed validator and thread-policy
references; the local launcher uses the bundled artifacts.

An app ledger finality certificate proves that the configured member threshold
approved a block. An L1 anchor makes a later application position durable and
discoverable through Cardano. These are related but distinct proofs.

## 1. Start an anchored local cluster

```bash
export YANO_CLUSTER_DIR=/tmp/yano-tutorial-anchor

./yano.sh appchain cluster start 3 \
  --anchor-mode script \
  --anchor-every 2
```

Script mode uses a thread NFT and a Plutus V3 validator. The local launcher has
a deterministic demo anchor seed and can fund it from the self-contained
devnet faucet. When the cluster is up, the launcher's summary names the next
step, `anchor-bootstrap <chain>`.

## 2. Bootstrap one chain's immutable anchor identity

```bash
./yano.sh appchain cluster anchor-bootstrap orders-chain
```

The command funds the anchor wallet from the devnet faucet, then calls the
chain's `admin/anchor/bootstrap` endpoint and prints its JSON answer.
Bootstrap consumes a seed UTxO, mints the one-shot thread NFT, and creates the
genesis datum. It establishes identity; it does not let the wallet alone claim
an arbitrary application tip.

Inspect the L1 Anchor card on <http://127.0.0.1:7070/ui/app-chain/>. It should
show the thread policy, script address, wallet, anchored height, transaction,
and lag.

## 3. Produce application progress

Submit one event through the REST API so you keep its message id, and wait
until it is final:

```bash
API=http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain

MESSAGE_ID=$(curl -s -X POST "$API/messages" \
  -H 'Content-Type: application/json' \
  -d '{"topic":"audit","body":"{\"event\":\"certificate-issued\",\"id\":\"C-1\"}"}' \
  | jq -r .messageId)

until HEIGHT=$(curl -sf "$API/messages/$MESSAGE_ID" | jq -er .height); do sleep 1; done
echo "final at height $HEIGHT"
```

An anchor is due once `--anchor-every 2` blocks have accumulated, and blocks
exist only when there are messages. Submit a second event, then wait until a
confirmed anchor covers your message's height:

```bash
./yano.sh appchain cluster submit orders-chain audit \
  '{"event":"certificate-published","id":"C-1"}'

until curl -sf "$API/anchor/commitment" \
    | jq -e --argjson h "$HEIGHT" '.anchoredHeight >= $h' >/dev/null; do
  sleep 2
done
./yano.sh appchain cluster status
curl -s "$API/status" | jq '.anchor | {bootstrapped, lastAnchoredHeight, lastAnchorTx, lagBlocks}'
```

All members independently reconcile the authenticated script UTxO from their
own L1 view. Node-local “confirmed since restart” counters may differ after a
restart; the durable anchored height, transaction, and lag should converge.

## 4. Read the node's anchor

```bash
curl -s "$API/anchor/commitment" \
  | jq '{mode, anchoredHeight, stateRoot, transactionHash, provenance}'
```

Expected output, with your values:

```json
{
  "mode": "script",
  "anchoredHeight": <a>,
  "stateRoot": "<64 hex characters>",
  "transactionHash": "<64 hex characters>",
  "provenance": "L1-confirmed by this node"
}
```

The provenance says what this is: the node's report of what it saw on
Cardano. It is the starting point for verification, not the end of it.

## 5. Prove the record against the anchored root

Ask for a typed proof of your message at the latest confirmed anchor, and check
that its root is the anchored root:

```bash
ANCHOR_ROOT=$(curl -s "$API/anchor/commitment" | jq -r .stateRoot)

curl -s -X POST "$API/proof-subjects/finalized-message-v1/proof" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg id "$MESSAGE_ID" '
    {coordinates:{"message-id":$id}, view:"latest-confirmed-anchor",
     claim:{claimId:"recorded",operands:{}}, includeEvidence:false}')" \
  | jq --arg root "$ANCHOR_ROOT" '{trust, height: .proof.version,
      presence: .proof.presence, recorded: .claimResult.satisfied,
      sameRootAsAnchor: (.proof.stateRoot == $root)}'
```

Expected output:

```json
{
  "trust": "NODE_CONFIRMED_L1_REFERENCE",
  "height": <a>,
  "presence": "PRESENT",
  "recorded": true,
  "sameRootAsAnchor": true
}
```

If `presence` is `ABSENT`, the anchor you read predates your message: wait for
the next anchor and ask again. The proof response also carries the block's
finality certificate, which you verify in step 7.

## 6. Export portable proof material

Save the material a verifier needs, so it can be checked later without this
cluster:

```bash
curl -s "$API/messages/$MESSAGE_ID/proof-package" > message-proof-package.json
curl -s "$API/evidence/$MESSAGE_ID" > evidence-bundle.json
jq 'keys' message-proof-package.json
```

The proof package (`appchain-message-proof-v1`) holds the message's inclusion
path, the block-message-root and state-record proofs, the evidence bundle, and
the anchor reference the node used. Its embedded verification results are
explanatory only. The evidence bundle holds the message's block, every block up
to the anchored one, and their finality signatures; verify it with
`EvidenceVerifier.verify(bundle, trustContext)` from `yano-core-api`, with a
trust context you pinned yourself.

## 7. The independent verification chain

Everything so far came from one node. Seven checks remove that trust. Step
through them, then see how a forged root or an old anchor shows up:

<!-- illustration: verify-ladder -->
1. **Recompute the record.** Recompute the record or message commitment from
   your message id.
2. **Check the path.** Verify its MPF or messages-root path against the correct
   app-block root.
3. **Check finality.** Verify the app block's threshold signatures using an
   independently trusted chain profile: genesis, members, threshold, and the
   consensus-context digest.
4. **Follow the hash chain.** When the record predates the anchor, verify the
   certified block-hash chain to the anchored descendant.
5. **Fetch from Cardano.** Fetch the Cardano transaction and UTxO from an
   independent source.
6. **Check the anchor identity.** Require the expected validator address and
   state-thread asset.
7. **Match the datum.** Decode the exact inline datum and match chain id,
   genesis id, application id, commitment profile, format fingerprint, height,
   block hash, state root, member set, and threshold.
<!-- /illustration -->

A transaction hash appearing in a Yano JSON response is not, by itself,
independent anchor verification. From Java, use `ProofVerifier.verifyCertified`
for steps 1–3 and `EvidenceVerifier` for the hash chain, rather than trusting
server booleans.

## Metadata versus script anchoring

| Property | Metadata | Script |
|---|---|---|
| Setup | Fund wallet | Fund wallet + bootstrap |
| L1 enforcement | Data commitment only | Monotonic thread + threshold member signatures |
| Main safety authority | Anchor wallet | On-chain validator and member threshold |
| Typical use | Low-cost timestamp/discovery | Strong consortium settlement boundary |

Both modes commit application data; only script mode enforces the threshold
and monotonic successor rules on chain. Neither checks that a root is correct:
in script mode, the members who co-sign do.

## Public test networks

For preview or preprod:

- generate a dedicated raw 32-byte anchor seed;
- provide it through the owner-only anchor-key file mechanism;
- fund the printed enterprise address with test ADA;
- use an explicit public-network confirmation before the demo spends; and
- increase cadence/stability settings to match real L1 timing and fees.

Do not reuse a wallet mnemonic, validator member seed, actor signing key, or
API key as the anchor wallet.

## 8. Clean up

```bash
./yano.sh appchain cluster clean
unset YANO_CLUSTER_DIR
```

Local devnet state is disposable. A public script anchor is permanent Cardano
history even after local files are deleted.

## Go deeper

- Follow [L1 anchoring §5](../../APP_CHAIN_USER_GUIDE.md) for portable evidence
  bundles and exact trust-context construction.
- Read [Cardano anchoring](../../site/concepts-anchoring.md) to step through how members
  and the validator check each advance.
- Test L1 rollback and anchor resubmission before a pilot.
- Review the pinned Aiken release artifacts and the Java/julc cross-implementation
  drift checks before depending on a released script identity.

Next: [Tutorial 8 — plugins and composites](08-plugins-and-composites.md).
