---
title: "Download a Yano X release"
description: "Each Yano X GitHub release publishes yano-x-jvm-<version>.zip, three standalone archives, and SHA256SUMS. Verify an archive before extraction:"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/RELEASE_DOWNLOADS.md"
---
Each Yano X GitHub release publishes `yano-x-jvm-<version>.zip`, three
standalone archives, and `SHA256SUMS`. Verify an archive before extraction:

```bash
sha256sum --check --ignore-missing SHA256SUMS
```

On macOS, use `shasum -a 256 --check --ignore-missing SHA256SUMS`.
`--ignore-missing` checks only the archives you downloaded; each one must
report `OK`.

The same archive runs local and generated-project nodes, the local showcase, the
deployment CLI, and App-Chain Studio. It needs Java 25; the showcase also needs
Python 3, `curl`, and `jq`, and the tutorials use `curl` and `jq`.

| Path in the archive | Use it when | First command, from the extracted directory |
| --- | --- | --- |
| `yano.sh` | You want app ledger members with the standard Yano X plugin set: a rehearsal on one machine, or your own node in an application with other operators. | `./yano.sh appchain try` |
| `examples/showcase/` | You want the quickest local three-node, multi-ledger demonstration. | `cd examples/showcase && ./showcase.sh quickstart --profile light --nodes 3 --instance demo` |
| `tools/yano-deploy/` | You want to render and apply multi-node OpenTofu and Ansible deployments. | `tools/yano-deploy/bin/yano-x-deploy init ./cluster` |
| `studio/` | You want the browser-based binding editor. It is a static site. | `cd studio && python3 -m http.server 8080` |
| `plugins/` | You already operate the matching Yano JVM release and want to select Yano X runtime bundles yourself. | Copy reviewed bundles into the node plugin directory. |

The archive embeds the exact compatible Yano host identity in
`yano-x-distribution-v1.json`. Do not mix its plugins with a different Yano
release.

If you need only one part, download it on its own. Each standalone archive
carries the same files as the matching path in the JVM archive:

| Standalone archive | Contents |
| --- | --- |
| `yano-x-plugin-pack-<version>.zip` | `plugins/`, `yano-x-plugin-pack-v1.json`, the SBOM, and a README naming the one Yano release the bundles run on |
| `yano-x-deploy-<version>.zip` | The deployment CLI (`bin/yano-x-deploy`) with its own libraries and the provider-neutral deployment material |
| `yano-x-jvm-<version>-<os>-<arch>.zip` | The whole JVM archive plus a bundled Java 25 runtime, for machines without Java (Linux x64/arm64, macOS arm64) |

## Run members on one machine

`./yano.sh appchain try` rehearses an application on a private devnet: a
coordinator and three operators, alice, bob and carol, each with their own
node directory and member key under `./appchain-try/`. It runs the same setup
commands that real operators run, and prints each one:

```bash
unzip yano-x-jvm-<version>.zip
cd yano-x-jvm-<version>
./yano.sh appchain try
./yano.sh appchain status --node-dir appchain-try/alice/yano-node
```

> **✓ You should see** a message finalized at the same height and root on
> every node, then `members online 3/3` and `chain orders … members agree`.

`./yano.sh appchain stop --node-dir appchain-try/alice/yano-node` stops one
node and keeps its data; `./yano.sh appchain try --clean` stops all three and
deletes the rehearsal. [Tutorial 1](/tutorials/01-first-app-chain/)
walks through it. To run your own application with other operators, each on
their own node, see [Set up an application](/deployment/configure/).

On devnet, the archive's `config/application-devnet.yml` turns off Yano's L1
history projection, which cannot load outside Linux x64 in this Yano release
([bloxbean/yano#137](https://github.com/bloxbean/yano/issues/137)). App ledgers
and the showcase do not need it; Yano's address, account, and reward history
endpoints return 503 on devnet until it is re-enabled.

## Run the local multi-node showcase

The showcase lives in `examples/showcase/` and runs on the distribution it
ships in. It keeps its own demo configuration under `examples/showcase/yano/`
and never edits the distribution's `config/`:

```bash
cd yano-x-jvm-<version>/examples/showcase
./showcase.sh doctor --profile light
./showcase.sh quickstart --profile light --nodes 3 --instance demo
./showcase.sh verify --instance demo
./showcase.sh ui --instance demo
./showcase.sh stop --instance demo
```

The light profile uses a private local devnet. `stop` preserves the instance so
it can be restarted later. Continue with the [local showcase guide](/start-here/quickstart/).

## Open App-Chain Studio

Studio is a static browser application and has no server-side component:

```bash
cd yano-x-jvm-<version>/studio
python3 -m http.server 8080
```

Open `http://localhost:8080` and choose **Bindings** to compose state machines
with a binding document. Check the downloaded document with
`./yano.sh appchain bindings` from the same release, and deploy it as a chain of
an application with `./yano.sh appchain new <app> --chain <id>=bindings:<file>`.

## Use the deployment CLI

The deployment CLI ships in `tools/yano-deploy/` with its schema,
provider-neutral OpenTofu and Ansible material, and examples. A deployment
imports the whole release archive, which carries the showcase profile it
deploys:

```bash
cd yano-x-jvm-<version>
tools/yano-deploy/bin/yano-x-deploy init ./cluster
tools/yano-deploy/bin/yano-x-deploy artifact import ./cluster \
  --file /path/to/yano-x-jvm-<version>.zip
tools/yano-deploy/bin/yano-x-deploy validate ./cluster
tools/yano-deploy/bin/yano-x-deploy doctor ./cluster
tools/yano-deploy/bin/yano-x-deploy render ./cluster
```

`render` is offline. Cloud planning and application require the provider tools
and credentials described in `tools/yano-deploy/README.md` and the deployment
guide.

## Add Yano X plugins to an existing node

Operators assembling a custom node from the matching ordinary Yano JVM
distribution can take bundles from the JVM archive or from
`yano-x-plugin-pack-<version>.zip`. `plugins/` contains the
conflict-free set, and `yano-x-plugin-pack-v1.json` lists every bundle with
its checksum. Two bundles that provide the same contribution in the active
plugin directory are a catalog error.

Bundles leave out the classes the host `yano.jar` already provides, so they run
only on the Yano release named in `yano-x-distribution-v1.json`.

Application developers can instead consume reusable Yano X libraries from Maven
Central with the `yano-x-bom`. Runtime plugin bundles, CLI programs, web
applications, and demos are delivered through the GitHub release rather than
Maven Central.
