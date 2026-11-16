# EUTxO ZK demo

> **Excluded from the build.** Like every module under `ledgers/eutxo-zk`, this
> module is left out of `settings.gradle`: Gradle does not compile, test, or
> publish it, and no distribution includes it. See the
> [parent README](../README.md) for what restoring it requires.

Optional disposable-devnet scenario for the trusted-prover ZeroJ flow. It
extends the base [EUTxO demo](../../eutxo/demo/README.md) orchestration: the
b16 batch proof is generated and verified before the demo vault pays out. The
step-by-step flow is in [DEVNET_WALKTHROUGH.md](../DEVNET_WALKTHROUGH.md).

## Artifact

Not published. The build file names its POM *Yano App-Chain EUTxO ZK Demo*.

## Key contents

Package `org.yanoproject.x.eutxo.zk.demo`: `EutxoZkDemoScenarioProvider` (an
`EutxoDemoScenarioProvider`) and `EutxoZkDemoWorkflow`.

## Dependencies

Implementation dependencies on `:ledgers:eutxo:demo`,
`:ledgers:eutxo-zk:client`, `:ledgers:eutxo-zk:lifecycle`, and Jackson. The
base demo must never load it: `:ledgers:eutxo:demo:verifyNoZerojDependency`
rejects ZeroJ and `eutxo-zk` artifacts on its classpath.

## Build

None while the module is excluded. After restoring the build entries listed
in the [parent README](../README.md), the project path is `:ledgers:eutxo-zk:demo`.
[Build and test Yano X](../../../docs/BUILD_AND_TEST.md) describes the
required Yano version arguments.

## Notes

The demo vault is explicitly distinct from the production proof-withdrawal
validator, and the setup keys are test-only.
