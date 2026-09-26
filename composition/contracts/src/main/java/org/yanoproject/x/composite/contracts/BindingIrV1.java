package org.yanoproject.x.composite.contracts;

import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Version-one, data-only declarative composition document committed by the composite profile.
 *
 * <p>The canonical CBOR envelope contains the IR version, function-catalog id, expression-dialect id,
 * workflow generation height, components, admission rules, bindings, and limits, in that order. Declaration
 * order of components, bindings, and rule attachments is preserved; rules are sorted by id and parameters by
 * name; map keys are canonicalized by the codec. Reordering bindings or attachments can change execution and
 * therefore changes the committed bytes. This contract has no dependency on a host plugin implementation or CEL
 * runtime.
 *
 * <p>ADR-031.3 amended this layout in place (the identifiers are unchanged): the rules section, per-component
 * attachments, scoped fields, and the thirteenth limit were added, and every amended structure changed its array
 * arity. Bytes produced before that amendment fail decode with an explicit "predates ADR-031.3" error rather than
 * being misread; no code path accepts both layouts.
 *
 * <p>Constructors enforce structural bounds and the scope-by-use-site table: binding conditions, lookups, and
 * mappings read only {@code event} and {@code context}; rule clauses read only {@code command}, {@code params},
 * {@code config}, {@code context}, and {@code facts}, with {@code command} only in a rule that names a command.
 * Machine-specific schemas, attachment parameters, authorization-sensitive evidence assignments, and graph cycles
 * are checked separately when the runtime constructs a binding program.
 *
 * @param components ordered component instances with fully normalized configuration and rule attachments
 * @param rules admission rules sorted by id; attached to components by id
 * @param bindings ordered event-to-command or event-to-effect edges
 * @param limits consensus-selected resource limits; these are not node-local tuning parameters
 * @param workflowFromHeight earliest height of this workflow generation; changed binding programs need a new generation
 */
