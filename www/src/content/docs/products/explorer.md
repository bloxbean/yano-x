---
title: Verifiable Explorer
description: A derived, verify-on-ingest index over stock app chains with a read service, a CLI, and a console; every row keeps its evidence and can be proven offline.
sidebar:
  order: 7
---

The Verifiable Explorer is a read-side product (ADR-050) on top of any Yano app
chain that runs stock state machines. A `yano-explorer` service tails a node's
finalized blocks over the public REST API, verifies each block's evidence
bundle before it writes a row, decodes commands with the contract-owned codecs,
and serves timelines, search, and per-subject views with a proof-backed state
check. Any row or subject can be exported as a bundle that `yano-explorer
verify` checks offline at the ADR-047 trust levels. The index adds convenience,
never trust: nothing in consensus or proof verification reads it, and it can be
rebuilt from any node.

## The journey

1. **Index.** `yano-explorer serve --url <node>` discovers the node's chains,
   reads each chain's state identity, and follows blocks. For every block with
   a message it fetches the node's evidence bundle, verifies the canonical block,
   its message ids, and the finality certificate under the bundle's declared
   members (or caller-pinned members with `--members`), captures the
   authenticated block record proof, decodes the messages, and writes the block
   in one transaction. The block's level (`VERIFIED_PINNED`,
   `VERIFIED_DECLARED`, `HEADER_ONLY`, `JSON_ONLY`) is stored; rows inherit it.
2. **Browse.** The console lists chains with checkpoint, tip, and levels; the
   timeline opens blocks and messages with their decoded rows; search finds a
   message id, a height, a topic, a sender prefix, or a subject prefix.
3. **Read a subject.** A doc-trail entity's trail recomputes the head from its
   revisions and compares it with the authenticated head at the tip; registry
   keys, accounts, approval items, and map entries get the same rows, derived
   view, and state check (`MATCH`, `DIVERGES`, `READ`).
4. **Prove a row.** *Verify this row* asks the service for an
   `explorer-row-proof-v1` bundle (certified block, compact inclusion path,
   block record proof, message copy). The browser recomputes the envelope copy,
   message id, sender signature, inclusion path, and block record; the CLI
   verifies finality under pinned members or an anchor datum.
5. **Archive content.** `yano-explorer archive add --file` (or an allow-listed
   `fetch`) verifies a body against the committed entry hash before storing it
   under its SHA-256; the trail shows each revision as finalized or
   content-verified and serves the body by hash.

## What ships

| Piece | Where |
|---|---|
| `yano-explorer` CLI: `chains`, `index`, `serve`, `status`, `search`, `subject`, `trail`, `row`, `verify`, `export`, `rebuild`, `archive` | `tools/yano-explorer` in the JVM distribution |
| Read service (GET only, CORS open, node key server-side) | `yano-explorer serve` |
| Console (static site, runtime-configured service URL) | `product-ui/explorer` |
| Launcher for a showcase instance | `examples/explorer/explorer.sh` |

The full guide, with setup, the CLI walkthrough, the trust levels, and the
runtime findings, is `docs/appchain/EXPLORER.md` in the repository.
