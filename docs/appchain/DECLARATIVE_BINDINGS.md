# Declarative event bindings

Status: experimental, undergoing ADR-031.1 qualification. Do not use this
profile as a production compatibility promise.

Declarative composites connect existing state-machine commands through bounded
event bindings. For example, registering an order can open an approval round;
the threshold-reaching vote can append an audit entry in the same atomic
cascade. Application authors configure these connections without implementing
a Java coordinator. Nodes execute canonical binary IR, not YAML or a scripting
engine.

Start with [procurement](../../examples/bindings/procurement.yaml) or
[approval-gated attestation](../../examples/bindings/attestation.yaml).
See [the CLI guide](DECLARATIVE_BINDINGS_CLI.md) for compilation, validation,
graphing, and deterministic fixture execution.

## Mental model

Each component has an instance id, its own state namespace, a catalog-selected
machine, normalized committed settings, and a distinct ingress topic. Two
instances of the same machine remain independent. A binding selects a source
component and event, optionally checks conditions, then builds a command for
another component or an effect intent.

One source message and its derived commands form one breadth-first cascade.
Later commands see earlier planned changes. All business writes and effect
intents commit only if every step succeeds. A rejection discards those plans
and stores an authenticated explanation. Work already attempted remains
charged, so repeatedly failing cascades cannot evade block limits. Storage
corruption or infrastructure errors abort the host block transaction; they
are not ordinary business rejections.

Human approval is not synchronous: the proposal commits in one cascade, votes
arrive in later source messages, and only a threshold-reaching vote produces
the approved event. An external effect is an outbox boundary, not a synchronous
call or exactly-once delivery guarantee.

## Authoring rules

- YAML order controls component and binding order. Changing either changes
  the committed program. Configuration defaults are normalized before hashing.
- Event fields are signed 64-bit integers, booleans, text, or bytes. Nested
  values travel as canonical CBOR bytes. There is no implicit bytes/text
  conversion: use `hex`, `utf8-bytes`, or a documented codec explicitly.
- `when` clauses are a short-circuiting conjunction. `expr` accepts the
  restricted `yano-x-cel-v1` profile: checked integer arithmetic, comparisons,
  boolean logic, a lazy conditional, and bounded concatenation. Loops,
  comprehensions, floating point, arbitrary functions, and Java access are
  rejected.
- Lookups read one logical key from a named participant, including earlier
  cascade writes. The owner converts the logical key to its canonical local
  key. Map lookups use canonical `[collectionId, applicationKey]` bytes.
  Lookups cannot enumerate or read another namespace implicitly.
- Every evidence field must copy an event field directly. A literal, function,
  or expression cannot manufacture an actor signature or approval reference.
  The target still verifies the action, signer, policy, deadline, and one-use
  consumption. Raw command forwarding is forbidden if any target command
  accepts evidence.
- Command edges must be acyclic. All byte, depth, fan-out, function, and work
  limits are committed in the IR. The expression-named work budgets also
  cover non-CEL conditions, functions, lookups, event decoding, and copies.

Standalone balances retain their existing arbitrary-precision behavior. The
declarative native-event path rejects values outside signed int64 with
`BALANCE_EVENT_RANGE`; it never rounds or truncates them. Approvals configured
with the legacy on-approved effect do not expose a composable kernel: use an
explicit effect binding instead of enabling both activation paths.

## Language reference

Field clauses support `eq`/`ne` on equal scalar types, `lt`/`le`/`gt`/`ge` on
integers, and `in`/`exists`/`absent` on text, bytes, or integers. `in` has at
most 64 operands. Lookup clauses support existence, absence, equality to a
byte literal, or equality to a byte-valued event field. Clauses execute in
declaration order and stop at the first false result; an evaluation error
rejects the cascade instead of silently skipping it.

Mapping sources are `{field: name}`, `{literal: value}`, `{fn: id, args: [...]}`,
or `{expr: '...'}`. Command field mappings follow the target's declared codec
layout, not YAML key order. `map: identity` is for effects only. `rawBody`
forwards a byte-valued event field and is subject to the evidence restriction.
Functions nest at most two levels, with at most eight arguments per call.

