package org.yanoproject.x.composite.bindings;

import org.yanoproject.api.appchain.transition.TransitionEvent;

import java.util.Map;

/** Exact baseline wire sizing and deterministic mandatory work, without hashing or encoding a command. */
final class BindingPayload {
    private BindingPayload() { }

    /**
     * Measures the complete canonical scalar map, including topic UTF-8 and CBOR length headers.
     * The zero hash has exactly the eventual hash's wire size. No raw body is truncated or omitted.
     */
    static Estimate estimate(String topic, byte[] sender, byte[] messageId, byte[] body, int maximum) {
        Map<String, Object> fields = Map.of("topic", topic, "sender", sender, "messageId", messageId,
                "body", body, "bodyHash", new byte[32], "bodyLength", (long) body.length);
        long bytes = header(fields.size());
        for (var entry : fields.entrySet()) {
            bytes += scalar(entry.getKey()) + scalar(entry.getValue());
        }
        if (bytes > maximum || bytes > TransitionEvent.MAX_PAYLOAD_BYTES) {
            throw new BindingFailure("COMMAND_PAYLOAD_TOO_LARGE");
        }
        return new Estimate(bytes, body.length + BindingWork.encoding(fields));
    }

    private static long scalar(Object value) {
        if (value instanceof Long number) return header(number);
        long size = BindingWork.size(value);
        return header(size) + size;
    }

    private static int header(long value) {
        return value < 24 ? 1 : value <= 255 ? 2 : value <= 65535 ? 3 : value <= 0xffffffffL ? 5 : 9;
    }

    /** Baseline preparation excludes dispatch and decoding, which have separate block-budget treatment. */
    record Estimate(long encodedBytes, long preparationWork) {
        long decodingWork() { return 1 + encodedBytes; }
    }
}
