package com.bloxbean.cardano.yano.appchain.deployment;

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
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ShowcaseArtifact {
    private static final long MAX_ARCHIVE_BYTES = 8L * 1024 * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 16L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 50_000;
    private static final Set<String> REQUIRED = Set.of(
            "showcase.sh",
            "catalog/showcase-catalog-v1.json",
            "yano/yano.sh",
            "yano/yano.jar",
            "yano/config/application-appchain.yml",
            "yano/yano-distribution-v1.json",
            "yano/yano-x-distribution-v1.json");
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
            if (roots.size() != 1 || !roots.first().startsWith("yano-showcase-")) {
                throw new IOException("showcase archive root must be exactly one yano-showcase-* directory");
            }
            if (!relativeEntries.containsAll(REQUIRED)) {
                TreeSet<String> missing = new TreeSet<>(REQUIRED);
                missing.removeAll(relativeEntries);
                throw new IOException("showcase archive is missing required entries: " + missing);
            }
            String root = roots.first();
            JsonNode catalog = readJson(zip, root + "/catalog/showcase-catalog-v1.json");
            List<String> chains = new ArrayList<>();
            catalog.path("chains").forEach(chain -> chains.add(chain.path("chainId").asText()));
            if (catalog.path("schemaVersion").asInt() != 1 || chains.size() != 13
                    || !chains.contains(SETTLEMENT_CHAIN)) {
                throw new IOException("showcase catalog must be schema v1 with the expected 13-chain profile");
            }
            JsonNode yano = readJson(zip, root + "/yano/yano-distribution-v1.json");
            JsonNode yanoX = readJson(zip, root + "/yano/yano-x-distribution-v1.json");
            return new Metadata(file, sha256, archiveSize, root, List.copyOf(chains),
                    digestJson(yano), digestJson(yanoX));
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
                metadata.chains(), metadata.yanoIdentitySha256(), metadata.yanoXIdentitySha256());
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
        lock.put("excludedChainIds", List.of(SETTLEMENT_CHAIN));
        lock.put("excludedIntegrations", List.of("kafka", "s3-effects", "ipfs"));
        lock.put("yanoIdentitySha256", metadata.yanoIdentitySha256());
        lock.put("yanoXIdentitySha256", metadata.yanoXIdentitySha256());
        json.writerWithDefaultPrettyPrinter().writeValue(
                document.directory().resolve("artifact.lock.json").toFile(), lock);
    }

    private JsonNode readJson(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.getSize() > DeploymentLoader.MAX_MANIFEST_BYTES) {
            throw new IOException("required JSON entry is missing or too large: " + name);
        }
        try (InputStream input = zip.getInputStream(entry)) {
            return json.readTree(input);
        }
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
            String yanoXIdentitySha256) {
    }
}
