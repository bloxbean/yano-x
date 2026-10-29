---
title: "Scaffold, sign, install"
description: "Five steps from nothing to a plugin installed on every member. None of them require touching Yano. Select a step to see its command and what it checks."
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/scaffold-sign-install.md"
---
Five steps from nothing to a plugin installed on every member. None of them
require touching Yano. Select a step to see its command and what it checks.

<!-- illustration: plugin-pipeline -->
1. Scaffold a project with `plugin scaffold`.
2. Implement and test it.
3. Sign the catalog and manifests with `plugin sign`.
4. Build the JAR and verify it with `plugin validate`.
5. Install the same JAR on every member; each node checks it at start-up.
<!-- /illustration -->

## 1. Scaffold

```bash
./yano.sh appchain plugin scaffold \
  --mode state-machine \
  --id shipment \
  --package com.example.shipment \
  --yano-version <matching-yano-host-version> \
  --output shipment-plugin
```

Replace `<matching-yano-host-version>` with the exact Yano version in your
distribution manifest (see the [current version table](/reference/modules/)).
This is the host version, not the Yano X plugin version.

Modes are `state-machine`, `composite-role`, `effect-executor`, and `sink`. All
four share the same runtime manifest, signed product catalog, and ServiceLoader
conventions.

The generated provider deliberately does **no** business work: state-machine
admission is closed and executor/sink factories return no instances until you
implement them. The tool refuses a non-empty output directory.

## 2. Implement and test

Write the codec, the admission check, and the transitions. A state machine's
only execution entry point is
`apply(AppBlockExecutionContext, AppStateWriter, AppEffectEmitter)`;
[Tutorial 8](/tutorials/08-plugins-and-composites/) shows a complete machine.
Before you write it, read [Determinism rules](/concepts/determinism-rules/) —
the constraints are strict and the failure mode (a cluster that stops
finalizing) is expensive to debug.

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

The scaffold ships no Gradle wrapper, so install a Gradle that supports
Java 25 (9.1 or later) or add your organization's wrapper first. Sign before you build: the signature is packaged
inside the JAR.

```bash
cd shipment-plugin && gradle jar && cd ..

./yano.sh appchain plugin validate shipment-plugin/build/libs/shipment-yano-plugin.jar \
  --trust-key example-release-2026=<64-hex-public-key> \
  --output shipment-catalog.json
```

`inspect` prints the same verified catalog and its capabilities.

Neither command loads provider classes, runs plugin code, fetches a registry, or
installs the JAR. The exported snapshot is a safe, data-only copy of the
verified catalog.

## 5. Install on every member

Copy the exact same JAR into `plugins/` in every member's distribution, then
validate the resulting set from the distribution root:

```bash
tools/yano-plugins/bin/yano-plugins validate plugins/*.jar
```

The plugin directory property is **`yano.plugins.directory`**. Restart the nodes;
Yano is not rebuilt. At start-up each node checks the JAR again before any plugin
code runs; [How plugins load](/plugins/how-plugins-load/) lists those checks.

:::note[Custom catalogs and the setup CLI]
`appchain seal` resolves an application's chains through the distribution's
own recipe and capability catalog. It does not yet accept a custom signed
component catalog, so a custom capability such as `state:shipment` cannot be
selected in a setup draft in this release. See
[Tutorial 8](/tutorials/08-plugins-and-composites/) for running a custom
plugin today.
:::

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

## Find the member that is out of step

Each node reports its plugin catalog fingerprint as `pluginCatalogFingerprint`
from the privileged `identity` route of each chain; compare it across members
to find the one that runs a different bundle. For an application set up with
the setup CLI, `appchain start` refuses a node whose sealed bundles changed
since it joined, until its operator accepts the new set with
`./yano.sh appchain join <app>.chain --accept-bundles`.
