---
title: "Evidence Desk"
description: "The Evidence Desk is a browser workbench for role-gated release. An issuer proposes a release, auditors from independent organizations approve or reject…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/products-evidence-desk.md"
---
The Evidence Desk is a browser workbench for role-gated release. An issuer
proposes a release, auditors from independent organizations approve or reject
it with keys that never leave their browsers, the approved release is applied
once, and every record the desk shows is read back from the ledger with its
proof.

## Problem

A document release often needs sign-off from several organizations. In most
tools, approvals are rows in one vendor's database, signed by nobody, and
"approved" is whatever that database says. Auditors cannot show later that two
independent organizations approved exactly these bytes.

## Who it is for

- **Issuers and auditors** who approve releases with their own keys, from a
  browser, without installing a CLI.
- **Reviewers and regulators** who need every decision, and the record it
  produced, with a proof.
- **Teams evaluating role-aware approvals** on an app ledger. The desk is the
  user interface for the role workflow and for the
  [Evidence](/products/evidence/) product.

## Actors and flow

The desk runs against any app ledger whose composite carries the
`domain-actors` and `role-approvals` components. That includes the light
showcase's `document-review-chain`, whose `document-release` policy needs an
`issuer` to propose and two `auditor`s from distinct organizations to approve:

<!-- illustration: desk-approvals -->

Before signing a decision, the desk predicts the chain's answer from the
records it has read: `TERMINAL` (the proposal is no longer pending), `EXPIRED`,
`CONFLICT` (this actor already decided), `ROLE_MISMATCH`, or
`DISTINCTNESS_DUPLICATE` (another actor from the same organization already
decided this clause). After finality it reads the chain's actual result record.
A finalized statement that breaks the workflow's rules is a deterministic
no-op: the chain records its outcome code, and the desk shows that code rather
than assuming acceptance. The full list of outcome codes is in the
[user guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/EVIDENCE_DESK.md).

## What it proves, and what it does not

Every organization, actor, policy, proposal, result, receipt, and document head
is read through the state proof endpoint, decoded as canonical CBOR, and shown
with the height and root it was committed at. `BOUND` means the proof names that
key, height, and root and the finalized block at that height carries the same
root. The browser does not re-verify MPF paths or threshold finality; the JVM
verifier does that on the export bundle, as with [Attest](/products/attest/)
certificates.

A decision is an Ed25519 signature by the actor's registered key over a
statement that binds the chain, proposal, policy revision, payload domain and
hash, deadline, actor revision, key id, and clause. That shows which registered
actor approved which bytes. It does not establish the actor's legal identity, or
that the document's content is true.

## Try it

Start the light showcase from an extracted `yano-x-jvm-<version>.zip`, then
serve the desk, which ships as `product-ui/evidence`:

```bash
cd examples/showcase
./showcase.sh quickstart --profile light --nodes 3 --instance demo
python3 -m http.server 8088 --directory ../../product-ui/evidence
```

Open `http://localhost:8088`, connect to the node URL the showcase printed, and
choose `document-review-chain`. A node that is not same-origin with the desk
must allow the desk's origin through CORS, including `GET`, `POST`,
`Content-Type`, and `X-API-Key`; serving the desk behind the node's reverse proxy
avoids CORS. The
[user guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/EVIDENCE_DESK.md)
shows how to derive the showcase's demo actor seeds and walks through propose,
approve, release, and export.

## Modules

| Module | Path | Role |
|---|---|---|
| `yano-x-evidence-ui` | `products/evidence/ui` | Static site, shipped as `product-ui/evidence` |
| golden fixture | `examples/showcase` (`EvidenceDeskGoldenTest`) | Bytes from the Java contracts that the browser port is tested against |

## Status

`preview`. The decision record is ADR-048. Not yet supported:

- Release submission for the `role-evidence` profile: its command nests S3,
  IPFS, and Kafka connector commands the browser does not build yet. Use
  `demo.sh publish` from `examples/evidence`, and decide and inspect in the desk.
- Governed mutations (onboarding, rotation, revocation) stay CLI operations.

Further reading: [domain actors and role-aware approvals](https://github.com/bloxbean/yano-x/blob/main/docs/APP_CHAIN_DOMAIN_ROLES.md).
