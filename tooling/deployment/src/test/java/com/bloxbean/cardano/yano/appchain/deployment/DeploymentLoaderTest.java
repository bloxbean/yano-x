package com.bloxbean.cardano.yano.appchain.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentLoaderTest {
    @Test
    void rejectsDuplicateYamlKeys(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("deployment.yaml"), """
                apiVersion: one
                apiVersion: two
                kind: YanoClusterDeployment
                """);
        assertThatThrownBy(() -> new DeploymentLoader().load(directory))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Duplicate field");
    }

    @Test
    void rejectsSymlinkManifest(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("source.yml");
        Files.writeString(source, "kind: test\n");
        Files.createSymbolicLink(directory.resolve("deployment.yaml"), source);
        assertThatThrownBy(() -> new DeploymentLoader().load(directory))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a regular file");
    }
}
