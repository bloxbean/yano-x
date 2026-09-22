# Declarative binding authoring commands

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
state changes.

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

A fixture executes one block containing one or more ordinary source messages.
Message order is the original block order. `state` contains physical composite
keys, not machine-local keys; each entry is `{keyHex, valueHex}`. Empty state
and height 1 exercise genesis initialization. For a later height, supply the
actual preceding state, including composite/profile markers.

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
