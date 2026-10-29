package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yanoproject.catalog.CatalogDigests;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;

/** Plugin validators of an authenticated map, pinned by their bundle's artifact-closure digest at seal. */
final class AuthenticatedMapPluginValidators {
    static final String ALLOW_LIST = "yano.plugins.allow-list";
    private static final String SHA256_PREFIX = "sha256:";
    private static final byte[] EMPTY_CBOR_MAP = {(byte) 0xa0};
    private static final ObjectMapper JSON = new ObjectMapper();

    private AuthenticatedMapPluginValidators() {
    }

    static AuthenticatedMapContract.ValidatorDescriptor descriptor(
            AppChainProjectModel.AuthenticatedMapPluginValidatorIntent validator, Map<String, Path> bundles) {
        if (validator == null || blank(validator.id()) || blank(validator.provider()) || blank(validator.bundle())) {
            throw new IllegalArgumentException("authenticatedMap validators require id, provider and bundle");
        }
        Path jar = bundles.get(validator.bundle());
        if (jar == null) {
            throw new IllegalArgumentException("authenticatedMap validator " + validator.id() + " needs plugin bundle "
                    + validator.bundle() + ", which is not installed");
        }
        try {
            String closure = CatalogDigests.artifactClosure(List.of(CatalogDigests.artifact(jar))).value();
            return AuthenticatedMapContract.ValidatorDescriptor.plugin(validator.id(), validator.provider(),
                    HexFormat.of().parseHex(closure.substring(SHA256_PREFIX.length())), EMPTY_CBOR_MAP);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read plugin bundle " + jar, error);
        }
    }

    /** Bundle id → JAR for every plugin bundle in {@code directory} with a readable manifest. */
    static Map<String, Path> bundles(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("authenticatedMap plugin validators require the plugins directory");
        }
        Map<String, Path> bundles = new TreeMap<>();
        try (var files = Files.list(directory)) {
            for (Path jar : files.filter(path -> path.toString().endsWith(".jar")).sorted().toList()) {
                String id = bundleId(jar);
                if (id != null) bundles.put(id, jar);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("cannot list plugin bundles in " + directory, error);
        }
        return bundles;
    }

    private static String bundleId(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            var entry = file.stream().filter(item -> item.getName().startsWith("META-INF/yano/plugins/")
                    && item.getName().endsWith(".json")).findFirst().orElse(null);
            if (entry == null) return null;
            try (InputStream stream = file.getInputStream(entry)) {
                JsonNode manifest = JSON.readTree(stream);
                String id = manifest.path("id").asText(null);
                return blank(id) ? null : id;
            }
        } catch (IOException error) {
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
