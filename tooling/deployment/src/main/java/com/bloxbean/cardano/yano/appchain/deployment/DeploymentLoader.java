package com.bloxbean.cardano.yano.appchain.deployment;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class DeploymentLoader {
    static final long MAX_MANIFEST_BYTES = 1024 * 1024;
    private final ObjectMapper yaml;

    DeploymentLoader() {
        YAMLFactory factory = YAMLFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        yaml = new ObjectMapper(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    DeploymentDocument load(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        Path manifest = normalized.resolve("deployment.yaml");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("deployment.yaml is missing or is not a regular file");
        }
        long size = Files.size(manifest);
        if (size == 0 || size > MAX_MANIFEST_BYTES) {
            throw new IOException("deployment.yaml must be between 1 byte and 1 MiB");
        }
        JsonNode root = yaml.readTree(Files.newInputStream(manifest));
        if (root == null || !root.isObject()) {
            throw new IOException("deployment.yaml must contain one YAML object");
        }
        return new DeploymentDocument(normalized, root);
    }

    void write(DeploymentDocument document) throws IOException {
        yaml.writerWithDefaultPrettyPrinter().writeValue(
                document.directory().resolve("deployment.yaml").toFile(), document.root());
    }
}
