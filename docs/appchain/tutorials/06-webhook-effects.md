# Tutorial 6 — Invoke an External HTTP Endpoint Safely

To run this outcome with other operators, follow [Set up an application](../deployment/configure.md).

- **Level:** intermediate to advanced
- **Time:** about 20 minutes
- **Outcome:** understand when to use a finalized-block webhook sink versus an
  acknowledged `webhook.post` effect, and run the latter from a stock approval
  transition against a local receiver.

**Before you start:** finish [Tutorial 1](01-first-app-chain.md) and remove
its rehearsal with `./yano.sh appchain try --clean`. You need the extracted Yano X JVM distribution, Python 3,
`curl`, and `jq`. Every command runs from the distribution's top-level
directory, the one that contains `yano.sh`; the tutorial files ship under
`docs/` there. In Studio, add the `effects:on-approved` and `executor:webhook`
capabilities; Studio then asks for the effect type and the webhook URL, which
the exercise sets to `webhook.post` and `http://127.0.0.1:8099/yano`.

Yano provides two HTTP delivery shapes with different guarantees.

| Capability | Use it for | Result committed to app state? |
|---|---|---:|
| Finalized-block webhook sink | Projection/indexing of every finalized block | No |
| `webhook.post` effect | One business action after a deterministic transition | Yes, for `CHAIN` results |

The snippets in Options A and B use the single-chain form,
`yano.app-chain.<key>`. A node set up with `./yano.sh appchain join` addresses
each chain by index, so the same keys go under
`yano.app-chain.chains[<i>].<key>`; the exercise below uses `chains[0]`.

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

### 2. Rehearse an approvals chain that emits the effect

Write a small template with one chain. It uses the `approval-workflow` recipe
(the stock `approvals` machine) and adds the `effects:on-approved` capability,
whose `effectType` answer names the effect it emits:

```bash
cat > webhook.template.yaml <<'EOF'
chains:
  - id: approvals
    recipe: approval-workflow
    capabilities: [effects:on-approved]
    answers: {effectType: webhook.post}
EOF
./yano.sh appchain try --from webhook.template.yaml
```

`seal` fixes the same settings for every member. The capability turns on the
effects runtime and seals these chain settings:

```properties
effects.enabled=true
machines.approvals.on-approved-effect.enabled=true
machines.approvals.on-approved-effect.type=webhook.post
machines.approvals.on-approved-effect.gate=chain-default
machines.approvals.activations.on-approved-effect=1
```

These settings attach one generic action to the stock approval transition.
`webhook.post` is only the executor routing type; the approvals machine does
not interpret the payload as a payment or webhook. The approval decision stays
`APPROVED`, while the separate `ae/s/<itemId>` record tracks the effect as
`PENDING`, `CONFIRMED`, or `FAILED`. Change `effectType` to another packaged
or custom executor contract without changing approval semantics. The effect
uses a `CHAIN` result.

### 3. Run the executor on alice's node only

Which node executes an effect, and where it sends it, are node-local
settings. They go in that node's `config/local.yaml`, which the setup tool
never rewrites. Alice's node runs the executor; bob's and carol's do not.

`join` gives each chain an index. Check the chain's index on alice's node:

```bash
cat appchain-try/alice/yano-node/apps/chain-indexes.json
```

With this one chain it is `0`. Append the executor settings for `chains[0]`
to alice's local file:

```bash
cat >> appchain-try/alice/yano-node/config/local.yaml <<'EOF'
yano:
  app-chain:
    "chains[0]":
      effects:
        executor:
          enabled: true
          types: webhook.post
        executors:
          webhook:
            url: http://127.0.0.1:8099/yano
            timeout-ms: 5000
EOF
```

These keys are node-local; `start` refuses a local file that changes a sealed
setting. Restart alice's node so it reads the file:

```bash
./yano.sh appchain stop --node-dir appchain-try/alice/yano-node
./yano.sh appchain start --node-dir appchain-try/alice/yano-node
```

> **✓ You should see** `✓ Stopped alice; its data is kept`, then
> `✓ Node alice is up at http://127.0.0.1:…`.

To run the executor on every member instead, add the `executor:webhook`
capability to the chain with a `webhookUrl` answer. Every member then
executes, so the receiver must deduplicate.

Read each node's HTTP port from its `node.json`:

```bash
ALICE=$(jq -r .httpPort appchain-try/alice/yano-node/node.json)
BOB=$(jq -r .httpPort appchain-try/bob/yano-node/node.json)
CAROL=$(jq -r .httpPort appchain-try/carol/yano-node/node.json)
```

### 4. Encode and submit a proposal

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

Propose through alice's node, then approve through bob's and carol's.
Proposing is not approving: the item needs two approvals from distinct
members.

```bash
API=api/v1/app-chain/chains/approvals/messages

curl -s -X POST "http://127.0.0.1:$ALICE/$API" \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"approvals\",\"bodyHex\":\"$PROPOSE_HEX\"}" | jq .

curl -s -X POST "http://127.0.0.1:$BOB/$API" \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"approvals\",\"bodyHex\":\"$APPROVE_HEX\"}" | jq .

curl -s -X POST "http://127.0.0.1:$CAROL/$API" \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"approvals\",\"bodyHex\":\"$APPROVE_HEX\"}" | jq .
```

Each call answers `202 Accepted` with a message id:

```json
{
  "messageId": "<64 hex characters>",
  "chainId": "approvals",
  "topic": "approvals"
}
```

### 5. Watch the delivery

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
STATUS=http://127.0.0.1:$ALICE/api/v1/app-chain/chains/approvals/status
until curl -s "$STATUS" | jq -e '.effects.executor.executionTotals.confirmed >= 1
    and .effects.executor.openOnChain == 0' >/dev/null; do
  sleep 1
done

curl -s \
  "http://127.0.0.1:$ALICE/api/v1/app-chain/chains/approvals/effects?fromHeight=1&limit=20" \
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

### 6. Clean up

```bash
./yano.sh appchain try --clean
rm webhook.template.yaml
```

`try --clean` stops the nodes and deletes the rehearsal, including alice's
`local.yaml`. Stop the receiver with `Ctrl-C`.

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

- Read [Effects §17](../../APP_CHAIN_USER_GUIDE.md) for quarantine, requeue,
  cancellation, proof, and external executor APIs.
- Kill the receiver temporarily, observe retries and parking, restore it, and
  use the operator requeue endpoint.
- Build a custom executor plugin with named target aliases and secret-backed
  authentication rather than putting URLs or credentials in replicated
  payloads.

Next: [Tutorial 7 — anchors and verification](07-anchors-and-verification.md).
