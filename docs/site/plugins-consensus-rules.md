# Consensus rules for plugins

Plugin code that runs inside `apply()` is consensus code. Every member executes
it and must derive the same state root. This page is the contract.

## The rules

- The same bundle, machine or profile id, and committed settings run on **every
  voting member**.
- `apply()` must not use wall clock, randomness, DNS, filesystem, database, or
  network I/O.
- Invalid finalized bytes become **deterministic no-ops**, not escaping
  exceptions.
- Bound message bytes, decode depth and item counts, collections, state growth,
  and work per block.
- **Never** silently change semantics behind an existing machine or component
  id.
- Use activation heights and profile governance for compatible evolution, and a
  new namespace plus a migration plan for incompatible state.
- Every state write belongs to the authenticated writer. Do not keep hidden
  consensus state in static fields or node-local storage.

[Determinism rules](/concepts/determinism-rules/) has the full list of
non-deterministic constructs to avoid, with replacements.

## Admission versus application

Two different jobs, with different constraints:

```java
@Override
public AdmissionResult validate(AppMessage message) {
    // Bounded structural validation only. Never perform I/O here.
    return decodeSafely(message.getBody())
            ? AdmissionResult.accept()
            : AdmissionResult.reject("invalid shipment command");
}

@Override
public void apply(AppBlockExecutionContext context, AppStateWriter state, AppEffectEmitter effects) {
    // Deterministic bounded transitions only.
}
```

`validate` runs at ingress and may reject a message before it is ever ordered.
It is an efficiency and hygiene filter — it is **not** a security boundary,
because a message can reach `apply()` through catch-up from history.

`apply` runs on finalized bytes on every member. By the time it runs, the bytes
are already agreed; your job is to interpret them identically. Anything
`apply()` cannot handle must become a recorded no-op, not an exception.

## Treat finalized input as hostile

Finalized does not mean well-formed. A member could have proposed anything the
threshold was willing to sign, and history can be replayed from an arbitrary
peer.

Bound everything:

| Bound | Why |
|---|---|
| Message byte length | A single huge body must not exhaust memory during replay. |
| Decode depth and item count | Nested CBOR/JSON is a classic decompression-style attack. |
| Collection sizes you build | Per-block work must be predictable. |
| State growth per transition | Unbounded growth makes replay and snapshots impossible. |
| Work per block | The slowest member sets the finality rate. |

The framework already caps effects per block and effect payload bytes as
consensus parameters. Your state machine must supply the equivalent bounds for
its own domain.

## Evolving a live chain

This is where most of the risk sits. Replaying history under new rules produces
different state, so a semantic change is never a rolling code change.

### What counts as a semantic change

- state encoding or key layout,
- transition logic or validation outcomes,
- what a transition emits as an effect,
- a commitment profile or component order,
- a proof subject descriptor, or
- genesis-selected configuration.

### The three options

**1. Compatible evolution behind a governed profile.**
Package the reviewed current and dormant targets on **every** member first, then
threshold-authorize one exact digest and a future activation height. Members
that have not staged the target cannot activate it, and editing YAML or swapping
a JAR alone changes nothing.

**2. A new component id or namespace.**
For an incompatible change, publish a new id. The old one keeps its meaning for
historical replay, and new state lives in a new namespace with an explicit
migration plan.

**3. A new chain.**
When identity itself must change. Recall that identity is pinned by:

```text
(commitment-profile, format-fingerprint, genesis-id)
```

A retained `genesis-id` is never regenerated.

:::danger[The thing not to do]
Editing a state machine's logic and rolling out the new JAR member by member.
During the rollout, members compute different roots and the chain stops
finalizing; afterwards, replaying history produces state that does not match the
signed roots. This is unrecoverable without a new chain.
:::

### Effect emission is included

Changing what a transition emits is a hard fork unless it is gated. Ship the new
emission logic behind a governed profile activation, or behind a condition that
is itself part of committed state.

## Composites are consensus-critical Java

A composite profile's component order, versions, routes, quotas, and workflow
transitions are **code**, not configuration, precisely because two members
discovering a different order would derive different roots. That code is small,
but it deserves the same review as a state transition.

The profile is canonically encoded, committed to authenticated state at height
1, and re-verified on restart and every transition.

## Keeping secrets out of consensus

Endpoints, credentials, and API keys belong in **node-local** executor
configuration. They must never appear in replicated effect payloads or in
consensus-shared configuration — a replicated payload is visible to every member
and provable to anyone holding a proof.

The five secret classes to keep separate: member signing keys, business-actor
keys, API keys, effect credentials, and anchor wallet funds.

## Verifying before you ship

```bash
# Focused iteration.
./gradlew :state-machines:stdlib:test

# Real cluster: identical height, root, profile, genesis, capability digest.
./yano.sh appchain cluster start 3
./yano.sh appchain cluster status
./yano.sh appchain cluster node join 3      # catch-up under your rules
./yano.sh appchain cluster stop && ./yano.sh appchain cluster start 3
```

Persistence changes additionally require apply, rollback, replay, restart, and
root-parity checks. Derived indexes must never advance beyond authoritative
app-chain state.

Continue to [Testing and deployment](/plugins/testing-and-deployment/).
