# approvals

`approvals` is a `k`-of-`n` decision among member nodes. One member proposes
an item, distinct members approve it, and the item ends APPROVED, REJECTED or
EXPIRED. Every item can be proved against the state root, and an approved item
can trigger one effect.

The machine id is exactly `approvals`. Item ids and topics are chosen by the
application; they do not create new machine types.

## At a glance

| | |
|---|---|
| Machine id | `approvals` |
| Maturity | stable |
| Commands | `[0, itemId, payload, required, deadlineMillis]` PROPOSE; `[1, itemId]` APPROVE; `[2, itemId]` REJECT |
| Settings | optional on-approved effect under `machines.approvals.on-approved-effect.*` |
| State | `i/<itemId>` → `[status, proposer, payloadHash, required, deadline, approvers, rejecter]`; with the effect, `ae/p/<itemId>` and `ae/s/<itemId>` |
| Statuses | `0` PENDING, `1` APPROVED, `2` REJECTED, `3` EXPIRED |
| Proof subject | `basic-approval-outcome-v1`: coordinate `proposal-id`; claims `status`, `quorum-reached`, `payload-digest` |
| Result codes | none: a command that breaks a rule is a final no-op |
| Events | `approvals.item-proposed.v1`, `approvals.item-approved.v1`, `approvals.item-rejected.v1`, in composites only |

## How it works

<!-- illustration: approvals-lifecycle -->
1. **Propose.** A member creates a PENDING item. The proposer is not counted as
   an approver.
2. **Approve.** A second member approves: 1 of 2.
3. **Approve again.** The same member approves again; a member counts once, so
   nothing changes.
4. **Reach the threshold.** A third member approves: the item is APPROVED and,
   with the on-approved effect, one effect is emitted.
5. **Reject too late.** A rejection of a terminal item is a no-op.
6. **Record the result.** The effect's record becomes CONFIRMED; the item stays
   APPROVED.
<!-- /illustration -->

The rules, in the order the machine applies them:

1. PROPOSE creates a PENDING item if the id is new. A later PROPOSE for the same
   id is a no-op, even with another payload. `required` must be positive.
2. APPROVE or REJECT of an unknown id, or of an item that is not PENDING, is a
   no-op.
3. If the item has a deadline (`deadlineMillis` greater than 0) and the block
   time is after it, the command marks the item EXPIRED instead. This applies
   to REJECT too.
4. APPROVE from a member that already approved is a no-op. Otherwise the member
   is added; when the count reaches `required` the item is APPROVED.
5. REJECT from any member marks the item REJECTED.

Deadlines are Unix milliseconds compared with the finalized block timestamp.
Time passing writes nothing: an item stays PENDING in state until a command
touches it after its deadline.

The sender of the envelope is the approver. Through the local cluster, ports
7070, 7071 and 7072 are three different members.

## When to use it

Use `approvals` when member keys really are the people, services or
organizations that decide:

- release and deployment gates;
- consortium or treasury authorization;
- cross-organization sign-off, or review before an external action.

Use [`role-approvals`](role-approvals.md) when approvers are business actors
with roles and organizations, separate from the member nodes. A REST API key
controls HTTP access; it is not an approval identity.

## Try it

The stock local cluster hosts `effects-chain`, which runs `approvals` with a
demonstration effect. From the top-level directory of the extracted release:

```bash
./yano.sh appchain cluster start 3
./yano.sh appchain cluster effect demo "release 2026-07 approved"
```

The demo proposes and approves a one-approval item, emits a `demo.webhook`
effect, plays the external worker, reports success, and checks the effect
proof. It calls no real webhook.

## Submit through REST

Encode the commands with the tutorial helper:

```bash
TOOL=docs/appchain/tutorials/tools/stdlib_command.py
ITEM=release-2026-07

PROPOSE_HEX=$(python3 "$TOOL" approvals propose "$ITEM" --required 2 \
  --payload-text '{"artifact":"inventory-service:2.4.0"}')
APPROVE_HEX=$(python3 "$TOOL" approvals approve "$ITEM")
REJECT_HEX=$(python3 "$TOOL" approvals reject "$ITEM")
```

Propose through node 0, then approve through two other members:

```bash
curl -sS -X POST http://127.0.0.1:7070/api/v1/app-chain/chains/effects-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"approvals.command.v1\",\"bodyHex\":\"$PROPOSE_HEX\"}" | jq .

for port in 7071 7072; do
  curl -sS -X POST "http://127.0.0.1:$port/api/v1/app-chain/chains/effects-chain/messages" \
    -H 'Content-Type: application/json' \
    -d "{\"topic\":\"approvals.command.v1\",\"bodyHex\":\"$APPROVE_HEX\"}" | jq .
done
```

`202` means the receiving member queued the envelope. It does not mean the
command is final or changed the item. The topic is a label; the machine reads
only the body. To reject a pending item instead, submit `REJECT_HEX` through
any member. Whichever terminal transition is final first wins.

To add a deadline, pass absolute epoch milliseconds:

```bash
DEADLINE=$(python3 -c 'import time; print(int((time.time() + 300) * 1000))')
PROPOSE_HEX=$(python3 "$TOOL" approvals propose expiring-review-001 --required 2 \
  --deadline-millis "$DEADLINE" --payload-text '{"document":"policy-v3"}')
```

## Submit from Java

```groovy
implementation "org.yanoproject.x:yano-x-client:${yanoXVersion}"
```

