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

Unit-test `parse` and `apply` first, including empty, oversized and malformed
bodies. Then climb the [testing ladder](https://yano-x.io/plugins/testing-and-deployment/):
replay, hostile input, an embedded multi-member cluster, and a packaged JVM
cluster. The [plugin template](../../../scaffolds/plugin-template/) shows unit,
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

`plugin sign` ends with `PLUGIN_CATALOG_SIGNED key=example-release-2026
public-key=<64 hex characters>`; that public key is the
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
  --chain shipments=custom-plugin --yes
```

Open `shipments.draft.yaml` and add the machine id to the chain:

```yaml
chains:
  - id: shipments
    recipe: custom-plugin
    answers:
      stateMachine: shipment
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

> **✓ You should see** `chain shipments  custom-plugin` in the sealed summary,
> then `[Live]` with `members online 1/1` from `status`.

Submit a shipment update through the node and look it up, as in
[Set up an application](../deployment/configure.md#7-send-a-message-and-see-it-final),
with the chain `shipments` and a body such as `A-100=shipped`. Stop the node
with `./yano.sh appchain stop --node-dir shipment-node`.

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
--accept-bundles`. Agree the change with every operator first; the command
only makes the change explicit.

## Go deeper

- [Scaffold, sign, install](../../site/scaffold-sign-install.md), the same
  lifecycle as a reference
- [Testing and deployment](https://yano-x.io/plugins/testing-and-deployment/)
- [Composite implementation guide](../../../composition/runtime/README.md), for a
  Java composite
- [Plugin template scaffold](../../../scaffolds/plugin-template/)
- [Yano core testkit](https://github.com/bloxbean/yano/tree/main/appchain/appchain-testkit)
- Yano's [plugin query and domain API guide](https://github.com/bloxbean/yano/blob/main/docs/APP_CHAIN_PLUGIN_QUERY_AND_DOMAIN_API.md)
  and [plugin operations guide](https://github.com/bloxbean/yano/blob/main/docs/PLUGIN_OPERATIONS.md)
