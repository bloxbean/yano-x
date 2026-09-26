package org.yanoproject.x.composite.bindings;

import org.yanoproject.api.appchain.AppStateMachine.AdmissionResult;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator.Budget;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator.Scoped;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.AdmissionRule;
import org.yanoproject.x.composite.contracts.BindingIrV1.ExpressionClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.LookupClause;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleFailure;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleTrace;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Validated admission rules of one binding program and their deterministic evaluation (ADR-031.3).
 *
 * <p>Rules are forbid-only: every rule attached to a component must hold for each command it selects, or the step
 * is denied. They never grant or widen authority, alter a command, or replace kernel admission or decision. Each
 * component's attachments split into the admission slot (rules that do not read {@code facts.*}; evaluated after
 * the kernel's admit hooks and before any work is reserved) and the verified-fact slot (rules that read facts;
 * evaluated only after the kernel approved, with the exact facts instance of that approval). Attachment order is
 * kept within each slot, and every admission-slot rule precedes every fact rule.
 *
 * <p>Evaluation reads only committed IR, the step body through its descriptor view, the engine's step context,
 * overlay lookups of declared components, and kernel facts validated against their declarations. It charges both
 * work counters at every depth, including for denials, and never refunds work. A failure is always a
 * {@link BindingFailure}: {@code ADMISSION_RULE_DENIED}, {@code ADMISSION_RULE_ERROR}, {@code ADMISSION_RULE_INPUT},
 * or {@code EXPRESSION_CAPACITY_EXCEEDED}, recorded in the step's {@link RuleTrace}.
 */
final class BindingRules {
    private static final String CAPACITY = "EXPRESSION_CAPACITY_EXCEEDED";

    /**
     * One attachment resolved against its component.
     *
     * @param index attachment position within the component
     * @param rule the attached rule
     * @param parameters normalized parameter values as rules read them (binding parameters are text)
     * @param factRule whether the rule reads {@code facts.*} and so runs in the verified-fact slot
     * @param isStatic whether the rule is also evaluated, advisory only, at local ingress
     */
    record Attached(int index, AdmissionRule rule, Map<String, Object> parameters, boolean factRule,
                    boolean isStatic) {
        Attached {
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }
        boolean selectsCommand() { return rule.command() != null; }
        boolean applies(BindingCommandView.View view) {
            return rule.command() == null || view != null && rule.command().equals(view.command().commandName());
        }
    }

    /** A component's attachments by slot, and the committed inputs every rule of it may read. */
    record Component(List<Attached> admission, List<Attached> facts, Map<String, Object> configuration,
                     Map<String, RuleFact.Type> declaredFacts) {
        Component {
            admission = List.copyOf(admission);
            facts = List.copyOf(facts);
            configuration = Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
            declaredFacts = Collections.unmodifiableMap(new LinkedHashMap<>(declaredFacts));
        }
        static final Component NONE = new Component(List.of(), List.of(), Map.of(), Map.of());
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
     * {@link #trace()} is the step's receipt trace. It also carries the command view between the two slots.
     */
    static final class Run {
        private int held;
        private RuleFailure failure;
        private BindingCommandView.View view;

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
     * @throws BindingFailure when a rule does not hold; {@code run} then records the failure
     */
    void admissionSlot(String component, byte[] body, Map<String, Object> context,
                       Function<String, AppStateReader> views, Budget cascade, Budget block, Run run) {
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
                evaluate(rule, inputs(rules, rule, run, context, null), views, cascade, block, run);
            }
        }
    }

    /** Whether any fact rule applies to this step's command, so the kernel's verified facts are needed. */
    boolean needsFacts(String component, Run run) {
        return component(component).facts().stream().anyMatch(rule -> rule.applies(run.view));
    }

    /**
     * Runs the verified-fact slot with a kernel's fact values, which must satisfy the kernel's declarations. A
     * violation fails the step with {@code ADMISSION_RULE_INPUT}, naming the first applicable fact rule.
     *
     * @throws BindingFailure when the values violate the declarations or a fact rule does not hold
     */
    void factSlot(String component, Map<String, Object> values, Map<String, Object> context,
                  Function<String, AppStateReader> views, Budget cascade, Budget block, Run run) {
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
            if (rule.applies(run.view)) {
                evaluate(rule, inputs(rules, rule, run, context, facts), views, cascade, block, run);
            }
        }
    }

    /**
     * Advisory ingress evaluation of the static rules attached to a source component, with fresh local budgets of
     * {@code maxExpressionWorkPerCascade}. Followers never run it; block-time evaluation is authoritative.
     */
    AdmissionResult advisory(String component, byte[] body, BindingIrV1.Limits limits) {
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
                    return AdmissionResult.reject("ADMISSION_RULE_INPUT/" + selecting.rule().id());
                }
            }
            for (Attached rule : statics) {
                if (rule.applies(run.view)) {
                    evaluate(rule, inputs(rules, rule, run, Map.of(), null), ignored -> null, cascade, block, run);
                }
            }
            return AdmissionResult.accept();
        } catch (BindingFailure rejected) {
            if (rejected.code().equals(CAPACITY)) return AdmissionResult.reject(CAPACITY);
            String rule = run.failure.ruleId();
            return AdmissionResult.reject(run.failure.denyCode() != null
                    ? rejected.code() + "/" + rule + "/" + run.failure.denyCode() : rejected.code() + "/" + rule);
        }
    }

    /** One rule's clauses in order; the first clause that does not hold denies, errors or exhausts work. */
    private void evaluate(Attached attached, Scoped<Object> inputs, Function<String, AppStateReader> views,
                          Budget cascade, Budget block, Run run) {
        AdmissionRule rule = attached.rule();
        try {
            BindingWork.charge(1, cascade, block);
        } catch (BindingFailure exhausted) {
            run.failure = new RuleFailure(rule.id(), -1, null);
            throw exhausted;
        }
        for (int index = 0; index < rule.clauses().size(); index++) {
            boolean holds;
            try {
                BindingWork.charge(1, cascade, block);
                holds = switch (rule.clauses().get(index)) {
                    case ExpressionClause expression -> Boolean.TRUE.equals(BindingExpressionEvaluator.evaluate(
                            expression.expression(), inputs, program.ir().limits(), cascade, block));
                    case LookupClause lookup -> program.lookup(lookup, inputs, views, cascade, block);
                    default -> throw new IllegalStateException("unvalidated rule clause");
                };
            } catch (BindingFailure failed) {
                run.failure = new RuleFailure(rule.id(), index, null);
                throw new BindingFailure(capacityOr(failed, "ADMISSION_RULE_ERROR"));
            }
            if (!holds) {
                run.failure = new RuleFailure(rule.id(), index, rule.denyCode());
                throw new BindingFailure("ADMISSION_RULE_DENIED");
            }
        }
        run.held++;
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
     * Validates a kernel's fact values against its declarations (ADR-031.3 §5.5) and returns an owned copy.
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
