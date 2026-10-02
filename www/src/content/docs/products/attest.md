---
title: Attest
description: Record a document digest on a threshold-finalized doc-trail ledger and hand out a portable certificate that anyone can verify offline against member keys or a Cardano anchor.
sidebar:
  order: 4
---

Attest is the smallest complete Yano X product: a browser UI, a CLI, and a Java
client on top of the stock `doc-trail` state machine. It answers "did this exact
file exist, in this series, before this block?" with a **certificate** that a
third party verifies without trusting the node that issued it.

## Problem

You need to show later that a contract, report, design, or dataset existed in a
given form, without publishing it and without a notary service in the loop. A
timestamp from your own database proves nothing to anyone else.

## Who it is for

- **Teams that need proof of existence** for documents and evidence files.
- **Auditors** who receive a certificate and the original file and want a
  verdict they can reproduce offline.
- **Anyone evaluating Yano** who wants the shortest path from a running ledger
  to a verifiable artifact.

## Actors and flow

`yano-attest attest` hashes a file, submits a `doc-trail` append carrying the
digest, waits for finality, and writes a certificate. The certificate embeds the
signed message, its inclusion proof, the node's evidence bundle, and, when
available, a proof of the series' trail head. A verifier then runs six checks
offline:

<!-- illustration: attest-lab -->

The browser UI runs the digest, binding, and inclusion checks, and reports
`INTERNAL_CONSISTENCY_ONLY`. When it is connected to a node, it labels what the
node says about the block and the latest anchor `NODE_CONFIRMED_L1_REFERENCE`:
the node's claim, not an independent check. Finality and anchor verification are
the CLI's job.

## What it proves, and what it does not

A certificate accepted by `yano-attest verify` shows that a member of the ledger
signed a `doc-trail` append carrying exactly this digest, series id, and
reference; that the message sits at a fixed position in a block the ledger's
threshold of members finalized; what revision the series had at that block; and,
with an anchor datum, that a Cardano transaction commits to that block.

It does **not** show:

- who the person behind the file was. The signer is the receiving member's key;
  put authorship into the reference, which the command binds;
- anything about the content beyond its digest; or
- a calendar time. Ordering comes from the block height and, when anchored, the
  Cardano slot.

## Try it

Attest runs against the `documents-chain` of the local showcase. From an
extracted `yano-x-jvm-<version>.zip`:

```bash
cd examples/showcase
./showcase.sh quickstart --profile light --nodes 3 --instance demo

ATTEST=../../tools/yano-attest/bin/yano-attest
NODE=http://127.0.0.1:7070/api/v1
KEY=yano-local-cluster-full-key            # the showcase's local key

$ATTEST status --url $NODE --chain documents-chain --api-key $KEY
$ATTEST attest --url $NODE --chain documents-chain --api-key $KEY \
  --file contract.pdf --reference "signed by legal" --output contract.pdf.attest.json
$ATTEST verify --certificate contract.pdf.attest.json --file contract.pdf   # exit 6: no trust input yet
```

Use the ports the showcase prints if 7070 is busy. To reach exit 5, build a
members file from the deployment record, never from the certificate, and pass
`--members keys.json`. The
[user guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/ATTEST.md)
shows the file format, anchored certificates, and the UI, which ships as
`product-ui/attest`.

## Modules

| Artifact | Repo path | Role |
|---|---|---|
| `yano-x-attest-client` | `products/attest/client` | Certificate format, node client, offline verifier |
| `yano-x-attest-cli` | `products/attest/cli` | `yano-attest attest`, `certificate`, `verify`, `trail`, `status`; shipped as `tools/yano-attest` |
| `yano-x-attest-ui` | `products/attest/ui` | Static web application; files never leave the browser |

Attest adds no plugin and no new proof format. Every check reuses a Yano
primitive: the evidence bundle, the compact message proof, the height-pinned
state proof, and the script-anchor datum.

## Status

`preview`. The decision record is ADR-047. Composite ledgers that embed a
`documents` component are not supported by this version. Read
[State and proofs](/concepts/state-and-proofs/) for how to read a proof result.
