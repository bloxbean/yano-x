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
`framework/{api,config,core}/build/test-results/test` XML and
`framework/core/build/reports/framework-boundary.json` with the CI run.

The first frozen Host API will be the oldest supported host for this feature.
Once frozen, run this gate against that unchanged artifact and each newer
compatible API, then run the same packaged X bundle on oldest/current hosts.
That packaged matrix is still outstanding; this core-only gate does not replace
it. Do not regenerate the baseline with each framework release.
