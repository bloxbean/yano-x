---
title: "Trust Registry"
description: "The Trust and Status Registry answers \"is this credential still valid?\" and \"is this issuer authorized?\" with proofs bound to a certified block. It is a…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/products-trust-registry.md"
---
The Trust and Status Registry answers "is this credential still valid?" and "is
this issuer authorized?" with proofs bound to a certified block. It is a
configuration-only product on the stock governed `authenticated-map` state
machine, plus a CLI, a read service, and a console that run outside consensus.

## Problem

Verifiers check credential status and issuer authorization against a registry
that one party runs. That party can change a status, backdate it, or serve
different answers to different callers, and the verifier cannot tell. Status
lists published as files carry no evidence of who changed what, or when.

## Who it is for

- **Credential issuers and consortia** that manage revocation and suspension
  lists, and the issuers allowed to sign credentials.
- **Verifiers** who need a status answer they can check offline, at the
  current height or an earlier one.
- **Teams already using W3C Bitstring Status Lists** who want the served list
  tied to a ledger value.

## Actors and flow

A JSON descriptor names organizations, actors with their key proofs, and seeded
issuers; `yano-trust genesis` turns it into the node properties. The same
descriptor, members, and threshold always give the same genesis id. After that:

- an `issuer` sets status bits and publishes lists, and a `registrar` records
  subjects, each write signed with the actor's key and a one-use authorization
  the ledger consumes exactly once;
- onboarding an issuer after genesis needs approvals from two registrars in
  distinct organizations; and
- anyone asks for a status, at the tip or a retained height, and gets an answer
  backed by state proofs.

<!-- illustration: status-registry -->

`yano-trust serve` serves lists as W3C Bitstring Status Lists and answers
TRQP-shaped authorization queries ("is entity E authorized for A under
framework F at height H"), without processing JSON-LD. Lists are replayed from
the ledger's applied writes, and the served bitstring hashes to the value the
ledger holds.

## What it proves, and what it does not

An answer shows what the consortium agreed about an identifier: that an entry
existed with this revision at this height under this root, that a key had no
entry, or that it was revoked; who wrote it, under which policy, and that their
authorization was used once; that the pinned members certified the root; and,
with an anchor datum, that Cardano carries it.

The registry never mints identifiers and never asserts that a credential's
claims are true. An `ABSENT` answer says only that this registry had no entry at
that height.

`yano-trust verify` reaches `CALLER_PINNED_ROOT` with a members file (exit 5)
and `INDEPENDENTLY_VERIFIED_L1_ANCHOR` with an anchor datum read from Cardano
(exit 0). Without a trust input the answer is consistent only (exit 6). Treat
only exits 0 and 5 as verified.

## Try it

From the root of an extracted `yano-x-jvm-<version>.zip`, the launcher finds the
distribution and `tools/yano-trust` by itself. `registry.sh env` does not put
`yano-trust` on your `PATH`, so add it:

```bash
examples/trust-registry/registry.sh up          # three members on ports 7270-7272
eval "$(examples/trust-registry/registry.sh env)"
export PATH="$PWD/tools/yano-trust/bin:$PATH"

yano-trust put --url $YANO_TRUST_URL --chain $YANO_TRUST_CHAIN --actor issuer-a \
  --seed-file $YANO_TRUST_SEEDS/issuer-a.seed --list list-1 --index 5 --bit 1 \
  --genesis-id $YANO_TRUST_GENESIS_ID          # the first write on a fresh ledger
yano-trust status --url $YANO_TRUST_URL --chain $YANO_TRUST_CHAIN --list list-1 --index 5 \
  --members $YANO_TRUST_MEMBERS                # exit 5
```

`registry.sh env` prints the demo API key; run it in a shell whose history you
control. The console ships as `product-ui/trust-registry`, and
`registry.sh gateway` starts an operator gateway for its write view. The
[user guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/TRUST_REGISTRY.md)
covers the data model, every command, the console, and troubleshooting.

## Modules

| Module | Path | Role |
|---|---|---|
| `yano-x-trust-registry-profile` | `products/trust-registry/profile` | Collections, schemas, policies, value codecs, genesis, bitstring projection, TRQP evaluator |
| `yano-x-trust-registry-client` | `products/trust-registry/client` | Node client, answers, signer, verifier, standards service |
| `yano-x-trust-registry-cli` | `products/trust-registry/cli` | `yano-trust`, shipped as `tools/yano-trust` |
| `yano-x-trust-registry-ui` | `products/trust-registry/ui` | Console, shipped as `product-ui/trust-registry` |
| launcher | `products/trust-registry/harness/registry.sh` | Three-member registry; shipped as `examples/trust-registry` |

## Status

`preview`. The decision record is ADR-049. Onboarding an issuer after genesis
uses the approval route and has no CLI yet.
