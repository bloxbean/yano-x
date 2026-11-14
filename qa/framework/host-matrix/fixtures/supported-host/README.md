# Supported-host candidate fixture

This client was compiled once against the first supported **unpublished candidate** host: Yano/foundation `0.1.0-245.5`, Host API `1.0.0-245.4`, and X `0.1.0-245.9`. It is not a claim of a public release or support for pre-Host-API Yano. The initial qualification used the same baseline and current host, reported as one deduplicated cell per profile.

`provenance.json` records compiler/source/bytecode and exact compilation-input hashes, plus separate frozen host identities and input hashes for both profiles. The fixture is compiled on the lean standalone classpath; the Cardano profile uses only public embedding APIs and records the optional runtime classes' actual loaded origins. `qualification.json` retains compact passing evidence and the SHA-256 of the full local report. No host or framework JAR is stored here.

Run this bytecode unchanged; never implicitly recompile it during qualification:

```sh
python3 qa/framework/host-matrix/run.py run \
  --inputs qa/framework/host-matrix/inputs.example.json \
  --fixture qa/framework/host-matrix/fixtures/supported-host \
  --out /private/tmp/new-supported-host-run
```

The earlier host `.4` / X `.6`–`.7` probe remains under `../first-host`; it predates the final assembly-order contract and cannot replace this baseline. Final coordinated Yano release qualification remains required.

`independent-framework-8.json` records a second actual framework selection on the same host bytes: X `.8` passes both profiles using the unchanged fixture compiled with X `.9`. Together with the `.9` result this tests two distinct framework candidates against one host; it does not imply the client was compiled against `.8` or that either candidate was publicly released.

`independent-framework-10.json` records X `.10` with Yaci `0.5.0-pre15-app-only-245.3` passing both profiles against the unchanged oldest supported candidate host `.5`, Host API `.4`, and the same `.9`-compiled fixture.

`host-5-and-6-framework-10.json` records all four profiles passing with X `.10` and Yaci `.3`: the unchanged oldest host/foundation `.5` and the newer `.6`, both using the unchanged Host API `.4` and frozen fixture.
