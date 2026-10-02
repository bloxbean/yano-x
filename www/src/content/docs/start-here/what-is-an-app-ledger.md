---
title: "What is an app ledger?"
description: "An app ledger is an application-specific, replicated ledger that a group of organizations runs together. Each organization runs a member node. Members…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/start-here-what-is-an-app-ledger.md"
---
An **app ledger** is an application-specific, replicated ledger that a group of
organizations runs together. Each organization runs a **member** node. Members
agree on the order of application messages, apply them with the same
deterministic state machine, and each derives the same authenticated state root
on its own. Records in that state can be proved against the root, and the root
can optionally be anchored on Cardano.

The shortest description:

> A programmable, multi-party application ledger with deterministic state,
> threshold finality, proofs, Cardano anchoring, and controlled external
> actions.

Yano's command line, configuration, and APIs call an app ledger an *app chain*.
You will see `./yano.sh appchain` commands, `yano.app-chain.*` settings, and
`/api/v1/app-chain/` URLs. They refer to the same thing.

## The problem it solves

Many business processes span several organizations and systems, and today they
usually look like this:

- each participant keeps its own database;
- one operator controls the shared API or message broker;
- audits reconstruct history after the fact;
- external actions are hard to tie back to an agreed business decision; and
- putting every application event directly on a public blockchain is too slow,
  too costly, too public, or too inflexible.

An app ledger gives the participants a shared application layer without turning
every business operation into a Cardano transaction.

<!-- illustration: app-ledger-overview -->

The app ledger does not replace Cardano. It supplies application-specific
execution and coordination; Cardano supplies an independently observable record
of the state roots the ledger commits to.

## What makes it a ledger rather than a shared database

| Characteristic | What it means | Why it matters |
|---|---|---|
| Signed participation | Every message travels in an envelope signed by the member that accepted it. People and organizations who approve a business decision sign that decision themselves, inside the message. | The ledger knows which member submitted a message and who approved an action. |
| Deterministic execution | Every member applies the same messages, in the same order, with the same state machine. | Honest members derive the same state root, byte for byte. |
| Threshold finality | A block is final once the configured number of members has signed it in two rounds of votes, PREPARE and COMMIT. | No single database or broker operator decides history. |
| Hash-linked blocks | Each block commits to the block before it. | Reordering or rewriting history is detectable. |
| Provable state | State is held in an authenticated tree that supports inclusion and exclusion proofs. Its type, MPF or JMT, is fixed when the ledger is created. | A client checks a record against a root without trusting one node. |
| Cardano anchoring | A certified state root can be published in Cardano transaction metadata or in a script-controlled anchor. | Auditors can tie the ledger's evidence to public Cardano history. |
| Deterministic effects | External work is authorized by an immutable effect record that the state machine emits. | Network calls never run inside consensus execution. |
| Catch-up and recovery | Restarted or new members fetch history and verify every block and certificate themselves. | Recovery does not require trusting a database copy. |
| Plugins | State machines, executors, sinks, APIs, and queries are extensible. | A domain can evolve without forking the consensus framework. |

## Life of a message

Follow one message from an HTTP call to a final, provable result. The "What if"
scenarios show what happens when a business rule fails, when the leader is
offline, and when one member computes a different result.

<!-- illustration: message-lifecycle -->
1. **Submit.** Your application sends a topic and a body to a member. The member
   wraps the body in an envelope signed with its own member key.
2. **Admit.** The member checks the command against the state machine and
   answers 202 with a message id. 202 means "queued", not "final".
3. **Gossip.** Members share the envelope and hold it in memory until a block
   includes it.
4. **Propose.** The leader for this height selects pending messages, applies the
   block itself, and sends it with its PREPARE vote.
5. **Re-execute.** Every other member applies the block itself and votes PREPARE
   only if its own state root matches.
6. **Prepare.** A threshold of PREPARE votes forms a PreparedQC, and members sign
   COMMIT.
7. **Commit.** A threshold of COMMIT votes forms the finality certificate. The
   block is final on every member.
8. **Read and prove.** Your application reads the result and can request a proof
   against the certified root.
9. **Anchor.** Optionally, a certified root is later published to Cardano.
<!-- /illustration -->

Three things follow from this flow:

- **202 is not success.** It means one member queued the message. Wait for the
  block, then read the application's result. A final command that breaks a
  business rule is recorded as a no-op on every member.
- **No member is trusted with the result.** Every member re-executes every block,
  and the finality certificate shows that a threshold of them computed the same
  root.
- **The ledger fails closed.** Envelope signatures, membership at each height,
  the leader for each round, hash links, and the re-executed state root are
  checked on every member, always. A member that computes a different root does
  not vote, and if too few members agree, no block becomes final.

[Consensus and finality](/concepts/consensus-and-finality/) describes the round
in more detail.

## Vocabulary

| Term | Meaning |
|---|---|
| **App ledger** | A replicated application ledger run by a group of members. Yano's tooling calls it an *app chain*. |
| **Chain id** | The identifier of one app ledger (`chain-id` in configuration). The same members may run several independent ledgers, and one node can host several. |
| **Member** | A node that takes part in a ledger, identified by an Ed25519 public key. Members sign the envelopes of messages they accept, check and vote on blocks, and each hold the full state. A ledger has at most 32 members. |
| **Actor** | A person or organization that signs a business decision, such as an approval, inside a message. Actors are separate from members. |
| **Leader** | The member that proposes the block for a height and view. With a fixed sequencer, a configured member leads view 0; with a rotating sequencer, the view-0 leader is derived from the previous block. If a round times out, a threshold of members moves to the next view, and the next member in order leads. |
| **Threshold** | How many members must vote for a block before it is final. |
| **Finality certificate** | The threshold of COMMIT signatures that makes a block final. |
| **App message** | An envelope with a topic and an opaque body, signed by the member that accepted it. Only the state machine interprets the body. |
| **Topic** | A label inside a ledger, for routing and filtering messages. |
| **App block** | An ordered batch of messages, the state root after applying them, a hash link to the previous block, and the block's finality certificate. |
| **State root** | The root of the authenticated state after a block. Identical on every member, provable, and anchorable on Cardano. |
| **State machine** | The only component that interprets message bodies. |
| **Effect** | An immutable record emitted by a transition, authorizing external work that an executor performs after finality. |
| **Anchor leader** | The single node that builds, pays for, and submits anchor transactions. Its powers depend on the anchor mode; script anchors also require member signatures. |

## When an app ledger is the right answer

An app ledger is useful when several of these needs apply:

- several organizations must agree on the same sequence of application records;
- no single participant should own the authoritative database;
- someone will later need to prove a specific record, not merely be told about
  it;
- the volume, privacy, or cost profile makes putting each event directly on a
  public chain impractical; and
- some external systems must act on decisions, but only after those decisions
  are final.

It is **not** the right answer for a single-organization application with no
external verifier, for high-frequency data with no dispute surface, or for
anything that genuinely needs permissionless participation.

<!-- illustration: app-ledger-fit -->

## Where Yano and Yano X fit

Yano is the host: a Cardano data node with a minimal app ledger runtime,
consensus, proofs, anchoring, the effect system, the plugin SPI, and
`ordered-log` as its only built-in state machine.

**Yano X** is the extension ecosystem on top: the stock state machines, the
composition framework, connectors, products, SDKs, tooling, and the
batteries-included JVM distribution.

Next: [Why Yano X](/start-here/why-yano-x/).
