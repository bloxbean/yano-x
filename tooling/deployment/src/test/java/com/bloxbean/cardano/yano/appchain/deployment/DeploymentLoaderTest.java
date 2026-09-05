package com.bloxbean.cardano.yano.appchain.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentLoaderTest {
    @Test
    void schemaRejectsUnknownFieldsAndInvalidCidrs(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("deployment.yaml"), """
                apiVersion: yano.bloxbean.com/v1alpha1
                kind: YanoClusterDeployment
                unexpected: true
                metadata: {name: test}
                spec:
                  identity: {clusterId: "11111111-1111-1111-1111-111111111111"}
                  l1: {network: preprod}
                  providers: [{name: internal, type: existing}]
                  nodes: []
                  consensus: {threshold: 1, sequencer: {mode: fixed, proposerNode: node-0}}
                  network: {p2pPort: 13337, httpPort: 7070}
                  runtime: {artifact: {kind: local-showcase-zip, file: "", sha256: ""}}
                  application: {}
                  access:
                    ssh: {mode: allowlist, sourceCidrs: ["127.0.0.1/24; touch /tmp/injected"]}
                    api: {exposure: disabled, sourceCidrs: []}
                """);

        DeploymentDocument document = new DeploymentLoader().load(directory);
        assertThat(document.validate(false))
                .anyMatch(error -> error.contains("unexpected"))
                .contains("invalid SSH source CIDR: 127.0.0.1/24; touch /tmp/injected");
    }

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
