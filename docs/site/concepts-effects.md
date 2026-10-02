# Effects

Everything else in an app ledger keeps state inside the ledger. **Effects** let a
finalized transition trigger an action outside it — call an ERP or webhook,
publish to Kafka, store an object, pin to IPFS, submit a Cardano payment —
without breaking determinism.

**You'll learn:** how an effect travels from `emit` to a recorded outcome, what
its identity and result policy mean, how to run an executor, and what a result
does and does not prove.

## The rule that makes it safe

> A state machine never performs the action. It emits a record describing it.

Emission happens inside deterministic `apply()`, is identical on every member,
and is committed transitively through a count-bound `effectsRoot` leaf into the
state root. A separate **effect runtime**, outside consensus, executes
finalized effects. When the effect asks for it, the runtime reports the outcome
back as an ordinary sequenced message.

The guarantee is **exactly-once incorporation, at-least-once execution**. The
external action may run more than once, so **every executor and every receiver
must be idempotent** under the supplied idempotency identity.

## Life of an effect

Step through one `webhook.post` effect. The "What if" scenarios show the `NONE`
result policy, a receiver that answers 4xx or 5xx, and an executor that crashes
after the POST.

<!-- illustration: effect-lifecycle -->
1. **Emit.** Inside `apply()`, the state machine calls `effects.emit(...)`. Nothing
   is sent. The emitter returns the effect's id: its chain, height, and ordinal.
2. **Finalize.** The block becomes final. Every member stores the same effect
   record, and its hash is committed under `~fx/root/<height>`.
3. **Pick up.** The effect runtime, enabled on one node, reads the finalized
   record and checks its gate. Its progress is node-local.
4. **Deliver.** The executor POSTs the payload with an `Idempotency-Key` derived
   from the effect id, the same on every attempt.
5. **Answer.** A 2xx confirms the effect. A 4xx fails it with no retry. A 5xx or
   a network error is retried.
6. **Report.** For a `CHAIN` effect, the executor node submits a member-signed
   `~fx/result` message.
7. **Incorporate.** The first valid result closes the effect, and every member
   calls `onEffectResult`. Later results are no-ops.
<!-- /illustration -->

## Identity and idempotency

An effect's identity is `EffectId(chain, height, ordinal)`: the block that
emitted it and its position among that block's emissions. Every member derives
the same id, across retries, restarts, and executor failover. The scope you pass
to `.scope(...)` is an application handle and the executor's ordering key; it is
**not** part of the identity.

Executors hand external systems a fixed-width key derived from that id: the
Blake2b-256 hash of `"yano-fx-v1"`, the chain id, the height, and the ordinal.
The built-in webhook sends it as the `Idempotency-Key` header. A receiver must
store the key with the work it does and answer a repeated key without doing the
work again.

## Result policy: NONE or CHAIN

`ResultPolicy` decides whether the outcome comes back into the ledger. There are
two values, and the default is `NONE`.

| Policy | What happens to the outcome | Expiry | `onEffectResult` |
|---|---|---|---|
| `NONE` (default) | Recorded only on the executing node; visible through REST and metrics. | Not allowed | Never called |
| `CHAIN` | Re-enters as a member-signed `~fx/result` message and is incorporated exactly once. | Mandatory | Called with `CONFIRMED`, `FAILED`, `CANCELLED`, or `EXPIRED` |

Choose `CHAIN` whenever the application must react to the outcome or prove it
later.

## Enabling effects

Effects are off by default, and these are **consensus parameters** — every
member must set identical values, or the state root diverges exactly as it
would with a mismatched state machine.

```yaml
yano.app-chain.effects.enabled: true
yano.app-chain.effects.max-per-block: 256           # effects one block may emit
yano.app-chain.effects.max-payload-bytes: 16384     # per-effect payload cap
yano.app-chain.effects.max-expiry-blocks: 100000
yano.app-chain.effects.result-window-blocks: 100000 # results incorporable within this window
yano.app-chain.effects.outcome-commitment: per-effect  # per-effect | per-block
yano.app-chain.effects.default-gate: app-final      # app-final | l1-anchored | zk-settled
```

