# Observation qualification fixture (preview)

This helper prepares configuration only. It does not launch processes, copy
chainstate, submit Cardano transactions, bootstrap anchors, or mark any
qualification gate passed. The full qualification matrix remains in Yano
ADR-037 and its observation qualification runbook.

First build and verify the exact host Maven inputs and ordinary JVM ZIP, then
the matching Yano X distribution. Extract both into a dedicated artifact area.
Use the plugin directory from that tested Yano X distribution. Do not point
the helper at a source build's loose classes or an existing deployment.

```bash
./gradlew :tooling:devtools:prepareObservationQualification \
  -PyanoVersion=<exact-host-version> \
  -PqualificationDirectory=/absolute/path/to/new-qualification \
  -PqualificationHostDirectory=/absolute/path/to/extracted-host \
  -PqualificationPluginDirectory=/absolute/path/to/extracted-yano-x/plugins \
  -PqualificationHttpBase=18070 -PqualificationN2nBase=18337
```

Supply the normal exact staging repository option, or explicit
`-PuseMavenLocal=true` for already published local inputs. The host distribution
marker must match `yanoVersion`. This marker check does not replace artifact
checksum/provenance and packaged plugin checks.

The target must not exist. Preparation creates a private POSIX directory,
five node configuration files, and a private reporter/API-key file. Every
signing file is mode 0600 and no secrets are printed. `qualification.json`
contains only public pins and settings. Never publish `node.properties` or
`reporters.private.properties`, and never regenerate an existing identity to
work around a startup failure.

The allow-list is the transitive dependency set read from the packaged stdlib
manifest, not just the stdlib ID. Missing dependencies fail before creating
the target. Normal runtime catalog/version/provider checks still apply.

The fixture uses five fresh validator keys with `q=4, f=1`, rotating proposers,
five independent test reporter keys, the ADA/USD reference plugin's three
synthetic source groups, authenticated state, and a fresh genesis ID. These are
synthetic price claims, not statements about an actual market. HTTP listens on
loopback; check both five-port ranges are free before starting any process.
Effects and anchors are explicitly disabled; no spending key is requested.
App-chain API-key authentication is explicitly enabled. Membership is governed
from genesis so the qualification can exercise approved membership epochs.

Each node has separate `chainstate` and `appchain-chainstate` paths. Seed the
former only from an offline-consistent copy or perform a dedicated clean sync.
Never copy a live RocksDB store or modify the retained source. Start the exact
host `yano.jar` with `-Dquarkus.profile=preprod` and
`-Dquarkus.config.locations=<absolute-node-properties>` from that node's directory.
Record the precise JVM command, artifact hashes, ports, PIDs and log paths.
The configuration selects `praos-ledger`, body validation `none`, and compatible
operational-certificate counters. Do not describe this as full Cardano
transaction/script validation. Verify actual L1 progress and nonce restoration
before treating a node as part of a Preprod experiment.

Before traffic, compare runtime identities against the public fixture pins,
including enabled observation profile and fault bound. After traffic, verify
same-height roots and certified SDK proofs across nodes. Finality context pins
must be independently derived for the active membership/height; an untrusted
proof response is never its own trust anchor. Process restarts, report faults,
membership transition, cadence/soak and the operator recovery drill still need
explicit evidence; successful configuration generation proves none of them.

## First certified round

`ObservationQualificationBaseline` is a one-shot driver for a pristine fixture:

```bash
java -cp '/absolute/path/to/yano-x/tools/yano-appchain/lib/*' \
  com.bloxbean.cardano.yano.appchain.devtools.ObservationQualificationBaseline \
  /absolute/path/to/qualification
```

It derives the effective genesis from the base seed and mandatory authenticated
index profiles using the public codecs. It does not trust a node-supplied
genesis/context as its own pin. It verifies certified subscription and round
proofs before signing, durably journals four test reporters' claims for three
synthetic sources, and requires identical certified result proofs across all
five nodes. No wake hint or Cardano transaction is submitted. The result file
uses create-new semantics, and a partial failure preserves state and journals
for inspection; do not reset the chain to hide a failure.

The baseline is distinct from live-L1 qualification. Record L1 progress and
validation-start activation separately: historical catch-up before the pinned
Conway validation checkpoint is not evidence of post-checkpoint validation.
