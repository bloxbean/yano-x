package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;

/** The extracted Yano X JVM distribution this tool belongs to: launcher, plugin bundles and versions. */
record Distribution(Path home) {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code YANO_HOME}, else the distribution that contains this tool ({@code tools/yano-appchain}). */
    static Distribution locate() {
        String configured = System.getenv("YANO_HOME");
        if (configured != null && !configured.isBlank()) return new Distribution(Path.of(configured).toAbsolutePath());
        String app = System.getenv("APP_HOME");
        if (app != null && !app.isBlank()) {
            Path candidate = Path.of(app).toAbsolutePath().normalize().getParent();
            if (candidate != null && candidate.getParent() != null
                    && Files.isRegularFile(candidate.getParent().resolve("yano.sh"))) {
                return new Distribution(candidate.getParent());
            }
        }
        throw CliException.failure("Cannot find the Yano X distribution; set YANO_HOME to the extracted "
                + "yano-x-jvm-<version> directory");
    }

    Path launcher() {
        return home.resolve("yano.sh");
    }

    Path plugins() {
        return home.resolve("plugins");
    }

    Path bindingStarters() {
        return home.resolve("examples").resolve("bindings");
    }

    Path devnetGenesisTemplate() {
        return home.resolve("config/network/devnet/shelley-genesis.json");
    }

    String yanoVersion() {
        return version("yano-distribution-v1.json", "version");
    }

    String yanoXVersion() {
        return version("yano-x-distribution-v1.json", "version");
    }

    void requireLauncher() {
        if (!Files.isRegularFile(launcher())) {
            throw CliException.failure("No yano.sh in " + home + "; set YANO_HOME to the extracted Yano X "
                    + "distribution (the jlink image can create and seal, but cannot run a node)");
        }
    }

    /** Plugin bundle id → SHA-256 of its JAR, for every bundle in {@code plugins/}. */
    Map<String, String> bundleDigests() {
        Map<String, String> digests = new TreeMap<>();
        if (!Files.isDirectory(plugins())) return digests;
        try (var files = Files.list(plugins())) {
            for (Path jar : files.filter(path -> path.toString().endsWith(".jar")).sorted().toList()) {
                String id = bundleId(jar);
                if (id != null) digests.put(id, sha256(jar));
            }
        } catch (IOException error) {
            throw CliException.failure("Cannot read plugin bundles in " + plugins() + ": " + error.getMessage());
        }
        return digests;
    }

    private static String bundleId(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            var entry = file.stream().filter(item -> item.getName().startsWith("META-INF/yano/plugins/")
                    && item.getName().endsWith(".json")).findFirst().orElse(null);
            if (entry == null) return null;
            try (InputStream stream = file.getInputStream(entry)) {
                JsonNode manifest = JSON.readTree(stream);
                String id = manifest.path("id").asText(null);
                return id == null || id.isBlank() ? null : id;
            }
        } catch (IOException error) {
            return null;
        }
    }

    static String sha256(Path file) {
        try (InputStream stream = new DigestInputStream(Files.newInputStream(file),
                MessageDigest.getInstance("SHA-256"))) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(((DigestInputStream) stream).getMessageDigest().digest());
        } catch (IOException | NoSuchAlgorithmException error) {
            throw CliException.failure("Cannot read " + file + ": " + error.getMessage());
        }
    }

    private String version(String file, String... fields) {
        Path path = home.resolve(file);
        if (!Files.isRegularFile(path)) return "unknown";
        try {
            JsonNode node = JSON.readTree(path.toFile());
            for (String field : fields) {
                if (node.hasNonNull(field)) return node.get(field).asText();
            }
            return "unknown";
        } catch (IOException error) {
            return "unknown";
        }
    }
}
