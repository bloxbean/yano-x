# Declarative binding authoring commands

For a guided introduction, start with [your first workflow](bindings/01-first-workflow.md).
The [learning path](bindings/README.md) explains separate YAML versus inline
blueprints and the current read-only Studio graph viewer.

`appchain bindings` compiles and checks ADR-031.1 documents against the actual
plugin bundles selected for the application. It does not start a node or submit
transactions. Use the matching Yano X CLI, Yano host API, and dependency-complete
plugin bundles from one build.

```bash
./yano.sh appchain bindings compile bindings.yml \
  --plugins-directory /absolute/path/to/plugins --context context.json
./yano.sh appchain bindings validate bindings.yml \
  --plugins-directory /absolute/path/to/plugins --context context.json
./yano.sh appchain bindings graph bindings.yml \
  --plugins-directory /absolute/path/to/plugins --context context.json
./yano.sh appchain bindings dry-run bindings.yml \
  --plugins-directory /absolute/path/to/plugins --context context.json \
  --fixture fixture.json
```

All results go to stdout. Redirect them to a new file when needed. `compile`
prints canonical IR hex. `validate` prints JSON containing the actual profile's
manifest and diagnostic status. `graph` prints Graphviz DOT. `dry-run` prints
canonical receipt hex, decoded receipt arrays, effect intents, and physical
state changes and a complete `postState` rehearsal snapshot. Construction failures
retain authored paths such as `$.composite.components[0].config`; JSON conversion
failures retain field/index paths rather than only an underlying constructor message.

## Receipt proof-key discovery

```bash
./yano.sh appchain bindings receipt-key "$SOURCE_MESSAGE_ID"
```

This data-only command accepts exactly one 32-byte source message ID and prints
`stateKeyHex`, `receiptQueryPath`, and `keyQueryPath`. It uses the public workflow
namespace encoder; no manual CBOR or namespace concatenation is needed. The key
survives replacement of the same logical workflow. It does **not** assert that
the receipt exists or prove anything by itself. Use `stateKeyHex` with the normal
chain state-proof endpoint and verify against a separately trusted root. The
runtime `composite/binding-receipt-key-v1/<id>` query discovers the same raw
physical key: the generic HTTP query envelope's `payloadHex` is directly usable
as the proof key, with no CBOR decoding. This query takes empty parameters and
works even when the receipt is absent; it is not a presence claim.

## Consecutive-block rehearsal

```bash
./yano.sh appchain bindings dry-run bindings.yml \
  --plugins-directory /absolute/path/to/plugins --context context.json \
  --fixture block-1.json > result-1.json
./yano.sh appchain bindings dry-run bindings.yml \
  --plugins-directory /absolute/path/to/plugins --context context.json \
  --fixture block-2.json --prior-result result-1.json > result-2.json
```

For a bounded continuation without duplicated receipt diagnostics, add
`--continuation-only` to `dry-run`; its output is directly accepted by the next
`--prior-result`. To preserve a full diagnostic result and a compact continuation
without executing twice:

```bash
jq '{assurance,height,executionIdentity,postState}' result-1.json > continuation-1.json
```

The compact artifact fits the continuation input bound even at maximum fixture
state size. Full diagnostic results may be larger because they repeat state and
receipt encodings; extract the compact fields before continuing those results.

The next fixture must have `state: []` and the immediately following height.
Its messages, timestamp, assumed predecessor `stateRootHex`, and `pendingEffects`
remain explicit. The CLI carries the previous complete physical `postState`,
including retained receipts, rather than incorrectly treating a change set as
complete state. A fresh catalog machine is constructed for each invocation.
The continuation identity checks canonical IR, explicit chain/context, and the
catalog-created capability manifest; a changed profile/context is rejected.
Same-ID replay therefore retains the original receipt without reapplying its
business effects.

These artifacts are editable, **unverified rehearsal inputs**, not authenticated
node exports or backups. Their identity detects accidental mixing, not tampering.
No post-state root is computed; no finality, proof, signature, or effect delivery
is verified. Do not import `postState` into a running chain. Keep predecessor
root and pending-effect assumptions appropriate to the scenario being rehearsed.
Continuation input is capped at 64 MiB and still obeys the fixture's independent
state-entry and memory bounds; it is intended for bounded application rehearsals.

