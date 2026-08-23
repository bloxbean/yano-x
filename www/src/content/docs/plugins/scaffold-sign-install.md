---
title: Scaffold, sign, install
description: The full plugin lifecycle command by command — scaffold a bounded starting point, implement, sign the catalog, validate, pin it into a project, and install it on every member.
sidebar:
  order: 2
---

Seven steps from nothing to a running plugin. None of them require touching
Yano.

```mermaid
flowchart LR
    S[scaffold] --> I[implement + test]
    I --> G[sign]
    G --> V[validate / inspect]
    V --> P["init --plugin-jar<br/>(pin into a project)"]
    P --> D[doctor]
    D --> C["copy into plugins/<br/>on every member"]
```

## 1. Scaffold

```bash
./yano.sh appchain plugin scaffold \
  --mode state-machine \
  --id shipment \
  --package com.example.shipment \
  --yano-version 0.1.0-pre13 \
  --output shipment-plugin
```

Modes are `state-machine`, `composite-role`, `effect-executor`, and `sink`. All
four share the same runtime manifest, signed product catalog, and ServiceLoader
conventions.

The generated provider deliberately does **no** business work: state-machine
admission is closed and executor/sink factories return no instances until you
implement them. The tool refuses a non-empty output directory.

## 2. Implement and test

Write the codec, the admission check, and the transitions. Before you do, read
[Determinism rules](/concepts/determinism-rules/) — the constraints are strict
and the failure mode (a cluster that stops finalizing) is expensive to debug.

Then work up the [testing ladder](/plugins/testing-and-deployment/).

## 3. Sign

Sign the exact catalog, runtime manifest, and optional configuration metadata.

```bash
./yano.sh appchain plugin sign \
  --catalog shipment-plugin/src/main/resources/META-INF/yano/appchain-component-catalog-v1.json \
  --runtime-manifest shipment-plugin/src/main/resources/META-INF/yano/plugins/plugin-bundle.shipment.json \
  --seed-file /secure/publisher.seed \
  --key-id example-release-2026 \
  --output shipment-plugin/src/main/resources/META-INF/yano/appchain-component-catalog-v1.sig.json
```

:::danger[Key handling]
The publisher key is a 32-byte seed. Keep it **outside the repository** and pass
it **by file only** — never inline on a command line, never in CI logs, never
in an environment variable that gets echoed. The corresponding public key is not
secret and is distributed with `--trust-key`.
:::

## 4. Build and validate

```bash
cd shipment-plugin && gradle jar && cd ..

./yano.sh appchain plugin validate shipment-plugin/build/libs/shipment-yano-plugin.jar \
  --trust-key example-release-2026=<64-hex-public-key> \
  --output shipment-catalog.json
```

`inspect` prints the same verified catalog and its capabilities.

Neither command loads provider classes, runs plugin code, fetches a registry, or
installs the JAR. The exported snapshot is the safe, data-only local-import
format used by Studio and by generated projects.

## 5. Pin it into a project

```bash
./yano.sh appchain init --non-interactive \
  --recipe custom-plugin --network devnet --members 3 --runtime jvm \
  --capability state:shipment \
  --plugin-jar shipment-plugin/build/libs/shipment-yano-plugin.jar \
  --trust-key example-release-2026=<64-hex-public-key> \
  --output shipment-chain

./yano.sh appchain config validate --mode project shipment-chain
```

The project stores the signed data-only snapshot under `component-catalogs/`.
Its lock pins the snapshot, the catalog, the runtime manifest, the configuration
metadata, and the complete plugin-JAR digests. Rendering and `doctor` reverify
the project snapshot automatically.

## 6. Check readiness against a real distribution

```bash
./yano.sh appchain doctor shipment-chain --distribution /opt/yano-x
```

A missing or different JAR fails artifact readiness. A native distribution
reports a direct incompatibility, because Yano X plugins target the JVM host.

## 7. Install on every member

Copy the exact pinned JAR into `plugins/` in every member's distribution, then
validate the resulting set:

```bash
tools/yano-plugins/bin/yano-plugins validate plugins/*.jar
```

The plugin directory property is **`yano.plugins.directory`**. Restart the nodes;
Yano is not rebuilt.

:::caution[Every member, or none]
The same bundle, the same machine or profile id, and the same committed settings
must run on every voting member. A member with a different bundle derives a
different root and the chain stops finalizing. Treat plugin drift as a
deployment error, never as something the system should tolerate.
:::

## Changing a plugin later

Adding a member or a node is routine. Changing what a plugin *computes* is not
— see [Consensus rules](/plugins/consensus-rules/). In short:

- Give every bundle a stable plugin id and a semantic version.
- Never silently change semantics behind an existing machine or component id.
- Use activation heights and profile governance for compatible evolution.
- Use a new namespace and a migration plan for incompatible state.

## Compare, then apply

```bash
./yano.sh appchain diff previous.lock shipment-chain/appchain.lock
./yano.sh appchain migrate shipment-chain --dry-run
./yano.sh appchain drift shipment-chain --peer <node-identity-url>
```

`diff` classifies a blueprint change before you apply it. `drift` compares a
project against running nodes when identities are available — the fastest way to
find the one member that is out of step.
