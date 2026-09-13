package org.yanoproject.x.devtools;

import org.yanoproject.appchain.config.AppChainPropertyRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainMultiChainProjectTest {
    @TempDir
    Path temporary;

    private final AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();

    @Test
    void documentTrailRecipeUsesStockProvider() throws Exception {
        var catalog = new AppChainProjectCatalog(properties);
        var resolver = new AppChainProjectResolver(properties, catalog);
        var result = resolver.resolve(blueprint(chain("documents", "document-trail"), chain("orders", "audit-log")));
        assertThat(result.consensusProperties()).containsEntry("yano.app-chain.chains[0].state-machine", "doc-trail")
                .containsEntry("yano.app-chain.chains[1].state-machine", "ordered-log");
    }

    @Test
    void rendersIndependentChainsAndAllPeerOverlaysReproducibly() throws Exception {
        var catalog = new AppChainProjectCatalog(properties);
        var resolver = new AppChainProjectResolver(properties, catalog);
        var renderer = new AppChainProjectRenderer(catalog, resolver);
        var blueprint = blueprint(chain("orders", "audit-log"), chain("registry", "owned-registry"));
        var resolution = resolver.resolve(blueprint);
        var project = temporary.resolve("project");
        var lock = renderer.initialize(project, blueprint);
        assertThat(lock.recipe()).isEqualTo(catalog.recipe("audit-log").id() + ":" + catalog.recipe("audit-log").version()
                + ",owned-registry:" + catalog.recipe("owned-registry").version());
        assertThat(lock.consensusValues()).containsEntry("yano.app-chain.chains[0].chain-id", "orders")
                .containsEntry("yano.app-chain.chains[1].chain-id", "registry");
        assertThat(lock.consensusValues().get("yano.app-chain.chains[0].state.genesis-id"))
                .isNotEqualTo(lock.consensusValues().get("yano.app-chain.chains[1].state.genesis-id"));
        assertThat(Files.readString(project.resolve("config/nodes/node0.yaml")))
                .contains("127.0.0.1:13338", "127.0.0.1:13339",
                        "${YANO_APPCHAIN_DATA_ROOT}/node0/history", "${YANO_APPCHAIN_DATA_ROOT}/node0/yano.log");
        assertThat(resolution.nodePropertyTemplate()).containsKeys(
                "yano.app-chain.chains[0].signing-key", "yano.app-chain.chains[1].signing-key");
        assertThat(project.resolve("chains/registry/docs/VERIFY.md")).exists();
        assertThat(renderer.render(project)).isEqualTo(lock);
        assertThat(renderer.validate(project).lock()).isEqualTo(lock);
    }

    @Test
    void chainIdentityDoesNotDependOnPositionOrOtherChains() throws Exception {
        var catalog = new AppChainProjectCatalog(properties);
        var resolver = new AppChainProjectResolver(properties, catalog);
        var orders = chain("orders", "audit-log");
        var old = resolver.resolve(blueprint(orders));
        var added = resolver.resolve(blueprint(chain("registry", "owned-registry"), orders));
        assertThat(added.chains().get(1).consensusProperties()).isEqualTo(old.consensusProperties());
    }

    @Test
    void rejectsDuplicateIdsAndIncompatibleNodePlacementBeforeWriting() throws Exception {
        var catalog = new AppChainProjectCatalog(properties);
        var resolver = new AppChainProjectResolver(properties, catalog);
        assertThatThrownBy(() -> resolver.resolve(blueprint(chain("orders", "audit-log"),
                chain("orders", "owned-registry")))).hasMessageContaining("unique");
        var other = new AppChainProjectModel.ChainIntent("registry", "owned-registry", List.of(), Map.of(),
                new AppChainProjectModel.Topology(1, List.of(), List.of(), "all", "fixed", "static", null, null));
        assertThatThrownBy(() -> resolver.resolve(blueprint(chain("orders", "audit-log"), other)))
                .hasMessageContaining("same members");
    }

    static AppChainProjectModel.ChainIntent chain(String id, String recipe) {
        return new AppChainProjectModel.ChainIntent(id, recipe, List.of(), Map.of(),
                new AppChainProjectModel.Topology(3, List.of(), List.of(), "two-thirds", "fixed", "static",
                        null, null));
    }

    static AppChainProjectModel.Blueprint blueprint(AppChainProjectModel.ChainIntent... chains) {
        return new AppChainProjectModel.Blueprint(AppChainProjectModel.API_VERSION,
                AppChainProjectModel.BLUEPRINT_KIND, new AppChainProjectModel.Metadata("multi-chain"),
                new AppChainProjectModel.Spec("test", "devnet", new AppChainProjectModel.RuntimeSelection("jvm"),
                        new AppChainProjectModel.DeploymentSelection("host"), List.of(chains)));
    }
}