## Generate governed recipes for your own chain

```bash
./yano.sh appchain bindings recipe dpp \
  --descriptor actors.json --members members.json --threshold 2 > recipe.json
jq '.document' recipe.json > bindings.yml
jq '.context' recipe.json > context.json
./yano.sh appchain bindings validate bindings.yml \
  --plugins-directory /absolute/path/to/plugins --context context.json
```

Use `feed` instead of `dpp` for the attestation-feed round-approval starter. This
is a packaged CLI command, not a Gradle test task. JSON is accepted as YAML, so
the extracted document is directly compilable. The generator uses the public
product profile builders for the real schemas, policies, actor genesis, and
authenticated-map genesis. It activates no runtime provider; the last command
validates the output against your explicit catalog. No demo chain ID, member,
actor identity, private key, or proof is substituted.

`members.json` is an array of your member public-key hex strings. `actors.json`
is a closed schema-version-1 public descriptor with these fields:

- `schemaVersion: 1`, your `chainId`, and `organizations: [{"id":"..."}]`;
- `actors`: each has `id`, `organizationId`, `roles`, `keyId`, `publicKeyHex`,
  and `keyProofHex`;
- `authority`: `id`, `administratorActorIds`, `distinctActorThreshold`, and
  `maximumLifetimeBlocks`;
- optional empty `issuers` and `schemas` arrays. Nonempty initial entries are
  not supported by these starter recipes.

Choose roles and independent organizations matching the product's policy:
DPP uses `manufacturer`, `operator`, `claim-issuer`, `certifier`, and `auditor`
(with `dpp-admin` administration); feed uses `source`, `feed-operator`, and
`publisher` (with `feed-admin` administration). These are product starter
policies, not a generator for arbitrary governance policy. Actors need not be
consensus members. Review the emitted normalized `members`, `proposer`,
`threshold`, and `blockIntervalMs` when configuring the chain; changing these
genesis inputs requires regeneration, not editing opaque CBOR.

Generation checks starter usability at height 1: every direct policy needs an
eligible role holder, every approval policy needs an eligible proposer, and its
voters must meet the distinct-organization quorum. Eligibility requires an active
actor, organization, and signing key. Proposer roles are alternatives; a proposer
may also vote when it holds the voting role. These checks cover the fixed starter
policies only, not general policy satisfiability, future key validity, or the
business prerequisites of every possible action.

Public keys alone cannot prove possession. Each actor signs a chain-bound proof
using the existing offline role CLI, outside the recipe generator:

```bash
PUBLIC_KEY="$(./yano.sh appchain role public-key --seed-file /owner-only/actor.seed)"
KEY_PROOF="$(./yano.sh appchain role key-proof \
  --chain "$CHAIN_ID" --actor "$ACTOR_ID" --actor-revision 1 \
  --key "$KEY_ID" --public-key "$PUBLIC_KEY" \
  --valid-from-height 1 --valid-until-height 0 \
  --seed-file /owner-only/actor.seed)"
```

Put the public key and full encoded `KEY_PROOF` into that actor's descriptor
entry. Do not use the signature-only command for `keyProofHex`. The generator
verifies every proof against the supplied chain, actor, and key; changing the
chain ID requires fresh proofs. It never reads seed files. Generated DPP/feed
binding workflows retain the documented limitations around legacy product
projections and certificates; generation does not imply those integrations.

## Admission and catalog validation

Before changing plugin bundles for retained profiles, run:

```bash
./yano.sh appchain bindings profile-check --profiles profiles.json \
  --context context.json --plugins-directory /absolute/path/to/candidate-plugins
```

`profiles.json` is a bounded JSON array of retained canonical composite profile
hex strings. The checker constructs the real candidate catalog and verifies
byte-exact reproduction, reporting `reproducesProfiles`, expected profile
digests, and bounded diagnostics. Exit zero means all supplied profiles were
reproduced; exit 2 means invalid/incompatible input, 64 is usage, and 74 is I/O.
Use the original explicit chain context. This is an upgrade preflight, not a
migration tool, replay test, proof verification, or semantic-equivalence claim.
Retain independent replay/proof regression evidence before deploying an upgrade.

