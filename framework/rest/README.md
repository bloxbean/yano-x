# Application-ledger REST and console

The Quarkus surface of the integrated Yano + Yano X application (ADR-064 D8, D9):
the app-chain REST API, plugin domain routes, API-key filter, Micrometer metrics,
the `appchain` health group, the `yano.app-chain.*` configuration projection and
the app-chain console. It is a Quarkus library composed with Yano's published
`yano-app-core`; [`distribution/jvm`](../../distribution/jvm/README.md) is the
application built from it. Plain-Java embedders use
[`framework/host-adapter`](../host-adapter/README.md)'s `AppLedgers` instead.

## Artifact

`org.yanoproject.x:yano-x-appchain-rest` (`library`).

## Key contents

Package `org.yanoproject.x.framework.rest`:

- `AppChainResource` (`app-chain/...`, chain-scoped under
  `app-chain/chains/{chainId}/...`): messages, SSE stream, blocks, state proofs,
  committed queries, topics, effects, observations, snapshots, evidence and admin
  operations (pause, anchor, members, threshold), under the host's REST root (for
  example `/api/v1/app-chain`).
- `PluginDomainResource` (`plugins/{bundleId}/...`): host-owned adapter for
  manifested domain API routes.
- `AppChainApiKeyFilter`, `AppChainAccess`: API-key authentication
  (`yano.app-chain.api.*`) with read, topic-scoped submit, snapshot-admin and
  privileged levels.
- `AppChainGlobals`: projects the typed configuration into the runtime globals
  passed to plugins and fails startup when app chains are enabled without the
  `org.yanoproject.x.app-ledger` root in `yano.plugins.required`.
- `AppChainMetrics`, `AppChainHealthGroupCheck` (`/q/health/group/appchain`, not
  part of readiness), `AppChainProducer`, `AppChainApiGroup` (the separate
  `/q/openapi-app-chain` document).
- `META-INF/microprofile-config.properties`: defaults at ordinal 100 (operator
  configuration wins), including `yano.plugins.required` and
  `yano.app-chain.storage.path=appchain-chainstate`.
- `console/`: the SvelteKit app-chain console, built by `buildConsole` into
  `META-INF/resources/ui/app-chain` and served at `/ui/app-chain/`, next to Yano's
  generic console at `/ui/`. See [the console guide](../../docs/console-ui.md).

## Dependencies / boundaries

`api`: the Quarkus platform BOM, Yano's `yano-app-core` and `framework/api`;
`implementation`: `framework/config`, `framework/host-adapter`,
`framework/proof-verifier`. Yano's console has no app-chain pages; app-chain UI
belongs here.

## Build & test

```bash
./gradlew :framework:rest:test
./gradlew :framework:rest:check
```

`test` runs the resource unit tests; `check` adds `checkConsole`, `testConsole`
(Vitest) and `verifyConsoleContract`. The build downloads Node 22.12.0 for the
console. The HTTP and OpenAPI suites run in `distribution/jvm`. Add the Yano inputs
from [Build and test](../../docs/BUILD_AND_TEST.md) when the pinned Yano version is
local or staged.
