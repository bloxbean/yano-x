---
title: Determinism rules
description: The constraints on code that runs inside consensus — time from the block, no randomness, no unordered iteration, no locale or environment reads, no I/O — what happens when members disagree, and how to test for it.
sidebar:
  order: 6
---

Every member applies the same messages with the same state machine and must
derive the same state root, byte for byte. A member votes only for a root it
computed itself, so code that gives different results on different members
does not corrupt the ledger. It stops members from agreeing.

**You'll learn:** what code inside `apply()` must not do, what happens when
members disagree, and how to test determinism before it reaches a cluster.

**Before you start:** read [Consensus and finality](/concepts/consensus-and-finality/).

## Forbidden inside consensus

This is the checklist for any code that runs inside `apply()`.

| Do not | Why | Instead |
|---|---|---|
| Read the clock (`Instant.now()`, `System.currentTimeMillis()`) | Every member reads a different value. | Use `context.block().timestamp()`, the proposer's clock in milliseconds, recorded in the block. Or use the block height. |
| Use randomness (`Math.random()`, `new Random()`, `UUID.randomUUID()`) | Not reproducible. | Derive values from message bytes, a hash, or a sequence number. |
| Iterate an unordered collection (`HashMap`, `HashSet`, `Map.of`, `Set.of`) | Iteration order is unspecified. It can change between JVM runs and Java versions, and with keys whose hash code is per-object. | Use `TreeMap` or `TreeSet`, a `LinkedHashMap` filled in a deterministic order, or sort before iterating. |
| Depend on the default locale | `toLowerCase()`, `toUpperCase()`, and `String.format()` follow the member's locale. In Turkish, `"TITLE".toLowerCase()` is `"tıtle"`. | Pass `Locale.ROOT`, and encode text with `StandardCharsets.UTF_8`. |
| Read environment variables, system properties, or files | They are node-local, so members differ. | Put the value in consensus-shared configuration or in the message. |
| Make a network call | Latency, failures, and responses differ per member. | Emit an [effect](/concepts/effects/). |
| Use `double` or `float` for amounts | Rounding makes results depend on the form and order of operations, and `Math` functions may differ between platforms. | Use integers, or `BigDecimal` with an explicit scale and rounding mode. |
| Depend on object identity or `Object.hashCode()` | Identity hash codes differ per run. | Compare and key on canonical bytes. |
| Catch an exception and continue differently on one member | Control flow diverges. | Validate deterministically and record a no-op the same way on every member. |
| Start threads or use concurrency inside `apply()` | Scheduling order is not reproducible. | Keep `apply()` single-threaded. |
| Write keys under `~yano/` or `~fx/` | These prefixes are reserved for the framework and the effect system. | Use your own key namespace. |

### Time inside a block

`context.block().timestamp()` is the same on every member because it is part of
the block. It is the proposer's clock reading: followers do not compare it with
their own clocks, so it is only as accurate as the proposer's clock.

The block also carries a Cardano reference, `l1Slot` and `l1BlockHash`. It is 0
and empty unless the chain sets `l1.stability-depth` above 0. Then every
follower checks it against its own view of Cardano, and the slot never
decreases from one block to the next.

## Two properties, not one

Determinism has a per-run part and a cross-member part, and you need both:

- **Reproducible:** replaying the same blocks on the same member gives the same
  state. Replay and restart tests check this.
- **Identical:** every member independently gives the same state. Root parity
  across a real multi-member cluster checks this.

Code can be reproducible and still not identical. Suppose a state machine
stores `name.toLowerCase()` and one member runs with a Turkish default locale.
That member gives the same answer on every run, so its own replay tests pass,
but its root differs from the others'. Always validate on a real cluster.

## When members disagree

What happens depends on who computes the different root:

