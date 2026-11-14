# Coordinated prerelease inputs

The existing `build.yml` workflow has a `framework-stage` manual scope for the
issue #245 bootstrap. Supply a successful Yano foundation staging run ID and full
commit using `yano_inputs_run_id` and `yano_inputs_commit`. The input preparer
verifies repository, workflow, event, successful conclusion, source commit,
manifest and SHA-256 inventory; it exports independently pinned `nodeApiVersion`
and `hostApiVersion`. It does not require the removed `yano-core-api` aggregate.

Framework staging tests and publishes only the inventoried framework/proof
modules into an Actions artifact, including verified foundation Maven inputs.
It never checks out or runs a sibling repository. Yano consumes that artifact to
produce the full host ZIP. Normal X jobs then consume the successful full-host
run/commit with `scope=all`. See Yano's
[coordinated input sequence](https://github.com/bloxbean/yano/blob/main/.github/COORDINATED_INPUTS.md).

To make ordinary branch CI consume a successful full-host run, commit
`.github/ci-input-pins.json`:

```json
{
  "host":{"runId":"<successful-full-host-run>","commit":"<full-Yano-commit>"},
  "foundation":{"runId":"<successful-foundation-run>","commit":"<full-Yano-commit>"}
}
```

Explicit run/commit inputs take precedence; partial pins are rejected. Do not
commit placeholders. No pin means normal published Maven/ZIP resolution.
Qualification artifacts expire after 90 days; switch to published dependency
versions before expiration. This does not publish an intermediate Yano release.

The normal commit-build job also builds the standalone plugin template against
its selected host version and the newly staged X version, exercising the real
catalog probe and manifest check. Missing/blank template inputs have a separate
configuration test.

Run the input self-test with mocked artifact transport and tiny Gradle property
probes (the wrapper may bootstrap Gradle; no repository publication occurs):

```bash
bash .github/scripts/tests/prepare-yano-inputs-test.sh
```

The branch defaults in `gradle.properties` match the verified full-host inputs;
framework modules read the central node/Host API properties instead of local
candidate fallbacks. Environment and `-P` overrides remain available for older
compatible hosts. The frozen minimum-host matrix continues to use its explicit
input manifest and unchanged fixture.

For a clean local checkout, authenticate `gh`, prepare the same pinned inputs,
then use the ordinary staging/build sequence (Java 25):

```bash
inputs_env="$(mktemp)"
GITHUB_WORKSPACE="$PWD" GITHUB_ENV="$inputs_env" \
  bash .github/scripts/prepare-yano-inputs.sh
while IFS='=' read -r name value; do export "$name=$value"; done < "$inputs_env"
./gradlew publishAllPublicationsToInternalRepository -PskipSigning=true --no-parallel
./gradlew build -PskipSigning=true --no-parallel
```

This stages only this checkout's X publications locally; upstream host inputs
come from the verified artifact. No Maven Local or sibling checkout is used.

Current verified sequence:

- [Yano foundation 43605316740](https://github.com/bloxbean/yano/actions/runs/43605316740),
  commit `272157da2cb368fc2b7fc4fd0f8b9d68a8f23340`.
- [X framework 43607120836](https://github.com/bloxbean/yano-x/actions/runs/43607120836),
  commit `2fc43e75944ba9c52b7a8a763155ea851801c23a`.
- [Yano full host 43608923020](https://github.com/bloxbean/yano/actions/runs/43608923020),
  commit `7823bfe21bc8079af1b813199271dbc2d3169ab9`, host/node/Cardano
  `0.1.0-pre20-7823bfe21`, retaining Host API `1.0.0-272157da2` unchanged.

All three runs succeeded. Full-host staging includes the actual packaged JVM
catalog smoke and verifies that rebuilding the Host API did not change the
foundation JAR bytes or overwrite its original publication metadata. The normal
X preparer downloaded this host artifact and verified its complete checksum
inventory locally before the host pin was committed.
