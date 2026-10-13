---
title: "How plugins load"
description: "A plugin JAR is checked twice: by tools before you deploy it, and by every node when it starts. Neither check makes plugin code safe to run. This page…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/plugins-how-plugins-load.md"
---
A plugin JAR is checked twice: by tools before you deploy it, and by every node
when it starts. Neither check makes plugin code safe to run. This page shows
what each check covers, what the node's class loader is, and where the trust
boundaries are.

- **You'll learn:** which command catches which mistake, the order of the
  node's start-up checks, what the shared loader does and does not isolate, and
  how to find a member that runs a different JAR.
- **Before you start:** read [SPI and manifest](/plugins/spi-and-manifest/) for
  the files a plugin JAR carries.

## Before deployment: tooling checks

These commands read plugin JARs as data. None of them runs the plugin's code.

| Command | Run it | It checks |
|---|---|---|
| `./yano.sh appchain plugin validate <jar> --trust-key <id>=<key>` (or `inspect`) | after `gradle jar` | The Ed25519 envelope over the catalog, runtime manifest and configuration metadata. `validate --output` exports a data-only catalog snapshot. |
| `tools/yano-plugins/bin/yano-plugins validate <jar>` | before copying JARs to a node | The manifest, the match between manifest and ServiceLoader entries, no packaged Yano API classes, and API compatibility with this host. `--api-major` and `--api-level` target another host. Exit 2 means invalid. |
| `./yano.sh appchain init --plugin-jar <jar> --trust-key …` | when you create the project | The signed snapshot; it then pins the catalog, manifest, metadata and JAR digests in `appchain.lock`. `render` and `doctor` re-verify the snapshot. |
| `./yano.sh appchain doctor <project> --distribution <dir>` | before starting nodes | Every pinned JAR is present in the distribution and unchanged. |
| `./yano.sh appchain drift <project> --peer <api-base-url>` | against running nodes | Each node's identity against the project, including its plugin catalog fingerprint. |

`yano-plugins` uses the same strict scanner as the node, so a JAR it rejects as
malformed would also stop a node from starting. It is the quickest check after a
build.

## At start-up: the node's checks

A JVM node builds its plugin catalog once, at start-up, before any plugin code
runs. Step through the checks in order, then try a "What if" to see which check
stops a broken bundle.

<!-- illustration: plugin-activation -->
1. **Capture.** The node copies every JAR in `yano.plugins.directory` into a
   private snapshot: at most 256 JARs, 1 GiB each and 4 GiB in total.
2. **Scan.** It reads each snapshot as data. It needs one schema-v1 manifest,
   no packaged `org/yanoproject/api/**` classes, ServiceLoader entries that match
   the manifest exactly, and no JAR-manifest `Class-Path`.
3. **Check the API range.** The manifest's `yanoApi` must include the host's API
   major, and the host's level must be at least `minLevel`.
4. **Validate in isolation.** Each JAR is opened alone to resolve its provider
   types. No provider constructor or static initializer runs.
5. **Apply policy.** `yano.plugins.allow-list` and `yano.plugins.deny-list`
   select bundles. A JAR that mixes selected and filtered bundles is refused.
6. **Build the shared loader.** Selected snapshots join one parent-first class
   loader, in SHA-256 order.
7. **Correlate providers.** Every manifested provider needs a matching
   ServiceLoader entry from its own JAR.
8. **Order bundles.** Declared dependencies, versions and cycles are checked,
   and bundles are put in dependency order.
9. **Fingerprint.** Every JAR is hashed again and must be unchanged. The catalog
   fingerprint is logged and reported as `pluginCatalogFingerprint`.
10. **Publish and activate.** The immutable registry is published. Lifecycle
    callbacks run, and each provider is constructed when the node first needs
    it.
<!-- /illustration -->

Any structural catalog error stops start-up. The node does not start with part
of a broken plugin set.

## The shared loader

All selected plugin JARs share **one** class loader, with the host's own class
loader as its parent:

- **Parent-first.** A class the host already has always comes from the host.
  That keeps one copy of the Yano API, and it is why a plugin must not package
  `org/yanoproject/api/**` classes. Do not also put plugin JARs on the host's
  class path.
- **Shared.** Bundles see each other's classes. There is no per-bundle
  isolation: if two bundles carry different versions of one library, both get
  whichever copy the loader finds first.
- **Snapshotted.** The loader reads the private copies made at start-up.
  Replacing a JAR in the plugin directory changes nothing until the node
  restarts, and there is no hot reload.
- **Not a sandbox.** Plugins are trusted, in-process Java code with the node's
  permissions. A broken callback can ignore interruption; if a plugin blocks
  shutdown, stop the process and remove or replace the bundle.

## Trust boundaries

| Question | Answer |
|---|---|
| Does the node verify the publisher's signature? | No. Signatures are checked by tooling: `plugin validate`, `inspect`, `metadata verify`, project rendering and `doctor`. Signed manifests and JARs at runtime are deferred work. |
| What does the node trust? | Any JAR in its plugin directory that passes the structural checks and the allow and deny lists. Protect that directory like the node's binaries. |
| Does a valid signature approve the code? | No. It authenticates the catalog and manifest bytes, nothing more. |
| What must match across members? | The same bundles, the same machine or profile ids and the same committed settings. A member with a different JAR can compute a different state root and stall. |
| Which contributions affect agreement? | The host's `CONSENSUS` tier: state machines, sequencer modes and L1 observers. Executors, signers and domain APIs are `PRIVILEGED_LOCAL`; sinks, health and metrics are `AUXILIARY_LOCAL`. |

The same caution applies to authoring tools that construct plugins:
`appchain bindings catalog`, `validate` and `profile-check` run the installed
plugins' code, unsandboxed. Run them only on bundles you trust.

## Diagnostics

- **Start-up failure.** Start-up stops with the reason. Common ones:

  | Message contains | Cause | Fix |
  |---|---|---|
  | `plugin artifact packages Yano API class` | The JAR includes host API classes | Make the host API `compileOnly` and rebuild |
  | `manifest and supported ServiceLoader entries differ` | A provider is in the manifest but not in `META-INF/services`, or the reverse | Make the two agree |
  | `does not support Yano plugin API major` | The host's major is outside `min`–`max`, or its level is below `minLevel` | Use the host version the bundle was built for |
  | `cannot mix selected and policy-filtered bundles` | One JAR holds bundles that the allow or deny list splits | Put each bundle in its own JAR |

- **Which catalog is running.** Each node logs a
  `YANO_PLUGIN_CATALOG_PROVENANCE` line with its catalog fingerprint at
  start-up, and reports it as `pluginCatalogFingerprint` from
  `/api/v1/app-chain/chains/<chain-id>/identity` (under the configured API
  prefix).
- **Find the member that differs.** `./yano.sh appchain drift <project> --peer
  http://<member>:8080/api/v1/` compares every node's identity with the project.
  A plugin difference shows as `cluster.plugin-catalog` and the result is
  `DRIFT_DETECTED`.
- **Plugin health and metrics.** See Yano's
  [plugin operations guide](https://github.com/bloxbean/yano/blob/main/docs/PLUGIN_OPERATIONS.md).

Next: [Consensus rules](/plugins/consensus-rules/).
