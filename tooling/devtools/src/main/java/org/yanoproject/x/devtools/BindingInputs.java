package org.yanoproject.x.devtools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Records every binding tooling input in the order an invocation reads it (ADR-031.2 contract C2).
 *
 * <p>Each file is read once. The SHA-256 digest covers exactly the bounded bytes that were strictly decoded as
 * UTF-8 and passed, as text, to the unchanged parsers; nothing is re-read, re-serialized or normalized before
 * hashing. An input that was never reached keeps state {@code not-read}; an oversized file keeps state
 * {@code oversized} without a digest because only its first {@code maximum + 1} bytes were read. Existing
 * exception types and messages are preserved so command output and exit codes remain unchanged.
 */
final class BindingInputs {
    /** Roles recognized by the report contract, in the order the binding commands read them. */
    static final List<String> ROLES = List.of("document", "context", "fixture", "priorResult");

    /**
     * One input's observed state.
     *
     * @param role contract role
     * @param state {@code read}, {@code not-read} or {@code oversized}
     * @param bytes exact byte length when read, otherwise {@code -1}
     * @param sha256 lowercase hexadecimal digest of the exact bytes, or {@code null}
     */
    record Observed(String role, String state, long bytes, String sha256) { }

    private final List<Observed> observed = new ArrayList<>();

    /**
     * Reads a bounded UTF-8 file for one role and records its identity before returning its text.
     *
     * @throws IllegalArgumentException with the historical message when the file exceeds {@code maximum} bytes
     * @throws IOException when the file cannot be read or is not valid UTF-8
     */
    String read(String role, Path path, int maximum) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(maximum + 1);
        }
        if (bytes.length > maximum) {
            observed.add(new Observed(role, "oversized", -1, null));
            throw new IllegalArgumentException("binding input file exceeds size limit");
        }
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        observed.add(new Observed(role, "read", bytes.length, sha256(bytes)));
        return text;
    }

    /** Observations in read order, followed by {@code not-read} entries for the given expected roles. */
    List<Observed> snapshot(List<String> expectedRoles) {
        List<Observed> result = new ArrayList<>(observed);
        for (String role : expectedRoles) {
            if (result.stream().noneMatch(value -> value.role().equals(role))) {
                result.add(new Observed(role, "not-read", -1, null));
            }
        }
        return List.copyOf(result);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
