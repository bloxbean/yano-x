package org.yanoproject.x.composite.contracts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Version-one, data-only declarative composition document committed by the composite profile.
 *
 * <p>The canonical CBOR envelope contains the IR version, function-catalog id, expression-dialect id,
 * workflow generation height, components, bindings, and limits, in that order. Declaration order is preserved;
 * map keys are canonicalized by the codec. Reordering bindings can change execution and therefore changes
 * the committed bytes. This contract has no dependency on a host plugin implementation or CEL runtime.
 *
 * <p>Constructors enforce structural bounds. Machine-specific schemas, authorization-sensitive evidence
 * assignments, and graph cycles are checked separately when the runtime constructs a binding program.
 *
 * @param components ordered component instances with fully normalized configuration
 * @param bindings ordered event-to-command or event-to-effect edges
 * @param limits consensus-selected resource limits; these are not node-local tuning parameters
 * @param workflowFromHeight earliest height of this workflow generation; changed binding programs need a new generation
 */
public record BindingIrV1(List<Component> components, List<Binding> bindings, Limits limits, long workflowFromHeight) {
    public static final String FUNCTIONS = "yano-x-binding-functions-v1";

    public BindingIrV1 {
        components = List.copyOf(components);
        bindings = List.copyOf(bindings);
        Objects.requireNonNull(limits, "limits");
        if (workflowFromHeight < 1) throw new IllegalArgumentException("workflow generation height");
        if (components.isEmpty() || components.size() > 16 || bindings.size() > 256) {
            throw new IllegalArgumentException("binding document exceeds component/binding limits");
        }
        unique(components.stream().map(Component::id).toList());
        unique(components.stream().map(Component::ingressTopic).toList());
        unique(bindings.stream().map(Binding::id).toList());
        if (components.stream().anyMatch(component -> component.fromHeight() > workflowFromHeight)) {
            throw new IllegalArgumentException("workflow cannot precede a participant generation");
        }
    }

    /** Constructs a genesis workflow generation; governed replacements supply an explicit generation height. */
    public BindingIrV1(List<Component> components, List<Binding> bindings, Limits limits) {
        this(components, bindings, limits, 1);
    }

