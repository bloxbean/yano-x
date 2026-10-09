---
title: "The extension ladder"
description: "Yano's core provides ordering, threshold finality, deterministic state, proofs, anchoring, effects, plugin lifecycle, health, and metrics. Application…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/plugins-overview.md"
---
Yano's core provides ordering, threshold finality, deterministic state, proofs,
anchoring, effects, plugin lifecycle, health, and metrics. Application teams
extend the **application layer**, not the consensus runtime.

The plugin framework exists so that a domain can evolve without anyone forking
Yano. Its central rule is simple:

> Choose the smallest extension that models your outcome.

<!-- illustration: extension-ladder -->
```text
Does a stock machine or recipe already model the outcome?
  ├─ yes → configuration only                        (rung 0)
  └─ no
      Do existing machines have the commands and events you need?
        ├─ yes
        │   Can bindings express the coordination?
        │     ├─ yes → declarative bindings          (rung 1)
        │     └─ no  → a small Java composite plugin (rung 2)
        └─ no  → a custom state-machine plugin       (rung 3)
```
<!-- /illustration -->

Other contribution kinds work around the ledger rather than inside `apply()`:
effect executors, finalized-stream sinks, domain APIs, signers, sequencer modes
and L1 observers. Their I/O runs outside `apply()`. Their outputs reach state only
as ordered, certified inputs; see [around the ledger](#around-the-ledger).

## Rung 0 — configuration only

Select one stock machine or profile, identically on every member:

```yaml
yano.app-chain.state-machine: kv-registry
```

No JAR, no build, no signing. This covers `ordered-log`, `kv-registry`,
`authenticated-map`, `approvals`, `balances`, `doc-trail`, `role-approvals`,
and the evidence profiles. See the [recipe catalog](/recipes/).

For stock composite and role profiles, the profile identifier and its
configuration digest become part of chain identity — so select them for a fresh
chain, or through a governed activation.

## Rung 1 — declarative bindings

Use this when existing machines already expose the commands and events you
need, and you only need to connect them: *when this component emits this event,
check these conditions and send that command*. The bundled
`declarative-composite` machine compiles a YAML document into canonical IR that
every member executes identically. Admission rules in the same document can
forbid commands, for example over a limit or from the wrong organization.

No Java is involved. The compiled IR, component settings and limits are part of
the committed composite profile, so changing a deployed workflow is a governed
profile change, not a file edit. Bindings cannot call Java, query the network,
or create authority. Start with the [bindings learning path](/bindings/).

## Rung 2 — a small Java composite plugin

Use this when every component you need already exists, but the coordination is
outside what bindings can express. A composite explicitly defines:

- component ids and versions;
- deterministic application order;
- routed public topics;
- per-component quotas;
- workflow transitions between components; and
- one committed profile identity and digest.

The Java class is intentionally small, and intentionally **consensus-critical**.
Component order is committed profile data: in a Java composite it is this code,
and in a declarative composite it is the compiled IR. Members that ran a
different order would derive different roots, so every member must run the same
reviewed bundle, and a change goes through governed activation.

The effect-gated evidence and role-evidence presets are the reference
implementations. Package the provider, manifest, service entry, and components
in one reviewed bundle.

## Rung 3 — a custom state-machine plugin

Only when you need genuinely new state or new rules.

```java
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;

public final class ShipmentStateMachine implements AppStateMachine {
    @Override
    public String id() {
        return "shipment-v1";
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        // Bounded structural validation only; never perform I/O here.
        return ShipmentCodec.isWellFormed(message.getBody())
                ? AdmissionResult.accept()
                : AdmissionResult.reject("INVALID_SHIPMENT_COMMAND");
    }

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter state, AppEffectEmitter effects) {
        for (AppMessage message : context.messages()) {
            // Deterministic, bounded transitions only. Bytes you cannot decode are a no-op.
        }
    }
}
```

`ShipmentCodec` stands for your own command codec. Reject with a public code of
at most 32 uppercase letters or underscores; REST callers see any other reason
text as `APPLICATION_REJECTED`. Contribute the
machine through `AppStateMachineProvider`, add the service entry and the plugin
manifest, then copy the bundle JAR into the configured plugin directory on every
member. Yano itself is never recompiled for a JVM deployment.

Read [Determinism rules](/concepts/determinism-rules/) before writing `apply()`,
and [Consensus rules](/plugins/consensus-rules/) before changing one that is
already live.

## Around the ledger

These contributions do their work outside `apply()`. That does not make them
irrelevant to the state root: whatever they bring back enters only as ordered,
certified input that every member checks.

| Contribution | Purpose | How it reaches state |
|---|---|---|
| Effect executor | Perform an authorized external action after its finality gate. | With result policy `CHAIN`, the outcome returns as a member-signed `~fx/result` message. |
| L1 observer | React to Cardano address deposits or metadata labels. | As `~l1/*` messages that every member re-derives from its own L1 view; observer profiles are committed in the consensus-context digest. |
| Sequencer mode | Choose who may propose at each height. | It does not; every member must run the same mode, and it cannot weaken finality. |
| Finalized-stream sink | Deliver finalized blocks to Kafka, a webhook, or your own system. | It does not. |
| Domain API and committed queries | Bounded read surfaces over your own state. | It does not. Keep it read-only unless writes still enter through authenticated submission. |
| Signer | External key custody or a KMS. | It does not. |

The host derives a trust tier from each contribution kind: state machines,
sequencer modes and L1 observers are `CONSENSUS`; executors, signers and domain
APIs are `PRIVILEGED_LOCAL`; sinks, health and metrics are `AUXILIARY_LOCAL`.

Create a custom executor when the action needs typed target aliases,
authentication, polling, reconciliation, or a domain-specific receipt. Keep
endpoints and secrets in **node-local** executor configuration, never in
replicated effect payloads.

## What is and is not a plugin

Every optional behavior a running node can independently select or manage
crosses the plugin catalog boundary — stock state machines, capabilities,
connectors, effects, observers, indexers, and product runtime behavior. All of
it activates through `PluginProviderRegistry` and a schema-v1 plugin manifest.
There is no raw `ServiceLoader` path, no direct host construction, no product
switch, and no product-specific host CDI or REST activation.

Pure libraries, DTOs, clients, codecs, testkits, CLIs, on-chain validators, and
deterministic helpers are ordinary JARs. They only become plugins if the host
selects or manages them.

Runtime plugins additionally must:

- publish **dependency-complete** bundles;
- **not** embed host SPI classes;
- declare a compatible `yanoApi` range: `min` and `max` API majors and a
  `minLevel` (there is no maximum level); and
- have bounded lifecycle cleanup.

:::caution[JVM only]
Yano X plugins target the JVM distribution. Yano's native image cannot discover
directory JARs and does not include Yano X plugins — `appchain doctor` reports
a direct incompatibility if you point a project at a native distribution.
:::

## The rest of this section

1. [Scaffold, sign, install](/plugins/scaffold-sign-install/) — the actual
   lifecycle, command by command.
2. [SPI and manifest](/plugins/spi-and-manifest/) — the three bounded contracts
   your JAR carries and the trust envelope over them.
3. [How plugins load](/plugins/how-plugins-load/) — what tools check before
   deployment, what the node checks at start-up, and what neither checks.
4. [Consensus rules](/plugins/consensus-rules/) — what plugin code may do, and
   how to evolve it without forking a live chain.
5. [Testing and deployment](/plugins/testing-and-deployment/) — the testing
   ladder and operational expectations.

The hands-on version of all of this is
[Tutorial 8](/tutorials/08-plugins-and-composites/).
