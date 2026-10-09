---
title: Attestation Feed
description: An experimental, configuration-only consortium observation ledger on the governed authenticated map, with a deterministic aggregation every verifier recomputes, approval-closed rounds, a candidate Cardano datum, a portal, a gateway, and a console.
sidebar:
  order: 9
---

The Attestation Feed is an **experimental starter**: a consortium observation
ledger that runs configuration-only on the stock governed `authenticated-map`
state machine. Sources sign their own readings, the consortium closes each
round through an approval, and every verifier recomputes the result.

## Problem

Several parties need one agreed value per period, such as a cold-store
temperature, from several independent sources. A single collector can drop
inconvenient readings or change them later. Each party wants to see every
source's reading, the rule that combined them, and proof that nobody rewrote
the result.

## Who it is for

- **Consortia** that combine readings from known sources and must later show
  how each published value was derived.
- **Auditors** who recompute a round themselves from a bundle, offline.
- **Teams evaluating** an oracle-style design before building one. This starter
  is not a public price oracle, and nothing is published to Cardano.

## Actors and flow

A `feed-admin` defines each feed: unit, scale, round calendar, 1 to 16 sources,
the quorum, the outlier tolerance, and value bounds. Each `source` records one
reading per round under `<feed>/<round>/<source>` with its own actor key. A
`feed-operator` proposes the round's result computed at one height. Two
`publisher`s from distinct organizations each recompute it and approve, and the
map records it once.

The aggregate is not computed on the ledger. It is a pure function of the
proven observations and the proven policy, and every verifier recomputes it:

<!-- illustration: feed-aggregation-sim -->

A round is `CLOSED` with an aggregate, or `NO_QUORUM`. The verifier flags a
record that disagrees with its recomputation (`RECORD_DISAGREES`), a record that
binds a different policy (`WRONG_POLICY`), a rewritten record (`REWRITTEN`), and
a record without a proven approval (`UNPROVEN_APPROVAL`), among others.

## What it proves, and what it does not

A verified round bundle shows that, at the closing height, the policy was this
and each configured source had exactly this observation or none; that the
aggregation rule yields this result; that the consortium recorded exactly that
result later, through an approval whose one-time use is proven, and never
rewrote it; and that the pinned members certified the roots.

It does **not** show that a reading is true, that the sources are independent,
or that the closing height was a fair moment: an observation one block later is
invisible to it, so re-answer the round at a later height and compare. Nothing
reaches Cardano. The candidate datum is computed and hashed into the record, but
no executor publishes it.

`yano-feed verify` reports a trust level. With no trust input it can only show
internal consistency (exit 6). With a members file you obtained independently it
reaches `CALLER_PINNED_ROOT` (exit 5), and with an anchor datum read from
Cardano, `INDEPENDENTLY_VERIFIED_L1_ANCHOR` (exit 0). Treat only exits 0 and 5 as
verified.

## Try it

From an extracted `yano-x-jvm-<version>.zip`, the launcher finds the
distribution and `tools/yano-feed` by itself:

```bash
examples/attestation-feed/feed.sh up       # three members on ports 7570-7572
examples/attestation-feed/feed.sh demo     # the journey, including an outlier and a NO_QUORUM round
examples/attestation-feed/feed.sh portal   # public portal on 8680
examples/attestation-feed/feed.sh stop     # data is kept; clean deletes the instance
```

`demo` defines the `coldstore-7` feed, has three simulated sources report with
`source-gamma` as an outlier, shows an apply refused because both approvals came
from one organization, closes the round, and records the next round as
`NO_QUORUM`. Then verify a round yourself:

```bash
eval "$(examples/attestation-feed/feed.sh env)"
export PATH="$PWD/tools/yano-feed/bin:$PATH"
yano-feed round get --feed coldstore-7 --round latest --members "$YANO_FEED_MEMBERS" --output round.json
yano-feed verify --bundle round.json --members "$YANO_FEED_MEMBERS"   # exit 5
```

`feed.sh env` prints the node API key and the gateway token; treat its output as
a secret.

## Modules

| Module | Role |
|---|---|
| `products/attestation-feed/profile` | Collections, policies, schemas, the calendar, `Aggregation` (`feed-aggregation-v1`), and the candidate datum |
| `products/attestation-feed/client` | Writes, round close, round bundles, the verifier, the portal and gateway services |
| `products/attestation-feed/cli` | `yano-feed`, shipped as `tools/yano-feed` |
| `products/attestation-feed/ui` | The console, shipped as `product-ui/attestation-feed` |
| `products/attestation-feed/harness` | `feed.sh`, shipped as `examples/attestation-feed` |

The [user guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/ATTESTATION_FEED.md)
covers the data model, the console, every CLI command, portal and gateway
routes, and troubleshooting.

## Status

`experimental` (starter). The decision record is ADR-052. Deferred: the Cardano
publication executor and its thread-token model, a consumer validator, an
aggregation component that would make admission and closure consensus rules, a
round clock based on Cardano slots, a circuit breaker for round jumps,
source-authentication profiles and relays, and anchored verification across the
bundle's two heights.
