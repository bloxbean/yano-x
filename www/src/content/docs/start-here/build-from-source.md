---
title: Build from source
description: Yano X has no published release yet. Build the batteries-included JVM distribution from a clean clone with one Gradle command — no second checkout and no flags.
sidebar:
  order: 3
---

Yano X has **no published release**, so the install path is a source build.
That sounds heavier than it is: from a clean clone it is one command, because
the build resolves the matching Yano host distribution for you.

:::note[Two different build tracks]
This page is the **user** track: build the distribution, run it, move on. If
you are changing Yano X itself — or changing Yano and Yano X together — read
[Developing Yano X](/contributing/) instead. Mixing the two is the most common
source of confusion, because the contributor track needs Maven Local and
several `-P` flags that you do not.
:::

## Prerequisites

| Requirement | Notes |
|---|---|
| **Java 25** | Required. Check with `java -version`. |
| Git | To clone the repository. |
| ~2 GB disk | The base Yano JVM ZIP alone is around 280 MB, plus Gradle caches. |
| `bash`, `curl`, `jq`, `python3`, `openssl` | Used by the cluster launcher and the tutorials. |
| Docker Desktop | Only for the full evidence/connector demo. |

Gradle itself does not need to be installed — the repository ships a wrapper.

## Build

```bash
git clone https://github.com/bloxbean/yano-x.git
cd yano-x
./gradlew clean build -PskipSigning=true
```

That is the whole thing. No `-PyanoVersion`, no `-PuseMavenLocal`, no
`-PyanoJvmDist`.

### Why it works with no flags

`gradle.properties` pins the exact Yano host line Yano X is built against:

<!-- catalog:versions-start -->

