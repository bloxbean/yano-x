# Download a Yano X release

Each Yano X GitHub release publishes one ZIP file, `yano-x-jvm-<version>.zip`,
and its `SHA256SUMS`. Verify the archive before extraction:

```bash
sha256sum --check SHA256SUMS
```

On macOS, use `shasum -a 256 --check SHA256SUMS`.

The same archive serves a production node, the local showcase, the deployment
CLI, and App-Chain Studio. It needs Java 25; the showcase also needs Python 3,
`curl`, and `jq`.

| Path in the archive | Use it when | First command, from the extracted directory |
| --- | --- | --- |
| `yano.sh` | You want one production-shaped Yano JVM node with the standard Yano X plugin set. | `./yano.sh start:devnet,appchain` |
| `examples/showcase/` | You want the quickest local three-node, multi-app-chain demonstration. | `examples/showcase/showcase.sh quickstart --profile light --nodes 3 --instance demo` |
| `tools/yano-deploy/` | You want to render and apply multi-node OpenTofu and Ansible deployments. | `tools/yano-deploy/bin/yano-x-deploy init ./cluster` |
| `studio/` | You want the browser-based blueprint editor. It is a static site. | `cd studio && python3 -m http.server 8080` |
| `plugins/`, `optional-plugins/` | You already operate the matching Yano JVM release and want to select Yano X runtime bundles yourself. | Copy reviewed bundles into the node plugin directory. |

The archive embeds the exact compatible Yano host identity in
`yano-x-distribution-v1.json`. Do not mix its plugins with a different Yano
release.

## Run one node

The archive is the normal starting point for a single node:

```bash
unzip yano-x-jvm-<version>.zip
cd yano-x-jvm-<version>
./yano.sh start:devnet,appchain
```

This starts one Yano node with app-chain support. Review the included
configuration before using a public Cardano network.

On devnet, `config/application-devnet.yml` turns off Yano's L1 history
projection, which cannot load outside Linux x64 in this Yano release
([bloxbean/yano#137](https://github.com/bloxbean/yano/issues/137)). App chains
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
./showcase.sh verify all --instance demo
./showcase.sh ui --instance demo
./showcase.sh stop --instance demo
```

The light profile uses a private local devnet. `stop` preserves the instance so
it can be restarted later. Continue with the [local showcase guide](appchain/deployment/quickstart.md).

## Open App-Chain Studio

Studio is a static browser application and has no server-side component:

```bash
cd yano-x-jvm-<version>/studio
python3 -m http.server 8080
```

Open `http://localhost:8080`, create a blueprint, and download `appchain.yaml`.
Validate the downloaded intent with the CLI from the same release.

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
distribution can take bundles from the archive. `plugins/` contains the
conflict-free default set; alternative implementations are under
`optional-plugins/`, and `yano-x-plugin-pack-v1.json` lists every bundle with
its checksum. Copying both implementations of the same contribution into the
active plugin directory is a catalog error.

Bundles leave out the classes the host `yano.jar` already provides, so they run
only on the Yano release named in `yano-x-distribution-v1.json`.

Application developers can instead consume reusable Yano X libraries from Maven
Central with the `yano-x-bom`. Runtime plugin bundles, CLI programs, web
applications, and demos are delivered through the GitHub release rather than
Maven Central.
