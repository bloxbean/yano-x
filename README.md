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

- `yano-x-plugin-pack-<version>.zip`, containing 17 conflict-free default
  bundles under `plugins/`, the alternative eUTxO ZK runtime under
  `optional-plugins/`, and a checksummed manifest covering all 18 independently
  versioned runtime plugin bundles.
- `yano-x-jvm-<version>.zip`, containing the standard Yano JVM distribution,
  the same default and optional plugin layout, and Yano/Yano X identity
  manifests.

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

Release rehearsal must disable Maven Local and pin every input explicitly:

```bash
./gradlew clean check distributionCheck \
  -Pversion=<yano-x-version> \
  -PinternalRepository=/absolute/path/to/yano-x-staging \
  -PyanoRepository=/absolute/path/to/yano-staging \
  -PyanoVersion=<staged-yano-version> \
  -PyanoJvmDist=/absolute/path/to/yano-<build-identity>.zip \
  -PuseMavenLocal=false
```

Both release ZIPs contain `LICENSE` and a normalized CycloneDX 1.6 SBOM under
`sbom/`. Distribution verification rejects missing license metadata for any
external Maven component. The combined JVM ZIP also preserves Yano's license as
`LICENSE.yano` and its host SBOM as `sbom/yano.cdx.json`.

The default plugin directory is an activatable selection, not an indiscriminate
copy of every published alternative. The standard eUTxO runtime and the eUTxO
ZK runtime both intentionally provide `app-state-machine/eutxo-ledger`; to use
the ZK implementation, remove the standard eUTxO ledger bundle and copy the ZK
runtime bundle from `optional-plugins/` into `plugins/` on every member. Validate
the resulting set with `tools/yano-plugins/bin/yano-plugins validate plugins/*.jar`.

See [Build and test](docs/BUILD_AND_TEST.md),
[distributions](docs/BUILD_DISTRIBUTIONS.md), and the
[app-chain documentation index](docs/appchain/README.md) for the independent
Yano X workflows. The Java package namespace remains
`com.bloxbean.cardano.yano.appchain.*`; the repository split does not rename
the app-chain technical domain.
