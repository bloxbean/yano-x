# ADR-060: App-message gossip throughput and consensus priority

Status: Proposed (revised after design review)

Date: 2026-11-25

Depends on: Yaci ADR 0019 (app-message submission throughput and priority). Yano needs only
a dependency bump to that Yaci release. Its host API passes protocol-100 agent factories
through and never configures protocol 100 itself. The `batchSize(10)` in Yano's
`ServeSubsystem` is the Cardano `TxSubmissionConfig`, not app-message gossip.

Evidence: [`perf-test/RESULTS-2026-11-25.md`](../perf-test/RESULTS-2026-11-25.md) (before) and
[`perf-test/RESULTS-2026-11-25-gossip.md`](../perf-test/RESULTS-2026-11-25-gossip.md) (after).

## Context

The Yano 0.1.0-pre22 throughput study fixed three bottlenecks in the app-ledger framework.
After those fixes, one chain sustains about 2,500 msg/s on 3 and 5 nodes. Above about
2,000 msg/s offered, the gossip of app messages between members becomes the limit.

**Messages are lost at the gossip queue.**
- The protocol-100 window comes from `unionTransportConfig` / `transportConfig`, which leave
  Yaci's default of 10. The client queue holds 1,000 messages.
- When that queue is full, enqueue rejects the message, and `AppPeerClient.enqueue` ignores
  the result. `AppPeerClient` re-offers its 200-entry replay queue only on reconnect.
  `SharedAppPeerLink` retries its own replay queue on the next enqueue.

**Lost messages become permanent.** The proposer sequences what it has received. Once a later
seq from a sender is finalized, an earlier seq still in flight is dropped as stale, although
the origin returned HTTP 202. The receiving pool can also drop a message as `pool_full`
after marking it seen. Every later copy is then ignored as a repeat sighting. One run lost
1,946 of 9,000 accepted messages at only 300 msg/s.

**Consensus messages share the queue.** Consensus traffic travels as app messages on seven
`~consensus/*` topics. When the queue is full they are rejected too, and chains stalled.

**Every relayed copy is verified again.** Each member relays every message to every peer, so
each directed link carries the full rate. The inbound validator
(`AppLedgerManager.verifyByChain`) runs a full Ed25519 check on every copy, on the Netty
event loop. Block validation (`signedEnvelopes`) then verifies the same envelope once more.

## Decision

### D1. Size gossip for the rate and the round-trip time

`transportConfig()` and `unionTransportConfig()` set these values:

| setting | value |
|---|---|
| `batchSize` = `maxUnacknowledged` | 2,048 |
| `maxBodiesBytesPerRequest` | 2 MiB |
| `maxPendingMessages` | `max(pool.max-messages, 10_000)` per chain |
| `maxPendingPriorityMessages` | 4,096 |
| `maxPendingPriorityBytes` | `4 × block.max-bytes` |

Notes on these values:

- **Window.** One link carries about `window / (2 × RTT)`. A window of 2,048 gives roughly
  3,000 msg/s at 325 ms RTT, which covers a whole chain's capacity on one link.
- **Shared-session sums.** The union config of a shared session carries every chain, so its
  pending capacities are the sums over chains, not the maximum.
- **Priority byte bound.** `maxPendingPriorityBytes` stops proposals, which carry whole
  blocks, from holding unbounded heap on a slow link.
- **Reconnect replay.** The `AppPeerClient` replay queue is keyed by message ID and bounded
  at 10,000 entries rather than 200, and at 64 MiB of message bytes, so whole-block proposals
  cannot pin unbounded heap per peer.
  - Enqueue is O(1) amortized: the oldest entries make room. Expired entries are dropped when
    the queue is replayed.
  - A message already in the queue is still handed to the live Yaci agent. Proposal
    re-gossip and observation re-diffusion re-send on purpose, and the agent may have
    rejected the first copy because a lane was full.
  - A reconnect re-offers ordinary messages in arrival order, so a sender's seqs stay in
    order. It re-offers only the 256 most recent consensus and anchor envelopes, newest
    first: older rounds are finalized or re-gossiped, and stale proposals would otherwise
    fill the priority lane's byte budget before the current round.
  - The new connection's server pulls each message again, and the receiver's seen-set or pool
    drops repeats. A reconnect costs bandwidth but loses less.

These are node-local transport tunables. They are not part of genesis, the commitment
profile or any consensus rule. A peer that runs the older Yaci still interoperates at
today's rate.

### D2. Consensus and anchor messages use the Yaci priority lane

The priority predicate selects topics that begin with `~consensus/` (all seven consensus
topics) and `~anchor/` (anchor co-signatures). Losing these stalls the chain, and each is
one small message per member per round.

`~bridge/` and the `~obs-diffusion/` observation topics stay in the ordinary lane. They carry
data volume and tolerate delay, and they recover through their own catch-up.