| Function | Inputs → output |
|---|---|
| `blake2b-256`, `sha-256` | Bytes or UTF-8 text → 32 hash bytes |
| `concat` | 2–8 values, all text or all bytes → joined value of the same type |
| `utf8-bytes` | Text → UTF-8 bytes |
| `hex` | Bytes → lowercase hexadecimal text |
| `byte-length` | Bytes or text → byte count; text counts UTF-8 bytes |
| `cbor-encode` | One scalar → canonical CBOR bytes |
| `cbor-field` | Canonical scalar-valued CBOR map bytes and text key → scalar field |

There is no automatic JSON/CBOR conversion, nested map traversal, regex,
network lookup, clock, or random input. `cbor-field` rejects malformed maps
and missing fields. Input and output sizes are bounded, including UTF-8
conversion and concatenation.

All limits below are positive committed integers. Larger values are not
node-local tuning overrides; changing them changes the profile.

| Limit | Default | Maximum |
|---|---:|---:|
| `maxCascadeDepth` | 8 | 32 |
| `maxDerivedPerSourceMessage` | 32 | 256 |
| `maxDerivedPerBlock` | 4,096 | 65,536 |
| `maxEventPayloadBytes` | 4,096 | 65,536 |
| `maxLookupsPerCondition` | 2 | 4 |
| `maxFunctionCallsPerMapping` | 8 | 16 |
| `maxFunctionInputBytes` | 4,096 | 65,536 |
| `maxExpressionNodes` | 128 | 512 |
| `maxExpressionDepth` | 16 | 32 |
| `maxExpressionValueBytes` | 4,096 | 65,536 |
| `maxExpressionWorkPerCascade` | 262,144 | 4,194,304 |
| `maxExpressionWorkPerBlock` | 4,194,304 | 67,108,864 |

Documents additionally cap components at 16, bindings at 256, clauses per
binding at 8, and assignments per mapping at 16. IR and receipts each cap
encoded bytes at 65,536. A profile's enclosing encoding can impose a tighter
effective IR size. Work counters charge attempted work even when a cascade
rejects; receipt replay does not repeat the work.

## Why did a binding not fire?

First query the source receipt and inspect its overall outcome. A `PLANNED`
step inside a rejected receipt did **not** commit. For each considered binding,
`failedClause` is the zero-based first false/error clause, or `-1` if all
clauses matched. A binding-level budget failure before the first clause also
uses `-1`; the rejected step and code distinguish it. Unvisited bindings are
not invented in the trace. Capacity fallback can omit non-failed history and,
if needed, the failed step's event names. It always preserves the failed step,
its original code, and every visited condition record, including the failing
binding and clause. The overall receipt code reports `RECEIPT_CAPACITY_EXCEEDED`.

Reproduce with `bindings dry-run` using the exact committed IR, block height,
timestamp, message order, and pre-block state fixture. This explains execution;
it does not authenticate the fixture or prove its root.

| Codes | Meaning / next check |
|---|---|
| `LIMIT_DEPTH`, `LIMIT_FANOUT`, `CAPACITY_EXCEEDED` | Cascade depth, source fan-out, or block derivation budget exhausted |
| `EXPRESSION_CAPACITY_EXCEEDED` | Binding-language work budget exhausted, including non-CEL operations |
| `EVENT_PAYLOAD_TOO_LARGE`, `RECEIPT_CAPACITY_EXCEEDED` | Event or authenticated trace exceeded its byte/record cap |
| `EXPRESSION_VALUE_LIMIT`, `FUNCTION_INPUT_LIMIT`, `FUNCTION_OUTPUT_LIMIT` | Intermediate value or function byte bound exceeded |
| `LOOKUP_KEY_LIMIT`, `STATE_KEY_LIMIT` | Logical or namespaced physical key exceeded its bound |
| `EFFECT_CAPACITY_EXCEEDED`, `EFFECT_PAYLOAD_LIMIT`, `EFFECT_EXPIRY_LIMIT` | Workflow quota or host effect profile rejected the planned intent |
| `CRYPTO_WORK_EXCEEDED` | Shared owner-scoped cryptographic-work budget exhausted |
| `EXPRESSION_OVERFLOW`, `EXPRESSION_DIVISION_BY_ZERO` | Checked signed-int64 arithmetic failed |
| `EXPRESSION_MISSING_FIELD`, `EXPRESSION_TYPE_ERROR`, `EVENT_MISSING_FIELD`, `EVENT_TYPE_ERROR` | Produced event does not satisfy the selected expression/schema |
| `FUNCTION_INVALID_CBOR`, `FUNCTION_MISSING_FIELD` | `cbor-field` input is malformed or lacks the requested key |
| `LOOKUP_KEY_TYPE`, `LOOKUP_KEY_INVALID`, `MAPPING_MISSING_FIELD`, `MAPPING_TYPE_ERROR`, `INVALID_UNICODE` | Mapping or logical-key decoding failed |
| `MALFORMED_DERIVED_COMMAND`, `ADMISSION` | Target codec or admission rejected the command |
| `CONSUMPTION_CONFLICT`, `REPLAY_OR_CONFLICT` | One-use consumption or workflow claim conflicted |
| `RESERVED_ACCOUNTING_KEY`, `RESERVED_EVENT_ID`, `UNDECLARED_WORK_REFERENCE` | Kernel attempted a reserved operation; check the plugin contract |

