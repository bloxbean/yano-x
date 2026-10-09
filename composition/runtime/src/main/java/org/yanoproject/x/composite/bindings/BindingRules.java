package org.yanoproject.x.composite.bindings;

import org.yanoproject.api.appchain.AppStateMachine.AdmissionResult;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.api.appchain.transition.RuleValueView;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator.Budget;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator.Scoped;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator.WriteIndex;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.AdmissionRule;
import org.yanoproject.x.composite.contracts.BindingIrV1.ExpressionClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.LookupClause;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleFailure;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleTrace;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Validated admission rules of one binding program and their deterministic evaluation (ADR-031.3, extended by
 * ADR-031.4).
 *
 * <p>Rules are forbid-only: every rule attached to a component must hold for each command it selects, or the step
 * is denied. They never grant or widen authority, alter a command, or replace kernel admission or decision. Each
 * component's attachments split into the admission slot (rules that read neither facts nor write coverage;
 * evaluated after the kernel's admit hooks and before any work is reserved) and the verified-fact slot (rules that
 * read facts or write coverage; evaluated only after the kernel approved, with the exact facts instance of that
 * approval). Attachment order is kept within each slot, and every admission-slot rule precedes every fact rule.
 *
 * <p>Evaluation reads only committed IR, the step body through its descriptor view, the engine's step context,
 * overlay lookups and exact-key reads of declared components, the attached kernel's write view, and kernel facts
 * and coverage validated against their declarations. Reads are evaluated once per rule, in declaration order,
 * before its clauses, and are decoded by the owning kernel. The write view is materialized once per step, only when
 * an applicable rule quantifies over it; coverage once, in the fact slot, only when an applicable fact rule reads
 * it. Evaluation charges both work counters at every depth, including for denials, and never refunds work. A failure
 * is always a {@link BindingFailure}: {@code ADMISSION_RULE_DENIED}, {@code ADMISSION_RULE_ERROR},
 * {@code ADMISSION_RULE_INPUT}, or {@code EXPRESSION_CAPACITY_EXCEEDED}, recorded in the step's {@link RuleTrace}.
 */
final class BindingRules {
    private static final String CAPACITY = "EXPRESSION_CAPACITY_EXCEEDED";
    /** Entries in one decoded read or one write element: fields plus value fields (ADR-031.4 §5.6). */
    private static final int ELEMENT_ENTRIES = 2 * RuleValueView.MAX_FIELDS;

