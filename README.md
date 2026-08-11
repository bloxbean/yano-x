# Yano X

Yano X is the JVM extension and application ecosystem for Yano.

The build consumes published Yano artifacts and an explicit Yano JVM ZIP. It
does not invoke tasks in, copy sources from, or assume the location of a Yano
checkout.

## Local development

During the repository split, first publish the matching Yano checkout to Maven
Local and create its ordinary JVM ZIP. No special base ZIP is required:

```bash
./gradlew publishToMavenLocal :app:yanoDistZip \
  -PskipSigning=true --no-parallel
```

Then pass the exact published version and ZIP to Yano X:

```bash
./gradlew distributionCheck \
  -PuseMavenLocal=true \
  -Pversion=<published-yano-version> \
  -PyanoVersion=<published-yano-version> \
  -PyanoJvmDist=/absolute/path/to/yano-<build-identity>.zip
```

This produces two reproducible JVM artifacts under
`distribution/jvm/build/distributions`:

- `yano-x-plugin-pack-<version>.zip`, containing the 18 independently
  versioned runtime plugin bundles and their checksummed manifest.
- `yano-x-jvm-<version>.zip`, containing the standard Yano JVM distribution,
  the same plugin bundles under `plugins/`, and Yano/Yano X identity manifests.

Yano X is JVM-only. `verifyJvmOnlyBuild` rejects native-image build or
distribution tasks, while the Yano base ZIP contract records whether its
plugin directory is supported.

`mavenLocal()` is disabled unless `useMavenLocal=true`. A missing ZIP, a ZIP
whose root build identity differs from `yanoVersion`, or Maven artifacts from
another Yano version fail verification.

CI stages Yano X's own publications in an empty, build-scoped Maven repository
using `-PinternalRepository=<path>`. This exercises published POM and bundle
coordinates without depending on global Maven Local state. Set
`-PyanoRepository=<URL-or-path>` when the requested Yano version is in a staging
repository rather than Maven Central; this read-only input is distinct from the
build-scoped Yano X publication repository.

See [Build and test](docs/BUILD_AND_TEST.md),
[distributions](docs/BUILD_DISTRIBUTIONS.md), and the
[app-chain documentation index](docs/appchain/README.md) for the independent
Yano X workflows. The Java package namespace remains
`com.bloxbean.cardano.yano.appchain.*`; the repository split does not rename
the app-chain technical domain.
