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

## Versions

:::tip[Check GitHub for the latest release]
The GitHub repository is the source of truth for releases. This site can lag
behind a new release, so always confirm the latest version on
[Yano X releases](https://github.com/bloxbean/yano-x/releases).
:::

The latest Yano X release and the Yano host it is built on:

<!-- catalog:versions-start -->

| Value | Current |
|---|---|
| Yano X version | `0.1.0-pre4` |
| Yano host version | `0.1.0-pre19-4e8accd-SNAPSHOT` |
| Maven group | `org.yanoproject.x` |
| Java | `25` |
| Base Yano JVM ZIP | [`yano-0.1.0-pre19-4e8accd.zip`](https://repo.bloxbean.org/dist/snapshots/yano/0.1.0-pre19-4e8accd/yano-0.1.0-pre19-4e8accd.zip) |

<!-- catalog:versions-end -->

Snapshot builds are for internal testing only. The BloxBean snapshot
repository (`https://repo.bloxbean.org/maven/snapshots`) publishes them under
the next version with `-SNAPSHOT` appended, such as `0.1.0-pre4-SNAPSHOT`. A
snapshot distribution adds the short commit id to its file name, as in
`yano-x-jvm-0.1.0-pre4-3e9e1c8.zip`, only to tell builds apart. Drop the commit
id and add `-SNAPSHOT` to get the Maven version. A distribution you build
yourself may carry neither, as in `yano-x-jvm-0.1.0-pre4.zip`, yet is still
`0.1.0-pre4-SNAPSHOT`; its artifacts exist only where you publish them,
normally your Maven Local. In every case,
`jq -r .version yano-x-distribution-v1.json` in the distribution's top-level
directory prints the exact Maven version.

## Publication types

`config/artifacts-v1.json` gives every module one of eight publication types.
Only four of them are Maven Central dependencies.

| Type | Meaning | On Maven Central |
|---|---|---|
| `runtime-plugin` | Activated by the host through `PluginProviderRegistry` and a schema-v1 manifest. Publishes a normal JAR and a dependency-complete **bundle** JAR. | Yes, unless marked otherwise |
| `library` | An ordinary JAR: contracts, clients, codecs, and deterministic helpers. Never loaded as a plugin. | Yes, unless marked otherwise |
| `test-library` | A JAR for your tests, such as a testkit. | Yes, unless marked otherwise |
| `onchain-artifact` | Cardano validator source in Java (Julc), such as the on-chain proof verifiers. | Yes, unless marked otherwise |
| `tool` | A command-line or browser tool shipped in the JVM distribution, such as the developer tools, the deployment CLI, or Studio. | No |
| `application` | A runnable demo or client program, such as a demo runner. | No |
| `web-application` | A static product UI shipped in the JVM distribution. | No |
| `test-fixture` | Repository test material, such as an end-to-end fixture. | No |

Some modules in the first four types set `publishToCentral: false` and ship
only in the GitHub release. The distinction between the first two is the
architectural boundary described in [Why Yano X](/start-here/why-yano-x/):
every optional behavior a running node can independently select or manage is
a runtime plugin; everything else is not.

`verifyArtifactInventory` checks that each module has exactly one declared
artifact identity and that every runtime plugin has a bundle publication.

## Modules

<!-- catalog:modules-start -->

### `runtime-plugin` (15)

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
| `:ledgers:eutxo:ledger` | `yano-x-eutxo-ledger` | `org.yanoproject.x.eutxo` | [ledgers/eutxo/ledger](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/ledger) |
| `:ledgers:eutxo:bridge-cardano` | `yano-x-eutxo-bridge-cardano` | `org.yanoproject.x.eutxo.bridge.cardano` | [ledgers/eutxo/bridge-cardano](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/bridge-cardano) |
| `:ledgers:eutxo:indexer-jdbc` | `yano-x-eutxo-indexer-jdbc` | `org.yanoproject.x.eutxo.indexer` | [ledgers/eutxo/indexer-jdbc](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/indexer-jdbc) |

### `library` (14)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:state-machines:stdlib-contracts` | `yano-x-stdlib-contracts` | — | [state-machines/stdlib-contracts](https://github.com/bloxbean/yano-x/blob/main/state-machines/stdlib-contracts) |
| `:composition:contracts` | `yano-x-composite-contracts` | — | [composition/contracts](https://github.com/bloxbean/yano-x/blob/main/composition/contracts) |
| `:composition:client` | `yano-x-composite-client` | — | [composition/client](https://github.com/bloxbean/yano-x/blob/main/composition/client) |
| `:capabilities:role-workflow-contracts` | `yano-x-role-workflow-contracts` | — | [capabilities/role-workflow-contracts](https://github.com/bloxbean/yano-x/blob/main/capabilities/role-workflow-contracts) |
| `:sdk:client` | `yano-x-client` | — | [sdk/client](https://github.com/bloxbean/yano-x/blob/main/sdk/client) |
| `:sdk:proof-contracts` | `yano-x-proof-contracts` | — | [sdk/proof-contracts](https://github.com/bloxbean/yano-x/blob/main/sdk/proof-contracts) |
| `:sdk:integration-contracts` | `yano-x-integration-contracts` | — | [sdk/integration-contracts](https://github.com/bloxbean/yano-x/blob/main/sdk/integration-contracts) |
| `:products:evidence:contracts` | `yano-x-evidence-contracts` | — | [products/evidence/contracts](https://github.com/bloxbean/yano-x/blob/main/products/evidence/contracts) |
| `:products:evidence:client` | `yano-x-evidence-client` | — | [products/evidence/client](https://github.com/bloxbean/yano-x/blob/main/products/evidence/client) |
| `:products:cardano-history:client` | `yano-x-cardano-history-client` | — | [products/cardano-history/client](https://github.com/bloxbean/yano-x/blob/main/products/cardano-history/client) |
| `:ledgers:eutxo:contracts` | `yano-x-eutxo-contracts` | — | [ledgers/eutxo/contracts](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/contracts) |
| `:ledgers:eutxo:client` | `yano-x-eutxo-client` | — | [ledgers/eutxo/client](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/client) |
| `:ledgers:eutxo:indexer-core` | `yano-x-eutxo-indexer-core` | — | [ledgers/eutxo/indexer-core](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/indexer-core) |
| `:tooling:spring-boot-starter` | `yano-x-spring-boot-starter` | — | [tooling/spring-boot-starter](https://github.com/bloxbean/yano-x/blob/main/tooling/spring-boot-starter) |

### `application` (3)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:products:evidence:demo-runner` | `yano-x-evidence-demo-runner` | — | [products/evidence/demo-runner](https://github.com/bloxbean/yano-x/blob/main/products/evidence/demo-runner) |
| `:examples:showcase-client` | `yano-x-showcase-client` | — | [examples/showcase-client](https://github.com/bloxbean/yano-x/blob/main/examples/showcase-client) |
| `:ledgers:eutxo:demo` | `yano-x-eutxo-demo` | — | [ledgers/eutxo/demo](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/demo) |

### `tool` (6)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:tooling:appchain-formats` | `yano-x-appchain-formats` | — | [tooling/appchain-formats](https://github.com/bloxbean/yano-x/blob/main/tooling/appchain-formats) |
| `:tooling:devtools` | `yano-x-devtools` | — | [tooling/devtools](https://github.com/bloxbean/yano-x/blob/main/tooling/devtools) |
| `:tooling:appchain-cli` | `yano-x-appchain-cli` | — | [tooling/appchain-cli](https://github.com/bloxbean/yano-x/blob/main/tooling/appchain-cli) |
| `:tooling:deployment` | `yano-x-deployment` | — | [tooling/deployment](https://github.com/bloxbean/yano-x/blob/main/tooling/deployment) |
| `:tooling:studio` | `yano-x-studio` | — | [tooling/studio](https://github.com/bloxbean/yano-x/blob/main/tooling/studio) |
| `:products:cardano-history:cli` | `yano-x-cardano-history-cli` | — | [products/cardano-history/cli](https://github.com/bloxbean/yano-x/blob/main/products/cardano-history/cli) |

### `test-library` (2)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:sdk:effects-testkit` | `yano-x-effects-testkit` | — | [sdk/effects-testkit](https://github.com/bloxbean/yano-x/blob/main/sdk/effects-testkit) |
| `:ledgers:eutxo:testkit` | `yano-x-eutxo-testkit` | — | [ledgers/eutxo/testkit](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/testkit) |

### `onchain-artifact` (3)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:sdk:proof-onchain` | `yano-x-proof-onchain` | — | [sdk/proof-onchain](https://github.com/bloxbean/yano-x/blob/main/sdk/proof-onchain) |
| `:products:cardano-history:onchain` | `yano-x-cardano-history-onchain` | — | [products/cardano-history/onchain](https://github.com/bloxbean/yano-x/blob/main/products/cardano-history/onchain) |
| `:ledgers:eutxo:bridge-onchain` | `yano-x-eutxo-bridge-onchain` | — | [ledgers/eutxo/bridge-onchain](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/bridge-onchain) |

### `web-application` (1)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:products:eutxo:ui` | `yano-x-eutxo-ui` | — | [products/eutxo/ui](https://github.com/bloxbean/yano-x/blob/main/products/eutxo/ui) |

### `test-fixture` (2)

| Gradle module | Artifact id | Plugin bundle id | Source |
|---|---|---|---|
| `:fixtures:eutxo-e2e` | `yano-x-eutxo-e2e` | — | [fixtures/eutxo-e2e](https://github.com/bloxbean/yano-x/blob/main/fixtures/eutxo-e2e) |
| `:fixtures:governed-recipes` | `yano-x-governed-recipes` | — | [fixtures/governed-recipes](https://github.com/bloxbean/yano-x/blob/main/fixtures/governed-recipes) |

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
