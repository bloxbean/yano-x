# Supported-host candidate fixture

This client was compiled once against the first supported **unpublished candidate** host: Yano/foundation `0.1.0-245.5`, Host API `1.0.0-245.4`, and X `0.1.0-245.9`. It is not a claim of a public release or support for pre-Host-API Yano. Baseline and current are genuinely the same host, reported as one deduplicated cell per profile.

`provenance.json` records compiler/source/bytecode and exact compilation-input hashes, plus separate frozen host identities and input hashes for both profiles. The fixture is compiled on the lean standalone classpath; the Cardano profile uses only public embedding APIs and records the optional runtime classes' actual loaded origins. `qualification.json` retains compact passing evidence and the SHA-256 of the full local report. No host or framework JAR is stored here.

Run this bytecode unchanged; never implicitly recompile it during qualification:

```sh
python3 qa/framework/host-matrix/run.py run \
  --inputs qa/framework/host-matrix/inputs.example.json \
  --fixture qa/framework/host-matrix/fixtures/supported-host \
  --out /private/tmp/new-supported-host-run
```

The earlier host `.4` / X `.6`–`.7` probe remains under `../first-host`; it predates the final assembly-order contract and cannot replace this baseline. Final coordinated Yano release qualification remains required.
