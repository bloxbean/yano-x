---
title: Architecture
description: The four layers of an app ledger — consensus, execution, evidence, and integration — the two-plane boundary between deterministic intent and external execution, the components of a Yano node, and composite state machines.
sidebar:
  order: 1
---

An app ledger has four layers and one boundary that matters more than the rest.
This page names the layers, shows that boundary, and maps both onto the
components of a Yano node. To follow a single message through them, see
[Life of a message](/start-here/what-is-an-app-ledger/#life-of-a-message).

**You'll learn:** what each layer does and who provides it, why consensus code
never performs external actions, which component of a node does what, and how a
composite runs several state machines under one root.

**Before you start:** read [What is an app ledger?](/start-here/what-is-an-app-ledger/)

## The four layers

<!-- illustration: architecture-layers -->

| Layer | What it does | Provided by | What it guarantees |
|---|---|---|---|
| 1. Consensus | Members order messages into blocks and certify each block with a threshold of signatures. | Yano | A final block is never rolled back. |
| 2. Execution | One deterministic state machine computes each block's new state. Members run it while they vote. | Yano runs it. `ordered-log` is built in; Yano X provides the other stock machines and composites. | Every honest member computes the same state. |
| 3. Evidence | A state root after every block, proofs against it, and optional Cardano anchors. | Yano | A record can be checked without trusting the node that served it. |
| 4. Integration | APIs and clients, plus effects that act on external systems after finality. | Yano provides the APIs, the effect runtime, and the webhook executor. Yano X adds connectors, SDKs, and products. | External actions never run inside consensus. |

Two identities stay separate across the layers. The member that accepts a
message signs its envelope; that is transport. When a workflow needs a person
or organization to approve something, their signature travels inside the
message body; that is business authority.

## The two-plane design

This is the boundary to internalize: deterministic intent and external
execution never mix.

<!-- illustration: two-planes -->

The state machine records **what is authorized**. It never calls Kafka, IPFS,
S3, Cardano, an ERP, or a webhook during consensus. After the block is final, an
executor outside consensus performs the action and reports the outcome back as
a new message.

The guarantee is:

- **exactly-once** incorporation of each result into state; and
- **at-least-once** external execution.

So every executor and every receiver must be idempotent under the supplied
idempotency identity. See [Effects](/concepts/effects/).

## Inside a Yano node

<!-- illustration: architecture-components -->

| Component | Responsibility | Key property |
|---|---|---|
| REST API, SSE, and clients | Submit messages, read blocks and state, stream finality, request proofs. | Clients can verify evidence rather than trust a response. |
| `AppChainManager` | One per node: shared message transport, catch-up server, dispatch by chain id. | Chains share only networking and the node's view of Cardano. |
| Message pool | Holds admitted messages until a block includes them. | In memory; a full pool answers 429. |
| Consensus engine | Runs the two-phase round, view changes, and catch-up for one chain. | A serial event loop; each final block is one atomic write. |
| Member group | Knows the members and threshold at every height. | Old blocks verify against the members of their time. |
| State machine | Interprets message bodies and writes deterministic state. | No clock, randomness, or external I/O. |
| Ledger store | Blocks, certificates, authenticated state, indexes, vote locks. | One RocksDB instance per chain. |
| Anchor and effect services | Optional anchoring, L1 observers, sinks, and the effect runtime. | External failures neither fork nor block consensus. |
| Plugin catalog | Loads manifested bundles and activates their contributions. | Trusted in-process code; it checks manifests, but it is not a sandbox. |

## Composite state machines

One app ledger selects exactly **one** state machine. A composite is a state
machine that hosts several reusable components and the workflows between them
under one state root.

<!-- illustration: composite-profile -->

A composite profile declares each component's id, version, configuration id,
topics, query paths, effect quota, and activation heights, plus its workflows in
execution order. Components read and write only their own namespace; changes
that span components go through a declared workflow. The profile is canonical
data: in fixed mode it is stored at height 1 and checked on restart and at the
start of every block, so a different order or configuration fails rather than
silently forming a different application.

Two modes:

- **Fixed** keeps one profile for the life of the chain.
- **Governed** keeps an append-only chain of profile epochs. Operators install
  the reviewed current and future profiles on every member first, then a
  threshold authorizes one exact profile digest and a future activation height.
  Changing YAML or a JAR alone never changes consensus behavior.

[Declarative bindings](/bindings/), an experimental feature, build a composite
from YAML instead of Java; the compiled rules are part of the committed profile.

## Data on disk

Each node keeps three sibling stores. Mixing them up is a correctness bug:

| Store | Contents | Authority |
|---|---|---|
| `chainstate/` | Cardano L1 state | Authoritative |
| `appchain-chainstate/` | App ledger state | Authoritative |
| `appchain-indexers/` | Local read indexes | Rebuildable, never authoritative |

Derived indexes never advance beyond authoritative app ledger state, and app
ledger data is never placed below L1 `chainstate`.

## Deployment shape

- **JVM:** copy a self-contained manifested bundle into every member's plugin
  directory, select it in configuration, and restart. No Yano rebuild is needed.
- **Native:** Yano's native image is core-only. Yano X extensions are JVM-only.
- **Several ledgers per node:** one node can host several independently
  configured chains.
- **Executor placement:** effects can run on a designated member, a dedicated
  executor node, or an external worker through the claim and report API.

See the [deployment guide](/deployment/) for running members.

## Where to go next

- [Consensus and finality](/concepts/consensus-and-finality/): how a block
  becomes final.
- [State and proofs](/concepts/state-and-proofs/): what a root commits to and
  how to verify it.
- [Effects](/concepts/effects/): the execution plane in detail.
- [Determinism rules](/concepts/determinism-rules/): what consensus code may
  not do.
