---
title: "Glossary"
description: "Short definitions of the terms these guides use, with a link to where each is explained. Yano's command line, configuration, and APIs say app chain where…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/reference-glossary.md"
---
Short definitions of the terms these guides use, with a link to where each is
explained. Yano's command line, configuration, and APIs say *app chain* where
the guides say *app ledger*; identifiers such as `yano.app-chain.*` and
`/api/v1/app-chain/` keep their names.

## A–C

**Actor.** A person or organization that signs a business decision, such as an
approval, with its own key. The signature travels inside the message body.
Actors are separate from members. See [Keys and trust](/concepts/trust-model/).

**Admission.** The check a member runs before it accepts a message into its
pending pool. A refusal answers HTTP 400 and keeps the message out of blocks;
acceptance answers 202, which is not finality.

**Anchor.** A certified state root published to Cardano, either in transaction
metadata or in a script-controlled output whose inline datum carries the ledger's
identity. Optional; finality never waits for it. See
[Cardano anchoring](/concepts/anchoring/).

**Anchor leader.** The node with anchoring enabled that builds, pays for, and
submits anchor transactions. It is fixed and does not rotate. In script mode it
needs a threshold of member co-signatures.

**App ledger.** An application-specific, replicated ledger that a group of
organizations runs together. The tooling calls it an *app chain*. See
[What is an app ledger?](/start-here/what-is-an-app-ledger/)

**Binding.** A declarative event binding: when one component emits an event,
check conditions and build a command for another component. Bindings run
identically on every member. Experimental. See
[Declarative bindings](/bindings/).

**Block.** An ordered batch of messages, with the state root after applying them,
a hash link to the previous block, and a finality certificate. The block hash
covers the header; messages are bound through the messages root.

**Capability.** An entry in the release catalog, such as `state:kv-registry` or
`executor:kafka`, with an availability: `BUNDLED`, `FIRST_PARTY_OPTIONAL`,
`REFERENCE`, or `EXPERIMENTAL`. See the [capability catalog](/reference/capabilities/).

**Capability manifest.** The `capabilityManifest` in a ledger's status: discovery
data describing its components, workflows, and proof subjects. Not a trust root.
See [Chain identity](/concepts/chain-identity/).

**Catch-up.** How a member that fell behind fetches missed blocks from a peer and
verifies each one, including its certificate and state root. See
[Restart, catch-up and snapshots](/concepts/recovery/).

**Chain id.** The identifier of one app ledger, `chain-id` in configuration. One
node can host several ledgers.

**Commitment profile.** The authenticated-state structure, chosen at genesis:
`mpf-blake2b256-v1` (MPF) or `jmt-blake2b256-v1` (JMT). Only MPF proofs can be
checked on Cardano. See [Chain identity](/concepts/chain-identity/).

**Composite.** One state machine that hosts several components under one state
root, applying them in a committed order within each block.

**Consensus context.** A digest in every block header that binds the chain id,
genesis id, height, quorum, member keys, and consensus profiles. Every vote
signs it.

## E–M

**Effect.** An immutable record that a transition emits to authorize external
work, such as a webhook call or a Kafka message. An executor performs it after
finality. See [Effects](/concepts/effects/).

**Executor.** The node-local component that performs finalized effects. It runs
outside consensus and is off by default.

**Fault bound.** `f`, the number of members allowed to be dishonest, set as
`consensus.max-byzantine-members` (default 0). The threshold `t` of `n` members
must satisfy `2t − n > f` and `t ≤ n − f`. See [Keys and trust](/concepts/trust-model/).

**Finality certificate.** The COMMIT signatures of a threshold of distinct
members on one block. A block with a valid certificate is final, and there is no
rollback below finality. See [Consensus and finality](/concepts/consensus-and-finality/).

**Format fingerprint.** A hash of the commitment profile's exact commitment and
proof format, `state.format-fingerprint`. Fixed at genesis.

**Genesis id.** 32 bytes, `state.genesis-id`, that name one generation of a
ledger. Never regenerate the genesis id of a ledger whose data you keep.

**Leader.** The member that proposes the block for a height and view. With a
`fixed` sequencer, a configured member leads view 0; with `rotating`, the view-0
leader is derived from the consensus context and the parent block hash. After a
certified timeout, the next member in order leads.

**Member.** A node that takes part in a ledger, identified by an Ed25519 public
key. Members sign the envelopes of messages they accept, re-execute and vote on
every block, and each hold the full state. A ledger has at most 32 members.

**Membership epoch.** The member set and threshold in force from a given height.
With governed membership, changes are finalized ledger transactions, and every
check uses the membership at that height.

**Message.** An envelope with a topic and an opaque body, signed by the member
that accepted it. Its message id is derived from its content. Only the state
machine interprets the body.

## O–R

**Observation.** External information that enters the ledger as certified
input: stable Cardano facts on reserved `~l1/` topics, which every member
re-derives from its own Cardano view, or generic observations certified under a
shared policy (preview). See [External observations](/concepts/observations/).

**Organization.** A governed record that groups actors, so a policy can require
approvals from distinct organizations.

**PreparedQC.** A threshold of PREPARE votes for one block in one view. Members
persist it and then sign COMMIT; a later leader must carry it forward.

**Preset.** A packaged choice made when a ledger is created, such as
`machines.composite.preset` (default `evidence-v1-gated`) or a Cardano History
preset (default `params-only-v1`).

**Profile.** For a composite, the canonical description of its components,
order, routes, versions, and quotas, committed at height 1. It changes only
through [profile governance](/deployment/profile-governance/).

**Proof subject.** A typed descriptor, such as `finalized-message-v1`, that turns
an application identity (a message id, an account, a key) into the canonical
state key and evaluates declared claims on the proven value. See
[State and proofs](/concepts/state-and-proofs/).

**Read index.** A node-local, rebuildable index derived from finalized blocks,
kept in `appchain-indexers/`. Never authoritative. See
[Where data lives](/concepts/where-data-lives/).

**Recipe.** A reviewed starting point that selects the capabilities and
configuration for a kind of ledger. See [Recipes](/recipes/).

## S–V

**Sink.** A component that pushes finalized blocks to an external system, such
as a webhook or Kafka, at least once.

**Snapshot.** A member-signed copy of one ledger's database, used to restore or
onboard a member. Verified before it is opened. See
[Restart, catch-up and snapshots](/concepts/recovery/).

**State machine.** The deterministic component that interprets message bodies
and writes state. `ordered-log` is built in; the others are plugins. See
[State machines](/state-machines/).

**State root.** The root of the authenticated state after a block. Every member
computes it and compares it byte for byte; proofs are checked against it.

**Threshold.** The number of member signatures a finality certificate needs.
Set it explicitly; the launchers default to a majority, `⌊n/2⌋ + 1`.

**Topic.** A label inside a ledger for routing and filtering messages. Topics
starting with `~` are reserved for the framework.

**Trust level.** How far a verified result can be trusted:
`INTERNAL_CONSISTENCY_ONLY`, `CALLER_PINNED_ROOT`, `NODE_CONFIRMED_L1_REFERENCE`,
`CALLER_PINNED_ANCHOR`, or `INDEPENDENTLY_VERIFIED_L1_ANCHOR`. See
[Keys and trust](/concepts/trust-model/#trust-levels).

**View.** A numbered attempt to finalize a height. If a round times out, a
threshold of members signs timeouts that form a new-view certificate, and the
next view begins with the next leader.
