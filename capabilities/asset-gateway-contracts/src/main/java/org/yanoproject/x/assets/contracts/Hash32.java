package org.yanoproject.x.assets.contracts;

import java.util.Arrays;
import java.util.HexFormat;

/** An immutable 32-byte identifier or digest. */
public final class Hash32 implements Comparable<Hash32> {
    public static final int BYTES = 32;

    private final byte[] value;

    private Hash32(byte[] value) {
        this.value = value;
    }

    public static Hash32 of(byte[] value) {
        if (value == null || value.length != BYTES) {
            throw new IllegalArgumentException("a hash is exactly 32 bytes");
        }
        return new Hash32(value.clone());
    }

    public static Hash32 ofHex(String hex) {
        return of(HexFormat.of().parseHex(hex));
    }

    public byte[] bytes() {
        return value.clone();
    }

    public String hex() {
        return HexFormat.of().formatHex(value);
    }

    @Override
    public int compareTo(Hash32 other) {
        return Arrays.compareUnsigned(value, other.value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Hash32 that && Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return hex();
    }
}
