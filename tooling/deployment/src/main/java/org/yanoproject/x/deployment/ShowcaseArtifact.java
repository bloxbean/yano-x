package org.yanoproject.x.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ShowcaseArtifact {
    private static final long MAX_ARCHIVE_BYTES = 8L * 1024 * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 16L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 50_000;
    private static final long MAX_JSON_ENTRY_BYTES = 64L * 1024 * 1024;
    // The showcase ships inside the Yano X JVM distribution at examples/showcase.
    // Its yano/ home holds only the demo configuration; the runtime is the
    // distribution root.
    static final String SHOWCASE = "examples/showcase/";
    static final String SHOWCASE_APPLICATION = SHOWCASE + "yano/config/application-appchain.yml";
    private static final Set<String> REQUIRED = Set.of(
            SHOWCASE + "showcase.sh",
            SHOWCASE + "catalog/showcase-catalog-v1.json",
            SHOWCASE_APPLICATION,
            "yano.sh",
            "yano.jar",
            "yano-distribution-v1.json",
            "yano-x-distribution-v1.json",
            "yano-x-plugin-pack-v1.json",
            "sbom/yano.cdx.json",
            "sbom/yano-x.cdx.json");
    private static final String SETTLEMENT_CHAIN = "payment-chain-settlement";

    private final ObjectMapper json = new ObjectMapper();

    Metadata inspect(Path archive) throws IOException {
        Path file = archive.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("showcase archive is missing or is not a regular file");
        }
        long archiveSize = Files.size(file);
        if (archiveSize == 0 || archiveSize > MAX_ARCHIVE_BYTES) {
            throw new IOException("showcase archive is empty or exceeds 8 GiB");
        }
        String sha256 = digest(file);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            TreeSet<String> roots = new TreeSet<>();
            Set<String> relativeEntries = new TreeSet<>();
            long expanded = 0;
            int count = 0;
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                if (++count > MAX_ENTRIES) {
                    throw new IOException("showcase archive contains too many entries");
                }
                String name = entry.getName();
                validateEntryName(name);
                int slash = name.indexOf('/');
                if (slash <= 0) {
                    throw new IOException("showcase archive must have exactly one root directory");
                }
                roots.add(name.substring(0, slash));
                if (!entry.isDirectory()) {
                    relativeEntries.add(name.substring(slash + 1));
                    if (entry.getSize() < 0) {
                        throw new IOException("showcase archive contains an entry with unknown size: " + name);
                    }
                    expanded = Math.addExact(expanded, entry.getSize());
                    if (expanded > MAX_EXPANDED_BYTES) {
                        throw new IOException("showcase archive expands beyond 16 GiB");
                    }
                }
            }
            if (roots.size() != 1 || !roots.first().startsWith("yano-x-jvm-")) {
                throw new IOException("showcase archive root must be exactly one yano-x-jvm-* directory");
            }
            if (!relativeEntries.containsAll(REQUIRED)) {
                TreeSet<String> missing = new TreeSet<>(REQUIRED);
                missing.removeAll(relativeEntries);
                throw new IOException("showcase archive is missing required entries: " + missing);
            }
            String root = roots.first();
            JsonNode catalog = readJson(zip, root + "/" + SHOWCASE + "catalog/showcase-catalog-v1.json");
            List<String> chains = new ArrayList<>();
            catalog.path("chains").forEach(chain -> chains.add(chain.path("chainId").asText()));
            if (catalog.path("schemaVersion").asInt() != 1 || chains.size() != 13
                    || !chains.contains(SETTLEMENT_CHAIN)) {
                throw new IOException("showcase catalog must be schema v1 with the expected 13-chain profile");
            }
            JsonNode yano = readJson(zip, root + "/yano-distribution-v1.json");
            JsonNode yanoX = readJson(zip, root + "/yano-x-distribution-v1.json");
            validateDistributionIdentities(yano, yanoX);
            validateSbom(readJson(zip, root + "/sbom/yano.cdx.json"), "Yano");
            validateSbom(readJson(zip, root + "/sbom/yano-x.cdx.json"), "Yano X");
            Map<String, String> pluginChecksums = validatePluginPack(zip, root, yanoX);
            return new Metadata(file, sha256, archiveSize, root, List.copyOf(chains),
                    digestJson(yano), digestJson(yanoX), pluginChecksums);
        } catch (ArithmeticException failure) {
            throw new IOException("showcase archive expanded-size overflow", failure);
        }
    }

    Metadata importInto(DeploymentDocument document, Path source) throws IOException {
        Metadata metadata = inspect(source);
        Path cache = document.directory().resolve("artifacts/sha256");
        Files.createDirectories(cache);
        Path target = cache.resolve(metadata.sha256() + ".zip");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !metadata.sha256().equals(digest(target))) {
                throw new IOException("content-addressed artifact target exists with different content");
            }
        } else {
            Path temporary = cache.resolve(metadata.sha256() + ".zip.part");
            Files.copy(metadata.path(), temporary, StandardCopyOption.COPY_ATTRIBUTES);
            if (!metadata.sha256().equals(digest(temporary))) {
                Files.deleteIfExists(temporary);
                throw new IOException("showcase archive changed while it was imported");
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        }
        ObjectNode runtime = (ObjectNode) document.root().withObject("/spec/runtime");
        ObjectNode artifact = runtime.withObject("artifact");
        artifact.put("kind", "local-showcase-zip");
        artifact.put("file", document.directory().relativize(target).toString());
        artifact.put("sha256", metadata.sha256());
        writeLock(document, metadata, target);
        return new Metadata(target, metadata.sha256(), metadata.bytes(), metadata.rootDirectory(),
                metadata.chains(), metadata.yanoIdentitySha256(), metadata.yanoXIdentitySha256(),
                metadata.pluginBundleSha256());
    }

    void verifyImportLock(DeploymentDocument document, Metadata metadata) throws IOException {
        Path lockPath = document.directory().resolve("artifact.lock.json");
        if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("artifact.lock.json is missing or is not a regular file; import the Yano X JVM ZIP");
        }
        JsonNode lock = json.readTree(lockPath.toFile());
        String expectedFile = document.directory().relativize(metadata.path()).toString();
        if (lock.path("schemaVersion").asInt() != 1
                || !"YanoShowcaseArtifactLock".equals(lock.path("kind").asText())
                || !expectedFile.equals(lock.path("file").asText())
                || !metadata.sha256().equals(lock.path("sha256").asText())
                || metadata.bytes() != lock.path("bytes").asLong(-1)
                || !metadata.rootDirectory().equals(lock.path("archiveRoot").asText())
                || !metadata.yanoIdentitySha256().equals(lock.path("yanoIdentitySha256").asText())
                || !metadata.yanoXIdentitySha256().equals(lock.path("yanoXIdentitySha256").asText())) {
            throw new IOException("artifact.lock.json does not match the imported showcase ZIP");
        }
        List<String> lockedChains = new ArrayList<>();
        lock.path("chainIds").forEach(chain -> lockedChains.add(chain.asText()));
        if (!metadata.chains().equals(lockedChains)) {
            throw new IOException("artifact.lock.json chain catalog does not match the imported showcase ZIP");
        }
        Map<String, String> lockedPlugins = new TreeMap<>();
        lock.path("pluginBundleSha256").fields().forEachRemaining(entry ->
                lockedPlugins.put(entry.getKey(), entry.getValue().asText()));
        if (!metadata.pluginBundleSha256().equals(lockedPlugins)) {
            throw new IOException("artifact.lock.json plugin checksums do not match the imported showcase ZIP");
        }
    }

    byte[] readEntry(Metadata metadata, String relativeName) throws IOException {
        try (ZipFile zip = new ZipFile(metadata.path().toFile())) {
            ZipEntry entry = zip.getEntry(metadata.rootDirectory() + "/" + relativeName);
            if (entry == null || entry.isDirectory() || entry.getSize() > DeploymentLoader.MAX_MANIFEST_BYTES) {
                throw new IOException("archive entry is absent or too large: " + relativeName);
            }
            try (InputStream input = zip.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    String digestEntry(Metadata metadata, String relativeName) throws IOException {
        try (ZipFile zip = new ZipFile(metadata.path().toFile())) {
            return entryDigest(zip, metadata.rootDirectory() + "/" + relativeName);
        }
    }

    static String digest(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void writeLock(DeploymentDocument document, Metadata metadata, Path target) throws IOException {
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("schemaVersion", 1);
        lock.put("kind", "YanoShowcaseArtifactLock");
        lock.put("file", document.directory().relativize(target).toString());
        lock.put("sha256", metadata.sha256());
        lock.put("bytes", metadata.bytes());
        lock.put("archiveRoot", metadata.rootDirectory());
        lock.put("chainIds", metadata.chains());
        lock.put("excludedChainIds", List.of());
        lock.put("excludedIntegrations", List.of(
                "kafka", "objectstore-s3", "ipfs", "evidence-profile", "evidence-registry",
                "effects-cardano", "eutxo-zk"));
        lock.put("yanoIdentitySha256", metadata.yanoIdentitySha256());
        lock.put("yanoXIdentitySha256", metadata.yanoXIdentitySha256());
        lock.put("pluginBundleSha256", metadata.pluginBundleSha256());
        json.writerWithDefaultPrettyPrinter().writeValue(
                document.directory().resolve("artifact.lock.json").toFile(), lock);
    }

    private JsonNode readJson(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() < 0
                || entry.getSize() > MAX_JSON_ENTRY_BYTES) {
            throw new IOException("required JSON entry is missing or too large: " + name);
        }
        try (InputStream input = zip.getInputStream(entry)) {
            return json.readTree(input);
        }
    }

    private void validateDistributionIdentities(JsonNode yano, JsonNode yanoX) throws IOException {
        if (yano.path("schemaVersion").asInt() != 1
                || !"yano".equals(yano.path("product").asText())
                || !"core-jvm".equals(yano.path("distribution").asText())
                || yano.path("version").asText().isBlank()
                || !yano.path("pluginDirectorySupported").asBoolean()) {
            throw new IOException("embedded Yano JVM distribution identity is invalid");
        }
        if (yanoX.path("schemaVersion").asInt() != 1
                || !"yano-x".equals(yanoX.path("product").asText())
                || !"jvm".equals(yanoX.path("distribution").asText())
                || yanoX.path("version").asText().isBlank()
                || !yano.path("version").asText().equals(yanoX.path("yanoVersion").asText())
                || yanoX.path("nativeImageSupported").asBoolean(true)
                || !hexDigest(yanoX.path("baseDistributionSha256").asText())
                || !hexDigest(yanoX.path("pluginPackManifestSha256").asText())) {
            throw new IOException("embedded Yano X distribution identity is invalid or mismatched with Yano");
        }
    }

    private void validateSbom(JsonNode sbom, String product) throws IOException {
        if (!"CycloneDX".equals(sbom.path("bomFormat").asText())
                || sbom.path("specVersion").asText().isBlank()
                || !sbom.path("components").isArray()) {
            throw new IOException(product + " CycloneDX SBOM is invalid");
        }
    }

    private Map<String, String> validatePluginPack(ZipFile zip, String root, JsonNode yanoX) throws IOException {
        String manifestPath = root + "/yano-x-plugin-pack-v1.json";
        JsonNode manifest = readJson(zip, manifestPath);
        if (manifest.path("schemaVersion").asInt() != 1
                || !"yano-x".equals(manifest.path("product").asText())
                || !yanoX.path("version").asText().equals(manifest.path("version").asText())
                || !manifest.path("bundles").isArray()
                || manifest.path("bundles").size() != yanoX.path("availablePluginBundleCount").asInt(-1)
                || !entryDigest(zip, manifestPath).equals(yanoX.path("pluginPackManifestSha256").asText())) {
            throw new IOException("Yano X plugin-pack manifest is invalid or mismatched with distribution identity");
        }
        Map<String, String> checksums = new TreeMap<>();
        for (JsonNode bundle : manifest.path("bundles")) {
            String file = bundle.path("file").asText();
            String installMode = bundle.path("installMode").asText();
            if (!file.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar")
                    || !hexDigest(bundle.path("sha256").asText())) {
                throw new IOException("plugin-pack manifest contains an invalid bundle record");
            }
            String directory = "optional".equals(installMode) ? "optional-plugins" : "plugins";
            String path = root + "/" + directory + "/" + file;
            if (!bundle.path("sha256").asText().equals(entryDigest(zip, path))) {
                throw new IOException("plugin bundle checksum differs from manifest: " + file);
            }
            checksums.put(directory + "/" + file, bundle.path("sha256").asText());
        }
        return java.util.Collections.unmodifiableMap(checksums);
    }

    private String entryDigest(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("required archive entry is missing: " + name);
        }
        MessageDigest digest = sha256();
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean hexDigest(String value) {
        return value.matches("[0-9a-f]{64}");
    }

    private String digestJson(JsonNode node) throws IOException {
        ObjectMapper canonical = new ObjectMapper();
        canonical.configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        canonical.configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        return HexFormat.of().formatHex(sha256().digest(canonical.writeValueAsBytes(node)));
    }

    private static void validateEntryName(String name) throws IOException {
        if (name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.indexOf('\0') >= 0) {
            throw new IOException("showcase archive contains an unsafe entry name");
        }
        Path normalized = Path.of(name).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..") || name.contains("\\")) {
            throw new IOException("showcase archive contains a path-traversal entry: " + name);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    record Metadata(
            Path path,
            String sha256,
            long bytes,
            String rootDirectory,
            List<String> chains,
            String yanoIdentitySha256,
            String yanoXIdentitySha256,
            Map<String, String> pluginBundleSha256) {
    }
}
