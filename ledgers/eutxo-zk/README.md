# Optional EUTxO validity and ZK-rollup modules

> **Excluded from the build.** These modules are untested, so `settings.gradle`
> leaves them out: Gradle does not compile, test, or publish them, and no
> distribution, catalog, recipe, demo scenario, or tool includes them. The
> commands below no longer work. The source, the devnet end-to-end test in
> [`e2e/`](e2e/), and the records of the last runs in [`acceptance/`](acceptance/)
> stay in the repository. To build the modules again, restore their entries in
> `settings.gradle`, `config/artifacts-v1.json`, `config/jvm-distribution-v1.json`
> and the capability catalog.

The shortest disposable devnet path is the unified
[EUTxO demo guide](../eutxo/DEMO.md). The manual lifecycle and public-testnet
operator path remain in [GETTING_STARTED.md](GETTING_STARTED.md).

These sibling modules implement Phase C of ADR-UTXO-001. They are optional:
the base EUTxO ledger has no ZeroJ or Julc dependency and behaves exactly as
before unless `machines.eutxo.validity.enabled=true`.

The current implementation is pre-production. Development Groth16 setup keys
are test-only; production release remains blocked until the ADR's ceremony,
audit, data-availability and operational gates are satisfied.

Start with [GETTING_STARTED.md](GETTING_STARTED.md) for status and module
verification. Use
[DEVNET_WALKTHROUGH.md](DEVNET_WALKTHROUGH.md) for the step-by-step
Cardano L1 deposit, appchain L2 transaction, proof, root settlement, and L1
withdrawal flow.
The optional provider-neutral validity lifecycle is projected by the common
[EUTxO indexer](../eutxo/INDEXER_OPERATIONS.md); the indexer core has no ZeroJ
dependency.

Milestone notes:

- [Z0 feasibility](Z0_FEASIBILITY.md)
- [Z1 bounded circuit](Z1_BATCH_CIRCUIT.md)
- [Z2 prover pipeline](Z2_PROVER_PIPELINE.md)
- [Z3 validity root](Z3_VALIDITY_ROOT.md)
- [Z4 withdrawals](Z4_PROOF_WITHDRAWALS.md)
- [Z5 data availability and recovery](Z5_DATA_AVAILABILITY.md)
- [Z6 production hardening and release gates](Z6_PRODUCTION_HARDENING.md)
- [D3 fixed batch profiles and measurements](D3_BATCH_PROFILES.md)
- [D4 packaged testnet lifecycle](D4_LIFECYCLE.md)
- [D5 network acceptance and evidence status](D5_ACCEPTANCE.md)
- [D6 experimental testnet release decision](D6_RELEASE_DECISION.md)
