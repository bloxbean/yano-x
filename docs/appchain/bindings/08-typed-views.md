# 8. Typed views

[Previous: Admission rules](07-admission-rules.md) · [Learning path](README.md)

The rules in [chapter 7](07-admission-rules.md) judge the command in front of
them. **Typed views** (ADR-031.4) let a rule also read other components' state,
and judge every write in a batch. Kernels expose both as typed fields, so a rule
never parses stored bytes itself.

- **You'll learn:** how a rule reads another component's record, why every read
  needs a `present` guard, how `writes.all` and `writes.exists` walk a batch,
  and which slot each kind of rule runs in.
- **Before you start:** read [chapter 7](07-admission-rules.md). The recipes
  here run on a governed authenticated map; their fixtures need the `context.json`
  shipped beside them.

Typed views are experimental and need a matching host build; see the
[learning path](README.md) for the version requirement. A node executes
committed binary IR, not the YAML text.

## Read another component's state

A rule may declare up to four **reads**. Each reads one exact key of another
component's **value view**: the typed fields that component's kernel exposes for
the records it stores. `asset-governed-limits.yaml` keeps its transfer limit in
a map, not in the profile:

```yaml
    - id: governed-transfer-limit
      command: transfer
      deny: TRANSFER_LIMIT_EXCEEDED
      reads:
        limits: {component: registry, namespace: settings, key: {literal: "transfer"}}
      require:
        - expr: 'command.amount <= reads.limits.value.max'
```

- `component` names a declared component. `namespace` selects one of its views:
  `""` is the default, and each map collection is a namespace.
- `key` is a source from the rule's key scopes: `command`, `params`, `config`,
  `context` and `facts`. A key never reads another read.
- Reads run before the clauses, one rule at a time, in name order. For each
  read, the owning kernel turns the key into its own state key, the engine
  reads it through the state this cascade left, and the kernel decodes the
  record into the view's fields.
- Clauses read `reads.<name>.present` and the view fields, as
  `reads.<name>.<field>`, or `reads.<name>.value.<member>` for a map value's
  schema member.

An absent record has only `present == false`. Reading any other field of it is
an evaluation error, so the rule fails closed with `ADMISSION_RULE_ERROR` and no
deny code. Check `present` first, as `tier-limit` does below. A read that cannot
be made at all, for example because its key is refused, records clause `-1`.

The limit lives in the registry, so raising it is an approved map write, not a
profile change: the next block reads the new value. A rule with reads is never
static, so its refusals come at block time, with a finalized, provable receipt.
A fifth read does not compile: `validate` reports `EXPECTED_OBJECT`, "expected a
map of at most 4 reads".

## Rules that read state, step by step

`asset-governed-limits.yaml` attaches three rules to a `token` balances
component. `governed-transfer-limit` reads the limit above; `tier-limit` and
`lock-up` each read the sender's holder record. Each scenario below is one of
the recipe's fixture blocks.

<!-- illustration: typed-view-explorer -->
1. **Submit a transfer.** At height 4, member `f553…` transfers 100 tokens. All
   three rules select `transfer` and declare reads, so they run at block time.
2. **Read the limit.** `governed-transfer-limit` reads `settings/transfer` from
   the registry: `present`, `ACTIVE`, `value.max` 1,000.
3. **Check the limit.** 100 is within 1,000, so the rule holds.
4. **Read the holder.** `tier-limit` reads `holders/<sender>`: `present`,
   `ACTIVE`, a tier (`value.maxTransfer`) of 500, acquired at height 0.
5. **Check the tier.** The record is present and active, and 100 is within 500.
6. **Check the lock-up.** `lock-up` makes its own `holder` read. Height 4 is at
   least 0 plus the 2 blocks of `params.blocks`.
7. **Transfer commits.** All three rules held, the rule trace is `[3, null]`,
   and the balances kernel decides the transfer.
<!-- /illustration -->

The refusals in the other scenarios are the ones `BindingRecipesIT` checks on
three nodes: `TRANSFER_LIMIT_EXCEEDED` over the governed limit,
`TIER_LIMIT_EXCEEDED` over the holder's tier or without a holder record, and
`LOCKED` for a holder still inside its lock-up. The receipt names the rule,
clause and deny code. It never contains the values a rule read.

## The stock value views

| Kernel | Namespace (key) | Fields |
|---|---|---|
| `balances` | `""` (account id text, for example `{fn: hex, args: [{context: sender}]}`) | `balance`; an absent account means zero |
| `kv-registry` | `""` (key bytes) | `owner`, `value`, `valueLength`; `valueText` with `value-format: utf8` |
| `doc-trail` | `""` (entity id) | `count`, `headHash` |
| `approvals` | `""` (item id) | `status` (`PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`), `required`, `approverCount`, `proposer`, `payloadHash` |
| `authenticated-map-component` | each collection id (application key) | `status` (`ACTIVE`, `REVOKED`), `revision`, `createdHeight`, `lastMutationHeight`, `valueLength`, `controller`; `value.<member>` for the first 32 scalar top-level members of a schema-typed value |

Text and bytes over 4096 bytes are absent, though their length stays readable,
and an integer outside int64 fails closed with `ADMISSION_RULE_INPUT`. Map
members are exposed only when their key is a CEL identifier, and a revoked entry
exposes no `value.<member>`, so an unguarded member read of a revoked record
fails closed. A catalog exported with `appchain bindings catalog` lists each
instance's views as `ruleValueViews`.

