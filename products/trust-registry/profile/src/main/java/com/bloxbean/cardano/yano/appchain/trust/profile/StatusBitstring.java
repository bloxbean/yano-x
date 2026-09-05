package com.bloxbean.cardano.yano.appchain.trust.profile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A W3C Bitstring Status List bitstring: index 0 is the most significant bit of byte 0,
 * exactly as the specification orders positions. The list hash the chain holds is the
 * SHA-256 of these raw bytes; GZIP output is never hashed because it is not canonical.
 * {@code encodedList} is the multibase base64url (prefix {@code u}, no padding) of the GZIP
 * of the raw bytes.
 */
public final class StatusBitstring {
    public static final char MULTIBASE_BASE64URL = 'u';
    private static final int MAX_BYTES = (int) (TrustRegistryProfile.MAX_BIT_LENGTH / 8);

    private final byte[] bits;
    private final long bitLength;

    public StatusBitstring(long bitLength) {
        this.bitLength = requireBitLength(bitLength);
        this.bits = new byte[(int) ((bitLength + 7) / 8)];
    }

    private StatusBitstring(byte[] bits, long bitLength) {
        this.bits = bits;
        this.bitLength = bitLength;
    }

    public static StatusBitstring of(byte[] raw, long bitLength) {
        requireBitLength(bitLength);
        Objects.requireNonNull(raw, "raw");
        if (raw.length != (bitLength + 7) / 8) {
            throw new IllegalArgumentException("bitstring byte length does not match its bit length");
        }
        return new StatusBitstring(raw.clone(), bitLength);
    }

    public long bitLength() {
        return bitLength;
    }

    public boolean get(long index) {
        checkIndex(index);
        return (bits[(int) (index >>> 3)] & mask(index)) != 0;
    }

    public void set(long index, boolean value) {
        checkIndex(index);
        int position = (int) (index >>> 3);
        if (value) {
            bits[position] |= (byte) mask(index);
        } else {
            bits[position] &= (byte) ~mask(index);
        }
    }

    public long setCount() {
        long count = 0;
        for (byte b : bits) {
            count += Integer.bitCount(b & 0xff);
        }
        return count;
    }

    public byte[] bytes() {
        return bits.clone();
    }

    public byte[] sha256() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bits);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public String sha256Hex() {
        return HexFormat.of().formatHex(sha256());
    }

    public String encodedList() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(bits);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return MULTIBASE_BASE64URL
                + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());
    }

    /** Decodes a served {@code encodedList}; the decompressed size is bounded by the profile. */
    public static StatusBitstring decodeEncodedList(String encodedList, long bitLength) {
        Objects.requireNonNull(encodedList, "encodedList");
        if (encodedList.length() < 2 || encodedList.charAt(0) != MULTIBASE_BASE64URL) {
            throw new IllegalArgumentException("encodedList must be multibase base64url (u...)");
        }
        byte[] compressed;
        try {
            compressed = Base64.getUrlDecoder().decode(encodedList.substring(1));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("encodedList is not base64url", malformed);
        }
        byte[] raw = new byte[MAX_BYTES + 1];
        int total = 0;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            int read;
            while (total < raw.length && (read = gzip.read(raw, total, raw.length - total)) > 0) {
                total += read;
            }
        } catch (IOException malformed) {
            throw new IllegalArgumentException("encodedList is not a GZIP bitstring", malformed);
        }
        if (total > MAX_BYTES) {
            throw new IllegalArgumentException("encodedList exceeds the profile's list bound");
        }
        return of(Arrays.copyOf(raw, total), bitLength);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StatusBitstring that
                && bitLength == that.bitLength && Arrays.equals(bits, that.bits);
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(bitLength) + Arrays.hashCode(bits);
    }

    private void checkIndex(long index) {
        if (index < 0 || index >= bitLength) {
            throw new IndexOutOfBoundsException("bit index " + index + " is outside the list");
        }
    }

    private static int mask(long index) {
        return 0x80 >>> (int) (index & 7);
    }

    private static long requireBitLength(long bitLength) {
        if (bitLength < TrustRegistryProfile.MIN_BIT_LENGTH
                || bitLength > TrustRegistryProfile.MAX_BIT_LENGTH) {
            throw new IllegalArgumentException("bit length must be between "
                    + TrustRegistryProfile.MIN_BIT_LENGTH + " and "
                    + TrustRegistryProfile.MAX_BIT_LENGTH);
        }
        return bitLength;
    }
}
