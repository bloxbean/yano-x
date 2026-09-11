package org.yanoproject.x.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.yanoproject.x.devtools.AppChainMultiChainProjectTest.blueprint;
import static org.yanoproject.x.devtools.AppChainMultiChainProjectTest.chain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainProjectOperationsTest {
    @TempDir Path temporary;
    private final AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    private AppChainProjectRenderer renderer() throws Exception {
        var catalog = new AppChainProjectCatalog(properties);
        return new AppChainProjectRenderer(catalog, new AppChainProjectResolver(properties, catalog));
    }

    @Test
    void preparePinsIndependentKeysOnceAndDoesNotOverwriteRetainedIdentity() throws Exception {
        Path root = temporary.resolve("project");
        renderer().initialize(root, blueprint(chain("orders", "audit-log"), chain("registry", "owned-registry")));
        var operations = new AppChainProjectOperations(properties);
        operations.prepare(root);
        var first = renderer().validate(root).lock();
        byte[] secret = Files.readAllBytes(root.resolve("secrets/node0.env"));
        operations.prepare(root);
        assertThat(renderer().validate(root).lock()).isEqualTo(first);
        assertThat(Files.readAllBytes(root.resolve("secrets/node0.env"))).isEqualTo(secret);
        var chains = renderer().readBlueprint(root).spec().chains();
        assertThat(chains.getFirst().topology().memberKeys()).hasSize(3).doesNotHaveDuplicates();
        assertThat(chains.get(1).topology().memberKeys()).isEqualTo(chains.getFirst().topology().memberKeys());
        assertThat(Files.readString(root.resolve("appchain.yaml"))).doesNotContain(
                new String(secret).split("=")[1].split("\n")[0]);
    }

    @Test
    void plansAndAppliesAdditionWithoutTouchingStateAndRejectsStalePlan() throws Exception {
        Path root = temporary.resolve("project");
        renderer().initialize(root, blueprint(chain("orders", "audit-log")));
        var operations = new AppChainProjectOperations(properties);
        operations.prepare(root);
        operations.startCheck(root);
        Files.createDirectories(root.resolve("data/node0/appchain-chainstate/orders"));
        Path retained = root.resolve("data/node0/appchain-chainstate/orders/retained.txt");
        Files.writeString(retained, "retained-state");
        var original = renderer().readBlueprint(root);
        var originalGenesis = renderer().readLock(root).consensusValues().get(
                "yano.app-chain.chains[0].state.genesis-id");
        var added = new AppChainProjectModel.ChainIntent("registry", "owned-registry", List.of(), Map.of(),
                original.spec().chains().getFirst().topology());
        var spec = original.spec();
        var proposed = new AppChainProjectModel.Blueprint(original.apiVersion(), original.kind(), original.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), spec.network(), spec.runtime(), spec.deployment(),
                        List.of(added, spec.chains().getFirst()), spec.componentCatalogs(), spec.acknowledgements()));
        Files.write(root.resolve("appchain.yaml"), yaml.writeValueAsBytes(proposed));
        var plan = operations.plan(root);
        assertThat(plan.status()).isEqualTo("PLAN_READY");
        assertThat(plan.chains()).contains(new AppChainProjectOperations.ChainChange("orders", "UNCHANGED"),
                new AppChainProjectOperations.ChainChange("registry", "ADD"));
        assertThatThrownBy(() -> renderer().render(root)).hasMessageContaining("Retained deployment changed");
        assertThatThrownBy(() -> operations.apply(root, "00".repeat(32))).hasMessageContaining("stale");
        operations.apply(root, plan.digest());
        operations.apply(root, plan.digest());
        operations.startCheck(root);
        assertThat(Files.readString(retained)).isEqualTo("retained-state");
        assertThat(renderer().validate(root).lock().consensusValues()).containsEntry(
                "yano.app-chain.chains[1].state.genesis-id", originalGenesis);
        assertThat(root.resolve(".deployment/pending.json")).doesNotExist();
    }

    @Test
    void resumesInterruptedFileActivationAndRefusesStartupUntilComplete() throws Exception {
        Path root = temporary.resolve("project");
        renderer().initialize(root, blueprint(chain("orders", "audit-log")));
        var operations = new AppChainProjectOperations(properties);
        operations.prepare(root);
        operations.startCheck(root);
        var oldLock = renderer().readLock(root);
        byte[] oldBytes = Files.readAllBytes(root.resolve("appchain.lock"));
        byte[] oldConfig = Files.readAllBytes(root.resolve("config/nodes/node0.yaml"));
        operations.addChain(root, "documents", "document-trail", List.of(), Map.of());
        var plan = operations.plan(root);
        operations.apply(root, plan.digest());
        var nextLock = renderer().readLock(root);
        var before = new java.util.TreeMap<>(oldLock.generatedFiles());
        before.put("appchain.lock", plan.beforeDigest());
        var after = new java.util.TreeMap<>(nextLock.generatedFiles());
        after.put("appchain.lock", AppChainProjectCatalog.sha256(Files.readAllBytes(root.resolve("appchain.lock"))));
        Files.write(root.resolve("appchain.lock"), oldBytes);
        Files.write(root.resolve("config/nodes/node0.yaml"), oldConfig);
        Files.write(root.resolve(".deployment/active.lock"), oldBytes);
        Files.write(root.resolve(".deployment/pending.json"), new ObjectMapper().writeValueAsBytes(
                new AppChainProjectOperations.Transaction(plan, before, after)));
        assertThatThrownBy(() -> operations.startCheck(root)).hasMessageContaining("pending apply");
        assertThatThrownBy(() -> operations.apply(root, "00".repeat(32))).hasMessageContaining("original plan");
        operations.apply(root, plan.digest());
        operations.startCheck(root);
        assertThat(renderer().validate(root).lock()).isEqualTo(nextLock);
        assertThat(root.resolve(".deployment/pending.json")).doesNotExist();
    }

    @Test
    void planExposesNodePlacementChangesBeforeApply() throws Exception {
        Path root = temporary.resolve("project");
        renderer().initialize(root, blueprint(chain("orders", "audit-log")));
        var document = yaml.readTree(Files.readAllBytes(root.resolve("appchain.yaml")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.at("/spec/chains/0/topology"))
                .put("httpPortBase", 19080);
        Files.write(root.resolve("appchain.yaml"), yaml.writeValueAsBytes(document));
        var plan = new AppChainProjectOperations(properties).plan(root);
        assertThat(plan.status()).isEqualTo("PLAN_BLOCKED");
        assertThat(plan.blockers()).anyMatch(value -> value.contains("Node placement"));
    }

    @Test
    void blocksExistingConsensusChangesAndActiveProcesses() throws Exception {
        Path root = temporary.resolve("project");
        renderer().initialize(root, blueprint(chain("orders", "audit-log")));
        var operations = new AppChainProjectOperations(properties);
        var document = yaml.readTree(Files.readAllBytes(root.resolve("appchain.yaml")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.at("/spec/chains/0/topology"))
                .put("finality", "all");
        Files.write(root.resolve("appchain.yaml"), yaml.writeValueAsBytes(document));
        assertThat(operations.plan(root).status()).isEqualTo("PLAN_BLOCKED");
        Files.createDirectories(root.resolve("run"));
        Files.writeString(root.resolve("run/node0.pid"), Long.toString(ProcessHandle.current().pid()));
        assertThatThrownBy(() -> operations.apply(root, operations.plan(root).digest()))
                .hasMessageContaining("Stop all project nodes");
    }
}
