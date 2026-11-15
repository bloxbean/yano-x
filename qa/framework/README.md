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
  -PyanoRepository=file:///absolute/foundation-maven-repository \
  -PnodeApiVersion=EXACT_NODE_API_VERSION \
  -PhostApiVersion=EXACT_HOST_API_VERSION
```


Framework tests follow their implementation owners: generic engine/store/effects
and format tests are in `framework/core`; Cardano observer/anchor tests are in
`framework/cardano`. Mixed host assembly, lifecycle, catalog and REST tests remain
in Yano and consume the published framework. The core's `java-test-fixtures`
classifier supplies only package-private construction/setup/fault-injection
helpers to Cardano and Yano tests; it is not a runtime API or module.

`frameworkCheck` needs the external foundation and Host API, but does not resolve
`yano-runtime`. `:framework:cardano:integrationTest` separately runs
`L1DeliveryLoopTest` and `ScriptAnchorServiceTest` against the exact selected host
runtime, using the checksum-documented protocol parameters under
`framework/cardano/src/integrationTest/resources/cardano-fixtures`. Normal
`check` includes that integration task. Run it after matching namespace-compatible
host artifacts are available:

```sh
./gradlew :framework:cardano:integrationTest --no-parallel \
  -PyanoRepository=file:///absolute/host-maven-repository \
  -PyanoVersion=EXACT_HOST_VERSION \
  -PnodeApiVersion=EXACT_NODE_API_VERSION \
  -PhostApiVersion=EXACT_HOST_API_VERSION
```

The catalog resolves published Yaci `0.5.0-pre16-f57fc88-SNAPSHOT` from the
BloxBean snapshot repository, including the merged app-only handshake changes.
A Yaci checkout or local version override is no longer required. The optional
`artifact-inputs.gradle` is reserved for testing another explicit candidate.

No checkout or build invocation in another repository is required. Local staging
is development evidence, not a published compatibility baseline. Keep the
`framework/{api,cardano-api,config,core,host-adapter,cardano,cardano-onchain}/build/test-results/test` XML and
`framework/core/build/reports/framework-boundary.json` with the CI run.

The namespace migration requires the first namespace-compatible host implementation
as the new supported baseline; the Host API contract itself is unchanged.
The candidate fixture below is now frozen. Run this gate against that unchanged
artifact and each newer compatible API, then run the same packaged X bundle on
oldest/current hosts.
The [packaged host matrix](host-matrix/README.md) runs a frozen client against
actual thin host artifacts; the core-only gate does not replace it. Do not regenerate the baseline with each framework release.

The core suite includes `AppOnlyWireTest`: real TCP handshake 101, signed messages
on protocol 100, persisted block bytes over 103, and keepalive. It explicitly
checks transaction/Plutus classes are absent, so an integrated Yano classpath
cannot accidentally mask a dependency leak. Optional Cardano contracts have a
separate test task. Native MPF proof encodings and snapshot/reopen checks remain
on the lean core classpath; no format or hashing algorithm changed.


The historical development probe uses Yano `0.1.0-245.4` and Host API
`1.0.0-245.4`. The retained fixture was compiled once with X `.6`, then passed
unchanged with X `0.1.0-245.7`: three-node TCP consensus, late joining, threshold
certificates, commitments/evidence, stop/start, reopen and no L1 state. Required
framework activation works with optional plugins disabled. Loaded class origins
are checked inside the unpacked Maven-only package, with no Cardano runtime root.
That probe used the same first candidate for baseline/current, reported as one cell;
this is not a claim that a pre-Host-API Yano release supports the framework.

That historical probe predates both the final assembly-order contract and the
namespace migration. The subsequent old-package candidate used host/foundation `.5` and unchanged Host API `1.0.0-245.4`, with
X compiled against node API `.4`. The matrix additionally constructs and closes
the actual optional Cardano assembly in a separate packaged JVM.

These retained old-package fixtures are immutable historical evidence and do not
qualify the migrated namespace. The separate namespace fixture below was prepared
once against the new host; reuse it unchanged for subsequent compatible X candidates.

All candidate artifacts are unpublished development inputs. Freeze the first
published compatible host and its exact inputs during the coordinated release;
ordinary later X releases reuse that baseline and the unchanged fixture. Keep
all final public dependency versions truthful, including the upstream Yaci
release; the explicit QA candidate override is not a production dependency pin.

## Namespace-compatible baseline

The [new namespace fixture](host-matrix/fixtures/namespace-host/README.md)
passes standalone three-node protocol 100/103 communication, late join and restart,
plus Cardano construction against CI host `0.1.0-pre20-bf45507fd` and framework
`0.1.0-pre6-a3ef67b36`, with unchanged foundation/Host API `272157da2`.
Baseline and current are the same first candidate, honestly deduplicated.
Earlier old-package fixtures remain historical evidence; never regenerate them.
