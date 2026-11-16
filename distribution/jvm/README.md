# Yano X JVM distribution

The integrated Yano + Yano X application and its release archives (ADR-064 D8).
This module is a small Quarkus application composed from Yano's published
`yano-app-core` and `yano-runtime` with the Yano X framework
([`framework/rest`](../../framework/rest/README.md) and the Cardano activation
root [`framework/cardano`](../../framework/cardano/README.md)). It builds the
integrated `yano.jar` and packages it, with the runtime plugin bundles, tools,
Studio and showcase, on top of the ordinary Yano JVM ZIP.

## Artifact

Not published to Maven. The outputs are GitHub release assets:

- `build/yano.jar`: the integrated application (`:distribution:jvm:quarkusBuild`).
  It reports the Yano version it runs and carries a `Yano-X-Version` manifest attribute.
- `build/distributions/yano-x-jvm-<version>.zip` (`yanoXJvmDistZip`): the Yano
  JVM ZIP with the integrated `yano.jar`, `plugins/`, `tools/`, `studio/`,
  `examples/showcase/`, both identity manifests, `LICENSE` and SBOMs.
- `build/distributions/yano-x-plugin-pack-<version>.zip` (`yanoXPluginPackZip`).
- `yano-x-jvm-<version>-<os>-<arch>.zip` (`yanoXSelfContainedDistZip`): the same
  archive plus a jlink-trimmed Java 25 runtime. It is not a native image.

## Key contents

- `build.gradle`: the application, the embedded plugin index (logging plugin plus
  the `org.yanoproject.x.app-ledger` root) and `verifyYanoInputs`, which checks that
  the Yano Maven artifacts and the Yano JVM ZIP have the same build identity.
- `plugin-pack.gradle`, `jvm-distribution.gradle`: bundle set, manifests, SBOM
  normalization, archives and `verifyYanoXJvmDistribution`.
- `packaged-plugin-catalog-smoke.gradle`: `packagedJvmPluginCatalogSmoke` starts the
  unpacked archive with the [plugin conformance fixture](../../fixtures/appchain-plugin-conformance/README.md).
- `launcher/yano-appchain.sh`: the `appchain` commands added to Yano's `yano.sh`.
- `config/application-devnet.yml`: the Yano X devnet profile overlay.
- `src/test`: `@QuarkusTest` suites for OpenAPI, domain-route auth, required-root
  startup, configuration projection, catalog packaging and the integrated launcher.

## Dependencies / boundaries

Depends on `:framework:rest` and, at runtime, `:framework:cardano`, which serves both
`yano.mode=cardano` and `yano.mode=app-ledger`. The standalone root is an
alternative and is not composed here. Yano artifacts and the base ZIP are consumed
by exact published version only; this module never invokes tasks in, or copies
sources from, a Yano checkout. Yano X is JVM-only (`verifyJvmOnlyBuild`).

## Build & test

```bash
./gradlew :distribution:jvm:test
./gradlew :distribution:jvm:yanoXJvmDistZip
./gradlew distributionCheck
```

The Yano version, base ZIP (`yanoJvmDist`) and repository inputs are described in
[Build distributions](../../docs/BUILD_DISTRIBUTIONS.md) and
[Build and test](../../docs/BUILD_AND_TEST.md). User-facing download and run
instructions are in [Release downloads](../../docs/RELEASE_DOWNLOADS.md).