Targets can also return their existing domain rejection codes, such as an
authorization failure. Infrastructure failures remain block failures rather
than being relabeled as ordinary cascade receipts.

## Governed map actions

Use explicit catalog leaves, not nested standalone presets:

| Selector | Purpose |
|---|---|
| `domain-actors-component` | Genesis-fixed actor authority and shared crypto-work owner |
| `governed-role-approvals` | Actor-signed proposal/vote lifecycle with bounded action staging |
| `authenticated-map-component` | Existing map decisions, authorization, consumption, and proofs |

The role leaf accepts `StagedActorCommandV1`: a separate canonical wrapper
containing the unchanged signed actor command and, only for PROPOSE, the action
bytes. A proposal stages at most 32,768 bytes. Approval passes the action and
proposal id to the map; the map recomputes the signed scoped hash. A mismatched
action rolls back the threshold-reaching vote and map write together, retains
the pending proposal/action, and keeps the signature-work charge.

The map offers `apply-basic-action` for evidence-free collections,
`apply-action` with an approval reference, and `apply-authorized` with the
existing complete signed authorization envelope. Selecting a different command
does not bypass collection authorization.

The initial actor leaf is deliberately genesis-fixed. It does not support
actor rotation or governance command derivation. Existing standalone governed
presets retain their governance routes. Plan deployment lifecycles accordingly;
do not represent this assembly as full standalone-preset feature parity.

## Receipts, proofs, and product boundaries

Query `composite/binding-receipt-v1/<source-message-id-hex>` for the canonical
receipt. It records source identity, acceptance/rejection, step ordinals,
events, and condition results. Retrieve a state proof of its authenticated
workflow key and verify against caller-pinned chain identity and finality.
A dry-run receipt is an explanation, not a finality certificate or an L1 anchor.

Capability metadata exposes `declarative-event-bindings` with the
`yano-x-binding-graph-v1` schema. Studio can preview a locally imported manifest
as a graph; the preview does not authenticate the manifest or modify a blueprint.
Operational counters describe the last local execution attempt, which may be
a candidate later discarded by the host. They are not finalized-state indexes
and never feed consensus decisions.

Derived commands are not signed or independently stored as finalized messages.
Consequently the existing ADR-047 signed-message attestation certificate format
does not apply to a derived document-trail command. The attestation recipe proves
the receipt and trail state; it does not fabricate a certificate-compatible
envelope. A separately versioned composite certificate remains future work.

DPP/feed recipes automate the existing approval-gated map action. They retain
the starter product's schemas and distinct-organization approval policies.
Existing list projections/portals that replay original map envelopes do not
automatically discover derived writes; exact known-key proofs are the qualified
read path. Feed aggregation remains off chain and independently recomputable,
not a new on-chain price oracle or Cardano publication.

## Evolution and operation

The complete IR, component configuration, quotas, topics, and generation heights
are included in the composite profile commitment. A local YAML edit does not
upgrade a running chain. Use the existing governed profile-epoch activation
workflow. Changed component configuration requires a new component generation;
changed bindings require a new workflow generation. Retained genesis identity
and historical receipts must remain intact.

Generation changes do not migrate genesis-bound state. If a leaf's stored
genesis/configuration cannot change in place, allocate a new component instance
id and plan the application's explicit migration; a later generation height
alone does not make an incompatible stored genesis valid.

Use an exact published Yano Maven version with its matching ordinary JVM ZIP.
Optional machines must come through manifested plugin contributions; OrderedLog
remains the host's sole built-in exception. No optional classpath fallback or
source-checkout dependency is supported.
