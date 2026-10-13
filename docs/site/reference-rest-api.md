# REST API

Base path: `<artifact-api-prefix>/app-chain`, by default `/api/v1/app-chain`.

The prefix is fixed into each JVM, native, or container artifact at build time
with `-PyanoApiPrefix=<path>`. It is not editable launch configuration, and
changing it requires a rebuild.

Yano's API calls an app ledger an *app chain*, so the paths say `app-chain`.

## Chain scoping

Every app ledger endpoint is chain-scoped:

```text
/api/v1/app-chain/chains/{chainId}/...
```

`GET /api/v1/app-chain/chains` lists the hosted chains with their tip height
and state root.

Some endpoints also answer without the `chains/{chainId}` segment, but only
while exactly one chain is configured. With several chains those aliases
return `400`, and with none they return `503`. They are hidden from the
OpenAPI document, and these endpoints have no alias at all: `query`,
`identity`, `admin/members*`, `admin/threshold`, `proof-subjects/*`,
`snapshots/*`, `admin/snapshots/*`, and `observations/*`. **Use
`chains/{chainId}` in every integration.** Swagger UI at `/q/swagger-ui/`
documents the chain-scoped surface.

## Access

Each endpoint has one access level:

| Level | Who may call it |
|---|---|
| **Read** | Anyone, unless `yano.app-chain.api.auth.enabled=true`; then any configured key. Some reads use POST because they carry parameters. |
| **Submit** | Like a read, except a topic-restricted key (`key=topicA\|topicB`) may submit only to its topics. |
| **Privileged** | Only a full API key from `yano.app-chain.api.keys`, even when broad authentication is off. Without a configured key these calls fail closed. |
| **Snapshot admin** | Only a key from `yano.app-chain.api.snapshot-admin-keys`. |

Send the key in the `X-API-Key` header. An unannotated `GET` is a read and any
other unannotated method is privileged, so a new endpoint is never public by
accident.

<!-- illustration: rest-explorer -->

## Endpoints

All paths below are relative to `/api/v1/app-chain/chains/{chainId}`.

### Chains and status

| Method and path | Access | Purpose |
|---|---|---|
| `GET /status` | Read | Role, tip height, state root, pool size, peer connectivity, counters, anchor and sink progress. |
| `GET /tip` | Read | `{chainId, height, stateRoot}` of the last finalized block. |
| `GET /blocks/{height}` | Read | Hashes, roots, proposer, certificate signature count, and the full message list. |
| `GET /blocks?from=&limit=` | Read | Block summaries, ascending, with `messageCount` and `certSignatures`. Defaults to a window ending at the tip. |
| `GET /state/identity` | Read | The genesis-selected commitment profile and the current root. |
| `GET /state/oldest-provable` | Read | The oldest height that still has proofs. |
| `GET /anchor/commitment` | Read | The anchor commitment for this chain. |

### Messages

| Method and path | Access | Purpose |
|---|---|---|
| `POST /messages` | Submit | Body `{"topic":"...","body":"<text>"}` or `{"topic":"...","bodyHex":"<hex>"}`. Returns `202` with the content-derived `messageId`. |
| `GET /messages?limit=100&topic=...` | Read | Recently accepted messages, local and gossiped, with sender, sequence, body hex, and source. |
| `GET /messages/{messageIdHex}` | Read | One finalized message: position (`height`, `index`) plus full content. `404` until it is final. |
| `GET /messages/by-topic/{topic}?fromHeight=&limit=` | Read | Finalized message references on a topic, ascending. |
| `GET /messages/by-sender/{senderHex}?fromHeight=&limit=` | Read | Finalized message references from a member key, ascending. |
| `GET /stream?fromHeight=&topic=` | Read | Server-sent events of finalized messages: replay from `fromHeight`, then live. |

### Proofs and evidence

| Method and path | Access | Purpose |
|---|---|---|
| `GET /state/proof/{keyHex}` | Read | Profile-tagged inclusion or exclusion proof for a canonical state key at the tip or a retained `?height=`. OrderedLog derives its key from a namespace and message id; use the `finalized-message-v1` typed proof subject to avoid constructing it yourself. |
| `GET /state/entry/{keyHex}` | Read | The same lookup without proof bytes. |
| `GET /proof-subjects` | Read | The typed proof subjects this chain offers. |
| `POST /proof-subjects/{subjectId}/proof`, `.../package` | Read | Typed proof in application language. See [State and proofs](/concepts/state-and-proofs/). |
| `GET /messages/{messageIdHex}/proof`, `.../proof-package` | Read | A proof that a finalized message is recorded. |
| `GET /evidence/{messageIdHex}` | Read | A portable, offline-verifiable evidence bundle for a finalized message. |
| `POST /proof/verify` | Read | Verify a proof on the node. |
| `POST /query/{path}` | Read | Run the state machine's read hook against one committed snapshot. |

