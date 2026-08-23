---
title: Capability catalog
description: Every selectable app-chain capability in this release — state machines, profiles, sequencing, membership, anchoring, effects, executors, sinks, and observability — generated from the repository catalog.
sidebar:
  order: 3
---

A **capability** is the smallest selectable unit of app-chain behavior. A
[recipe](/recipes/) is a reviewed bundle of them.

This page is generated at documentation build time from
`tooling/devtools/src/main/resources/appchain-dx/v1alpha1/appchain-capability-catalog.json`.
For your exact build, ask the binary:

```bash
./yano.sh appchain capabilities
./yano.sh appchain capabilities --format json
```

## Reading the tables

| Field | Meaning |
|---|---|
| Availability | `BUNDLED` ships in the distribution; `FIRST_PARTY_OPTIONAL` needs its release-matched plugin bundle installed; `EXPERIMENTAL` may change; `REFERENCE` is a documented workflow. |
| Maturity | `stable`, `preview`, or `experimental`. |
| Runtimes | `jvm`, and `native` only for the handful of core capabilities. Yano X extensions are JVM-only. |
| Requires artifacts | The runtime artifacts that must be present. `yano-runtime` means the host itself. |

Capabilities also declare `requires`, `implies`, and `conflicts` relationships.
The CLI and [Studio](/studio/) resolve those for you; a conflicting selection
fails closed rather than picking one.

## Capabilities by category

<!-- catalog:capabilities-start -->

