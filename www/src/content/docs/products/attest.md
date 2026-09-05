---
title: Attest
description: Record a document digest on a threshold-finalized doc-trail chain and hand out a portable certificate that anyone can verify offline against member keys or a Cardano anchor.
sidebar:
  order: 4
---

Attest is the smallest complete Yano X product: a browser UI, a CLI, and a Java
client on top of the stock `doc-trail` state machine. It answers "did this exact
file exist, in this series, before this block?" with a **certificate** that a
third party verifies without trusting the node that issued it.

It adds no plugin, no new proof format, and no core change. Every check reuses
a Yano primitive: the evidence bundle, the compact message proof, the height
pinned state proof, and the script-anchor datum.

## What a certificate proves

| Check | Question | Verified by |
|---|---|---|
| Digest | Does the file hash to the attested SHA-256? | UI and CLI |
| Command binding | Did a chain member sign a doc-trail append carrying exactly this digest, series id, and reference? | UI and CLI |
| Message inclusion | Does the message sit at a fixed position of the stated block? | UI and CLI |
| Finality | Did the chain's threshold of members sign that block? | CLI, against member keys you obtained independently |
| Anchor linkage | Does a Cardano state-thread datum commit to that block, state root, membership, and application id? | CLI, with the datum read from Cardano |
| Trail head | Did the series have the stated revision count and head digest at that block? | CLI |

The UI reports `INTERNAL_CONSISTENCY_ONLY`; the CLI reaches
`CALLER_PINNED_ROOT` with a members file and
`INDEPENDENTLY_VERIFIED_L1_ANCHOR` with an anchor datum. These are the ADR-037
trust levels used across Yano, so a certificate never claims more than its
inputs support.

## What it does not prove

- Who the person behind the file was. The signer is the ingress member's key;
  put authorship into the reference, which the command binds on chain.
- Anything about the content beyond its digest.
- A calendar time. Ordering comes from block height and, when anchored, the
  Cardano slot.

## Modules

| Artifact | Repo path | Role |
|---|---|---|
| `yano-x-attest-client` | `products/attest/client` | Certificate format, node client, offline verifier. |
| `yano-x-attest-cli` | `products/attest/cli` | `yano-attest attest / certificate / verify / trail / status`. |
| `yano-x-attest-ui` | `products/attest/ui` | Portable static web application. Files never leave the browser. |

## Who this is for

- **Teams that need proof of existence** for contracts, reports, designs, or
  evidence files, without a notary service in the loop.
- **Auditors** who receive a certificate and the original file and want a
  verdict they can reproduce offline.
- **Anyone evaluating Yano app chains** who wants the shortest path from a
  running chain to a verifiable artifact.

## Deeper reading

- [Attest product guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/ATTEST.md)
  — setup on the showcase or a fresh chain, CLI and UI walkthroughs, trust
  modes, exit codes, and troubleshooting.
- [`doc-trail` capability](/reference/capabilities/) — the state machine
  underneath.
- [State and proofs](/concepts/state-and-proofs/) — how to read a proof result
  correctly.
