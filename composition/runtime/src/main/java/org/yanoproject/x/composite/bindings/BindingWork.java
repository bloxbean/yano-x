package org.yanoproject.x.composite.bindings;

import java.util.List;
import java.util.Map;

/**
 * Version-one deterministic work schedule shared by all binding-language operations.
 *
 * <p>Both budgets retain attempted work, including a charge that exhausts either budget. Text is measured
 * in UTF-8 bytes without allocating a temporary encoding. Encoding reserves nine units per CBOR item/header
 * plus variable payload bytes; this intentionally bounds encoding work rather than predicting wire length.
 * Kernel authorization work has a separate, participant-owned budget and is not charged here.
 */
final class BindingWork {
    private BindingWork() { }

    /** Charges both budgets even when the first reservation fails. */
    static void charge(long units, BindingExpressionEvaluator.Budget cascade,
                       BindingExpressionEvaluator.Budget block) {
        BindingFailure failure = null;
        try { block.charge(units); } catch (BindingFailure exhausted) { failure = exhausted; }
        try { cascade.charge(units); } catch (BindingFailure exhausted) { failure = exhausted; }
        if (failure != null) throw failure;
    }

    /** Returns payload bytes for variable scalars, nine units for fixed-width scalars. */
    static long size(Object value) {
        if (value instanceof byte[] bytes) return bytes.length;
        if (!(value instanceof String text)) return 9;
        long size = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) size++;
            else if (c < 0x800) size += 2;
            else if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new BindingFailure("INVALID_UNICODE");
                }
                size += 4;
            } else {
                if (Character.isLowSurrogate(c)) throw new BindingFailure("INVALID_UNICODE");
                size += 3;
            }
        }
        return size;
    }

    /** Reserves a bounded scalar/container encoding before its output buffer is allocated. */
    static long encoding(Object value) {
        if (value instanceof Map<?, ?> map) {
            long work = 9;
            for (var entry : map.entrySet()) work += encoding(entry.getKey()) + encoding(entry.getValue());
            return work;
        }
        if (value instanceof List<?> list) {
            long work = 9;
            for (Object item : list) work += encoding(item);
            return work;
        }
        return value instanceof byte[] || value instanceof String ? 9 + size(value) : 9;
    }
}
