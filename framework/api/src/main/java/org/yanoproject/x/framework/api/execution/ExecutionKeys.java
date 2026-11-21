package org.yanoproject.x.framework.api.execution;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Trie keys of execution-backed state machines (ADR-058 D9).
 *
 * <p>{@code ~exec/} is reserved for the execution state machine's receipts, next to the
 * effect system's {@code ~fx/}: framework code and other state machines must not write under it,
 * and guest programs may not write any key that starts with {@code ~}. Exposed in the API so that
 * clients and verifiers can build receipt proof keys.</p>
 */
public final class ExecutionKeys {

    /** Reserved trie-key prefix: {@code "~exec/"}. */
    public static final byte[] RESERVED_PREFIX = "~exec/".getBytes(StandardCharsets.UTF_8);

    private static final byte[] MESSAGE_RECEIPT_PREFIX = "~exec/r/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EFFECT_RECEIPT_PREFIX = "~exec/fx/".getBytes(StandardCharsets.UTF_8);

    private ExecutionKeys() {
    }

    /** {@code ~exec/r/<message id>}: the receipt of a discarded {@code apply_message}. */
    public static byte[] messageReceiptKey(byte[] messageId) {
        return concat(MESSAGE_RECEIPT_PREFIX, Objects.requireNonNull(messageId, "messageId"));
    }

    /** {@code ~exec/fx/<effect id hash>}: the receipt of a discarded {@code on_effect_result}. */
    public static byte[] effectReceiptKey(byte[] effectIdHash) {
        return concat(EFFECT_RECEIPT_PREFIX, Objects.requireNonNull(effectIdHash, "effectIdHash"));
    }

    /** True when a key lies in the reserved {@code ~exec/} namespace. */
    public static boolean isReserved(byte[] key) {
        return key != null && key.length >= RESERVED_PREFIX.length
                && Arrays.equals(key, 0, RESERVED_PREFIX.length, RESERVED_PREFIX, 0, RESERVED_PREFIX.length);
    }

    private static byte[] concat(byte[] prefix, byte[] suffix) {
        byte[] key = Arrays.copyOf(prefix, prefix.length + suffix.length);
        System.arraycopy(suffix, 0, key, prefix.length, suffix.length);
        return key;
    }
}
