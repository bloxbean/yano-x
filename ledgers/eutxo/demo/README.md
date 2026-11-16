# EUTxO demo orchestration

Safe, resumable orchestration behind `./yano.sh appchain eutxo demo`: the
disposable ledger and Cardano-bridge scenarios, their workspaces and
journals, and the settlement deployment tooling. User-facing steps are in
[EUTxO demos](../DEMO.md); the product itself is described in the
[EUTxO product family](../README.md).

## Artifact

`org.yanoproject.x:yano-x-eutxo-demo` (application). Not released to Maven
Central; it reaches users through the Yano X JVM distribution, where
`:tooling:devtools` and `:tooling:deployment` embed it.

## Key contents

Package `org.yanoproject.x.eutxo.demo`:

- `EutxoDemoCli` — the `yano appchain eutxo demo` command;
- `EutxoDemoScenarioProvider` with `LedgerDemoScenarioProvider` and
  `BridgeDemoScenarioProvider` (registered in `META-INF/services`), selected
  through `EutxoDemoScenarioRegistry`;
- `EutxoDemoWorkspace`, `EutxoDemoJournal`, and `EutxoDemoCluster` — durable
  workspace state and node lifecycle through the app-chain setup commands;
- `EutxoBridgeDemoWorkflow` and `EutxoExternalDepositWorkflow` — the bridge
  round trip and devnet-scoped external deposits; and
- `SettlementDeployment`, `SettlementBootstrapWorkflow`, and
  `ShowcaseSettlementDemo` — settlement identity deployment and the showcase
  settlement demo.

## Dependencies and boundaries

Depends on `:sdk:client`, `:ledgers:eutxo:contracts`, `:ledgers:eutxo:client`,
`:ledgers:eutxo:bridge-cardano`, `:ledgers:eutxo:bridge-onchain` (compiled
validator resources only), `:framework:cardano-onchain`, Julc's
cardano-client-lib integration, and cardano-client-lib backends.
`verifyNoZerojDependency` (part of `check`) rejects any ZeroJ or `eutxo-zk`
artifact on the runtime classpath.

## Build and test

```bash
./gradlew :ledgers:eutxo:demo:check
```

See [Build and test Yano X](../../../docs/BUILD_AND_TEST.md) for the
required Yano version arguments. The design is
[ADR-UTXO-002](../../../adr/app-layer/utxo/002-unified-eutxo-demo-experience.md).

## Notes

Demo wallets and devnet builders are public test material, not for real funds.
