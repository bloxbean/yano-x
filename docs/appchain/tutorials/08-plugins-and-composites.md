# Tutorial 8 — Extend Yano Without Forking It

To run a plugin chain with other operators, follow
[Set up an application](../deployment/configure.md) after step 6.

- **Level:** Java application developer
- **Time:** 30–60 minutes for a first plugin
- **Outcome:** build and sign a small state-machine plugin, install the same
  JAR in every member's JVM distribution, and run it on a sealed chain.

You will write a `shipment` machine that records the latest status of each
shipment. It is deliberately small: the point is the path from scaffold to a
pinned, verifiable JAR. The plugin pages explain each part in depth; this
tutorial links to them instead of repeating them.

## Before you start

- Java 25 and Gradle 9.1 or later, the first Gradle release that runs on
  Java 25 (Yano X itself builds with Gradle 9.4.1). The generated project has
  no Gradle wrapper; step 5 shows how to build with your own `gradle` or add
  a wrapper.
- An extracted Yano X JVM distribution. This tutorial assumes `/opt/yano-x` and
  runs every `./yano.sh` command from that directory.
- A 32-byte publisher seed, written as 64 hexadecimal characters to a file
  outside any repository, for signing. For a throwaway tutorial key in an
  owner-only directory of your home:

  ```bash
  mkdir -m 700 -p "$HOME/.yano-tutorial"
  (umask 077; openssl rand -hex 32 > "$HOME/.yano-tutorial/publisher.seed")
  ```

- The exact Yano host version of your distribution. Run
  `jq -r .version yano-distribution-v1.json` in the distribution's top-level
  directory; step 2 uses it directly. The host and Yano X have separate
  versions: `yano-x-distribution-v1.json` holds the Yano X version.

## 1. Choose the smallest extension

A Java plugin is the last rung, not the first. Check whether configuration or
[declarative bindings](../bindings/README.md) already model your outcome:

<!-- illustration: extension-ladder -->
```text
Does a stock machine or recipe already model the outcome?
  ├─ yes → configuration only
  └─ no
      Do existing machines have the commands and events you need?
        ├─ yes → declarative bindings, or a small Java composite
        └─ no  → a custom state-machine plugin (this tutorial)
```
<!-- /illustration -->

