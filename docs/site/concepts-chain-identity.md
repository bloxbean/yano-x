# Chain identity

Two ledgers can share a chain id, a member set, and even a state machine, and
still be different ledgers. A handful of values, most of them chosen when the
ledger is created, decide which ledger a block, a proof, or an anchor belongs
to. Yano's configuration calls an app ledger an *app chain*, so these settings
live under `yano.app-chain.*`.

- **You'll learn:** which values make up a ledger's identity, where the runtime
  commits them, and what a verifier must pin.
- **Before you start:** [State and proofs](/concepts/state-and-proofs/) and
  [Consensus and finality](/concepts/consensus-and-finality/).

<!-- illustration: chain-identity -->

## The genesis triple

Three settings form one indivisible state identity. They must be configured
together, or the node refuses to start with "state commitment profile,
fingerprint, and genesis id must be configured together":

| Setting | What it is |
|---|---|
| `state.commitment-profile` | The authenticated-state structure: `mpf-blake2b256-v1` (MPF) or `jmt-blake2b256-v1` (JMT). Only MPF proofs can be checked on Cardano. |
| `state.format-fingerprint` | A Blake2b-256 hash of the profile's exact commitment and proof format. It must match the selected profile. |
| `state.genesis-id` | 32 bytes, as lowercase hex, that name this generation of the ledger. |

For example, from the distribution's stock configuration:

```yaml
state:
  commitment-profile: mpf-blake2b256-v1
  format-fingerprint: 91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b
  genesis-id: c2b9c92a865dfa7c218a1a6e49f1dd88163372e40466009876458c01609d0d70
```

Products built on the governed authenticated map, such as the Trust Registry,
compute the genesis id from their genesis descriptor, so every member can
regenerate it and compare. The runtime then binds the configured id to the
application profile, including the configuration of its built-in indexes. The
id that status, anchors, and the consensus context report is that derived id.

None of the three can change on a running ledger. A node that finds a retained
ledger with a different identity refuses to open it. To change any of them,
start a new ledger. Never regenerate the genesis id of a ledger whose data you
keep.

## The application id

The application id is the ledger's configured `state-machine`, or the committed
composite profile id. The capability manifest names it, and a script anchor's
datum carries it, so an anchor cannot be moved between applications.

## Height-1 markers

At height 1 the runtime writes markers into the authenticated state, under
reserved keys: the state-commitment identity (`~yano/state-commitment/v1`), the
consensus profile (`~yano/consensus-profile/v2`), and the observation profile
(`~yano/obs/profile/v1`, written even when observations are disabled).
Configuration records for enabled built-in indexes are written there too.

Because the markers are part of the state root, a member configured with a
different identity computes a different root at height 1. It cannot vote for
that history or apply it, and every later block is checked against the
retained markers.

## The consensus context

Every block header carries a `consensusContextDigest`. It is a Blake2b-256 digest
over the protocol version, chain id, genesis id, height, the quorum (members,
threshold, and fault bound), the sorted member keys at that height, and the
consensus, observer, and observation profile digests. Every PREPARE, COMMIT,
and timeout signature covers it, and with a rotating leader, the view-0 leader is
derived from it and the parent block hash.

A member with a different genesis id or membership computes a different digest,
rejects the others' proposals, and stalls. The remaining members keep finalizing
only if they still reach the threshold without it: with a threshold of 2 of 3
they continue; with 3 of 3, no block becomes final.

## The capability manifest

The chain status returns a `capabilityManifest` with a `manifestDigest`. It
describes the application's components, workflows, and proof subjects so tools
can discover them; the Evidence Desk and the Verifiable Explorer, for example,
choose how to read a ledger from it. It is descriptive
discovery data, **not a trust root**: proof verification still pins the chain,
genesis, commitment profile, root, and typed subject.

## The anchor datum

A script anchor on Cardano carries the identity in its inline datum, eleven
fields in this order:

| # | Field | Meaning |
|---:|---|---|
| 1 | `version` | Anchor format version, 1 |
| 2 | `chain-id` | The ledger's chain id |
| 3 | `chain-genesis-id` | The genesis id |
| 4 | `application-id` | The state machine or application profile id |
| 5 | `commitment-profile-id` | For example, `mpf-blake2b256-v1` |
| 6 | `format-fingerprint` | The commitment and proof format |
| 7 | `height` | The block height this anchor attests |
| 8 | `block-hash` | That block's hash |
| 9 | `state-root` | That block's state root |
| 10 | `member-keys` | The member public keys, sorted |
| 11 | `threshold` | The finality threshold |

The on-chain validator lets the height, root, members, and threshold advance,
but not the chain, genesis, application, or commitment identity.

## What a verifier pins

A proof is only as good as the identity it is checked against. Obtain these
independently, never from the response you are verifying: the chain id, genesis
id, commitment profile and fingerprint, height, root, and, to check finality,
the member set, threshold, and consensus-context digest. Read a node's view with:

```bash
./yano.sh appchain state identity --url http://127.0.0.1:7070/api/v1 --chain orders-chain
```

Operators compare the same values across members: identical height, state root,
commitment profile, genesis id, and capability-manifest digest on every member is
the sign of one ledger.

Next: [Where data lives](/concepts/where-data-lives/).
