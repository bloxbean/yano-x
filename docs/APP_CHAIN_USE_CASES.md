# Use cases

What you can build today with a Yano app ledger, and how much you need to
bring. Yano's command line, configuration, and APIs call an app ledger an *app
chain*; you will see that name in commands and settings below.

- **Part A**: the default distribution, out of the box. Stock state machines
  and bundled profiles, configured and driven with typed commands.
- **Part B**: the default distribution plus your own state machine, packaged
  as a plugin JAR.
- **Part C**: Yano as a library, embedded in your own application.

Each example gives the problem, why an app ledger fits better than the usual
alternatives, a setup sketch, and the verification story: what a third party
can prove without trusting one node. For runnable paths from these use cases to
a local cluster, proofs, effects, and role-aware workflows, start with the
[tutorials](appchain/tutorials/README.md).

The supported pilot posture today is a **trusted-member permissioned group**
with registered keys. A group whose members only partly trust each other also
needs co-attested effect outcomes and continuous independent auditing; see
[What this is not for (yet)](#what-this-is-not-for-yet).

Every use case inherits the same backbone:

| Property | Mechanism |
|---|---|
| Total order of records | A leader per height proposes hash-linked blocks |
| Multi-party agreement | Ed25519 finality certificates signed by a threshold of members, verified by every member |
| Tamper evidence | Content-derived message ids, a Merkle root over each block's messages, an authenticated state root |
| Independent verifiability | Inclusion and exclusion proofs against a state root that can be **anchored to Cardano** |
| Neutral custody | Every member holds the full ledger; there is no single broker |
| Cardano without extra infrastructure | The Yano node is its own Cardano node: it submits and observes anchors itself |

---

## Part A: out of the box

Nothing in this part needs a custom state machine. The JVM distribution,
member keys, configuration, and REST or client commands are enough for the
bundled capabilities. `ordered-log` keeps opaque bodies; the stock state
machines and bundled profiles add typed state. Kafka, S3, IPFS, or Cardano
payment actions also need their first-party optional connector bundle and the
external service; see [optional connectors](appchain/OPTIONAL_CONNECTORS.md).

### A1. Multi-party audit and compliance log

**Problem.** Two or more organizations or departments must keep a shared,
append-only record of events (approvals, access grants, configuration changes,
regulatory filings) that no party can later deny, reorder, or rewrite.

**Why an app ledger.** A database owned by one party proves nothing to the
others. A public blockchain is too costly, too slow, and too public for every
event. An `ordered-log` ledger between the parties gives co-signed ordering, and
a periodic Cardano anchor gives a publicly verifiable reference point.

**Setup sketch.** One node per organization; the members are each
organization's key. Use a threshold of all members (2 of 2, 3 of 3) when
everyone must vouch for every entry, or a smaller threshold for availability.
Anchor every N blocks. A suggested body:
`{"event":"access-granted","actor":"...","object":"...","ts":"..."}`.

**Verification.** An auditor takes any entry, asks any node for the
`finalized-message-v1` proof subject using the public message id, reads the
anchor from Cardano, and checks the proof against the anchored `state-root`.
Neither organization, nor Yano, needs to be trusted.

### A2. Neutral consortium message queue

**Problem.** Banks or partners exchange settlement instructions or business
messages over a broker such as Kafka or MQ. The broker is operated by one party,
so disputes about message order and delivery cannot be settled.

**Why an app ledger.** Submission works like a queue (`POST /messages`), but the
"broker" is operated jointly: order and content are finalized by threshold
signatures, every party stores everything, and a dispute reduces to a proof
check.

**Setup sketch.** One node per participant, optionally plus an auditor that is
a member but never submits. Use one topic per flow: `settlement`,
`reconciliation`, `dispute`. Consumers read finalized messages with
`GET /messages/by-topic/settlement`, follow the server-sent event stream
(`GET /stream?topic=settlement`), or subscribe to `AppBlockFinalizedEvent` in a
small plugin.

**Verification.** "You never sent instruction X" or "you sent it after Y" is
settled by the message's finalized height, the block contents, and the anchor.

### A3. Supply-chain trail or Digital Product Passport

**Problem.** A manufacturer, logistics providers, and certifiers must attach a
growing, non-repudiable event trail to products (origin, handling,
certification), and downstream buyers must be able to verify single entries
cheaply.

**Why an app ledger.** Each step is a message from a known member. Documents
stay outside the ledger (S3, IPFS, internal systems); the ledger records
`{productId, step, documentHash, actor}`. The envelope proves which member
submitted it; durable manufacturer, shipper, or device identity still needs a
domain-signed actor statement and an actor registry. Buyers verify one entry
with one proof against a public anchor, without seeing the rest of the trail.

**Setup sketch.** A topic per product line, or a `productId` field in the body.
Anchor hourly. Keep bodies small: hashes and metadata, not documents. The
`doc-trail` state machine keeps one provable chained head per product or case,
and the [DPP Starter](appchain/DPP_STARTER.md) packages a passport registry.

### A4. Notarization and proof of existence

**Problem.** Prove that content existed by time T (contracts, model weights,
datasets, research results) without publishing the content.

**Why an app ledger.** Submit `{"sha256":"...","label":"..."}`. The entry is
ordered, finalized, and later anchored to Cardano in one transaction per anchor
interval, at app-ledger throughput and cost. A single organization can benefit
too: two or three nodes across departments or regions with a threshold of 2
prevent any one of them from rewriting history alone.

**Verification.** Reveal the content later. Anyone recomputes the hash, checks
the proof, and checks the anchor. The anchor transaction's Cardano block time
bounds T. [Attest](appchain/ATTEST.md) packages this pattern with a portable
certificate.

### A5. Cross-organization integration and SLA evidence

**Problem.** Two systems integrate over APIs or webhooks, and disagreements
arise: "we called you at 12:01 and you didn't respond", "you never sent the
webhook".

**Why an app ledger.** Both sides record request and response digests
(`{direction, endpoint, payloadHash, status}`) on a shared ledger as they
happen. The ordered, co-signed log is the single source of truth for SLA
disputes. With `message.enforce-sender-seq` enabled, per-sender sequence
numbers also expose gaps.

### A6. Game and loyalty event feeds settled on Cardano

**Problem.** High-frequency application events (scores, points, achievements)
are too frequent for Cardano itself, but rewards must eventually settle there
credibly.

**Why an app ledger.** The operator's nodes, or the operator, platform, and
guilds as members, finalize the event stream outside Cardano; anchored roots
make the feed auditable. Settlement jobs read finalized blocks
(`GET /blocks/{height}`) and pay out on Cardano, citing a proof for every reward
decision.

---

## Part B: your own state machine as a plugin JAR

Everything in Part A, plus your own interpretation of messages: the state
machine turns the log into typed, validated, queryable state, and every key it
writes is individually provable. To deploy, drop a bundle JAR into `plugins/`
on every member; see the [plugin tutorial](appchain/tutorials/08-plugins-and-composites.md).

### B1. Replicated registry

**Problem.** A consortium needs one authoritative registry (token metadata, DID
documents, allow and deny lists, service endpoints, schema versions) that no
single member can edit unilaterally and that outsiders can query with proofs.

**How.** The state machine interprets commands, from the tutorial's
`set:<key>=<value>` / `del:<key>` to typed CBOR commands with per-key ownership
rules in `apply`; for example, only the member that created a key may update
it, because the sender is in the envelope. `GET /state/proof/{keyHex}` returns
the value and its proof in one call. The stock `kv-registry` state machine
covers this without a plugin.

This is the closest thing to smart-contract state the framework offers today:
deterministic multi-party state transitions with provable results, in plain
Java.

### B2. Business ledgers with admission rules

**Problem.** Partners share a business ledger (purchase orders, inventory
movements, quota consumption) whose entries must satisfy business rules: no
negative stock, no exceeded quota, only valid state transitions.

**How.** Admission (`validateForBlock`) rejects malformed commands before they
reach a block. `apply` enforces stateful rules deterministically, recording a
rule violation as a no-op or as a rejection entry. Because every member
re-executes `apply`, no member can be fed a different ledger than its peers:
the state roots would differ and the block would not become final.

### B3. Micropayment and receipt netting

**Problem.** High-frequency micro-receipts (API calls, content access, agent
payments) are individually too small for Cardano fees, but the parties need
credible accounting and periodic settlement.

**How.** The state machine tracks per-party balances from receipt messages, and
every balance is a provable state key. A settlement job nets balances every
anchor interval and pays on Cardano, citing the anchored root the balances came
from. Script anchoring enforces a monotonic, threshold-signed root chain, but
production payment, reconciliation, and any domain-specific withdrawal
validator still need dedicated hardening. The stock `balances` state machine
covers the ledger part without a plugin.

### B4. Approval workflows and multi-party sign-off

**Problem.** Cross-organization processes need k-of-n approvals by people or
systems (release gates, payment authorizations, credential issuance), with a
non-repudiable record of who approved what, and in which order.

**How.** Messages are propose, approve, and reject commands. `apply` tracks the
workflow state per item and marks it approved when the required set of
approvers is reached. The full decision trail is provable per item.

The stock `approvals` state machine fits when the approvers are the members
themselves (the approver is the envelope sender). The stock `role-evidence`
profile covers the broader case: governed business actors who are not members
sign exact statements, policies enforce roles and approvals from distinct
organizations, and any member can relay a command without becoming the
recorded approver. See [domain actors and roles](APP_CHAIN_DOMAIN_ROLES.md).

### B5. Member-attested data feeds

**Problem.** A consortium wants an agreed data feed (prices, weather, results)
where each member observes independently and the group publishes one agreed
value.

**How.** Each member's gateway submits its observation for a round. `apply`
aggregates deterministically once all observations, or a quorum of them, are
present (for example, a median), and writes the agreed value for the round as
provable state. Fetch external data outside the node, in a small submitter
service per member: `apply` itself must stay deterministic and free of I/O. The
[Attestation Feed](appchain/ATTESTATION_FEED.md) starter shows a configuration-only
variant on the stock authenticated map.

---

## Part C: Yano as a library

Yano publishes Java artifacts (`yano-core-api`, `yano-runtime`, and others);
the node application is one way to package them. Library mode is for teams
building their own node or product around an app ledger.

### C1. An enterprise service that is also a member

**Problem.** You want an existing Java service to be a ledger member (submit
and consume records in-process, expose your own domain API, keep your own
database) without operating a separate node process.

**How.** Depend on `yano-runtime`, assemble a node with `YanoAssembly`, pass your
`AppStateMachine` instance directly to the `AppChainSubsystem` constructor (no
provider or services file is needed in library mode), and register it with the
node. Your service calls `submit(...)` and listens for `AppBlockFinalizedEvent`
to update its own read models once per finalized block: an event-sourcing
backbone whose event log is multi-party and anchored.

**Fits.** Core banking adapters, ERP connectors, and marketplace back ends that
must share state with partners but want one JVM and one deployment unit.

### C2. Application logic that reacts to Cardano

**Problem.** Your application state should react to Cardano itself: track
deposits to an address, mirror an on-chain registry, or maintain an index that
partners agree on.

**How.** In library or plugin mode, an `L1ObserverProvider` turns stable Cardano
observations, such as `address-deposit` and `metadata-label`, into reserved
app-ledger messages. With `l1.stability-depth > 0`, every member checks each
block's Cardano reference against its own view of Cardano before voting, so a
fabricated or rolled-back reference is rejected, and replay derives the same
observation state.

**Caveat.** The observer contract is deliberately narrower than arbitrary reads
of live Cardano data: `apply` stays free of I/O. Domain observers must validate
the exact fact they observe, and a script anchor enforces only the anchor-chain
rules, not arbitrary bridge withdrawals. Treat this as integration
infrastructure for trusted members, not as an adversarial bridge, unless a
separately audited domain protocol supplies the missing proofs, attestation
policy, and on-chain enforcement.

### C3. Custom distributions and appliances

**Problem.** You are shipping a product: a consortium ledger appliance, a
sector-specific node (energy trading, healthcare data exchange), or a service in
which each tenant group gets its own ledger.

**How.** Build your own launcher on `yano-runtime`: preconfigure roles, bundle
your state machines, add your API surface, and brand it. The node's `Subsystem`
interface lets you add sidecar subsystems (schedulers, exporters, notification
bridges) with the same lifecycle and health handling as the built-in ones. The
Yano node application is the reference to copy.

### C4. Integration tests and CI for ledger workflows

**Problem.** Teams building on an app ledger need fast, deterministic tests
without public networks or drifting fixtures.

**How.** Start two or three `AppChainSubsystem` instances in one JVM with
temporary ledgers and real sockets, drive scenarios (submit, finalize, prove,
restart, catch up), and assert on state roots. Yano's own runtime integration
tests follow this pattern, and `StateMachineConformance` checks that a state
machine produces identical roots across runs and after a restart.

### C5. Replicating finalized data to the rest of your stack

**Problem.** The app ledger is the agreed source of truth, but the rest of your
stack needs the data in Kafka, Postgres, or Elasticsearch.

**How.** A small library-mode process can listen for `AppBlockFinalizedEvent`,
or an operator can configure a first-party finalized sink, and forward finalized
messages to the surrounding systems. Delivery is ordered and cursor-tracked, but
at least once across acknowledgement crashes, so consumers deduplicate by chain,
block, and message identity. The ledger stays the neutral system of record.

---

## Choosing your entry point

| You need | Use |
|---|---|
| A shared tamper-evident log, provable records, minimal effort | **Part A**: configuration only |
| Typed, validated shared state, per-key proofs, business rules | **Part B**: a plugin JAR, or a stock state machine |
| Your own node or product, in-process integration, logic that reacts to Cardano | **Part C**: Yano as a library |

## What this is not for (yet)

Be clear with stakeholders about the current boundaries:

- **Open or stake-based membership.** Membership is a configured list of member
  keys. There is no stake, no slashing, and no open participation.
- **Tolerating dishonest members by default.** With the default fault bound of
  0, the consensus is crash-tolerant: a threshold below the member count lets
  the ledger finish rounds while some members are offline, but it assumes that
  members do not lie. See
  [production deployment](appchain/PRODUCTION_DEPLOYMENT.md#2-members-threshold-and-nodes).
- **Domain-enforced bridges and withdrawals.** Metadata anchors and
  threshold-co-signed script anchors are implemented, but the stock anchor
  validator does not check a domain withdrawal or payment policy.
- **Semi-trusted effect outcomes.** Effect results are member attestations. A
  k-of-n outcome policy and a continuous independent auditor are needed before
  executors or members can be treated as semi-trusted.
- **Large payloads.** Message bodies are capped at 64 KiB by default. Store
  large content elsewhere and record its hash.
- **Public data distribution.** The ledger replicates only to members. Publish
  proofs and roots to outsiders, not the ledger.
- **Sub-second global finality.** Finality needs network round trips to a
  threshold of members. Tune `block.interval-ms` and the threshold to your
  latency budget.

---

## Capabilities and the use cases they unlock

The stock capabilities turn several Part B patterns into configuration-only
Part A deployments (`kv-registry` covers B1, `approvals` covers B4) and add
capabilities the parts above do not cover. The
[capability catalog](appchain/CAPABILITIES.md) lists each one with its maturity.

| Capability | Use cases it unlocks |
|---|---|
| `balances` state machine | B3 receipt netting, loyalty points, and internal credit ledgers with no custom code |
| `doc-trail` state machine | A3 supply-chain trails: one provable chained head per product or case covers the whole trail |
| `composite` with the `evidence-v1-gated` preset | Approval-coordinated S3 and IPFS publication followed by an acknowledged Kafka notification, all under one root |
| `role-evidence` profile | Evidence release signed by business actors, with governed organizations, key rotation and revocation, two auditor organizations, and a regulator |
| Governed composite profiles | Deploy reviewed, dormant profile generations first, then let a threshold of members authorize activation at a future height; see [profile governance](APP_CHAIN_PROFILE_GOVERNANCE.md) |
| `credential-registry` (experimental) | Verifiable credentials on an anchored registry: issuer-signed attribute sets with selective disclosure |
| `zk-gate` (experimental) | Private policy compliance: prove "amount ≤ limit" or "KYC holds" across organizations without revealing the data |
| `zk-membership` (experimental) | Anonymous but authorized submissions among known members: voting, sealed bids, whistleblowing |
| Evidence export | A1 and A5 audits: one offline-verifiable bundle per record for regulators and counterparties |
