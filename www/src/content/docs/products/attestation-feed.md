---
title: Attestation Feed
description: An experimental, configuration-only consortium observation ledger on the governed authenticated map, with a deterministic aggregation every verifier recomputes, approval-closed rounds, a candidate Cardano datum, a portal, a gateway, and a console.
sidebar:
  order: 9
---

The Attestation Feed (ADR-052) is an **experimental starter**: the observation
ledger that ADR-046 §4.6 sequences last and that Yano ADR app-layer/014 lists
as available today. It declares three collections on the stock governed
`authenticated-map` state machine: feeds (unit, scale, round calendar, sources,
quorum, outlier rule), observations (one per source and round, signed by the
source's own actor key), and round records (closed through an approval round
with two publishers from distinct organizations). The aggregate is a pure
function of the proven observations and the proven policy: it is not computed
on chain, every verifier recomputes it and compares it with the record, and a
disagreement is a flagged failure. It is not the oracle pipeline of ADR
app-layer/012, nothing is published to Cardano, and it must not be pitched as a
public price oracle.

## The journey

1. **Define.** `yano-feed feed create` writes the feed policy as a `feed-admin`:
   description, unit, scale, `epochStart` and `roundSeconds`, 1–16 sources, the
   quorum, the outlier tolerance, and the value bounds.
2. **Observe.** Each `source` records its reading for a round with
   `yano-feed observe` (or the console's Source view through the gateway): a
   signed integer at the feed's scale and the source's signed time, under
   `<feed>/<round>/<source>`, with `PUT_IF_ABSENT`.
3. **Close.** A `feed-operator` proposes the round record computed at one
   height (`round propose`); two `publisher`s from distinct organizations
   approve, each after recomputing the round at that height (the CLI and the
   gateway refuse an approval whose numbers they cannot reproduce); `round
   apply` submits the map command with the approval reference, applied once.
4. **Read and verify.** `yano-feed round get`, the portal
   (`/feeds/{id}/rounds/{n}`), or the console: the policy, every source with its
   disposition (`ACCEPTED`, `OUTLIER`, `ABSENT`, `FOREIGN_WRITER`,
   `EQUIVOCATED`, `WRONG_ROUND`, `OUT_OF_RANGE`), the recomputed result beside
   the record, and the candidate datum. `yano-feed verify` checks a bundle
   offline at the ADR-047 trust levels.

## What it proves

A verified `feed-round-v1` bundle says: at the closing height the policy was
this and each configured source had exactly this observation or none; the
`feed-aggregation-v1` rule (the lower median after outlier and quorum rules,
ADR app-layer/012 §8.4 reduced to one reporter per source) yields this result;
the consortium recorded exactly that result at a later height through a proven
approval consumption and never rewrote it; the roots were certified by the
pinned members. It does not say that a reading is true, that sources are
independent, that the closing height was fair (re-answer the round later and
compare), or that anything reached Cardano.

## Modules and setup

`products/attestation-feed/{profile,client,cli,ui,harness}` on the Trust
Registry's map client and the DPP Starter's approval-round patterns. The
launcher `feed.sh up | demo | portal | gateway` starts a three-member chain,
runs the cold-store temperature demo (three simulated sources with one
outlier, a same-organization approval refused, the round closed, a `NO_QUORUM`
round), and serves the portal on 8680 and the gateway on 8690. The full guide
is [`docs/appchain/ATTESTATION_FEED.md`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/ATTESTATION_FEED.md).

## Deferred

The Cardano publication executor and thread-token UTxO model, the consumer
validator, the aggregation composite component that would make admission and
closure consensus rules, the L1-slot round clock, the round-jump circuit
breaker, source-auth profiles and relays, and anchored verification across the
bundle's two heights (ADR-052 §8).
