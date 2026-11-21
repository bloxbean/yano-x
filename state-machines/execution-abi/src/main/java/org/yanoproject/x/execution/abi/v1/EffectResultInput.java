package org.yanoproject.x.execution.abi.v1;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A committed effect outcome, mirroring Yano's {@code EffectResult}.
 *
 * @param outcome 1 confirmed, 2 failed, 3 cancelled, 4 expired
 * @param detailHash 32 bytes, or {@code null} when absent
 */
public record EffectResultInput(String chainId, long height, long ordinal, String type, String scope,
                                int outcome, byte[] externalRef, byte[] detailHash, long resultHeight) {

    public EffectResultInput {
        Objects.requireNonNull(chainId, "chainId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");
        externalRef = externalRef.clone();
        detailHash = detailHash == null ? null : detailHash.clone();
        if (outcome < 1 || outcome > 4 || height < 1 || ordinal < 0 || resultHeight < 0) {
            throw new IllegalArgumentException("invalid effect result");
        }
        if (detailHash != null && detailHash.length != 32) {
            throw new IllegalArgumentException("detailHash must be 32 bytes");
        }
    }

    Map<Long, Object> toCbor() {
        Map<Long, Object> m = new LinkedHashMap<>();
        m.put(0L, chainId);
        m.put(1L, height);
        m.put(2L, ordinal);
        m.put(3L, type);
        m.put(4L, scope);
        m.put(5L, (long) outcome);
        m.put(6L, externalRef.clone());
        if (detailHash != null) {
            m.put(7L, detailHash.clone());
        }
        m.put(8L, resultHeight);
        return m;
    }

    static EffectResultInput fromCbor(Object value) {
        Fields f = Fields.of("effect-result", value, Fields.keys(0, 1, 2, 3, 4, 5, 6, 7, 8));
        long outcome = f.uint(5);
        if (outcome < 1 || outcome > 4) {
            throw Fields.schema("effect-result", "outcome must be 1..4");
        }
        long height = f.uint(1);
        if (height < 1) {
            throw Fields.schema("effect-result", "height must be >= 1");
        }
        return new EffectResultInput(f.text(0), height, f.uint(2), f.text(3), f.text(4), (int) outcome,
                f.bytes(6), f.has(7) ? f.bytes(7, 32) : null, f.uint(8));
    }

    @Override public byte[] externalRef() { return externalRef.clone(); }
    @Override public byte[] detailHash() { return detailHash == null ? null : detailHash.clone(); }

    @Override public boolean equals(Object o) {
        return o instanceof EffectResultInput e && chainId.equals(e.chainId) && height == e.height
                && ordinal == e.ordinal && type.equals(e.type) && scope.equals(e.scope) && outcome == e.outcome
                && Arrays.equals(externalRef, e.externalRef) && Arrays.equals(detailHash, e.detailHash)
                && resultHeight == e.resultHeight;
    }

    @Override public int hashCode() {
        return Objects.hash(chainId, height, ordinal);
    }
}
