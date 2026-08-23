<!-- yanoXVersion: 0.1.0-SNAPSHOT; yanoVersion: 0.1.0-pre13 -->

> **Read this entire document before generating any Yano X code or
> configuration.** It distills the architecture boundary, the extension ladder,
> the determinism rules, the plugin lifecycle, and the invariants that AI agents
> most reliably get wrong. Following it avoids the expensive failure mode: a
> change that compiles, passes unit tests, and stops a cluster from finalizing.

This pack is optimized for AI ingestion, not for human onboarding. Humans should
start at [What is an app chain?](https://yanox.dev/start-here/what-is-an-app-chain/).

---

## 1. What Yano X is

Yano X is the **Java 25, JVM-only extension ecosystem** for Yano app chains.

An **app chain** is an application-specific replicated ledger run by a group of
organizations: members agree on ordered commands, execute the same deterministic
state machine, independently derive the same authenticated state root, prove
records against it, and optionally settle that root on Cardano.

Two repositories, one strict direction:

```text
yano-x  ──depends on──▶  yano
```

| Owned by **Yano** (the host) | Owned by **Yano X** |
|---|---|
| Cardano data node, chain sync, chainstate | Every state machine except `ordered-log` |
| App-block sequencing, membership, threshold finality | Composition framework and governed profiles |
| Authenticated state, MPF proofs, catch-up, replay | Kafka / S3 / IPFS / Cardano-payment connectors |
| Cardano anchoring | Evidence, Cardano History, eUTxO and ZK products |
| Effect runtime | Java client SDK, Spring starter, testkits, Studio |
| Public plugin SPI, catalog, lifecycle, isolation | The batteries-included JVM distribution |
| `ordered-log` — the only built-in state machine | |

### Version and identity facts

<!-- catalog:versions-start -->

| Value | Current |
|---|---|
| Yano X version | `0.1.0-SNAPSHOT` |
| Yano host version | `0.1.0-pre13` |
| Maven group | `com.bloxbean.cardano` |
| Java | `25` |
| Base Yano JVM ZIP | [`yano-0.1.0-pre13.zip`](https://github.com/bloxbean/yano/releases/download/v0.1.0-pre13/yano-0.1.0-pre13.zip) |

<!-- catalog:versions-end -->

---

## 2. Hard invariants — violating these is always wrong

1. **Never** add a source-checkout dependency, composite Gradle build, sibling
   task invocation, or generated-file dependency from Yano X to Yano. Consume an
   exact published Yano version and its matching ordinary JVM ZIP.
2. **Java packages stay `com.bloxbean.cardano.yano.appchain.*`** while
   repository and artifact names are `yano-x`. This is deliberate. Do not
   "fix" it.
3. **The plugin directory property is `yano.plugins.directory`.** Never
   `yaci.plugins.directory` — that spelling was removed and must not return.
4. **Yano X is JVM-only.** Never add GraalVM/native-image tasks, reachability
   metadata, or native executables. `verifyJvmOnlyBuild` enforces it.
5. **Every optional runtime behavior crosses the plugin catalog boundary.**
   Activation is `PluginProviderRegistry` plus a schema-v1 plugin manifest.
   Never raw `ServiceLoader` discovery by the host, direct host construction,
   product switches, or product-specific host CDI/REST activation.
6. **Runtime plugins** publish dependency-complete bundles, do not embed host
   SPI classes, declare compatible Yano API major and min/max levels, and have
   bounded lifecycle cleanup.
7. **Do not duplicate the artifact inventory.** Update
   `config/artifacts-v1.json` and its verification tests when module identity
   changes.
8. **The app-chain feature has not had a public release.** Remove obsolete
   adapters, aliases, and duplicate activation paths rather than preserving
   accidental compatibility. Preserve documented Cardano node, OrderedLog,
   wire/storage, proof, replay, and distribution invariants.
9. **Never stage or commit automatically.** Inspect `git status --short` before
   editing and preserve unrelated changes.

---

## 3. The extension ladder — always choose the smallest rung

```text
Does a stock machine or profile already model the outcome?
  ├─ yes → rung 0: configuration only
  └─ no
      Are all required components already available?
        ├─ yes → rung 1: a small composite plugin
        └─ no  → rung 2: a custom state-machine plugin
```

| Rung | What it is | Cost |
|---|---|---|
| 0 | Select a built-in id: `yano.app-chain.state-machine: kv-registry` | No JAR, no build |
| 1 | A composite plugin declaring component ids, versions, deterministic order, routed topics, quotas, workflows, and one committed profile digest | Small but consensus-critical Java |
| 2 | A custom `AppStateMachine` with new state and rules | Full consensus responsibility |
| 3 | Effect executors, sinks, domain APIs, signers, sequencer modes, L1 observers | Outside consensus; never affects the state root |

**Default assumption: the answer is rung 0 or 1.** Do not propose a custom state
machine before checking `./yano.sh appchain recipes` and
`./yano.sh appchain capabilities`.

YAML cannot dynamically insert arbitrary component plugins into a frozen
profile, because two members discovering a different order would derive
different roots. Composite order is code, reviewed and signed.

### Stock state machines (rung 0)

`ordered-log` (host), `kv-registry`, `authenticated-map`, `approvals`,
`balances`, `doc-trail`, `role-approvals`, plus the `evidence-v1-gated` and
`role-evidence` profiles.

Live recipe and capability lists:
[`/ai/catalog.json`](https://yanox.dev/ai/catalog.json), [`/recipes/`](https://yanox.dev/recipes/),
[`/reference/capabilities/`](https://yanox.dev/reference/capabilities/).

---

## 4. Determinism — the rules for anything inside `apply()`

Every member executes the same messages and must derive the same state root
**byte for byte**. Divergence does not heal; the chain stops finalizing.

| Never | Because | Instead |
|---|---|---|
| `Instant.now()`, `System.currentTimeMillis()` | Different per member | Block height, or an L1 slot carried in the block |
| `Math.random()`, `new Random()`, `UUID.randomUUID()` | Unreproducible | Derive from message bytes, a hash, or a sequence number |
| Iterating `HashMap` / `HashSet` | Order varies across JVMs and histories | `TreeMap` / `LinkedHashMap`, or sort explicitly |
| Any network call | Latency and content differ per member | Emit an effect |
| Files, env vars, system properties | Node-local, therefore divergent | Consensus-shared config, or put it in the message |
| Locale/charset defaults | `toLowerCase()`, `getBytes()` are platform sensitive | `Locale.ROOT`, `StandardCharsets.UTF_8` |
| Floating point for values | Rounding and formatting traps | Integers, or `BigDecimal` with explicit scale and rounding |
| `Object.hashCode()` / identity | Varies per run | Compare and key on canonical bytes |
| Divergent exception handling | Different control flow per member | Validate deterministically; no-op or reject uniformly |
| Threads or concurrency in `apply()` | Scheduling is not reproducible | Keep `apply()` single-threaded |
| Writing keys under `~fx/` | Reserved for the effect system from genesis | Use your own namespace |
| Hidden state in static fields or node-local storage | Not part of the authenticated root | Every write goes through the authenticated writer |

**Reproducible ≠ identical.** A `HashMap` iteration is stable within one JVM run
and differs across members: unit tests pass, the cluster stalls. Always validate
on a real multi-node cluster.

**Invalid finalized bytes must become deterministic no-ops**, never escaping
exceptions. `validate()` at ingress is a hygiene filter, not a security
boundary — a message can still reach `apply()` through catch-up.

**Bound everything**: message byte length, decode depth and item count,
collection sizes, state growth per transition, and work per block.

---

## 5. Effects — how to touch the outside world

> A state machine never performs the action. It emits a record describing it.

```java
@Override
public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
    for (AppMessage m : block.messages()) {
        Order o = decode(m.getBody());
        writer.put(key(o.id()), o.toBytes());
        if (o.isApproved()) {
            effects.emit(EffectIntent.of("webhook.post", o.fulfilmentJson())
                    .scope("orders/" + o.id())        // application idempotency scope
                    .result(ResultPolicy.CHAIN)       // outcome returns on-chain
                    .gate(FinalityGate.CHAIN_DEFAULT)
                    .expiryBlocks(1000)               // deterministic timeout; mandatory
                    .sourceMessageId(m.getMessageId())
                    .build());
        }
    }
}

@Override
public void onEffectResult(AppBlock block, EffectResult result, AppStateWriter writer) {
    // Deterministic incorporation of CONFIRMED / FAILED / CANCELLED / EXPIRED.
}
```

Key facts:

- Guarantee is **exactly-once incorporation, at-least-once execution**. Every
  executor and receiver must be idempotent.
- Effects are **off by default**, and `yano.app-chain.effects.*` caps are
  **consensus parameters** — identical on every member or the root diverges.
- Gates: `app-final` (block committed), `l1-anchored` (covered by a confirmed,
  stability-deep anchor), `zk-settled` (reserved).
- **Expiry is mandatory** for `CHAIN` effects. `EXPIRED` ("nobody answered") is
  distinct from `FAILED` ("the target answered no").
- A result is a **member attestation**, not a verified fact. Narrow it with
  `yano.app-chain.effects.result.signers`. For L1-visible facts prefer an L1
  observer.
- Duplicate, late, malformed, unknown, or out-of-window results are
  deterministic no-ops. A result can never stall the chain.
- Endpoints and credentials go in **node-local** executor configuration, never
  in replicated effect payloads.

Bundled: `webhook.post` executor and finalized webhook sink. First-party
optional plugins: Kafka, S3-compatible `object.put`, `ipfs.pin`,
`cardano.payment`.

---

## 6. The plugin lifecycle — the exact commands

```bash
# 1. Scaffold. Modes: state-machine | composite-role | effect-executor | sink
./yano.sh appchain plugin scaffold --mode state-machine --id shipment \
  --package com.example.shipment --output shipment-plugin

# 2. Implement and test.

# 3. Sign the catalog, runtime manifest, and optional config metadata.
#    The 32-byte seed stays outside the repo and is passed BY FILE ONLY.
./yano.sh appchain plugin sign \
  --catalog <catalog.json> --runtime-manifest <manifest.json> \
  --seed-file /secure/publisher.seed --key-id example-release-2026 \
  --output <catalog.sig.json>

# 4. Build and validate. Loads no plugin code.
./yano.sh appchain plugin validate <jar> --trust-key <key-id>=<public-key-hex>

# 5. Pin into a project.
./yano.sh appchain init --non-interactive --recipe custom-plugin \
  --network devnet --members 3 --runtime jvm \
  --capability state:shipment --plugin-jar <jar> \
  --trust-key <key-id>=<public-key-hex> --output shipment-chain

# 6. Check readiness against a real distribution.
./yano.sh appchain doctor shipment-chain --distribution /opt/yano-x

# 7. Copy the exact pinned JAR into plugins/ on EVERY member, then validate.
tools/yano-plugins/bin/yano-plugins validate plugins/*.jar
```

The JAR carries three independent bounded contracts plus a signature:

```text
META-INF/yano/plugins/<bundle-id>.json          runtime contributions + API levels
META-INF/yano/appchain-config-metadata-v1.json  typed configuration (optional)
META-INF/yano/appchain-component-catalog-v1.json selectable capabilities
META-INF/yano/appchain-component-catalog-v1.sig.json  Ed25519 trust envelope
```

**Signing authenticates bytes; it does not approve code** and does not elevate a
custom component to `BUNDLED`, `stable`, or native. Custom entries stay JVM-only
`REFERENCE` or `EXPERIMENTAL`. Release id, namespace, and artifact collisions
fail closed.

The custom state-machine shape:

```java
public final class ShipmentStateMachine implements AppStateMachine {
    @Override public String id() { return "shipment-v1"; }

    @Override
    public AdmissionResult validate(AppMessage message) {
        return decodeSafely(message.getBody())
                ? AdmissionResult.accept()
                : AdmissionResult.reject("invalid shipment command");
    }

    @Override
    public void apply(AppBlock block, AppStateWriter state) {
        // Deterministic bounded transitions only.
    }
}
```

Contribute it through `AppStateMachineProvider`, add the service entry and the
plugin manifest. Yano is never recompiled for a JVM deployment.

---

## 7. Evolving a live chain — versioning discipline

A change to deterministic semantics is **never** an ordinary rolling code
change. Semantic changes include: state encoding or key layout, transition
logic, validation outcomes, what a transition emits, a commitment profile or
component order, a proof subject descriptor, and genesis-selected configuration.

Chain identity is pinned by:

```text
(commitment-profile, format-fingerprint, genesis-id)
```

A retained `genesis-id` is never regenerated.

Three legitimate options:

1. **Governed profile activation** — stage the reviewed current and dormant
   targets on every member, then threshold-authorize one exact digest and a
   future activation height. Editing YAML or swapping a JAR alone changes
   nothing.
2. **A new component id or namespace**, with a migration plan, for incompatible
   state.
3. **A new chain**, when identity itself must change.

**Never silently change semantics behind an existing machine or component id.**
Rolling out edited transition logic member by member stalls the chain during the
rollout and makes historical replay disagree with signed roots afterwards. That
is unrecoverable without a new chain.

---

## 8. Configuration scope — the mistake that stalls clusters

| Scope | Meaning | Getting it wrong |
|---|---|---|
| `CONSENSUS_SHARED` | Identical on every member | The root diverges; the chain stops finalizing |
| Node-local | Ports, storage, credentials, executor placement | Only that node is affected |

Consensus-shared includes: the state-machine id, the composite profile digest,
all `effects.*` caps, value formats, quotas, and
`message.enforce-sender-seq`.

`NEW_CHAIN_REQUIRED` change policy means the value is part of chain identity.

Five secret classes, kept separate: member signing keys, business-actor keys,
API keys, effect/connector credentials, anchor wallet funds. Never place a
credential in consensus-shared configuration or a replicated effect payload.

---

## 9. Build and test commands

```bash
# User track: a clean clone, one command. No flags, no Yano checkout.
./gradlew clean build -PskipSigning=true
#  -> distribution/jvm/build/distributions/yano-x-jvm-<version>.zip

# Contributor track, coordinated Yano + Yano X development:
#   in the Yano repo:
./gradlew publishToMavenLocal :app:yanoDistZip -PskipSigning=true --no-parallel
#   in yano-x:
./gradlew test verifyArtifactInventory verifyJvmOnlyBuild \
  -PyanoVersion=<published-yano-version> -PuseMavenLocal=true --offline

# Focused gates
./gradlew :state-machines:stdlib:test -PyanoVersion=<v>
./gradlew :tooling:devtools:test      -PyanoVersion=<v>
./gradlew integrationTest             -PyanoVersion=<v>
./gradlew cryptoTest                  -PyanoVersion=<v>
```

`mavenLocal()` is disabled unless `-PuseMavenLocal=true`. For a released
non-SNAPSHOT `yanoVersion`, the base Yano JVM ZIP resolves from its GitHub
release automatically; `-PyanoJvmDist` only overrides that.

Run `verifyArtifactInventory` for any module, publication, manifest, bundle, or
contribution change; `verifyJvmOnlyBuild` for build topology changes;
`distributionCheck` or a clean `build` for dependency, bundle, class-isolation,
launch, or packaging changes.

**Do not stop at unit tests** when a plugin, catalog, distribution, persistence,
consensus, proof, anchor, or cross-node behavior changed. Validate a real
multi-node cluster: identical chain height, root, profile, genesis, and
capability-manifest digest, plus finality certificates, proof retrieval,
catch-up, restart, and anchor state.

---

## 10. Coding conventions

- Java 25, four-space indentation, lines at most 120 characters.
- Prefer package imports to fully qualified names.
- SLF4J for logging.
- JUnit 5, Mockito, AssertJ. Unit tests in `src/test/java`; integration tests in
  the configured integration source sets, named `*IT`.
- License: MIT.

---

## 11. Error-to-fix table

| Symptom | Root cause | Fix |
|---|---|---|
| Cluster stops finalizing after a deploy | Members run different bundles or different `CONSENSUS_SHARED` values | `./yano.sh appchain drift <project> --peer <node-url>`; align bundles and config |
| Root parity holds locally, fails on a cluster | Reproducible but not identical — usually `HashMap` iteration or a wall clock | See §4; re-run under `@AppChainCluster` |
| `verifyJvmOnlyBuild` fails | A native-image build or distribution task crept in | Remove it. Yano X is JVM-only by decision |
| `verifyArtifactInventory` fails | Module identity changed without updating `config/artifacts-v1.json` | Update the inventory and its verification tests |
| Build tries to reach Maven Local | `-PuseMavenLocal=true` needed but that is the contributor track | Users need no flags at all |
| Missing release asset for `yanoVersion` | SNAPSHOT or staged Yano version | Supply `-PyanoJvmDist` with the exact matching ZIP |
| `appchain doctor` reports incompatibility | Pointed at a native distribution | Yano X plugins target the JVM host |
| Node refuses to start with a catalog error | Two bundles provide the same contribution (classically both eUTxO runtimes) | Install exactly one; the other lives in `optional-plugins/` |
| `POST /messages` returns 429 | Pending pool full — backpressure working | Back off and retry; tune `pool.max-messages` deliberately |
| Message rejected, `drops.stale_seq` | Per-sender seq at or below the last finalized seq — a replay | Do not reuse envelopes; seq gaps are fine and meaningless |
| Product routes return 404, CLI exit code 3 | Cardano History has no finalized fact yet | Wait for the next stable L1 epoch transition. Never synthesize a value |
| Proof says `INTERNAL_CONSISTENCY_ONLY` | The root is not pinned by the caller, a finality policy, or a checked L1 output | Pin the root, or verify the Cardano output independently |
| Proof unavailable for an old height | Pruned past `oldestProvableHeight` | Unavailable is not evidence of absence |
| Effect ran twice externally | Execution is at-least-once by design | Make the receiver idempotent under the supplied identity |
| Effect never completed | No result within the window | It becomes `EXPIRED` deterministically; handle it in `onEffectResult` |

---

## 12. Where to look things up

| Need | Source |
|---|---|
| Live recipes, capabilities, modules, config properties | [`/ai/catalog.json`](https://yanox.dev/ai/catalog.json) |
| Everything on this site as one file | [`/llms-full.txt`](https://yanox.dev/llms-full.txt) |
| Version-matched truth for a build | `./yano.sh appchain recipes`, `./yano.sh appchain capabilities --format json` |
| Project workflow for agents | The in-repo `configure-yano-appchain` skill under `tooling/devtools/src/main/resources/appchain-dx/v1alpha1/skills/` — **it wins where it overlaps this pack** |
| Exhaustive reference | [Reference shelf](https://yanox.dev/reference/shelf/) |
| Plugin SPI contract | ADR-011, in the repository's `adr/app-layer/` directory |
| Repository invariants | [`AGENTS.md`](https://github.com/bloxbean/yano-x/blob/main/AGENTS.md) |

---

## 13. Agent safety rules

- Never request, print, copy, infer, or commit secret values. Refer only to
  documented environment-variable or secret-provider names.
- Never invent configuration keys, values, defaults, recipes, or compatibility
  claims. Report an unavailable capability as unsupported.
- Keep blueprint, resolved-config, release, plugin-catalog, and consensus
  identities distinct.
- Do not mutate a running node or call privileged runtime APIs unless explicitly
  asked.
- Treat custom-plugin metadata as `PARTIAL` unless Yano reports `FULL`.
- Never delete retained clusters, state, keys, or staging repositories without
  reviewing the exact path and obtaining explicit authorization. `stop`
  preserves state; `reset --yes` is destructive.
- Preprod operations submit real test-network transactions and spend test ADA.
  Require explicit authorization before deployment, anchor bootstrap, settlement
  bootstrap, or smoke traffic.
- Do not modify the sibling Yano repository as a side effect of a Yano X task.
