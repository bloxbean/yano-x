# ADR-011.2 plugin conformance fixture

This build-only bundle (`org.yanoproject.fixture.plugin-conformance`) covers
the `NodePlugin` lifecycle, the eleven typed X app-chain plugin SPIs and the
Yano `health`/`metrics` kinds without peers or external work. It moved from
Yano (`appchain/fixtures/appchain-plugin-conformance`) with ADR-064; Yano keeps
its own X-free fixture for the built-in kinds.

Its schema-v1 manifest declares the host's current plugin API range
(`PluginApiVersion`; major `3`, `minLevel` `14` at the time of writing). The
X kinds are not Yano built-ins: a host accepts them only when the X framework
descriptors (`META-INF/yano/contribution-kinds/<owner>.json`) and bindings are on its class
path. A bare Yano rejects this bundle with "contribution kind is not supported".

- The JVM test opens the fixture JAR through `PluginRuntimeEnvironment` with the
  X framework on the host class path and constructs every product through the
  catalog-owned provider registry.
- Every fixture factory poisons its provider-callback TCCL before returning.
  The first callback on each returned product poisons it again, and every
  later callback rejects a missing TCCL. Catalog facades restore the caller
  and reinstall the plugin loader at both boundaries; a raw returned product
  leaks the first poison and fails its next callback. The tests therefore
  prove product mediation, not merely factory mediation.
- `NativePluginConformanceVerifier` verifies only its own catalog-managed
  `NodePlugin` lifecycle and logs exactly one marker:

  ```text
  ADR-011.2 node-plugin conformance activated through catalog
  ```

  It never resolves another SPI and no fixture-specific registry backdoor is
  exposed.

The fixture is never published and is absent from stock artifacts. The packaged
integrated-distribution smoke loads it from an external plugin directory.

For multi-node operations tests, the same fixture JAR can expose node-local
health outcomes with `-Dyano.plugin.conformance.health-mode=UP|DEGRADED|DOWN|HANG`.
The default is `UP`. `HANG` deliberately ignores interruption while sampling;
use it only in a disposable process and terminate that process forcibly after
the timeout behavior is verified. Selector text never becomes a metric label,
provider identity, or public check identity.

```bash
./gradlew :fixtures:appchain-plugin-conformance:test
```
