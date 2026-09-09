---
title: Recipe catalog
description: Reviewed starting points for an app chain, generated from the release-pinned recipe catalog in the Yano X repository.
sidebar:
  order: 1
---

A **recipe** is a reviewed starting point. It resolves the capabilities it
requires, and you may then add only capabilities that are compatible with them.

Everything on this page is generated at documentation build time from
`tooling/devtools/src/main/resources/appchain-dx/v1alpha1/appchain-recipe-catalog.json`
in the repository. The authoritative answer for *your* build is always the
binary in your hands:

```bash
./yano.sh appchain recipes
./yano.sh appchain capabilities
./yano.sh appchain capabilities --format json
```

## The catalog

<!-- catalog:recipes-start -->

| Recipe | Name | Availability | Maturity | Primary outcome |
|---|---|---|---|---|
| [`audit-log`](https://github.com/bloxbean/yano-x/blob/main/docs/core-host.md) | Replicated audit log | `BUNDLED` | `stable` | One opaque record is threshold-finalized in the shared application order. |
| [`owned-registry`](/state-machines/kv-registry/) | Owned registry | `BUNDLED` | `stable` | The first writer owns a named value that is readable with a committed state proof. |
| [`document-trail`](/state-machines/doc-trail/) | Document trail | `BUNDLED` | `preview` | A document hash advances an entity trail; its count and head have a committed state proof. |
| [`authenticated-map`](/state-machines/authenticated-map/) | Authenticated map | `BUNDLED` | `preview` | A value accepted under a genesis-declared collection schema is committed and available with a state proof. |
| [`approval-workflow`](/state-machines/approvals/) | Threshold approval workflow | `BUNDLED` | `stable` | Distinct validator-member decisions produce a provable terminal approval or rejection. |
| [`role-approval`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/tutorials/05-domain-role-approvals.md) | Governed role approvals | `BUNDLED` | `preview` | A governed role policy accepts valid actor decisions and rejects an ineligible actor decision. |
| [`evidence-ledger`](/tutorials/04-evidence-publication/) | Role-aware evidence ledger | `BUNDLED` | `preview` | A role-authorized evidence record and its release decision are committed and queryable. |
| [`eutxo-ledger`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/README.md) | Scalus-backed EUTxO ledger | `FIRST_PARTY_OPTIONAL` | `experimental` | A signed key-controlled or bounded Plutus V3 transaction consumes virtual EUTxOs and creates MPF-proven outputs and receipts. |
| [`eutxo-cardano-bridge`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/README.md) | Federated Cardano EUTxO bridge | `EXPERIMENTAL` | `experimental` | Accepted stable deposits create mirrored EUTxOs; signed L2 spends create irrevocable claims that settle only through the configured signer path or a current accepted MPF root with single-use nullification. |
| [`eutxo-zeroj-validity`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/README.md) | ZeroJ EUTxO validity development profile | `EXPERIMENTAL` | `experimental` | Bounded finalized EUTxO payments deterministically update both the Yano MPF root and the circuit-friendly validity root and can be proved against pinned development artifacts. |
| [`eutxo-zeroj-preview`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/GETTING_STARTED.md) | ZeroJ EUTxO testnet lifecycle | `EXPERIMENTAL` | `experimental` | A project can bootstrap pinned development artifacts, accept finalized Jubjub-authorized L2 transactions, produce a constant-size proof, and prepare idempotent Cardano deposit, settlement, withdrawal, and recovery operations. |
| [`custom-plugin`](/plugins/) | Custom state-machine project | `REFERENCE` | `experimental` | The operator-defined state-machine contract is loaded from a reviewed pinned plugin bundle. |

<!-- catalog:recipes-end -->

## Availability vocabulary

| Availability | Meaning |
|---|---|
| `BUNDLED` | Ships in the Yano X JVM distribution and needs no additional runtime JAR. It may still need public identities, application bootstrap, or node-local configuration. |
| `FIRST_PARTY_OPTIONAL` | Maintained and tested by Yano X, but not selected in every deployment. Install the exact release-matched plugin bundle in `plugins/` on every applicable node. |
| `EXPERIMENTAL` | Available for evaluation. Interfaces, wire formats, and behavior may change. |
| `REFERENCE` | A documented workflow rather than a shipped runtime — the custom-plugin path. |

| Maturity | Meaning |
|---|---|
| `stable` | Covered by packaged-runtime acceptance. |
| `preview` | Implemented and tested; the contract may still move before release. |
| `experimental` | Under active development. |

Remember that Yano X is JVM-only. A recipe whose native posture is
`unsupported` cannot run on Yano's native image, which carries `ordered-log`
only.

## Using a recipe

```bash
# 1. See what this build offers.
./yano.sh appchain recipes

# 2. Generate a project non-interactively and reproducibly.
./yano.sh appchain init --non-interactive \
  --recipe owned-registry --network preprod --members 3 \
  --node-host node-a.example --node-host node-b.example --node-host node-c.example \
  --deployment host --output product-registry

# 3. Edit appchain.yaml, then regenerate the derived output.
#    Rendering stops if a generated file has an unaccounted manual edit.
./yano.sh appchain render product-registry

# 4. Validate the project and check it against a real distribution.
./yano.sh appchain config validate --mode project product-registry
./yano.sh appchain doctor product-registry --distribution /path/to/yano-x-jvm

# 5. Export deterministic deployment derivatives.
./yano.sh appchain gitops product-registry --target helm      --output deploy/helm
./yano.sh appchain gitops product-registry --target kustomize --output deploy/kustomize
```

Edit **only** `appchain.yaml`. Generated runtime files are derived output, and
the project lock pins the snapshot, catalog, runtime manifest, configuration
metadata, and plugin-JAR digests.

You can also build a blueprint visually in the
[App-Chain Studio](/studio/) and export it.

## Selection and deployment rules

- Start with `./yano.sh appchain recipes`, then inspect
  `./yano.sh appchain capabilities`.
- A bundled capability needs no extra runtime JAR, but may still need
  identities, bootstrap, or node-local configuration.
- An optional JVM capability requires the exact release-matched plugin bundle
  in every applicable node's `plugins/` directory.
- For stock composite and role profiles, the profile identifier and its
  configuration digest become part of chain identity. Select them only for a
  fresh chain or a governed activation.

## Each recipe in detail

<!-- catalog:recipe-details-start -->

### `audit-log` — Replicated audit log

A replicated append-only log for opaque application records.

- **Outcome:** One opaque record is threshold-finalized in the shared application order.
- **Availability / maturity:** `BUNDLED` / `stable`
- **Capabilities:** `state:ordered-log`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`
- **Runtimes:** `jvm`, `native` · **Deployment:** `host`, `docker-compose`
- **Reference:** [docs/core-host.md](https://github.com/bloxbean/yano-x/blob/main/docs/core-host.md)

```bash
./yano.sh appchain init --non-interactive \
  --recipe audit-log --network devnet --members 3 --runtime jvm \
  --output audit-log-chain
```

### `owned-registry` — Owned registry

A first-writer-owned key/value registry with committed query proofs.

- **Outcome:** The first writer owns a named value that is readable with a committed state proof.
- **Availability / maturity:** `BUNDLED` / `stable`
- **Capabilities:** `state:kv-registry`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`, `yano-x-stdlib`
- **Runtimes:** `jvm` · **Deployment:** `host`, `docker-compose`
- **Reference:** [/state-machines/kv-registry/](/state-machines/kv-registry/)

```bash
./yano.sh appchain init --non-interactive \
  --recipe owned-registry --network devnet --members 3 --runtime jvm \
  --output owned-registry-chain
```

### `document-trail` — Document trail

Append document hashes to a provable trail for each product, case, or shipment.

- **Outcome:** A document hash advances an entity trail; its count and head have a committed state proof.
- **Availability / maturity:** `BUNDLED` / `preview`
- **Capabilities:** `state:doc-trail`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`, `yano-x-stdlib`
- **Runtimes:** `jvm` · **Deployment:** `host`, `docker-compose`
- **Reference:** [/state-machines/doc-trail/](/state-machines/doc-trail/)

```bash
./yano.sh appchain init --non-interactive \
  --recipe document-trail --network devnet --members 3 --runtime jvm \
  --output document-trail-chain
```

### `authenticated-map` — Authenticated map

A proof-oriented multi-collection registry with optional canonical-CBOR and declarative schema validation.

- **Outcome:** A value accepted under a genesis-declared collection schema is committed and available with a state proof.
- **Availability / maturity:** `BUNDLED` / `preview`
- **Capabilities:** `state:authenticated-map`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`, `yano-x-stdlib`
- **Runtimes:** `jvm` · **Deployment:** `host`, `docker-compose`
- **Bootstrap requirements:** `pin-all-genesis-member-identities`
- **Reference:** [/state-machines/authenticated-map/](/state-machines/authenticated-map/)

```bash
./yano.sh appchain init --non-interactive \
  --recipe authenticated-map --network devnet --members 3 --runtime jvm \
  --output authenticated-map-chain
```

### `approval-workflow` — Threshold approval workflow

Validator members propose, approve, reject, and prove terminal decisions.

- **Outcome:** Distinct validator-member decisions produce a provable terminal approval or rejection.
- **Availability / maturity:** `BUNDLED` / `stable`
- **Capabilities:** `state:approval-workflow`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`, `yano-x-stdlib`
- **Runtimes:** `jvm` · **Deployment:** `host`, `docker-compose`
- **Reference:** [/state-machines/approvals/](/state-machines/approvals/)

```bash
./yano.sh appchain init --non-interactive \
  --recipe approval-workflow --network devnet --members 3 --runtime jvm \
  --output approval-workflow-chain
```

### `role-approval` — Governed role approvals

Governed organizations, actors, and policies approve arbitrary application payload hashes.

- **Outcome:** A governed role policy accepts valid actor decisions and rejects an ineligible actor decision.
- **Availability / maturity:** `BUNDLED` / `preview`
- **Capabilities:** `state:role-approvals`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`, `yano-x-composite`, `yano-x-role-workflow`
- **Runtimes:** `jvm` · **Deployment:** `host`, `docker-compose`
- **Bootstrap requirements:** `governed-organizations`, `governed-actors`, `governed-policies`
- **Reference:** [docs/appchain/tutorials/05-domain-role-approvals.md](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/tutorials/05-domain-role-approvals.md)

```bash
./yano.sh appchain init --non-interactive \
  --recipe role-approval --network devnet --members 3 --runtime jvm \
  --output role-approval-chain
```

### `evidence-ledger` — Role-aware evidence ledger

A role-aware evidence ledger that emits publication intents; add and provision explicit connector capabilities for external mutation.

- **Outcome:** A role-authorized evidence record and its release decision are committed and queryable.
- **Availability / maturity:** `BUNDLED` / `preview`
- **Capabilities:** `state:role-evidence`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`, `yano-x-stdlib`, `yano-x-evidence-registry`, `yano-x-composite`, `yano-x-role-workflow`, `yano-x-evidence-profile`
- **Runtimes:** `jvm` · **Deployment:** `host`, `docker-compose`
- **Bootstrap requirements:** `governed-organizations`, `governed-actors`, `governed-policies`
- **Reference:** [/tutorials/04-evidence-publication/](/tutorials/04-evidence-publication/)

```bash
./yano.sh appchain init --non-interactive \
  --recipe evidence-ledger --network devnet --members 3 --runtime jvm \
  --output evidence-ledger-chain
```

### `eutxo-ledger` — Scalus-backed EUTxO ledger

A deterministic Cardano-shaped test ledger funded by an explicit virtual genesis allocation.

- **Outcome:** A signed key-controlled or bounded Plutus V3 transaction consumes virtual EUTxOs and creates MPF-proven outputs and receipts.
- **Availability / maturity:** `FIRST_PARTY_OPTIONAL` / `experimental`
- **Capabilities:** `profile:eutxo-plutus-v3`, `funding:eutxo-genesis`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`, `yano-x-eutxo-ledger`
- **Runtimes:** `jvm` · **Deployment:** `host`, `docker-compose`
- **Bootstrap requirements:** `use-test-keys-and-no-real-funds`
- **Reference:** [ledgers/eutxo/README.md](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/README.md)

```bash
./yano.sh appchain init --non-interactive \
  --recipe eutxo-ledger --network devnet --members 3 --runtime jvm \
  --output eutxo-ledger-chain
```

### `eutxo-cardano-bridge` — Federated Cardano EUTxO bridge

A Cardano-backed EUTxO ledger with stable deposits and claim-bound federated withdrawals through external signing or permissionless current-root proofs.

- **Outcome:** Accepted stable deposits create mirrored EUTxOs; signed L2 spends create irrevocable claims that settle only through the configured signer path or a current accepted MPF root with single-use nullification.
- **Availability / maturity:** `EXPERIMENTAL` / `experimental`
- **Capabilities:** `profile:eutxo-plutus-v3`, `bridge:cardano-federated`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`, `yano-x-eutxo-ledger`, `yano-x-eutxo-bridge-cardano`
- **Runtimes:** `jvm` · **Deployment:** `host`, `docker-compose`
- **External prerequisites:** `reviewed-staging-vault-root-nullifier-and-proof-contract-identities`, `stable-cardano-l1-feed`, `federated-vault-and-root-operators`, `external-threshold-or-hsm-settlement-signer-for-signer-mode`
- **Bootstrap requirements:** `configure-durable-settlement-journal-for-signer-mode`, `pin-current-root-nullifier-and-proof-contract-identities-for-proof-mode`, `complete-real-funds-custody-review`
- **Reference:** [ledgers/eutxo/README.md](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/README.md)

```bash
./yano.sh appchain init --non-interactive \
  --recipe eutxo-cardano-bridge --network devnet --members 3 --runtime jvm \
  --output eutxo-cardano-bridge-chain
```

### `eutxo-zeroj-validity` — ZeroJ EUTxO validity development profile

A no-real-funds EUTxO ledger with the optional ZeroJ validity commitment and the interfaces needed to operate and test direct proof settlement.

- **Outcome:** Bounded finalized EUTxO payments deterministically update both the Yano MPF root and the circuit-friendly validity root and can be proved against pinned development artifacts.
- **Availability / maturity:** `EXPERIMENTAL` / `experimental`
- **Capabilities:** `funding:eutxo-genesis`, `settlement:zeroj-validity`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`, `yano-x-eutxo-ledger`, `yano-x-eutxo-zk-zeroj`
- **Runtimes:** `jvm` · **Deployment:** `host`, `docker-compose`
- **External prerequisites:** `zeroj-0.1.0-pre10`, `julc-0.1.0-pre16`, `development-ceremony-bundle`
- **Bootstrap requirements:** `use-test-keys-and-no-real-funds`
- **Reference:** [ledgers/eutxo-zk/README.md](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/README.md)

```bash
./yano.sh appchain init --non-interactive \
  --recipe eutxo-zeroj-validity --network devnet --members 3 --runtime jvm \
  --output eutxo-zeroj-validity-chain
```

### `eutxo-zeroj-preview` — ZeroJ EUTxO testnet lifecycle

Packages the Cardano-shaped L2 client, measured b16 development circuit, durable lifecycle tooling, contract plans, prover artifacts, relay handoff, and recovery evidence as one JVM-only testnet project.

- **Outcome:** A project can bootstrap pinned development artifacts, accept finalized Jubjub-authorized L2 transactions, produce a constant-size proof, and prepare idempotent Cardano deposit, settlement, withdrawal, and recovery operations.
- **Availability / maturity:** `EXPERIMENTAL` / `experimental`
- **Capabilities:** `settlement:zeroj-validity`, `bridge:cardano-federated`, `l1:slot-feed`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`, `yano-x-eutxo-ledger`, `yano-x-eutxo-bridge-cardano`, `yano-x-eutxo-zk-zeroj`, `yano-x-eutxo-zk-runtime`, `yano-x-eutxo-zk-lifecycle`
- **Runtimes:** `jvm` · **Deployment:** `host`, `docker-compose`
- **External prerequisites:** `zeroj-0.1.0-pre10`, `julc-0.1.0-pre16`, `funded-testnet-operator-key-for-live-L1-operations`
- **Bootstrap requirements:** `trusted-proof-submitter`, `development-ceremony-or-reviewed-testnet-ceremony`, `deployed-pinned-contract-identities`, `disposable-test-funds-only`
- **Reference:** [ledgers/eutxo-zk/GETTING_STARTED.md](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/GETTING_STARTED.md)

```bash
./yano.sh appchain init --non-interactive \
  --recipe eutxo-zeroj-preview --network devnet --members 3 --runtime jvm \
  --output eutxo-zeroj-preview-chain
```

### `custom-plugin` — Custom state-machine project

A generated project boundary for a separately reviewed custom state-machine plugin.

- **Outcome:** The operator-defined state-machine contract is loaded from a reviewed pinned plugin bundle.
- **Availability / maturity:** `REFERENCE` / `experimental`
- **Capabilities:** `state:custom-plugin`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`
- **Runtimes:** `jvm` · **Deployment:** `host`, `docker-compose`
- **External prerequisites:** `reviewed-custom-plugin-bundle`
- **Bootstrap requirements:** `plugin-metadata-trust-review`
- **Reference:** [/plugins/](/plugins/)

```bash
./yano.sh appchain init --non-interactive \
  --recipe custom-plugin --network devnet --members 3 --runtime jvm \
  --output custom-plugin-chain
```

<!-- catalog:recipe-details-end -->

## Next

- [Choosing a recipe](/recipes/choosing-a-recipe/) — a decision path from
  business outcome to selection.
- [Stock state-machine cookbook](/tutorials/03-stock-state-machines/) — compare
  the built-in deterministic models.
- [Capability catalog](/reference/capabilities/) — the full capability list
  behind these recipes.
