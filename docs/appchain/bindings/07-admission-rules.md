# 7. Admission rules

[Previous: Studio editor](06-guided-editor.md) · [Learning path](README.md)

Declarative bindings are experimental and still undergoing qualification. The
examples here explain the current implementation, not a production
compatibility promise. A node executes committed binary IR, not the YAML text.

Bindings say what should happen next. **Admission rules** say what a component
must refuse. A rule is attached to a component and checked for every command
that reaches it: commands submitted directly and commands a binding derives, at
every depth of a cascade. Rules only forbid. They never grant authority, change
a command, or replace the component's own admission and decision. They are part
of the committed composite profile, so every member evaluates them identically.

This chapter uses the three rule recipes in `examples/bindings/`:
`balances-transfer-limit.yaml`, `procurement-admission.yaml` and
`dpp-role-gated.yaml`. The integration harness `BindingRecipesIT` runs each of
them on three real nodes and replays every finalized block offline with
byte-identical receipts.

## Declare a rule and attach it

A rule lives in the document's `rules` section and takes effect only where a
component's `admission` list attaches it:

```yaml
composite:
  components:
    - id: points
      machine: balances
      admission:
        - rule: transfer-limit
          params: {maxAmount: 10000}
  rules:
    - id: transfer-limit
      command: transfer
      deny: TRANSFER_LIMIT_EXCEEDED
      params:
        maxAmount: {type: integer}
      require:
        - expr: 'command.amount <= params.maxAmount'
  bindings: []
```

- `id` names the rule; `deny` is the code a refusal records. Deny codes are
  upper-case identifiers and cannot start with `ADMISSION_RULE_`, which the
  engine reserves.
- `command` (optional) selects one command of the attached component. A rule
  without a selector applies to every command. A selector needs a
  command-selectable kernel: every command is an opcode-prefixed array with a
  distinct opcode, or the kernel has exactly one command that is not raw bytes.
- `params` declares typed parameters (`integer`, `text`, `bytes`, `boolean`,
  `binding`), optionally with a `default`. Each attachment supplies values;
  stating a default and omitting it compile to identical bytes. A `binding`
  parameter must name a binding that targets the attached component.
- `require` is an ordered list of clauses. Each is a restricted CEL `expr` or a
  `lookup` (`exists`, `absent`, or `eq` against a source). The first clause that
  does not hold refuses the command with the rule's deny code.

A rule that no component attaches is rejected (`RULE_UNATTACHED`). A rule is
type-checked separately against every component it is attached to.

## What a rule can read

Each use site has its own scopes. Bindings read the event that triggered them;
rules read the command they judge.

