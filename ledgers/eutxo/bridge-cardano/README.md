# EUTxO Cardano bridge plugin

Optional runtime plugin behind the `eutxo-cardano-bridge` recipe
(`bridge:cardano-federated`): exact L1 observers for stable vault deposits and
withdrawal confirmations, and the federated settlement executor that pays out
accepted withdrawal claims. The custody model, operator obligations, and
trust boundary are described in the [EUTxO product family](../README.md); the
validators it drives live in [bridge-onchain](../bridge-onchain/README.md).

## Artifact

`org.yanoproject.x:yano-x-eutxo-bridge-cardano` (thin jar) and
`yano-x-eutxo-bridge-cardano-bundle` (plugin bundle
`org.yanoproject.x.eutxo.bridge.cardano`). Runtime plugin; not released to
Maven Central. The bundle is part of the Yano X JVM distribution's default
plugin set.

## Key contents

Package `org.yanoproject.x.eutxo.bridge.cardano`:

- L1 observers (`L1ObserverProvider`): `CardanoBridgeObserverProvider`
  (`eutxo-vault-deposit-v1`), `CardanoWithdrawalConfirmationObserverProvider`
  (`eutxo-withdrawal-confirmation-v1`), and
  `CardanoBatchWithdrawalConfirmationObserverProvider`
  (`eutxo-batch-withdrawal-confirmation-v1`);
- settlement: `EutxoSettlementExecutorFactory` (`eutxo-settlement` effect
  executor), `BatchSettlementExecutor`, `SettlementCosignService`,
  `WithdrawalCoordinator`, and the crash-safe `SettlementJournal` /
  `FileSettlementJournal`;
- external signing: `ExternalSettlementSigner` /
  `HttpExternalSettlementSigner` for an operator-owned threshold or HSM
  signer;
- transaction builders such as `DepositAcceptanceBuilder`,
  `VaultWithdrawalTransactionBuilder`, and `ProofWithdrawalTransactionBuilder`;
- operations: `BridgeDoctor` (read-only preflight) and `BridgeRollbackGuard`
  (fail-closed deep-rollback decision); and
- `EutxoBridgeDomainApiProvider` — the bridge's read-only domain API.

## Dependencies and boundaries

- Compiles against `:framework:cardano-api`, Yaci core, cardano-client-lib,
  and CBOR as `compileOnly`; the host supplies them. The bundle embeds
  `:ledgers:eutxo:contracts`, `:ledgers:eutxo:client`, and Jackson.
- `verifyEutxoBridgeArtifactBoundaries` and `verifyEutxoBridgeDependencyGraph`
  (both part of `check`) reject host, Cardano, Julc, or ZeroJ classes in the
  bundle and Julc or ZeroJ in the runtime graph.
- The virtual ledger (`:ledgers:eutxo:ledger`) never depends on this module.

## Build and test

```bash
./gradlew :ledgers:eutxo:bridge-cardano:check      # tests and boundary checks
./gradlew :ledgers:eutxo:bridge-cardano:shadowJar  # plugin bundle
```

See [Build and test Yano X](../../../docs/BUILD_AND_TEST.md) for the
required Yano version arguments. Design:
[ADR-UTXO-001](../../../adr/app-layer/utxo/001-eutxo-state-machine-and-cardano-settlement.md),
[ADR-UTXO-003](../../../adr/app-layer/utxo/003-multi-user-eutxo-deposit-payment-and-withdrawal.md),
and [ADR-042](../../../adr/042-l1-observation-and-eutxo-settlement-lifecycle.md).

## Notes

Experimental and not production-funds ready; see the release evidence in
[M5_RELEASE_GATES.md](../M5_RELEASE_GATES.md). JVM only.
