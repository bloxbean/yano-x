# eUTxO settlement end-to-end fixture

Devnet end-to-end tests for the eUTxO ledger's Cardano settlement bridge. Each
test class starts one devnet node from the packaged Yano X JVM distribution and
drives the settlement stack on its L1. The tests never boot the Yano host
in-process.

## Artifact

Not published (`test-fixture`, artifact id `yano-x-eutxo-e2e` in
[`config/artifacts-v1.json`](../../config/artifacts-v1.json)).

## Key contents

All code is under `src/test/java`:

- `org.yanoproject.app.e2e.EutxoSettlementBootstrapDevnetE2ETest`: the settlement
  bootstrap from the checked-in artifacts. Both one-shot Plutus mints run on chain
  and the root and 16 shard threads land at the resolved validator addresses with
  the planned genesis datums.
- `org.yanoproject.app.e2e.EutxoSettlementEffectPathDevnetE2ETest`: an L1 deposit,
  an L2 withdrawal and the full `l1.settlement` effect path (co-sign, Settle
  transaction, batch confirmation) on a live devnet chain.
- `@PackagedDevnet` and its extension `PackagedDevnetNode`: extract the packaged
  JVM distribution and run one devnet node configured by the class's Quarkus test
  profile (`EutxoSettlementDevnetE2ETestProfile`).

## Dependencies / boundaries

Test dependencies only: Yano's published `yano-runtime`, `yano-plugin-catalog` and
`yano-app-e2e-testkit`, the eUTxO modules (`client`, `bridge-onchain`, `demo`,
`testkit`), `composition:runtime`, `products:evidence:profile`, cardano-client-lib,
Julc and Scalus. `e2eTest` depends on `:distribution:jvm:yanoXJvmDistZip`. Nothing
depends on this module. See the [eUTxO ledger](../../ledgers/eutxo/README.md).

## Build & test

```bash
./gradlew :fixtures:eutxo-e2e:e2eTest
```

The ordinary `test` task excludes the `e2e` tag. To run the classes against a node
that is already running, pass `-Dyano.e2e.baseUrl=<node>/api/v1/`; the distribution
is then not built. Add the Yano inputs from [Build and test](../../docs/BUILD_AND_TEST.md).
CI runs `e2eTest` in the `eUTxO settlement E2E` workflow when a change touches
`ledgers/eutxo/` or `fixtures/eutxo-e2e/`.