```java
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.StdlibAppChainClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

var proposer = new StdlibAppChainClient(AppChainClient.builder("http://127.0.0.1:7070/api/v1")
        .chainId("effects-chain").build());
var approver1 = new StdlibAppChainClient(AppChainClient.builder("http://127.0.0.1:7071/api/v1")
        .chainId("effects-chain").build());
var approver2 = new StdlibAppChainClient(AppChainClient.builder("http://127.0.0.1:7072/api/v1")
        .chainId("effects-chain").build());

String itemId = "release-2026-07";
byte[] payload = "{\"artifact\":\"inventory-service:2.4.0\"}".getBytes(StandardCharsets.UTF_8);
long deadline = Instant.now().plusSeconds(300).toEpochMilli();

proposer.propose(itemId, payload, 2, deadline);
approver1.approve(itemId);
approver2.approve(itemId);
```

Add `.apiKey("...")` to each builder when API authentication is enabled. Two
client objects against the same node are one approver: the envelope sender is
that node's member key.

## Read and prove an item

The item lives under the UTF-8 key `i/<itemId>`. It stores the Blake2b-256 hash
of the payload, not the payload itself; the original bytes stay in block
history while the node retains it.

```bash
ITEM_KEY_HEX=$(python3 -c 'print("i/release-2026-07".encode().hex())')

curl -sS \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/effects-chain/state/proof/$ITEM_KEY_HEX" \
  | jq '{committedHeight, stateRoot, presence, valueHex}'
```

`valueHex` is the CBOR item. In Java:

```java
var item = proposer.approval(itemId).orElseThrow().value();
System.out.println("status=" + item.status() + " approvals=" + item.approvers().size());
```

For an audit, verify the proof against a state root from pinned finality or a
Cardano anchor, not the root the serving node reports.

## Configure

`effects-chain` in the stock cluster file is a complete example, including the
three [state-identity settings](README.md#before-you-configure-one) and the
on-approved effect:

```yaml
yano:
  app-chain:
    chains[2]:
      chain-id: "effects-chain"
      state-machine: approvals
      state:
        commitment-profile: mpf-blake2b256-v1
        format-fingerprint: 91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b
        genesis-id: 63281a4424bed827004e10185a663bce10de4228953d3959e2ed20aa3a4a9f0b
      membership:
        mode: governed
      block:
        interval-ms: 1000
      effects:
        enabled: true
        default-gate: app-final
        external:
          enabled: true
        executor:
          enabled: true
          types: demo.webhook
          tick-ms: 250
        metrics:
          types: demo.webhook
      machines:
        approvals:
          on-approved-effect:
            enabled: true
            type: demo.webhook
            gate: app-final
            expiry-blocks: 100
          activations:
            on-approved-effect: 1
```

Without the `effects` and `machines.approvals` blocks, the machine needs no
settings. A new chain needs its own genesis id.

## The on-approved effect

When an item first becomes APPROVED, the machine can emit one effect. The
proposal payload is the effect's opaque payload, and `type` tells an executor
which contract to run; the machine itself never interprets it.

```text
i/<itemId>     PENDING -> APPROVED            the decision; never changed by the effect
ae/p/<itemId>  the staged payload             written at PROPOSE, deleted when used
ae/s/<itemId>  PENDING -> CONFIRMED | FAILED  the delivery record
effect scope   approvals/on-approved/<itemId>
```

- The payload is staged at `ae/p/<itemId>` only for proposals finalized once
  the effect is active, from its activation height. Pick that height before the
  first proposal that should emit an effect.
- While the effect is active, a PROPOSE whose payload is larger than
  `effects.max-payload-bytes` (16,384 by default) is refused at admission.
- REJECTED and EXPIRED items delete their staged payload and emit nothing.
- Execution is at least once. Executors must use the effect's identity to stay
  idempotent.

For a real HTTP delivery, configure the `webhook.post` executor as in the
[webhook effects tutorial](../tutorials/06-webhook-effects.md).

## Operating notes

- Member signing keys are approval authority. Protect and rotate them as
  consensus identities, not API credentials.
- Payloads are replicated to every member and kept in block history. Encrypt
  secrets before you propose them.
- Adding members does not change an existing item's `required` count.

## Advanced

### Composites

In a declarative composite, `approvals` emits typed events instead of the
standalone effect, and the payload is always staged. An item that reaches
APPROVED without a staged payload is rejected with
`APPROVAL_PAYLOAD_UNAVAILABLE`. A chain with the legacy on-approved effect
enabled exposes no composable kernel; use an effect binding instead of both.

### Admission-rule views and facts

Admission rules can read an item (ADR-031.4, see
[admission rules](../bindings/07-admission-rules.md)):

- **Value view** (namespace `""`, key: the item id): `status` (`PENDING`,
  `APPROVED`, `REJECTED`, `EXPIRED`), `required`, `approverCount`, `proposer`,
  `payloadHash`. The deadline is not exposed: `EXPIRED` is written by the first
  vote after it, so a stored `PENDING` item may already be past it.
- **Post-state facts**, after an approved command: `approverCountAfter`,
  `required`, `approvedNow` and `proposerIsSender`; absent when no item exists.

## Related documentation

- [Your first app ledger](../tutorials/01-first-app-chain.md)
- [Choose a stock state machine](../tutorials/03-stock-state-machines.md)
- [Webhook effects](../tutorials/06-webhook-effects.md)
- [role-approvals](role-approvals.md)
- [Java app ledger client](../../../sdk/client/README.md)
