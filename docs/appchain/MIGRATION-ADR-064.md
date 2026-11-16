# Migrating to Yano-X-owned app chains (ADR-064)

Yano no longer depends on Yano X. Yano is a Cardano node and a generic plugin
host (Host API 1.2) with no application-ledger code. Yano X owns the app-chain
engine, adapter, REST API, console, configuration projection, QA and the
integrated JVM product. This is a preview break; there are no compatibility
shims. Wire formats, consensus identities, storage layouts, `yano.app-chain.*`
keys and defaults, REST paths, JSON shapes and status codes are unchanged.

## Embedders

- `Yano.appChain()`, `appChains()`, `domainApis()` and `localReadModels()` are
  removed. `Yano` now extends `PluginServiceLookup`; reach the chains with
  `org.yanoproject.x.framework.host.AppLedgers.of(yano)` from
  `yano-x-appchain-host-adapter`. Resolve on every use instead of caching the
  result across a restart.
- Start Yano with `YanoAssembly`, set `yano.plugins.required` to the X root
  bundle id (`org.yanoproject.x.app-ledger`), and pass the `yano.app-chain.*`
  settings as `RuntimeOptions` globals. Quarkus is not required.
- `YanoConfig.appChainStoragePath` / `getAppChainStoragePath()` are removed.
  Configure storage with `yano.app-chain.storage.path`; the default is still
  `appchain-chainstate`, now resolved by
  `org.yanoproject.x.framework.config.AppChainStoragePaths`
  (`yano-x-appchain-config`). The key constants that were
  `YanoPropertyKeys.AppChain` moved to X with the same strings.
- X's `CardanoRuntimeServices` record and the `AppLedgerHostPlugin`
  `NODE_CONFIG_SERVICE`, `RUNTIME_OPTIONS_SERVICE` and `ASSEMBLY_SERVICE`
  service keys are removed. The adapter reads `HostApi.nodeConfig()` and
  `runtimeOptions()` and registers its assembly with
  `HostLifecycleRegistry.registerAssembly`; Cardano services come from Yano's
  `org.yanoproject.api.CardanoHostServices`, registered under
  `CardanoHostServices.SERVICE_KEY` (`org.yanoproject.host.cardano-services`)
  and looked up through `PluginServiceLookup`.

## Maven coordinates

Yano stops publishing these artifacts. Use the Yano X owners:

| Old (`org.yanoproject`) | New (`org.yanoproject.x`) |
|---|---|
| `yano-appchain-core-testkit` | `yano-x-appchain-testkit` (`sdk/appchain-testkit`, package `org.yanoproject.x.testkit`) |
| `yano-appchain-config` | `yano-x-appchain-config` |
| `yano-appchain-anchor-onchain` | `yano-x-appchain-anchor-onchain` |
| `yano-appchain-proof-verifier` | `yano-x-appchain-proof-verifier` (`framework/proof-verifier`, package `org.yanoproject.x.framework.proof`) |

## Plugin authors

- Bundle manifests are unchanged.
- `ContributionKind` is no longer an enum with app-chain constants. Yano knows
  four built-in kinds; X declares its kinds (`app-state-machine`, `domain-api`,
  `l1-observer`, ...) in owner-qualified
  `META-INF/yano/contribution-kinds/<owner>.json` descriptors in the X framework
  jars. Refer to X kinds by their manifest key strings.
- Catalog classes take the kind registry: `new BundleManifestParser(kinds)`,
  `new PluginCatalogInspector(kinds)`, `new PluginIndexGenerator(kinds)`. In
  tests, `org.yanoproject.x.testkit.AppChainTestKinds.classPathRegistry()`
  builds the registry from the X descriptors on the class path; replace
  `contribution.kind() == ContributionKind.APP_STATE_MACHINE` with a check of
  `contribution.kind().manifestKey()`.
- The `yano-plugins` CLI accepts X kinds only when you pass the integrated jar
  or the X framework jars with `--host-artifact <jar>`.
- A bare Yano with X bundles in `plugins/` fails closed with "contribution kind
  is not supported".

## Operators

- The integrated JVM ZIP comes from Yano X. Yano's own ZIP, Docker image and
  native binary serve no app chains; the native binary has no
  application-ledger support.
- The `yano.sh appchain` subcommand now exists only in the Yano X
  distribution launcher.
- `yano.mode=app-ledger` on a bare Yano fails startup with "app-ledger mode
  requires a plugin that provides an app-only network".
- The integrated application sets `yano.plugins.required` to
  `org.yanoproject.x.app-ledger`. If `yano.app-chain.enabled` is true and an
  override drops that root from `yano.plugins.required`, startup fails.
- `/api/v1/plugin-operations` is protected by Yano's own
  `PluginOperationsAuthFilter`, with or without Yano X. It still reads the keys
  from `yano.app-chain.api.keys` and keeps the same outcomes: 404 for a
  non-canonical path, 503 `AUTH_UNAVAILABLE` without a full key, 401 for a
  missing or invalid key, 403 for a topic-scoped key.
- Docker: Yano's compose bundle no longer mounts `appchain-chainstate/` or
  `appchain-indexers/`, and Yano X publishes no Docker image. Build one from the
  integrated `yano.jar` (see `scaffolds/docker-compose-cluster`).
- REST paths, ports, `yano.app-chain.*` settings and storage directories are
  unchanged. The app-chain console is at `/ui/app-chain/` and links back to
  Yano's console at `/ui/`.

## Host API compatibility

Yano X requires Host API 1.2 (`HostApiVersion` `(1, 2)`). The frozen
host-matrix fixtures that modelled hosts embedding X (`first-host`,
`supported-host`, `namespace-host`) are retired. The Host API 1.2 baseline
fixture is committed at `qa/framework/host-matrix/fixtures/host-api-1.2-baseline`,
compiled against Yano `0.1.0-pre20-adr064.4` (Host API `1.2.0-adr064.4`) and
X `0.1.0-pre6`; see `qa/framework/host-matrix/README.md`.

See [Yano ADR-064](https://github.com/bloxbean/yano/blob/main/adr/064-yano-x-depends-on-yano-never-the-reverse.md).