    /**
     * One declared read resolved against the value view of the component it reads.
     *
     * @param name read name, the prefix of its {@code reads.*} fields
     * @param component the component whose state is read
     * @param namespace the view's namespace
     * @param key the read's key source
     * @param fields declared field types of the view: plain names and {@code value.<field>} names
     */
    record Read(String name, String component, String namespace, BindingSourceV1 key,
                Map<String, RuleFact.Type> fields) {
        Read {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    /**
     * One attachment resolved against its component.
     *
     * @param index attachment position within the component
     * @param rule the attached rule
     * @param parameters normalized parameter values as rules read them (binding parameters are text)
     * @param factRule whether the rule reads {@code facts.*} or write coverage and so runs in the verified-fact slot
     * @param isStatic whether the rule is also evaluated, advisory only, at local ingress
     * @param readsWrites whether the rule quantifies over the attached kernel's write view
     * @param readsCoverage whether the rule reads a write-coverage field
     * @param reads the rule's reads, in evaluation order
     */
    record Attached(int index, AdmissionRule rule, Map<String, Object> parameters, boolean factRule,
                    boolean isStatic, boolean readsWrites, boolean readsCoverage, List<Read> reads) {
        Attached {
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            reads = List.copyOf(reads);
        }
        boolean selectsCommand() { return rule.command() != null; }
        boolean applies(BindingCommandView.View view) {
            return rule.command() == null || view != null && rule.command().equals(view.command().commandName());
        }
    }

    /**
     * A kernel's write view as rules read it: content fields (with {@code index}, which the engine supplies, and the
     * write value fields) and coverage fields.
     */
    record WriteView(Map<String, RuleFact.Type> content, Map<String, RuleFact.Type> coverage) {
        WriteView {
            content = Collections.unmodifiableMap(new LinkedHashMap<>(content));
            coverage = Collections.unmodifiableMap(new LinkedHashMap<>(coverage));
        }
    }

    /** A component's attachments by slot, and the committed inputs every rule of it may read. */
    record Component(List<Attached> admission, List<Attached> facts, Map<String, Object> configuration,
                     Map<String, RuleFact.Type> declaredFacts, WriteView writes) {
        Component {
            admission = List.copyOf(admission);
            facts = List.copyOf(facts);
            configuration = Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
            declaredFacts = Collections.unmodifiableMap(new LinkedHashMap<>(declaredFacts));
        }
        static final Component NONE = new Component(List.of(), List.of(), Map.of(), Map.of(), null);
        boolean empty() { return admission.isEmpty() && facts.isEmpty(); }
        /** Rules in evaluation order: the admission slot, then the verified-fact slot. */
        List<Attached> ordered() {
            List<Attached> ordered = new ArrayList<>(admission);
            ordered.addAll(facts);
            return ordered;
        }
        Attached firstSelecting() {
            return ordered().stream().filter(Attached::selectsCommand).findFirst().orElse(null);
        }
    }

    /**
     * Mutable evaluation record of one step: the rules that held and the first failure. Once evaluation stops, its
     * {@link #trace()} is the step's receipt trace. It also carries the command view, the write view and its
     * coverage between the two slots.
     */
    static final class Run {
        private int held;
        private RuleFailure failure;
        private BindingCommandView.View view;
        private List<Map<String, Object>> writes;
        private List<Map<String, Object>> covered;

        RuleTrace trace() { return new RuleTrace(held, failure); }
    }

    private final BindingProgram program;
    private final Map<String, Component> components;

    BindingRules(BindingProgram program, Map<String, Component> components) {
        this.program = program;
        this.components = Map.copyOf(components);
    }

    /** Returns a component's resolved attachments; components without rules have none. */
    Component component(String component) { return components.getOrDefault(component, Component.NONE); }

    /**
     * Runs the admission slot for one step. When some attached rule names a command, the command view is built
     * first; if it cannot be built canonically no rule runs and the step fails with {@code ADMISSION_RULE_INPUT}.
     *
     * @param writes the kernel's write view of the decoded command, called at most once and only when an applicable
     *               rule quantifies over it
     * @throws BindingFailure when a rule does not hold; {@code run} then records the failure
     */
    void admissionSlot(String component, byte[] body, Map<String, Object> context,
                       Function<String, AppStateReader> views, Budget cascade, Budget block, Run run,
                       Supplier<List<Map<String, Object>>> writes) {
        Component rules = component(component);
        if (rules.empty()) return;
        Attached selecting = rules.firstSelecting();
        if (selecting != null) {
            try {
                run.view = program.commandView(component, body, cascade, block);
            } catch (BindingFailure noView) {
                run.failure = new RuleFailure(selecting.rule().id(), -1, null);
                throw new BindingFailure(capacityOr(noView, "ADMISSION_RULE_INPUT"));
            }
        }
        for (Attached rule : rules.admission()) {
            if (rule.applies(run.view)) {
                if (rule.readsWrites()) materializeWrites(rules, rule, writes, cascade, block, run);
                evaluate(rule, inputs(rules, rule, run, context, null), views, cascade, block, run, run.writes);
            }
        }
    }

    /** Whether any fact rule applies to this step's command, so the kernel's verified facts are needed. */
    boolean needsFacts(String component, Run run) {
        return component(component).facts().stream().anyMatch(rule -> rule.applies(run.view));
    }

    /**
     * Runs the verified-fact slot with a kernel's fact values, which must satisfy the kernel's declarations. A
     * violation fails the step with {@code ADMISSION_RULE_INPUT}, naming the first applicable fact rule. Coverage is
     * requested from the kernel only when an applicable fact rule reads it, with the approving facts instance, and must
     * describe exactly the writes of the write view.
     *
     * @throws BindingFailure when the values violate the declarations or a fact rule does not hold
     */
    void factSlot(String component, Map<String, Object> values, Map<String, Object> context,
                  Function<String, AppStateReader> views, Budget cascade, Budget block, Run run,
                  Supplier<List<Map<String, Object>>> writes, Supplier<List<Map<String, Object>>> coverage) {
        Component rules = component(component);
        Attached first = rules.facts().stream().filter(rule -> rule.applies(run.view)).findFirst().orElse(null);
        if (first == null) return;
        Map<String, Object> facts;
        try {
            BindingWork.charge(1L + BindingWork.encoding(values), cascade, block);
            facts = verifiedFacts(values, rules.declaredFacts());
        } catch (BindingFailure violation) {
            run.failure = new RuleFailure(first.rule().id(), -1, null);
            throw new BindingFailure(capacityOr(violation, "ADMISSION_RULE_INPUT"));
        }
        for (Attached rule : rules.facts()) {
            if (!rule.applies(run.view)) continue;
            if (rule.readsWrites()) materializeWrites(rules, rule, writes, cascade, block, run);
            if (rule.readsCoverage()) materializeCoverage(rules, rule, coverage, cascade, block, run);
            evaluate(rule, inputs(rules, rule, run, context, facts), views, cascade, block, run,
                    rule.readsCoverage() ? run.covered : run.writes);
        }
    }

    /**
     * Advisory ingress evaluation of the static rules attached to a source component, with fresh local budgets of
     * {@code maxExpressionWorkPerCascade}. Static rules read the command, parameters, configuration and write
     * content, never state, facts or coverage. Followers never run this; block-time evaluation is authoritative.
     * A refusal carries structured details (bloxbean/yano#153): the rule, its deny code, and the deciding write.
     */
    AdmissionResult advisory(String component, byte[] body, BindingIrV1.Limits limits,
                             Supplier<List<Map<String, Object>>> writes) {
        Component rules = component(component);
        List<Attached> statics = rules.admission().stream().filter(Attached::isStatic).toList();
        if (statics.isEmpty()) return AdmissionResult.accept();
        Budget cascade = new Budget(limits.maxExpressionWorkPerCascade());
        Budget block = new Budget(limits.maxExpressionWorkPerCascade());
        Run run = new Run();
        Attached selecting = statics.stream().filter(Attached::selectsCommand).findFirst().orElse(null);
        try {
            if (selecting != null) {
                try {
                    run.view = program.commandView(component, body, cascade, block);
                } catch (BindingFailure noView) {
                    if (noView.code().equals(CAPACITY)) throw noView;
                    return AdmissionResult.reject("ADMISSION_RULE_INPUT", Map.of("rule", selecting.rule().id()));
                }
            }
            for (Attached rule : statics) {
                if (rule.applies(run.view)) {
                    if (rule.readsWrites()) materializeWrites(rules, rule, writes, cascade, block, run);
                    evaluate(rule, inputs(rules, rule, run, Map.of(), null), ignored -> null, cascade, block, run,
                            run.writes);
                }
            }
            return AdmissionResult.accept();
        } catch (BindingFailure rejected) {
            if (rejected.code().equals(CAPACITY)) return AdmissionResult.reject(CAPACITY);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("rule", run.failure.ruleId());
            if (run.failure.denyCode() != null) details.put("deny", run.failure.denyCode());
            if (run.failure.writeIndex() != null) details.put("write", (long) run.failure.writeIndex());
            return AdmissionResult.reject(rejected.code(), details);
        }
    }

    /**
     * Materializes and validates the write view once per step (ADR-031.4 §5.2): one element per write, in command
     * order, each with the engine's {@code index} and only declared content and value fields. It charges
     * {@code 1 + encoded element bytes} on both counters before validation.
     */
    private void materializeWrites(Component rules, Attached rule, Supplier<List<Map<String, Object>>> source,
                                   Budget cascade, Budget block, Run run) {
        if (run.writes != null) return;
        try {
            List<Map<String, Object>> raw = source.get();
            BindingWork.charge(1L + BindingWork.encoding(raw == null ? List.of() : raw), cascade, block);
            run.writes = elements(raw, rules.writes().content(), true, -1);
        } catch (BindingFailure failure) {
            run.failure = new RuleFailure(rule.rule().id(), -1, null);
            throw new BindingFailure(capacityOr(failure, "ADMISSION_RULE_INPUT"));
        }
    }

    /**
     * Materializes and validates the write view's verified coverage once per step, in the fact slot: one element per
     * write of the write view, merged with that write's content.
     */
    private void materializeCoverage(Component rules, Attached rule, Supplier<List<Map<String, Object>>> source,
                                     Budget cascade, Budget block, Run run) {
        if (run.covered != null) return;
        try {
            List<Map<String, Object>> raw = source.get();
            BindingWork.charge(1L + BindingWork.encoding(raw == null ? List.of() : raw), cascade, block);
            List<Map<String, Object>> coverage = elements(raw, rules.writes().coverage(), false, run.writes.size());
            List<Map<String, Object>> merged = new ArrayList<>();
            for (int position = 0; position < coverage.size(); position++) {
                Map<String, Object> element = new HashMap<>(run.writes.get(position));
                element.putAll(coverage.get(position));
                merged.add(Collections.unmodifiableMap(element));
            }
            run.covered = List.copyOf(merged);
        } catch (BindingFailure failure) {
            run.failure = new RuleFailure(rule.rule().id(), -1, null);
            throw new BindingFailure(capacityOr(failure, "ADMISSION_RULE_INPUT"));
        }
    }

    /**
     * Validates kernel elements against their declarations and returns owned copies. An element holds at most
     * {@code 2 * RuleValueView.MAX_FIELDS} entries (ADR-031.4 §5.6), text keys only, and never an {@code index}.
     *
     * @param indexed whether to add the engine's {@code index} to each element
     * @param size the required element count, or {@code -1}
     * @throws BindingFailure {@code ADMISSION_RULE_INPUT} for any violation
     */
    private static List<Map<String, Object>> elements(List<Map<String, Object>> raw,
                                                      Map<String, RuleFact.Type> declared, boolean indexed, int size) {
        if (raw == null || raw.size() > RuleValueView.MAX_WRITES || size >= 0 && raw.size() != size) {
            throw new BindingFailure("ADMISSION_RULE_INPUT");
        }
        List<Map<String, Object>> elements = new ArrayList<>();
        for (int position = 0; position < raw.size(); position++) {
            if (!(((Object) raw.get(position)) instanceof Map<?, ?> element)) {
                throw new BindingFailure("ADMISSION_RULE_INPUT");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) element;
            if (values.size() > ELEMENT_ENTRIES) throw new BindingFailure("ADMISSION_RULE_INPUT");
            // Key types first, so no kernel map compares a foreign key; the engine supplies the element's position.
            for (Object name : element.keySet()) {
                if (!(name instanceof String) || name.equals("index")) throw new BindingFailure("ADMISSION_RULE_INPUT");
            }
            Map<String, Object> copy = new HashMap<>(verifiedFacts(values, declared));
            if (indexed) copy.put("index", (long) position);
            elements.add(Collections.unmodifiableMap(copy));
        }
        return List.copyOf(elements);
    }

    /** One rule's reads, then its clauses in order; the first clause that does not hold denies, errors or exhausts. */
    private void evaluate(Attached attached, Scoped<Object> inputs, Function<String, AppStateReader> views,
                          Budget cascade, Budget block, Run run, List<Map<String, Object>> writes) {
        AdmissionRule rule = attached.rule();
        try {
            BindingWork.charge(1, cascade, block);
        } catch (BindingFailure exhausted) {
            run.failure = new RuleFailure(rule.id(), -1, null);
            throw exhausted;
        }
        if (!attached.reads().isEmpty()) {
            inputs = withReads(inputs, reads(attached, inputs, views, cascade, block, run));
        }
        for (int index = 0; index < rule.clauses().size(); index++) {
            boolean holds;
            WriteIndex decided = new WriteIndex();
            try {
                BindingWork.charge(1, cascade, block);
                holds = switch (rule.clauses().get(index)) {
                    case ExpressionClause expression -> Boolean.TRUE.equals(BindingExpressionEvaluator.evaluate(
                            expression.expression(), inputs, writes, program.ir().limits(), cascade, block, decided));
                    case LookupClause lookup -> program.lookup(lookup, inputs, views, cascade, block);
                    default -> throw new IllegalStateException("unvalidated rule clause");
                };
            } catch (BindingFailure failed) {
                run.failure = new RuleFailure(rule.id(), index, null, decided.value());
                throw new BindingFailure(capacityOr(failed, "ADMISSION_RULE_ERROR"));
            }
            if (!holds) {
                run.failure = new RuleFailure(rule.id(), index, rule.denyCode(), decided.value());
                throw new BindingFailure("ADMISSION_RULE_DENIED");
            }
        }
        run.held++;
    }

    /**
     * Evaluates a rule's reads in declaration order (ADR-031.4 §5.1): the key source, the owner's
     * {@code ruleValueKey}, an overlay read, and the owner's decoding, validated against the view. It charges
     * {@code 1 + key bytes}, then the owner-local key, the stored value and the encoded size of the decoded fields
     * ({@link BindingWork#encoding}). A key that cannot be built or is refused is {@code ADMISSION_RULE_ERROR}; a
     * decoding that violates the view (more than {@code 2 * RuleValueView.MAX_FIELDS} entries, an undeclared name,
     * a wrong type, malformed text) is {@code ADMISSION_RULE_INPUT}; both fail before any clause.
     *
     * @return {@code read.present}, {@code read.field} and {@code read.value.field} values
     */
    private Map<String, Object> reads(Attached attached, Scoped<Object> inputs, Function<String, AppStateReader> views,
                                      Budget cascade, Budget block, Run run) {
        Map<String, Object> values = new HashMap<>();
        for (Read read : attached.reads()) {
            try {
                byte[] key = program.ruleKey(read.key(), inputs, cascade, block);
                BindingWork.charge(1L + key.length, cascade, block);
                var kernel = program.kernel(read.component());
                byte[] local;
                try {
                    local = kernel.ruleValueKey(read.namespace(), key.clone());
                    if (local == null || local.length == 0) throw new IllegalArgumentException("empty value key");
                    CompositeStateKeys.componentKey(read.component(), local);
                } catch (IllegalArgumentException refused) {
                    throw new BindingFailure("LOOKUP_KEY_INVALID");
                }
                BindingWork.charge(local.length, cascade, block);
                Optional<byte[]> stored = views.apply(read.component()).get(local);
                values.put(read.name() + ".present", stored.isPresent());
                if (stored.isEmpty()) continue;
                BindingWork.charge(stored.get().length, cascade, block);
                Map<String, Object> decoded = kernel.ruleValueFields(read.namespace(), key.clone(), stored.get());
                try {
                    if (decoded != null && decoded.size() > ELEMENT_ENTRIES) {
                        throw new BindingFailure("ADMISSION_RULE_INPUT");
                    }
                    BindingWork.charge(BindingWork.encoding(decoded == null ? Map.of() : decoded), cascade, block);
                    verifiedFacts(decoded, read.fields()).forEach((field, value) ->
                            values.put(read.name() + "." + field, value));
                } catch (BindingFailure violation) {
                    throw new BindingFailure(capacityOr(violation, "ADMISSION_RULE_INPUT"));
                }
            } catch (BindingFailure failure) {
                run.failure = new RuleFailure(attached.rule().id(), -1, null);
                String code = failure.code();
                throw new BindingFailure(code.equals(CAPACITY) || code.equals("ADMISSION_RULE_INPUT") ? code
                        : "ADMISSION_RULE_ERROR");
            }
        }
        return values;
    }

    private static Scoped<Object> withReads(Scoped<Object> inputs, Map<String, Object> reads) {
        Map<Scope, Map<String, Object>> scopes = new EnumMap<>(Scope.class);
        scopes.putAll(inputs.scopes());
        scopes.put(Scope.READS, reads);
        return new Scoped<>(scopes);
    }

    /** Keeps an exhausted work budget's code; any other failure becomes {@code code}. */
    private static String capacityOr(BindingFailure failure, String code) {
        return failure.code().equals(CAPACITY) ? CAPACITY : code;
    }

    private static Scoped<Object> inputs(Component component, Attached rule, Run run, Map<String, Object> context,
                                         Map<String, Object> facts) {
        Map<Scope, Map<String, Object>> scopes = new EnumMap<>(Scope.class);
        if (rule.selectsCommand() && run.view != null) scopes.put(Scope.COMMAND, run.view.data());
        scopes.put(Scope.PARAMS, rule.parameters());
        scopes.put(Scope.CONFIG, component.configuration());
        scopes.put(Scope.CONTEXT, context);
        if (facts != null) scopes.put(Scope.FACTS, facts);
        return new Scoped<>(scopes);
    }

    /**
     * Validates a kernel's fact values, decoded read values or write-view elements against their declarations
     * (ADR-031.3 §5.5) and returns an owned copy.
     *
     * @throws BindingFailure {@code ADMISSION_RULE_INPUT} for any violation
     */
    static Map<String, Object> verifiedFacts(Map<String, Object> values, Map<String, RuleFact.Type> declared) {
        if (values == null) throw new BindingFailure("ADMISSION_RULE_INPUT");
        Map<String, Object> verified = new HashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) values).entrySet()) {
            if (!(entry.getKey() instanceof String name) || !declared.containsKey(name) || entry.getValue() == null) {
                throw new BindingFailure("ADMISSION_RULE_INPUT");
            }
            verified.put(name, verifiedValue(declared.get(name), entry.getValue()));
        }
        return verified;
    }

    private static Object verifiedValue(RuleFact.Type type, Object value) {
        return switch (type) {
            case INTEGER -> require(value instanceof Long, value);
            case BOOLEAN -> require(value instanceof Boolean, value);
            case TEXT -> require(value instanceof String text && utf8(text, RuleFact.MAX_VALUE_BYTES) >= 0, value);
            case BYTES -> {
                if (!(value instanceof byte[] bytes) || bytes.length > RuleFact.MAX_VALUE_BYTES) {
                    throw new BindingFailure("ADMISSION_RULE_INPUT");
                }
                yield bytes.clone();
            }
            case TEXT_SET -> {
                if (!(value instanceof List<?> list) || list.size() > RuleFact.MAX_SET_ENTRIES) {
                    throw new BindingFailure("ADMISSION_RULE_INPUT");
                }
                List<String> entries = new ArrayList<>();
                byte[] previous = null;
                for (Object item : list) {
                    if (!(item instanceof String text) || utf8(text, RuleFact.MAX_SET_ENTRY_BYTES) < 0) {
                        throw new BindingFailure("ADMISSION_RULE_INPUT");
                    }
                    byte[] current = text.getBytes(StandardCharsets.UTF_8);
                    if (previous != null && Arrays.compareUnsigned(previous, current) >= 0) {
                        throw new BindingFailure("ADMISSION_RULE_INPUT");
                    }
                    previous = current;
                    entries.add(text);
                }
                yield List.copyOf(entries);
            }
        };
    }

    private static Object require(boolean valid, Object value) {
        if (!valid) throw new BindingFailure("ADMISSION_RULE_INPUT");
        return value;
    }

    /** UTF-8 size of well-formed text within {@code maximum}, or {@code -1}; the length precheck avoids work. */
    private static long utf8(String text, int maximum) {
        if (text.length() > maximum) return -1;
        long size;
        try {
            size = BindingWork.size(text);
        } catch (BindingFailure malformed) {
            return -1;
        }
        return size > maximum ? -1 : size;
    }
}
