package org.yanoproject.x.proofs.commitment;

import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Portable commitment to an already finalized application block. This envelope does
 * not change AppBlock hashing and does not itself prove finality or L1 settlement.
 */
public record FinalizedAppCommitmentV1(String chainId, String commitmentProfile,
                                     byte[] formatFingerprint, byte[] genesisId,
                                     byte[] consensusContextDigest, long height,
                                     byte[] blockHash, byte[] stateRoot) {
    private static final byte[] DOMAIN = "yano-app-finalized-commitment-v1\0"
            .getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_TEXT_BYTES = 1024;
    private static final int FIXED_BYTES = DOMAIN.length + 4 + 8 + 8 + 5 * 32;

    public FinalizedAppCommitmentV1 {
        textBytes(chainId);
        textBytes(commitmentProfile);
        formatFingerprint = digestBytes(formatFingerprint);
        genesisId = digestBytes(genesisId);
        consensusContextDigest = digestBytes(consensusContextDigest);
        blockHash = digestBytes(blockHash);
        stateRoot = digestBytes(stateRoot);
        if (height <= 0) throw new IllegalArgumentException("Finalized height must be positive");
    }

    @Override public byte[] formatFingerprint() { return formatFingerprint.clone(); }
    @Override public byte[] genesisId() { return genesisId.clone(); }
    @Override public byte[] consensusContextDigest() { return consensusContextDigest.clone(); }
    @Override public byte[] blockHash() { return blockHash.clone(); }
    @Override public byte[] stateRoot() { return stateRoot.clone(); }

    /** Domain, u32 version, length-prefixed UTF-8 chain/profile, five 32-byte hashes and i64 height. */
    public byte[] encode() {
        byte[] chain = textBytes(chainId);
        byte[] profile = textBytes(commitmentProfile);
        return ByteBuffer.allocate(FIXED_BYTES + chain.length + profile.length)
                .put(DOMAIN).putInt(1).putInt(chain.length).put(chain)
                .putInt(profile.length).put(profile).put(formatFingerprint).put(genesisId)
                .put(consensusContextDigest).putLong(height).put(blockHash).put(stateRoot).array();
    }

    public byte[] digest() { return sha256(encode()); }

    public static FinalizedAppCommitmentV1 decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length < FIXED_BYTES + 2 || encoded.length > FIXED_BYTES + 2 * MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Invalid finalized commitment size");
        }
        try {
            ByteBuffer input = ByteBuffer.wrap(encoded.clone());
            byte[] domain = new byte[DOMAIN.length];
            input.get(domain);
            if (!Arrays.equals(domain, DOMAIN) || input.getInt() != 1) {
                throw new IllegalArgumentException("Unknown finalized commitment domain/version");
            }
            String chain = readText(input);
            String profile = readText(input);
            FinalizedAppCommitmentV1 commitment = new FinalizedAppCommitmentV1(chain, profile,
                    readDigest(input), readDigest(input), readDigest(input), input.getLong(),
                    readDigest(input), readDigest(input));
            if (input.hasRemaining()) throw new IllegalArgumentException("Trailing commitment bytes");
            return commitment;
        } catch (BufferUnderflowException failure) {
            throw new IllegalArgumentException("Truncated finalized commitment", failure);
        }
    }

    @Override public boolean equals(Object value) {
        return value instanceof FinalizedAppCommitmentV1 other && Arrays.equals(encode(), other.encode());
    }
    @Override public int hashCode() { return Arrays.hashCode(encode()); }

    static byte[] textBytes(String value) {
        Objects.requireNonNull(value, "identity");
        if (value.isBlank()) throw new IllegalArgumentException("Identity must not be blank");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            if (bytes.remaining() > MAX_TEXT_BYTES) throw new IllegalArgumentException("Identity is too long");
            byte[] result = new byte[bytes.remaining()];
            bytes.get(result);
            return result;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Identity is not valid Unicode", invalid);
        }
    }

    static byte[] digestBytes(byte[] value) {
        if (value == null || value.length != 32) throw new IllegalArgumentException("Expected a 32-byte digest");
        return value.clone();
    }

    static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String readText(ByteBuffer input) {
        int length = input.getInt();
        if (length < 1 || length > MAX_TEXT_BYTES || length > input.remaining()) {
            throw new IllegalArgumentException("Invalid identity length");
        }
        byte[] bytes = new byte[length];
        input.get(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Identity is not valid UTF-8", invalid);
        }
    }

    private static byte[] readDigest(ByteBuffer input) {
        byte[] result = new byte[32];
        input.get(result);
        return result;
    }
}
