# Application-ledger configuration

Side-effect-free parsing, property registry and validation of the
`yano.app-chain.*` configuration. The same code serves the running node (host
adapter, engine, REST projection) and offline tooling (`tooling/devtools`), so
the runtime and the tools apply the same rules.

## Artifact

`org.yanoproject.x:yano-x-appchain-config` (`library`). It replaces Yano's former
`org.yanoproject:yano-appchain-config`; see the
[ADR-064 migration notes](../../docs/appchain/MIGRATION-ADR-064.md).

## Key contents

Package `org.yanoproject.x.framework.config`:

- `AppChainConfigParser`: parses keys relative to `yano.app-chain.` (for example
  `chain-id`, `effects.enabled`) into `AppChainConfig`.
- `AppChainPropertyKeys`: the configuration key constants (formerly
  `YanoPropertyKeys.AppChain` in Yano, same strings).
- `AppChainPropertyRegistry`, `AppChainPropertyDefinition`, `AppChainMetadataSource`,
  `AppChainMetadataDescriptor`: the release-pinned property registry, extended by
  first-party components and plugin descriptors
  (`META-INF/yano/appchain-config-metadata-v1.json`) without CLI changes.
- `AppChainTemplateValidator`, `AppChainSemanticValidator`, `AppChainConfigSemantics`,
  `ValidationDiagnostic`: structural and semantic validation shared by the runtime
  and tooling.
- `AppChainStoragePaths`: resolves `yano.app-chain.storage.path` (default
  `appchain-chainstate`) independently of the L1 chainstate.
- `AppChainEffectsConfig`, `AppChainApprovalsConfig`: typed sub-configurations.

## Dependencies / boundaries

`api project(':framework:api')` only. It has no runtime, network or storage
dependency. Consumers include `framework/core`, `framework/host-adapter`,
`framework/rest`, `framework/cardano`, `state-machines/stdlib` and
`tooling/devtools`. Configuration keys and defaults are part of the preserved
contract (ADR-057, ADR-064).

## Build & test

```bash
./gradlew :framework:config:test
```

`frameworkCheck` includes these tests. Add the Yano inputs from
[Build and test](../../docs/BUILD_AND_TEST.md) when the pinned Yano version is
local or staged.
