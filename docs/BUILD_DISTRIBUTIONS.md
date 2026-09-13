# Build Yano X distributions

Yano X produces JVM extensions only. Yano continues to publish its ordinary
JVM and GraalVM distributions; no special base ZIP exists for Yano X.

## Inputs

The default build creates and verifies the release ZIP:

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
their internal bundle publications. This keeps a from-scratch `clean build`
self-contained while `verifyArtifactInventory` checks that every runtime bundle
still has the expected independent publication.

Bundles leave out every class and resource the host `yano.jar` already
provides (`gradle/plugin-bundle-host-classes.gradle`). Yano loads plugins
parent-first, so those copies were never loaded. `META-INF` metadata and
libraries a bundle relocates into its own namespace always stay. A bundle is
therefore tied to the Yano release it was built against.

## Outputs

See [Download a Yano X release](RELEASE_DOWNLOADS.md) for the user-facing
quickstarts.

The build writes one release archive,
`distribution/jvm/build/distributions/yano-x-jvm-<version>.zip`. It overlays
the Yano X runtime bundles on the supplied ordinary Yano JVM ZIP and includes
both Yano and Yano X identity manifests:

- `plugins/` holds the conflict-free default bundle set and `optional-plugins/`
  the alternative implementations. `yano-x-plugin-pack-v1.json` lists every
  bundle with its checksum.
- `tools/` holds the command-line tools, including the deployment CLI under
  `tools/yano-deploy` with its schema, documentation, and mixed-provider
  example. The tools share `tools/lib`; each launcher keeps its exact classpath,
  so a tool loads only its own jars.
- `studio/` holds the static App-Chain Studio site.
- `examples/showcase/` holds the local multi-node showcase. Its `yano/` home
  carries only the demo configuration; `showcase.sh` links everything else back
  to the distribution root on first run and never edits the distribution's own
  `config/`.
- `config/application-devnet.yml` (also in the showcase's `yano/config/`) turns
  off the devnet L1 history projection. Yano's JVM ZIP ships DuckLake extensions
  for Linux x64 only ([bloxbean/yano#137](https://github.com/bloxbean/yano/issues/137));
  remove the overlay from `distribution/jvm/config/` once Yano fixes that.

The archive includes the repository `LICENSE` and a normalized CycloneDX 1.6
SBOM at `sbom/yano-x.cdx.json`. The release task fills the MIT declaration for
repository-owned components and fails if an external Maven component lacks
license metadata. It also preserves the base host's license as `LICENSE.yano`
and its independent `sbom/yano.cdx.json` inventory.

Both `build` and `distributionCheck` run `showcaseDistributionContract` against
the extracted archive. With Java 25, Python 3, `curl`, and `jq` installed:

```bash
unzip yano-x-jvm-<version>.zip
cd yano-x-jvm-<version>/examples/showcase
./showcase.sh doctor
./showcase.sh quickstart --profile light --nodes 3 --instance demo
./showcase.sh status --instance demo
./showcase.sh ui --instance demo
./showcase.sh stop --instance demo
```

The light profile runs a self-contained local devnet; `stop` preserves instance
data. See `examples/showcase/DEMO_SHOWCASE.md` for scenarios and restart
operations.

All 18 runtime bundles remain independently published and are represented in
the manifest. The default distribution activates 17. The eUTxO ZK runtime is
optional because it and the standard eUTxO runtime intentionally own the same
`app-state-machine/eutxo-ledger` contribution. Operators select exactly one of
those two implementations on every member; copying both into `plugins/` is a
hard catalog error.

`verifyYanoInputs` rejects a base ZIP whose root, JAR implementation version,
or distribution manifest differs from `yanoVersion`. The final distribution
check also rejects missing, duplicate, or unexpected plugin bundles, a tool
launcher whose classpath is missing from `tools/lib`, and any native executable
in the archive. Manifest generation rejects a default
selection with unresolved bundle dependencies or duplicate contribution
identities before an archive is created. Snapshot and locally staged versions
must supply `yanoJvmDist`; they never fall back to a GitHub release asset.

Yano X does not provide a native-image build. A future native extension model
requires a separate architecture decision and build-time composition contract.
