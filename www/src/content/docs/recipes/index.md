---
title: Recipe catalog
description: Reviewed starting points for an app ledger, generated from the release-pinned recipe catalog in the Yano X repository.
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
| [`declarative-composite`](/reference/declarative-bindings-cli/) | Declarative event workflow | `BUNDLED` | `preview` | A source command and its event-derived commands commit atomically with an auditable receipt. |
| [`audit-log`](https://github.com/bloxbean/yano-x/blob/main/docs/core-host.md) | Replicated audit log | `BUNDLED` | `stable` | One opaque record is threshold-finalized in the shared application order. |
| [`owned-registry`](/state-machines/kv-registry/) | Owned registry | `BUNDLED` | `stable` | The first writer owns a named value that is readable with a committed state proof. |
| [`document-trail`](/state-machines/doc-trail/) | Document trail | `BUNDLED` | `preview` | A document hash advances an entity trail; its count and head have a committed state proof. |
| [`authenticated-map`](/state-machines/authenticated-map/) | Authenticated map | `BUNDLED` | `preview` | A value accepted under a genesis-declared collection schema is committed and available with a state proof. |
| [`approval-workflow`](/state-machines/approvals/) | Threshold approval workflow | `BUNDLED` | `stable` | Distinct validator-member decisions produce a provable terminal approval or rejection. |
| [`role-approval`](/tutorials/05-domain-role-approvals/) | Governed role approvals | `BUNDLED` | `preview` | A governed role policy accepts valid actor decisions and rejects an ineligible actor decision. |
| [`evidence-ledger`](/tutorials/04-evidence-publication/) | Role-aware evidence ledger | `BUNDLED` | `preview` | A role-authorized evidence record and its release decision are committed and queryable. |
| [`eutxo-ledger`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/README.md) | Scalus-backed EUTxO ledger | `FIRST_PARTY_OPTIONAL` | `experimental` | A signed key-controlled or bounded Plutus V3 transaction consumes virtual EUTxOs and creates MPF-proven outputs and receipts. |
| [`eutxo-cardano-bridge`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/README.md) | Federated Cardano EUTxO bridge | `EXPERIMENTAL` | `experimental` | Accepted stable deposits create mirrored EUTxOs; signed L2 spends create irrevocable claims that settle only through the configured signer path or a current accepted MPF root with single-use nullification. |
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

A recipe is chosen per chain in an application's setup draft. The coordinator
creates the draft, each operator makes a key and a member card on their own
machine, the coordinator seals, and each operator joins and starts:

```bash
# 1. See what this build offers, and rehearse every step on one machine.
./yano.sh appchain recipes
./yano.sh appchain try

# 2. Coordinator: create the draft (a wizard asks when run without options).
./yano.sh appchain new product-registry --network preprod \
  --operators alice,bob,carol --chain registry=owned-registry

# 3. Each operator, on their own machine: make a key and a member card.
./yano.sh appchain request product-registry.draft.yaml

# 4. Coordinator: seal the draft with the cards beside it.
./yano.sh appchain seal product-registry.draft.yaml

# 5. Each operator: compare the fingerprint, then join and start.
./yano.sh appchain join product-registry.chain
./yano.sh appchain start
./yano.sh appchain status product-registry
```

Edit **only** the draft, and only before `seal`. `seal` resolves every chain
through the release's recipe and capability catalog once and writes the exact
settings, member keys and plugin bundle digests into the sealed `.chain` file;
`join` turns that into each node's configuration. See
[Set up an application](/deployment/configure/) for the whole ceremony.

For several state machines in one chain, compose them with a binding document
in the [binding editor](/studio/) or YAML and name it with
`--chain <id>=bindings:<file>`.

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

### `declarative-composite` — Declarative event workflow

Compile bounded event bindings into one committed atomic composite profile.

- **Outcome:** A source command and its event-derived commands commit atomically with an auditable receipt.
- **Availability / maturity:** `BUNDLED` / `preview`
- **Capabilities:** `state:declarative-composite`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`, `yano-x-composite`, `yano-x-stdlib`, `yano-x-role-workflow`
- **Runtimes:** `jvm` · **Deployment:** `host`, `docker-compose`
- **External prerequisites:** `Exact manifested authoring plugin directory`
- **Bootstrap requirements:** `Pinned public member keys and normalized component configuration`
- **Reference:** [/reference/declarative-bindings-cli/](/reference/declarative-bindings-cli/)

```bash
./yano.sh appchain new declarative-composite-app --network devnet --operators alice,bob,carol \
  --chain flow=bindings:examples/bindings/registry-to-audit.yaml
```

### `audit-log` — Replicated audit log

A replicated append-only log for opaque application records.

- **Outcome:** One opaque record is threshold-finalized in the shared application order.
- **Availability / maturity:** `BUNDLED` / `stable`
- **Capabilities:** `state:ordered-log`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`
- **Runtimes:** `jvm`, `native` · **Deployment:** `host`, `docker-compose`
- **Reference:** [docs/core-host.md](https://github.com/bloxbean/yano-x/blob/main/docs/core-host.md)

```bash
./yano.sh appchain new audit-log-app --network devnet --operators alice,bob,carol \
  --chain audit-log=audit-log
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
./yano.sh appchain new owned-registry-app --network devnet --operators alice,bob,carol \
  --chain owned-registry=owned-registry
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
./yano.sh appchain new document-trail-app --network devnet --operators alice,bob,carol \
  --chain document-trail=document-trail
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
./yano.sh appchain new authenticated-map-app --network devnet --operators alice,bob,carol \
  --chain authenticated-map=authenticated-map
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
./yano.sh appchain new approval-workflow-app --network devnet --operators alice,bob,carol \
  --chain approval-workflow=approval-workflow
```

### `role-approval` — Governed role approvals

Governed organizations, actors, and policies approve arbitrary application payload hashes.

- **Outcome:** A governed role policy accepts valid actor decisions and rejects an ineligible actor decision.
- **Availability / maturity:** `BUNDLED` / `preview`
- **Capabilities:** `state:role-approvals`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`, `yano-x-composite`, `yano-x-role-workflow`
- **Runtimes:** `jvm` · **Deployment:** `host`, `docker-compose`
- **Bootstrap requirements:** `governed-organizations`, `governed-actors`, `governed-policies`
- **Reference:** [/tutorials/05-domain-role-approvals/](/tutorials/05-domain-role-approvals/)

```bash
./yano.sh appchain new role-approval-app --network devnet --operators alice,bob,carol \
  --chain role-approval=role-approval
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
./yano.sh appchain new evidence-ledger-app --network devnet --operators alice,bob,carol \
  --chain evidence-ledger=evidence-ledger
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
./yano.sh appchain new eutxo-ledger-app --network devnet --operators alice,bob,carol \
  --chain eutxo-ledger=eutxo-ledger
```

This recipe needs `eutxoGenesisAddress`, `eutxoGenesisLovelace`. Give each with `--answer eutxo-ledger.<name>=<value>` (the wizard asks for any that is missing), or set them under the chain's `answers:` in the draft before sealing.

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
./yano.sh appchain new eutxo-cardano-bridge-app --network devnet --operators alice,bob,carol \
  --chain eutxo-cardano-bridge=eutxo-cardano-bridge
```

This recipe needs `bridgeVaultAddress`, `bridgeVaultScriptHash`, `bridgeMaxDepositLovelace`, `bridgeWithdrawalAddress`, `bridgeEpoch`, `bridgeMaxWithdrawalLovelace`, `bridgeMaxPendingWithdrawals`. Give each with `--answer eutxo-cardano-bridge.<name>=<value>` (the wizard asks for any that is missing), or set them under the chain's `answers:` in the draft before sealing.

### `custom-plugin` — Custom state-machine project

A chain that runs a separately reviewed custom state-machine plugin, named by the stateMachine answer and pinned by digest at seal.

- **Outcome:** The operator-defined state-machine contract is loaded from a reviewed pinned plugin bundle.
- **Availability / maturity:** `REFERENCE` / `experimental`
- **Capabilities:** `state:custom-plugin`, `sequencer:fixed`
- **Runtime artifacts:** `yano-runtime`
- **Runtimes:** `jvm` · **Deployment:** `host`, `docker-compose`
- **External prerequisites:** `reviewed-custom-plugin-bundle`
- **Bootstrap requirements:** `plugin-metadata-trust-review`
- **Reference:** [/plugins/](/plugins/)

```bash
./yano.sh appchain new custom-plugin-app --network devnet --operators alice,bob,carol \
  --chain custom-plugin=custom-plugin --answer custom-plugin.stateMachine=<machine-id>
```

Copy the reviewed plugin JAR into `plugins/` of every operator's distribution, the coordinator's included, before `seal`: the seal pins the digest of every installed bundle. See [Scaffold, sign, install](/plugins/scaffold-sign-install/).

<!-- catalog:recipe-details-end -->

## Next

- [Choosing a recipe](/recipes/choosing-a-recipe/) — a decision path from
  business outcome to selection.
- [Stock state-machine cookbook](/tutorials/03-stock-state-machines/) — compare
  the built-in deterministic models.
- [Capability catalog](/reference/capabilities/) — the full capability list
  behind these recipes.
