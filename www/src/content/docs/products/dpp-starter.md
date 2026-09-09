---
title: DPP Starter
description: A configuration-only Digital Product Passport prototype on the governed authenticated map, with proof-bound passports, a certification round, a public portal, an operator gateway, and a console.
sidebar:
  order: 8
---

The DPP Starter (ADR-051) is a **prototype**: the stage-1 preview that ADR-046
§4.4 and Yano ADR app-layer/026 §5.1 permit before the full DPP provider is
built. It declares DPP-shaped collections on the stock governed
`authenticated-map` state machine and shows the infrastructure a Digital
Product Passport registry needs: governed writes by manufacturers, operators,
and claim issuers; a certification round with two independent auditors;
documents kept outside consensus and committed by hash; committed claims that
are disclosed out of band; and a passport that is a set of proof-bound answers
at one height, verifiable offline. It is not the DPP product of ADR-026, it
enforces no DPP lifecycle rule on chain, and it claims conformance with no DPP
standard.

## The journey

1. **Register and publish.** `yano-dpp register` creates the product record as
   a `manufacturer`; `publish-version` hashes a passport document, stores it
   under its SHA-256, and commits the version record and the new current
   version in one batch with a compare-and-set on the product's revision.
2. **Claim and observe.** A `claim-issuer` attaches public claims or committed
   ones (a salted SHA-256 commitment on chain, the salt and text in a
   `dpp-disclosure-v1` document handed to verifiers). Operators append
   lifecycle events; the ledger orders them, never a clock.
3. **Certify.** A `certifier` proposes a certificate through the map's approval
   route; two `auditor`s from distinct organizations approve; the map command
   carrying the approval reference is applied, and its proposal's one-use
   consumption is proven in the passport.
4. **Read.** The portal (`yano-dpp serve`) serves the passport view, the
   `dpp-passport-v1` bundle, a GS1 Digital Link resolver (`/01/{gtin}`), and
   archived documents by hash. `yano-dpp verify` checks a bundle offline under
   bundle-declared members, a pinned members file, or an anchor datum.
5. **Operate from the browser.** The console's Passport view reads the portal
   with no secret and checks that every proof names one chain, genesis, height,
   root, and block; the Operator view drives an operator gateway
   (`yano-dpp gateway`) that signs on the operator's machine.

What the starter cannot prevent, it exposes: a rewritten version, a dangling
current version, a writer from another organization, and expired validity
windows are flagged on the passport.

## Posture

`REFERENCE`, labelled a prototype. Version one runs on the ADR-049 map client and
signer and reuses the ADR-047 trust input. The full provider (`dpp-core-v1`,
enforced lifecycle and sequence rules, product heads, recovery claims,
publication workflows) stays gated on ADR-025 qualification and ADR-026
acceptance. Browser-side signing, trust-registry accreditation lookups, an
explorer module for the starter's collections, per-product Cardano publication,
and ZK selective disclosure are deferred.

The user guide is
[`docs/appchain/DPP_STARTER.md`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/DPP_STARTER.md);
the decision record is
[ADR-051](https://github.com/bloxbean/yano-x/blob/main/adr/051-digital-product-passport-starter.md).
