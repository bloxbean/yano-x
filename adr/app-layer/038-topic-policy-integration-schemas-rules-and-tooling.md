# ADR app-layer/038: Topic Policies in Yano X — Schemas, Admission Rules, Tooling and Delegated Clients

**Status:** Proposed — design only, for review. No implementation is part of this ADR.
**Date:** 2026-10-22
**Scope:** How Yano X adopts the core topic registry: one schema engine, admission rules over typed
topic payloads and verified writers, composites bound to registered topics, authoring tools, client
libraries for frames and delegations, and the reference example.
**Depends on:** core [Yano ADR app-layer/039](https://github.com/bloxbean/yano/blob/main/adr/app-layer/039-topic-registry-namespaces-and-writer-policies.md)
(topic registry, namespaces and writer policies; under review in
[bloxbean/yano#197](https://github.com/bloxbean/yano/pull/197)); Yano X ADR app-layer/025.1, 031, 031.3, 031.4.
**Risk:** Medium. No new consensus rules of its own, but it changes the authenticated map's schema
dependency and the admission-rule views, both of which feed consensus decisions.
**Numbering:** local to `adr/app-layer`; the root-level ADR-038 (documentation site) is a separate
series.
**Preview reset policy:** app chains are unreleased; no compatibility adapters.

---

## 1. Context

Core ADR-039 gives every Yano app chain an opt-in topic registry in consensus state: namespaces with
owners, four creation modes, per-topic writer policies, short-lived delegations (session keys), and
optional canonical-CBOR schemas checked with the `yano-cbor-schema-ir-v1` evaluator, which it moves
from Yano X into core. Core deliberately stops at the framework boundary: it does not author
schemas, edit policies, or know about composites and admission rules.

Yano X owns those parts today:

- `AuthenticatedMapSchema` (`state-machines/stdlib-contracts`) — the IR contract and evaluator that
  core adopts.
- `AuthenticatedMapCddlCompiler` (`tooling/devtools`) — `cddl-yano-subset-v1` → IR.
- Declarative composites, where each component is already bound to a topic
  (`components[].topic`, e.g. `examples/bindings/dpp-namespace-isolation.yaml`).
- Admission rules (ADR-031.3/031.4), which can read `command.*` only for command-selectable
  kernels, `context.{height, sender, derived, depth, binding}`, `params`, `config`, `facts`,
  `reads` and the authenticated-map write view. They cannot read evidence or a timestamp, and they
  see nothing of an `ordered-log` body, which is opaque.
- The `yano.sh appchain` CLI, Studio, the stdlib machines, the docs and the examples.

![Where each part lives: Yano core and Yano X](images/038/layering.svg)

## 2. Decisions

### X1 — One schema engine: the authenticated map uses the core evaluator

- Remove the evaluator from `stdlib-contracts` and call core's `yano-cbor-schema-ir-v1` evaluator.
  The IR id, version, limits and bytes stay identical, so existing collection schemas and genesis
  files keep their meaning (V1).
- `AuthenticatedMapSchema` shrinks to the collection-specific glue (encoding checks, error codes
  `VALUE_ENCODING` / `VALUE_SCHEMA`), or disappears if nothing collection-specific remains.
- The Yano X release that does this pins a Yano host with the core evaluator (an API-level bump).

### X2 — One CDDL compiler for collections and topics

- Rename `AuthenticatedMapCddlCompiler` to a neutral `CddlSchemaCompiler` in `tooling/devtools`
  (no behaviour change) and use it for both authenticated-map collections and topic schemas.
- The compiler stays a tool: members never compile CDDL; they evaluate committed IR.

### X3 — CLI: `yano.sh appchain topics`

A new command group that prepares, signs and submits registry commands without the user handling
CBOR:

| Command | Does |
|---|---|
| `topics init <namespace>` | writes a local `topics.yaml` (owners, policies, schemas as CDDL) |
| `topics plan` | compiles schemas, resolves inheritance, reads the chain's generation id and each target's head revision, shows the exact command statements (core D5a) and their effect |
| `topics sign --key-file <f>` | adds an owner, registrar or issuer signature (keys by file only, never on the command line) |
| `topics submit --peer <url>` | submits to `~topics/registry`, or prints the governance command for members |
| `topics resolve <topic>` | the effective policy, the entries and revisions it came from, and a `topic-policy-v1` effective-policy proof (core D15) |
| `topics delegate` | issues a delegation for a session key (for testing and small deployments) |
| `topics verify <messageId>` | offline authorship proof: message proof + frame + generation id + effective-policy proof for its topic and height; refuses an entry-only proof |

Owner keys can be split across machines: `plan` produces an unsigned command file that each owner
signs until the threshold is met (k-of-n). The command names the head revision it expects and a
`notAfter`, so a plan that waits too long, or races another change, fails with
`TOPIC_COMMAND_STALE` or `TOPIC_COMMAND_EXPIRED` and is re-planned; it is never silently applied
to a newer policy.

### X4 — Admission rules see typed payloads and the verified writer

ADR-031.3 rules gain two read-only views when the core topic registry is enabled. Both come from
core's `TopicMessageView` (core D7a), which is attached to the message every hook receives, so
admission and apply see the same values. No node-local cache or tool output decides them.

**`payload.*` and its schema contract.** Topic schemas can change through owner commands, so a rule
cannot simply follow "the topic's schema":

- A component that reads `payload.*` declares `payloadSchemas: [<irHash>, …]` in its committed
  composite program. Every rule on it is type-checked against each listed schema when the program
  is compiled and validated, and is accepted only if it type-checks under all of them. The
  projection is fixed per schema: for a text-keyed map root, its first 32 top-level scalar members
  (the ADR-031.4 authenticated-map value-view rule).
- At evaluation the rule engine reads the message's effective schema hash from the view. If it is
  one of the declared hashes, the rule runs on that schema's projection. Otherwise — a different
  schema, no schema, an open or unregistered topic — every rule that reads `payload.*` refuses
  with `RULE_PAYLOAD_SCHEMA_MISMATCH`. It fails closed, it never reinterprets bytes, and it is the
  same on every member because the hash comes from the committed registry at the candidate height.
- Schema evolution is therefore explicit: list the new hash in the program (an ADR-031 program
  change), then let the owners activate the new schema, then drop the old hash. `appchain doctor`
  and Studio warn when an active or pending topic schema is not listed by a component bound to it.
  A pending schema change takes effect at its activation height, so mempool messages are
  re-admitted against the candidate height's view, as for every other admission input.

**`writer.*` — a view matrix.** A scalar "writer key" is ill-defined for threshold proofs and for
derived steps, so the view is:

| Arrival | `writer.kind` | `writer.signers` | `writer.issuer` | `payload.*` |
|---|---|---|---|---|
| direct, protected topic, k-of-n writers | `direct` | the canonical signer set (sorted bytewise, deduplicated) | absent | the verified payload, by the schema contract |
| direct, protected topic, delegated | `delegated` | `{session key}` | the issuer | as above |
| direct, open or unregistered topic | `none` | empty | absent | by the schema contract (refuses if no declared schema matches) |
| derived step in a composite | `none` | empty | absent | unavailable — rules read `command.*`, as today |

- Rules test membership (`writer.signers contains <key>`, `size(writer.signers) >= 2`); there is no
  singular `writer.key`, so no outcome depends on proof order.
- A derived step carries `source.writer.*` and `source.topic` as **provenance only**: they describe
  the message that started the cascade, never authority to write the target component's topic.
- **Crossing into a protected topic is checked per step, at the candidate height.** Program
  validity stays structural and never reads the registry: `crossesTopicPolicy: true` is an optional
  per-binding permission that validation accepts whatever the current policies are, so a running
  and a restarted node always agree on whether a committed program is valid. Each derived step
  then looks up its target topic's effective policy in the committed registry at the candidate
  height (core D7a/D15), the same input on every member, at admission, in apply and on replay:

  | Target's effective policy at the candidate height | Binding without the flag | Binding with the flag |
  |---|---|---|
  | open writers, or unregistered under `unregistered-topics: open` | step runs | step runs |
  | protected writers | refused: `RULE_TOPIC_POLICY_CROSSING` | step runs; its view still has `writer.kind = none` |
  | retired entry | refused: `TOPIC_RETIRED` | refused: `TOPIC_RETIRED` |
  | unregistered under `unregistered-topics: reject` | refused: `TOPIC_UNREGISTERED` | refused: `TOPIC_UNREGISTERED` |

  A refused step fails the cascade exactly like any other derived-step rule refusal (the ADR-031.3
  direct/derived no-bypass contract). The flag is only a permission to cross a writer policy; it
  never stands in for the lookup, so retirement and `reject` still apply. When an owner protects a
  topic at height h, a previously accepted program without the flag keeps working until h and its
  crossing steps are refused from h on, on every member and on replay. `appchain doctor` and
  Studio warn before activation when a pending protection would start refusing a binding.
- Reading an absent field is a deterministic refusal (`RULE_FIELD_UNAVAILABLE`), never a default.
- Raw signatures remain unreadable (`RULE_EVIDENCE_READ` stands).

Rules that read only `payload` and `params` are static and refuse at ingress, like today's static
rules. Order is fixed: the core topic policy runs first, then the machine's own admission, then the
component's rules. Policies and rules both only forbid (core I10).

Example (illustrative):

```yaml
components:
  - id: rides
    machine: ordered-log
    topic: games/neon-rush/rides
    payloadSchemas: [<ride-v1 irHash>]
    admission:
      - rule: ride-bounds
rules:
  - id: ride-bounds
    deny: RIDE_OUT_OF_BOUNDS
    require:
      - expr: 'payload.frames >= 1200 && payload.frames <= 9000'
      - expr: 'writer.kind == "delegated"'   # only session keys issued by the game service
```

### X5 — Composites bind to registered topics

- A composite component's `topic:` must be covered by a registry entry when the chain has
  `unregistered-topics: reject`, and `appchain doctor` warns otherwise.
- Composite validation checks that a component's command format is compatible with its topic's
  schema: a schema on a typed-kernel topic (for example `balances`) is refused, because those
  kernels define their own command codec; schemas are for opaque-body machines and plugins.
- **Framed commands decode as commands.** On a protected topic the message body is the core frame
  `[version, payload, proof]`, but core D7a hands every machine hook a payload view whose
  `getBody()` is the verified payload, with the original `messageId`, topic and sender. Composites
  therefore keep decoding `source.getBody()` unchanged: `EventBindingWorkflow` admission
  (`validateKernel`), the queued step in apply and `commandHash` all see the command
  (`[op, account, amount]` for `balances`), never the frame. Nothing in Yano X re-parses the frame.
- The command hash a composite records is over the payload, the command actually executed; the
  original framed message, its `messageId` and its proof remain the authorship evidence (core I15).
- Framing changes the envelope's `messageId` (it covers the body), so identity-dependent state —
  the finalized-message index `ordered-log` maintains, cascade receipts, derived ids, deduplication
  — legitimately differs between a framed and an unframed submission of the same command. Nothing
  synthesizes an unframed id to hide that.
- Tests (MX3): the same composite on open and on protected topics yields **equivalent decoded
  commands and identity-independent effects** (for example `balances` amounts and the effects it
  emits); separately, for each original message, the finalized-message index, receipts and message
  proofs correspond to that message's own id and framed bytes; a framed command is accepted at
  admission and applied.

### X6 — Studio and the consoles

- A **Topics** view: the namespace tree, each entry's owners, writers, delegators, schema and
  revision history. The entry inspector requests a `topic-entry-v1` proof for an entry at a height;
  the topic resolver requests a `topic-policy-v1` effective-policy proof for a topic at a height
  (core D15).
- A policy editor that produces the same `topics.yaml` and plan as the CLI. Studio never holds or
  requests private keys; it exports unsigned commands for the CLI or an external signer.

### X7 — Client libraries for frames and delegations

- **JavaScript** (browser-first, WebCrypto Ed25519, no dependencies), published with the examples:
  build a frame; sign a write statement; create a session key; request, store and refresh a
  delegation; parse refusal codes.
- **Java**: the same in `:sdk:client`, for services that issue delegations.
- Both ship with the core test vectors (core V2) and pass them in CI.

### X8 — Reference: a delegation service and the game center

Static sites cannot hold an issuer key, so the reference adds a tiny **delegation service** (one
endpoint, any serverless runtime) and uses it in the game center example:

1. The game publisher owns `games/` (claimed in `open` mode on the local cluster; created by
   governance or a registrar on the showcase).
2. It creates `games/neon-rush/` with `writers: {threshold 1, keys: [issuer]}` and
   `delegate: [issuer]`, a ride schema, and `maxDelegationMs: 3600000`.
3. A player signs in with their commander profile (unchanged); the page creates a session key and
   asks the delegation service for a one-hour delegation scoped to `games/neon-rush/`. The service
   checks the commander's profile signature before signing.
4. Rides are framed and signed with the session key and submitted to any member. Forged,
   malformed and replayed rides are refused at the door and never stored.

![Neon Rush with delegated session keys](images/038/neon-rush-delegation.svg)

What it still cannot do — and the docs say so: verify the ride time (browsers still replay the
inputs), or tell a bot from a human.

### X9 — Docs and inventory

- A task guide "Protect a topic and give users session keys", a concept page on namespaces and
  delegations, and updates to the admission-rules chapter (payload and writer views).
- `config/artifacts-v1.json` changes only if X7's JavaScript library becomes a published artifact
  (OQ-X3).

## 3. Invariants

| ID | Invariant | Decisions |
|---|---|---|
| IX1 | Existing authenticated-map schemas accept and reject exactly the same values before and after X1. | X1 |
| IX2 | The CLI and Studio produce byte-identical registry commands from the same `topics.yaml`. | X3, X6 |
| IX3 | `payload.*` and `writer.*` are read-only, derived from core's verified view at admission and apply alike, and never expose signatures. | X4 |
| IX4 | No Yano X tool or UI ever transmits a private key; keys are read from files or external signers only. | X3, X6, X7 |
| IX5 | Client libraries reproduce every core test vector byte for byte. | X7 |
| IX6 | A composite decodes the same commands and produces the same identity-independent effects whether they arrive framed or unframed; identity-dependent state and proofs correspond to each original message. | X5 |
| IX7 | A rule reading `payload.*` evaluates only under a schema hash its committed program declares, and refuses otherwise; no schema change reinterprets a committed rule. | X4 |
| IX8 | Writer views are independent of proof order, and a derived step never presents a source writer as authority for its target topic. Composite program validity never depends on registry state; crossing into a protected, retired or rejected-unregistered target is decided per step from the registry at the candidate height. | X4 |

## 4. Alternatives considered

| Alternative | Why not |
|---|---|
| Keep a second evaluator in Yano X | Two implementations of a consensus-relevant predicate drift apart; core already needs one. |
| A Yano X `schema-log` machine instead of core topic schemas | Covers logs only; superseded by core ADR-039. |
| Let Studio sign registry commands with a stored key | ADR-053 keeps signing in a signing gateway or a key the user holds in their own browser session; a stored owner key in Studio would be a new, long-lived secret in a product. |
| Put the delegation service inside the node | Members must not hold application issuer keys; delegations are an application concern. |

## 5. Milestones

| ID | Scope | Entry gate | Exit |
|---|---|---|---|
| MX1 | X1, X2: evaluator switch and compiler rename | core M4 released and pinned | IX1 via the existing authenticated-map schema vectors |
| MX2 | X7 client libraries | core M1 vectors frozen | IX5 |
| MX3 | X3 CLI and X5 composite checks and framed-command tests | core M3 and M5 released and pinned | IX2, IX6; doctor checks; a 3-node cluster scenario |
| MX4 | X4 admission-rule views, schema contract and writer matrix | core M5 released and pinned | IX3, IX7, IX8; rule vectors replayed offline |
| MX5 | X6 Studio, X8 reference, X9 docs | MX3, MX4 | the game-center scenario passes end to end; docs published |

## 6. Verification

- **V1:** the full authenticated-map schema test corpus passes unchanged against the core evaluator.
- **V2:** CLI, Studio and both client libraries reproduce core's frame, delegation and command
  vectors.
- **V3:** rule-view tests: payload projections, writer fields, static-rule ingress refusals, and
  order of evaluation (policy → machine → rules); framed typed-kernel commands decode identically at
  admission and in apply, with index and proof correspondence per original message (IX6);
  **schema contract**: a schema change activating at height h (before, at and after the boundary),
  an inherited schema changed on the parent entry, a mempool message re-admitted across the
  boundary, a node restarted mid-transition, and offline replay, each yielding the same accept or
  `RULE_PAYLOAD_SCHEMA_MISMATCH` result (IX7); **writer matrix**: threshold proofs with the same
  signers in different orders, a delegated source deriving into a protected target with and without
  `crossesTopicPolicy`, derived-step reads of `writer.*` and `payload.*`, and an open topic with no
  writer (IX8); **crossing across activation**: a program accepted while its target is open, then
  the target protected at height h (steps before h run, from h refused without the flag), the same
  sequence after a node restart and in offline replay, a retired target and an unregistered target
  under `reject` with and without the flag.
- **V4:** the reference game-center scenario on a 3-node cluster: delegation issue, ride accepted,
  forged / replayed / expired / out-of-scope rides refused, issuer rotation, recovery drill.

## 7. Open questions

| ID | Question | Lean |
|---|---|---|
| OQ-X1 | Should `payload.*` project nested fields or arrays? | No in v1; match the authenticated-map value view. |
| OQ-X2 | Where does the reference delegation service live? | In `yano-x-examples`, not in the distribution. |
| OQ-X3 | Publish the JavaScript client as an npm artifact now? | Not until core formats are frozen (core M1). |
| OQ-X4 | Should core instead refuse a schema change that a bound composite does not declare? | No: core does not know composites; Yano X fails closed (IX7) and warns before activation. |

## Revision history

- r1 (2026-10-22): initial proposal, companion to core ADR app-layer/039 r1.
- r2 (2026-10-22; responds to the automated review of `4ac4a9d8`): F1 (framed bodies reach
  typed-kernel codecs) → resolved in core ADR-039 r2 D7a (payload view for machine hooks); X5 states
  the effect on composites and `commandHash` and adds tests; new IX6; MX3 gate and V3 updated.
- r3 (2026-10-22; responds to the review of `0e5157454` with core bloxbean/yano#197 at
  `d9e209480`): F2 → X4 schema contract (`payloadSchemas`, fail-closed
  `RULE_PAYLOAD_SCHEMA_MISMATCH`, explicit evolution, candidate-height view at admission via core
  D7a); new IX7, OQ-X4, MX4, V3. F3 → X4 writer view matrix (canonical signer set, no singular
  key, derived steps carry provenance only, `crossesTopicPolicy` declaration); new IX8, V3. F4 →
  IX6 narrowed to decoded commands and identity-independent effects, with per-message index and
  proof correspondence; X5 and V3 updated. X3 updated for core r3 (generation-bound commands with
  head revisions, effective-policy proofs).
- r4 (2026-10-22; responds to the review of `db37e961f`): F3 completion → `crossesTopicPolicy` is a
  structural permission; program validity never reads the registry; each derived step checks its
  target's effective policy at the candidate height (protected without the flag, retired, and
  rejected-unregistered targets refuse); IX8 and V3 updated. F5 → X6 uses `topic-entry-v1` for the
  entry inspector and `topic-policy-v1` for resolved topics.
