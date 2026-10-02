# Tutorial 6 — Invoke an External HTTP Endpoint Safely

[Open this approval-to-webhook workflow in App-Chain Studio](../../../tooling/studio/src/main/web/index.html#recipe=approval-workflow&network=devnet&members=3&finality=majority&sequencing=fixed&membership=governed&runtime=jvm&deployment=host&name=webhook-effects&chainId=webhook-effects&capabilities=effects:on-approved,executor:webhook)

- **Level:** intermediate to advanced
- **Time:** about 20 minutes
- **Outcome:** understand when to use a finalized-block webhook sink versus an
  acknowledged `webhook.post` effect, and run the latter from a stock approval
  transition against a local receiver.

**Before you start:** finish [Tutorial 1](01-first-app-chain.md) and stop or
clean its cluster. You need the extracted Yano X JVM distribution, Python 3,
`curl`, and `jq`. Every command runs from the distribution's top-level
directory, the one that contains `yano.sh`; the tutorial files ship under
`docs/` there. Studio asks for the effect type and the webhook URL, which the
exercise sets to `webhook.post` and `http://127.0.0.1:8099/yano`.

Yano provides two HTTP delivery shapes with different guarantees.

| Capability | Use it for | Result committed to app state? |
|---|---|---:|
| Finalized-block webhook sink | Projection/indexing of every finalized block | No |
| `webhook.post` effect | One business action after a deterministic transition | Yes, for `CHAIN` results |

The snippets in Options A and B use the single-chain form,
`yano.app-chain.<key>`. On a node with several chains, such as the local
cluster, the same keys go under `yano.app-chain.chains[<i>].<key>`; the
exercise below uses `chains[0]`.

## Option A — observe every finalized block

Configure a cursor-backed sink:

```yaml
yano.app-chain.webhooks: https://projection.example/yano/finalized-blocks
```

Each finalized block is POSTed as JSON, in height order, with
`X-App-Chain-Id` and `X-App-Chain-Height` headers. Delivery is at-least-once,
and a persistent sink cursor resumes after restart; a failing sink halts and
retries rather than skipping a block. This is the simplest option when the
receiver wants all finalized history and does not need a per-action result fed
back into the state machine.

## Option B — execute one acknowledged action

The built-in executor supports `webhook.post`. The target URL lives in the
executor's configuration, not in the replicated payload:

```yaml
yano.app-chain.effects.executors.webhook.url: https://erp.example/hooks/yano
yano.app-chain.effects.executors.webhook.timeout-ms: 10000
```

It sends:

- `Idempotency-Key`: the hex hash of the effect id, identical on every attempt;
- `X-App-Chain-Id`; and
- `X-Effect-Id`, `X-Effect-Type`, and `X-Effect-Scope`.

Response semantics are:

- `2xx` → confirmed, with the `Location` header as the external reference when
  present;
- `4xx` → failed without automatic retry; and
- anything else, including `5xx` and transport failures → retried with bounded
  backoff, then parked for an operator.

The receiver must deduplicate by `Idempotency-Key`, because execution is
at-least-once even though outcome incorporation is exactly once. Step through
the whole path, including a crash after the POST:

<!-- illustration: effect-lifecycle -->
1. **Emit.** The state machine emits an effect record during `apply()`. Nothing
   is sent yet.
2. **Finalize.** The block becomes final, and the effect is committed in the
   state root.
3. **Pick up.** The effect runtime on the executor node reads the finalized
   record and checks its gate.
4. **Deliver.** The executor POSTs the payload with the `Idempotency-Key` header.
5. **Answer.** Your receiver answers 2xx, 4xx, or 5xx, which decides confirmed,
   failed, or retried.
6. **Report.** The executor node submits a member-signed `~fx/result` message.
7. **Incorporate.** The first valid result closes the effect, and every member
   records it.
<!-- /illustration -->

[Effects](../../site/concepts-effects.md) explains result policies, expiry, and executors in
more depth.

## Runnable local approval-to-webhook exercise

The stock `approvals` machine can emit a configured effect when a proposal
reaches its threshold. This avoids writing a custom state machine for the
tutorial.

### 1. Start a receiver in a separate terminal

```bash
python3 - <<'PY'
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

seen = set()

class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        key = self.headers.get("Idempotency-Key", "")
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        duplicate = key in seen
        seen.add(key)
        print({"idempotencyKey": key, "duplicate": duplicate,
               "contentType": self.headers.get("Content-Type"),
               "body": body.decode("utf-8", errors="replace")}, flush=True)
        self.send_response(200)
        self.send_header("Location", f"local://receipt/{key}")
        self.end_headers()

    def log_message(self, *_):
        pass

ThreadingHTTPServer(("127.0.0.1", 8099), Receiver).serve_forever()
PY
```

It prints nothing until the first POST arrives.

### 2. Create private per-node overrides

Start from a clean tutorial cluster and create three owner-only property files.
All members receive the same consensus settings; only node 0 runs the
executor. Each file must begin with `config_ordinal=275`.

The local cluster launcher loads these files as per-node overlays when the
`YANO_CLUSTER_NODE_CONFIG_DIR` environment variable names their directory.
They are not generated-project configuration: generated app ledger projects and
`appchain config` use YAML.

Common settings for `node0.properties`, `node1.properties`, and
`node2.properties`:

```properties
config_ordinal=275
yano.app-chain.chains[0].state-machine=approvals
yano.app-chain.chains[0].effects.enabled=true
yano.app-chain.chains[0].machines.approvals.on-approved-effect.enabled=true
yano.app-chain.chains[0].machines.approvals.activations.on-approved-effect=1
yano.app-chain.chains[0].machines.approvals.on-approved-effect.type=webhook.post
yano.app-chain.chains[0].machines.approvals.on-approved-effect.gate=app-final
```

These settings attach one generic action to the stock approval transition.
`webhook.post` is only the executor routing type; the approvals machine does
not interpret the payload as a payment or webhook. The approval decision stays
`APPROVED`, while the separate `ae/s/<itemId>` record tracks the effect as
`PENDING`, `CONFIRMED`, or `FAILED`. Change `type` to another packaged or custom
executor contract without changing approval semantics. The effect uses a
`CHAIN` result, and with no `expiry-blocks` setting its expiry is the chain's
default.

Append these node-local settings only to `node0.properties`:

```properties
yano.app-chain.chains[0].effects.executor.enabled=true
yano.app-chain.chains[0].effects.executor.types=webhook.post
yano.app-chain.chains[0].effects.executors.webhook.url=http://127.0.0.1:8099/yano
yano.app-chain.chains[0].effects.executors.webhook.timeout-ms=5000
```

The distribution includes those exact files under
`docs/appchain/tutorials/config/webhook/`. Install owner-only copies and start
a fresh cluster:

```bash
install -d -m 700 /tmp/yano-tutorial-webhook-config
install -m 600 \
  docs/appchain/tutorials/config/webhook/node*.properties \
  /tmp/yano-tutorial-webhook-config/

export YANO_CLUSTER_DIR=/tmp/yano-tutorial-webhook
export YANO_CLUSTER_NODE_CONFIG_DIR=/tmp/yano-tutorial-webhook-config
./yano.sh appchain cluster start 3
```

The chain id remains `orders-chain`, but its fresh deterministic profile is
now `approvals`. Never apply this override to retained `ordered-log` state.

### 3. Encode and submit a proposal

Encode the supplied HTTP body and the canonical stock commands with the
tutorial helper:

```bash
TOOL=docs/appchain/tutorials/tools/stdlib_command.py
WEBHOOK_HEX=$(python3 "$TOOL" webhook \
  --body-file docs/appchain/tutorials/config/webhook/request.json \
  --content-type application/json)

PROPOSE_HEX=$(python3 "$TOOL" approvals propose erp-release-001 \
  --required 2 --payload-hex "$WEBHOOK_HEX")
APPROVE_HEX=$(python3 "$TOOL" approvals approve erp-release-001)
```

Propose through member 0, then approve through members 1 and 2. Proposing is
not approving: the item needs two approvals from distinct members.

```bash
API=api/v1/app-chain/chains/orders-chain/messages

curl -s -X POST "http://127.0.0.1:7070/$API" \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"approvals\",\"bodyHex\":\"$PROPOSE_HEX\"}" | jq .

curl -s -X POST "http://127.0.0.1:7071/$API" \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"approvals\",\"bodyHex\":\"$APPROVE_HEX\"}" | jq .

curl -s -X POST "http://127.0.0.1:7072/$API" \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"approvals\",\"bodyHex\":\"$APPROVE_HEX\"}" | jq .
```

Each call answers `202 Accepted` with a message id:

```json
{
  "messageId": "<64 hex characters>",
  "chainId": "orders-chain",
  "topic": "approvals"
}
```

### 4. Watch the delivery

After finality and the executor tick, the receiver terminal prints one line:

```text
{'idempotencyKey': '<64 hex characters>', 'duplicate': False, 'contentType': 'application/json', 'body': '{"action":"release-order","orderId":"A-1001"}\n'}
```

A crash at the acknowledgement boundary may produce another physical POST with
the same idempotency key, printed with `'duplicate': True`. That is why the
receiver keeps a deduplication set.

Wait until the result is incorporated, then inspect the effect record and the
executor status:

```bash
STATUS=http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/status
until curl -s "$STATUS" | jq -e '.effects.executor.executionTotals.confirmed >= 1
    and .effects.executor.openOnChain == 0' >/dev/null; do
  sleep 1
done

curl -s \
  'http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/effects?fromHeight=1&limit=20' \
  | jq '.effects[] | {height, ordinal, type, scope}'

curl -s "$STATUS" \
  | jq '.effects.executor | {executed, openOnChain, confirmed: .executionTotals.confirmed}'
```

Expected output, with your height:

```json
{
  "height": <h>,
  "ordinal": 0,
  "type": "webhook.post",
  "scope": "approvals/on-approved/erp-release-001"
}
{
  "executed": 1,
  "openOnChain": 0,
  "confirmed": 1
}
```

One confirmed execution and zero open on-chain effects mean the `~fx/result`
message has been incorporated. The approval item is still `APPROVED`;
confirmation updates only its independently provable generic effect-state
record.

### 5. Clean up

```bash
./yano.sh appchain cluster clean
unset YANO_CLUSTER_DIR YANO_CLUSTER_NODE_CONFIG_DIR
rm -rf /tmp/yano-tutorial-webhook-config
```

Stop the receiver with `Ctrl-C`. Remove the private override directory after
the cluster is stopped.

## Security and product boundary

`webhook.post` is intentionally small: one POST to a configured endpoint. It
does not yet provide named target aliases, OAuth/API-key/mTLS profiles,
arbitrary methods, response-body contracts, or asynchronous operation polling.
Keep `allow-payload-url=false` unless a separately reviewed allow-list and SSRF
boundary exists.

For a product API such as uVerify, prefer a dedicated executor when durable
acceptance requires several API calls, polling, reconciliation, or a typed
receipt. Use the generic webhook only when one idempotent POST and its immediate
status are the real business contract.

## Go deeper

- Read [Effects §18](../../APP_CHAIN_USER_GUIDE.md) for quarantine, requeue,
  cancellation, proof, and external executor APIs.
- Kill the receiver temporarily, observe retries and parking, restore it, and
  use the operator requeue endpoint.
- Build a custom executor plugin with named target aliases and secret-backed
  authentication rather than putting URLs or credentials in replicated
  payloads.

Next: [Tutorial 7 — anchors and verification](07-anchors-and-verification.md).