## Judge every write of a batch

The authenticated map also declares a **write view**: one element per mutation
of a command, in command order. Each element has `index`, `collection`, `key`,
`keyText`, `op`, `hasValue`, `valueLength`, `expectedRevision` and the schema
members as `value.<member>`. After the map has verified the command's evidence,
each element also carries its **coverage**: `direct` with the covering actor's
`actorId`, `actorOrganizationId` and `actorRoles`, or `approval`, or `none`.

`dpp-namespace-isolation.yaml` requires every direct write to `product-versions`
or `events` to be keyed under the covering actor's organization:

```yaml
    - id: manufacturer-owns-product
      deny: FOREIGN_PRODUCT
      require:
        - expr: 'writes.all(w, (w.collection != "product-versions" && w.collection != "events") || (w.coverage == "direct" && startsWith(w.keyText, w.actorOrganizationId + "/")))'
```

`writes.all(w, …)` and `writes.exists(w, …)` are the only quantifiers. They
visit the writes in index order and stop at the first element that decides:
`all` at the first false element, `exists` at the first true one. The receipt
records where a quantifier stopped as the **deciding write**. Watch it on a
two-write batch:

<!-- illustration: quantifier-stepper -->
1. **One batch, two writes.** `logistics-a`, an operator at `swift-logistics`,
   signs one map command with two puts to `events`: `swift-logistics/p1/e3` and
   `green-labs/p1/e4`.
2. **Verify, then cover.** The map verifies the signature and approves. Only
   that approval supplies coverage, so the rule, which reads coverage, runs now.
3. **Element 0.** `swift-logistics/p1/e3` starts with `swift-logistics/`. The
   body is true, so `writes.all` moves on.
4. **Element 1.** `green-labs/p1/e4` does not. The body is false, so
   `writes.all` stops at index 1 and returns false.
5. **Refuse the batch.** The step fails with `FOREIGN_PRODUCT`, trace
   `[0, [manufacturer-owns-product, 0, FOREIGN_PRODUCT, 1]]`. Write 0 is refused
   too: a command commits whole or not at all.
<!-- /illustration -->

There is no nesting and no other comprehension. A quantifier body may use the
rule's declared `reads.*`, but no read can be keyed by an element: there are no
per-write reads. A catalog lists the write fields as `ruleWriteFields` and the
coverage fields as `ruleWriteCoverageFields`.

## Which slot a typed-view rule runs in

| A rule that reads… | Runs in | Also at ingress? |
|---|---|---|
| Only `command.*`, `params.*`, `config.*` and write content, in expression clauses | Admission slot | Yes: it is static |
| Any `reads.*`, `context.*` or lookup clause, and no facts or coverage | Admission slot | No |
| `facts.*`, or any coverage field (`w.coverage`, `w.actorId`, `w.actorOrganizationId`, `w.actorRoles`) | Verified-fact slot | No |

Coverage exists only in the verified-fact slot, so forged or unverified evidence
never reaches a rule that reads it. A refusal there keeps any crypto work the
kernel reserved to verify that evidence; an admission-slot refusal happens before
any work is reserved. [Chapter 7](07-admission-rules.md#when-rules-run) steps
through both slots with `feed-slot-rules.yaml`, which combines a read with a
write-content check in one rule and a coverage check in the other.

## Limits

| Limit | Maximum |
|---|---|
| reads per rule | 4 |
| writes in one write view | 128 |
| fields of one decoded read or write element | 64 |

A read costs `1 +` its key bytes, the stored key and value bytes, and the
decoded fields. A write view, and in the fact slot its coverage, cost their
encoded elements once per step. A quantifier costs its body once per element it
visits. All of it is charged to both the cascade and the block budget, including
for refusals, and none of it is refunded.

## Try the recipes

Each recipe ships with a governed `context.json` and one fixture per block
under `examples/bindings/fixtures/`. Run the blocks in order, passing each
result to the next with `--prior-result`:

```bash
./yano.sh appchain bindings dry-run examples/bindings/asset-governed-limits.yaml \
  --plugins-directory plugins \
  --context examples/bindings/fixtures/asset-governed-limits/context.json \
  --fixture examples/bindings/fixtures/asset-governed-limits/fixture-1.json \
  > block-1.json
./yano.sh appchain bindings dry-run examples/bindings/asset-governed-limits.yaml \
  --plugins-directory plugins \
  --context examples/bindings/fixtures/asset-governed-limits/context.json \
  --fixture examples/bindings/fixtures/asset-governed-limits/fixture-2.json \
  --prior-result block-1.json > block-2.json
```

| Recipe | Shows |
|---|---|
| `asset-governed-limits.yaml` | A governed limit and a per-holder tier and lock-up, read from a map (blocks 5 to 8 are refusals) |
| `dpp-namespace-isolation.yaml` | Coverage rules on a batch; block 3 is refused at write 1 |
| `feed-slot-rules.yaml` | A read plus a write-content rule in the admission slot, and a coverage rule in the fact slot |

These are deterministic local-demo fixtures; never use their demo keys in
production. `validate` lists each rule's slot, reads and whether it quantifies
over writes, and `dry-run` names the deciding write of every refusal.

Return to the [learning path](README.md), or read the
[reference](../DECLARATIVE_BINDINGS.md#admission-rules) for the complete grammar.
