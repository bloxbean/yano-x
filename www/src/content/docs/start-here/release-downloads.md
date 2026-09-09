---
title: "Choose a Yano X release download"
description: "Each Yano X GitHub release publishes five ZIP files. Download the files you need and SHA256SUMS from the same release. If you download all five ZIPs…"
editUrl: false
---

:::note[Imported page]
This page is generated from [`docs/RELEASE_DOWNLOADS.md`](https://github.com/bloxbean/yano-x/blob/main/docs/RELEASE_DOWNLOADS.md)
in the Yano X repository, which is its source of truth.
:::

Each Yano X GitHub release publishes five ZIP files. Download the files you
need and `SHA256SUMS` from the same release. If you download all five ZIPs,
verify them before extraction:

```bash
sha256sum --check SHA256SUMS
```

On macOS, use `shasum -a 256 --check SHA256SUMS`.

| ZIP | Use it when | First command after extraction |
| --- | --- | --- |
| `yano-x-jvm-<version>.zip` | You want one production-shaped Yano JVM node with the standard Yano X plugin set. | `./yano.sh start:devnet,appchain` |
| `yano-x-plugin-pack-<version>.zip` | You already operate the matching Yano JVM release and want to select Yano X runtime bundles yourself. | Copy reviewed bundles from `plugins/` into the node plugin directory. |
| `yano-showcase-<version>.zip` | You want the quickest local three-node, multi-app-chain demonstration. | `./showcase.sh quickstart --profile light --nodes 3 --instance demo` |
| `yano-x-studio-<version>.zip` | You want the browser-based blueprint editor. It is a static site. | `python3 -m http.server 8080` |
| `yano-x-deploy-<version>.zip` | You want to render and apply multi-node OpenTofu and Ansible deployments. | `bin/yano-x-deploy init ./cluster` |

All five files use the Yano X release version. The combined JVM and plugin-pack
archives also embed the exact compatible Yano host identity. Do not mix a
plugin pack with a different Yano release.

## Run one node

The combined JVM archive is the normal starting point for a single node:

```bash
unzip yano-x-jvm-<version>.zip
cd yano-x-jvm-<version>
./yano.sh start:devnet,appchain
```

This starts one Yano node with app-chain support. Review the included
configuration before using a public Cardano network.

## Run the local multi-node showcase

The showcase archive contains its own combined JVM runtime, demo plugins,
configuration, scripts, and guides. It needs Java 25, Python 3, `curl`, and
`jq`:

```bash
unzip yano-showcase-<version>.zip
cd yano-showcase-<version>
./showcase.sh doctor --profile light
./showcase.sh quickstart --profile light --nodes 3 --instance demo
./showcase.sh verify all --instance demo
./showcase.sh ui --instance demo
./showcase.sh stop --instance demo
```

The light profile uses a private local devnet. `stop` preserves the instance so
it can be restarted later. Continue with the [local showcase guide](/start-here/quickstart/).

## Open App-Chain Studio

Studio is a static browser application and has no server-side component:

```bash
unzip yano-x-studio-<version>.zip
python3 -m http.server 8080
```

Open `http://localhost:8080`, create a blueprint, and download `appchain.yaml`.
Validate the downloaded intent with the CLI from the matching Yano X release.

## Use the deployment CLI

The deployment archive contains the CLI, schema, provider-neutral OpenTofu and
Ansible material, and examples:

```bash
unzip yano-x-deploy-<version>.zip
cd yano-x-deploy-<version>
bin/yano-x-deploy init ./cluster
bin/yano-x-deploy artifact import ./cluster \
  --file /path/to/yano-showcase-<version>.zip
bin/yano-x-deploy validate ./cluster
bin/yano-x-deploy doctor ./cluster
bin/yano-x-deploy render ./cluster
```

`render` is offline. Cloud planning and application require the provider tools
and credentials described in the archive's `README.md` and deployment guide.

## Use the plugin pack

The plugin pack is for operators assembling a custom node from the matching
ordinary Yano JVM distribution. Its `plugins/` directory contains the
conflict-free default set. Alternative implementations are under
`optional-plugins/`; review the manifest before selecting one. Copying both
implementations of the same contribution into the active plugin directory is a
catalog error.

Application developers can instead consume reusable Yano X libraries and
runtime plugin bundles from Maven Central with the `yano-x-bom`. CLI programs,
web applications, demos, and the five ZIP distributions are delivered through
GitHub Releases rather than Maven Central.
