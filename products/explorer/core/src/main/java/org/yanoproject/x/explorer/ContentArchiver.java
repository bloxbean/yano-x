package org.yanoproject.x.explorer;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The content archiver of ADR-050 §2.3: bodies are verified against the committed entry hash
 * before they are stored under their SHA-256, and a mismatch is recorded, never stored. Fetches
 * follow only caller allow-listed URL prefixes, never redirects, and are bounded in size.
 */
public final class ContentArchiver {
    public static final long MAX_BODY_BYTES = 16L * 1024 * 1024;
    public static final String MATCHED = "MATCHED";
    public static final String MISMATCH = "MISMATCH";
    public static final String UNBOUND = "UNBOUND";
    private static final HexFormat HEX = HexFormat.of();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final IndexStore store;
    private final Path directory;
    private final HttpClient http;

    public ContentArchiver(IndexStore store, Path directory) {
        this.store = Objects.requireNonNull(store, "store");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Path directory() {
        return directory;
    }

    /**
     * Verifies and stores one body. With an expected entry hash the body must hash to it under
     * SHA-256 or Blake2b-256; a mismatch is recorded and the body discarded.
     */
    public IndexStore.ContentRecord add(byte[] body, String source, String expectedEntryHashHex) {
        Objects.requireNonNull(body, "body");
        if (body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new ExplorerException(ExplorerException.Error.USAGE,
                    "content must contain 1-" + MAX_BODY_BYTES + " bytes");
        }
        String sha256 = HEX.formatHex(sha256(body));
        String blake2b = HEX.formatHex(Blake2bUtil.blake2bHash256(body));
        String expected = expectedEntryHashHex == null ? "" : expectedEntryHashHex.trim().toLowerCase();
        String status;
        if (expected.isEmpty()) {
            status = UNBOUND;
        } else if (expected.equals(sha256) || expected.equals(blake2b)) {
            status = MATCHED;
        } else {
            status = MISMATCH;
        }
        IndexStore.ContentRecord record = new IndexStore.ContentRecord(sha256, blake2b, body.length,
                bounded(source), status, expected, System.currentTimeMillis());
        if (!MISMATCH.equals(status)) {
            write(sha256, body);
        }
        store.putContent(record);
        return record;
    }

    /** Fetches a reference that matches one of the allow-listed prefixes, then {@link #add}. */
    public IndexStore.ContentRecord fetch(String url, List<String> allowedPrefixes, String expectedEntryHashHex) {
        String reference = Objects.requireNonNull(url, "url").trim();
        if (allowedPrefixes == null || allowedPrefixes.stream().noneMatch(prefix ->
                !prefix.isBlank() && reference.startsWith(prefix.trim()))) {
            throw new ExplorerException(ExplorerException.Error.USAGE,
                    "the reference is outside the allow-listed prefixes");
        }
        if (!reference.startsWith("http://") && !reference.startsWith("https://")) {
            throw new ExplorerException(ExplorerException.Error.USAGE, "only http and https references are fetched");
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(reference)).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | IllegalArgumentException failure) {
            throw new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                    "reference unreachable: " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExplorerException(ExplorerException.Error.UNAVAILABLE, "fetch interrupted");
        }
        if (response.statusCode() != 200) {
            throw new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                    "reference returned HTTP " + response.statusCode());
        }
        if (response.body().length > MAX_BODY_BYTES) {
            throw new ExplorerException(ExplorerException.Error.INVALID, "reference body exceeds the size bound");
        }
        return add(response.body(), reference, expectedEntryHashHex);
    }

    public Optional<byte[]> read(String sha256Hex) {
        if (sha256Hex == null || !sha256Hex.matches("[0-9a-f]{64}")) return Optional.empty();
        Path file = directory.resolve(sha256Hex + ".bin");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            byte[] body = Files.readAllBytes(file);
            if (!HEX.formatHex(sha256(body)).equals(sha256Hex)) {
                throw new ExplorerException(ExplorerException.Error.INVALID,
                        "archived content " + sha256Hex + " no longer hashes to its name");
            }
            return Optional.of(body);
        } catch (IOException failure) {
            throw new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                    "cannot read archived content: " + failure.getMessage(), failure);
        }
    }

    /** The record whose SHA-256 or Blake2b-256 equals the entry hash, when archived. */
    public Optional<IndexStore.ContentRecord> forEntryHash(String entryHashHex) {
        if (entryHashHex == null || !entryHashHex.matches("[0-9a-f]{64}")) return Optional.empty();
        return store.contentForEntryHash(entryHashHex);
    }

    private void write(String sha256, byte[] body) {
        try {
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
                restrict(directory, "rwx------");
            }
            Path file = directory.resolve(sha256 + ".bin");
            if (!Files.exists(file)) {
                Path temporary = Files.createTempFile(directory, "content-", ".tmp");
                Files.write(temporary, body);
                restrict(temporary, "rw-------");
                Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException failure) {
            throw new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                    "cannot store content: " + failure.getMessage(), failure);
        }
    }

    static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String bounded(String source) {
        String value = source == null ? "" : source.trim();
        return value.length() > 512 ? value.substring(0, 512) : value;
    }

    private static void restrict(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX file system
        }
    }
}
