# Build Yano X distributions

Yano X produces JVM extensions only. Yano continues to publish its ordinary
JVM and GraalVM distributions; no special base ZIP exists for Yano X.

## Inputs

The default build creates and verifies all three release ZIPs:

```bash
./gradlew build -PskipSigning=true
```

The default Yano version comes from `gradle.properties`; `-PyanoVersion=...`
overrides it. Use `-Pversion=<release-version>` to name the Yano X release artifacts.
`distributionCheck` runs the archive verification tier without the full unit-test suite.

Use the same exact Yano version for Maven dependencies and the base JVM ZIP.
For a released version, Gradle resolves and caches the ordinary JVM ZIP from
the matching GitHub release automatically:

```bash
./gradlew distributionCheck \
  -Pversion=<yano-x-version> \
  -PyanoVersion=<released-yano-version>
```

The release URL convention is
`https://github.com/bloxbean/yano/releases/download/v<version>/yano-<version>.zip`.
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

Yano X runtime bundles are assembled from the exact `shadowJar` outputs used by
their Maven bundle publications. This keeps a from-scratch `clean build`
self-contained while `verifyArtifactInventory` checks that every runtime bundle
still has the expected independent publication.

## Outputs

The outputs are written under `distribution/jvm/build/distributions`:

- `yano-x-plugin-pack-<version>.zip` contains the dependency-complete runtime
  plugin bundles and their checksummed manifest. Its `plugins/` directory is a
  conflict-free default set; alternative implementations live under
  `optional-plugins/`.
- `yano-x-jvm-<version>.zip` overlays those bundles on the supplied ordinary
  Yano JVM ZIP and includes both Yano and Yano X identity manifests. It also
  packages the provider-neutral deployment CLI under `tools/yano-deploy`,
  including its schema, documentation, and mixed-provider example.

Both archives include the repository `LICENSE` and a normalized CycloneDX 1.6
SBOM at `sbom/yano-x.cdx.json`. The release task fills the MIT declaration for
repository-owned components and fails if an external Maven component lacks
license metadata. The combined JVM archive also preserves the base host's
license as `LICENSE.yano` and its independent `sbom/yano.cdx.json` inventory.

The third release artifact is
`examples/showcase/build/distributions/yano-showcase-<version>.zip`.
It includes the JVM distribution, demo plugins, configuration, scripts, and
guides for a local multi-node demo. Both `build` and `distributionCheck` run
`showcaseDistributionContract` against the extracted ZIP. Publish this ZIP
alongside the two main ZIPs in GitHub releases so users need no source checkout
or Gradle installation to try the showcase.

With Java 25, Python 3, `curl`, and `jq` installed, extract it into a new directory:

```bash
unzip yano-showcase-<version>.zip
cd yano-showcase-<version>
./showcase.sh doctor
./showcase.sh quickstart --profile light --nodes 3 --instance demo
./showcase.sh status --instance demo
./showcase.sh ui --instance demo
./showcase.sh stop --instance demo
```

The light profile runs a self-contained local devnet; `stop` preserves instance
data. See the included `DEMO_SHOWCASE.md` for scenarios and restart operations.

All 18 runtime bundles remain independently published and are represented in
the manifest. The default distribution activates 17. The eUTxO ZK runtime is
optional because it and the standard eUTxO runtime intentionally own the same
`app-state-machine/eutxo-ledger` contribution. Operators select exactly one of
those two implementations on every member; copying both into `plugins/` is a
hard catalog error.

`verifyYanoInputs` rejects a base ZIP whose root, JAR implementation version,
or distribution manifest differs from `yanoVersion`. The final distribution
check also rejects missing, duplicate, or unexpected plugin bundles and any
native executable in the JVM archive. Manifest generation rejects a default
selection with unresolved bundle dependencies or duplicate contribution
identities before an archive is created. Snapshot and locally staged versions
must supply `yanoJvmDist`; they never fall back to a GitHub release asset.

Yano X does not provide a native-image build. A future native extension model
requires a separate architecture decision and build-time composition contract.
