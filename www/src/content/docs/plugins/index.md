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

```text
Does a stock machine or profile already model the outcome?
  ├─ yes → configuration only                 (rung 0)
  └─ no
      Are all required components already available?
        ├─ yes → a small composite plugin     (rung 1)
        └─ no  → a custom state-machine plugin (rung 2)
```

Independent SPIs cover effect executors, finalized-stream sinks, domain APIs,
signers, sequencer mode, and L1 observers. Those are rung 3 — they run outside
consensus, so they never affect the state root.

## Rung 0 — configuration only

Select one built-in id or profile, identically on every member:

```yaml
yano.app-chain.state-machine: kv-registry
```

No JAR, no build, no signing. This covers `ordered-log`, `kv-registry`,
`authenticated-map`, `approvals`, `balances`, `doc-trail`, `role-approvals`,
and the evidence profiles. See the [recipe catalog](/recipes/).

For stock composite and role profiles, the profile identifier and its
configuration digest become part of chain identity — so select them for a fresh
chain, or through a governed activation.

## Rung 1 — a small composite plugin

Use this when every component you need already exists, but you need them
arranged differently. A composite explicitly defines:

- component ids and versions;
- deterministic application order;
- routed public topics;
- per-component quotas;
- workflow transitions between components; and
- one committed profile identity and digest.

The Java class is intentionally small, and intentionally **consensus-critical**.
YAML cannot dynamically insert arbitrary component plugins into a frozen
profile, because two members discovering a different order would derive
different roots. The order is code, reviewed and signed.

The effect-gated evidence and role-evidence presets are the reference
implementations. Package the provider, manifest, service entry, and components
in one reviewed bundle.

## Rung 2 — a custom state-machine plugin

Only when you need genuinely new state or new rules.

```java
public final class ShipmentStateMachine implements AppStateMachine {
    @Override
    public String id() {
        return "shipment-v1";
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        // Bounded structural validation only; never perform I/O here.
        return decodeSafely(message.getBody())
                ? AdmissionResult.accept()
                : AdmissionResult.reject("invalid shipment command");
    }

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter state, AppEffectEmitter effects) {
        // Deterministic bounded transitions only.
    }
}
```

Contribute it through `AppStateMachineProvider`, add the service entry and the
plugin manifest, then copy the bundle JAR into the configured plugin directory
on every member. Yano itself is never recompiled for a JVM deployment.

Read [Determinism rules](/concepts/determinism-rules/) before writing `apply()`,
and [Consensus rules](/plugins/consensus-rules/) before changing one that is
already live.

## Rung 3 — outside consensus

| SPI | Purpose |
|---|---|
| Effect executor | Perform an authorized external action after its finality gate. |
| Finalized-stream sink | Deliver finalized blocks to Kafka, a webhook, or your own system. |
| Domain API and committed queries | Bounded read surfaces over your own state. |
| Signer | External key custody or a KMS. |
| Sequencer mode | Alternative proposer selection. |
| L1 observer | React to Cardano address deposits or metadata labels. |

Create a custom executor when the action needs typed target aliases,
authentication, polling, reconciliation, or a domain-specific receipt. Keep
endpoints and secrets in **node-local** executor configuration, never in
replicated effect payloads.

Keep plugin domain APIs read-only unless commands still enter through the
authenticated app-chain submission path.

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
- declare compatible Yano API major and min/max levels; and
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
3. [Consensus rules](/plugins/consensus-rules/) — what plugin code may do, and
   how to evolve it without forking a live chain.
4. [Testing and deployment](/plugins/testing-and-deployment/) — the testing
   ladder and operational expectations.

The hands-on version of all of this is
[Tutorial 8](/tutorials/08-plugins-and-composites/).
