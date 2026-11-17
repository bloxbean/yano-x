# Independent framework gate (#245)

`./gradlew frameworkCheck --rerun-tasks` runs API wire-vector, configuration and
engine tests and resolves the production core closure.
`verifyFrameworkPackageOwnership` checks the actual module JARs for their assigned
`org.yanoproject.x.framework.*` packages and rejects duplicate definitions; its
report is `build/reports/framework-package-ownership.json`. `verifyFrameworkBoundary`
rejects Yano implementation artifacts and Cardano settlement SDKs even when pulled
in transitively. Its JSON report includes coordinates and SHA-256 for every input
JAR. API model/crypto compatibility dependencies remain allowed; this is not a
claim that the legacy observer SPI has no Cardano types.

For the coordinated unpublished candidates, supply exact artifact repositories:

```sh
./gradlew frameworkCheck --rerun-tasks --no-parallel \
  -PyanoRepository=file:///absolute/yano-maven-repository \
  -PyanoVersion=EXACT_YANO_VERSION
```

Framework tests follow their implementation owners: generic engine/store/effects
and format tests are in `framework/core`; Cardano observer/anchor tests are in
`framework/cardano`. Since ADR-064, host assembly, lifecycle, catalog and REST
tests of the application ledger also live in X and consume the published Yano
host. The core's `java-test-fixtures` classifier supplies only package-private
construction/setup/fault-injection helpers to framework tests; it is not a
runtime API or module.

`frameworkCheck` needs the external `yano-node-api`, `yano-cardano-api` and
`yano-host-api`, all at the one `yanoVersion`, but does not resolve
`yano-runtime`. `:framework:cardano:integrationTest` separately runs
`L1DeliveryLoopTest` and `ScriptAnchorServiceTest` against the exact selected host
runtime, using the checksum-documented protocol parameters under
`framework/cardano/src/integrationTest/resources/cardano-fixtures`. Normal
`check` includes that integration task. Run it after matching namespace-compatible
host artifacts are available:

```sh
./gradlew :framework:cardano:integrationTest --no-parallel \
  -PyanoRepository=file:///absolute/yano-maven-repository \
  -PyanoVersion=EXACT_YANO_VERSION
```

The catalog resolves published Yaci `0.5.0-pre16-f57fc88-SNAPSHOT` from the
BloxBean snapshot repository, including the merged app-only handshake changes.
A Yaci checkout or local version override is no longer required. The optional
`artifact-inputs.gradle` is reserved for testing another explicit candidate.

No checkout or build invocation in another repository is required. Local staging
is development evidence, not a published compatibility baseline. Keep the
`framework/{api,cardano-api,config,core,host-adapter,cardano,cardano-onchain}/build/test-results/test` XML and
`framework/core/build/reports/framework-boundary.json` with the CI run.

ADR-064 makes Host API 1.2 the supported baseline: Yano no longer depends on X,
and X activates through `yano.plugins.required`, assembly callbacks and its own
contribution-kind descriptors. Run this gate against the first ADR-064 host and
each newer compatible API, then run the same packaged X bundle on oldest/current
hosts.
The [packaged host matrix](host-matrix/README.md) runs a frozen client against
actual thin host artifacts; the core-only gate does not replace it. Do not regenerate the baseline with each framework release.

The core suite includes `AppOnlyWireTest`: real TCP handshake 101, signed messages
on protocol 100, persisted block bytes over 103, and keepalive. It explicitly
checks transaction/Plutus classes are absent, so an integrated Yano classpath
cannot accidentally mask a dependency leak. Optional Cardano contracts have a
separate test task. Native MPF proof encodings and snapshot/reopen checks remain
on the lean core classpath; no format or hashing algorithm changed.

All candidate artifacts are unpublished development inputs. Freeze the first
published compatible host and its exact inputs during the coordinated release;
ordinary later X releases reuse that baseline and the unchanged fixture. Keep
all final public dependency versions truthful, including the upstream Yaci
release; the explicit QA candidate override is not a production dependency pin.

## Host API 1.2 baseline

The frozen fixtures that modelled hosts embedding X (`first-host`,
`supported-host`, `namespace-host`) were retired with ADR-064. A new Host API 1.2
baseline fixture is recorded once with `host-matrix/run.py prepare` from the first
released (non-SNAPSHOT) Yano version that contains Host API 1.2, and then reused
unchanged. No baseline fixture is committed until that Yano release exists: every
Yano module, `yano-host-api` included, is published at the one Yano version, and
the matrix accepts only immutable Yano versions.