Enabling effects reserves the `~fx/` key prefix in the state trie — a state
machine may not write keys starting with `~fx/`. The reservation holds from
genesis regardless of the flag, so effects can be switched on later without
colliding with historical state.

## Emitting from a state machine

```java
public class OrderStateMachine implements AppStateMachine {
    @Override public String id() { return "orders"; }

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) {
        for (AppMessage m : context.messages()) {
            Order o = decode(m.getBody());
            writer.put(key(o.id()), o.toBytes());          // ordinary state
            if (o.isApproved()) {
                effects.emit(EffectIntent.of("webhook.post", o.fulfilmentJson())
                        .scope("orders/" + o.id())         // application idempotency scope
                        .result(ResultPolicy.CHAIN)        // the outcome returns on-chain
                        .gate(FinalityGate.CHAIN_DEFAULT)
                        .expiryBlocks(1000)                // deterministic timeout
                        .sourceMessageId(m.getMessageId())
                        .build());
            }
        }
    }

    // Called deterministically when a CHAIN effect's outcome is incorporated.
    @Override
    public void onEffectResult(AppBlockExecutionContext context, EffectResult result,
                               AppStateWriter writer, AppEffectEmitter effects) {
        if (!result.scope().startsWith("orders/")) return;
        String id = result.scope().substring("orders/".length());
        writer.put(fulfilledKey(id), result.externalRef());
    }
}
```

The emitter records intent and performs no I/O. Everything forbidden in
`apply()` — wall clock, randomness, network — stays forbidden. Leave out
`.result(ResultPolicy.CHAIN)` and the effect is `NONE`: `onEffectResult` is
never called for it.

:::caution[Emission logic is consensus logic]
Changing what a transition emits on a live chain is a hard fork unless the
change is gated behind a governed profile activation. See
[Consensus rules for plugins](/plugins/consensus-rules/).
:::

## Finality gates

A **gate** decides when an emitted effect becomes eligible to execute.

| Gate | Eligible when | Notes |
|---|---|---|
| `app-final` | The block is committed. | The chain is append-only after finality, so emission is already irrevocable. |
| `l1-anchored` | The effect's height is covered by an anchor confirmed in an L1 block at least `l1.stability-depth` blocks below this node's L1 tip. | The emission is provable against Cardano before you act. An anchor that L1 rolls back stops counting. |
| `zk-settled` | Covered by an accepted validity proof. | Reserved for the ZK settlement roadmap; waits until expiry on non-ZK chains. |

`l1-anchored` requires [anchoring](/concepts/anchoring/) to be enabled and
`l1.stability-depth` to be above 0; a chain whose `effects.default-gate` is
`l1-anchored` without a stability depth fails to start.
`effects.gate.anchor-margin-blocks` (node-local, default 0) holds effects that
many blocks further below the stable anchored height. Each node judges depth
from its own L1 view, and in metadata mode only the anchor leader observes
anchors, so run `l1-anchored` executors on that node.

## Expiry is mandatory

Every `CHAIN` effect must provably close, because a result arriving after the
result window is a deterministic no-op. Passing `expiryBlocks(0)`, or no expiry
at all, sets it to the smaller of `effects.max-expiry-blocks` and
`effects.result-window-blocks`. An expiry larger than the result window is
rejected.

When the expiry height passes with no incorporated result, the effect
deterministically becomes **`EXPIRED`** and is delivered to `onEffectResult`.
That is the "nobody answered in time" escape hatch, and it is distinct from
**`FAILED`**, which means "the target answered no". An operator's cancel
closes an open `CHAIN` effect as **`CANCELLED`**; it cannot unsend an action
already taken. The executor does not dispatch an effect within two blocks of
its expiry, so the expiry can close it cleanly.

## The result path, and what a result actually proves

Executed `CHAIN` outcomes re-enter as member-signed `~fx/result` messages,
sequenced like anything else. The executor node resubmits a result until the
ledger closes the effect. The interpreter is fail-closed and
first-result-wins: duplicate, late, malformed, unknown, or out-of-window
results are deterministic no-ops. **A result can never stall the chain.**

