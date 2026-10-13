# Why Yano X

Choose Yano X when several organizations need shared records, workflows, or
integrations with independently verifiable results. Start with existing
capabilities, then add custom Java rules only where your application needs them.

Yano supplies the host. Yano X adds application behavior through JVM plugins
and reusable libraries. The dependency runs one way: `yano-x → yano`.

## The boundary

<!-- illustration: yano-boundary -->

| Concern | Owned by |
|---|---|
| Cardano node, chain sync, L1 `chainstate` | Yano |
| Message ordering, membership, threshold finality certificates | Yano |
| Authenticated state (MPF or JMT), proofs and proof subjects, catch-up, replay | Yano |
| Anchoring to Cardano metadata or a threshold-signed script | Yano |
| Effect runtime: gates, retries, receipts, result incorporation; the webhook executor | Yano |
| Plugin SPI, manifest schema, catalog validation, lifecycle | Yano |
| `ordered-log` | Yano |
| Every other stock state machine | **Yano X** |
| Composite profiles, governed profile evolution, declarative bindings | **Yano X** |
| Kafka, S3, IPFS, and Cardano-payment executors; the Kafka sink | **Yano X** |
| Products: Evidence, Cardano History, Attest, Trust Registry, and more | **Yano X** |
| Java client SDK, Spring Boot starter, testkits, deployment tool, App-Chain Studio | **Yano X** |
| The JVM distribution that ships all of the above pre-installed | **Yano X** |

<!-- /illustration -->

## What "batteries included" means

For a first run, choose the [local showcase](/start-here/quickstart/).
Each release ships one archive, **`yano-x-jvm-<version>.zip`**: the standard
Yano JVM distribution with the Yano X plugin bundles already laid out, the
command-line and deployment tools, App-Chain Studio, the local showcase, and
identity manifests for both projects. The
[release download guide](/start-here/release-downloads/) shows where each part
lives. Operators who already run the matching Yano release can take bundles
from its `plugins/` directory or download the standalone plugin pack; the
deployment tool and Studio also ship as standalone archives.

The default `plugins/` directory is a deliberate, conflict-free **selection**,
not a copy of every published bundle. Alternative implementations that would
claim the same contribution live under `optional-plugins/`, and you opt into
them explicitly.

## Everything optional is a plugin

Any behavior that a running node can select or manage independently is a
plugin:

- The node activates it through its plugin catalog (`PluginProviderRegistry`),
  from a schema-v1 plugin manifest.
- A runtime plugin ships as one dependency-complete bundle that does not embed
  host SPI classes, and its manifest declares the plugin API range it supports
  (`yanoApi`: `min` and `max` API major, and `minLevel`).
- Plugins run in the node's process as trusted code. The catalog checks
  manifests, compatibility, and lifecycle; it is not a sandbox.

Pure libraries, DTOs, clients, codecs, testkits, CLIs, on-chain validators, and
deterministic helpers are ordinary JARs. They become plugins only if the host
selects or manages them.

## JVM only, on purpose

Yano X is JVM-only: no GraalVM or native-image tasks and no native executables.
Yano's native image is core-only and starts with `ordered-log`, but it cannot
load Yano X bundles. `./yano.sh appchain doctor` reports a custom JVM plugin as
incompatible with a native distribution.

## Namespaces

Use the namespace of the artifact you consume:

- **Yano X uses Maven group `org.yanoproject.x` and Java packages
  `org.yanoproject.x.*`.** Yano host artifacts use `org.yanoproject` and host
  packages use `org.yanoproject.*`. Third-party Cardano libraries keep their
  own groups, including `com.bloxbean.cardano`.
- **The plugin directory property is `yano.plugins.directory`.**

## What you work with

Whatever you build, the surface is the same:

- `./yano.sh appchain …`: the main command line, shipped inside the
  distribution. The deployment tool and some products add their own commands
  under `tools/`.
- The REST API and the server-sent event stream on each node.
- The Java client SDK, and a Spring Boot starter, for typed commands and proof
  verification.
- [App-Chain Studio](/studio/), for building and exporting a blueprint
  visually.

Next: [Try the local showcase](/start-here/quickstart/), then
[choose a recipe](/recipes/choosing-a-recipe/).
