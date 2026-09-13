---
title: "Effects"
description: "Everything else in an app chain keeps state inside the chain. Effects let a finalized transition trigger an action outside it — call an ERP or webhook…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/concepts-effects.md"
---
Everything else in an app chain keeps state inside the chain. **Effects** let a
finalized transition trigger an action outside it — call an ERP or webhook,
publish to Kafka, store an object, pin to IPFS, submit a Cardano payment —
without breaking determinism.

## The rule that makes it safe

> A state machine never performs the action. It emits a record describing it.

Emission happens inside deterministic `apply()`, is identical on every member,
and is committed transitively through a count-bound `effectsRoot` leaf into the
state root. A separate **effect runtime**, outside consensus, executes
finalized effects and reports the outcome back as an ordinary sequenced
message.

```text
apply()  ── emit ──▶  effect record (outbox + effectsRoot, provable, anchored)
                          │  finalized
                          ▼
                 Effect runtime ── execute ──▶  external system
                          │
        ~fx/result (member-signed, sequenced) ──▶ apply() records the outcome
```

The guarantee is **exactly-once incorporation, at-least-once execution**. The
external action may run more than once, so **every executor and every receiver
must be idempotent** under the supplied idempotency identity.

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
`apply()` — wall clock, randomness, network — stays forbidden.

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
| `l1-anchored` | The effect's height is covered by an L1-confirmed, stability-deep anchor. | The emission is provable against Cardano before you act. A verifiability delay, not a rollback safeguard. |
| `zk-settled` | Covered by an accepted validity proof. | Reserved for the ZK settlement roadmap; waits until expiry on non-ZK chains. |

`l1-anchored` requires [anchoring](/concepts/anchoring/) to be enabled and
`l1.stability-depth` to be set. `effects.gate.anchor-margin-blocks` adds a
safety margin above the anchor high-water mark.

## Expiry is mandatory

Every `CHAIN` effect must provably close, because a result arriving after the
result window is a deterministic no-op. Passing `expiryBlocks(0)` makes the
framework default it to the result window.

When the expiry height passes with no incorporated result, the effect
deterministically becomes **`EXPIRED`** and is delivered to `onEffectResult`.
That is the "nobody answered in time" escape hatch, and it is distinct from
**`FAILED`**, which means "the target answered no".

## The result path, and what a result actually proves

Executed `CHAIN` outcomes re-enter as member-signed `~fx/result` messages,
sequenced like anything else. The interpreter is fail-closed and
first-result-wins: duplicate, late, malformed, unknown, or out-of-window
results are deterministic no-ops. **A result can never stall the chain.**

A result is a **member attestation**, not an independently verified fact.
Followers check the signature and membership, not the external world. Narrow
who may attest:

```yaml
# Only these member keys' ~fx/result messages are accepted. Default: any member.
yano.app-chain.effects.result.signers: "<hex pubkey of the executor node>"
```

For L1-visible facts such as a payment landing, prefer verifying through an L1
observer over trusting an attestation. `k`-of-`n` result attestation for
high-value effects is designed but not yet shipped.

## Executors and sinks

| Kind | What it does | Availability |
|---|---|---|
| `webhook.post` executor and finalized webhook sink | HTTP delivery of an authorized action or a finalized block stream. | Bundled |
| `kafka.publish` executor and Kafka sink | Acknowledged effects and finalized blocks to Kafka topics. | First-party optional plugin |
| `object.put` executor | Immutable or versioned writes to tested S3-compatible stores. | First-party optional plugin |
| `ipfs.pin` executor | Reconciled pin-only effects through a configured Kubo RPC. | First-party optional plugin |
| `cardano.payment` executor | Cardano payments from the effect system. | First-party optional plugin |

An optional connector needs its exact release-matched bundle installed in
`plugins/` on every applicable node, plus the external service. See
[Optional connectors](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/OPTIONAL_CONNECTORS.md).

Executors may run on a designated member, a dedicated executor node, or as an
external worker through the claim/report API. Type partitions and stable
executor identities make ownership visible and recoverable, and executor
fencing prevents two workers from double-claiming after a failover.

## Try it

```bash
# The bundled effects demo needs no broker, object store, or credentials.
./yano.sh appchain cluster start 3
./yano.sh appchain cluster effect demo
./yano.sh appchain cluster effect demo "order 42 approved"
```

Then read [Tutorial 6 — webhook effects](/tutorials/06-webhook-effects/), which
covers when to use a finalized-block sink versus an acknowledged
`webhook.post` effect.
