package com.bloxbean.cardano.yano.appchain.dpp.client;

import com.bloxbean.cardano.yano.appchain.dpp.profile.DppValues;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Published documents kept outside consensus state under a content directory keyed by their
 * SHA-256 (ADR-051 §2.2). A body is served only by the hash it actually has, so a version
 * record's digest either matches the served bytes or the document is reported unavailable.
 */
public final class DocumentStore {
    public static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;
    private static final HexFormat HEX = HexFormat.of();

    private final Path directory;

    public DocumentStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public Path directory() {
        return directory;
    }

    /** Stores the bytes under their hash (owner-only) and returns the hash. */
    public String put(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("document exceeds " + MAX_DOCUMENT_BYTES + " bytes");
        }
        String sha256 = HEX.formatHex(DppValues.sha256(bytes));
        Files.createDirectories(directory);
        ownerOnly(directory, "rwx------");
        Path target = directory.resolve(sha256);
        if (!Files.exists(target)) {
            Path temp = Files.createTempFile(directory, "staging-", ".tmp");
            Files.write(temp, bytes);
            ownerOnly(temp, "rw-------");
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        return sha256;
    }

    public boolean has(String sha256Hex) {
        return requireHash(sha256Hex) && Files.isRegularFile(directory.resolve(sha256Hex));
    }

    /** The bytes stored under the hash, re-hashed on read so a corrupted file is never served. */
    public Optional<byte[]> get(String sha256Hex) throws IOException {
        if (!has(sha256Hex)) {
            return Optional.empty();
        }
        Path file = directory.resolve(sha256Hex);
        if (Files.size(file) > MAX_DOCUMENT_BYTES) {
            return Optional.empty();
        }
        byte[] bytes = Files.readAllBytes(file);
        if (!HEX.formatHex(DppValues.sha256(bytes)).equals(sha256Hex)) {
            return Optional.empty();
        }
        return Optional.of(bytes);
    }

    private static boolean requireHash(String sha256Hex) {
        return sha256Hex != null && sha256Hex.matches("[0-9a-f]{64}");
    }

    private static void ownerOnly(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Permission bits are not exposed on this file system.
        }
    }
}
