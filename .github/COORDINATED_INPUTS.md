# Coordinated prerelease inputs

Yano X builds against Yano artifacts only (ADR-064 D10). Yano never consumes a
Yano X artifact, so the issue #245 bootstrap, in which an X `framework-stage`
run fed the Yano host build, is obsolete. Yano's `stage-consumer-inputs.yml`
stages its Maven publications and matching JVM ZIP; X branch CI consumes that
run with `scope=all`, `yano_inputs_run_id` and `yano_inputs_commit`. The input
preparer verifies repository, workflow, event, successful conclusion, source
commit, manifest and SHA-256 inventory, and exports independently pinned
`yanoVersion`, `nodeApiVersion` and `hostApiVersion`.

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

The verified pins are recorded in `.github/ci-input-pins.json`. Retired #245
sequences (foundation, X framework and full-host runs) and the frozen
host-matrix fixtures that modelled hosts embedding X are historical; the
Host API 1.2 baseline fixture is recorded from the first ADR-064 Yano artifacts.