    /**
     * One independently configured instance of a catalog-selected state machine.
     * The instance id selects its state namespace; the machine id selects its provider. Configuration must
     * include descriptor defaults before commitment so nodes cannot silently choose different defaults.
     */
    public record Component(String id, String machineId, String ingressTopic,
                            Map<String, BindingSourceV1.Literal> configuration, int maxEffectsPerBlock,
                                    long fromHeight) {
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
        }
        /** Constructs a component generation available from genesis. */
        public Component(String id, String machineId, String ingressTopic,
                         Map<String, BindingSourceV1.Literal> configuration, int maxEffectsPerBlock) {
            this(id, machineId, ingressTopic, configuration, maxEffectsPerBlock, 1);
        }
        Object wire() {
            Map<String, Object> config = new LinkedHashMap<>();
            configuration.forEach((name, value) -> config.put(name, value.value()));
            return List.of(id, machineId, ingressTopic, config, maxEffectsPerBlock, fromHeight);
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
        }
        Object wire() {
            return List.of(id, sourceComponent, eventId, conditions.stream().map(Clause::wire).toList(), target.wire());
        }
    }

    public sealed interface Clause permits FieldClause, LookupClause, ExpressionClause { Object wire(); }
    /** Frozen wire ordinals for field conditions; adding or reordering values changes the IR contract. */
    public enum Operator { EQ, NE, LT, LE, GT, GE, IN, EXISTS, ABSENT }
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
    /** Frozen lookup expectation tags; equality compares authenticated value bytes, not decoded objects. */
    public enum Expectation { EXISTS, ABSENT, EQUAL_LITERAL, EQUAL_EVENT }
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
                case EQUAL_EVENT -> operand instanceof BindingSourceV1.Field;
            };
            if (!valid) throw new IllegalArgumentException("lookup operand");
        }
        @Override public Object wire() {
            Object expected = switch (expectation) {
                case EXISTS, ABSENT -> List.of(expectation.ordinal());
                case EQUAL_LITERAL -> List.of(2, ((BindingSourceV1.Literal) operand).value());
                case EQUAL_EVENT -> List.of(3, ((BindingSourceV1.Field) operand).name());
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
     * they are not counts of successfully committed commands alone.
     */
    public record Limits(int maxCascadeDepth, int maxDerivedPerSourceMessage, int maxDerivedPerBlock,
                         int maxEventPayloadBytes, int maxLookupsPerCondition, int maxFunctionCallsPerMapping,
                         int maxFunctionInputBytes, int maxExpressionNodes, int maxExpressionDepth,
                         int maxExpressionValueBytes, int maxExpressionWorkPerCascade, int maxExpressionWorkPerBlock) {
        public static final Limits DEFAULT = new Limits(8, 32, 4096, 4096, 2, 8, 4096,
                128, 16, 4096, 262144, 4194304);
        public Limits {
            int[] actual = {maxCascadeDepth, maxDerivedPerSourceMessage, maxDerivedPerBlock, maxEventPayloadBytes,
                    maxLookupsPerCondition, maxFunctionCallsPerMapping, maxFunctionInputBytes, maxExpressionNodes,
                    maxExpressionDepth, maxExpressionValueBytes, maxExpressionWorkPerCascade,
                            maxExpressionWorkPerBlock};
            int[] maxima = {32, 256, 65536, 65536, 4, 16, 65536, 512, 32, 65536, 4194304, 67108864};
            for (int i = 0; i < actual.length; i++) {
                if (actual[i] < 1 || actual[i] > maxima[i]) throw new IllegalArgumentException("invalid binding limit");
            }
        }
        Object wire() {
            return List.of(maxCascadeDepth, maxDerivedPerSourceMessage, maxDerivedPerBlock, maxEventPayloadBytes,
                    maxLookupsPerCondition, maxFunctionCallsPerMapping, maxFunctionInputBytes, maxExpressionNodes,
                    maxExpressionDepth, maxExpressionValueBytes, maxExpressionWorkPerCascade,
                            maxExpressionWorkPerBlock);
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
                bindings.stream().map(Binding::wire).toList(), limits.wire()));
        if (bytes.length > CompositeCommitmentV1.MAX_PROFILE_BYTES)
                throw new IllegalArgumentException("binding IR size");
        return bytes;
    }

    /**
     * Decodes a bounded canonical envelope and checks that reconstruction preserves its exact bytes.
     * Unknown versions/catalogs, unsupported scalar encodings, trailing data, and noncanonical forms fail closed.
     *
     * @throws IllegalArgumentException if the input does not represent a supported canonical document
     */
    public static BindingIrV1 decode(byte[] encoded) {
        List<?> root = BindingCbor.array(BindingCbor.decode(encoded, 65536), 7);
        if (BindingCbor.integer(root.getFirst()) != 1 || !FUNCTIONS.equals(root.get(1))
                || !BindingExpressionV1.DIALECT.equals(root.get(2)))
                        throw new IllegalArgumentException("binding catalog");
        long workflowHeight = BindingCbor.integer(root.get(3));
        List<Component> components = list(root.get(4)).stream().map(BindingIrV1::component).toList();
        List<Binding> bindings = list(root.get(5)).stream().map(BindingIrV1::binding).toList();
        List<?> limits = BindingCbor.array(root.get(6), 12);
        int[] v = limits.stream().mapToInt(value -> Math.toIntExact(BindingCbor.integer(value))).toArray();
        BindingIrV1 result = new BindingIrV1(components, bindings, new Limits(v[0], v[1], v[2], v[3], v[4], v[5],
                v[6], v[7], v[8], v[9], v[10], v[11]), workflowHeight);
        if (!Arrays.equals(encoded, result.encode())) throw new IllegalArgumentException("noncanonical binding IR");
        return result;
    }
    private static Component component(Object value) {
        List<?> fields = BindingCbor.array(value, 6);
        if (!(fields.get(3) instanceof Map<?, ?> raw)) throw new IllegalArgumentException("configuration map");
        Map<String, BindingSourceV1.Literal> config = new LinkedHashMap<>();
        raw.forEach((key, scalar) -> config.put(BindingCbor.text(key), new BindingSourceV1.Literal(scalar)));
        return new Component(BindingCbor.text(fields.get(0)), BindingCbor.text(fields.get(1)),
                BindingCbor.text(fields.get(2)), config, Math.toIntExact(BindingCbor.integer(fields.get(4))),
                BindingCbor.integer(fields.get(5)));
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
                BindingCbor.array(expected, expectation.ordinal() < 2 ? 1 : 2);
                BindingSourceV1 operand = switch (expectation) {
                    case EXISTS, ABSENT -> null;
                    case EQUAL_LITERAL -> new BindingSourceV1.Literal(expected.get(1));
                    case EQUAL_EVENT -> new BindingSourceV1.Field(BindingCbor.text(expected.get(1)));
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
    private static void unique(List<String> values) {
        if (values.stream().distinct().count() != values.size())
                throw new IllegalArgumentException("duplicate binding id or route");
    }
}
