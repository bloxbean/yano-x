package org.yanoproject.x.execution.abi.v1;

import java.util.Arrays;

/** Byte-array map key with content equality. */
record ByteKey(byte[] bytes) {
    @Override public boolean equals(Object o) {
        return o instanceof ByteKey k && Arrays.equals(bytes, k.bytes);
    }

    @Override public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
