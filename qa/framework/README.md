# Independent framework gate (#245)

`./gradlew frameworkCheck --rerun-tasks` runs API wire-vector, configuration and
engine tests and resolves the production core closure. `verifyFrameworkBoundary`
rejects Yano implementation artifacts and Cardano settlement SDKs even when pulled
in transitively. Its JSON report includes coordinates and SHA-256 for every input
JAR. API model/crypto compatibility dependencies remain allowed; this is not a
claim that the legacy observer SPI has no Cardano types.

For the coordinated unpublished candidates, supply exact artifact repositories:

```sh
./gradlew frameworkCheck --rerun-tasks --no-parallel \
  -PyanoRepository=file:///absolute/foundation-maven-repository \
  -PnodeApiVersion=EXACT_NODE_API_VERSION \
  -PhostApiVersion=EXACT_HOST_API_VERSION \
  -I qa/framework/artifact-inputs.gradle \
  -PappLedgerYaciRepository=file:///absolute/yaci-maven-repository \
  -PappLedgerYaciVersion=EXACT_YACI_VERSION
```

No checkout or build invocation in another repository is required. Local staging
is development evidence, not a published compatibility baseline. Keep the
`framework/{api,cardano-api,config,core,host-adapter,cardano,cardano-onchain}/build/test-results/test` XML and
`framework/core/build/reports/framework-boundary.json` with the CI run.

The first frozen Host API will be the oldest supported host for this feature.
Once frozen, run this gate against that unchanged artifact and each newer
compatible API, then run the same packaged X bundle on oldest/current hosts.
The [packaged host matrix](host-matrix/README.md) runs a frozen client against
actual thin host artifacts; the core-only gate does not replace it. Do not regenerate the baseline with each framework release.

The core suite includes `AppOnlyWireTest`: real TCP handshake 101, signed messages
on protocol 100, persisted block bytes over 103, and keepalive. It explicitly
checks transaction/Plutus classes are absent, so an integrated Yano classpath
cannot accidentally mask a dependency leak. Optional Cardano contracts have a
separate test task. Native MPF proof encodings and snapshot/reopen checks remain
on the lean core classpath; no format or hashing algorithm changed.


The first development baseline uses Yano `0.1.0-245.4` and Host API
`1.0.0-245.4`. The retained fixture was compiled once with X `.6`, then passed
unchanged with X `0.1.0-245.7`: three-node TCP consensus, late joining, threshold
certificates, commitments/evidence, stop/start, reopen and no L1 state. Required
framework activation works with optional plugins disabled. Loaded class origins
are checked inside the unpacked Maven-only package, with no Cardano runtime root.
Baseline/current are the same first-supported host and are reported as one cell;
this is not a claim that a pre-Host-API Yano release supports the framework.

All candidate artifacts are unpublished development inputs. Freeze the first
published compatible host and its exact inputs during the coordinated release;
ordinary later X releases reuse that baseline and the unchanged fixture. Keep
all final public dependency versions truthful, including the upstream Yaci
release; the explicit QA candidate override is not a production dependency pin.
