package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned report envelope {@code yano-x-binding-report-v1} for editor consumption (ADR-031.2 contract C2).
 *
 * <p>A report is an unauthenticated local file. Its digests let an editor detect that a draft, context, catalog
 * or fixture has changed since the report was produced; they do not identify who produced it. The envelope never
 * contains physical post-state, local file paths or secrets. Int64 values are canonical decimal text.
 */
final class BindingReport {
    static final String SCHEMA = "yano-x-binding-report-v1";
    /** Reports larger than this are not written; the command then exits with the I/O status. */
    static final int MAX_BYTES = 16 * 1024 * 1024;
    static final String ASSURANCE = "Unauthenticated local tool output. Matching digests show that the same inputs "
            + "were used; they do not identify the producer, prove finality or approve a deployment.";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private BindingReport() { }

    /**
     * Canonical ReceiptViewV1 projection of canonical receipt bytes; the bytes remain the source of truth.
     *
     * @throws IllegalArgumentException if the bytes are not a canonical version-one receipt
     */
    static Map<String, Object> receiptView(byte[] encoded) {
        BindingReceiptV1 receipt = BindingReceiptV1.decode(encoded);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("version", 1);
        view.put("sourceMessageIdHex", HexFormat.of().formatHex(receipt.sourceMessageId()));
        view.put("height", Long.toString(receipt.height()));
        view.put("status", receipt.accepted() ? "ACCEPTED" : "REJECTED");
        view.put("failedStepOrdinal", receipt.failedStepOrdinal());
        view.put("code", receipt.code());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (BindingReceiptV1.Step step : receipt.steps()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("ordinal", step.ordinal());
            value.put("depth", step.depth());
            value.put("bindingId", step.bindingId());
            value.put("targetComponentId", step.targetComponentId());
            value.put("messageIdHex", HexFormat.of().formatHex(step.messageId()));
            value.put("eventsProduced", step.eventsProduced());
            value.put("conditions", step.conditions().stream().map(condition -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("bindingId", condition.bindingId());
                item.put("failedClause", condition.failedClause());
                return item;
            }).toList());
            value.put("status", step.status());
            value.put("code", step.code());
            value.put("rawBody", step.rawBody());
            steps.add(value);
        }
        view.put("steps", steps);
        return view;
    }

    /**
     * Canonical digest of complete physical rehearsal state: SHA-256 over the ASCII domain
     * {@code yano-x-binding-post-state-v1\0} followed, for every entry in ascending key order, by the four-byte
     * big-endian key length, key bytes, four-byte value length and value bytes. It chains consecutive reports;
     * it is not a state root and authenticates nothing.
     */
    static String postStateDigest(List<BindingDryRun.Entry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("yano-x-binding-post-state-v1\0".getBytes(StandardCharsets.US_ASCII));
            List<BindingDryRun.Entry> sorted = new ArrayList<>(entries);
            sorted.sort((left, right) -> left.keyHex().toLowerCase().compareTo(right.keyHex().toLowerCase()));
            for (BindingDryRun.Entry entry : sorted) {
                byte[] key = HexFormat.of().parseHex(entry.keyHex());
                byte[] value = HexFormat.of().parseHex(entry.valueHex());
                digest.update(ByteBuffer.allocate(4).putInt(key.length).array());
                digest.update(key);
                digest.update(ByteBuffer.allocate(4).putInt(value.length).array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Serializes a report deterministically, failing closed above {@link #MAX_BYTES}. */
    static byte[] encode(Map<String, Object> report) throws IOException {
        byte[] bytes = (JSON.writeValueAsString(report) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("binding report exceeds " + MAX_BYTES + " bytes");
        return bytes;
    }

    /**
     * Writes the report through a same-directory temporary file with owner-only permissions where the file system
     * supports them, then moves it into place so no partial report is observable and a pre-existing symbolic link
     * at the target is replaced rather than followed.
     */
    static void write(Path target, byte[] bytes) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path directory = absolute.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IOException("report directory does not exist");
        }
        if (Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException("report path is a directory");
        Path temporary;
        try {
            temporary = Files.createTempFile(directory, ".binding-report-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException nonPosix) {
            temporary = Files.createTempFile(directory, ".binding-report-", ".tmp");
        }
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Rejects report locations that would overwrite an input or modify the plugin directory. Comparisons use
     * normalized absolute paths and, when both files exist, file identity.
     */
    static void requireSafeTarget(Path report, List<Path> inputs, Path pluginDirectory) throws IOException {
        Path target = report.toAbsolutePath().normalize();
        for (Path input : inputs) {
            if (input == null) continue;
            Path candidate = input.toAbsolutePath().normalize();
            if (candidate.equals(target) || Files.exists(target) && Files.exists(candidate)
                    && Files.isSameFile(candidate, target)) {
                throw new IllegalArgumentException("--report must not name an input file");
            }
        }
        if (pluginDirectory != null) {
            Path plugins = pluginDirectory.toAbsolutePath().normalize();
            Path real = Files.exists(plugins) ? plugins.toRealPath() : plugins;
            Path parent = target.getParent();
            Path realParent = parent != null && Files.exists(parent) ? parent.toRealPath() : parent;
            if (target.startsWith(plugins) || realParent != null && realParent.startsWith(real)) {
                throw new IllegalArgumentException("--report must not be inside the plugin directory");
            }
        }
    }
}