### `state`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`state:ordered-log`](https://github.com/bloxbean/yano-x/blob/main/docs/core-host.md) | `BUNDLED` | `stable` | `jvm`, `native` | `yano-runtime` | Append-only ordered application messages. |
| [`state:kv-registry`](/state-machines/kv-registry/) | `BUNDLED` | `stable` | `jvm` | `yano-runtime`, `yano-x-stdlib` | First-writer-owned mutable key/value records with committed proofs. |
| [`state:authenticated-map`](/state-machines/authenticated-map/) | `BUNDLED` | `preview` | `jvm` | `yano-runtime`, `yano-x-stdlib`, `yano-x-role-workflow` | Multi-collection authenticated records with basic, direct-role, and approval authorization plus optional canonical-CBOR and schema validation. |
| [`state:approval-workflow`](/state-machines/approvals/) | `BUNDLED` | `stable` | `jvm` | `yano-runtime`, `yano-x-stdlib` | Validator-member proposals, votes, and terminal threshold decisions. |
| [`state:balances`](/state-machines/balances/) | `BUNDLED` | `stable` | `jvm` | `yano-runtime`, `yano-x-stdlib` | Member-authorized mint and transfer accounts for bounded application balances. |
| [`state:doc-trail`](/state-machines/doc-trail/) | `BUNDLED` | `stable` | `jvm` | `yano-runtime`, `yano-x-stdlib` | Append-only per-entity document and event hash trails. |
| [`state:evidence-registry`](/tutorials/04-evidence-publication/) | `BUNDLED` | `preview` | `jvm` | `yano-runtime`, `yano-x-evidence-registry` | Inspection and compliance evidence records with exact committed query proofs. |
| [`state:role-approvals`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/tutorials/05-domain-role-approvals.md) | `BUNDLED` | `preview` | `jvm` | `yano-runtime`, `yano-x-composite`, `yano-x-role-workflow` | Governed organizations, actors, policies, and signed approvals for arbitrary payload hashes. |
| [`state:role-evidence`](/tutorials/04-evidence-publication/) | `BUNDLED` | `preview` | `jvm` | `yano-runtime`, `yano-x-stdlib`, `yano-x-evidence-registry`, `yano-x-composite`, `yano-x-role-workflow`, `yano-x-evidence-profile` | Evidence registration combined with governed domain actors and role-aware release approval. |
| [`state:zk-gate`](https://github.com/bloxbean/yano-x/blob/main/state-machines/zk/README.md) | `EXPERIMENTAL` | `experimental` | `jvm` | `yano-runtime`, `yano-x-zk` | Verifies configured Groth16 or Plonk proofs during deterministic state transition. |
| [`state:zk-membership`](https://github.com/bloxbean/yano-x/blob/main/state-machines/zk/README.md) | `EXPERIMENTAL` | `experimental` | `jvm` | `yano-runtime`, `yano-x-zk` | Membership authorization using a configured zero-knowledge circuit and nullifier deduplication. |
| [`state:credential-registry`](https://github.com/bloxbean/yano-x/blob/main/state-machines/zk/README.md) | `EXPERIMENTAL` | `experimental` | `jvm` | `yano-runtime`, `yano-x-zk` | Registry for selectively disclosed BBS credential statements from configured issuers. |
| [`state:eutxo-ledger`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/README.md) | `FIRST_PARTY_OPTIONAL` | `experimental` | `jvm` | `yano-runtime`, `yano-x-eutxo-ledger` | Deterministic Cardano-shaped EUTxO state, root-fixed receipts, address indexes, and MPF-proven outputs under one explicitly selected ledger profile. |

### `custom-plugin`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`state:custom-plugin`](/plugins/) | `REFERENCE` | `experimental` | `jvm` | `yano-runtime` | Project boundary for a separately reviewed custom state-machine bundle. |

### `profile`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`profile:eutxo-plutus-v3`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/README.md) | `FIRST_PARTY_OPTIONAL` | `experimental` | `jvm` | `yano-runtime`, `yano-x-eutxo-ledger` | Pins the immutable yano-eutxo-v2-plutus-v3 ledger profile and every consensus-relevant bound. |
| [`profile:eutxo-bridge-settlement`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/README.md) | `FIRST_PARTY_OPTIONAL` | `experimental` | `jvm` | `yano-runtime`, `yano-x-eutxo-ledger` | Pins the immutable yano-eutxo-v3-bridge-settlement ledger profile (ADR-UTXO-009) and every consensus-relevant bound. |
| [`profile:eutxo-key-payments`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/README.md) | `FIRST_PARTY_OPTIONAL` | `experimental` | `jvm` | `yano-runtime`, `yano-x-eutxo-ledger` | Pins the immutable yano-eutxo-v1 ledger profile used by the current bounded ZeroJ validity circuit. |

### `funding`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`funding:eutxo-genesis`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/README.md) | `FIRST_PARTY_OPTIONAL` | `experimental` | `jvm` | `yano-runtime`, `yano-x-eutxo-ledger` | Bootstraps one explicit ADA-only virtual allocation for no-real-funds testing. |

### `bridge`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`bridge:cardano-federated`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/README.md) | `EXPERIMENTAL` | `experimental` | `jvm` | `yano-runtime`, `yano-x-eutxo-ledger`, `yano-x-eutxo-bridge-cardano` | Observes exact accepted vault deposits and settles irrevocable bounded claims through either exact external signing or permissionless current-root MPF proofs with an on-chain replay nullifier. |

### `settlement`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`settlement:zeroj-validity`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/README.md) | `EXPERIMENTAL` | `experimental` | `jvm` | `yano-runtime`, `yano-x-eutxo-zk-zeroj`, `yano-x-eutxo-zk-runtime`, `yano-x-eutxo-zk-lifecycle` | Adds a Yano L2 envelope with a Cardano-compatible body, registered Jubjub session-key authorization, optional Poseidon validity commitment, durable proving interfaces, proof-bound Cardano root advancement, bounded aggregate withdrawal and canonical L1 batch publication. |

### `product-label`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`rollup:zeroj-cardano`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/Z6_PRODUCTION_HARDENING.md) | `EXPERIMENTAL` | `experimental` | `jvm` | `yano-runtime`, `yano-x-eutxo-ledger`, `yano-x-eutxo-zk-zeroj` | Graduation label for a separately approved ZeroJ validity product; it does not add runtime behavior. |

### `indexer`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`indexer:eutxo-lifecycle`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/INDEXER_OPERATIONS.md) | `FIRST_PARTY_OPTIONAL` | `preview` | `jvm` | `yano-runtime`, `yano-x-eutxo-indexer-core`, `yano-x-eutxo-indexer-jdbc` | Indexes finalized EUTxO transactions, accounts, bridge lifecycle records, and optional validity batches for bounded APIs and the unified console. |

### `sequencer`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`sequencer:fixed`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/README.md) | `BUNDLED` | `stable` | `jvm`, `native` | `yano-runtime` | One declared member proposes application blocks. |
| [`sequencer:rotating`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/README.md) | `BUNDLED` | `experimental` | `jvm`, `native` | `yano-runtime` | Rotates proposership across members using bounded L1 slot windows. |

### `membership`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`membership:static`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/README.md) | `BUNDLED` | `stable` | `jvm`, `native` | `yano-runtime` | Pins the initial member set as the active set. |
| [`membership:governed`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/README.md) | `BUNDLED` | `preview` | `jvm`, `native` | `yano-runtime` | Allows authenticated member epochs to evolve through replicated governance. |

### `l1`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`l1:slot-feed`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/README.md) | `BUNDLED` | `stable` | `jvm`, `native` | `yano-runtime` | Supplies stable local Cardano slot observations to slot-aware app-chain behavior. |

### `anchor`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`anchor:metadata`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/tutorials/07-anchors-and-verification.md) | `BUNDLED` | `stable` | `jvm`, `native` | `yano-runtime` | Commits finalized app-chain roots in Cardano transaction metadata. |
| [`anchor:script`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/tutorials/07-anchors-and-verification.md) | `BUNDLED` | `preview` | `jvm`, `native` | `yano-runtime` | Advances an app-chain anchor thread through a reviewed Cardano script. |

### `l1-observer`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`observer:address-deposit`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/README.md) | `BUNDLED` | `preview` | `jvm`, `native` | `yano-runtime` | Creates stable observations for lovelace paid to one configured Cardano address. |
| [`observer:metadata-label`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/README.md) | `BUNDLED` | `preview` | `jvm`, `native` | `yano-runtime` | Creates stable observations for one configured Cardano transaction metadata label. |

### `effects-runtime`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`effects:runtime`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/tutorials/06-webhook-effects.md) | `BUNDLED` | `preview` | `jvm`, `native` | `yano-runtime` | Enables deterministic effect intents, gates, result incorporation, and proofs. |

### `effect-emission`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`effects:on-approved`](/state-machines/approvals/) | `BUNDLED` | `preview` | `jvm` | `yano-runtime`, `yano-x-stdlib` | Emits one typed chain-result effect when a stock approval reaches its threshold. |

### `effect-executor`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`executor:webhook`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/tutorials/06-webhook-effects.md) | `BUNDLED` | `preview` | `jvm`, `native` | `yano-runtime` | Executes webhook.post effects against one node-local configured URL. |
| [`executor:kafka`](https://github.com/bloxbean/yano-x/blob/main/connectors/kafka/README.md) | `FIRST_PARTY_OPTIONAL` | `preview` | `jvm` | `yano-runtime`, `yano-x-kafka` | Executes kafka.publish intents through allowlisted node-local Kafka targets. |
| [`executor:objectstore-s3`](https://github.com/bloxbean/yano-x/blob/main/connectors/objectstore-s3/README.md) | `FIRST_PARTY_OPTIONAL` | `preview` | `jvm` | `yano-runtime`, `yano-x-objectstore-s3` | Executes immutable object.put promotion against allowlisted S3-compatible targets. |
| [`executor:ipfs`](https://github.com/bloxbean/yano-x/blob/main/connectors/ipfs/README.md) | `FIRST_PARTY_OPTIONAL` | `preview` | `jvm` | `yano-runtime`, `yano-x-ipfs` | Executes reconciled ipfs.pin intents against allowlisted Kubo targets. |
| [`executor:cardano-payment`](https://github.com/bloxbean/yano-x/blob/main/connectors/effects-cardano/README.md) | `FIRST_PARTY_OPTIONAL` | `preview` | `jvm` | `yano-runtime`, `yano-x-effects-cardano` | Executes bounded cardano.payment intents from an operator-controlled payer wallet. |

### `finalized-sink`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`sink:webhook`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/README.md) | `BUNDLED` | `stable` | `jvm`, `native` | `yano-runtime` | Posts finalized application blocks to one configured node-local webhook endpoint. |
| [`sink:kafka`](https://github.com/bloxbean/yano-x/blob/main/connectors/kafka/README.md) | `FIRST_PARTY_OPTIONAL` | `preview` | `jvm` | `yano-runtime`, `yano-x-kafka` | Publishes finalized application blocks to a configured Kafka topic. |

### `distribution`

| Capability | Availability | Maturity | Runtimes | Requires artifacts | Description |
|---|---|---|---|---|---|
| [`runtime:composite`](/plugins/) | `BUNDLED` | `preview` | `jvm` | `yano-x-composite` | Deterministic component, workflow, profile, query, quota, and profile-governance framework. |
| [`ui:console`](https://github.com/bloxbean/yano-x/blob/main/docs/console-ui.md) | `BUNDLED` | `preview` | `jvm`, `native` | `yano-runtime` | Embedded operational app-chain status console derived from the selected Yano distribution. |
| [`observability:prometheus`](https://github.com/bloxbean/yano-x/blob/main/docs/console-ui.md) | `FIRST_PARTY_OPTIONAL` | `preview` | `jvm`, `native` | `yano-runtime` | Optional one-command durable metrics history for the unified console. |

<!-- catalog:capabilities-end -->

## Runtime artifacts

Each artifact is an independently published, dependency-complete plugin bundle
with its own version. The `bundleId` is what appears in a plugin manifest and
in the distribution's checksummed bundle list.

<!-- catalog:artifacts-start -->

| Artifact | Availability | Plugin bundle id | Runtimes | Native posture |
|---|---|---|---|---|
| `yano-runtime` | `BUNDLED` | `builtin:yano-runtime` | `jvm`, `native` | `bundled` |
| `yano-x-stdlib` | `BUNDLED` | `com.bloxbean.cardano.yano.appchain.stdlib` | `jvm` | `unsupported` |
| `yano-x-evidence-registry` | `BUNDLED` | `com.bloxbean.cardano.yano.appchain.evidence-registry` | `jvm` | `unsupported` |
| `yano-x-composite` | `BUNDLED` | `library:composition:runtime` | `jvm` | `unsupported` |
| `yano-x-role-workflow` | `BUNDLED` | `com.bloxbean.cardano.yano.appchain.role-workflow` | `jvm` | `unsupported` |
| `yano-x-evidence-profile` | `BUNDLED` | `com.bloxbean.cardano.yano.appchain.evidence-profile` | `jvm` | `unsupported` |
| `yano-x-kafka` | `FIRST_PARTY_OPTIONAL` | `com.bloxbean.cardano.yano.appchain.kafka` | `jvm` | `unsupported` |
| `yano-x-objectstore-s3` | `FIRST_PARTY_OPTIONAL` | `com.bloxbean.cardano.yano.appchain.objectstore.s3` | `jvm` | `unsupported` |
| `yano-x-ipfs` | `FIRST_PARTY_OPTIONAL` | `com.bloxbean.cardano.yano.appchain.ipfs` | `jvm` | `unsupported` |
| `yano-x-effects-cardano` | `FIRST_PARTY_OPTIONAL` | `com.bloxbean.cardano.yano.appchain.effects.cardano` | `jvm` | `unsupported` |
| `yano-x-zk` | `EXPERIMENTAL` | `com.bloxbean.cardano.yano.appchain.zk` | `jvm` | `unsupported` |
| `yano-x-eutxo-ledger` | `BUNDLED` | `com.bloxbean.cardano.yano.appchain.eutxo` | `jvm` | `unsupported` |
| `yano-x-eutxo-indexer-core` | `BUNDLED` | `com.bloxbean.cardano.yano.appchain.eutxo.indexer` | `jvm` | `unsupported` |
| `yano-x-eutxo-indexer-jdbc` | `BUNDLED` | `library:ledgers:eutxo:indexer-jdbc` | `jvm` | `unsupported` |
| `yano-x-eutxo-bridge-cardano` | `BUNDLED` | `com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano` | `jvm` | `unsupported` |
| `yano-x-eutxo-zk-zeroj` | `BUNDLED` | `library:ledgers:eutxo-zk:zeroj` | `jvm` | `unsupported` |
| `yano-x-eutxo-zk-runtime` | `BUNDLED` | `library:ledgers:eutxo-zk:runtime` | `jvm` | `unsupported` |
| `yano-x-eutxo-zk-lifecycle` | `BUNDLED` | `library:ledgers:eutxo-zk:lifecycle` | `jvm` | `unsupported` |

<!-- catalog:artifacts-end -->

## Distributions

<!-- catalog:distributions-start -->

| Distribution | Runtime | Archive | Platforms | Bundled artifacts |
|---|---|---|---|---|
| `yano-x-jvm` | `jvm` | `yano-x-jvm-{version}.zip` | `java-25` | 13 |

<!-- catalog:distributions-end -->

## Optional connectors

A `FIRST_PARTY_OPTIONAL` capability is maintained and tested by Yano X but is
not selected in every deployment. Using one means:

1. installing the **exact release-matched** bundle in `plugins/` on every
   applicable node;
2. providing the external service it talks to; and
3. configuring its endpoints and credentials as **node-local** values — never
   in replicated effect payloads or consensus-shared configuration.

```bash
tools/yano-plugins/bin/yano-plugins validate plugins/*.jar
./yano.sh appchain doctor <project> --distribution /opt/yano-x
```

See the
[optional connector installation guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/OPTIONAL_CONNECTORS.md)
for per-connector requirements and security profiles.

## The conflict you will actually hit

The standard eUTxO runtime and the eUTxO ZK runtime both provide
`app-state-machine/eutxo-ledger`. Exactly one may be installed, which is why the
ZK runtime ships under `optional-plugins/`. See
[eUTxO and ZK](/products/eutxo-and-zk/).

## Related

- [Recipe catalog](/recipes/) — reviewed capability bundles.
- [Configuration reference](/reference/configuration/) — the typed properties
  these capabilities own.
- [Modules and artifacts](/reference/modules/) — where each artifact is built.
- [Release acceptance and schema status](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/RELEASE_ACCEPTANCE.md)
