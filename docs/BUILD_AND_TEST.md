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
plugin versions. For a non-SNAPSHOT `yanoVersion`, distribution tasks resolve
and cache `yano-<version>.zip` from the matching `v<version>` release in
`bloxbean/yano`. Set `yanoJvmDist` only to override that release asset with an
exact local or staged ZIP.

The command above is the source-and-test build and does not assemble every
distribution. Gradle's root `build` lifecycle also validates the release
archives. It consumes the same dependency-complete bundle JARs attached to the
runtime plugins' Maven publications, so a clean full build is one invocation:

```bash
./gradlew clean build \
  -PyanoVersion=<published-yano-version> \
  -PskipSigning=true
```

For a release rehearsal, publish every coordinate to a new empty isolated
repository after the clean build:

```bash
./gradlew publishAllPublicationsToInternalRepository \
  -PinternalRepository=/absolute/path/to/empty/yano-x-staging \
  -PyanoVersion=<published-yano-version> \
  -PskipSigning=true
```

For coordinated local or staged Yano development, retain
`-PuseMavenLocal=true` or `-PyanoRepository=<URL-or-path>` as appropriate on
both commands, and add the matching `-PyanoJvmDist` to the clean build.

## Useful scopes

```bash
./gradlew :state-machines:stdlib:test -PyanoVersion=<yano-version>
./gradlew :tooling:devtools:test -PyanoVersion=<yano-version>
./gradlew :tooling:deployment:test -PyanoVersion=<yano-version>
./gradlew integrationTest -PyanoVersion=<yano-version>
./gradlew cryptoTest -PyanoVersion=<yano-version>
```

`verifyArtifactInventory` checks that every module has exactly one declared
artifact identity and that runtime plugins have bundle publications.
`verifyJvmOnlyBuild` rejects accidental native-image tasks in Yano X.

Distribution assembly additionally needs Yano's ordinary JVM ZIP; see
[BUILD_DISTRIBUTIONS.md](BUILD_DISTRIBUTIONS.md).

## Reproduce settlement artifacts

The bundled settlement validators must reproduce with the published Julc
compiler selected in `gradle/libs.versions.toml`. Release builds must keep Maven
Local disabled: a locally republished compiler with the same version can emit
different script bytes and addresses.

For the Yano `0.1.0-pre14` baseline, the compiler is Maven Central
`com.bloxbean.cardano:julc-compiler:0.1.0-pre16`, with JAR SHA-256
`04c9c2c75dfa38e14206b27f1b55def0106aa859a74c96adfb4ca5f6db7c1336`.
`SettlementArtifactBundleTest` compares the bundled templates with a fresh
source compile. The showcase tests also verify that its parameterized scripts
and configuration agree with the derived settlement plan.

Regenerating templates is a script-identity change, even when the Java source
is unchanged. Review the parameterized scripts, addresses, hashes, and genesis
identity together. Updated defaults are for fresh demo deployments; they are
not an in-place migration for an existing settlement chain. Retained deployments
must preserve their pinned scripts and chain identity.
