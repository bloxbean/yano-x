package org.yanoproject.x.execution.abi.v1;

import org.yanoproject.x.execution.abi.cbor.CanonicalCbor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Argument of the {@code effect_emit} host function, mirroring Yano's {@code EffectIntent}.
 * The host sets the source message id itself; the guest cannot.
 *
 * @param gate -1 chain default, 0 app final, 1 L1 anchored, 2 ZK settled
 * @param resultPolicy 0 none, 1 chain
 * @param expiryBlocks 0 for the default; only allowed with result policy 1
 */
public record EffectIntentArg(String type, byte[] payload, String scope, int gate, int resultPolicy, long expiryBlocks) {

    public static final String TYPE = "effect-intent";

    public EffectIntentArg {
        if (!AbiV1.isEffectType(type)) {
            throw Fields.schema(TYPE, "invalid effect type");
        }
        payload = payload.clone();
        Objects.requireNonNull(scope, "scope");
        if (payload.length > AbiV1.MAX_EFFECT_PAYLOAD_BYTES) {
            throw Fields.schema(TYPE, "payload too large");
        }
        if (scope.getBytes(StandardCharsets.UTF_8).length > AbiV1.MAX_SCOPE_BYTES) {
            throw Fields.schema(TYPE, "scope too large");
        }
        if (gate < -1 || gate > 2) {
            throw Fields.schema(TYPE, "gate must be -1..2");
        }
        if (resultPolicy < 0 || resultPolicy > 1) {
            throw Fields.schema(TYPE, "result policy must be 0 or 1");
        }
        if (expiryBlocks < 0 || (expiryBlocks > 0 && resultPolicy == 0)) {
            throw Fields.schema(TYPE, "expiry applies only to result policy 1");
        }
    }

    public byte[] encode() {
        Map<Long, Object> m = new LinkedHashMap<>();
        m.put(0L, type);
        m.put(1L, payload.clone());
        if (!scope.isEmpty()) {
            m.put(2L, scope);
        }
        if (gate != -1) {
            m.put(3L, (long) gate);
        }
        if (resultPolicy != 0) {
            m.put(4L, (long) resultPolicy);
        }
        if (expiryBlocks != 0) {
            m.put(5L, expiryBlocks);
        }
        return CanonicalCbor.encode(m);
    }

    /**
     * Decodes strictly. Optional keys carrying their default value are rejected, so every intent
     * has exactly one encoding.
     */
    public static EffectIntentArg decode(byte[] bytes) {
        Fields f = Fields.decode(TYPE, bytes, Fields.keys(0, 1, 2, 3, 4, 5));
        String scope = f.has(2) ? f.text(2) : "";
        int gate = f.has(3) ? (int) clamp(f.integer(3)) : -1;
        int policy = f.has(4) ? (int) clamp(f.uint(4)) : 0;
        long expiry = f.has(5) ? f.uint(5) : 0;
        if ((f.has(2) && scope.isEmpty()) || (f.has(3) && gate == -1) || (f.has(4) && policy == 0)
                || (f.has(5) && expiry == 0)) {
            throw Fields.schema(TYPE, "optional key carries its default value");
        }
        return new EffectIntentArg(f.text(0), f.bytes(1), scope, gate, policy, expiry);
    }

    private static long clamp(long v) {
        return Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    @Override public byte[] payload() { return payload.clone(); }

    @Override public boolean equals(Object o) {
        return o instanceof EffectIntentArg e && type.equals(e.type) && Arrays.equals(payload, e.payload)
                && scope.equals(e.scope) && gate == e.gate && resultPolicy == e.resultPolicy
                && expiryBlocks == e.expiryBlocks;
    }

    @Override public int hashCode() {
        return Objects.hash(type, scope, gate);
    }
}
