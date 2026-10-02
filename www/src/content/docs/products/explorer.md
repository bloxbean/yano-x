---
title: Verifiable Explorer
description: A derived, verify-on-ingest index over stock app ledgers with a read service, a CLI, and a console; every row keeps its evidence and can be proven offline.
sidebar:
  order: 7
---

The Verifiable Explorer is a read-side product for any app ledger that runs
stock state machines. It verifies each block before it writes a row, decodes
commands into readable rows, and hands out bundles that prove a row offline. The
index adds convenience, never trust.

## Problem

People want to browse a ledger: timelines, search, the history of one document
or account. A conventional block explorer asks you to trust its database. Once
the explorer is the place people read, a wrong or tampered row looks just like a
right one.

## Who it is for

- **Operators and support teams** who need timelines, search, and per-subject
  history for stock ledgers.
- **Auditors and counterparties** who want to check a specific row without
  trusting the explorer or the node behind it.
- **Product teams** that need a read model to start from.

## Actors and flow

A `yano-explorer` service follows one node's finalized blocks, checks each
block's evidence, and writes the block, its messages, and decoded rows in one
transaction. Every block gets a level, and its rows inherit it:

<!-- illustration: explorer-ingest -->

| Level | Meaning |
|---|---|
| `VERIFIED_PINNED` | The certificate verified under members and a threshold the operator pinned with `--members`. |
| `VERIFIED_DECLARED` | The certificate verified under the members the evidence declares: internal consistency at ingest. |
| `HEADER_ONLY` | A block without messages has no evidence bundle; its header is the node's view. |
| `JSON_ONLY` | The node no longer retains the evidence; rows come from its JSON view and cannot be proven from the index. |

For a subject (a `doc-trail` entity, registry key, account, approval item, or
map entry), the explorer also reads the authenticated value with a proof at the
tip and compares it with the view derived from the rows: `MATCH`, `DIVERGES`
(at least one finalized command did not apply), or `READ` (nothing comparable
to derive). Rows are finalized commands, and a finalized command can still be
a business rejection, which is why this check exists.

## What it proves, and what it does not

A row bundle (`explorer-row-proof-v1`) proves that the message is at that
position of a block that a threshold of your pinned members finalized, and that
the block record sits under the certified state root. A state bundle
(`explorer-state-proof-v1`) proves a subject's typed value at a certified root.
`yano-explorer verify` reaches `CALLER_PINNED_ROOT` with `--members` (exit 5)
and an independently checked anchor with `--anchor-datum-hex` (exit 0).

The index proves nothing by itself. It is never an authority: nothing in
consensus, proof verification, or accounting reads it, and any node can rebuild
it with `yano-explorer rebuild`. It is a local, rebuildable read index; see
[Where data lives](/concepts/where-data-lives/).

## Try it

Start a showcase instance with some traffic, then point the explorer at node 0.
From the root of an extracted `yano-x-jvm-<version>.zip`:

```bash
examples/showcase/showcase.sh up --profile light --instance demo
examples/showcase/showcase.sh run all --instance demo

examples/explorer/explorer.sh up --url http://127.0.0.1:7070 --instance demo
examples/explorer/explorer.sh status --instance demo
eval "$(examples/explorer/explorer.sh env --instance demo)"   # EXPLORER_SERVICE_URL, EXPLORER_DB
```

The service listens on `127.0.0.1:8490`. Serve `product-ui/explorer` with any
static web server for the console, or use `tools/yano-explorer/bin/yano-explorer`
for `status`, `search`, `trail`, `row`, and `verify`. The
[user guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/EXPLORER.md)
walks through each command.

## Modules

| Module | Path | Role |
|---|---|---|
| `yano-x-explorer-core` | `products/explorer/core` | Follower, SQLite index, stock modules, subjects, archiver, bundles, verifier, read service |
| `yano-x-explorer-cli` | `products/explorer/cli` | `yano-explorer`, shipped as `tools/yano-explorer` |
| `yano-x-explorer-ui` | `products/explorer/ui` | Static console, shipped as `product-ui/explorer` |
| launcher | `products/explorer/harness/explorer.sh` | Runs the service against a node; shipped as `examples/explorer` |

The read service is GET-only, binds to loopback by default, and keeps the node
API key on its side.

## Status

`preview`. The decision record is ADR-050. Role-approval commands are indexed
as generic rows in this version.
