---
title: "Troubleshooting"
description: "Symptoms you are likely to meet when you run an app ledger, what causes them, and how to fix them. Commands assume an extracted yano-x-jvm-<version>.zip…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/deployment-troubleshooting.md"
---
Symptoms you are likely to meet when you run an app ledger, what causes them,
and how to fix them. Commands assume an extracted `yano-x-jvm-<version>.zip`:
`./yano.sh appchain cluster <command>` runs the packaged cluster launcher, and
the showcase wraps the same commands as `./showcase.sh <command> --instance <name>`.

Start with these two:

```bash
./yano.sh appchain cluster status        # health, per-chain tips and roots, consistency
./yano.sh appchain cluster logs 0        # the last lines of node 0's log; add -f to follow
```

Each node's `GET /api/v1/app-chain/chains/{chainId}/status` reports its role,
tip height, state root, pending pool size, peers, and whether it is `stalled`.

## A port is busy

| Symptom | Cause | Fix |
|---|---|---|
| `HTTP range 7070-7072 unavailable; using …` (a warning) | The default range is taken, so the launcher moved to a free one. | Use the ports it printed. |
| `explicit HTTP range … is busy (port N)` or `explicit server range … is busy (port N)` | You asked for a range, with `--http-base`, `--server-base`, or `YANO_CLUSTER_HTTP_BASE`, and a port in it is in use. An explicit range is never moved. | Stop whatever holds the port, or pick another range. |
| The showcase fails on port 7070 | The showcase always passes its ports explicitly, defaulting to 7070 and 13337. | Start the instance with free ranges, for example `--http-base 7170 --server-base 9170`. The instance remembers them. |
| `node N is already running` | The cluster is already up. | Use it, or `./yano.sh appchain cluster stop` first. |

## Accepted, but not final yet

| Symptom | Cause | Fix |
|---|---|---|
| `POST …/messages` returned 202, but `GET …/messages/{id}` answers 404 `No finalized message with id …` | 202 only means one member queued the message. It is final once a block containing it is. | Poll until the lookup returns a height, as in [Configure](/deployment/configure/), and then read the application's result. |
| It never becomes final | Too few members are connected to reach the threshold, or the leader cannot complete rounds. | Check `cluster status` and the logs. A round that times out moves to the next leader automatically. |
| The message disappeared | Pending messages live in memory. A message expires from the pool after its TTL (10 minutes by default), and one that no other member had received is lost if its member restarts. | Check that it was not finalized, then submit it again. |
| Final, but nothing changed | The command broke a business rule when the block ran: a deterministic no-op on every member. | Read the result, fix the cause, and submit a new message. |

## Members disagree: MISMATCH

`cluster status` prints `<chain>: AGREED` or `<chain>: MISMATCH`. It compares
the current state root each running node reports, **without aligning heights**,
and it skips nodes that do not answer.

| Cause | How to tell | Fix |
|---|---|---|
| One member is a block behind, or a block finalized between two reads | MISMATCH clears on the next `status` | Run `status` again. `./showcase.sh verify all --instance <name>` waits until every node reports the same tip height and root. |
| No node reports the chain | The chain is in the configuration file but not hosted, or no node is ready yet | See "A chain is missing" below. |
| A member computes a different root | Its log shows `Proposal state-root mismatch at height … — rejecting`; the member stops voting and stalls | The state machine is not deterministic, or members run different code or configuration. Compare members with `./yano.sh appchain drift <project> --peer <url> …`, and see [Determinism rules](/concepts/determinism-rules/). |

## A chain is missing

| Symptom | Cause | Fix |
|---|---|---|
| `404 {"error":"Unknown app chain: <chainId>"}` | The node does not host that chain id. | Check the spelling and the node's configuration: `./yano.sh appchain cluster chains` lists the chains in the configuration file. To add one, follow [Add a chain](/deployment/add-chain/). |
| `400 … app chains are hosted — use /app-chain/chains/{chainId}/...` | A route without a chain id was used on a node that hosts several chains. | Use the chain-scoped route `/api/v1/app-chain/chains/{chainId}/…`. |
| `503 App chain is not enabled on this node` | The node hosts no app ledger. | Add a chain to the node's configuration. |
| The node does not start: `… is not in the configured member list (yano.app-chain.members)` | The node's member key is not in the member list. | Fix `yano.app-chain.members` or the node's `signing-key`. |
| The node does not start: `… is not selected (available: […])` | The configured `state-machine` id does not match any loaded state machine. | Fix the id, or install the plugin bundle on that node. |
| The node does not start: `Retained state-commitment profile, fingerprint, or genesis id is incompatible` | The ledger on disk belongs to another identity. | Restore the original identity settings. Do not reset retained data; see [Chain identity](/concepts/chain-identity/). |
| The node does not start: `consensus quorums do not intersect in an honest member` | The threshold is too low for the member count and fault bound. | Choose a threshold `t` with `2t − n > f` and `t ≤ n − f`. See [Keys and trust](/concepts/trust-model/#how-many-members-can-fail). |

## HTTP 429 or 503 on submit

| Symptom | Cause | Fix |
|---|---|---|
| `429 … pending pool is full (10000 messages) — retry later` | The member's pending pool is full, 10,000 messages by default. Inbound gossip is dropped and counted too. | Retry with backoff. Raise `pool.max-messages` for the chain (it must be at least `block.max-messages`), or drain faster with a shorter `block.interval-ms` or a larger `block.max-messages`. |
| `503 Submissions are paused (admin)` | An operator paused submissions. | Resume them with the privileged `/admin/resume` route. |
| `400` with a code | The state machine refused the command at admission. | Fix the command; nothing was queued. |

## A member is stalled

A member stalls when a peer is ahead and it has made no progress for 60 seconds.
Its log shows `App chain '<id>' appears stalled: local tip …, best peer tip …`,
the node raises `AppChainStalledEvent`, its status shows `stalled`, the
`yano.appchain.stalled` gauge is 1, and the `appchain` health group reports it.

| Cause | Fix |
|---|---|
| It cannot apply certified blocks because it computes a different state root | Look for `Catch-up block state-root mismatch` or `Proposal state-root mismatch` in its log. Fix the non-determinism, or deploy the same plugins and configuration as the other members (`./yano.sh appchain drift`), then restart it. |
| It cannot reach its peers | Check connectivity and the peer list; catch-up resumes on its own once a peer is reachable. |
| Its own view of Cardano is behind | Catch-up pauses until the block's Cardano reference is within its view; let its Cardano node sync. |
| It is far behind after a long outage | Let catch-up run, or restore a recent [snapshot](/concepts/recovery/#snapshots) and let it catch up the rest. |

Restarting a member is safe: finalized history is immutable and vote locks are
persisted. Never delete `appchain-chainstate/` to "fix" a stall; deleting
`appchain-indexers/` only rebuilds read indexes. See
[Restart, catch-up and snapshots](/concepts/recovery/).
