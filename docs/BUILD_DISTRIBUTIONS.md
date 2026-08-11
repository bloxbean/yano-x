# Build Yano X distributions

Yano X produces JVM extensions only. Yano continues to publish its ordinary
JVM and GraalVM distributions; no special base ZIP exists for Yano X.

## Inputs

Use the same exact Yano version for Maven dependencies and the base JVM ZIP.
For local refactoring, publish and package Yano locally:

```bash
# In the Yano repository.
./gradlew publishToMavenLocal :app:yanoDistZip \
  -PskipSigning=true --no-parallel
```

Then assemble and verify Yano X:

```bash
./gradlew distributionCheck \
  -Pversion=<yano-x-version> \
  -PyanoVersion=<published-yano-version> \
  -PyanoJvmDist=/absolute/path/to/yano-<build-identity>.zip \
  -PuseMavenLocal=true --offline
```

## Outputs

The outputs are written under `distribution/jvm/build/distributions`:

- `yano-x-plugin-pack-<version>.zip` contains the dependency-complete runtime
  plugin bundles and their checksummed manifest.
- `yano-x-jvm-<version>.zip` overlays those bundles on the supplied ordinary
  Yano JVM ZIP and includes both Yano and Yano X identity manifests.

`verifyYanoInputs` rejects a base ZIP whose root, JAR implementation version,
or distribution manifest differs from `yanoVersion`. The final distribution
check also rejects missing, duplicate, or unexpected plugin bundles and any
native executable in the JVM archive.

Yano X does not provide a native-image build. A future native extension model
requires a separate architecture decision and build-time composition contract.
