---
title: REST API
description: The public app-chain HTTP surface — submission, reads, proofs, streaming, and admin — plus the submission limits, backpressure, and replay-protection rules that govern it.
sidebar:
  order: 2
---

Base path: `<artifact-api-prefix>/app-chain`, by default `/api/v1/app-chain`.

The prefix is fixed into each JVM, native, or container artifact at build time
with `-PyanoApiPrefix=<path>`. It is not editable launch configuration, and
changing it requires a rebuild.

## Chain scoping

Every chain endpoint below is also available chain-scoped:

```text
/api/v1/app-chain/chains/{chainId}/...
```

The chain-less form keeps working while exactly one chain is configured. With
several chains it returns `400` (ambiguous), and `503` when no chain is enabled.

**Prefer `chains/{chainId}` in new integrations.** Swagger UI at
`/q/swagger-ui/` documents the chain-scoped surface only — the chain-less
aliases are deliberately hidden from the OpenAPI document, because on a
multi-chain node they can only answer `400`.

When API-key authentication is enabled, every request needs `X-API-Key`.

## Endpoints

### Chains and status

| Method and path | Purpose |
|---|---|
| `GET /chains` | Hosted chains: `[{chainId, tipHeight, stateRoot}]`. |
| `GET /status` | Role, tip height, state root, pool size, peer connectivity, counters, anchor and sink progress. |
| `GET /tip` | `{chainId, height, stateRoot}` of the last finalized block. |

### Messages

| Method and path | Purpose |
|---|---|
| `POST /messages` | Submit. Body `{"topic":"...","body":"<text>"}` or `{"topic":"...","bodyHex":"<hex>"}`. Returns `202` with the content-derived `messageId`. |
| `GET /messages?limit=100&topic=...` | Recently accepted messages (local and peer), with sender, sequence, body hex, and source. |
| `GET /messages/{messageIdHex}` | One finalized message: position (`height`, `index`) plus full content. |
| `GET /messages/by-topic/{topic}?fromHeight=&limit=` | Finalized message refs on a topic, ascending. |
| `GET /messages/by-sender/{senderHex}?fromHeight=&limit=` | Finalized message refs from a member key, ascending. |

### Blocks, proofs, and evidence

| Method and path | Purpose |
|---|---|
| `GET /blocks/{height}` | Hashes, roots, proposer, certificate signature count, full message list. |
| `GET /blocks?from=&limit=` | Paged block summaries, ascending. Defaults to a window ending at the tip. |
| `GET /state/proof/{keyHex}` | MPF inclusion proof for a state key against the committed root. For `ordered-log` the key **is** the message id, and the response includes the value and `finalizedAtHeight`. |
| `POST /proof-subjects/{subjectId}/proof` | Typed proof in application language. See [State and proofs](/concepts/state-and-proofs/). |
| `GET /evidence/{messageIdHex}` | A portable, offline-verifiable evidence bundle for a finalized message. |

### Streaming

| Method and path | Purpose |
|---|---|
| `GET /stream?fromHeight=&topic=` | SSE stream of finalized messages: replay from `fromHeight`, then live. |

### Admin

These are privileged. Protect them with an API key.

| Method and path | Purpose |
|---|---|
| `POST /snapshot` | Atomic ledger snapshot for fast member onboarding. Body `{"path":"<fresh dir>"}`. |
| `POST /admin/pause`, `POST /admin/resume` | Pause or resume local submissions. |
| `POST /admin/drain-pool` | Drop all pending, unfinalized messages. |
| `POST /admin/force-anchor` | Anchor the current tip now. |
| `GET /admin/members` | Effective member set and threshold. |
| `POST /admin/members/add`, `.../remove` | Stage a member key in or out. Body `{"publicKey":"..."}`. |
| `POST /admin/members/reset` | Drop the persisted member override and return to the configured list. |
| `POST /admin/threshold` | Set the finality threshold. Body `{"threshold": N}`. |

Plugins contribute their own bounded, read-only routes below
`/api/v1/plugins/<bundle-id>/`.

## Submission semantics

**The node signs with its own member key.** The REST caller is trusted local
input — the same model as a wallet talking to its own node. This is why the API
must not be exposed to untrusted callers without authentication.

**The body is opaque.** Use `body` for UTF-8 text or `bodyHex` for arbitrary
bytes. The framework never parses it; only the state machine does.

| Limit | Default |
|---|---|
| `max-message-bytes` | 64 KB |
| `default-ttl-seconds` | 600 — an unfinalized message expires out of the pool |
| `pool.max-messages` | 10,000 |

Topics starting with `~` are reserved for consensus and system traffic.

### Backpressure

When the pending pool is full, `POST /messages` returns **429** and the message
is neither stored nor relayed. Back off and retry.

Inbound gossip dropped by a full pool is counted in `GET /status` under
`drops.pool_full`. That is not an error — the sender's own node already holds
the message.

### Replay protection

Every envelope carries a per-sender sequence number. A message whose seq is at
or below the sender's last **finalized** seq is a replay and is rejected at
admission on every ledger node, counted as `drops.stale_seq`.

Gaps are allowed and meaningless — seqs are wall-clock seeded, so a restart
never reuses one. **The seq does not define ordering**; the sequencer does.

```yaml
yano.app-chain.message.enforce-sender-seq: true
```

With that flag the rule becomes consensus-visible: followers reject any block
whose per-sender seqs are not strictly increasing above the finalized floor. It
defaults to off for compatibility, and — like the state-machine id — **all
members must agree on it**.

## Clients

Prefer a typed client over raw HTTP where one exists:

| Artifact | What it adds |
|---|---|
| `yano-x-client` | REST, SSE, and client-side proof verification. |
| `yano-x-composite-client` | Governed-profile finality, one-root MPF, epoch-chain, and authorization-policy verification. |
| `yano-x-spring-boot-starter` | Spring Boot auto-configuration for the client SDK. |
| `yano-appchain-core-testkit` | JUnit 5 `@AppChainCluster` embedded clusters. |

## Deeper reading

The exhaustive API, configuration, security, and operations reference is
[section 4 of the app-chain user guide](https://github.com/bloxbean/yano-x/blob/main/docs/APP_CHAIN_USER_GUIDE.md).
