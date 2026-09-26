# 2. Conditions and mappings

[Previous: First workflow](01-first-workflow.md) · [Learning path](README.md) ·
[Next: Approval workflows](03-approval-workflows.md)

Declarative bindings are experimental and still undergoing ADR-031.1
qualification. The examples here explain the current implementation, not a
production compatibility promise. Use matching authoring tools and manifested
plugin bundles; a node executes committed binary IR, not the YAML text.

A binding answers three questions: which event matters, whether this event
qualifies, and how its fields become a target command. Start with one event and
one target, then add conditions only when you can explain their business purpose.

## Start with a typed native event

Save this complete document as `conditions.yml`. It is the registry-to-audit
example exercised through the real plugin catalog in `BindingCliIT` and shown in
the [CLI guide](../DECLARATIVE_BINDINGS_CLI.md#document).

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

Submit registry commands to `records.command.v1`, the component's default
ingress topic. A successful put produces `kv-registry.entry-put.v1`. For values
shorter than 100 bytes, the binding appends an audit entry whose entity ID is
the lowercase hexadecimal registry key. It copies the event's value hash; it
does not store the original value in the audit entry.

The key is bytes and the target entity ID is text, so `hex` is essential. Field
names on the left of `map` belong to the target command; names inside `field`
belong to the source event. The target codec controls encoded field order.
Reordering YAML assignments does not change that codec layout.

The event fields used in this example are:

| Source field | Type | Use |
|---|---|---|
| `key` | bytes | Registry key; convert to text for the audit entity |
| `valueHash` | bytes | Value digest; copy to the audit entry hash |
| `valueLength` | integer | Value byte count; compare in the condition |

The same put event also declares `value` (bytes), `owner` (bytes), `height`
(integer) and optional `previousValueHash` (bytes). A first insertion has no
previous value hash. Do not assume every event contains the command's full
payload: fields are event-specific. The compiler checks field names and types
against the selected kernel's descriptors.

With the offline `context.json` and registry `fixture.json` from the
[CLI fixture example](../DECLARATIVE_BINDINGS_CLI.md#dry-run-fixture-and-limits-of-assurance),
use the extracted distribution's launcher and plugins:

```bash
./yano.sh appchain bindings validate conditions.yml \
  --plugins-directory plugins --context context.json
./yano.sh appchain bindings dry-run conditions.yml \
  --plugins-directory plugins --context context.json --fixture fixture.json
```

That fixture puts value bytes `0304` under key bytes `0102`: the condition is
true, the audit entity is text `0102`, and the receipt has a source step and a
derived append. A 100-byte value would make this condition false: the put may
still commit, but this binding does not append. A false condition is not a
rejected command.

## Add an explicit state condition

The checked-in [procurement recipe](../../../examples/bindings/procurement.yaml)
adds a supplier registry. Its first binding combines a size check with a lookup:

```yaml
- id: order-to-review
  from: {component: orders, event: kv-registry.entry-put.v1}
  when:
    - {field: valueLength, le: 2048}
    - lookup: {component: suppliers, key: {field: owner}, exists: true}
  to:
    component: reviews
    command: propose
    map:
      itemId: {fn: hex, args: [{field: key}]}
      payload: {field: value}
      required: {literal: 2}
      deadlineMillis: {literal: 0}
```

This is a binding-list entry, not a complete replacement document. Use it with
the recipe's `suppliers`, `orders`, and `reviews` components. Register the
supplier's sender bytes as a key in `suppliers` before sending an order. The
lookup tests registration of the event's owner, not the order key.

Clauses run in order as a short-circuiting conjunction. An oversized value
fails the first clause without reading supplier state. A missing supplier
fails the second. Neither failure opens a review, but neither makes the order
put itself invalid. If your business rule requires rejecting unregistered
orders, a condition that merely skips review is insufficient: enforce that
rule in the source's authorization/decision logic.

A lookup reads one logical key through the named component's key contract.
It sees earlier planned writes in the same cascade. It cannot scan a collection,
enumerate participants, or silently read another component's namespace.
Authenticated-map lookups require canonical CBOR bytes for
`[collectionId, applicationKey]`; a bare application key is not interchangeable.

In addition to `exists: true`, lookups support `absent: true` and byte equality
against a literal or byte-valued event field. For example, the equality operand
syntax is `eq: {literal: {bytesHex: '0102'}}`. Equality compares the returned
stored bytes, which may contain the machine's encoded record rather than just
your application value. Consult that machine's contract before constructing it.
An empty stored value is present; it is not an absent key.

## Use expressions for small deterministic decisions

`expr` uses the restricted `yano-x-cel-v1` profile. CEL is an authoring syntax;
the compiler lowers an allowed expression into portable typed IR. The runtime
does not execute arbitrary CEL, Java, or scripts.

The allowlist covers declared `event.field` values, scalar literals, equality,
integer ordering, `&&`, `||`, `!`, a lazy `condition ? yes : no`, checked integer
`+`, `-`, `*`, `/`, `%`, unary negation, and bounded text/bytes concatenation
with `+`. A `when` expression must return boolean. A mapping expression must
match its target field's type. For example, the procurement recipe uses
`event.approverCount >= 2` before appending its audit entry.

There are no loops, comprehensions, list/map construction, nested object
traversal, floating point, regex, clock, randomness, network calls, or Java
access. Even general CEL functions such as `size(event.currency)` are outside
this profile. Named mapping functions use `fn`, not arbitrary function calls
inside `expr`.

Integers are signed 64-bit values. Overflow and division by zero produce
deterministic errors; division truncates toward zero. Conditional branches are
lazy. CEL boolean error masking also applies: the source tests establish that
`(1 / 0 == 1) || true` evaluates to true and `true ? 7 : 1 / 0` evaluates to 7.
Resource exhaustion cannot be masked by a boolean result. Prefer clear guards
over deliberately relying on masking to hide invalid arithmetic.

The simpler field clauses remain useful: equality/inequality compare equal
scalar types, ordering compares integers, and `in`, `exists`, and `absent`
operate on text, bytes, or integers. `in` is bounded to 64 operands; existence
operators require `true`. An unhandled evaluation error rejects the entire
cascade rather than quietly treating the condition as false.

## Map values without implicit conversions

Each source is exactly one of `field`, `literal`, `fn` with `args`, or `expr`.
The four scalar types are integer, boolean, text, and bytes. Write a byte
literal as `{literal: {bytesHex: '00ff'}}`; `'00ff'` alone is text. Nested
application values travel as canonical CBOR bytes, not arbitrary YAML objects.

Use functions according to the representation you need:

- `hex` turns bytes into lowercase text; `utf8-bytes` turns text into bytes.
- `concat` joins 2–8 inputs of one type, all text or all bytes.
- `blake2b-256` and `sha-256` hash bytes or UTF-8 text into 32 bytes.
- `byte-length` measures bytes, including UTF-8 bytes for text.
- `cbor-encode` encodes one scalar; `cbor-field` extracts a scalar from a
  canonical scalar-valued CBOR map using a text key.

The [attestation recipe](../../../examples/bindings/attestation.yaml) demonstrates
why decoding matters. Its approved payload is a canonical CBOR map containing
`documentHash` bytes and `reference` text. Its append mapping is:

```yaml
map:
  entityId: {field: itemId}
  entryHash: {fn: cbor-field, args: [{field: payload}, {literal: documentHash}]}
  reference: {fn: cbor-field, args: [{field: payload}, {literal: reference}]}
```

This is the `map` of a `doc-trail` `append` target subscribed to
`approvals.item-approved.v1`. The external document's SHA-256 is prepared before
proposal; the document itself is not placed on chain. `cbor-field` neither
parses JSON nor traverses arbitrary nested maps. Malformed input or a missing
field rejects the cascade. Function nesting is limited to two levels and eight
arguments per call.

## Native events versus the baseline event

Native events describe domain transitions: a registry put, an approved item,
or an appended document entry. Prefer them when those semantics matter.
`composite.command-accepted.v1` is the generic baseline event for an accepted
component command. Its fields are `topic`, `sender`, `messageId`, `body`,
`bodyHash`, and `bodyLength`. It does not mean “an item was approved.”

For a byte-preserving tee between OrderedLog components, this complete document
is exercised by `BindingRecipesIT`:

```yaml
composite:
  components:
    - {id: source, machine: ordered-log}
    - {id: target, machine: ordered-log}
  bindings:
    - id: copy
      from: {component: source, event: composite.command-accepted.v1}
      to: {component: target, command: append, rawBody: body}
```

`rawBody` forwards a byte-valued event field as the complete command body.
It does not translate between codecs, and cannot be combined with `map`.
The target still decodes and admits the command. `map: identity` is a separate
feature for effect payloads only, not a shorthand for command mappings.

The baseline wrapper is materialized only when subscribed to. Its event size
includes metadata as well as the body, so a full 65,536-byte command does not
fit inside a 65,536-byte baseline event. Without a baseline subscriber there
is no wrapper-induced cap, although normal host, kernel, and native-event
limits still apply. No data is truncated to make it fit.

## Keep authorization and resource limits visible

An evidence field must copy an event field directly. Expressions, literals,
hashes, or concatenation cannot manufacture an actor signature or approval
reference. Raw forwarding is forbidden when any target command accepts
evidence. Targets still verify signers, policies, action hashes, deadlines,
and one-use consumption. A successful lookup is not a substitute for these
checks.

Command edges must be acyclic. Default limits include cascade depth 8, 32
derived operations per source message, and 65,536 encoded bytes per event.
Separate cascade and block work budgets charge conditions, lookups, functions,
mapping copies, decoding, and derived execution, even though their names say
“expression.” Attempted work is not refunded on rejection. A small payload
with large fan-out can exhaust work before exhausting the byte limit.

Limits, component order, binding order, normalized configuration, and generation
heights are committed. Raising a limit or recompiling with different defaults
is a profile change, not a node-local performance tweak. See the
[language and limit reference](../DECLARATIVE_BINDINGS.md#language-reference)
for exact bounds and [operations and upgrades](05-operations-and-upgrades.md)
before changing a deployed workflow.

When debugging, inspect the overall receipt first. A `PLANNED` step in a rejected
receipt did not commit. `failedClause` is the zero-based first false/error
clause; `-1` normally means all clauses matched, but can also mark a budget
failure before clause evaluation. Read the rejection code alongside it.

Source examples are covered by `BindingCliIT`, `BindingRecipesIT`,
`BindingDocumentCompilerTest`, `BindingExpressionCompilerTest`, and
`BindingLookupTest`; those names identify existing source coverage, not a claim
that this learning page has independently run the suites.

[Next: Build a multi-message approval workflow](03-approval-workflows.md)
