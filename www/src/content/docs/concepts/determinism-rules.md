---
title: Determinism rules
description: The hard constraints on code that runs inside consensus — no wall clock, no randomness, no ambient ordering, no I/O — and the versioning discipline that applies when any of it changes.
sidebar:
  order: 6
---

Every member executes the same messages through the same state machine and must
derive the same state root **byte for byte**. If two members disagree, the chain
does not "heal" — it fails to reach the threshold and stops finalizing.

This page is the checklist for any code that runs inside `apply()`.

## Forbidden inside consensus

| Do not | Why | Instead |
|---|---|---|
| Read the wall clock (`Instant.now()`, `System.currentTimeMillis()`) | Every member reads a different value. | Use block height, or an L1 slot value carried in the block. |
| Use randomness (`Math.random()`, `new Random()`, `UUID.randomUUID()`) | Unreproducible. | Derive deterministically from message bytes, a hash, or a sequence number. |
| Iterate a `HashMap` / `HashSet` | Iteration order varies across JVMs and insertion histories. | Use `TreeMap` / `LinkedHashMap`, or sort explicitly before iterating. |
| Make a network call | Latency, failure, and content differ per member — and it may not even be reachable. | Emit an [effect](/concepts/effects/). |
| Read files, environment variables, or system properties | Node-local, therefore divergent. | Put the value in consensus-shared configuration, or in the message. |
| Depend on locale or default charset | `toLowerCase()` and `String.getBytes()` are locale/platform sensitive. | Pin `Locale.ROOT` and `StandardCharsets.UTF_8` explicitly. |
| Use floating point for value arithmetic | Fine in principle, treacherous in practice around rounding and formatting. | Use integers or `BigDecimal` with an explicit scale and rounding mode. |
| Depend on object identity or `hashCode()` | `Object.hashCode()` varies per run. | Compare and key on canonical bytes. |
| Catch an exception and continue differently on one member | Divergent control flow. | Validate deterministically and reject or no-op uniformly. |
| Spawn threads or use concurrency inside `apply()` | Scheduling order is not reproducible. | Keep `apply()` single-threaded. |
| Write keys under `~fx/` | The prefix is reserved for the effect system from genesis. | Use your own namespace. |

## Two properties, not one

Determinism has a per-run component and a cross-node component. Both matter:

- **Reproducible** — replaying the same blocks on the same member yields the
  same state. This is what replay and restart tests check.
- **Identical** — every member independently yields the same state. This is
  what root parity across a real multi-node cluster checks.

Code can be reproducible and still not identical: a `HashMap` iteration is
stable within one JVM run but differs across members. Unit tests pass; the
cluster stalls. Always validate on a real cluster.

## Rejection must be deterministic too

Invalid input is not an exception path — it is a state transition that every
member must agree on. A message that fails validation should produce the same
observable outcome on every member: usually a recorded no-op, sometimes an
error record written to state, never a thrown exception that one member handles
differently.

The stock state machines demonstrate this: an unauthorized `kv-registry` write
is a **visible no-op**, not a failure. [Tutorial 2](/tutorials/02-registry-and-proofs/)
has you trigger one deliberately.

## Consensus-shared vs node-local configuration

The catalog classifies every property by scope, and the distinction is not
cosmetic:

| Scope | Meaning | Getting it wrong |
|---|---|---|
| `CONSENSUS_SHARED` | Must be identical on every member. | The state root diverges and the chain stops finalizing. |
| Node-local | Ports, storage paths, credentials, executor placement. | Only that node is affected. |

Effects caps, the state-machine id, the composite profile digest, value
formats, and quotas are all consensus-shared. The generated
[configuration reference](/reference/configuration/) marks the scope and change
policy of each property.

## Changing semantics is a versioned upgrade

A change to deterministic application semantics — state encoding, transition
logic, emission logic, a commitment profile, a proof subject, or
genesis-selected configuration — is **not** an ordinary rolling code change.
Replaying history under new rules produces different state, so the change must
be versioned and activated deterministically.

Three values pin chain identity:

```text
(commitment-profile, format-fingerprint, genesis-id)
```

A governed composite profile is the supported mechanism: operators package the
reviewed current and dormant targets on **every** member first, then
threshold-authorize one exact digest and a future activation height. Editing
YAML or swapping a JAR never changes consensus behavior on its own.

See [Consensus rules for plugins](/plugins/consensus-rules/) for the full
upgrade discipline.

## Verifying determinism

Do not stop at unit tests when a plugin, catalog, persistence, consensus,
proof, anchor, or cross-node behavior changed:

```bash
# Unit and property tests while iterating.
./gradlew :state-machines:stdlib:test

# A real multi-node cluster: identical chain height, root, profile, genesis,
# and capability-manifest digest across members.
./yano.sh appchain cluster start 3
./yano.sh appchain cluster loadtest orders-chain -n 1000 -c 20 --spread
./yano.sh appchain cluster status     # every chain must report AGREED

# Restart and re-verify, then check catch-up on a joining member.
./yano.sh appchain cluster stop
./yano.sh appchain cluster start 3
./yano.sh appchain cluster node join 3
```

Persistence changes additionally require apply, rollback, replay, restart, and
root-parity checks.

## Debugging a divergence

When members disagree, work down this list before suspecting the framework:

1. **Configuration drift.** Compare every `CONSENSUS_SHARED` value across
   members. `./yano.sh appchain drift <project> --peer <node-url>` does this
   against running nodes.
2. **Plugin version drift.** Compare the capability-manifest digest and the
   installed bundle digests on each member.
3. **Profile digest.** For composites, confirm every member committed the same
   profile digest at height 1.
4. **Your `apply()`.** Re-read the forbidden list above. Map iteration order and
   the wall clock account for most real cases.
5. **Replay locally.** Replay the same block range on one member twice. If that
   diverges, the bug is reproducibility; if not, it is cross-node identity.
