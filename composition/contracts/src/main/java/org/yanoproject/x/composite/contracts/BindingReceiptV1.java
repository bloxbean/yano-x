package org.yanoproject.x.composite.contracts;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Bounded authenticated explanation of one source-message cascade, including rejected attempts.
 * The version-one CBOR envelope stores source id, height, overall status, failed ordinal, code, and steps.
 * A planned step in a rejected receipt is diagnostic only: its mutations were not committed. Receipts do
 * not carry event payloads or constitute authorization evidence for another command.
 *
 * @param sourceMessageId original externally authenticated message id (32 bytes)
 * @param height source block height
 * @param accepted whether every planned step passed preflight and the cascade was selected for commit
 * @param failedStepOrdinal rejected step ordinal, or null on success
 * @param code stable rejection code, or empty on success
 * @param steps bounded execution trace ordered by derivation ordinal
 */
public record BindingReceiptV1(byte[] sourceMessageId, long height, boolean accepted,
                               Integer failedStepOrdinal, String code, List<Step> steps) {
    public static final int MAX_BYTES = 65_536;
    public static final int MAX_CONDITION_RECORDS = 256;
    /**
     * A considered binding and its first false or error-producing clause.
     *
     * @param bindingId binding selected by the event being processed
     * @param failedClause zero-based false/error clause, or {@code -1} when every clause matched;
     *                     a binding-level budget failure before clause evaluation also uses {@code -1},
     *                     distinguished by the enclosing rejected step and failure code
     */
    public record Condition(String bindingId, int failedClause) {
        public Condition {
            BindingExpressionV1.requireName(bindingId);
            if (failedClause < -1 || failedClause > 7) throw new IllegalArgumentException("receipt clause ordinal");
        }
        Object wire() { return List.of(bindingId, failedClause); }
    }
    /**
     * Diagnostic step metadata. Source ordinal/depth are zero; derived ordinals are one-based.
     * {@code bindingId} is null for the source. Event ids describe produced schemas, not payload contents.
     * {@code rawBody} identifies command mappings that forwarded opaque command bytes.
     *
     * @param ordinal zero for the source, otherwise the one-based derivation ordinal
     * @param depth distance from the source in the breadth-first cascade
     * @param bindingId originating binding, or null for the source
     * @param targetComponentId command target or owning component for an effect
     * @param messageId source or deterministic derived id, copied on construction and access
     * @param eventsProduced event schema ids in production order
     * @param conditions considered bindings and their condition outcomes
     * @param status PLANNED, EFFECT_PLANNED, or REJECTED; overall receipt status determines commit outcome
     * @param code stable failure code, empty for planned steps
     * @param rawBody whether opaque command bytes were forwarded without field mapping
     */
    public record Step(int ordinal, int depth, String bindingId, String targetComponentId,
                       byte[] messageId, List<String> eventsProduced, List<Condition> conditions,
                       String status, String code, boolean rawBody) {
        public Step {
            messageId = messageId.clone();
            eventsProduced = List.copyOf(eventsProduced);
            conditions = List.copyOf(conditions);
            BindingExpressionV1.requireName(targetComponentId);
            if (bindingId != null) BindingExpressionV1.requireName(bindingId);
            eventsProduced.forEach(BindingExpressionV1::requireName);
            if (!List.of("PLANNED", "EFFECT_PLANNED", "REJECTED").contains(status)
                    || code == null || code.length() > 127) throw new IllegalArgumentException("receipt step status");
            if (ordinal < 0 || ordinal > 256 || depth < 0 || depth > 33
                    || messageId.length != 32 || eventsProduced.size() > 257
                    || conditions.size() > MAX_CONDITION_RECORDS)
                            throw new IllegalArgumentException("receipt step limit");
        }
        @Override public byte[] messageId() { return messageId.clone(); }
        Object wire() {
            return Arrays.asList(ordinal, depth, bindingId, targetComponentId, messageId, eventsProduced,
                    conditions.stream().map(Condition::wire).toList(), status, code, rawBody);
        }
    }
    public BindingReceiptV1 {
        sourceMessageId = sourceMessageId.clone();
        steps = List.copyOf(steps);
        Objects.requireNonNull(code, "code");
        if (code.length() > 127 || accepted && (failedStepOrdinal != null || !code.isEmpty())
                || !accepted && (failedStepOrdinal == null || failedStepOrdinal < 0
                || failedStepOrdinal > 256 || code.isEmpty())) throw new IllegalArgumentException("receipt outcome");
        if (sourceMessageId.length != 32 || height < 0 || steps.size() > 257) {
            throw new IllegalArgumentException("receipt limit");
        }
    }
    @Override public byte[] sourceMessageId() { return sourceMessageId.clone(); }
    /**
     * Serializes the receipt and enforces its authenticated-state byte cap.
     * Callers must complete this operation before committing any component mutations or effects.
     *
     * @throws IllegalArgumentException if the encoded receipt exceeds {@link #MAX_BYTES}
     */
    public byte[] encode() {
        byte[] bytes = BindingCbor.encode(Arrays.asList(1, sourceMessageId, height, accepted ? "ACCEPTED" : "REJECTED",
                failedStepOrdinal, code, steps.stream().map(Step::wire).toList()));
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("binding receipt exceeds byte limit");
        return bytes;
    }

    /**
     * Decodes a receipt for queries and offline inspection, enforcing both canonical bytes and trace bounds.
     * A successfully decoded receipt still needs a state proof when used outside a trusted local read.
     *
     * @throws IllegalArgumentException if the envelope, status, trace, or canonical encoding is invalid
     */
    public static BindingReceiptV1 decode(byte[] encoded) {
        List<?> root = BindingCbor.array(BindingCbor.decode(encoded, MAX_BYTES), 7);
        if (BindingCbor.integer(root.getFirst()) != 1 || !(root.get(1) instanceof byte[] source)
                || !(root.get(6) instanceof List<?> rawSteps)) throw new IllegalArgumentException("receipt envelope");
        String status = BindingCbor.text(root.get(3));
        if (!List.of("ACCEPTED", "REJECTED").contains(status)) throw new IllegalArgumentException("receipt status");
        Integer failed = root.get(4) == null ? null : Math.toIntExact(BindingCbor.integer(root.get(4)));
        List<Step> steps = rawSteps.stream().map(BindingReceiptV1::step).toList();
        BindingReceiptV1 result = new BindingReceiptV1(source, BindingCbor.integer(root.get(2)),
                "ACCEPTED".equals(status), failed, BindingCbor.text(root.get(5)), steps);
        if (!Arrays.equals(encoded, result.encode())) throw new IllegalArgumentException("noncanonical receipt");
        return result;
    }

    private static Step step(Object value) {
        List<?> fields = BindingCbor.array(value, 10);
        if (!(fields.get(4) instanceof byte[] id) || !(fields.get(5) instanceof List<?> events)
                || !(fields.get(6) instanceof List<?> conditions) || !(fields.get(9) instanceof Boolean raw)) {
            throw new IllegalArgumentException("receipt step");
        }
        return new Step(Math.toIntExact(BindingCbor.integer(fields.get(0))),
                Math.toIntExact(BindingCbor.integer(fields.get(1))),
                fields.get(2) == null ? null : BindingCbor.text(fields.get(2)), BindingCbor.text(fields.get(3)), id,
                events.stream().map(BindingCbor::text).toList(), conditions.stream().map(valueCondition -> {
                    List<?> condition = BindingCbor.array(valueCondition, 2);
                    return new Condition(BindingCbor.text(condition.get(0)),
                            Math.toIntExact(BindingCbor.integer(condition.get(1))));
                }).toList(), BindingCbor.text(fields.get(7)), BindingCbor.text(fields.get(8)), raw);
    }
}
