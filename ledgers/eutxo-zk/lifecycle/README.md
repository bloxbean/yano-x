# EUTxO ZK lifecycle tooling

> **Excluded from the build.** Like every module under `ledgers/eutxo-zk`, this
> module is left out of `settings.gradle`: Gradle does not compile, test, or
> publish it, and no distribution includes it. See the
> [parent README](../README.md) for what restoring it requires.

Project-aware, restart-safe lifecycle tooling for the experimental validity
product: key generation, proving, relay, and recovery, with secret proving
material kept in an owner-only directory. See
[D4 packaged testnet lifecycle](../D4_LIFECYCLE.md).

## Artifact

Not published.

## Key contents

Package `org.yanoproject.x.eutxo.zk.lifecycle`: `EutxoValidityLifecycle` and
its command-line facade `EutxoValidityLifecycleCli`, whose usage text names
`./yano.sh appchain validity ...` commands. No launcher wires that command
while the module is excluded.

## Dependencies

API dependency on `:ledgers:eutxo-zk:contracts`; implementation dependencies on
the `eutxo-zk` client, prover, zeroj, and onchain modules,
`:ledgers:eutxo:bridge-cardano`, `:ledgers:eutxo:bridge-onchain`, and
Jackson.

## Build

None while the module is excluded. After restoring the build entries listed
in the [parent README](../README.md), the project path is `:ledgers:eutxo-zk:lifecycle`.
[Build and test Yano X](../../../docs/BUILD_AND_TEST.md) describes the
required Yano version arguments.
