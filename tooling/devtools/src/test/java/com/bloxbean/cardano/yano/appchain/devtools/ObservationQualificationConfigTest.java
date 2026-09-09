package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainConfigParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationQualificationConfigTest {
    @Test
    void freshFixturePinsFiveValidatorsAndKeepsSecretsOutOfPublicManifest(@TempDir Path directory)
            throws Exception {
        Path host = host(directory);
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        plugin(plugins.resolve("stdlib.jar"), "com.bloxbean.cardano.yano.appchain.stdlib", "fixture-dependency");
        plugin(plugins.resolve("dependency.jar"), "fixture-dependency", null);
        Path target = directory.resolve("qualification");
        ObservationQualificationConfig.prepare(target, host, plugins, "fixture-version", 18070, 18337);
        String manifestText = Files.readString(target.resolve("qualification.json"));
        var manifest = new ObjectMapper().readTree(manifestText);
        assertThat(manifest.path("validators").size()).isEqualTo(5);
        assertThat(manifest.path("reporters").size()).isEqualTo(5);
        assertThat(manifest.path("effectiveGenesisId").asText()).hasSize(64)
                .isNotEqualTo(manifest.path("chainSettings").path("state.genesis-id").asText());
        assertThat(Files.getPosixFilePermissions(target))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        for (int index = 0; index < 5; index++) {
            Path configFile = target.resolve("node-" + index + "/node.properties");
            Properties config = read(configFile);
            assertThat(Files.getPosixFilePermissions(configFile))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
            var chain = new LinkedHashMap<String, String>();
            String prefix = "yano.app-chain.chains[0].";
            config.stringPropertyNames().stream().filter(key -> key.startsWith(prefix))
                    .forEach(key -> chain.put(key.substring(prefix.length()), config.getProperty(key)));
            var parsed = AppChainConfigParser.parse(chain);
            assertThat(parsed.memberKeysHex()).hasSize(5);
            assertThat(parsed.threshold()).isEqualTo(4);
            assertThat(parsed.pluginSettings()).containsEntry("consensus.max-byzantine-members", "1")
                    .containsEntry("membership.mode", "governed")
                    .containsKey("observations.profile-cbor-hex");
            assertThat(manifestText).doesNotContain(parsed.signingKeyHex());
            assertThat(config.getProperty("quarkus.http.host")).isEqualTo("127.0.0.1");
            assertThat(config.stringPropertyNames())
                    .noneMatch(key -> key.startsWith("yano.upstream.validation."));
            assertThat(config.getProperty("yano.app-chain.api.auth.enabled")).isEqualTo("true");
            assertThat(config.getProperty("yano.plugins.allow-list"))
                    .isEqualTo("com.bloxbean.cardano.yano.appchain.stdlib,fixture-dependency");
            assertThat(chain).containsEntry("anchor.enabled", "false").containsEntry("effects.enabled", "false");
        }
        Properties secrets = read(target.resolve("reporters.private.properties"));
        secrets.values().forEach(value -> assertThat(manifestText).doesNotContain(value.toString()));
        assertThatThrownBy(() -> ObservationQualificationConfig.prepare(
                target, host, plugins, "fixture-version", 18070, 18337))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(target.resolve("qualification.json"))).isEqualTo(manifestText);

        // Retained manifests may predate the redundant effectiveGenesisId field.
        // Membership pins must derive identity from the same original chain settings.
        var validators = new ArrayList<String>();
        manifest.path("validators").forEach(key -> validators.add(key.asText()));
        var mapper = new ObjectMapper();
        Files.writeString(target.resolve("membership-epochs.json"), mapper.writeValueAsString(Map.of(
                "chainId", ObservationQualificationConfig.CHAIN_ID,
                "genesisId", manifest.path("effectiveGenesisId").asText(),
                "epochs", List.of(new ObservationQualificationMembership.Epoch(0, validators)))));
        ((ObjectNode) manifest).remove("effectiveGenesisId");
        Files.writeString(target.resolve("qualification.json"), mapper.writeValueAsString(manifest));
        assertThat(ObservationQualificationMembership.membersAt(target, validators, 53))
                .containsExactlyElementsOf(validators.stream().sorted().toList());
        ((ObjectNode) manifest).put("effectiveGenesisId", "00".repeat(32));
        Files.writeString(target.resolve("qualification.json"), mapper.writeValueAsString(manifest));
        assertThatThrownBy(() -> ObservationQualificationMembership.membersAt(target, validators, 53))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingCatalogDependencyFailsBeforeCreatingDeployment(@TempDir Path directory) throws Exception {
        Path host = host(directory);
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        plugin(plugins.resolve("stdlib.jar"), "com.bloxbean.cardano.yano.appchain.stdlib", "missing-dependency");
        Path target = directory.resolve("qualification");
        assertThatThrownBy(() -> ObservationQualificationConfig.prepare(
                target, host, plugins, "fixture-version", 18070, 18337))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing-dependency");
        assertThat(target).doesNotExist();
    }

    @Test
    void rejectsWrongHostAndOverlappingPortsBeforeCreatingDirectory(@TempDir Path directory) throws Exception {
        Path host = host(directory);
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        Path target = directory.resolve("qualification");
        assertThatThrownBy(() -> ObservationQualificationConfig.prepare(
                target, host, plugins, "wrong-version", 18070, 18337))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObservationQualificationConfig.prepare(
                target, host, plugins, "fixture-version", 18070, 18074))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(target).doesNotExist();
    }

    private static Path host(Path directory) throws Exception {
        Path host = Files.createDirectory(directory.resolve("host"));
        Files.writeString(host.resolve("yano-distribution-v1.json"),
                "{\"distribution\":\"core-jvm\",\"version\":\"fixture-version\"}");
        Files.createFile(host.resolve("yano.jar"));
        return host;
    }

    private static Properties read(Path path) throws Exception {
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(path)) {
            values.load(reader);
        }
        return values;
    }

    private static void plugin(Path path, String id, String dependency) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("META-INF/yano/plugins/" + id + ".json"));
            String dependencies = dependency == null ? "[]" : "[{\"id\":\"" + dependency + "\"}]";
            output.write(("{\"id\":\"" + id + "\",\"dependencies\":" + dependencies + "}")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
