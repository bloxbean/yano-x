---
title: eUTxO and ZK
description: An experimental deterministic Cardano-shaped UTxO ledger, an optional federated Cardano bridge, and an optional ZeroJ validity and rollup path.
sidebar:
  order: 4
---

:::danger[Experimental — no real funds]
This family is `EXPERIMENTAL` and pre-production. It is **not** a Cardano
bridge product, a custody product, or a rollup you should put value into. The
ZK modules ship development Groth16 setup keys that are **test-only**;
production release is blocked until the governing ADR's ceremony, audit,
data-availability, and operational gates are satisfied.

Use a devnet or a Cardano test network with disposable keys.
:::

A deterministic, Cardano-shaped eUTxO state machine that runs as an app ledger
capability, with an optional Cardano bridge and an optional zero-knowledge
validity path on top.

## Problem

Teams want to run Conway-shaped transactions at application-ledger speed and
cost, with threshold finality and proofs, and later move value between that
ledger and Cardano. Building a UTxO engine, a bridge, and a proving pipeline
from scratch is a large, risky undertaking.

## Who it is for

- **Researchers and protocol teams** exploring eUTxO execution on an app ledger,
  a federated bridge, or ZK validity proofs.
- **Developers** who want a disposable, no-real-funds environment for
  Cardano-shaped transactions.

It is not for production value, custody, or public bridges.

## Actors and flow

Three layers, each optional on top of the ledger:

<!-- illustration: eutxo-layers -->

| Recipe | Availability | What it adds |
|---|---|---|
| `eutxo-ledger` | `FIRST_PARTY_OPTIONAL` | The deterministic UTxO ledger with a virtual genesis allocation. |
| `eutxo-cardano-bridge` | `EXPERIMENTAL` | Federated deposit and withdrawal against Cardano. |
| `eutxo-zeroj-validity` | `EXPERIMENTAL` | ZeroJ Groth16 validity proofs over batches. |
| `eutxo-zeroj-preview` | `EXPERIMENTAL` | The full lifecycle: Cardano deposit, eUTxO transaction, proof, root settlement, Cardano withdrawal. |

The `eutxo-ledger` recipe combines the reusable `state:eutxo-ledger` engine with
`profile:eutxo-plutus-v3` and the separate `funding:eutxo-genesis` capability:

```yaml
yano:
  app-chain:
    chains:
      - chain-id: payments-eutxo
        state-machine: eutxo-ledger
        machines:
          eutxo:
            profile: yano-eutxo-v2-plutus-v3
            expected-profile-digest: 8cd4adb72def2c31dc8551a02f67429ea468bb2024dbe85a1dc7300590c9d1bf
            genesis:
              address: addr_test1...
              lovelace: 100000000
```

The profile commitment is SHA-256 over its canonical, versioned consensus
fields. **All members must use the same profile, digest, and genesis
allocation**: this is part of the ledger's identity, not a tunable. The ZeroJ
recipes use `profile:eutxo-key-payments` instead, so exactly one immutable ledger
profile owns the consensus settings at a time. The base ledger has **no** ZeroJ
or JuLC dependency and behaves exactly as it does without them unless
`machines.eutxo.validity.enabled=true`.

### The plugin-conflict rule

The standard eUTxO runtime and the eUTxO ZK runtime both provide the
`app-state-machine/eutxo-ledger` contribution, so exactly one may be installed.
The ZK runtime therefore ships under `optional-plugins/` rather than `plugins/`.
To switch:

```bash
# On EVERY member:
rm plugins/<standard-eutxo-ledger-bundle>.jar
cp optional-plugins/<eutxo-zk-runtime-bundle>.jar plugins/
tools/yano-plugins/bin/yano-plugins validate plugins/*.jar
```

Copying both into `plugins/` is a hard catalog error, and the node refuses to
start rather than pick one.

## What it proves, and what it does not

The ledger proves what any app ledger proves: that a threshold of members
finalized each transaction in order, and that each output is in the state under
a certified root, with an MPF proof. The ZeroJ path adds Groth16 validity proofs
over batches.

It does **not** make the bridge trustless. The bridge is federated custody: in
signer mode it trusts an external threshold or HSM, and in proof mode it trusts
the threshold-accepted root. The ZeroJ preview requires a trusted prover and
does not claim security against a malicious prover. Nothing here is ready for
production value.

The lifecycle indexer is a **rebuildable read index**. It lives in
`appchain-indexers/`, never takes part in validation, roots, finality, or
settlement, and must never be treated as authoritative state. See
[Where data lives](/concepts/where-data-lives/).

## Try it

The disposable demos create three-node devnet clusters with local test
identities. From the root of an extracted `yano-x-jvm-<version>.zip`, beside
`yano.sh`:

```bash
./yano.sh appchain eutxo demo scenarios
./yano.sh appchain eutxo demo up --scenario ledger --workspace ./eutxo-ledger-demo
./yano.sh appchain eutxo demo round-trip --workspace ./eutxo-ledger-demo
# Expected: EUTXO_LEDGER_DEMO_ROUND_TRIP_PASS
```

The `bridge` and `zk` scenarios follow the same pattern. The
[demo guide](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/DEMO.md)
covers all three.

## Modules

| Goal | Guide |
|---|---|
| The eUTxO family overview | [`ledgers/eutxo/README.md`](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/README.md) |
| Status and module verification | [ZK getting started](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/GETTING_STARTED.md) |
| Deposit, transaction, proof, settlement, withdrawal | [Devnet walkthrough](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo-zk/DEVNET_WALKTHROUGH.md) |
| Indexer API, SQLite, recovery, metrics | [Indexer operations](https://github.com/bloxbean/yano-x/blob/main/ledgers/eutxo/INDEXER_OPERATIONS.md) |
| ZK milestone notes | [`ledgers/eutxo-zk/`](https://github.com/bloxbean/yano-x/tree/main/ledgers/eutxo-zk) |

The modules live under `ledgers/eutxo` and `ledgers/eutxo-zk`, and the
lifecycle console ships as `product-ui/eutxo`.

## Status

`experimental`. Production release is gated on the ZK ceremony, audit,
data-availability, and operational gates described in the eUTxO ADRs.