Only members sign these topics, and the inbound validator checks membership before relay. A
non-member therefore cannot reach the lane. REST rejects client `~` topics except
`~topics/registry`, and that topic is ordinary.

A faulty member could still flood the lane. The Yaci client caps priority IDs at half of
each reply's window (0019 D3), so the flood cannot starve ordinary gossip.

### D3. A verified-envelope cache instead of re-verification

`AppMessageSigner.verifyEnvelope(AppMessage)` becomes the single Ed25519 entry point for
envelopes. It runs these steps in order:

1. Check the profile: a non-null `authProof` and a 32-byte sender.
2. Check `hasValidMessageId()`.
3. Look up the pair `(messageId, authProof)` in a bounded LRU cache.
4. On a miss, verify with the pinned BouncyCastle verifier, and add the pair to the cache
   only on success.

The cache key is sound. `messageId` is a content hash of every signed field (chain, topic,
sender, seq, expiry, body), and step 2 recomputes it. A cache hit therefore proves that
these exact signed bytes verified under this exact signature and sender.

The cache does not cover everything else:
- `authScheme`, chain id and sender membership at the target height are still checked by
  every caller, as today.
- REST submission does not use the cache. Locally signed envelopes enter it only when this
  node itself verifies them, for example when the proposer validates its own block. Signers
  are pluggable (KMS/HSM), so a signature is never trusted just because this node produced it.

Callers that use the cache:
- the inbound validator, for every topic including `~consensus/*`;
- block validation (`signedEnvelopes`), in proposal, catch-up and selection.

Relayed genuine copies carry the same `authProof`, so N−1 of N copies, and the block-time
check, become cache hits. A forged copy with the same ID but another `authProof` misses and
is fully verified and rejected. No validator path accepts a message without either a
successful verification or a cache hit for its exact signature. The cache holds 131,072
entries (about 50 s of traffic at 2,500 msg/s; roughly 30 MB).

### D4. Validate an inbound reply in parallel

The validators of a shared session (`AppLedgerManager`) and of a per-chain session
(`AppLedgerRuntime.transportConfig()`) implement Yaci `AppMsgValidator.validateAll`:
- Each envelope's checks (per-chain size and TTL, profile, membership, `verifyEnvelope`) run
  as a parallel stream on the common fork-join pool, once a reply holds at least 32
  envelopes. They take no agent or engine lock: they read immutable config, concurrent
  counters, the volatile member snapshot, the striped cache and the store's tip height.
  `signedEnvelopes` already uses that pool. The Netty event loop waits as a stream caller,
  not as a pool task, so it cannot deadlock.
- Results keep input order. A check that throws rejects only its own envelope.

### D5. Re-admit after a full pool

When the pool rejects a message as `pool_full`, the runtime removes its ID from
`seenMessageIds`. A later copy, relayed by another peer or re-offered after a reconnect, can
then be admitted once there is room. A re-admitted copy is pooled but not relayed again: this
node already relayed the first copy, and relaying again would bounce the message between
members while their pools are full. Pool-full is still counted as a drop.

## Non-goals

- **Loss above saturation.** When the offered load exceeds what a chain can finalize, pools
  fill and messages can still be lost once a later seq from the same sender finalizes. D5
  narrows this, but does not guarantee delivery above saturation. The stale-seq rule (I1.2)
  is unchanged. Holding back seq N+k until N finalizes or times out would change consensus
  semantics and needs its own ADR.
- **Proposal pipelining, the fixed-delay propose tick, and the restart view-change delay.**
- **A dedicated consensus channel.** Consensus messages still share the connection and the
  protocol-100 session with ordinary traffic. D2 protects them from rejection and offers them
  first, but a vote queued mid-round still waits for that round's remaining body chunks.
  Isolating their latency needs a second app-message session (another mini-protocol ID) or a
  dedicated connection. That would be a follow-up if measurements show a vote delayed behind
  bulk traffic.

## Validation

Unit tests:
- transport config values, including the shared-session sums and the priority predicate
  (consensus and anchor in, bridge and observation out);
- `verifyEnvelope`:
  - a hit for the same `(id, proof)`;
  - a miss and rejection for the same id with a forged proof;
  - a tampered body caught by `hasValidMessageId`;
  - no caching of a failed verification;
  - eviction;
- `validateAll` keeps order and rejects forged consensus envelopes;
- the replay queue bound and duplicate handling;
- re-admission after `pool_full`.

Integration:
- locally published Yaci and Yano snapshots, the full Yano X build, and its distribution;
- the study's scenarios re-run:
  - local 3-node;
  - cluster A (3 VM nodes);
  - cluster B (2 VM + laptop, with ingress spread over all three);
  - cluster C (4 VM + laptop).

  Each reports finalized msg/s, delivery, latency and stalls, at 1,000, 2,000 and 3,000
  msg/s offered.
