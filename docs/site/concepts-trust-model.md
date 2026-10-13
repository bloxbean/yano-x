# Keys and trust

An app ledger involves several kinds of keys and credentials, and each one
means something different. This page says what each key signs or unlocks, what
a proof establishes, and what you still have to trust.

- **You'll learn:** the role of member, actor, API, anchor, connector, and
  publisher keys; the difference between proven and trusted; and the trust
  levels that verifiers report.
- **Before you start:** [What is an app ledger?](/start-here/what-is-an-app-ledger/)
  and [State and proofs](/concepts/state-and-proofs/).

<!-- illustration: who-signs-what -->

## Members and actors

A **member** is a node, identified by its Ed25519 member key. The member key
signs the envelope of every message its node accepts, its PREPARE and COMMIT
votes, and, in script anchoring, its anchor co-signatures. When an application
submits over REST, it signs nothing: the node signs the envelope with its own
member key, and treats the REST caller as trusted local input.

An **actor** is a person or organization that authorizes a business decision,
such as an approval, with its own key. The actor's signature travels inside the
message body and binds the exact chain, proposal, policy revision, payload hash,
deadline, and clause. Any member can relay it without becoming the approver.

| Key | Identifies | Business approver? |
|---|---|---|
| Member key | The node that relayed, voted, and finalized | Only in machines that use it that way, such as `approvals` and `balances` |
| Actor key | A person or organization with governed roles | Yes, in the role workflow |
| API key | A caller allowed to use a node's REST API | No: it is not an identity |

## Proven and trusted

A proof checked against a root you trust establishes facts about the ledger,
not about the world.

| Proven, against a root you pinned | Still trusted |
|---|---|
| A record is, or is not, in the state at a height | That the record's content is true |
| A threshold of the members you pinned finalized the block | That those members are who you think they are, and that too many of them do not collude |
| An actor's registered key signed this exact statement | That the actor registry's real-world identity check was correct |
| A Cardano anchor you checked commits to this root | That the data is still available: availability is reported as not proven |
| A member recorded an effect result | That the external system really acted: an effect result is a member's attestation |
| A member accepted this message | Who, behind that member, asked for it |

The members also run the same code. A proof cannot show that the state machine
or a plugin does what its documentation says.

## Trust levels

Verifiers never return a bare "valid". They report how far the result can be
trusted, using five levels:

| Level | Meaning |
|---|---|
| `INTERNAL_CONSISTENCY_ONLY` | The proof checks against a root carried in the same package. Nothing ties it to the real ledger. |
| `CALLER_PINNED_ROOT` | You supplied the chain, commitment, root, and height identity, or the members and threshold, independently. |
| `NODE_CONFIRMED_L1_REFERENCE` | The node you asked reports an accepted anchor. Its contents were not checked independently. |
| `CALLER_PINNED_ANCHOR` | You supplied and verified the complete expected Cardano anchor. |
| `INDEPENDENTLY_VERIFIED_L1_ANCHOR` | The verifier checked the Cardano anchor itself, not a node's report of it. |

Products' CLIs map these to exit codes; for Attest, Trust Registry, DPP
Starter, and Attestation Feed, only exits 0 and 5 mean verified. Get the members
file or the anchor datum from a source you trust, never from the bundle you are
checking.

## How many members can fail

A block is final when `threshold` members sign it, and the host checks the
threshold against a **fault bound** `f`, the number of members allowed to be
dishonest, set as `consensus.max-byzantine-members` (default 0). At startup it
requires:

- `2t − n > f`, or it fails with "consensus quorums do not intersect in an honest
  member"; and
- `t ≤ n − f`, or it fails with "consensus threshold cannot remain live under the
  configured fault bound".

With the default `f = 0`, the ledger is crash-tolerant: up to `n − t` members can
be offline, but no member is assumed to lie. A threshold of 2 of 3 tolerates one
member being down, not one dishonest member; it cannot be configured with
`f = 1`. Tolerating one dishonest member needs, for example, 4 members, a
threshold of 3, and `f = 1`.

## Node-local secrets

- **API keys** control access to a node's REST API: `yano.app-chain.api.keys`,
  sent as `X-API-Key`. A full key unlocks privileged operations, such as pausing
  submissions, membership and threshold administration, effect operations, and
  plugin operations; a topic-scoped key can only submit. Because the node signs
  submissions with its member key, whoever can submit through a node speaks as
  that member on the allowed topics.
- **The anchor wallet**, `yano.app-chain.anchor.signing-key`, pays Cardano fees
  on the anchor leader. Treat it as a hot wallet holding fee money only. In
  script mode a threshold of members must co-sign every anchor advance, so a
  compromised leader can stop anchoring but cannot advance the anchor alone.
- **Connector credentials** for Kafka, S3, IPFS, and Cardano payments stay in
  each executor node's configuration, never in an effect payload, blueprint, or
  shared configuration.

## Plugins are trusted code

Plugins run in the node's process, loaded through a shared, parent-first class
loader. That is not a sandbox: a plugin can do anything the node can do.

Plugin publishers sign a plugin's catalog and manifest with
`./yano.sh appchain plugin sign`, and Yano X tooling (`validate`,
`init --trust-key`, and `doctor`) checks the signature and pins the JAR digests
in the project lock. **The node itself does not check publisher signatures when
it loads plugins**; signed artifacts at load time are deferred work. So:

- install only plugins you trust, and the same JARs on every member;
- restrict which bundles load with `yano.plugins.allow-list` and
  `yano.plugins.deny-list`; and
- compare members with `./yano.sh appchain drift` to catch a node running
  different plugins or configuration.

Next: [Glossary](/reference/glossary/).
