package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedMapPluginValidatorsTest {
    @TempDir
    Path plugins;

    @Test
    void aPluginValidatorIsPinnedByItsBundlesArtifactClosure() throws Exception {
        Path jar = bundle("org.example.validators", "validators.jar", "first build");
        bundle("org.example.other", "other.jar", "other");
        Files.writeString(plugins.resolve("no-manifest.jar"), "not a jar");

        Map<String, Path> bundles = AuthenticatedMapPluginValidators.bundles(plugins);
        assertThat(bundles).containsOnlyKeys("org.example.other", "org.example.validators");
        var intent = new AppChainProjectModel.AuthenticatedMapPluginValidatorIntent(
                "gtin-v1", "gs1-gtin-v1", "org.example.validators");
        AuthenticatedMapContract.ValidatorDescriptor descriptor =
                AuthenticatedMapPluginValidators.descriptor(intent, bundles);
        assertThat(descriptor.id()).isEqualTo("gtin-v1");
        assertThat(descriptor.providerId()).isEqualTo("gs1-gtin-v1");
        assertThat(descriptor.definition()).hasSize(32)
                .isEqualTo(AuthenticatedMapPluginValidators.descriptor(intent, bundles).definition());

        // Another build of the bundle is another validator: the genesis names its exact artifact.
        Files.delete(jar);
        bundle("org.example.validators", "validators.jar", "second build");
        assertThat(AuthenticatedMapPluginValidators.descriptor(intent, AuthenticatedMapPluginValidators.bundles(plugins))
                .definition()).isNotEqualTo(descriptor.definition());
    }

    @Test
    void aValidatorNeedsAnInstalledBundle() {
        var intent = new AppChainProjectModel.AuthenticatedMapPluginValidatorIntent(
                "gtin-v1", "gs1-gtin-v1", "org.example.absent");
        assertThatThrownBy(() -> AuthenticatedMapPluginValidators.descriptor(intent, Map.of()))
                .hasMessageContaining("needs plugin bundle org.example.absent, which is not installed");
        assertThatThrownBy(() -> AuthenticatedMapPluginValidators.descriptor(
                new AppChainProjectModel.AuthenticatedMapPluginValidatorIntent("gtin-v1", null, "x"), Map.of()))
                .hasMessageContaining("require id, provider and bundle");
        assertThatThrownBy(() -> AuthenticatedMapPluginValidators.bundles(plugins.resolve("missing")))
                .hasMessageContaining("require the plugins directory");
    }

    private Path bundle(String id, String file, String content) throws IOException {
        Path jar = plugins.resolve(file);
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
            zip.putNextEntry(new JarEntry("META-INF/yano/plugins/" + id + ".json"));
            zip.write(("{\"id\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new JarEntry("content.txt"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }
}
