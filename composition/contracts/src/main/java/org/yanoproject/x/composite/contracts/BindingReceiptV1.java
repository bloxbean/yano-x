package org.yanoproject.x.composite.contracts;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Bounded authenticated explanation of one source-message cascade, including rejected attempts.
 * The version-one CBOR envelope stores source id, height, overall status, failed ordinal, code, and steps.
 * ADR-031.3 amended each step in place with an eleventh element, the compact admission-rule trace; steps written
 * before that amendment fail decode with an explicit error. ADR-031.4 amended the rule failure in place with a fourth
 * element, the deciding write's index; a failure written before it fails decode with an explicit error, while steps
 * without a rule failure are unchanged.
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
     * The first admission rule that did not hold at a step (ADR-031.3, amended by ADR-031.4).
     *
     * @param ruleId the failed rule
     * @param failedClause zero-based clause that was false or raised an error, or {@code -1} when the rule failed
     *                     before any clause ran: the command view, kernel facts or write view could not be
     *                     established, a read failed, or the rule's own precharge exhausted work
     * @param denyCode the rule's deny code for {@code ADMISSION_RULE_DENIED}; {@code null} otherwise
     * @param writeIndex the write-view element at which the failing clause's most recently completed quantifier
     *                   stopped early, 0..127, or {@code null} when it ran to completion, no quantifier ran, or the
     *                   rule failed before any clause
     */
    public record RuleFailure(String ruleId, int failedClause, String denyCode, Integer writeIndex) {
        /** Largest write index a receipt records. */
        public static final int MAX_WRITE_INDEX = 127;

        public RuleFailure {
            if (ruleId == null || !ruleId.matches("[a-z][a-z0-9-]{0,62}")) {
                throw new IllegalArgumentException("receipt rule id");
            }
            if (failedClause < -1 || failedClause > 7 || denyCode != null && (failedClause < 0
                    || !denyCode.matches("[A-Z][A-Z0-9_]{0,62}") || denyCode.startsWith("ADMISSION_RULE_"))) {
                throw new IllegalArgumentException("receipt rule failure");
            }
            if (writeIndex != null && (writeIndex < 0 || writeIndex > MAX_WRITE_INDEX || failedClause < 0)) {
                throw new IllegalArgumentException("receipt rule write index");
            }
        }
        /** A failure that no write decided. */
        public RuleFailure(String ruleId, int failedClause, String denyCode) {
            this(ruleId, failedClause, denyCode, null);
        }
        Object wire() { return Arrays.asList(ruleId, failedClause, denyCode, writeIndex); }
    }
    /**
     * Compact, lossless admission-rule trace of one step. Evaluation order is fixed by the committed IR
     * (admission-slot rules in attachment order, then fact rules in attachment order, skipping rules whose
     * command selector does not match, stopping at the first failure), so the held count and the failure
     * identify every rule that held. Fact values are never recorded.
     *
     * @param heldCount rules that held, before the failure if there was one
     * @param failure the first rule that did not hold, or {@code null}
     */
    public record RuleTrace(int heldCount, RuleFailure failure) {
        /** A step with no evaluated rule. */
        public static final RuleTrace NONE = new RuleTrace(0, null);
        public RuleTrace {
            if (heldCount < 0 || heldCount > BindingIrV1.Limits.MAX_RULES_PER_COMPONENT
                    || failure != null && heldCount == BindingIrV1.Limits.MAX_RULES_PER_COMPONENT) {
                throw new IllegalArgumentException("receipt rule trace");
            }
        }
        Object wire() { return Arrays.asList(heldCount, failure == null ? null : failure.wire()); }
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
     * @param rules admission rules evaluated for this step's command
     * @param status PLANNED, EFFECT_PLANNED, or REJECTED; overall receipt status determines commit outcome
     * @param code stable failure code, empty for planned steps
     * @param rawBody whether opaque command bytes were forwarded without field mapping
     */
    public record Step(int ordinal, int depth, String bindingId, String targetComponentId,
                       byte[] messageId, List<String> eventsProduced, List<Condition> conditions,
                       RuleTrace rules, String status, String code, boolean rawBody) {
        public Step {
            Objects.requireNonNull(rules, "rules");
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
        /** A step at which no admission rule was evaluated. */
        public Step(int ordinal, int depth, String bindingId, String targetComponentId, byte[] messageId,
                    List<String> eventsProduced, List<Condition> conditions, String status, String code,
                    boolean rawBody) {
            this(ordinal, depth, bindingId, targetComponentId, messageId, eventsProduced, conditions,
                    RuleTrace.NONE, status, code, rawBody);
        }
        @Override public byte[] messageId() { return messageId.clone(); }
        Object wire() {
            return Arrays.asList(ordinal, depth, bindingId, targetComponentId, messageId, eventsProduced,
                    conditions.stream().map(Condition::wire).toList(), rules.wire(), status, code, rawBody);
        }
    }
    public BindingReceiptV1 {
        sourceMessageId = sourceMessageId.clone();
        steps = List.copyOf(steps);
        Objects.requireNonNull(code, "code");
        if (code.length() > 127 || accepted && (failedStepOrdinal != null || !code.isEmpty())
                || !accepted && (failedStepOrdinal == null || failedStepOrdinal < 0
                || failedStepOrdinal > 256 || code.isEmpty())) throw new IllegalArgumentException("receipt outcome");
        // Every cascade records at least its source step, so an empty trace is not a receipt.
        if (sourceMessageId.length != 32 || height < 0 || steps.isEmpty() || steps.size() > 257) {
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
        if (value instanceof List<?> legacy && legacy.size() == 10) {
            throw new IllegalArgumentException("binding receipt predates ADR-031.3: step has the pre-policy-plane "
                    + "layout without a rule trace");
        }
        List<?> fields = BindingCbor.array(value, 11);
        if (!(fields.get(4) instanceof byte[] id) || !(fields.get(5) instanceof List<?> events)
                || !(fields.get(6) instanceof List<?> conditions) || !(fields.get(10) instanceof Boolean raw)) {
            throw new IllegalArgumentException("receipt step");
        }
        List<?> trace = BindingCbor.array(fields.get(7), 2);
        RuleFailure failure = null;
        if (trace.get(1) != null) {
            if (trace.get(1) instanceof List<?> legacy && legacy.size() == 3) {
                throw new IllegalArgumentException("binding receipt predates ADR-031.4: rule failure has the "
                        + "pre-typed-views layout without a write index");
            }
            List<?> failed = BindingCbor.array(trace.get(1), 4);
            failure = new RuleFailure(BindingCbor.text(failed.get(0)),
                    Math.toIntExact(BindingCbor.integer(failed.get(1))),
                    failed.get(2) == null ? null : BindingCbor.text(failed.get(2)),
                    failed.get(3) == null ? null : Math.toIntExact(BindingCbor.integer(failed.get(3))));
        }
        RuleTrace rules = new RuleTrace(Math.toIntExact(BindingCbor.integer(trace.get(0))), failure);
        return new Step(Math.toIntExact(BindingCbor.integer(fields.get(0))),
                Math.toIntExact(BindingCbor.integer(fields.get(1))),
                fields.get(2) == null ? null : BindingCbor.text(fields.get(2)), BindingCbor.text(fields.get(3)), id,
                events.stream().map(BindingCbor::text).toList(), conditions.stream().map(valueCondition -> {
                    List<?> condition = BindingCbor.array(valueCondition, 2);
                    return new Condition(BindingCbor.text(condition.get(0)),
                            Math.toIntExact(BindingCbor.integer(condition.get(1))));
                }).toList(), rules, BindingCbor.text(fields.get(8)), BindingCbor.text(fields.get(9)), raw);
    }
}