| Value | Current |
|---|---|
| Yano X version | `0.1.0-SNAPSHOT` |
| Yano host version | `0.1.0-pre14` |
| Maven group | `com.bloxbean.cardano` |
| Java | `25` |
| Base Yano JVM ZIP | [`yano-0.1.0-pre14.zip`](https://github.com/bloxbean/yano/releases/download/v0.1.0-pre14/yano-0.1.0-pre14.zip) |

<!-- catalog:versions-end -->

Because that `yanoVersion` is a released, non-SNAPSHOT version, the
distribution tasks resolve and cache the matching ordinary Yano JVM ZIP from
the corresponding `bloxbean/yano` GitHub release automatically. The URL
convention is:

```text
https://github.com/bloxbean/yano/releases/download/v<version>/yano-<version>.zip
```

You only need `-PyanoJvmDist` to *override* that release asset with a local or
staged ZIP.

`-PskipSigning=true` skips artifact signing, which is only relevant when
publishing.

## What you get

Two reproducible archives under `distribution/jvm/build/distributions`:

| Archive | Contents |
|---|---|
| `yano-x-jvm-<version>.zip` | The standard Yano JVM distribution, the default and optional Yano X plugin layout, and identity manifests for both projects. **This is what you run.** |
| `yano-x-plugin-pack-<version>.zip` | The plugin bundles only, with a checksummed manifest — for adding Yano X to a Yano distribution you already operate. |

Both include the repository `LICENSE` and a normalized CycloneDX 1.6 SBOM under
`sbom/`. The combined JVM archive also preserves the host's license as
`LICENSE.yano` and its SBOM as `sbom/yano.cdx.json`.

Inside the JVM archive, everything sits under a single `yano-x-jvm-<version>/`
directory (a `-SNAPSHOT` suffix is stripped from that directory name, so a
`0.1.0-SNAPSHOT` build unpacks to `yano-x-jvm-0.1.0/`):

```text
yano-x-jvm-<version>/
├── yano.sh                       # the public CLI and node launcher
├── yano.jar
├── config/                       # chain definitions and network genesis
│   └── schema/                   # blueprint, catalog, and lock schemas
├── plugins/                      # the default, conflict-free bundle selection
├── optional-plugins/             # alternatives you opt into explicitly
├── tools/
│   ├── yano-plugins/             # plugin catalog validator (from the Yano host)
│   └── yano-appchain/            # the offline engine behind `yano.sh appchain`
├── studio/                       # App-Chain Studio, the blueprint builder
├── skills/configure-yano-appchain/   # the first-party AI agent skill
├── appchain-cluster/             # the single-host cluster launcher scripts
├── examples/evidence/            # the evidence demo harness and runner
├── docs/                         # the repository documentation set
├── sbom/
└── LICENSE, LICENSE.yano
```

Two of those are easy to miss: `studio/` is the same blueprint builder
[hosted on this site](/studio/), and `skills/configure-yano-appchain/` is a
version-matched agent skill — see [Using Yano X with AI agents](/ai/).

## Unpack and check

```bash
unzip distribution/jvm/build/distributions/yano-x-jvm-*.zip -d ~/yano-x
cd ~/yano-x/yano-x-jvm-*

./yano.sh appchain help
./yano.sh appchain recipes
./yano.sh appchain capabilities
```

`recipes` and `capabilities` print what this exact build can do. They are the
authoritative answer for your version — the [recipe catalog](/recipes/) and
[capability catalog](/reference/capabilities/) on this site are generated from
the same source files, but the binary in your hands always wins.

You are ready for the [Quickstart](/start-here/quickstart/).

## The default plugin selection

`plugins/` is an activatable selection, not an indiscriminate copy. All runtime
bundles are published and versioned independently; the default distribution
activates every one that can coexist.

The standard eUTxO runtime and the eUTxO ZK runtime both intentionally provide
the `app-state-machine/eutxo-ledger` contribution, so exactly one of them may
be installed. Copying both into `plugins/` is a hard catalog error.

To switch to the ZK implementation, remove the standard eUTxO ledger bundle,
copy the ZK runtime bundle from `optional-plugins/` into `plugins/` **on every
member**, and validate the result:

```bash
tools/yano-plugins/bin/yano-plugins validate plugins/*.jar
```

## Building against a different Yano version

If you need a Yano line other than the pinned one — a newer pre-release, or a
staged build — pass it explicitly:

```bash
# A different released Yano version: the ZIP still resolves from its release.
./gradlew clean build -PyanoVersion=<released-yano-version> -PskipSigning=true

# A staged Maven repository plus an exact ZIP.
./gradlew clean build \
  -PyanoVersion=<staged-yano-version> \
  -PyanoRepository=/absolute/path/to/yano-staging \
  -PyanoJvmDist=/absolute/path/to/yano-<build-identity>.zip \
  -PskipSigning=true
```

The Maven version and the JVM ZIP identity must match exactly. `verifyYanoInputs`
rejects a base ZIP whose root directory, JAR implementation version, or
distribution manifest disagrees with `yanoVersion`. Snapshot and locally staged
versions never fall back to a GitHub release asset and must supply
`yanoJvmDist`.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Unsupported class file major version` or a Java-version error | Yano X requires Java 25. Point `JAVA_HOME` at a 25 JDK. |
| The build tries to reach Maven Local and fails | `mavenLocal()` is disabled unless `-PuseMavenLocal=true`. That flag belongs to the contributor track; you should not need it. |
| A missing release asset for `yanoVersion` | You are on a SNAPSHOT or staged Yano version. Supply `-PyanoJvmDist` with the exact matching ZIP. |
| `verifyJvmOnlyBuild` fails | Something introduced a native-image build or distribution task. Yano X is JVM-only by decision. |
| A native distribution is rejected by `appchain doctor` | Yano X plugins target the JVM host. Use the `yano-x-jvm` archive. |

For the full matrix of build tasks and verification gates, see
[Build and test](https://github.com/bloxbean/yano-x/blob/main/docs/BUILD_AND_TEST.md)
and
[Build distributions](https://github.com/bloxbean/yano-x/blob/main/docs/BUILD_DISTRIBUTIONS.md).
