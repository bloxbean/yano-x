# Yano X BOM

Gradle `java-platform` that aligns every Maven-published Yano X library with
the Yano host it was built against. Applications that consume several Yano X
artifacts import it once and leave the individual versions out.

## Artifact

`org.yanoproject.x:yano-x-bom` (Maven Central and the BloxBean repository).
The BOM is not listed in [`config/artifacts-v1.json`](../config/artifacts-v1.json);
its constraints are generated from that inventory.

## Contents

- `api platform("org.yanoproject:yano-bom:<yanoVersion>")`: the matching Yano
  host BOM, so one import also aligns `yano-node-api`, `yano-host-api`,
  `yano-runtime` and the rest of the host.
- One constraint per inventoried artifact whose publication type is
  `runtime-plugin`, `library`, `test-library` or `onchain-artifact`, except
  modules marked `publishToCentral: false` or aggregated into another artifact.
- Plugin bundle coordinates (`*-bundle`) are deliberately absent: bundles ship
  in the plugin pack and JVM distribution, not on Maven Central.

```gradle
dependencies {
    implementation platform("org.yanoproject.x:yano-x-bom:${yanoXVersion}")
    implementation 'org.yanoproject.x:yano-x-client'
}
```

See [Use as a library](../README.md#use-as-a-library) for repositories and
[modules and artifacts](../docs/site/modules.md) for every coordinate.

## Dependencies / boundaries

The BOM depends only on the published Yano BOM. The direction is Yano X to
Yano only (ADR-064 D1): Yano's own BOM never references Yano X.

## Build & test

```bash
./gradlew :bom:generatePomFileForMavenJavaPublication
./gradlew verifyArtifactInventory
```

`yanoVersion` is required (the POM task fails without it); it defaults to
`gradle.properties`. For a local or staged Yano, add the inputs described in
[Build and test](../docs/BUILD_AND_TEST.md). `verifyPublishedContracts` also
generates this POM.
