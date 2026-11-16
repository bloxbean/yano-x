# Application-ledger Cardano integration

The Cardano specialization of the application-ledger framework and the
activation root of the integrated Yano X application. It adds L1 delivery and
stability references, built-in L1 observers, L1 epoch observations, metadata and
script anchoring, the L1-slot rotating sequencer, the effect-executor binding and
node-local transaction building to the generic [`framework/core`](../core/README.md).
The same root also serves `yano.mode=app-ledger`.

## Artifact

`org.yanoproject.x:yano-x-appchain-cardano` (`library`). It carries the plugin
manifest `META-INF/yano/plugins/org.yanoproject.x.app-ledger.json`, whose bundle id
is the one the integrated distribution lists in `yano.plugins.required`.

## Key contents

Package `org.yanoproject.x.framework.cardano`:

- `CardanoAppLedgerPlugin`: the `node-plugin` root (extends the host adapter's
  `AppLedgerHostPlugin`).
- `AppChainManager`, `AppChainSubsystem`: Cardano specializations of
  `AppLedgerManager` and `AppLedgerRuntime`.
- `L1DeliveryLoop`, `L1ObservationService`, `L1EpochObservationCoordinator`,
  `HistoricalObservationReconciler`: deterministic L1 fact delivery and verification.
- `AddressDepositObserver` (`address-deposit`), `MetadataLabelObserver`
  (`metadata-label`): built-in L1 observers.
- `AnchorService` (metadata anchoring), `ScriptAnchorService` and
  `AnchorScriptArtifacts` (threshold co-signed script anchor using the checked
  validators from [`framework/cardano-onchain`](../cardano-onchain/README.md)).
- `RotatingSequencerMode`: the `rotating` sequencer.
- `EffectExecutorKindBinding`: host binding of the `effect-executor` kind.
- `DefaultL1TransactionBuilderService`, `NodeUtxoSupplier`: QuickTx transaction
  building against the node's own UTxO state.

## Dependencies / boundaries

`api`: `framework/core`, `framework/cardano-api`, Yano's `yano-host-api`;
`implementation`: `framework/host-adapter`, cardano-client-lib core and QuickTx,
Julc's cardano-client-lib bridge; `runtimeOnly`: `julc-vm-java` and
`framework/cardano-onchain`. Composed by [`distribution/jvm`](../../distribution/jvm/README.md)
and `sdk/appchain-testkit`. The standalone root
([`framework/standalone`](../standalone/README.md)) is the mutually exclusive no-Cardano
alternative: both roots declare the bundle id `org.yanoproject.x.app-ledger`, so a
host composes exactly one of them.

## Build & test

```bash
./gradlew :framework:cardano:test
./gradlew :framework:cardano:integrationTest
```

`test` is part of `frameworkCheck`. `integrationTest` (included in `check`) runs the
host assembly, consensus, catch-up, rotation, L1 delivery and anchoring suites
against the module JAR and the exact published `yano-runtime`; its
protocol-parameter fixture is a checksum-documented resource under
`src/integrationTest/resources/cardano-fixtures`. Add the Yano inputs from
[Build and test](../../docs/BUILD_AND_TEST.md) when the pinned Yano version is
local or staged.