[The extension ladder](../../site/plugins-overview.md) describes every rung.
A chain built from declarative bindings is a composite chain: create it with
`./yano.sh appchain new <app> --chain flow=bindings:<file>`, or with a
`bindings:` entry in the draft, as
[Set up an application](../deployment/configure.md#2-the-coordinator-creates-the-draft)
shows. No Java is needed for that rung.

## 2. Scaffold the plugin

```bash
./yano.sh appchain plugin scaffold \
  --mode state-machine \
  --id shipment \
  --package com.example.shipment \
  --yano-version "$(jq -r .version yano-distribution-v1.json)" \
  --output shipment-plugin
```

The scaffold is a small Gradle project. It compiles against the host API as
`compileOnly`, so the JAR never packages Yano API classes. It contains:

- `src/main/java/com/example/shipment/ShipmentStateMachineProvider.java`, the
  provider, which does no business work yet;
- its `META-INF/services/org.yanoproject.api.appchain.AppStateMachineProvider`
  entry;
- the runtime manifest `META-INF/yano/plugins/plugin-bundle.shipment.json`; and
- the product catalog `META-INF/yano/appchain-component-catalog-v1.json`, which
  declares the capability `state:shipment`.

The tool refuses a non-empty output directory. Other modes are
`composite-role`, `effect-executor` and `sink`.

The generated `build.gradle` resolves the host API from Maven Central. If your
distribution's host version is a commit snapshot (it ends in `-SNAPSHOT`), add
`maven { url = uri('https://repo.bloxbean.org/maven/snapshots') }` to its
`repositories` block.

## 3. Implement the machine

Replace the generated provider file with this one. A message body is the UTF-8
text `<shipment-id>=<status>`; the machine stores each shipment's latest
status.

```java
package com.example.shipment;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class ShipmentStateMachineProvider implements AppStateMachineProvider {
    @Override
    public String id() {
        return "shipment";
    }

    @Override
    public AppStateMachine create() {
        return new Machine();
    }

    /** Stores the latest status of each shipment. */
    private static final class Machine implements AppStateMachine {
        private static final int MAX_BODY_BYTES = 256;

        @Override
        public String id() {
            return "shipment";
        }

        @Override
        public AdmissionResult validate(AppMessage message) {
            // A fast structural check; never perform I/O here.
            return parse(message.getBody()).isPresent()
                    ? AdmissionResult.accept()
                    : AdmissionResult.reject("INVALID_SHIPMENT_COMMAND");
        }

        @Override
        public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) {
            for (AppMessage message : context.messages()) {
                // Finalized bytes can still be malformed: skip them, never throw.
                parse(message.getBody()).ifPresent(update -> writer.put(
                        ("shipment/" + update[0]).getBytes(StandardCharsets.UTF_8),
                        update[1].getBytes(StandardCharsets.UTF_8)));
            }
        }

        private static Optional<String[]> parse(byte[] body) {
            if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
                return Optional.empty();
            }
            String text = new String(body, StandardCharsets.UTF_8);
            int split = text.indexOf('=');
            if (split <= 0 || split == text.length() - 1) {
                return Optional.empty();
            }
            return Optional.of(new String[]{text.substring(0, split), text.substring(split + 1)});
        }
    }
}
```

Three rules matter here, and [Consensus rules](../../site/plugins-consensus-rules.md)
explains them all:

- `apply(AppBlockExecutionContext, AppStateWriter, AppEffectEmitter)` is the
  host's only execution entry point. It runs on every member and must give
  byte-identical state: no clock, randomness or I/O.
- `validate` is a filter at ingress, not a security boundary. `apply` must
  still treat every message as hostile and bound its work.
- A rejection reason is a public code: at most 32 uppercase letters or
  underscores.

Read [Determinism rules](https://yano-x.io/concepts/determinism-rules/) before you
add more logic. To act on an external system, emit an effect from `apply` and
let an executor perform it after finality; see [effects](../../site/concepts-effects.md).

## 4. Test it

The scaffold has no tests yet. Add JUnit to `shipment-plugin/build.gradle`,
using the same Yano version as its `compileOnly` line:

```groovy
dependencies {
    testImplementation 'org.yanoproject:yano-core-api:<the yano version above>'
    testImplementation platform('org.junit:junit-bom:5.10.2')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
test { useJUnitPlatform() }
```

Then unit-test `validate` and `apply`, including empty, oversized and malformed
bodies, in `src/test/java/com/example/shipment/ShipmentTest.java`:

```java
package com.example.shipment;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.FinalityCert;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipmentTest {
    private static AppMessage message(byte[] body) {
        return AppMessage.builder().version(2).chainId("t").topic("shipments")
                .sender(new byte[32]).senderSeq(0).expiresAt(0)
                .messageId(new byte[32]).authProof(new byte[0]).body(body).build();
    }

    @Test
    void appliesValidUpdatesAndSkipsMalformedOnes() {
        AppStateMachine machine = new ShipmentStateMachineProvider().create();
        assertTrue(machine.validate(message("A-100=shipped".getBytes())).isAccepted());
        assertFalse(machine.validate(message("bad".getBytes())).isAccepted());
        assertFalse(machine.validate(message(new byte[0])).isAccepted());
        assertFalse(machine.validate(message(new byte[300])).isAccepted());

        List<AppMessage> messages = List.of(message("A-100=shipped".getBytes()),
                message("=x".getBytes()), message(new byte[300]));
        AppBlock block = new AppBlock(2, "t", 1, new byte[32], 0, new byte[0], 0L,
                new byte[32], new byte[32], messages, new byte[32], FinalityCert.empty());
        Map<String, byte[]> state = new HashMap<>();
        AppStateWriter writer = new AppStateWriter() {
            public Optional<byte[]> get(byte[] key) { return Optional.ofNullable(state.get(new String(key))); }
            public void put(byte[] key, byte[] value) { state.put(new String(key), value); }
            public void delete(byte[] key) { state.remove(new String(key)); }
            public byte[] stateRoot() { return new byte[32]; }
        };
        machine.apply(AppBlockExecutionContext.fromValidatedBlock(block), writer,
                AppEffectEmitter.rejecting("none"));

        assertEquals(1, state.size());
        assertEquals("shipped", new String(state.get("shipment/A-100"), StandardCharsets.UTF_8));
    }
}
```

Run it with `(cd shipment-plugin && gradle test)`. Then climb the
[testing ladder](https://yano-x.io/plugins/testing-and-deployment/):
replay, hostile input, an embedded multi-member cluster, and a packaged JVM
cluster. The [plugin template](https://github.com/bloxbean/yano-x/tree/main/scaffolds/plugin-template) shows unit,
conformance and domain API tests for a similar machine.

## 5. Sign, build and validate

Sign the exact catalog and runtime manifest, then build the JAR so it carries
the signature:

```bash
./yano.sh appchain plugin sign \
  --catalog shipment-plugin/src/main/resources/META-INF/yano/appchain-component-catalog-v1.json \
  --runtime-manifest shipment-plugin/src/main/resources/META-INF/yano/plugins/plugin-bundle.shipment.json \
  --seed-file "$HOME/.yano-tutorial/publisher.seed" \
  --key-id example-release-2026 \
  --output shipment-plugin/src/main/resources/META-INF/yano/appchain-component-catalog-v1.sig.json

(cd shipment-plugin && gradle jar)   # Gradle 9.1+ on your PATH

./yano.sh appchain plugin validate shipment-plugin/build/libs/shipment-yano-plugin.jar \
  --trust-key example-release-2026=<64-hex-public-key> \
  --output shipment-catalog.json
tools/yano-plugins/bin/yano-plugins validate shipment-plugin/build/libs/shipment-yano-plugin.jar
```

The scaffold ships no Gradle wrapper, so `gradle jar` uses the Gradle on your
`PATH`. To pin a version for your team instead, add a wrapper once and build
with it:

```bash
(cd shipment-plugin && gradle wrapper --gradle-version 9.4.1 && ./gradlew jar)
```

`plugin sign` prints `PLUGIN_CATALOG_SIGNED key=example-release-2026
public-key=<64 hex characters> output=…`; that public key is the
`<64-hex-public-key>` for `--trust-key` here and in step 6. `plugin validate`
prints `PLUGIN_CATALOG_VALID` and `SNAPSHOT_WRITTEN shipment-catalog.json`,
and `yano-plugins validate` prints `VALID`.

Pass the seed by file only, and keep it out of the repository. `plugin validate`
verifies the signature and exports a data-only snapshot; `yano-plugins
validate` runs the node's structural checks. Neither runs your code.
[SPI and manifest](../../site/plugins-spi-and-manifest.md) explains what the
signature does and does not cover.

## 6. Install it in every member's distribution

Copy the exact JAR into `plugins/` of the Yano X distribution of **every**
operator, before the application is sealed. Each operator should first check
the JAR they received themselves:

```bash
./yano.sh appchain plugin validate shipment-plugin/build/libs/shipment-yano-plugin.jar \
  --trust-key example-release-2026=<64-hex-public-key> --output shipment-catalog.json
cp shipment-plugin/build/libs/shipment-yano-plugin.jar plugins/
```

`seal` records the SHA-256 digest of every bundle in `plugins/`, and `join`
and `start` refuse a distribution whose bundles differ. So every operator must
run the identical JAR, and a missing or rebuilt JAR is caught before the node
starts. `seal` does not read the plugin's signed component catalog: verifying
the signature is the `plugin validate` step above, which each operator runs.

## 7. Run it on a chain

A chain selects a custom machine with the `custom-plugin` recipe and the
machine id as the `stateMachine` answer. To try it alone, create a one-member
devnet application. It uses its own node directory and ports, so it does not
clash with other tutorials:

```bash
./yano.sh appchain new shipments --network devnet --operators alice \
  --chain shipments=custom-plugin --answer shipments.stateMachine=shipment --yes
```

`--answer` writes the machine id into the draft, which you can check:

```yaml
chains:
  - id: shipments
    recipe: custom-plugin
    answers:
      stateMachine: "shipment"
```

Then make the key, seal, join, and start:

```bash
./yano.sh appchain request shipments.draft.yaml --name alice --host 127.0.0.1 \
  --node-port 13637 --http-port 7370 --node-dir shipment-node --yes
./yano.sh appchain seal shipments.draft.yaml
./yano.sh appchain join shipments.chain --node-dir shipment-node
./yano.sh appchain start --node-dir shipment-node
./yano.sh appchain status shipments --node-dir shipment-node
```

`join` shows the chain fingerprint and asks you to confirm it matches the one
`seal` printed. In a script, pass `--expect-fingerprint <fingerprint>` instead.

> **✓ You should see** `chain shipments  custom-plugin` in the sealed summary,
> then `[Live]` with `members online 1/1` from `status`.

Submit a shipment update through the node, then look it up:

```bash
API_KEY=$(cat shipment-node/secrets/api.key)
MESSAGE_ID=$(curl -fsS -X POST http://127.0.0.1:7370/api/v1/app-chain/chains/shipments/messages \
  -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d '{"topic":"shipments","body":"A-100=shipped"}' | jq -er .messageId)
sleep 3
curl -fsS -H "X-API-Key: $API_KEY" \
  "http://127.0.0.1:7370/api/v1/app-chain/chains/shipments/messages/$MESSAGE_ID" | jq .height
```

> **✓ You should see** a height of 1 or more. A body your `validate` refuses,
> such as `bad`, is rejected at submission with `"code":"INVALID_SHIPMENT_COMMAND"`.

Stop the node with `./yano.sh appchain stop --node-dir shipment-node`.

For members on separate machines, list every operator in `--operators` and
follow [Set up an application](../deployment/configure.md): every operator
installs the same JAR first, and the fingerprint they compare covers its
digest. Each node checks the JAR again at start-up, before your code runs;
[How plugins load](../../site/plugins-how-plugins-load.md) lists those checks.

## 8. Change it safely later

Changing what `apply` computes is a consensus change, not a rolling upgrade.
Never roll new semantics out member by member: give the change a governed
activation height, or a new machine id and namespace.
[Consensus rules](../../site/plugins-consensus-rules.md#evolving-a-live-chain)
describes the options.

A rebuilt JAR has a new digest, so each node's `start` refuses it until its
operator accepts it with `./yano.sh appchain join shipments.chain
--node-dir shipment-node --accept-bundles`. Agree the change with every operator first; the command
only makes the change explicit.

## Go deeper

- [Scaffold, sign, install](../../site/scaffold-sign-install.md), the same
  lifecycle as a reference
- [Testing and deployment](https://yano-x.io/plugins/testing-and-deployment/)
- [Composite implementation guide](https://github.com/bloxbean/yano-x/blob/main/composition/runtime/README.md), for a
  Java composite
- [Plugin template scaffold](https://github.com/bloxbean/yano-x/tree/main/scaffolds/plugin-template)
- [Yano core testkit](https://github.com/bloxbean/yano/tree/main/appchain/appchain-testkit)
- Yano's [plugin query and domain API guide](https://github.com/bloxbean/yano/blob/main/docs/APP_CHAIN_PLUGIN_QUERY_AND_DOMAIN_API.md)
  and [plugin operations guide](https://github.com/bloxbean/yano/blob/main/docs/PLUGIN_OPERATIONS.md)
