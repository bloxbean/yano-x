package org.yanoproject.x.execution.abi.v1;

import org.yanoproject.x.framework.api.execution.ExecutionKeys;
import org.yanoproject.x.execution.abi.cbor.CanonicalCbor;

import java.util.Map;
import java.util.Objects;

/**
 * Deterministic record of a transition whose changes were discarded, written by the adapter at
 * {@code ~exec/r/<message id>} or {@code ~exec/fx/<effect id hash>} ({@link ExecutionKeys}).
 */
public record Receipt(long height, Kind kind, String code) {

    public enum Kind {
        REJECTED(1), TRAPPED(2), OUT_OF_COMPUTE(3), OUT_OF_HOST_FUEL(4), ABI_VIOLATION(5);

        private final int value;

        Kind(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        static Kind of(long value) {
            for (Kind kind : values()) {
                if (kind.value == value) {
                    return kind;
                }
            }
            throw Fields.schema("receipt", "unknown kind " + value);
        }
    }

    public Receipt {
        Objects.requireNonNull(kind, "kind");
        Fields.code("receipt", code);
    }

    public byte[] encode() {
        return CanonicalCbor.encode(Map.of(0L, 1L, 1L, height, 2L, (long) kind.value(), 3L, code));
    }

    public static Receipt decode(byte[] bytes) {
        Fields f = Fields.decode("receipt", bytes, Fields.keys(0, 1, 2, 3));
        if (f.uint(0) != 1) {
            throw Fields.schema("receipt", "version must be 1");
        }
        return new Receipt(f.uint(1), Kind.of(f.uint(2)), f.text(3));
    }

    public static byte[] messageKey(byte[] messageId) {
        return ExecutionKeys.messageReceiptKey(messageId);
    }

    public static byte[] effectKey(byte[] effectIdHash) {
        return ExecutionKeys.effectReceiptKey(effectIdHash);
    }
}
