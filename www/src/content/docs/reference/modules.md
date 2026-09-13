---
title: "Modules and artifacts"
description: "Yano X Maven publications use the group org.yanoproject.x. This inventory also includes tools, distributions, and fixtures that are not Maven Central…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/modules.md"
---
Yano X Maven publications use the group `org.yanoproject.x`. This inventory
also includes tools, distributions, and fixtures that are not Maven Central
libraries. This page
is generated at documentation build time from `config/artifacts-v1.json`, which
is the repository's single source of truth for artifact identity — the build
verifies it with `verifyArtifactInventory`.

<!-- catalog:versions-start -->

| Value | Current |
|---|---|
| Yano X version | `0.1.0-pre1-SNAPSHOT` |
| Yano host version | `0.1.0-pre15` |
| Maven group | `org.yanoproject.x` |
| Java | `25` |
| Base Yano JVM ZIP | [`yano-0.1.0-pre15.zip`](https://github.com/bloxbean/yano/releases/download/v0.1.0-pre15/yano-0.1.0-pre15.zip) |

<!-- catalog:versions-end -->

## Publication types

| Type | Meaning |
|---|---|
| `runtime-plugin` | Activated by the host through `PluginProviderRegistry` and a schema-v1 manifest. Publishes both a normal JAR and a dependency-complete **bundle** JAR. |
| `library` | An ordinary JAR — contracts, clients, codecs, testkits, CLIs, on-chain artifacts, deterministic helpers. Never loaded as a plugin. |

The distinction is the architectural boundary described in
[Why Yano X](/start-here/why-yano-x/): every optional behavior a running node
can independently select or manage is a runtime plugin; everything else is a
library.

`verifyArtifactInventory` checks that each module has exactly one declared
artifact identity and that every runtime plugin has a bundle publication.

## Modules

<!-- catalog:modules-start -->

### `runtime-plugin` (18)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:state-machines:stdlib` | `yano-x-stdlib` | `org.yanoproject.x.stdlib` | [state-machines/stdlib](https://github.com/bloxbean/yano-x/blob/main/state-machines/stdlib) |
| `:capabilities:authenticated-map-validators` | `yano-x-authenticated-map-validators` | `org.yanoproject.x.authenticated-map-validators` | [capabilities/authenticated-map-validators](https://github.com/bloxbean/yano-x/blob/main/capabilities/authenticated-map-validators) |
| `:composition:runtime` | `yano-x-composite` | `org.yanoproject.x.composite` | [composition/runtime](https://github.com/bloxbean/yano-x/blob/main/composition/runtime) |
| `:capabilities:role-workflow` | `yano-x-role-workflow` | `org.yanoproject.x.role-workflow` | [capabilities/role-workflow](https://github.com/bloxbean/yano-x/blob/main/capabilities/role-workflow) |
| `:products:evidence:registry` | `yano-x-evidence-registry` | `org.yanoproject.x.evidence-registry` | [products/evidence/registry](https://github.com/bloxbean/yano-x/blob/main/products/evidence/registry) |
| `:products:evidence:profile` | `yano-x-evidence-profile` | `org.yanoproject.x.evidence-profile` | [products/evidence/profile](https://github.com/bloxbean/yano-x/blob/main/products/evidence/profile) |
| `:products:cardano-history:runtime` | `yano-x-cardano-history` | `org.yanoproject.x.cardano-history` | [products/cardano-history/runtime](https://github.com/bloxbean/yano-x/blob/main/products/cardano-history/runtime) |
| `:examples:showcase` | `yano-x-showcase` | `org.yanoproject.x.showcase` | [examples/showcase](https://github.com/bloxbean/yano-x/blob/main/examples/showcase) |
| `:connectors:kafka` | `yano-x-kafka` | `org.yanoproject.x.kafka` | [connectors/kafka](https://github.com/bloxbean/yano-x/blob/main/connectors/kafka) |
| `:connectors:objectstore-s3` | `yano-x-objectstore-s3` | `org.yanoproject.x.objectstore.s3` | [connectors/objectstore-s3](https://github.com/bloxbean/yano-x/blob/main/connectors/objectstore-s3) |
| `:connectors:ipfs` | `yano-x-ipfs` | `org.yanoproject.x.ipfs` | [connectors/ipfs](https://github.com/bloxbean/yano-x/blob/main/connectors/ipfs) |
| `:connectors:effects-cardano` | `yano-x-effects-cardano` | `org.yanoproject.x.effects.cardano` | [connectors/effects-cardano](https://github.com/bloxbean/yano-x/blob/main/connectors/effects-cardano) |
| `:state-machines:zk` | `yano-x-zk` | `org.yanoproject.x.zk` | [state-machines/zk](https://github.com/bloxbean/yano-x/blob/main/state-machines/zk) |
| `:ledgers:eutxo:ledger` | `yano-x-eutxo-ledger` | `org.yanoproject.x.eutxo` | [ledgers/eutxo/ledger](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/ledger) |
| `:ledgers:eutxo:bridge-cardano` | `yano-x-eutxo-bridge-cardano` | `org.yanoproject.x.eutxo.bridge.cardano` | [ledgers/eutxo/bridge-cardano](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/bridge-cardano) |
| `:ledgers:eutxo:indexer-jdbc` | `yano-x-eutxo-indexer-jdbc` | `org.yanoproject.x.eutxo.indexer` | [ledgers/eutxo/indexer-jdbc](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/indexer-jdbc) |
| `:ledgers:eutxo-zk:runtime` | `yano-x-eutxo-zk-runtime` | `org.yanoproject.x.eutxo.zk.runtime` | [ledgers/eutxo-zk/runtime](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/runtime) |
| `:ledgers:eutxo-zk:indexer` | `yano-x-eutxo-zk-indexer` | `org.yanoproject.x.eutxo.zk.indexer` | [ledgers/eutxo-zk/indexer](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/indexer) |

### `library` (28)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:state-machines:stdlib-contracts` | `yano-x-stdlib-contracts` | — | [state-machines/stdlib-contracts](https://github.com/bloxbean/yano-x/blob/main/state-machines/stdlib-contracts) |
| `:composition:contracts` | `yano-x-composite-contracts` | — | [composition/contracts](https://github.com/bloxbean/yano-x/blob/main/composition/contracts) |
| `:composition:client` | `yano-x-composite-client` | — | [composition/client](https://github.com/bloxbean/yano-x/blob/main/composition/client) |
| `:capabilities:role-workflow-contracts` | `yano-x-role-workflow-contracts` | — | [capabilities/role-workflow-contracts](https://github.com/bloxbean/yano-x/blob/main/capabilities/role-workflow-contracts) |
| `:sdk:client` | `yano-x-client` | — | [sdk/client](https://github.com/bloxbean/yano-x/blob/main/sdk/client) |
| `:sdk:proof-contracts` | `yano-x-proof-contracts` | — | [sdk/proof-contracts](https://github.com/bloxbean/yano-x/blob/main/sdk/proof-contracts) |
| `:sdk:integration-contracts` | `yano-x-integration-contracts` | — | [sdk/integration-contracts](https://github.com/bloxbean/yano-x/blob/main/sdk/integration-contracts) |
| `:products:client` | `yano-x-products-client` | — | [products/client](https://github.com/bloxbean/yano-x/blob/main/products/client) |
| `:products:evidence:contracts` | `yano-x-evidence-contracts` | — | [products/evidence/contracts](https://github.com/bloxbean/yano-x/blob/main/products/evidence/contracts) |
| `:products:evidence:client` | `yano-x-evidence-client` | — | [products/evidence/client](https://github.com/bloxbean/yano-x/blob/main/products/evidence/client) |
| `:products:cardano-history:client` | `yano-x-cardano-history-client` | — | [products/cardano-history/client](https://github.com/bloxbean/yano-x/blob/main/products/cardano-history/client) |
| `:products:attest:client` | `yano-x-attest-client` | — | [products/attest/client](https://github.com/bloxbean/yano-x/blob/main/products/attest/client) |
| `:products:trust-registry:profile` | `yano-x-trust-registry-profile` | — | [products/trust-registry/profile](https://github.com/bloxbean/yano-x/blob/main/products/trust-registry/profile) |
| `:products:trust-registry:client` | `yano-x-trust-registry-client` | — | [products/trust-registry/client](https://github.com/bloxbean/yano-x/blob/main/products/trust-registry/client) |
| `:products:explorer:core` | `yano-x-explorer-core` | — | [products/explorer/core](https://github.com/bloxbean/yano-x/blob/main/products/explorer/core) |
| `:products:dpp:profile` | `yano-x-dpp-profile` | — | [products/dpp/profile](https://github.com/bloxbean/yano-x/blob/main/products/dpp/profile) |
| `:products:dpp:client` | `yano-x-dpp-client` | — | [products/dpp/client](https://github.com/bloxbean/yano-x/blob/main/products/dpp/client) |
| `:products:attestation-feed:profile` | `yano-x-attestation-feed-profile` | — | [products/attestation-feed/profile](https://github.com/bloxbean/yano-x/blob/main/products/attestation-feed/profile) |
| `:products:attestation-feed:client` | `yano-x-attestation-feed-client` | — | [products/attestation-feed/client](https://github.com/bloxbean/yano-x/blob/main/products/attestation-feed/client) |
| `:ledgers:eutxo:contracts` | `yano-x-eutxo-contracts` | — | [ledgers/eutxo/contracts](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/contracts) |
| `:ledgers:eutxo:client` | `yano-x-eutxo-client` | — | [ledgers/eutxo/client](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/client) |
| `:ledgers:eutxo:indexer-core` | `yano-x-eutxo-indexer-core` | — | [ledgers/eutxo/indexer-core](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/indexer-core) |
| `:ledgers:eutxo-zk:contracts` | `yano-x-eutxo-zk-contracts` | — | [ledgers/eutxo-zk/contracts](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/contracts) |
| `:ledgers:eutxo-zk:zeroj` | `yano-x-eutxo-zk-zeroj` | — | [ledgers/eutxo-zk/zeroj](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/zeroj) |
| `:ledgers:eutxo-zk:prover` | `yano-x-eutxo-zk-prover` | — | [ledgers/eutxo-zk/prover](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/prover) |
| `:ledgers:eutxo-zk:client` | `yano-x-eutxo-zk-client` | — | [ledgers/eutxo-zk/client](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/client) |
| `:ledgers:eutxo-zk:lifecycle` | `yano-x-eutxo-zk-lifecycle` | — | [ledgers/eutxo-zk/lifecycle](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/lifecycle) |
| `:tooling:spring-boot-starter` | `yano-x-spring-boot-starter` | — | [tooling/spring-boot-starter](https://github.com/bloxbean/yano-x/blob/main/tooling/spring-boot-starter) |

### `application` (4)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:products:evidence:demo-runner` | `yano-x-evidence-demo-runner` | — | [products/evidence/demo-runner](https://github.com/bloxbean/yano-x/blob/main/products/evidence/demo-runner) |
| `:examples:showcase-client` | `yano-x-showcase-client` | — | [examples/showcase-client](https://github.com/bloxbean/yano-x/blob/main/examples/showcase-client) |
| `:ledgers:eutxo:demo` | `yano-x-eutxo-demo` | — | [ledgers/eutxo/demo](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/demo) |
| `:ledgers:eutxo-zk:demo` | `yano-x-eutxo-zk-demo` | — | [ledgers/eutxo-zk/demo](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/demo) |

### `tool` (9)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:tooling:devtools` | `yano-x-devtools` | — | [tooling/devtools](https://github.com/bloxbean/yano-x/blob/main/tooling/devtools) |
| `:tooling:deployment` | `yano-x-deployment` | — | [tooling/deployment](https://github.com/bloxbean/yano-x/blob/main/tooling/deployment) |
| `:tooling:studio` | `yano-x-studio` | — | [tooling/studio](https://github.com/bloxbean/yano-x/blob/main/tooling/studio) |
| `:products:cardano-history:cli` | `yano-x-cardano-history-cli` | — | [products/cardano-history/cli](https://github.com/bloxbean/yano-x/blob/main/products/cardano-history/cli) |
| `:products:attest:cli` | `yano-x-attest-cli` | — | [products/attest/cli](https://github.com/bloxbean/yano-x/blob/main/products/attest/cli) |
| `:products:trust-registry:cli` | `yano-x-trust-registry-cli` | — | [products/trust-registry/cli](https://github.com/bloxbean/yano-x/blob/main/products/trust-registry/cli) |
| `:products:explorer:cli` | `yano-x-explorer-cli` | — | [products/explorer/cli](https://github.com/bloxbean/yano-x/blob/main/products/explorer/cli) |
| `:products:dpp:cli` | `yano-x-dpp-cli` | — | [products/dpp/cli](https://github.com/bloxbean/yano-x/blob/main/products/dpp/cli) |
| `:products:attestation-feed:cli` | `yano-x-attestation-feed-cli` | — | [products/attestation-feed/cli](https://github.com/bloxbean/yano-x/blob/main/products/attestation-feed/cli) |

### `test-library` (3)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:sdk:effects-testkit` | `yano-x-effects-testkit` | — | [sdk/effects-testkit](https://github.com/bloxbean/yano-x/blob/main/sdk/effects-testkit) |
| `:ledgers:eutxo:testkit` | `yano-x-eutxo-testkit` | — | [ledgers/eutxo/testkit](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/testkit) |
| `:ledgers:eutxo-zk:testkit` | `yano-x-eutxo-zk-testkit` | — | [ledgers/eutxo-zk/testkit](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/testkit) |

### `onchain-artifact` (4)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:sdk:proof-onchain` | `yano-x-proof-onchain` | — | [sdk/proof-onchain](https://github.com/bloxbean/yano-x/blob/main/sdk/proof-onchain) |
| `:products:cardano-history:onchain` | `yano-x-cardano-history-onchain` | — | [products/cardano-history/onchain](https://github.com/bloxbean/yano-x/blob/main/products/cardano-history/onchain) |
| `:ledgers:eutxo:bridge-onchain` | `yano-x-eutxo-bridge-onchain` | — | [ledgers/eutxo/bridge-onchain](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/bridge-onchain) |
| `:ledgers:eutxo-zk:onchain` | `yano-x-eutxo-zk-onchain` | — | [ledgers/eutxo-zk/onchain](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/onchain) |

### `web-application` (7)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:products:attest:ui` | `yano-x-attest-ui` | — | [products/attest/ui](https://github.com/bloxbean/yano-x/blob/main/products/attest/ui) |
| `:products:evidence:ui` | `yano-x-evidence-ui` | — | [products/evidence/ui](https://github.com/bloxbean/yano-x/blob/main/products/evidence/ui) |
| `:products:eutxo:ui` | `yano-x-eutxo-ui` | — | [products/eutxo/ui](https://github.com/bloxbean/yano-x/blob/main/products/eutxo/ui) |
| `:products:trust-registry:ui` | `yano-x-trust-registry-ui` | — | [products/trust-registry/ui](https://github.com/bloxbean/yano-x/blob/main/products/trust-registry/ui) |
| `:products:explorer:ui` | `yano-x-explorer-ui` | — | [products/explorer/ui](https://github.com/bloxbean/yano-x/blob/main/products/explorer/ui) |
| `:products:dpp:ui` | `yano-x-dpp-ui` | — | [products/dpp/ui](https://github.com/bloxbean/yano-x/blob/main/products/dpp/ui) |
| `:products:attestation-feed:ui` | `yano-x-attestation-feed-ui` | — | [products/attestation-feed/ui](https://github.com/bloxbean/yano-x/blob/main/products/attestation-feed/ui) |

### `test-fixture` (1)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:fixtures:eutxo-e2e` | `yano-x-eutxo-e2e` | — | [fixtures/eutxo-e2e](https://github.com/bloxbean/yano-x/blob/main/fixtures/eutxo-e2e) |

<!-- catalog:modules-end -->

## Using an artifact

```groovy
repositories { mavenCentral() }

// Define yanoXVersion in gradle.properties using your chosen Yano X release.
// The Yano X BOM imports the matching Yano host BOM.
dependencies {
    implementation platform("org.yanoproject.x:yano-x-bom:${yanoXVersion}")
    // The Java client SDK: REST, SSE, and client-side proof verification.
    implementation 'org.yanoproject.x:yano-x-client'

    // Contracts libraries are plain JARs, safe to use off-chain.
    implementation 'org.yanoproject.x:yano-x-evidence-contracts'

    // Tests.
    testImplementation 'org.yanoproject:yano-appchain-core-testkit'
    testImplementation 'org.yanoproject.x:yano-x-effects-testkit'
}
```

Use the [release download guide](/start-here/release-downloads/) to select a
published release. The catalog above describes this checkout; an older release
can have different coordinates and bundles. For unpublished changes, use a
staged repository or explicitly enabled Maven Local as described in
[Developing Yano X](/contributing/).

Runtime plugin bundles are **not** application dependencies. They are installed
into `plugins/` in a distribution, not added to a build file.

## Related

- [Capability catalog](/reference/capabilities/) — which capabilities each
  runtime artifact provides.
- [Build from source](/start-here/build-from-source/) — producing the
  distribution that contains them.
- [`config/artifacts-v1.json`](https://github.com/bloxbean/yano-x/blob/main/config/artifacts-v1.json)
  — the source of truth for this page.
