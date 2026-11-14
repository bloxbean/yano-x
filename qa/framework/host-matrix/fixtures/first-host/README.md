# First supported host fixture

This client was compiled once against immutable, **unpublished candidate** Maven artifacts: Yano `0.1.0-245.4`, Host API `1.0.0-245.4`, and X `0.1.0-245.6`. It is not evidence of compatibility with an older public Yano release. `provenance.json` records every compilation-input coordinate and SHA-256, the compiler, source hash, and unchanged fixture hash. The paths in that evidence identify the original isolated staging inputs; the matrix resolves its own artifacts and verifies their hashes.

The retained bytecode is reused unchanged for later X versions. Normal qualification uses `run`, never `prepare`:

```sh
python3 qa/framework/host-matrix/run.py run \
  --inputs /absolute/host-matrix.json \
  --fixture qa/framework/host-matrix/fixtures/first-host \
  --out /private/tmp/new-host-matrix-run
```

The first supported host is both the oldest supported and current host, so the harness truthfully reports one deduplicated cell. Independently selecting X `0.1.0-245.7` against that same frozen host tests an actual framework upgrade without recompiling this client. This candidate evidence does not replace final coordinated release qualification or establish support for pre-Host-API Yano versions.

Only the client JAR and provenance are retained here; no host or framework JAR is copied into the repository.
