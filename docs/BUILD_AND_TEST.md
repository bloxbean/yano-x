# Build and test Yano X

Yano X is a Java 25, JVM-only Gradle build. It consumes released Yano Maven
artifacts and never compiles a sibling Yano source checkout.

## Normal build

For a released or staged Yano version:

```bash
./gradlew clean test verifyArtifactInventory verifyJvmOnlyBuild \
  -PyanoVersion=<yano-version>
```

Maven Local is disabled by default. During coordinated local development,
publish the matching Yano version first, then opt in explicitly:

```bash
# Run in the Yano repository.
./gradlew publishToMavenLocal :app:yanoDistZip \
  -PskipSigning=true --no-parallel

# Run in this repository.
./gradlew test verifyArtifactInventory verifyJvmOnlyBuild \
  -PyanoVersion=<published-yano-version> \
  -PuseMavenLocal=true --offline
```

The `yanoVersion` must identify the exact Yano API/runtime line against which
Yano X is being built. `version` independently controls Yano X artifact and
plugin versions.

The command above is the source-and-test build and does not assemble every
distribution. Gradle's root `build` lifecycle also validates the release
archives, so it requires `yanoJvmDist` and a repository containing Yano X's
published module and bundle coordinates. For a clean, from-scratch full build,
use two invocations:

```bash
YANO_X_REPOSITORY=/absolute/path/to/empty/yano-x-staging

./gradlew clean publishAllPublicationsToInternalRepository \
  -PinternalRepository="$YANO_X_REPOSITORY" \
  -PyanoVersion=<published-yano-version> \
  -PuseMavenLocal=true -PskipSigning=true

./gradlew build \
  -PinternalRepository="$YANO_X_REPOSITORY" \
  -PyanoVersion=<published-yano-version> \
  -PyanoJvmDist=/absolute/path/to/yano-<build-identity>.zip \
  -PuseMavenLocal=true -PskipSigning=true
```

Use an empty directory for each clean rehearsal. Do not run `clean` between
the two invocations.

## Useful scopes

```bash
./gradlew :state-machines:stdlib:test -PyanoVersion=<yano-version>
./gradlew :tooling:devtools:test -PyanoVersion=<yano-version>
./gradlew integrationTest -PyanoVersion=<yano-version>
./gradlew cryptoTest -PyanoVersion=<yano-version>
```

`verifyArtifactInventory` checks that every module has exactly one declared
artifact identity and that runtime plugins have bundle publications.
`verifyJvmOnlyBuild` rejects accidental native-image tasks in Yano X.

Distribution assembly additionally needs Yano's ordinary JVM ZIP; see
[BUILD_DISTRIBUTIONS.md](BUILD_DISTRIBUTIONS.md).
