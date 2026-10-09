# State machines

A state machine holds the rules of an app ledger. Every member applies the same
machine to the same finalized messages, so every member reaches the same state
and the same state root. A chain selects exactly one machine id with
`state-machine`.

Two ideas apply to every machine on these pages:

- **Final does not mean changed.** A message can be final and still have no
  effect, because it broke a rule against the state it actually ran on. Read
  the resulting state, or its proof, before you treat a command as applied.
- **Admission is only a first filter.** The member that receives a message
  checks its shape before answering `202`. The authoritative decision happens
  when the block is applied.

## Choose a machine

<!-- illustration: sm-chooser -->

## Stock state machines

| Machine | Maturity | Use it for | State key | Proof subject |
|---|---|---|---|---|
| [`ordered-log`](ordered-log.md) | stable | One agreed order of opaque events | hash of the message id | `finalized-message-v1` |
| [`kv-registry`](kv-registry.md) | stable | Mutable records owned by their first writer | the key bytes | `registry-entry-v1` |
| [`authenticated-map`](authenticated-map.md) | preview | Several collections with their own authorization and validation | collection and key | `authenticated-map-entry-v1` |
| [`approvals`](approvals.md) | stable | Decisions taken by member nodes | `i/<itemId>` | `basic-approval-outcome-v1` |
| [`role-approvals`](role-approvals.md) | preview | Decisions signed by business actors under a policy | proposal record | `role-approval-outcome-v1` |
| [`balances`](balances.md) | stable | Member-owned units with a minter | `b/<account>` | `account-balance-v1` |
| [`doc-trail`](doc-trail.md) | stable | A chained history of document hashes per entity | `e/<entityId>` | `document-head-v1` |

`ordered-log` is built into Yano. The others ship in the Yano X standard
library and role-workflow bundles. Value validation for `authenticated-map` has
its [own page](authenticated-map-validation.md).

Each reference page has the same shape: **At a glance**, **How it works** with
an interactive illustration, the commands to use it, and **Advanced** topics
such as typed views and composites.

## Other state machines

These machines are in the capability catalog but are not stock references
here.

| Machine id | Maturity | What it is | Read more |
|---|---|---|---|
| `declarative-composite` | preview | Committed, bounded event-to-command and event-to-effect workflows over stock components | [Declarative bindings](../bindings/README.md) |
| `evidence-registry` | preview | Inspection and compliance evidence records with exact query proofs | [Tutorial 4](../tutorials/04-evidence-publication.md) |
| `composite` | preview | Stock evidence workflow; select it with `machines.composite.preset` set to `evidence-v1` or `evidence-v1-gated` | [Tutorial 4](../tutorials/04-evidence-publication.md) |
| `role-evidence` | preview | Evidence registration with governed actors and role-aware release approval | [Tutorial 5](../tutorials/05-domain-role-approvals.md) |
| `zk-gate` | experimental | Verifies configured Groth16 or Plonk proofs during the state transition | [ZK state machines](../../../state-machines/zk/README.md) |
| `zk-membership` | experimental | Membership authorization through a zero-knowledge circuit, with nullifier deduplication | [ZK state machines](../../../state-machines/zk/README.md) |
| `credential-registry` | experimental | Selectively disclosed BBS credential statements from configured issuers | [ZK state machines](../../../state-machines/zk/README.md) |
| `eutxo-ledger` | experimental | A Cardano-shaped EUTxO engine; you select it through a ledger profile, not directly | [EUTxO ledger](../../../ledgers/eutxo/README.md) |

## Before you configure one

Every chain carries three state-identity settings, and the host refuses to
start a chain unless all three are present:

| Setting | Meaning |
|---|---|
| `state.commitment-profile` | How state is committed, for example `mpf-blake2b256-v1` |
| `state.format-fingerprint` | The fingerprint of that profile's format; it must match the profile |
| `state.genesis-id` | 32 bytes, as 64 lowercase hex characters, naming this chain generation |

In a generated project, `appchain render` writes all three; do not edit them by
hand. The stock cluster file, `$YANO_HOME/config/application-appchain.yml`,
already contains them for its chains. When you add a chain to that file
yourself, copy the profile and fingerprint from an existing chain and give the
new chain a fresh genesis id, for example from `openssl rand -hex 32`. The
configuration examples on these pages show the three settings in that form.

Selecting a machine is a consensus decision. Every member must use the same
machine id and the same consensus-affecting settings. Changing either on an
existing chain needs a governed profile activation or a new chain.

## Go deeper

- [Choose a stock state machine](../tutorials/03-stock-state-machines.md), the
  hands-on companion to this page.
- [Consensus guide](https://github.com/bloxbean/yano/blob/main/docs/APP_CHAIN_CONSENSUS_GUIDE.md)
  in Yano: how blocks are ordered, re-executed and certified.
- [Plugins and composites](../tutorials/08-plugins-and-composites.md), for
  rules the stock machines do not cover.
