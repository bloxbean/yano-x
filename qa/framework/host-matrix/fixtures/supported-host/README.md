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

`host-5-and-7-framework-10.json` retains the subsequent four-cell pass against host/foundation `.7` and the same frozen oldest host `.5`, with X `.10`, Yaci `.3`, Host API `.4`, and fixture bytes unchanged.

`host-5-and-8-framework-11.json` records all four profiles passing with X `.11`
against the frozen host `.5` and current host `.8`. The older host still resolves
its historical `yano-core-api`; the current host resolves none. The fixture and
Host API `.4` are unchanged. `core-api-removal-qualification.json` retains the
focused checks, standalone template/catalog check, final distribution check, and
all 16 packaged smoke steps (no skips), with artifact/log hashes. The separate
[Yano cleanup validation report](https://github.com/bloxbean/yano/blob/feat/245-app-ledger-host-api/qa/app-ledger/CORE_API_REMOVAL_VALIDATION.md)
tracks coordinated host qualification; the X pass does not replace that result
or the final comprehensive release cycle.

`host-5-and-8-framework-11-yaci-4.json` records all four profiles passing after
[Yaci's wire-shape review](https://github.com/bloxbean/yaci/pull/245#issuecomment-6877562379):
the unchanged host `.5`/`.8`, framework `.11`, Host API `.4` and fixture run with
Yaci `0.5.0-pre15-app-only-245.4`. No framework or host rebuild was needed. This
new-parser matrix is separate from the retained full package/smoke qualification,
whose exact Yaci input remains `.3`; it does not relabel those earlier packages.

`host-5-and-9-framework-12-public-yaci.json` records all four profiles passing
against oldest host `.5` and current host `.9`, with framework `.12`, unchanged
Host API `.4` and the same frozen fixture. It selects the immutable timestamped
public Yaci snapshot `0.5.0-pre16-f57fc88-20261114.141754-1`; this is verified
byte-for-byte against the catalog's `0.5.0-pre16-f57fc88-SNAPSHOT` core JAR.

`reviewer1-host9-distribution-qualification.json` retains the matching host `.9`
and framework `.12` distribution check and all 16 packaged smoke steps, with no
skips. These include live Cardano anchoring/observations, cross-node roots and
restart/persistence checks. `reviewer1-template-and-boundary-checks.json` records
the isolated template/catalog checks, rejection of mixed framework versions and
rejection of forbidden host dependencies in evidence-contracts publication
metadata. See Yano's [qualification record](https://github.com/bloxbean/yano/blob/feat/245-app-ledger-host-api/qa/app-ledger/PHASE2_VALIDATION.md#published-yaci-snapshot-qualification)
for coordinated runtime, public snapshot and clean CI input qualification.
These results do not relabel historical native/Haskell runs or replace the one
final comprehensive release-qualification cycle.
