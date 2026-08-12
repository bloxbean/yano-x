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
  plugin bundles and their checksummed manifest. Its `plugins/` directory is a
  conflict-free default set; alternative implementations live under
  `optional-plugins/`.
- `yano-x-jvm-<version>.zip` overlays those bundles on the supplied ordinary
  Yano JVM ZIP and includes both Yano and Yano X identity manifests.

Both archives include the repository `LICENSE` and a normalized CycloneDX 1.6
SBOM at `sbom/yano-x.cdx.json`. The release task fills the MIT declaration for
repository-owned components and fails if an external Maven component lacks
license metadata. The combined JVM archive also preserves the base host's
license as `LICENSE.yano` and its independent `sbom/yano.cdx.json` inventory.

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
identities before an archive is created.

Yano X does not provide a native-image build. A future native extension model
requires a separate architecture decision and build-time composition contract.
