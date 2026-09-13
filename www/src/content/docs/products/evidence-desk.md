---
title: "Evidence Desk"
description: "The Evidence Desk is the user interface for the role workflow and the Evidence product. It runs against any app chain whose composite carries the…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/products-evidence-desk.md"
---
The Evidence Desk is the user interface for the role workflow and the
[Evidence](/products/evidence/) product. It runs against any app chain whose
composite carries the `domain-actors` and `role-approvals` components, which
includes the light showcase's connector-free `document-review-chain` and the
evidence product's `role-evidence` profile.

## The journey

1. **Connect** to a node; the desk lists eligible chains and picks a release
   adapter from each chain's capability manifest.
2. **Load an actor key.** A 32-byte Ed25519 seed is imported into WebCrypto as
   a non-extractable key and matched against the actor's `ACTIVE` key epoch
   read from the chain. It never leaves the tab.
3. **Propose.** The release inputs are fixed now: entity id, document digest
   (hashed in the browser), and reference are encoded into the release command,
   hashed with blake2b-256, and signed into the statement's payload hash.
4. **Decide.** Reviewers approve or reject a pending proposal. Every bound field
   is copied from the proposal record; the desk predicts the chain's answer
   (`ROLE_MISMATCH`, `DISTINCTNESS_DUPLICATE`, ...) before signing and reads the
   chain's actual result record afterwards.
5. **Release.** On a document-review chain the approved release is submitted
   only when its inputs re-derive the proposal's payload hash. The desk then
   shows the consumption receipt and the document head, bound to one root.
6. **Export** a bundle of every record and its state proof for offline
   verification.

## What it proves

Every organization, actor, policy, proposal, result, receipt, and document head
is read from the chain's authenticated state through the state proof endpoint,
decoded as canonical CBOR, and shown with the height and root it was committed
at. `BOUND` means the proof names that key, height, and root and the finalized
block at that height carries the same root. The MPF path and threshold finality
are verified by the JVM verifier on the export bundle, as with
[Attest](/products/attest/) certificates.

Decisions are Ed25519 signatures by the actor's registered key over a statement
that binds chain, proposal, policy revision, payload domain and hash, deadline,
actor revision, key id, and clause. Finalized statements that fail the
workflow's rules are deterministic no-ops; the chain records the outcome of
every one, and the desk shows it rather than assuming acceptance.

## Modules

| Module | Path | Role |
|---|---|---|
| `yano-x-evidence-ui` | `products/evidence/ui` | Static SvelteKit site; packaged under `product-ui/evidence` in the JVM distribution |
| golden fixture | `examples/showcase` (`EvidenceDeskGoldenTest`) | Bytes from the Java contracts that the browser port is tested against |

## Run it

Start with the [local showcase quickstart](/start-here/quickstart/) and keep
its extracted directory. From the directory containing `showcase.sh`:

```bash
./showcase.sh quickstart --profile light --nodes 3 --instance demo
./showcase.sh ui --instance demo
```

Use the node URL printed for that instance, which may differ from port 7070.
The Evidence Desk assets are packaged under `yano/product-ui/evidence` in the
showcase archive. Serve that directory with a static web server and choose
`document-review-chain` in the desk. For example, in another terminal from the
same extracted showcase directory:

```bash
python3 -m http.server 8088 --directory yano/product-ui/evidence
```

Open `http://localhost:8088`. Follow the
[user guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/EVIDENCE_DESK.md)
for demo actors, key handling, and the source-build alternative.

## Not yet

- Release submission for the `role-evidence` profile: its command nests S3,
  IPFS, and Kafka connector commands the browser does not build yet; use
  `demo.sh publish` and decide and inspect in the desk.
- Governed mutations (onboarding, rotation, revocation) stay CLI operations.

## Deeper reading

- [ADR-048](https://github.com/bloxbean/yano-x/blob/main/adr/048-evidence-desk.md) — the decision and its implementation record
- [Domain actors and role-aware approvals](https://github.com/bloxbean/yano-x/blob/main/docs/APP_CHAIN_DOMAIN_ROLES.md)
- [Evidence product](/products/evidence/) — the profile the desk fronts
- [Attest](/products/attest/) — the certificate and verify conventions the desk reuses
