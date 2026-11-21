package org.yanoproject.x.execution.abi.v1;

import java.util.Arrays;

/** One staged state change. {@code value == null} is a delete. */
public record Write(byte[] key, byte[] value) {

    public Write {
        key = key.clone();
        value = value == null ? null : value.clone();
    }

    public boolean isDelete() {
        return value == null;
    }

    @Override public byte[] key() { return key.clone(); }
    @Override public byte[] value() { return value == null ? null : value.clone(); }

    @Override public boolean equals(Object o) {
        return o instanceof Write w && Arrays.equals(key, w.key) && Arrays.equals(value, w.value);
    }

    @Override public int hashCode() {
        return Arrays.hashCode(key);
    }

    @Override public String toString() {
        java.util.HexFormat hex = java.util.HexFormat.of();
        return hex.formatHex(key) + (value == null ? " -> <delete>" : " -> " + hex.formatHex(value));
    }
}
