# App-message gossip fixes: throughput re-run (2026-11-25)

This re-runs the scenarios of [`RESULTS-2026-11-25.md`](RESULTS-2026-11-25.md) with the app-message gossip fixes:
- **Yaci ADR 0019:**
  - configurable protocol-100 window;
  - chunked body requests;
  - priority lane;
  - constant-time client queues;
  - batch validation;
  - acknowledgement fixes.
- **Yano X [ADR-060](../adr/060-app-message-gossip-throughput.md):**
  - window of 2,048;
  - consensus and anchor topics in the priority lane;
  - verified-envelope cache;
  - parallel reply validation;
  - larger replay queue;
  - re-admission after `pool_full`.

**Inputs.** All are built locally:
- Yaci `0.5.0-pre18-gossip-613c6ec-SNAPSHOT`, branched from `next` = 0.5.0-pre17;
- Yano `0.1.0-pre23-gossip-SNAPSHOT`, which is Yano `main` with that Yaci;
- Yano X branch `perf/appmsg-gossip-throughput`, stacked on PR #112.

**Setup.** As in the earlier study:
- the perf template has 1 s blocks, at most 5,000 messages per block, and a pool of 50,000;
- messages are signed by the nodes and submitted over REST, 128 bytes each, for 30 s;
- results are finalized msg/s;
- "delivered" is the share of HTTP-accepted messages that were finalized.

## Results

### Local M4, 3 nodes (fresh cluster)

| chain | 1000 | 2000 | 3000 offered |
|---|---|---|---|
| orders | 997 (p50 0.51 s) | 2,049 (p50 0.58 s) | 2,976 (p50 0.63 s) |
| registry | – | – | 3,074 (p50 0.58 s) |
| credits | – | – | 2,974 (p50 0.63 s) |

Before, this cluster saturated at about 2,500 msg/s.

### Cluster A: 3 VM nodes (fresh cluster)

| chain | before @3000 | after @1000 / 2000 / 3000 / 4000 |
|---|---|---|
| orders | 2,535 (p50 3.6 s) | 986 / 1,971 / 2,956 (p50 0.49 s) / 2,525 (saturated, 100 % delivered) |
| registry | 2,533 (p50 2.3 s) | – / 1,970 / 2,955 (p50 0.48 s) / – |
| credits | 2,456 (p50 3.6 s) | – / 2,015 / 2,773 (p50 0.64 s) / – |

Every run delivered 100 %.

### Cluster B: 2 VM nodes + laptop over an SSH tunnel (RTT ≈ 325 ms)

| ingress | before | after |
|---|---|---|
| VM nodes @1000 / 2000 / 3000 | 984 / 1,969 / 2,953 | 987 / 1,958 / 2,938 (p50 0.49 s) |
| all 3 nodes @300 | 1,946 of 9,000 lost, p90 35 s | 9,000 of 9,000, p90 0.99 s |
| all 3 nodes @1000 | – | 30,000 of 30,000, p50 0.64 s |
| all 3 nodes @2000 | – | 59,999 of 60,000 (one stale-seq drop), p50 0.62 s |

### Cluster C: 4 VM nodes + laptop, threshold 3

| run | before | after |
|---|---|---|
| orders @1000 / 2000 | 993 / 1,986 | 1,026 / 1,979 |
| orders @3000 | 2,483, then a repeat collapsed to 201 msg/s with ~24k lost | 2,463 and 2,461 on the repeat, no stall |
| orders @4000 | – | 2,390 (saturated) |
| registry @2000 / 3000 | 2,000 / 2,354 | 1,969 / 2,467 |
| credits @2000 / 3000 | 1,985 / 2,529 | 1,630 / 1,936 (see below) |
| all 5 nodes, orders @1000 / 2000 | – | 1,009 / 1,959, 99.94–100 % delivered |

## Observations

- **Below saturation, gossip is no longer the limit.** Latency stays near one block interval (p50 about 0.5 s) up to
  3,000 msg/s on 3 nodes, including over the 325 ms tunnel. The saturation point is now block execution and
  commit:
  - 3 VM nodes: about 2.5–2.95k msg/s;
  - 5 nodes on the VM CPU: about 2.4–2.5k msg/s.
- **Consensus no longer stalls under overload.** The chain kept finalizing at 4,000 msg/s offered. The 5-node
  collapse did not recur.
- **Remaining loss is the stale sender-seq rule (I1.2), not gossip drops.**
  - Each node relays every message to every peer. Copies of one sender's messages therefore take different paths
    and can reach the proposer out of seq order.
  - Once seq N+k is finalized, the earlier seq N is dropped as stale. With ingress spread across nodes this cost
    1 of 60,000 messages on B and 4–35 per run on C.
  - Removing it needs a consensus-level rule, for example holding back a gap for a bounded time. ADR-060 lists
    this as a non-goal.
- **Credits was slower on C than in the first study.** Its blocks came every 3.1 s at 2,000 msg/s, with a view
  change at height 86. Credits ran after the orders and registry overload runs on the same five nodes. The cause
  was not investigated further.
- **The seen-id cap (200k) is reached at these rates.** Status showed `seenIds` = 200,000 on cluster B. Cap
  evictions then let repeated copies through to the pool's duplicate check. This is harmless, but it is more work
  under load.
- **`pool_full` re-admission worked.** The 4,000 msg/s run on C overran the 50,000-message pools: about 2,400 drops
  per VM node. 115,542 of about 115,700 accepted messages were still finalized.