| Scope | Bindings (conditions, mappings) | Rules |
|---|---|---|
| `event.*` | yes | no |
| `context.*` | yes (the producing step) | yes (the step being admitted) |
| `command.*` | no | only with a `command` selector; DATA fields only |
| `params.*` | no | yes |
| `config.*` | no | yes (the component's normalized configuration) |
| `facts.*` | no | yes, if the kernel declares facts |

`context.*` has exactly five fields: `height` (integer), `sender` (bytes, the
source message's sender), `derived` (boolean), `depth` (integer, 0 for the
source command), and `binding` (text, the binding that produced the step, empty
for the source). There is no timestamp: rules and bindings are deterministic.

Evidence fields, such as signatures, are never readable (`RULE_EVIDENCE_READ`).
Reading an unavailable scope is `RULE_SCOPE_INVALID`, with the position of the
offending identifier. The YAML source forms mirror the scopes: `{context: …}`,
`{command: …}`, `{param: …}`, `{config: …}` and `{fact: …}` next to `{field: …}`
for events. `in` tests text membership and is available only in rules; a fact
of type text set can be read only on the right-hand side of `in`.

## When rules run

The engine checks a component's rules in two slots, keeping attachment order
within each slot:

1. **Admission slot** — rules that do not read `facts.*`. They run after the
   component's own admission checks and before any work is reserved, so a
   refused command costs no crypto work.
2. **Verified-fact slot** — rules that read `facts.*`. They run only after the
   kernel approved the command, with the exact facts that produced the
   approval. A kernel rejection runs no fact rule and reveals no fact.

The first failing rule wins; later rules are neither evaluated nor charged.
Each step's receipt records `rulesEvaluated = [heldCount, failure]`, where the
failure names the rule, the clause index and the deny code. Refusal codes are:

| Code | Meaning |
|---|---|
| `ADMISSION_RULE_DENIED` | a clause did not hold; the receipt carries the deny code |
| `ADMISSION_RULE_ERROR` | a clause could not be evaluated, for example an absent fact or a division by zero; it fails closed |
| `ADMISSION_RULE_INPUT` | the command view or the kernel's fact values were unusable; clause `-1` |
| `EXPRESSION_CAPACITY_EXCEEDED` | the cascade or block expression work was exhausted |

Rule work is charged to both the cascade and the block budget, including for
refusals, and is never refunded.

### Static rules also refuse at ingress

A rule whose clauses are all expressions that read only `command.*`, `params.*`
and `config.*` is **static**. A member evaluates the static rules of a source
component when it receives a submission, so an over-limit transfer is refused
before it is pooled. The REST response shows the host's bounded code
`APPLICATION_REJECTED`; the node's DEBUG admission log and `bindings dry-run`
show the full reason, for example
`ADMISSION_RULE_DENIED/transfer-limit/TRANSFER_LIMIT_EXCEEDED`. Block-time
evaluation remains authoritative.

## A refusal rolls back its whole cascade

A binding condition that is false only skips that binding. A rule refusal
rejects the command, and a rejected step rejects its **source message**: every
earlier step of the same cascade, including the source command, is rolled back,
and no business state is written: only the receipt, framework accounting, and any
non-refundable crypto work a kernel reserved before a verified-fact rule refused
it. For example, in `procurement-admission.yaml`:

```yaml
    - id: approvals
      machine: approvals
      admission:
        - rule: minimum-quorum
          params: {minimum: 2}
```

The order binding derives `approvals.propose` with `required: 2`. If a binding
author lowered that literal to 1, every order's cascade would be refused at
depth 1 with `QUORUM_TOO_LOW`, and the order put at depth 0 would not commit
either. `bindings dry-run` shows this before deployment: the receipt's failed
step is at depth 1, and `stateChanges` contains no order.

## Pattern: accept only arrivals through a binding

Rules can see how a step arrived. This rule makes an audit trail accept entries
only from the approval binding, never from a direct submission:

```yaml
    - id: only-via-binding
      deny: DIRECT_SUBMISSION_FORBIDDEN
      params:
        binding: {type: binding}
      require:
        - expr: 'context.derived && context.binding == params.binding'
```

It reads `context`, so it is not static: a direct append is pooled and refused
at block time with a finalized, provable receipt.

## Pattern: verified roles

Some kernels establish **verified facts** about a command, and only those
kernels can feed `facts.*`:

| Kernel | Facts |
|---|---|
| `authenticated-map-component` | `senderMember` (boolean), `collections` (text set: the collections the batch writes) |
| governed `authenticated-map-component` | also `directActorCount`, `approvalCount` (integers); for exactly one direct actor, `actorId`, `organizationId`, `role`, `roles` (text set) and `policyId` |
| `governed-role-approvals` | `actorId`, `organizationId`, `roles` (text set), `action` (`PROPOSE`, `APPROVE`, `REJECT`, `CANCEL`), `policyId`, `policyRevision` |

Other stock kernels authenticate only the member sender and declare no facts; a
fact rule attached to them is rejected (`RULE_FACT_UNKNOWN`). A multi-actor map
batch establishes counts only, so a rule that reads `facts.roles` for it fails
closed with `ADMISSION_RULE_ERROR`.

`dpp-role-gated.yaml` adds two rules to the DPP approval recipe:

```yaml
  rules:
    - id: allowed-organization
      deny: ORGANIZATION_NOT_ALLOWED
      params:
        organization: {type: text}
      require:
        - expr: 'facts.action != "APPROVE" || facts.organizationId == params.organization'
    - id: operator-for-direct-writes
      deny: ROLE_REQUIRED
      params:
        role: {type: text}
      require:
        - expr: 'facts.directActorCount == 0 || params.role in facts.roles'
```

An approval from an auditor outside `cert-body-a` is verified by the approval
kernel and then refused with `ORGANIZATION_NOT_ALLOWED`. A forged approval fails
the kernel's own signature check, and no rule runs. A direct map write signed by
an actor without `operator` is verified by the map and then refused with
`ROLE_REQUIRED`. Approval-referenced writes arriving through `apply-approved`
have `directActorCount == 0`; the approval policy governs them.

To role-gate a member-authenticated machine such as `kv-registry`, put a
role-verified component in front of it and attach an `only-via-binding` rule to
the target.

## Limits

| Limit | Default | Maximum |
|---|---|---|
| rules per document | — | 64 |
| attachments per component (`maxRulesPerComponent`) | 4 | 16 |
| clauses per rule | — | 8 |
| parameters per rule | — | 16 |

Rule expressions share the document's expression limits and lookup limit.

## Local loop

```bash
./yano.sh appchain bindings validate procurement-admission.yaml \
  --plugins-directory plugins --context context.json
./yano.sh appchain bindings dry-run procurement-admission.yaml \
  --plugins-directory plugins --context context.json \
  --fixture examples/bindings/fixtures/procurement-admission/fixture-1.json
./yano.sh appchain bindings graph procurement-admission.yaml \
  --plugins-directory plugins --context context.json
```

`validate` lists each component's rules in evaluation order with their slot.
`dry-run` shows each receipt's rule trace and explains a rolled-back cascade.
`graph` draws attached rules as guards on their components.

Rules are part of the committed profile. Changing a rule, a parameter or an
attachment changes the profile digest; on a running chain it is a governed
profile change, like any binding change (see [Operations and
upgrades](05-operations-and-upgrades.md)).

Return to the [learning path](README.md), or read the
[reference](../DECLARATIVE_BINDINGS.md) for the complete rule grammar.