### Effects

| Method and path | Access | Purpose |
|---|---|---|
| `GET /effects?fromHeight=&limit=` | Read | Emitted effect records, ascending. |
| `GET /effects/{height}/{ordinal}`, `.../proof` | Read | One effect record with this node's runtime status, and its proof. |
| `GET /effects/stats` | Read | Effect counters. |
| `POST /effects/claim` | Privileged | An external worker claims effects to execute. |
| `POST /effects/{height}/{ordinal}/report` | Privileged | An external worker reports a result. |
| `POST /effects/{height}/{ordinal}/requeue`, `.../cancel` | Privileged | Operator controls for one effect. |

### Authenticated snapshots

| Method and path | Access | Purpose |
|---|---|---|
| `GET /snapshots?series=&cursor=&limit=`, `GET /snapshots/{series}/{sequence}`, `GET /snapshots/status` | Read | Snapshot descriptors and status. |
| `POST /snapshots/{series}/{sequence}/proof`, `POST /snapshots/proof/verify` | Read | Prove and verify entries of a snapshot. |
| `POST /admin/snapshots/{series}/{sequence}/{archive\|restore\|evict}` | Snapshot admin | Start a lifecycle job. |
| `GET /admin/snapshots/jobs`, `GET /admin/snapshots/jobs/{jobId}` | Snapshot admin | Lifecycle job status. |

### Operations

| Method and path | Access | Purpose |
|---|---|---|
| `GET /identity` | Privileged | Redacted identity digests that `appchain drift` compares. |
| `GET /state/integrity` | Privileged | The node's state integrity check. |
| `POST /snapshot` | Privileged | Atomic ledger snapshot for member onboarding. Body `{"path":"<fresh dir>"}`. |
| `POST /admin/pause`, `POST /admin/resume` | Privileged | Pause or resume local submissions. |
| `POST /admin/drain-pool` | Privileged | Drop all pending, unfinalized messages. |
| `POST /admin/force-anchor` | Privileged | Anchor the current tip now. |
| `POST /admin/anchor/bootstrap` | Privileged | One-time script-anchor bootstrap. |
| `GET /admin/members` | Privileged | Effective member set and threshold. |
| `POST /admin/members/add`, `.../remove` | Privileged | Stage a member key in or out. Body `{"publicKey":"..."}`. |
| `POST /admin/members/reset` | Privileged | Drop the persisted member override and return to the configured list. |
| `POST /admin/threshold` | Privileged | Set the finality threshold. Body `{"threshold": N}`. |
| `POST /observations/reports`, `POST /observations/wake` | Privileged | Observation reports and wake-ups. |

### Plugin routes

Plugins contribute domain routes below `/api/v1/plugins/{bundleId}/`. A route
accepts `GET` or `POST` and declares its own access, read or privileged; a
route that is undeclared or internal answers `404`. A `POST` body may hold at
most 65,536 bytes, and errors are `{"code": ..., "error": ...}`. Plugin catalog
and lifecycle status is under `/api/v1/plugin-operations` and is always
privileged.

## Submission semantics

**The node signs with its own member key.** The REST caller is trusted local
input — the same model as a wallet talking to its own node. This is why the API
must not be exposed to untrusted callers without authentication.

**The body is opaque.** Use `body` for UTF-8 text or `bodyHex` for arbitrary
bytes. The framework never parses it; only the state machine does.

| Response | Meaning |
|---|---|
| `202` with `messageId` | Accepted into this node's pending pool. Not final yet. |
| `400` with `code` | The application rejected the command at admission. |
| `429` | The pending pool is full. Back off and retry. |
| `503` | The node cannot accept submissions right now. |

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
[section 4 of the app ledger user guide](https://github.com/bloxbean/yano-x/blob/main/docs/APP_CHAIN_USER_GUIDE.md).
The host's resource class is the final word on paths and access:
[`AppChainResource.java`](https://github.com/bloxbean/yano/blob/main/app/src/main/java/org/yanoproject/app/api/appchain/AppChainResource.java).
