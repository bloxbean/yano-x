package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The capability catalog names each artifact's runtime bundle exactly as the artifact inventory does. */
class AppChainCatalogInventoryTest {
    @Test
    void catalogBundleIdsMatchTheArtifactInventory() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, JsonNode> inventory = new HashMap<>();
        Path repository = Path.of(System.getProperty("yano.test.repo-root"));
        for (JsonNode artifact : json.readTree(repository.resolve("config/artifacts-v1.json").toFile())
                .path("artifacts")) {
            inventory.put(artifact.path("artifactId").asText(), artifact);
        }
        JsonNode catalog;
        try (InputStream input = AppChainCatalogInventoryTest.class.getClassLoader()
                .getResourceAsStream("appchain-dx/v1alpha1/appchain-capability-catalog.json")) {
            catalog = json.readTree(input);
        }

        List<String> mismatches = new ArrayList<>();
        for (JsonNode artifact : catalog.path("artifacts")) {
            String id = artifact.path("id").asText();
            String bundleId = artifact.path("bundleId").asText();
            JsonNode declared = inventory.get(id);
            if (declared == null) {
                if (!bundleId.startsWith("builtin:")) mismatches.add(id + " is not in the artifact inventory");
                continue;
            }
            String expected = "runtime-plugin".equals(declared.path("publicationType").asText())
                    ? declared.path("pluginBundleId").asText() : null;
            if (expected != null ? !expected.equals(bundleId) : !bundleId.startsWith("library:")) {
                mismatches.add(id + " names " + bundleId + ", inventory says "
                        + (expected == null ? "a library" : expected));
            }
        }
        assertThat(mismatches).isEmpty();
    }
}
