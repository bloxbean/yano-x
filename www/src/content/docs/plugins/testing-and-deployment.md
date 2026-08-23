---
title: Testing and deployment
description: The seven-rung testing ladder for a Yano X plugin, the effect failure modes worth testing explicitly, and the operational rules for running plugins in production.
sidebar:
  order: 5
---

A plugin that passes unit tests can still stall a cluster. The failure modes
that matter — cross-node divergence, replay mismatch, executor double-delivery —
only appear when you test for them deliberately.

## The testing ladder

Climb it in order. Each rung catches a class of bug the one below cannot.

1. **Unit-test codecs and deterministic transitions.**
   Round-trip every encoding. Assert exact bytes, not just equality of decoded
   objects.

2. **Run the state-machine conformance and replay matrix.**
   Replay the same blocks twice on one member and assert an identical root. This
   catches reproducibility bugs.

3. **Test malformed and hostile finalized input through `apply()`.**
   Truncated bodies, wrong types, deeply nested structures, oversized
   collections, duplicate ids, and values at every declared bound. Each must
   produce a deterministic no-op, never an escaping exception.

4. **Start an embedded multi-member cluster with `appchain-testkit`.**
   `@AppChainCluster` gives you a real multi-member consensus round inside a
   JUnit 5 test. This is the first rung that can catch cross-node divergence —
   a `HashMap` iteration bug passes rungs 1–3 and fails here.

5. **Verify root parity, proof keys, restart, catch-up, rollback/reapply, and
   plugin packaging.**
   Every member must expose the same root at the same height, and a member that
   joins late must reach it by verified catch-up.

6. **For effects, test the failure modes explicitly:**
   crash-before-send, send-before-ack, retry, reconciliation, parking, requeue,
   and duplicate idempotency. External execution is at-least-once, so a receiver
   that is not idempotent will be found here or in production.

7. **Run a packaged JVM cluster.**
   The real distribution, the real `plugins/` directory, the real catalog
   validation. Packaging bugs — a missing transitive dependency, an embedded
   host SPI class — only surface here.

## Test dependencies

| Artifact | Purpose |
|---|---|
| `yano-appchain-core-testkit` | JUnit 5 `@AppChainCluster` embedded clusters. |
| `yano-x-effects-testkit` | Effect executor and result-path testing. |
| `yano-x-client` | REST, SSE, and client-side proof verification from tests. |
| `yano-x-eutxo-testkit`, `yano-x-eutxo-zk-testkit` | For the eUTxO and ZK ledgers. |

## Gates in the Yano X build

If you are contributing a plugin to Yano X itself rather than shipping your own,
choose gates in proportion to the change:

```bash
./gradlew :state-machines:stdlib:test          # focused unit tests
./gradlew verifyArtifactInventory              # module/publication/manifest/bundle changes
./gradlew verifyJvmOnlyBuild                   # build topology changes
./gradlew integrationTest                      # integration domains
./gradlew cryptoTest                           # crypto domains
./gradlew clean build                          # dependency, bundle, isolation, packaging
```

`verifyArtifactInventory` checks that every module has exactly one declared
artifact identity and that runtime plugins have bundle publications.
`verifyJvmOnlyBuild` rejects accidental native-image tasks.

See [Developing Yano X](/contributing/) for the full contributor workflow.

## Deployment rules

- **Give every bundle a stable plugin id and a semantic version.** The id is a
  consensus-visible identity; do not reuse it for different semantics.
- **Verify catalog state and health on every member before admitting traffic.**
  A member whose catalog failed to validate should not receive submissions.
- **Treat plugin removal or drift as a deployment error**, not as something to
  fall back from automatically. Silent fallback would change semantics.
- **Namespace configuration and metrics by plugin and contribution**, so an
  operator can attribute a problem to the right bundle.
- **Keep plugin domain APIs read-only** unless commands still enter through the
  authenticated app-chain submission path.

## Installing on a cluster

```bash
# On every member's distribution:
cp shipment-yano-plugin.jar /opt/yano-x/plugins/
tools/yano-plugins/bin/yano-plugins validate /opt/yano-x/plugins/*.jar

# Before admitting traffic:
./yano.sh appchain doctor shipment-chain --distribution /opt/yano-x
./yano.sh appchain drift  shipment-chain --peer http://node-a:8080
```

The property is `yano.plugins.directory`. Restart the nodes; Yano is not
rebuilt.

`drift` is the fastest way to find the one member that is out of step, and it
is worth running as a routine check rather than only during an incident.

## Operating a plugin

| Surface | What it gives you |
|---|---|
| `/status` and `/ui/app-chain/` | Tip, root, agreement, anchor lag, plugin inventory. |
| Health checks | Per-plugin health contributed through the SPI. |
| Prometheus metrics | Namespaced by plugin and contribution; effect metrics can be scoped by type. |
| Effect operations | Inspect, retry, park, requeue, and reconcile stuck effects. |
| Admin API | Key rotation, snapshots, onboarding. |

Distinguish the four states an effect can be in when diagnosing: **intent**
recorded, **execution** attempted, **incorporation** completed, and
**anchoring** of the height that carries it. Operators who conflate them chase
the wrong problem.

## Before a pilot

Beyond the ladder above, plan for:

- application-specific soak and load testing on a public test network;
- key and secret operations, including rotation runbooks for all five secret
  classes;
- monitoring and SLOs, incident recovery, and restore rehearsal;
- retention, pruning, and `oldestProvableHeight` policy; and
- governance for any future semantic change.

[Tutorial 9 — from demo to pilot](/tutorials/09-from-demo-to-pilot/) turns this
into a concrete deployment plan.