The default event allowance is 65,536 encoded bytes. A baseline payload contains
command-body metadata only when a binding subscribes to that event; other sources
have no baseline-wrapper command cap. Admission checks any required baseline size
and statically impossible mandatory work before execution. A fixture containing
an admission-invalid source fails validation rather than fabricating a finalized
rejection receipt. Dynamic cascade failures still produce receipts. See
[submission validity and retry](DECLARATIVE_BINDINGS.md#submission-validity-and-retry)
for HTTP outcomes, fresh-message retry semantics, and committed work budgets.

Pass `--ir` to read an IR hex file instead of YAML. `graph --ir` is a data-only
operation and needs neither a plugin directory nor context; it does not claim
the graph is executable. The other commands always construct the real profile
through the host plugin catalog. Unmanifested libraries, missing providers,
incompatible API levels, invalid mappings, and cyclic command graphs fail
closed. Optional providers are never instantiated as a fallback. The sole host
builtin, OrderedLog, uses the same explicit resolution branch as a running node.

## Document

The input is the blueprint's `composite` object, either directly or wrapped in
one `composite` property. This example copies every accepted key/value change
to an audit trail:

```yaml
composite:
  components:
    - {id: records, machine: kv-registry}
    - {id: audit, machine: doc-trail}
  bindings:
    - id: audit-record
      from: {component: records, event: kv-registry.entry-put.v1}
      when: [{expr: 'event.valueLength < 100'}]
      to:
        component: audit
        command: append
        map:
          entityId: {fn: hex, args: [{field: key}]}
          entryHash: {field: valueHash}
          reference: {literal: published}
```

The registry key is bytes, whereas document-trail entity IDs are text. The
explicit `hex` conversion is required; mappings never silently coerce types.

Authoring rules:

- Component ingress topics default to `<id>.command.v1`. Configuration defaults
  come from the exact selected kernel and are included in the IR.
- Component `fromHeight` and top-level `workflowFromHeight` default to 1.
  Replacements still need the normal governed profile activation procedure.
- Scalar literals are signed int64, text, boolean, or bytes written as
  `{bytesHex: '00ff'}`. For example: `{literal: {bytesHex: '00ff'}}`.
- Sources are exactly one of `{field: name}`, `{literal: value}`,
  `{fn: name, args: [...]}`, or `{expr: 'restricted CEL'}`.
- Field conditions are `{field: name, eq: value}`, using one of `eq`, `ne`,
  `lt`, `le`, `gt`, `ge`, `in`, `exists`, or `absent`. The last two require
  `true`. CEL conditions use `{expr: 'boolean expression'}`.
- Lookup conditions are
  `{lookup: {component: registry, key: {field: key}, exists: true}}`.
  Alternatives are `absent: true` or
  `eq: {literal: {bytesHex: '01'}}` / `eq: {field: eventBytes}`. Keys follow
  the selected kernel's logical-key contract.
- A command target uses `component`, `command`, and `map`. An opaque bytes
  mapping uses `rawBody: fieldName` instead of `map`; evidence-bearing target
  commands reject this bypass.
- An effect target is `to: {effect: {type: webhook.post, gate: app-final,
  result: none, map: identity}}`. Its originating component needs an explicit
  positive `maxEffectsPerBlock` quota and the consensus context must enable
  effects. No effect executor is invoked by these commands.
- Unknown or duplicate fields, ambiguous operators/sources, unsupported CEL,
  YAML aliases, and multiple YAML documents are errors. Component/binding
  order is preserved; assignment/configuration map ordering is normalized.

## Explicit context

The context is strict JSON, not a node configuration. Copy the exact chain
identity, consensus profile, and membership epoch of the intended application.
Never generate a replacement genesis ID for an existing chain. Component
`machines.*` settings are rejected here: put them in the document's component
configuration so they are committed.

The following values are an offline-only example, not a retained deployment's
identity or an instruction to provision a chain:

```json
{
  "chainId": "offline-test",
  "settings": {
    "state.commitment-profile": "mpf-blake2b256-v1",
    "state.format-fingerprint": "91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b",
    "state.genesis-id": "0000000000000000000000000000000000000000000000000000000000000000"
  },
  "consensusProfile": {
    "schemaVersion": 2,
    "maxMessageBytes": 65536,
    "maxBlockMessages": 100,
    "maxBlockBytes": 1048576,
    "l1StabilityDepth": 0,
    "epochStabilityDepth": 0,
    "enforceSenderSeq": false,
    "effectsEnabled": false,
    "effectsMaxPerBlock": 0,
    "effectsMaxPayloadBytes": 0,
    "effectsMaxExpiryBlocks": 0,
    "effectsResultWindowBlocks": 0,
    "effectsDefaultGate": "APP_FINAL",
    "effectsOutcomeCommitment": "PER_EFFECT",
    "effectsStrictReservedPrefix": true,
    "effectResultSigners": []
  },
  "membership": {
    "fromHeight": 0,
    "members": ["2222222222222222222222222222222222222222222222222222222222222222"],
    "threshold": 1
  }
}
```

## Dry-run fixture and limits of assurance

A fixture executes one block containing zero or more ordinary source messages.
Message order is the original block order. `state` contains physical composite
keys, not machine-local keys; each entry is `{keyHex, valueHex}`. Empty state
and height 1 exercise genesis initialization. For a later height, supply the
actual preceding state, including composite/profile markers.
Use explicit `messages: []` to rehearse an empty block. It still initializes the
machine and executes its full block lifecycle, including height-driven work;
it emits no source receipts. Continue through every intervening height rather
than jumping directly to a later height.

```json
{
  "height": 1,
  "timestamp": 123,
  "stateRootHex": "0000000000000000000000000000000000000000000000000000000000000000",
  "pendingEffects": 0,
  "state": [],
  "messages": [{
    "messageIdHex": "1111111111111111111111111111111111111111111111111111111111111111",
    "senderHex": "2222222222222222222222222222222222222222222222222222222222222222",
    "senderSeq": 1,
    "expiresAt": 9223372036854775807,
    "topic": "records.command.v1",
    "bodyHex": "8300420102420304",
    "authProofHex": "00"
  }]
}
```

This deliberately synthetic authentication proof is acceptable only because
dry-run assumes that envelope authentication already happened. The ordinary
registry command puts bytes `0304` at key `0102`; the audit entity becomes
text `0102`. The returned receipt bytes come from the real composite query,
not a separate simulation of its transition logic.

Dry-run does **not** verify message signatures, membership proofs, certificates,
fixture state roots, or anchors; it does not calculate a post-state root. It
does not process reserved framework inputs or deliver effects. It is not a
substitute for node, replay, distribution, or multi-node acceptance testing.
Fixture input, memory, state entry sizes, and output mutation counts are
bounded independently of the committed workflow limits. A fixture-capacity
error is a tooling failure, not a new consensus rejection code.

## Worked proposal and two approvals through the packaged launcher

This small `approvals` example uses consensus-member voting, separate from the
actor-signed DPP/feed policies above. Save this as `approval.yml`:

```yaml
composite:
  components:
    - {id: reviews, machine: approvals}
    - {id: audit, machine: doc-trail}
  bindings:
    - id: record-approved
      from: {component: reviews, event: approvals.item-approved.v1}
      to:
        component: audit
        command: append
        map:
          entityId: {field: itemId}
          entryHash: {field: payloadHash}
          reference: {literal: approved}
```

Start with the offline `context.json` above and add the second public member.
These repeated-byte identities and assumed envelope authentication are rehearsal
data only; there are no private keys or valid envelope signatures here.

```bash
jq '.membership.members += [("33" * 32)] | .membership.threshold = 2' \
  context.json > approval-context.json
```

Save this as `propose.json`. The short body is the public
`ApprovalsContract.propose("a", new byte[]{1}, 2, 0)` encoding: item `a`, payload
`01`, two votes required, no deadline. The approval body `82016161` is
`ApprovalsContract.approve("a")`. No manual physical state encoding is needed.

```json
{
  "height": 1,
  "timestamp": 100,
  "stateRootHex": "0000000000000000000000000000000000000000000000000000000000000000",
  "pendingEffects": 0,
  "state": [],
  "messages": [{
    "messageIdHex": "1111111111111111111111111111111111111111111111111111111111111111",
    "senderHex": "2222222222222222222222222222222222222222222222222222222222222222",
    "senderSeq": 1,
    "expiresAt": 999999999,
    "topic": "reviews.command.v1",
    "bodyHex": "8500616141010200",
    "authProofHex": "00"
  }]
}
```

Create two fresh approval messages, then invoke the packaged CLI once per block:

```bash
jq '.height = 2 | .timestamp = 200 | .messages[0] |=
  (.messageIdHex = ("44" * 32) | .senderSeq = 2 | .bodyHex = "82016161")' \
  propose.json > approve-1.json
jq '.height = 3 | .timestamp = 300 | .messages[0] |=
  (.messageIdHex = ("55" * 32) | .senderHex = ("33" * 32) | .senderSeq = 1)' \
  approve-1.json > approve-2.json
./yano.sh appchain bindings dry-run approval.yml --plugins-directory plugins \
  --context approval-context.json --fixture propose.json > proposed.json
./yano.sh appchain bindings dry-run approval.yml --plugins-directory plugins \
  --context approval-context.json --fixture approve-1.json \
  --prior-result proposed.json > voted.json
./yano.sh appchain bindings dry-run approval.yml --plugins-directory plugins \
  --context approval-context.json --fixture approve-2.json \
  --prior-result voted.json > approved.json
jq '[.receipts[].receiptHex], .stateChanges' approved.json
```

The first two blocks retain a pending proposal and have one source step each.
The third has the source step plus the derived audit append. Its complete
`postState` retains all three receipts and the approved item with two distinct
voters. These are new commands acting on carried state, not replay of one message.
The zero predecessor roots remain explicit, unverified rehearsal assumptions.

To advance one idle height while retaining the state:

```bash
jq '.height = 4 | .timestamp = 400 | .messages = []' approve-2.json > idle.json
./yano.sh appchain bindings dry-run approval.yml --plugins-directory plugins \
  --context approval-context.json --fixture idle.json \
  --prior-result approved.json > idle-result.json
```

The idle block has no receipts; it still runs the full block lifecycle. Height 5
cannot directly follow `approved.json` at height 3, even with an empty message list.

## Blueprint projects

Initialize from an explicit document and installed authoring bundles:

```bash
./yano.sh appchain init --non-interactive --recipe declarative-composite \
  --network devnet --members 3 --member-key "$MEMBER_1" --member-key "$MEMBER_2" \
  --member-key "$MEMBER_3" --bindings bindings.yaml --plugins-directory plugins \
  --output workflow-project
```

Both flags are required together and rejected for other recipes. Input paths
resolve against the invocation directory; the imported document is copied into
the blueprint, so later edits to the original file do not silently change the
project. The plugin location is stored relative to the blueprint where possible
(absolute when filesystem roots differ). Rendering validates before creating
project output.

Select recipe `declarative-composite` and put the composite document's body in
the chain's `composite` field. Supply all topology member public keys, and set
`spec.runtime.pluginsDirectory` to the dependency-complete authoring bundles.
A relative directory resolves against the project blueprint directory, never
the shell's working directory. This is an authoring-only location: it is not
copied into generated node properties or treated as consensus identity.

Rendering constructs the actual catalog-selected composite, writes its canonical
IR into the chain configuration, and pins the IR digest, composite profile digest,
and plugin catalog fingerprint in `appchain.lock`. Validation and change planning
recompute these pins; replacing local bundles cannot silently preserve an old
lock. Deployment still needs its own installed runtime plugin directory.

The offline commands validate a fixed genesis composite profile. They do not
rehearse composite-profile governance activation or accept arbitrary
`machines.*` context overrides. Role and authenticated-map membership context
may still use the ordinary governed membership settings where their profiles
require it.
