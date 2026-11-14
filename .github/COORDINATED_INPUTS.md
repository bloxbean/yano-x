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

Run the input self-test without network calls or publication:

```bash
bash .github/scripts/tests/prepare-yano-inputs-test.sh
```