| Who differs | What happens |
|---|---|
| One follower, while a threshold still agrees | It does not vote. The others finalize, and that member stalls alone: it cannot apply the certified block, and after a minute without progress its status reports `stalled`. |
| The leader | Followers refuse its block, the round times out, and the next leader proposes after a view change. The leader then stalls. With a fixed sequencer it leads view 0 at every height, so every block waits for one timeout. |
| Enough members that no threshold agrees | No block becomes final. The ledger stops rather than accept a disputed state. |

<!-- illustration: divergence-sim -->

Two members that run the same wrong code agree with each other. Consensus
checks agreement, not correctness, so test before you deploy.

## Rejection must be deterministic too

Invalid input is not an exception path. It is a state transition that every
member must agree on. A message that fails validation must have the same
observable outcome on every member: usually a recorded no-op, sometimes an
error record written to state, never an exception that one member handles
differently.

The stock state machines work this way. An unauthorized `kv-registry` write is
a visible no-op, not a failure. [Tutorial 2](/tutorials/02-registry-and-proofs/)
has you trigger one deliberately.

## Consensus-shared and node-local configuration

The configuration catalog gives every property a scope, and the difference
matters:

| Scope | Meaning | Getting it wrong |
|---|---|---|
| `CONSENSUS_SHARED` | Must be identical on every member, for example the chain id, the state machine, the threshold, and the block limits. | Members compute different roots or reject each other's blocks, and finality stops or a member stalls. |
| `NODE_LOCAL` | For example the storage path, peers, and transport mode. | Only that node is affected. |

The generated [configuration reference](/reference/configuration/) shows the
scope and change policy of each property.

## Changing semantics is a versioned upgrade

A change to deterministic application semantics, such as state encoding,
transition logic, emitted effects, a commitment profile, a proof subject, or
genesis-selected configuration, is not an ordinary rolling code change.
Replaying history under new rules produces different state, so the change must
be versioned and activated deterministically.

Three values pin the state identity of a chain:

```text
(commitment-profile, format-fingerprint, genesis-id)
```

For composites, a governed composite profile is the supported mechanism:
operators install the reviewed current and future profiles on **every** member
first, then authorize one exact profile digest and a future activation height.
Editing YAML or swapping a JAR never changes consensus behavior on its own.

See [Consensus rules for plugins](/plugins/consensus-rules/) for the full
upgrade discipline.

## Testing determinism

Test reproducibility before a cluster, then identity on one:

1. **Conformance test.** Yano's `StateMachineConformance` (in `yano-runtime`)
   applies one seeded block corpus through the real commit path in several
   independent runs, plus a kill-and-reopen replay, and requires identical
   roots and effect lists at every height. It catches clock reads, randomness,
   and ordering that changes from run to run. It cannot catch differences
   between members, such as locale or environment.

   ```java
   StateMachineConformance.builder(new MyProvider())
           .blocks(50).messagesPerBlock(5).seed(42)
           .bodyGenerator((height, index, random) -> myRealisticCommand(random))
           .assertDeterministic();
   ```

2. **A real cluster.** Run traffic across members, then compare roots:

   ```bash
   ./yano.sh appchain cluster start 3
   ./yano.sh appchain cluster loadtest orders-chain -n 1000 -c 20 --spread
   ./yano.sh appchain cluster status     # every chain must report AGREED
   ```

3. **Restart and compare again.** Stop and start the cluster, let it catch up,
   and run `cluster status` once more.

Persistence changes also need apply, rollback, replay, restart, and root-parity
checks.

## Debugging a divergence

When members disagree, work down this list before suspecting the framework:

1. **Configuration and plugin drift.**
   `./yano.sh appchain drift <project> --peer <node-url>` compares your project's
   locked identity, including resolved configuration, consensus profile,
   composite profile, and plugin catalog, with each running node.
2. **The member's environment.** Compare locale, time zone, Java version, and
   environment variables, if your code could read any of them.
3. **Your `apply()`.** Re-read the forbidden list above.
4. **Replay locally.** Run `StateMachineConformance` with a corpus close to the
   failing traffic. If it fails, the bug is reproducibility; if it passes, look
   for a cross-member difference.

**Next:** [Plugin framework](/plugins/).
