package org.yanoproject.x.devtools;

import org.yanoproject.catalog.ContributionKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    void treatsContributionsNoShippedBundleProvidesAsHostBuiltIns() throws Exception {
        bundle("plugins", "example.bridge", null, "l1-observer", "vault-deposit");

        assertThat(AppChainPluginSelection.check(distribution, Map.of(
                "yano.app-chain.chains[0].state-machine", "ordered-log",
                "yano.app-chain.chains[0].observers.metadata.type", "metadata-label"), Set.of()).status())
                .isEqualTo("PASS");
    }

    private void bundle(String directory, String id, String dependency, String kind, String name)
            throws IOException {
        String provider = "example." + name.replace("-", "") + "." + id.replace(".", "").replace("-", "")
                + ".Provider";
        String dependencies = dependency == null ? "[]" : """
                [{"id":"%s","minVersion":"0.1.0","maxVersionExclusive":"0.2.0"}]""".formatted(dependency);
        String manifest = """
                {"schemaVersion":1,"id":"%s","version":"0.1.0",
                 "yanoApi":{"min":3,"max":3,"minLevel":1},
                 "dependencies":%s,
                 "contributions":[{"kind":"%s","name":"%s","provider":"%s"}]}
                """.formatted(id, dependencies, kind, name, provider);
        Path jar = distribution.resolve(directory).resolve(id + ".jar");
        Files.createDirectories(jar.getParent());
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file)) {
            entry(zip, "META-INF/yano/plugins/" + id + ".json", manifest);
            entry(zip, "META-INF/services/" + ContributionKind.fromManifestKey(kind).serviceType().getName(),
                    provider + "\n");
            entry(zip, provider.replace('.', '/') + ".class", "");
        }
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
