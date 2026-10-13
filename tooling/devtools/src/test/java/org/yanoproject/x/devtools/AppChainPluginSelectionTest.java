package org.yanoproject.x.devtools;

import org.yanoproject.appchain.config.AppChainPropertyRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AppChainPluginSelectionTest {
    private static final Map<String, String> EUTXO_CHAIN = Map.of(
            "yano.app-chain.chains[0].state-machine", "eutxo-ledger",
            "yano.app-chain.chains[0].observers.deposits.type", "vault-deposit");

    @TempDir
    Path distribution;

    @Test
    void passesWhenSelectedBundlesProvideTheChainsContributions() throws Exception {
        bundle("plugins", "example.ledger", null, "app-state-machine", "eutxo-ledger");
        bundle("plugins", "example.bridge", null, "l1-observer", "vault-deposit");

        assertThat(AppChainPluginSelection.check(distribution, EUTXO_CHAIN, Set.of()).status()).isEqualTo("PASS");
    }

    @Test
    void failsWhenASelectedBundleDependsOnAnUnselectedOne() throws Exception {
        bundle("plugins", "example.zk-ledger", null, "app-state-machine", "eutxo-ledger");
        bundle("plugins", "example.bridge", "example.ledger", "l1-observer", "vault-deposit");

        AppChainPluginSelection.Result result = AppChainPluginSelection.check(distribution, EUTXO_CHAIN, Set.of());

        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.detail()).contains("requires unavailable selected bundle 'example.ledger'");
    }

    @Test
    void namesTheOptionalBundleThatProvidesAMissingStateMachine() throws Exception {
        bundle("plugins", "example.bridge", null, "l1-observer", "vault-deposit");
        bundle("optional-plugins", "example.zk-ledger", null, "app-state-machine", "eutxo-ledger");

        AppChainPluginSelection.Result result = AppChainPluginSelection.check(distribution, EUTXO_CHAIN, Set.of());

        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.detail()).contains("app-state-machine/eutxo-ledger", "example.zk-ledger");
    }

    @Test
    void requiresTheOptionalBundlesTheProjectNames() throws Exception {
        bundle("plugins", "example.ledger", null, "app-state-machine", "eutxo-ledger");
        bundle("plugins", "example.bridge", null, "l1-observer", "vault-deposit");
        bundle("optional-plugins", "example.zk-ledger", null, "app-state-machine", "eutxo-ledger");

        AppChainPluginSelection.Result result = AppChainPluginSelection.check(
                distribution, EUTXO_CHAIN, Set.of("example.zk-ledger", "example.bridge"));

        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.detail()).contains("example.zk-ledger (install it from optional-plugins/)")
                .doesNotContain("example.bridge (");
    }

    @Test
    void acceptsTheHostsBuiltInStateMachinesAndObserversWithoutABundle() throws Exception {
        bundle("plugins", "example.bridge", null, "l1-observer", "vault-deposit");

        assertThat(AppChainPluginSelection.check(distribution, Map.of(
                "yano.app-chain.chains[0].state-machine", "ordered-log",
                "yano.app-chain.chains[0].observers.metadata.type", "metadata-label",
                "yano.app-chain.chains[0].observers.deposits.type", "address-deposit"), Set.of()).status())
                .isEqualTo("PASS");
    }

    @Test
    void failsWhenNoBundleProvidesAContributionTheHostDoesNotBuildIn() throws Exception {
        bundle("plugins", "example.bridge", null, "l1-observer", "vault-deposit");

        AppChainPluginSelection.Result result = AppChainPluginSelection.check(distribution, Map.of(
                "yano.app-chain.chains[0].state-machine", "made-up-ledger"), Set.of());

        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.detail()).contains("app-state-machine/made-up-ledger (no bundle in plugins/ provides it)");
    }

    @Test
    void failsWhenAProjectBundleIsInNeitherPluginDirectory() throws Exception {
        bundle("plugins", "example.bridge", null, "l1-observer", "vault-deposit");

        AppChainPluginSelection.Result result = AppChainPluginSelection.check(distribution, EUTXO_CHAIN,
                Set.of("example.ledger", "example.bridge"));

        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.detail())
                .contains("example.ledger (in neither plugins/ nor optional-plugins/)")
                .contains("app-state-machine/eutxo-ledger (no bundle in plugins/ provides it)")
                .doesNotContain("example.bridge (");
    }

    @Test
    void failsWhenADefaultDistributionHasNoPluginsAtAll() throws Exception {
        AppChainPluginSelection.Result result = AppChainPluginSelection.check(distribution, Map.of(
                "yano.app-chain.chains[0].state-machine", "kv-registry"), Set.of("org.yanoproject.x.stdlib"));

        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.detail()).contains("org.yanoproject.x.stdlib (in neither", "app-state-machine/kv-registry");
    }

    @Test
    void anUninstalledBundleTheReleaseDoesNotShipIsPendingNotFailed() throws Exception {
        bundle("plugins", "example.bridge", null, "l1-observer", "vault-deposit");

        AppChainPluginSelection.Result result = AppChainPluginSelection.check(distribution, EUTXO_CHAIN,
                Set.of("example.ledger", "example.bridge"), Set.of("example.ledger"));

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.detail()).contains("example.ledger (not shipped by this release",
                "app-state-machine/eutxo-ledger");
    }

    @Test
    void hostBuiltInsMatchTheCatalogCapabilitiesThatNeedOnlyTheHost() throws Exception {
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(AppChainPropertyRegistry.framework());
        Set<String> hostOnly = new java.util.TreeSet<>();
        for (AppChainProjectModel.Capability capability : catalog.capabilities()) {
            if (!List.of("yano-runtime").equals(capability.artifacts())) continue;
            Map<String, String> values = new java.util.TreeMap<>();
            capability.properties().forEach((key, value) -> {
                if (!value.contains("${")) values.put("yano.app-chain.chains[0]." + key, value);
            });
            hostOnly.addAll(AppChainPluginSelection.requiredContributions(values));
        }
        assertThat(hostOnly).isEqualTo(AppChainPluginSelection.HOST_BUILT_INS);
    }

    private void bundle(String directory, String id, String dependency, String kind, String name)
            throws IOException {
        PluginBundleFixture.write(distribution.resolve(directory), id, dependency, kind, name);
    }
}
