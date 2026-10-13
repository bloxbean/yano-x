---
title: DPP Starter
description: A configuration-only Digital Product Passport prototype on the governed authenticated map, with proof-bound passports, a certification round, a public portal, an operator gateway, and a console.
sidebar:
  order: 8
---

The DPP Starter is a **prototype** of a Digital Product Passport registry. It
declares passport-shaped collections on the stock governed `authenticated-map`
state machine, so it needs no plugin. A passport is a set of proof-bound
answers at one height that anyone can verify offline.

## Problem

A product passport gathers records from many parties: the manufacturer's
product data and documents, certifiers' certificates, claims from labs, and
events from logistics, repair, and recycling. Buyers and regulators need to see
who wrote each record and whether it was changed later, without trusting one
platform operator.

## Who it is for

- **Teams exploring Digital Product Passports** who want to see the
  infrastructure a passport registry needs before committing to a design.
- **Manufacturers, certifiers, and auditors** rehearsing who writes what, with
  their own keys.
- **Verifiers** who need a passport they can check offline.

It is not a full DPP product. It enforces no DPP lifecycle rule on the ledger,
and it claims conformance with no DPP standard.

## Actors and flow

Each record type is a collection with its own writer role:

- a `manufacturer` registers a product and publishes passport versions; the
  document is hashed and stored outside consensus, and its digest is committed;
- a `claim-issuer` attaches public claims, or committed ones whose salt and text
  are disclosed to verifiers out of band;
- an `operator` appends lifecycle events, ordered by the ledger, never by a
  clock; and
- a `certifier` proposes a certificate that two `auditor`s from distinct
  organizations must approve before it is applied, once.

<!-- illustration: passport-explorer -->

The portal serves the passport, the `dpp-passport-v1` bundle, archived
documents by hash, and a GS1 Digital Link resolver (`/01/{gtin}`). An operator
gateway signs for the console on the operator's machine.

## What it proves, and what it does not

A passport shows what the consortium agreed about a product at a height: these
records existed with these revisions under this root; which governed actor wrote
each one under which policy and key; that a certificate was applied through the
approval round, with the approval used exactly once; that a committed claim
matches what the issuer disclosed; that the pinned members certified the root;
and, when anchored, that Cardano carries it.

It does **not** show that a physical event occurred, that a measurement is
true, that an issuer is accredited beyond this ledger's genesis, that a document
remains available, or that anything conforms to a DPP standard.

What the prototype cannot prevent, it exposes as flags: `REWRITTEN`,
`DANGLING`, `FOREIGN_WRITER`, `EXPIRED`, `NOT_YET_VALID`, and `MALFORMED`. For
example, any manufacturer can rewrite a version record, and the passport shows
it.

## Try it

From the root of an extracted `yano-x-jvm-<version>.zip`, the launcher finds the
distribution and `tools/yano-dpp` by itself:

```bash
examples/dpp/dpp.sh up        # three members on ports 7470-7472
examples/dpp/dpp.sh demo      # register, publish, claim, events, certify, verify
examples/dpp/dpp.sh portal    # public portal on 8580
examples/dpp/dpp.sh stop      # data is kept; clean deletes the instance
```

Then read and verify the passport yourself:

```bash
eval "$(examples/dpp/dpp.sh env)"
export PATH="$PWD/tools/yano-dpp/bin:$PATH"
yano-dpp passport --product gtin:09506000134352 --members "$YANO_DPP_MEMBERS" --output passport.json
yano-dpp verify --passport passport.json --members "$YANO_DPP_MEMBERS"   # exit 5
```

`dpp.sh env` prints the node API key and the gateway token; treat its output as
a secret. The console ships as `product-ui/dpp`.

## Modules

| Module | Role |
|---|---|
| `products/dpp/profile` | Collections, policies, schemas, value codecs, and the genesis generator |
| `products/dpp/client` | Writes, the certification round, passport assembly, `PassportVerifier`, `PassportView`, the portal and gateway services |
| `products/dpp/cli` | `yano-dpp`, shipped as `tools/yano-dpp` |
| `products/dpp/ui` | The console, shipped as `product-ui/dpp` |
| `products/dpp/harness` | `dpp.sh`, shipped as `examples/dpp` |

The [user guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/DPP_STARTER.md)
covers the data model, the console, every command, and troubleshooting.

## Status

`reference`, labelled a prototype. The decision record is ADR-051. The full
provider, with enforced lifecycle and sequence rules, product heads, recovery
claims, and publication workflows, waits on qualification of the governed map
and acceptance of the DPP design. Browser-side signing, accreditation lookups in
the Trust Registry, an explorer module for these collections, per-product
Cardano publication, and zero-knowledge selective disclosure are deferred.