public record BindingIrV1(List<Component> components, List<AdmissionRule> rules, List<Binding> bindings,
                          Limits limits, long workflowFromHeight) {
    public static final String FUNCTIONS = "yano-x-binding-functions-v1";
    /** Maximum admission rules in one document. */
    public static final int MAX_RULES = 64;

    public BindingIrV1 {
        components = List.copyOf(components);
        rules = List.copyOf(rules);
        bindings = List.copyOf(bindings);
        Objects.requireNonNull(limits, "limits");
        if (workflowFromHeight < 1) throw new IllegalArgumentException("workflow generation height");
        if (components.isEmpty() || components.size() > 16 || bindings.size() > 256 || rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("binding document exceeds component/rule/binding limits");
        }
        unique(components.stream().map(Component::id).toList());
        unique(components.stream().map(Component::ingressTopic).toList());
        List<String> ids = new ArrayList<>(bindings.stream().map(Binding::id).toList());
        ids.addAll(rules.stream().map(AdmissionRule::id).toList());
        unique(ids);
        for (int index = 1; index < rules.size(); index++) {
            if (rules.get(index - 1).id().compareTo(rules.get(index).id()) >= 0) {
                throw new IllegalArgumentException("admission rules must be sorted by id");
            }
        }
        if (components.stream().anyMatch(component -> component.fromHeight() > workflowFromHeight)) {
            throw new IllegalArgumentException("workflow cannot precede a participant generation");
        }
        for (Component component : components) {
            if (component.admission().size() > limits.maxRulesPerComponent()) {
                throw new IllegalArgumentException("component rule attachments exceed limit");
            }
        }
        for (AdmissionRule rule : rules) {
            if (rule.lookupCount() > limits.maxLookupsPerCondition()) {
                throw new IllegalArgumentException("rule lookup clauses exceed limit");
            }
        }
    }

    /** Constructs a document without admission rules. */
    public BindingIrV1(List<Component> components, List<Binding> bindings, Limits limits, long workflowFromHeight) {
        this(components, List.of(), bindings, limits, workflowFromHeight);
    }

    /** Constructs a genesis workflow generation; governed replacements supply an explicit generation height. */
    public BindingIrV1(List<Component> components, List<Binding> bindings, Limits limits) {
        this(components, List.of(), bindings, limits, 1);
    }

    /** Returns the named rule, or {@code null}. */
    public AdmissionRule rule(String id) {
        return rules.stream().filter(rule -> rule.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * One independently configured instance of a catalog-selected state machine.
     * The instance id selects its state namespace; the machine id selects its provider. Configuration must
     * include descriptor defaults before commitment so nodes cannot silently choose different defaults.
     * Attachments are evaluated in their declared order within each evaluation slot.
     */
    public record Component(String id, String machineId, String ingressTopic,
                            Map<String, BindingSourceV1.Literal> configuration, int maxEffectsPerBlock,
                            long fromHeight, List<RuleAttachment> admission) {
        public Component {
            requireId(id);
            requireId(machineId);
            if (ingressTopic == null || ingressTopic.isBlank() || ingressTopic.startsWith("~")
                    || ingressTopic.length() > 127) throw new IllegalArgumentException("invalid ingress topic");
            if (configuration.size() > 64 || maxEffectsPerBlock < 0 || maxEffectsPerBlock > 1_048_576
                    || fromHeight < 1) {
                throw new IllegalArgumentException("invalid component limits");
            }
            configuration = Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
            configuration.forEach((name, value) -> {
                BindingExpressionV1.requireName(name);
                Objects.requireNonNull(value, "configuration value");
            });
            admission = List.copyOf(admission);
            if (admission.size() > Limits.MAX_RULES_PER_COMPONENT) {
                throw new IllegalArgumentException("component rule attachments exceed limit");
            }
        }
        /** Constructs a component generation without rule attachments. */
        public Component(String id, String machineId, String ingressTopic,
                         Map<String, BindingSourceV1.Literal> configuration, int maxEffectsPerBlock, long fromHeight) {
            this(id, machineId, ingressTopic, configuration, maxEffectsPerBlock, fromHeight, List.of());
        }
        /** Constructs a component generation available from genesis, without rule attachments. */
        public Component(String id, String machineId, String ingressTopic,
                         Map<String, BindingSourceV1.Literal> configuration, int maxEffectsPerBlock) {
            this(id, machineId, ingressTopic, configuration, maxEffectsPerBlock, 1, List.of());
        }
        Object wire() {
            Map<String, Object> config = new LinkedHashMap<>();
            configuration.forEach((name, value) -> config.put(name, value.value()));
            return List.of(id, machineId, ingressTopic, config, maxEffectsPerBlock, fromHeight,
                    admission.stream().map(RuleAttachment::wire).toList());
        }
    }

    /**
     * A rule attached to a component with concrete, normalized parameters: every parameter the rule declares is
     * present, defaults filled, so stating and omitting a default produce identical bytes.
     *
     * @param rule attached rule id
     * @param parameters parameter values by name; encoded as a canonical map
     */
    public record RuleAttachment(String rule, Map<String, BindingSourceV1.Literal> parameters) {
        public RuleAttachment {
            requireId(rule);
            if (parameters.size() > AdmissionRule.MAX_PARAMETERS) {
                throw new IllegalArgumentException("too many rule parameters");
            }
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            parameters.forEach((name, value) -> {
                requireParameterName(name);
                Objects.requireNonNull(value, "parameter value");
            });
        }
        Object wire() {
            Map<String, Object> values = new LinkedHashMap<>();
            parameters.forEach((name, value) -> values.put(name, value.value()));
            return List.of(rule, values);
        }
    }

    /**
     * One forbid-only admission rule: all clauses must hold, otherwise the step is denied with {@code denyCode}.
     * A rule never grants authority, alters a command, or replaces kernel admission or decision.
     *
     * @param id rule id, unique across rules and bindings
     * @param denyCode code reported when a clause is false, {@code [A-Z][A-Z0-9_]{0,62}}, not {@code ADMISSION_RULE_*}
     * @param command selected command name, or {@code null} for every command of the attached component
     * @param parameters declared parameters sorted by name
     * @param clauses one to eight lookup or expression clauses, evaluated in order
     */
    public record AdmissionRule(String id, String denyCode, String command, List<Parameter> parameters,
                                List<Clause> clauses) {
        public static final int MAX_PARAMETERS = 16;
        public static final int MAX_CLAUSES = 8;
        /** Engine codes use this prefix; a rule's own deny code may not. */
        public static final String RESERVED_PREFIX = "ADMISSION_RULE_";

        public AdmissionRule {
            requireId(id);
            if (denyCode == null || !denyCode.matches("[A-Z][A-Z0-9_]{0,62}") || denyCode.startsWith(RESERVED_PREFIX)) {
                throw new IllegalArgumentException("invalid rule deny code");
            }
            if (command != null) BindingExpressionV1.requireName(command);
            parameters = List.copyOf(parameters);
            clauses = List.copyOf(clauses);
            if (parameters.size() > MAX_PARAMETERS) throw new IllegalArgumentException("too many rule parameters");
            for (int index = 1; index < parameters.size(); index++) {
                if (parameters.get(index - 1).name().compareTo(parameters.get(index).name()) >= 0) {
                    throw new IllegalArgumentException("rule parameters must be sorted and unique");
                }
            }
            if (clauses.isEmpty() || clauses.size() > MAX_CLAUSES) {
                throw new IllegalArgumentException("rule clause count");
            }
            Set<Scope> allowed = command == null ? Set.of(Scope.PARAMS, Scope.CONFIG, Scope.CONTEXT, Scope.FACTS)
                    : BindingExpressionV1.RULE_SCOPES;
            for (Clause clause : clauses) {
                switch (clause) {
                    case ExpressionClause expression -> expression.expression().requireScopes(allowed,
                            command == null ? "a rule without a command selector" : "an admission rule");
                    case LookupClause lookup -> {
                        BindingSourceV1.requireScopes(lookup.key(), allowed, "an admission rule");
                        if (lookup.operand() != null) {
                            BindingSourceV1.requireScopes(lookup.operand(), allowed, "an admission rule");
                        }
                    }
                    case FieldClause ignored ->
                            throw new IllegalArgumentException("rules use expression and lookup clauses only");
                }
            }
        }
        /** Returns every scope the rule reads. */
        public Set<Scope> scopes() {
            Set<Scope> scopes = new HashSet<>();
            for (Clause clause : clauses) {
                if (clause instanceof ExpressionClause expression) scopes.addAll(expression.expression().scopes());
                else if (clause instanceof LookupClause lookup) {
                    scopes.addAll(BindingSourceV1.scopes(lookup.key()));
                    if (lookup.operand() != null) scopes.addAll(BindingSourceV1.scopes(lookup.operand()));
                }
            }
            return Set.copyOf(scopes);
        }
        /** A fact rule reads {@code facts.*} and is evaluated only after an approved kernel decision. */
        public boolean readsFacts() { return scopes().contains(Scope.FACTS); }
        /**
         * A static rule has only expression clauses over {@code command}, {@code params}, and {@code config}; it is
         * also evaluated, advisory only, at local ingress.
         */
        public boolean isStatic() {
            return clauses.stream().allMatch(clause -> clause instanceof ExpressionClause)
                    && Set.of(Scope.COMMAND, Scope.PARAMS, Scope.CONFIG).containsAll(scopes());
        }
        /** Returns the parameter declaration with this name, or {@code null}. */
        public Parameter parameter(String name) {
            return parameters.stream().filter(parameter -> parameter.name().equals(name)).findFirst().orElse(null);
        }
        int lookupCount() { return (int) clauses.stream().filter(clause -> clause instanceof LookupClause).count(); }
        Object wire() {
            return Arrays.asList(id, denyCode, command, parameters.stream().map(Parameter::wire).toList(),
                    clauses.stream().map(Clause::wire).toList());
        }
    }

    /** Frozen parameter-type ordinals; {@link #BINDING} values are binding ids, evaluated as text. */
    public enum ParameterType {
        INTEGER, TEXT, BYTES, BOOLEAN, BINDING;

        /** The scalar type rules see for a value of this parameter type. */
        public BindingExpressionV1.Type valueType() {
            return this == BINDING ? BindingExpressionV1.Type.TEXT : BindingExpressionV1.Type.values()[ordinal()];
        }
        /** Whether a scalar has this parameter's value type. */
        public boolean accepts(Object value) {
            return switch (valueType()) {
                case INTEGER -> value instanceof Long;
                case TEXT -> value instanceof String;
                case BYTES -> value instanceof byte[];
                case BOOLEAN -> value instanceof Boolean;
                case TEXT_SET -> false;
            };
        }
    }

    /**
     * One declared rule parameter.
     *
     * @param name CEL identifier {@code [a-zA-Z][a-zA-Z0-9_]{0,62}}, read as {@code params.<name>}
     * @param type value type
     * @param defaultValue value used when an attachment omits the parameter at authoring time, or {@code null}
     */
    public record Parameter(String name, ParameterType type, BindingSourceV1.Literal defaultValue) {
        public Parameter {
            requireParameterName(name);
            Objects.requireNonNull(type, "type");
            if (defaultValue != null && !type.accepts(defaultValue.value())) {
                throw new IllegalArgumentException("rule parameter default type");
            }
        }
        Object wire() {
            return Arrays.asList(name, type.ordinal(), defaultValue == null ? null : defaultValue.value());
        }
    }

    /** One ordered edge: all conditions must hold before its target is derived from the matching event. */
    public record Binding(String id, String sourceComponent, String eventId, List<Clause> conditions, Target target) {
        public Binding {
            requireId(id);
            requireId(sourceComponent);
            BindingExpressionV1.requireName(eventId);
            conditions = List.copyOf(conditions);
            if (conditions.size() > 8) throw new IllegalArgumentException("too many condition clauses");
            Objects.requireNonNull(target, "target");
            for (Clause clause : conditions) {
                switch (clause) {
                    case ExpressionClause expression ->
                            expression.expression().requireScopes(BindingExpressionV1.BINDING_SCOPES, "a binding");
                    case LookupClause lookup -> {
                        BindingSourceV1.requireScopes(lookup.key(), BindingExpressionV1.BINDING_SCOPES, "a binding");
                        if (lookup.operand() != null) {
                            BindingSourceV1.requireScopes(lookup.operand(), BindingExpressionV1.BINDING_SCOPES,
                                    "a binding");
                        }
                    }
                    case FieldClause ignored -> { }
                }
            }
            for (Assignment assignment : target.mapping().fields()) {
                BindingSourceV1.requireScopes(assignment.source(), BindingExpressionV1.BINDING_SCOPES, "a binding");
                if (assignment.source() instanceof BindingSourceV1.Expression expression) {
                    requireNoIn(expression.expression());
                }
            }
            for (Clause clause : conditions) {
                if (clause instanceof ExpressionClause expression) requireNoIn(expression.expression());
                if (clause instanceof LookupClause lookup && lookup.key() instanceof BindingSourceV1.Expression key) {
                    requireNoIn(key.expression());
                }
            }
        }
        /** Only rules can read a text set (a kernel-declared fact), so a binding never uses {@code in}. */
        private static void requireNoIn(BindingExpressionV1 expression) {
            if (expression.usesOperator("in")) {
                throw new IllegalArgumentException("'in' is available only in admission rules");
            }
        }
        Object wire() {
            return List.of(id, sourceComponent, eventId, conditions.stream().map(Clause::wire).toList(), target.wire());
        }
    }

    public sealed interface Clause permits FieldClause, LookupClause, ExpressionClause { Object wire(); }
    /** Frozen wire ordinals for field conditions; adding or reordering values changes the IR contract. */
    public enum Operator { EQ, NE, LT, LE, GT, GE, IN, EXISTS, ABSENT }
    /** A binding condition over one named event field. */
    public record FieldClause(String field, Operator operator,
            List<BindingSourceV1.Literal> operands) implements Clause {
        public FieldClause {
            BindingExpressionV1.requireName(field);
            Objects.requireNonNull(operator, "operator");
            operands = List.copyOf(operands);
            if (operator == Operator.IN ? operands.size() > 64
                    : operands.size() != (operator == Operator.EXISTS || operator == Operator.ABSENT ? 0 : 1)) {
                throw new IllegalArgumentException("condition operand count");
            }
        }
        @Override public Object wire() {
            if (operator == Operator.EXISTS || operator == Operator.ABSENT) return List.of(0, field,
                    operator.ordinal());
            Object operand = operator == Operator.IN ? operands.stream().map(BindingSourceV1.Literal::value).toList()
                    : operands.getFirst().value();
            return List.of(0, field, operator.ordinal(), operand);
        }
    }
    /**
     * Frozen lookup expectation tags; equality compares authenticated value bytes, not decoded objects.
     * {@link #EQUAL_FIELD} compares with a scoped field ({@code [3, scope, name]}).
     */
    public enum Expectation { EXISTS, ABSENT, EQUAL_LITERAL, EQUAL_FIELD }
    public record LookupClause(String participant, BindingSourceV1 key, Expectation expectation,
                               BindingSourceV1 operand) implements Clause {
        public LookupClause {
            requireId(participant);
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(expectation, "expectation");
            boolean valid = switch (expectation) {
                case EXISTS, ABSENT -> operand == null;
                case EQUAL_LITERAL -> operand instanceof BindingSourceV1.Literal literal
                        && literal.value() instanceof byte[];
                case EQUAL_FIELD -> operand instanceof BindingSourceV1.Field;
            };
            if (!valid) throw new IllegalArgumentException("lookup operand");
        }
        @Override public Object wire() {
            Object expected = switch (expectation) {
                case EXISTS, ABSENT -> List.of(expectation.ordinal());
                case EQUAL_LITERAL -> List.of(2, ((BindingSourceV1.Literal) operand).value());
                case EQUAL_FIELD -> {
                    var field = (BindingSourceV1.Field) operand;
                    yield List.of(3, field.scope().ordinal(), field.name());
                }
            };
            return List.of(1, participant, key.wire(), expected);
        }
    }
    public record ExpressionClause(BindingExpressionV1 expression) implements Clause {
        public ExpressionClause {
            if (expression.resultType() != BindingExpressionV1.Type.BOOLEAN) {
                throw new IllegalArgumentException("condition expression must return boolean");
            }
        }
        @Override public Object wire() { return List.of(2, expression.wire()); }
    }

    public record Assignment(String field, BindingSourceV1 source) {
        public Assignment { BindingExpressionV1.requireName(field); Objects.requireNonNull(source, "source"); }
        Object wire() { return List.of(field, source.wire()); }
    }
    /** Frozen mapping tags; raw forwarding does not bypass the target command's evidence restrictions. */
    public enum MappingKind { IDENTITY, FIELDS, RAW_BODY }
    public record Mapping(MappingKind kind, List<Assignment> fields, String bodyField) {
        public Mapping {
            Objects.requireNonNull(kind, "kind");
            fields = List.copyOf(fields);
            if (kind == MappingKind.FIELDS) {
                if (fields.isEmpty() || fields.size() > 16) throw new IllegalArgumentException("mapping field count");
                unique(fields.stream().map(Assignment::field).toList());
            } else if (!fields.isEmpty()) throw new IllegalArgumentException("unexpected mapping fields");
            if (kind == MappingKind.RAW_BODY) BindingExpressionV1.requireName(bodyField);
            else if (bodyField != null) throw new IllegalArgumentException("unexpected raw body field");
        }
        public static Mapping identity() { return new Mapping(MappingKind.IDENTITY, List.of(), null); }
        public static Mapping fields(List<Assignment> fields) { return new Mapping(MappingKind.FIELDS, fields, null); }
        public static Mapping raw(String name) { return new Mapping(MappingKind.RAW_BODY, List.of(), name); }
        Object wire() {
            if (kind == MappingKind.IDENTITY) return List.of(0);
            if (kind == MappingKind.RAW_BODY) return List.of(2, bodyField);
            List<Object> result = new ArrayList<>();
            result.add(1);
            fields.forEach(field -> result.add(field.wire()));
            return result;
        }
    }
    public sealed interface Target permits CommandTarget, EffectTarget { Mapping mapping(); Object wire(); }
    public record CommandTarget(String component, String command, Mapping mapping) implements Target {
        public CommandTarget {
            requireId(component);
            BindingExpressionV1.requireName(command);
            Objects.requireNonNull(mapping, "mapping");
            if (mapping.kind == MappingKind.IDENTITY) throw new IllegalArgumentException("command identity mapping");
        }
        @Override public Object wire() { return List.of(0, component, command, mapping.wire()); }
    }
    public record EffectTarget(String effectType, String gate, String resultPolicy, long expiryBlocks,
                               Mapping mapping) implements Target {
        public EffectTarget {
            BindingExpressionV1.requireName(effectType);
            if (!List.of("app-final", "l1-final").contains(gate)
                    || !List.of("none", "chain").contains(resultPolicy) || expiryBlocks < 0
                    || "none".equals(resultPolicy) && expiryBlocks != 0) {
                throw new IllegalArgumentException("invalid effect target");
            }
            Objects.requireNonNull(mapping, "mapping");
        }
        @Override public Object wire() {
            return List.of(1, effectType, gate, resultPolicy, expiryBlocks, mapping.wire());
        }
    }

    /**
     * Pinned execution bounds with implementation-wide maxima enforced by construction.
     * Derived-work and expression-work limits count attempted work, including work in rejected cascades;
     * they are not counts of successfully committed commands alone. Admission-rule evaluation charges both
     * expression-work counters at every depth, including for denials.
     */
    public record Limits(int maxCascadeDepth, int maxDerivedPerSourceMessage, int maxDerivedPerBlock,
                         int maxEventPayloadBytes, int maxLookupsPerCondition, int maxFunctionCallsPerMapping,
                         int maxFunctionInputBytes, int maxExpressionNodes, int maxExpressionDepth,
                         int maxExpressionValueBytes, int maxExpressionWorkPerCascade, int maxExpressionWorkPerBlock,
                         int maxRulesPerComponent) {
        /** Implementation-wide maximum of {@link #maxRulesPerComponent}. */
        public static final int MAX_RULES_PER_COMPONENT = 16;
        /**
         * Authoring defaults with room for a near-64-KiB baseline tee and bounded downstream work.
         * These are not an arbitrary-fan-out guarantee; decoding preserves every explicit committed limit.
         */
        public static final Limits DEFAULT = new Limits(8, 32, 4096, 65536, 2, 8, 65536,
                128, 16, 65536, 1048576, 33554432, 4);
        public Limits {
            int[] actual = {maxCascadeDepth, maxDerivedPerSourceMessage, maxDerivedPerBlock, maxEventPayloadBytes,
                    maxLookupsPerCondition, maxFunctionCallsPerMapping, maxFunctionInputBytes, maxExpressionNodes,
                    maxExpressionDepth, maxExpressionValueBytes, maxExpressionWorkPerCascade,
                            maxExpressionWorkPerBlock, maxRulesPerComponent};
            int[] maxima = {32, 256, 65536, 65536, 4, 16, 65536, 512, 32, 65536, 4194304, 67108864,
                    MAX_RULES_PER_COMPONENT};
            for (int i = 0; i < actual.length; i++) {
                if (actual[i] < 1 || actual[i] > maxima[i]) throw new IllegalArgumentException("invalid binding limit");
            }
        }
        Object wire() {
            return List.of(maxCascadeDepth, maxDerivedPerSourceMessage, maxDerivedPerBlock, maxEventPayloadBytes,
                    maxLookupsPerCondition, maxFunctionCallsPerMapping, maxFunctionInputBytes, maxExpressionNodes,
                    maxExpressionDepth, maxExpressionValueBytes, maxExpressionWorkPerCascade,
                            maxExpressionWorkPerBlock, maxRulesPerComponent);
        }
    }

    /**
     * Produces canonical profile-commitment bytes, rejecting documents larger than the profile byte limit.
     *
     * @return a newly allocated canonical CBOR envelope
     */
    public byte[] encode() {
        byte[] bytes = BindingCbor.encode(List.of(1, FUNCTIONS, BindingExpressionV1.DIALECT,
                workflowFromHeight, components.stream().map(Component::wire).toList(),
                rules.stream().map(AdmissionRule::wire).toList(),
                bindings.stream().map(Binding::wire).toList(), limits.wire()));
        if (bytes.length > CompositeCommitmentV1.MAX_PROFILE_BYTES)
                throw new IllegalArgumentException("binding IR size");
        return bytes;
    }

    /**
     * Decodes a bounded canonical envelope and checks that reconstruction preserves its exact bytes.
     * Unknown versions/catalogs, unsupported scalar encodings, trailing data, and noncanonical forms fail closed.
     * Documents written before ADR-031.3 fail with an explicit "predates ADR-031.3" error.
     *
     * @throws IllegalArgumentException if the input does not represent a supported canonical document
     */
    public static BindingIrV1 decode(byte[] encoded) {
        Object decoded = BindingCbor.decode(encoded, 65536);
        if (decoded instanceof List<?> legacy && legacy.size() == 7) throw predatesPolicyPlane("binding IR envelope");
        List<?> root = BindingCbor.array(decoded, 8);
        if (BindingCbor.integer(root.getFirst()) != 1 || !FUNCTIONS.equals(root.get(1))
                || !BindingExpressionV1.DIALECT.equals(root.get(2)))
                        throw new IllegalArgumentException("binding catalog");
        long workflowHeight = BindingCbor.integer(root.get(3));
        List<Component> components = list(root.get(4)).stream().map(BindingIrV1::component).toList();
        List<AdmissionRule> rules = list(root.get(5)).stream().map(BindingIrV1::rule).toList();
        List<Binding> bindings = list(root.get(6)).stream().map(BindingIrV1::binding).toList();
        if (root.get(7) instanceof List<?> legacy && legacy.size() == 12) throw predatesPolicyPlane("binding limits");
        List<?> limits = BindingCbor.array(root.get(7), 13);
        int[] v = limits.stream().mapToInt(value -> Math.toIntExact(BindingCbor.integer(value))).toArray();
        BindingIrV1 result = new BindingIrV1(components, rules, bindings, new Limits(v[0], v[1], v[2], v[3], v[4],
                v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12]), workflowHeight);
        if (!Arrays.equals(encoded, result.encode())) throw new IllegalArgumentException("noncanonical binding IR");
        return result;
    }

    /** The explicit decode failure for bytes written before ADR-031.3 amended the v1 layouts. */
    static IllegalArgumentException predatesPolicyPlane(String structure) {
        return new IllegalArgumentException("binding IR predates ADR-031.3: " + structure
                + " has the pre-policy-plane layout; re-create the declarative chain");
    }

    private static Component component(Object value) {
        if (value instanceof List<?> legacy && legacy.size() == 6) throw predatesPolicyPlane("component");
        List<?> fields = BindingCbor.array(value, 7);
        if (!(fields.get(3) instanceof Map<?, ?> raw)) throw new IllegalArgumentException("configuration map");
        Map<String, BindingSourceV1.Literal> config = new LinkedHashMap<>();
        raw.forEach((key, scalar) -> config.put(BindingCbor.text(key), new BindingSourceV1.Literal(scalar)));
        List<RuleAttachment> attachments = list(fields.get(6)).stream().map(entry -> {
            List<?> pair = BindingCbor.array(entry, 2);
            if (!(pair.get(1) instanceof Map<?, ?> parameters)) throw new IllegalArgumentException("parameter map");
            Map<String, BindingSourceV1.Literal> values = new LinkedHashMap<>();
            parameters.forEach((key, scalar) -> values.put(BindingCbor.text(key), new BindingSourceV1.Literal(scalar)));
            return new RuleAttachment(BindingCbor.text(pair.get(0)), values);
        }).toList();
        return new Component(BindingCbor.text(fields.get(0)), BindingCbor.text(fields.get(1)),
                BindingCbor.text(fields.get(2)), config, Math.toIntExact(BindingCbor.integer(fields.get(4))),
                BindingCbor.integer(fields.get(5)), attachments);
    }
    private static AdmissionRule rule(Object value) {
        List<?> fields = BindingCbor.array(value, 5);
        String command = fields.get(2) == null ? null : BindingCbor.text(fields.get(2));
        List<Parameter> parameters = list(fields.get(3)).stream().map(entry -> {
            List<?> parameter = BindingCbor.array(entry, 3);
            BindingSourceV1.Literal fallback = parameter.get(2) == null ? null
                    : new BindingSourceV1.Literal(parameter.get(2));
            return new Parameter(BindingCbor.text(parameter.get(0)),
                    enumAt(ParameterType.values(), parameter.get(1)), fallback);
        }).toList();
        List<Clause> clauses = list(fields.get(4)).stream().map(BindingIrV1::clause).toList();
        return new AdmissionRule(BindingCbor.text(fields.get(0)), BindingCbor.text(fields.get(1)), command,
                parameters, clauses);
    }
    private static Binding binding(Object value) {
        List<?> fields = BindingCbor.array(value, 5);
        return new Binding(BindingCbor.text(fields.get(0)), BindingCbor.text(fields.get(1)),
                BindingCbor.text(fields.get(2)), list(fields.get(3)).stream().map(BindingIrV1::clause).toList(),
                target(fields.get(4)));
    }
    private static Clause clause(Object value) {
        List<?> fields = list(value);
        if (fields.isEmpty()) throw new IllegalArgumentException("clause");
        return switch (Math.toIntExact(BindingCbor.integer(fields.getFirst()))) {
            case 0 -> {
                if (fields.size() < 3 || fields.size() > 4) throw new IllegalArgumentException("field clause");
                Operator op = enumAt(Operator.values(), fields.get(2));
                List<BindingSourceV1.Literal> operands = fields.size() == 3 ? List.of()
                        : op == Operator.IN ? list(fields.get(3)).stream().map(BindingSourceV1.Literal::new).toList()
                        : List.of(new BindingSourceV1.Literal(fields.get(3)));
                yield new FieldClause(BindingCbor.text(fields.get(1)), op, operands);
            }
            case 1 -> {
                BindingCbor.array(fields, 4);
                List<?> expected = list(fields.get(3));
                if (expected.isEmpty()) throw new IllegalArgumentException("lookup expectation");
                Expectation expectation = enumAt(Expectation.values(), expected.getFirst());
                if (expectation == Expectation.EQUAL_FIELD && expected.size() == 2) {
                    throw predatesPolicyPlane("unscoped lookup expectation");
                }
                BindingCbor.array(expected, switch (expectation) {
                    case EXISTS, ABSENT -> 1;
                    case EQUAL_LITERAL -> 2;
                    case EQUAL_FIELD -> 3;
                });
                BindingSourceV1 operand = switch (expectation) {
                    case EXISTS, ABSENT -> null;
                    case EQUAL_LITERAL -> new BindingSourceV1.Literal(expected.get(1));
                    case EQUAL_FIELD -> new BindingSourceV1.Field(BindingExpressionV1.scope(expected.get(1)),
                            BindingCbor.text(expected.get(2)));
                };
                yield new LookupClause(BindingCbor.text(fields.get(1)), BindingSourceV1.fromWire(fields.get(2)),
                        expectation, operand);
            }
            case 2 -> new ExpressionClause(BindingExpressionV1.fromWire(BindingCbor.array(fields, 2).get(1)));
            default -> throw new IllegalArgumentException("unknown clause");
        };
    }
    private static Target target(Object value) {
        List<?> fields = list(value);
        if (fields.isEmpty()) throw new IllegalArgumentException("target");
        return switch (Math.toIntExact(BindingCbor.integer(fields.getFirst()))) {
            case 0 -> {
                BindingCbor.array(fields, 4);
                yield new CommandTarget(BindingCbor.text(fields.get(1)), BindingCbor.text(fields.get(2)),
                        mapping(fields.get(3)));
            }
            case 1 -> {
                BindingCbor.array(fields, 6);
                yield new EffectTarget(BindingCbor.text(fields.get(1)), BindingCbor.text(fields.get(2)),
                        BindingCbor.text(fields.get(3)), BindingCbor.integer(fields.get(4)), mapping(fields.get(5)));
            }
            default -> throw new IllegalArgumentException("unknown target");
        };
    }
    private static Mapping mapping(Object value) {
        List<?> fields = list(value);
        if (fields.isEmpty()) throw new IllegalArgumentException("mapping");
        return switch (Math.toIntExact(BindingCbor.integer(fields.getFirst()))) {
            case 0 -> { BindingCbor.array(fields, 1); yield Mapping.identity(); }
            case 1 -> Mapping.fields(fields.subList(1, fields.size()).stream().map(entry -> {
                List<?> pair = BindingCbor.array(entry, 2);
                return new Assignment(BindingCbor.text(pair.get(0)), BindingSourceV1.fromWire(pair.get(1)));
            }).toList());
            case 2 -> Mapping.raw(BindingCbor.text(BindingCbor.array(fields, 2).get(1)));
            default -> throw new IllegalArgumentException("unknown mapping");
        };
    }
    private static List<?> list(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("expected binding list");
        return list;
    }
    private static <E> E enumAt(E[] values, Object value) {
        long ordinal = BindingCbor.integer(value);
        if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("invalid binding enum");
        return values[(int) ordinal];
    }
    private static void requireId(String id) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,62}")) throw new IllegalArgumentException("invalid binding id");
    }
    /** CEL reserved words match the identifier grammar but cannot be selected as {@code params.<name>}. */
    public static final Set<String> CEL_RESERVED_WORDS = Set.of("in", "as", "break", "const", "continue", "else",
            "false", "for", "function", "if", "import", "let", "loop", "namespace", "null", "package", "return",
            "true", "var", "void", "while");

    static void requireParameterName(String name) {
        if (name == null || !name.matches("[a-zA-Z][a-zA-Z0-9_]{0,62}") || CEL_RESERVED_WORDS.contains(name)) {
            throw new IllegalArgumentException("invalid rule parameter name");
        }
    }
    private static void unique(List<String> values) {
        if (values.stream().distinct().count() != values.size())
                throw new IllegalArgumentException("duplicate binding id or route");
    }
}