A result is a **member attestation**, not an independently verified fact.
Followers check the signature and membership, not the external world. Narrow
who may attest:

```yaml
# Only these member keys' ~fx/result messages are accepted. Default: any member.
yano.app-chain.effects.result.signers: "<hex pubkey of the executor node>"
```

For L1-visible facts such as a payment landing, prefer verifying through an
[L1 observer](/concepts/observations/) over trusting an attestation. `k`-of-`n`
result attestation for high-value effects is designed but not yet shipped.

## Running an executor

The effect runtime is off by default on every node. Everywhere it is off,
effects are still emitted and provable; they wait.

```yaml
yano.app-chain.effects.executor.enabled: true   # this node executes effects
yano.app-chain.effects.executor.types: ""       # empty = all; or "webhook.post,kafka.publish"
yano.app-chain.effects.executor.max-attempts: 8 # then PARKED for operator review
```

- **Run it on one node,** or split effect types across nodes with disjoint
  `effects.executor.types`. There is no cross-node mutual exclusion: two nodes
  enabled for the same type both attempt it, and idempotency absorbs the
  duplicate.
- **Failures retry with backoff, then park.** A parked effect never blocks the
  others; an operator can requeue or cancel it through the privileged effects
  REST surface.
- **A late-enabled executor does not fire history.** Open effects that predate
  it are quarantined until an operator requeues them.
- **External workers** can execute instead, through
  `yano.app-chain.effects.external.enabled: true`. A worker claims effects with
  `POST …/effects/claim` and reports with `POST …/effects/{height}/{ordinal}/report`;
  the node signs the `~fx/result`. Claims are wall-clock leases, and late
  reports are fenced to the lease holder. Leases and fencing are node-local
  bookkeeping on the member the worker talks to, not consensus state.

The [user guide's effects section](https://github.com/bloxbean/yano-x/blob/main/docs/APP_CHAIN_USER_GUIDE.md#18-effects--acting-on-the-outside-world)
covers every setting, the REST surface, and writing a custom executor.

## Executors and sinks

| Kind | What it does | Availability |
|---|---|---|
| `webhook.post` executor and finalized webhook sink | HTTP delivery of an authorized action or a finalized block stream. | Bundled |
| `kafka.publish` executor and Kafka sink | Acknowledged effects and finalized blocks to Kafka topics. | First-party optional plugin |
| `object.put` executor | Immutable or versioned writes to tested S3-compatible stores. | First-party optional plugin |
| `ipfs.pin` executor | Reconciled pin-only effects through a configured Kubo RPC. | First-party optional plugin |
| `cardano.payment` executor | Cardano payments from the effect system. A preview, tightly capped-wallet profile: use no material funds until the transaction-safety work tracked as `FX-002` is complete. | First-party optional plugin |

All of these capabilities, and the effect runtime itself, are **preview** in
the current release catalog. An optional connector needs its exact
release-matched bundle installed in `plugins/` on every applicable node, plus
the external service. See
[Optional connectors](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/OPTIONAL_CONNECTORS.md).

## Try it

The bundled effects demo needs no broker, object store, or credentials. It uses
the stock `effects-chain`, whose `approvals` machine emits a `demo.webhook`
effect when an item is approved; a simulated external worker claims it and
reports success.

```bash
./yano.sh appchain cluster start 3
./yano.sh appchain cluster effect demo "order 42 approved"
```

Expected output, with your heights and hashes:

```text
Effect emitted       effects-chain height=<h> ordinal=<n>
Effect type          demo.webhook
Executor             external demo worker on node 0
Delivery             CONFIRMED
External reference   demo-worker://confirmed/<effect id>
Attempts              <n>
Proof                 AVAILABLE (<hash prefix>...)
Captured payload      {"event":"demo.effect.requested","itemId":"demo-…","message":"order 42 approved"}
```

Next: [Tutorial 6 — webhook effects](/tutorials/06-webhook-effects/) runs a
real `webhook.post` against a local receiver, and covers when to use a
finalized-block sink instead.
