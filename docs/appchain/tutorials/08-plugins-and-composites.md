# Tutorial 8 — Extend Yano Without Forking It

[Open the custom-plugin path in App-Chain Studio](../../../tooling/studio/src/main/web/index.html#recipe=custom-plugin&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=host&name=custom-appchain&chainId=custom-appchain&stateMachine=com.example.my-machine)

- **Level:** Java application developer
- **Time:** 30–60 minutes for a first plugin
- **Outcome:** build, sign and pin a small state-machine plugin, and install the
  same JAR on every member of the standard JVM distribution.

You will write a `shipment` machine that records the latest status of each
shipment. It is deliberately small: the point is the path from scaffold to a
pinned, verifiable JAR. The plugin pages explain each part in depth; this
tutorial links to them instead of repeating them.

## Before you start

- Java 25 and Gradle (or your organization's Gradle wrapper).
- An extracted Yano X JVM distribution. This tutorial assumes `/opt/yano-x` and
  runs every `./yano.sh` command from that directory.
- A 32-byte publisher seed, written as 64 hexadecimal characters to a file
  outside any repository, for signing. For a throwaway tutorial key:
  `(umask 077; openssl rand -hex 32 > /secure/publisher.seed)`.
- The exact Yano host version in your distribution's manifest, printed by
  `jq -r .version yano-distribution-v1.json`. The host and Yano X have
  separate versions.

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

## 2. Scaffold the plugin

```bash
./yano.sh appchain plugin scaffold \
  --mode state-machine \
  --id shipment \
  --package com.example.shipment \
  --yano-version <matching-yano-host-version> \
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
  --seed-file /secure/publisher.seed \
  --key-id example-release-2026 \
  --output shipment-plugin/src/main/resources/META-INF/yano/appchain-component-catalog-v1.sig.json

(cd shipment-plugin && gradle jar)

./yano.sh appchain plugin validate shipment-plugin/build/libs/shipment-yano-plugin.jar \
  --trust-key example-release-2026=<64-hex-public-key> \
  --output shipment-catalog.json
tools/yano-plugins/bin/yano-plugins validate shipment-plugin/build/libs/shipment-yano-plugin.jar
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

## 6. Pin it into a project

```bash
./yano.sh appchain init --non-interactive \
  --recipe custom-plugin --network devnet --members 3 --runtime jvm \
  --capability state:shipment \
  --plugin-jar shipment-plugin/build/libs/shipment-yano-plugin.jar \
  --trust-key example-release-2026=<64-hex-public-key> \
  --output shipment-chain

./yano.sh appchain config validate --mode project shipment-chain
```

The project stores the signed snapshot under `component-catalogs/`, and
`appchain.lock` pins the catalog, runtime manifest, configuration metadata and
complete JAR digests. Rendering and `doctor` re-verify the snapshot.

## 7. Install on every member

Copy the exact pinned JAR into `plugins/` in every member's distribution, then
check readiness:

```bash
cp shipment-plugin/build/libs/shipment-yano-plugin.jar /opt/yano-x/plugins/
./yano.sh appchain doctor shipment-chain --distribution /opt/yano-x
```

A missing or different JAR fails artifact readiness. Yano X plugins target the
JVM distribution. Start the nodes as the
[deployment guide](../deployment/README.md) describes. Each node checks the JAR
again at start-up, before your code runs; [How plugins load](../../site/plugins-how-plugins-load.md)
lists those checks. Then confirm that every member runs the same plugin catalog:

```bash
./yano.sh appchain drift shipment-chain --peer http://node-a:8080/api/v1/ \
  --peer http://node-b:8080/api/v1/ --peer http://node-c:8080/api/v1/
```

## 8. Change it safely later

Changing what `apply` computes is a consensus change, not a rolling upgrade.
Never roll new semantics out member by member: give the change a governed
activation height, or a new machine id and namespace. Compare a project change
with `appchain diff` before you apply it.
[Consensus rules](../../site/plugins-consensus-rules.md#evolving-a-live-chain)
describes the options.

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
