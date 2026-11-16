# Standalone application-ledger root

The manifested activation root for application ledgers without Cardano. It is the
no-L1 alternative to [`framework/cardano`](../cardano/README.md): a host that
selects this root runs the application consensus and storage engine with no
Cardano runtime, observers, anchors or effect executors on its class path.

## Artifact

`org.yanoproject.x:yano-x-appchain-standalone` (`library`).

## Key contents

- `org.yanoproject.x.framework.standalone.StandaloneAppLedgerPlugin`: a final
  subclass of the host adapter's `AppLedgerHostPlugin`, deliberately free of
  Cardano dependencies.
- `META-INF/yano/plugins/org.yanoproject.x.app-ledger.json`: schema-v1 plugin
  manifest with one `node-plugin` contribution; bundle id
  `org.yanoproject.x.app-ledger`, version expanded from the project version.
- `META-INF/services/org.yanoproject.api.plugin.NodePlugin`.

## Dependencies / boundaries

`api project(':framework:host-adapter')` only. It must not depend on
`framework/cardano`, `framework/cardano-api` or `framework/cardano-onchain`; the
`standalone` profile of the [packaged host matrix](../../qa/framework/host-matrix/README.md)
forbids them. It declares the same bundle id as the Cardano root, so a host
composes exactly one of the two. The integrated JVM distribution composes the
Cardano root, which also serves `yano.mode=app-ledger`; this module is for
standalone compositions such as the host matrix. Operating the standalone mode is
described in [Standalone application ledgers](../../docs/appchain/STANDALONE.md).

## Build & test

```bash
./gradlew :framework:standalone:jar
```

The module has no tests of its own; `frameworkCheck` builds its JAR, and the host
matrix's `standalone` profile qualifies it with three packaged TCP nodes. Add the
Yano inputs from [Build and test](../../docs/BUILD_AND_TEST.md) when the pinned
Yano version is local or staged.
