---
title: Reference shelf
description: Deep links to the exhaustive guides that live in the Yano X repository — the 113 KB user guide, state-machine references, connector guides, ADRs, and demo scripts.
sidebar:
  order: 6
---

This site is curated. The Yano X repository holds several exhaustive documents
that are deliberately **not** duplicated here, because a second copy would
diverge from the code within a release.

They are linked below, and they are the authority whenever this site and the
repository disagree.

## The complete guides

| Document | Size | What it covers |
|---|---|---|
| [App-chain user guide](https://github.com/bloxbean/yano-x/blob/main/docs/APP_CHAIN_USER_GUIDE.md) | ~113 KB | The exhaustive reference: configuration, REST API, anchoring, custom app chains, multi-chain nodes, standard state machines, SSE/webhook/Kafka consumption, typed messages, security, compliance, operations, queries, client libraries, ZK, effects, troubleshooting, and current limitations. |
| [App-chain overview](https://github.com/bloxbean/yano-x/blob/main/docs/APP_CHAIN_OVERVIEW.md) | ~21 KB | The 10–15 minute architecture read, plus an editable presentation deck. |
| [Consensus and host internals](https://github.com/bloxbean/yano-x/blob/main/docs/core-host.md) | — | The consensus round check by check, vote locks, rotation math, catch-up and restart semantics, plugin query and domain API contract. |
| [App-chain tutorial](https://github.com/bloxbean/yano-x/blob/main/docs/APP_CHAIN_TUTORIAL.md) | ~22 KB | Run a cluster and build a custom state machine end to end. |
| [Use-case catalogue](https://github.com/bloxbean/yano-x/blob/main/docs/APP_CHAIN_USE_CASES.md) | ~19 KB | Worked application patterns and their starting points. |

## Domain and governance

| Document | What it covers |
|---|---|
| [Domain actors and role-aware approvals](https://github.com/bloxbean/yano-x/blob/main/docs/APP_CHAIN_DOMAIN_ROLES.md) | Governed organizations, actor and key revisions, role policies, and organization-distinct quorums. |
| [Profile governance runbook](https://github.com/bloxbean/yano-x/blob/main/docs/APP_CHAIN_PROFILE_GOVERNANCE.md) | Packaging, authorizing, and activating a composite profile epoch. |
| [Composable state and proofs](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/COMPOSABLE_STATE_AND_PROOFS.md) | Reusing stock transitions and verifying portable proofs. |
| [Proof Lab](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/PROOF_LAB.md) | Message, typed-state, imported, and on-chain proof workflows; independent-verifier and Cardano-validator guides. |
| [Authenticated snapshots](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/AUTHENTICATED_SNAPSHOTS.md) | Archiving and proving large immutable period datasets. |

## Capabilities, connectors, and release

| Document | What it covers |
|---|---|
| [Capability catalog](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/CAPABILITIES.md) | The release capability and recipe catalog in the repository. |
| [Optional connectors](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/OPTIONAL_CONNECTORS.md) | Per-connector installation, configuration, and security profiles. |
| [Release acceptance](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/RELEASE_ACCEPTANCE.md) | Acceptance scenarios and schema status. |
| [Cardano History](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/CARDANO_HISTORY.md) | The full product guide and route reference. |

## Build, operate, demo

| Document | What it covers |
|---|---|
| [Build and test](https://github.com/bloxbean/yano-x/blob/main/docs/BUILD_AND_TEST.md) | Every build task and verification gate. |
| [Build distributions](https://github.com/bloxbean/yano-x/blob/main/docs/BUILD_DISTRIBUTIONS.md) | Distribution inputs, outputs, and the artifact API prefix. |
| [Cluster launcher](https://github.com/bloxbean/yano-x/blob/main/scripts/appchain-cluster/README.md) | Per-node overlays, chain definitions, membership, load and soak tests. |
| [Evidence chain demo](https://github.com/bloxbean/yano-x/blob/main/docs/EVIDENCE_CHAIN_DEMO.md) | The complete scripted evidence scenario and its acceptance checks. |
| [Showcase demo](https://github.com/bloxbean/yano-x/blob/main/examples/showcase/DEMO_SHOWCASE.md) | Operating the unified showcase distribution. |
| [Developer tools](https://github.com/bloxbean/yano-x/blob/main/tooling/devtools/README.md) | The offline engine behind `yano.sh appchain`. |
| [App-Chain Studio](/studio/) | The blueprint builder, hosted here. |

## eUTxO and ZK

| Document | What it covers |
|---|---|
| [eUTxO product family](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/README.md) | The virtual ledger, profiles, and genesis. |
| [eUTxO demos](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/DEMO.md) | The disposable three-scenario quick start. |
| [Indexer operations](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/INDEXER_OPERATIONS.md) | Lifecycle API, SQLite, recovery, metrics. |
| [ZK getting started](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/GETTING_STARTED.md) | Status and module verification. |
| [ZK devnet walkthrough](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/DEVNET_WALKTHROUGH.md) | Deposit, L2 transaction, proof, settlement, withdrawal. |

## Decisions

Architecture decision records live in the `adr/` directory of the repository.
They are point-in-time decisions rather than documentation — several are
explicitly marked pre-split evidence — so they are deliberately not published
here. Read them in the repository when you need the rationale behind a
contract; trust this site and the code for current behaviour.

## The upstream host

Yano itself lives at [github.com/bloxbean/yano](https://github.com/bloxbean/yano)
and owns the node, consensus, proofs, anchoring, the effect runtime, the plugin
SPI, and `ordered-log`. Host-contract questions belong there.
