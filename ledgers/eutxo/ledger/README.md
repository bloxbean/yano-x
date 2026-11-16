# EUTxO ledger plugin

The optional `eutxo-ledger` app-chain state machine: a deterministic,
Cardano-shaped EUTxO ledger that accepts canonical Conway transactions under
an immutable ledger profile. The [EUTxO product family](../README.md) README
describes the profile, state, queries, and trust statement.

## Artifact

`org.yanoproject.x:yano-x-eutxo-ledger` (thin jar) and
`yano-x-eutxo-ledger-bundle` (plugin bundle `org.yanoproject.x.eutxo`).
Runtime plugin; not released to Maven Central. The bundle is part of the Yano
X JVM distribution's default plugin set.

## Key contents

Package `org.yanoproject.x.eutxo.ledger`:

- `EutxoStateMachineProvider` / `EutxoStateMachine` — the service-loaded
  `AppStateMachine` for `state:eutxo-ledger`;
- `UtxoTransitionEngine` with `KeyPaymentTransitionEngine` — the pure
  validation-to-mutation boundary;
- `PlutusV3Evaluator` with `ScalusPlutusV3Evaluator` — bounded Plutus V3
  spending validation through Scalus; and
- `EutxoDomainApiProvider` / `EutxoDomainApi` — the read-only explorer
  domain API.

`META-INF/yano/plugins/org.yanoproject.x.eutxo.json` declares the
`app-state-machine` (`eutxo-ledger`) and `domain-api` contributions.

## Dependencies and boundaries

- Compiles against `:framework:api`, cardano-client-lib, Scalus, and CBOR as
  `compileOnly`; the host runtime supplies them. Only
  `:ledgers:eutxo:contracts` is embedded in the bundle.
- `verifyEutxoLedgerArtifactBoundaries` (part of `check`) rejects a bundle
  that embeds host, Cardano, bridge, or ZK classes.
- `verifyEutxoVirtualLedgerDependencyGraph` (part of `check`) keeps
  contracts, ledger, client, and testkit free of bridge, custody, ZeroJ, and
  Julc dependencies.
- Tests run the plugin against the published Yano runtime
  (`org.yanoproject:yano-runtime`) plus `:framework:core`. Yano itself never
  depends on this module; see the
  [ADR-064 migration notes](../../../docs/appchain/MIGRATION-ADR-064.md).

## Build and test

```bash
./gradlew :ledgers:eutxo:ledger:check      # tests and boundary checks
./gradlew :ledgers:eutxo:ledger:shadowJar  # plugin bundle
```

See [Build and test Yano X](../../../docs/BUILD_AND_TEST.md) for the
required Yano version arguments.

## Notes

JVM only; native runtime compatibility is not claimed. Schemas remain alpha.
