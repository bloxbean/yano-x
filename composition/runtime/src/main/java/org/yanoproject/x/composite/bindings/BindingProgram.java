package org.yanoproject.x.composite.bindings;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.api.appchain.transition.TransitionWorkBudget;
import org.yanoproject.api.appchain.transition.TransitionWorkReference;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.Binding;
import org.yanoproject.x.composite.contracts.BindingIrV1.CommandTarget;
import org.yanoproject.x.composite.contracts.BindingIrV1.ExpressionClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.FieldClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.LookupClause;
import org.yanoproject.x.composite.contracts.BindingSourceV1;
import org.yanoproject.x.composite.CompositeStateKeys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Validated binding graph and deterministic condition/mapping operations shared by live and offline execution.
 *
 * <p>Construction checks catalog membership, event and command schemas, evidence assignments, expression
 * types, and command-derivation cycles. Validation cannot guarantee that an optional event field is present
 * or that data-dependent decoding succeeds; those cases are rejected during evaluation with a stable code.
 * This class never writes state, emits effects, or grants authority to a mapped command.
 * Construction diagnostics retain binding context and identify mapping fields or zero-based condition
 * and function-argument indexes. They describe declarations, never literal or evaluated values.
 */
public final class BindingProgram {
    public static final String BASELINE = "composite.command-accepted.v1";
    private final BindingIrV1 ir;
    private final Map<String, TransitionKernel<?, ?>> kernels;
    private final Map<String, List<String>> readParticipants;
    private final Map<TransitionWorkReference, TransitionWorkBudget> workBudgets;
    private final Map<String, Set<TransitionWorkReference>> workReferences;
    private final Map<String, Set<String>> accountingKeys;

    /**
     * Validates a document against the exact kernels selected for its component instances.
     *
     * @param ir committed document; declaration order controls binding execution order
     * @param kernels kernels indexed by component instance id, not machine type id
     * @throws IllegalArgumentException if schemas, graph structure, limits, or evidence mappings are invalid
     */
    public BindingProgram(BindingIrV1 ir, Map<String, TransitionKernel<?, ?>> kernels) {
        this.ir = ir;
        this.kernels = Map.copyOf(kernels);
        Set<String> components = new HashSet<>();
        ir.components().forEach(component -> components.add(component.id()));
        if (!components.equals(kernels.keySet())) throw invalid("KERNEL_CONTRACT_INVALID", "kernel/component mismatch");
        Map<String, List<String>> participantReads = new LinkedHashMap<>();
        for (var entry : this.kernels.entrySet()) {
            var kernel = entry.getValue();
            List<String> reads = List.copyOf(kernel.readParticipants());
            if (reads.size() > components.size() || reads.stream().distinct().count() != reads.size()
                    || reads.contains(entry.getKey()) || !components.containsAll(reads)) {
                throw invalid("KERNEL_CONTRACT_INVALID", "invalid kernel read participants: " + entry.getKey());
            }
            participantReads.put(entry.getKey(), reads);
            var events = kernel.events();
            if (events.stream().anyMatch(event -> BASELINE.equals(event.eventId()))
                    || events.stream().map(event -> event.eventId()).distinct().count() != events.size()) {
                throw invalid("KERNEL_CONTRACT_INVALID", "reserved or duplicate native event id");
            }
            var commands = kernel.commands();
            if (commands.stream().map(CommandDescriptor::commandName).distinct().count() != commands.size()) {
                throw invalid("KERNEL_CONTRACT_INVALID", "duplicate command name");
            }
        }
        this.readParticipants = Map.copyOf(participantReads);
        Map<TransitionWorkReference, TransitionWorkBudget> budgets = new LinkedHashMap<>();
        Map<String, Set<String>> reserved = new LinkedHashMap<>();
        for (var entry : this.kernels.entrySet()) {
            Set<String> keys = new HashSet<>();
            var declared = List.copyOf(entry.getValue().workBudgets());
            if (declared.size() > TransitionWorkBudget.MAX_DECLARATIONS) {
                throw invalid("KERNEL_CONTRACT_INVALID", "too many work budgets");
            }
            for (var budget : declared) {
                CompositeStateKeys.componentKey(entry.getKey(), budget.key());
                var reference = new TransitionWorkReference(entry.getKey(), budget.id());
                if (budgets.putIfAbsent(reference, budget) != null
                        || !keys.add(HexFormat.of().formatHex(budget.key()))) {
                    throw invalid("KERNEL_CONTRACT_INVALID", "duplicate work budget id or key");
                }
            }
            reserved.put(entry.getKey(), Set.copyOf(keys));
        }
        Map<String, Set<TransitionWorkReference>> references = new LinkedHashMap<>();
        for (var entry : this.kernels.entrySet()) {
            var declared = List.copyOf(entry.getValue().workReferences());
            if (declared.size() > TransitionWorkBudget.MAX_DECLARATIONS
                    || new HashSet<>(declared).size() != declared.size()
                    || !budgets.keySet().containsAll(declared)) {
                throw invalid("KERNEL_CONTRACT_INVALID", "invalid kernel work references: " + entry.getKey());
            }
            references.put(entry.getKey(), Set.copyOf(declared));
        }
        this.workBudgets = Map.copyOf(budgets);
        this.workReferences = Map.copyOf(references);
        this.accountingKeys = Map.copyOf(reserved);
        for (int index = 0; index < ir.bindings().size(); index++) {
            Binding binding = ir.bindings().get(index);
            try { validate(binding); }
            catch (IllegalArgumentException invalid) {
                throw BindingValidationException.wrap("binding '" + binding.id() + "' (" + binding.sourceComponent()
                        + "/" + binding.eventId() + ")", invalid, "UNCLASSIFIED",
                        new BindingValidationException.Context(index, binding.id(), null, null, null, null));
            }
        }
        for (String component : components) visit(component, new HashSet<>(), new HashSet<>());
    }
    public BindingIrV1 ir() { return ir; }
    /** Resolves a caller's statically declared reservation; a dynamic undeclared request fails closed. */
    public TransitionWorkBudget workBudget(String caller, TransitionWorkReference reference) {
        kernel(caller);
        if (!workReferences.get(caller).contains(reference)) {
            throw new BindingFailure("UNDECLARED_WORK_REFERENCE");
        }
        return workBudgets.get(reference);
    }

    /** Accounting keys are engine-owned: ordinary plans may never overwrite their non-refundable charges. */
    public boolean isAccountingKey(String component, byte[] key) {
        return accountingKeys.get(component).contains(HexFormat.of().formatHex(key));
    }
    /** Returns the construction-time snapshot of foreign read permissions for this component instance. */
    public List<String> readParticipants(String component) {
        kernel(component);
        return readParticipants.get(component);
    }
    public TransitionKernel<?, ?> kernel(String component) {
        TransitionKernel<?, ?> kernel = kernels.get(component);
        if (kernel == null) throw invalid("UNKNOWN_COMPONENT", "unknown component: " + component);
        return kernel;
    }
    public List<Binding> bindings(String component, String eventId) {
        return ir.bindings().stream().filter(binding -> binding.sourceComponent().equals(component)
                && binding.eventId().equals(eventId)).toList();
    }
    public Map<String, Type> schema(String component, String eventId) {
        kernel(component);
        if (BASELINE.equals(eventId)) return Map.of("topic", Type.TEXT, "sender", Type.BYTES,
                "messageId", Type.BYTES, "body", Type.BYTES, "bodyHash", Type.BYTES, "bodyLength", Type.INTEGER);
        var events = kernel(component).events().stream().filter(event -> event.eventId().equals(eventId)).toList();
        if (events.size() != 1) throw invalid("UNKNOWN_EVENT", "unknown or duplicate event: " + eventId);
        Map<String, Type> result = new LinkedHashMap<>();
        events.getFirst().fields().forEach(field -> result.put(field.name(), Type.valueOf(field.type().name())));
        return Map.copyOf(result);
    }
    public CommandDescriptor command(CommandTarget target) {
        var commands = kernel(target.component()).commands().stream()
                .filter(command -> command.commandName().equals(target.command())).toList();
        if (commands.size() != 1) throw invalid("UNKNOWN_TARGET_COMMAND", "unknown or duplicate target command");
        return commands.getFirst();
    }

    private void validate(Binding binding) {
        Map<String, Type> schema;
        try { schema = schema(binding.sourceComponent(), binding.eventId()); }
        catch (IllegalArgumentException invalid) {
            throw BindingValidationException.annotate(invalid,
                    BindingValidationException.Context.part("source-event"));
        }
        int lookups = 0;
        for (int index = 0; index < binding.conditions().size(); index++) {
            var clause = binding.conditions().get(index);
            String location = "condition[" + index + "]";
            if (clause instanceof FieldClause field) location += " field '" + field.field() + "'";
            try {
                if (clause instanceof FieldClause field) {
                    Type type = requireField(schema, field.field());
                    for (var operand : field.operands()) {
                        if (BindingExpressionEvaluator.type(operand.value()) != type) throw invalidType();
                    }
                    boolean valid = switch (field.operator()) {
                        case LT, LE, GT, GE -> type == Type.INTEGER;
                        case IN, EXISTS, ABSENT -> type != Type.BOOLEAN;
                        default -> true;
                    };
                    if (!valid) throw invalidType();
                } else if (clause instanceof LookupClause lookup) {
                    kernel(lookup.participant());
                    if (++lookups > ir.limits().maxLookupsPerCondition())
                        throw invalid("LOOKUP_LIMIT", "lookup limit");
                    validateAt("lookup key", "UNCLASSIFIED", BindingValidationException.Context.part("lookup-key"),
                            () -> {
                                if (sourceType(lookup.key(), schema) != Type.BYTES) throw invalidType();
                            });
                    if (lookup.operand() != null) validateAt("lookup operand", "UNCLASSIFIED",
                            BindingValidationException.Context.part("lookup-operand"), () -> {
                                if (sourceType(lookup.operand(), schema) != Type.BYTES) throw invalidType();
                            });
                } else if (clause instanceof ExpressionClause expression) {
                    validateAt("expression", "EXPRESSION_INVALID",
                            BindingValidationException.Context.part("expression"), () ->
                            BindingExpressionEvaluator.validate(expression.expression(), schema, ir.limits()));
                }
            } catch (IllegalArgumentException invalid) {
                throw at(location, invalid, "UNCLASSIFIED",
                        new BindingValidationException.Context(null, null, index, "condition", null, null));
            }
        }
        var mapping = binding.target().mapping();
        int calls = mapping.fields().stream().mapToInt(field -> functionCount(field.source())).sum();
        if (calls > ir.limits().maxFunctionCallsPerMapping()) {
            throw BindingValidationException.annotate(invalid("FUNCTION_CALL_LIMIT", "mapping function limit"),
                    BindingValidationException.Context.part("mapping"));
        }
        if (mapping.kind() == BindingIrV1.MappingKind.RAW_BODY) {
            validateAt("raw body field '" + mapping.bodyField() + "'", "UNCLASSIFIED",
                    BindingValidationException.Context.part("raw-body"), () -> {
                        if (requireField(schema, mapping.bodyField()) != Type.BYTES) throw invalidType();
                    });
        }
        mapping.fields().forEach(field -> validateAt("mapping field '" + field.field() + "'", "UNCLASSIFIED",
                BindingValidationException.Context.field("mapping", field.field()),
                () -> sourceType(field.source(), schema)));
        if (binding.target() instanceof CommandTarget target) {
            CommandDescriptor command;
            try { command = command(target); }
            catch (IllegalArgumentException invalid) {
                throw BindingValidationException.annotate(invalid,
                        BindingValidationException.Context.part("target-command"));
            }
            if (mapping.kind() == BindingIrV1.MappingKind.RAW_BODY) {
                // Raw bytes can select any opcode understood by the target codec, not just the
                // command named in the binding. Never let a harmless descriptor hide an evidence path.
                for (var candidate : kernel(target.component()).commands()) {
                    for (var field : candidate.fields()) {
                        if (field.role() == CommandDescriptor.Role.EVIDENCE) {
                            throw BindingValidationException.annotate(invalid("BINDING_EVIDENCE_UNSATISFIABLE",
                                    "raw body field '" + mapping.bodyField() + "', target command '"
                                            + candidate.commandName() + "' evidence field '" + field.name()
                                            + "': BINDING_EVIDENCE_UNSATISFIABLE"),
                                    BindingValidationException.Context.part("raw-body"));
                        }
                    }
                }
                return;
            }
            Map<String, BindingSourceV1> assigned = new LinkedHashMap<>();
            mapping.fields().forEach(field -> assigned.put(field.field(), field.source()));
            if (command.layout() == CommandDescriptor.Layout.RAW_BYTES) {
                throw BindingValidationException.annotate(invalid("RAW_TARGET_REQUIRES_RAW_MAPPING",
                        "raw target requires raw mapping"), BindingValidationException.Context.part("target"));
            }
            for (CommandDescriptor.Field field : command.fields()) {
                BindingSourceV1 source = assigned.remove(field.name());
                validateAt("target field '" + field.name() + "'"
                        + (field.role() == CommandDescriptor.Role.EVIDENCE ? " (evidence)" : ""), "UNCLASSIFIED",
                        BindingValidationException.Context.field("target-field", field.name()), () -> {
                    if (field.role() == CommandDescriptor.Role.EVIDENCE
                            && !(source instanceof BindingSourceV1.Field)) {
                        throw invalid("BINDING_EVIDENCE_UNSATISFIABLE", "BINDING_EVIDENCE_UNSATISFIABLE");
                    }
                    if (source == null && (field.required() || command.layout() != CommandDescriptor.Layout.MAP)) {
                        throw invalid("MISSING_TARGET_FIELD", "missing mapped field: " + field.name());
                    }
                    if (source != null) {
                        Type type = sourceType(source, schema);
                        if (type != null && !type.name().equals(field.type().name())) throw invalidType();
                    }
                });
            }
            if (!assigned.isEmpty()) {
                throw BindingValidationException.annotate(invalid("UNKNOWN_TARGET_FIELD",
                        "unknown target fields: " + String.join(", ", assigned.keySet())),
                        BindingValidationException.Context.field("target-field", assigned.keySet().iterator().next()));
            }
        }
    }

    private Type sourceType(BindingSourceV1 source, Map<String, Type> schema) {
        String location = source instanceof BindingSourceV1.Function function
                ? "function '" + function.functionId() + "'"
                : source instanceof BindingSourceV1.Expression ? "expression" : "source";
        try {
            return sourceTypeAt(source, schema);
        } catch (IllegalArgumentException invalid) {
            throw at(location, invalid, source instanceof BindingSourceV1.Expression ? "EXPRESSION_INVALID"
                    : "UNCLASSIFIED", BindingValidationException.Context.NONE);
        }
    }

    private Type sourceTypeAt(BindingSourceV1 source, Map<String, Type> schema) {
        if (source instanceof BindingSourceV1.Field field) return requireField(schema, field.name());
        if (source instanceof BindingSourceV1.Literal literal) return BindingExpressionEvaluator.type(literal.value());
        if (source instanceof BindingSourceV1.Expression expression) {
            BindingExpressionEvaluator.validate(expression.expression(), schema, ir.limits());
            return expression.expression().resultType();
        }
        var function = (BindingSourceV1.Function) source;
        List<Type> args = new ArrayList<>();
        for (int index = 0; index < function.arguments().size(); index++) {
            try {
                args.add(sourceType(function.arguments().get(index), schema));
            } catch (IllegalArgumentException invalid) {
                throw at("argument[" + index + "]", invalid, "UNCLASSIFIED",
                        BindingValidationException.Context.argument(index));
            }
        }
        Type first = args.getFirst();
        return switch (function.functionId()) {
            case "blake2b-256", "sha-256", "byte-length" -> {
                if (args.size() != 1 || first != Type.BYTES && first != Type.TEXT) throw invalidType();
                yield function.functionId().equals("byte-length") ? Type.INTEGER : Type.BYTES;
            }
            case "concat" -> {
                if (args.size() < 2 || first != Type.BYTES && first != Type.TEXT
                        || args.stream().anyMatch(type -> type != first)) throw invalidType();
                yield first;
            }
            case "hex" -> { if (args.size() != 1 || first != Type.BYTES) throw invalidType(); yield Type.TEXT; }
            case "utf8-bytes" -> { if (args.size() != 1 || first != Type.TEXT) throw invalidType(); yield Type.BYTES; }
            case "cbor-encode" -> { if (args.size() != 1) throw invalidType(); yield Type.BYTES; }
            case "cbor-field" -> {
                if (args.size() != 2 || first != Type.BYTES || args.get(1) != Type.TEXT) throw invalidType();
                yield null; // Schema-opaque field: destination type must be checked on the resulting value.
            }
            default -> throw invalid("UNKNOWN_FUNCTION", "unknown binding function: " + function.functionId());
        };
    }

    private void visit(String component, Set<String> active, Set<String> done) {
        if (done.contains(component)) return;
        if (!active.add(component)) throw invalid("CYCLIC_BINDING_GRAPH", "cyclic binding graph");
        for (Binding binding : ir.bindings()) {
            if (binding.sourceComponent().equals(component) && binding.target() instanceof CommandTarget target) {
                visit(target.component(), active, done);
            }
        }
        active.remove(component);
        done.add(component);
    }

    /**
     * Evaluates the conjunction in declaration order, stopping at the first false clause.
     * Lookups use component-local keys against the supplied cascade overlays; callers must not provide
     * node-local indexes or external state. False conditions skip a binding, whereas evaluation errors
     * reject its enclosing cascade. Budget charges are retained in either case.
     *
     * @return {@code -1} when all clauses hold, otherwise the zero-based first failing clause ordinal
     * @throws BindingFailure on data-dependent decoding, type, or resource-limit errors
     */
    public int condition(Binding binding, Map<String, Object> event, Function<String, AppStateReader> views,
                         BindingExpressionEvaluator.Budget cascade, BindingExpressionEvaluator.Budget block) {
        try { BindingWork.charge(1, cascade, block); }
        catch (BindingFailure failure) { throw failure.at(binding.id(), -1); }
        for (int index = 0; index < binding.conditions().size(); index++) {
            try {
                BindingWork.charge(1, cascade, block);
                var clause = binding.conditions().get(index);
                boolean matches;
                if (clause instanceof FieldClause field) {
                    Object value = event.get(field.field());
                    matches = switch (field.operator()) {
                        case EXISTS -> value != null;
                        case ABSENT -> value == null;
                        case IN -> value != null && field.operands().stream()
                                .anyMatch(operand -> equal(value, operand.value(), cascade, block));
                        case EQ -> value != null && equal(value, field.operands().getFirst().value(), cascade, block);
                        case NE -> value != null && !equal(value, field.operands().getFirst().value(), cascade, block);
                        case LT, LE, GT, GE -> {
                            if (value == null) yield false;
                            if (!(value instanceof Long number)) throw new BindingFailure("EVENT_TYPE_ERROR");
                            long expected = (Long) field.operands().getFirst().value();
                            yield switch (field.operator()) {
                                case LT -> number < expected;
                                case LE -> number <= expected;
                                case GT -> number > expected;
                                default -> number >= expected;
                            };
                        }
                    };
                } else if (clause instanceof LookupClause lookup) {
                    Object key = source(lookup.key(), event, cascade, block);
                    if (!(key instanceof byte[] bytes)) throw new BindingFailure("LOOKUP_KEY_TYPE");
                    if (bytes.length == 0 || bytes.length > ir.limits().maxFunctionInputBytes()) {
                        throw new BindingFailure("LOOKUP_KEY_LIMIT");
                    }
                    BindingWork.charge(bytes.length, cascade, block);
                    byte[] localKey;
                    try {
                        localKey = kernel(lookup.participant()).lookupKey(bytes.clone());
                        if (localKey == null || localKey.length == 0)
                                throw new IllegalArgumentException("empty local key");
                        CompositeStateKeys.componentKey(lookup.participant(), localKey);
                    } catch (IllegalArgumentException malformed) {
                        throw new BindingFailure("LOOKUP_KEY_INVALID");
                    }
                    BindingWork.charge(1L + localKey.length, cascade, block);
                    var current = views.apply(lookup.participant()).get(localKey);
                    if (current.isPresent()) BindingWork.charge(current.get().length, cascade, block);
                    matches = switch (lookup.expectation()) {
                        case EXISTS -> current.isPresent();
                        case ABSENT -> current.isEmpty();
                        case EQUAL_LITERAL, EQUAL_EVENT -> current.isPresent()
                                && equal(current.get(), source(lookup.operand(), event, cascade, block),
                                        cascade, block);
                    };
                } else {
                    Object result = BindingExpressionEvaluator.evaluate(((ExpressionClause) clause).expression(), event,
                            ir.limits(), cascade, block);
                    matches = Boolean.TRUE.equals(result);
                }
                if (!matches) return index;
            } catch (BindingFailure failure) {
                throw failure.at(binding.id(), index);
            }
        }
        return -1;
    }

    /**
     * Builds target wire bytes without invoking the target or modifying state.
     * Field mappings follow the target descriptor's wire layout and positional field order. Identity
     * mappings copy event bytes for effects; raw mappings copy one byte-valued event field. Target admission
     * and transition validation still run after this operation and remain the authority boundary.
     *
     * @return newly encoded or defensively copied target payload
     * @throws BindingFailure if required event data is missing, mistyped, or exceeds evaluation limits
     */
    public byte[] payload(Binding binding, Map<String, Object> event, byte[] eventBytes,
                          BindingExpressionEvaluator.Budget cascade, BindingExpressionEvaluator.Budget block) {
        var mapping = binding.target().mapping();
        BindingWork.charge(1, cascade, block);
        if (mapping.kind() == BindingIrV1.MappingKind.IDENTITY) {
            BindingWork.charge(eventBytes.length, cascade, block);
            return eventBytes.clone();
        }
        if (mapping.kind() == BindingIrV1.MappingKind.RAW_BODY) {
            Object value = event.get(mapping.bodyField());
            if (!(value instanceof byte[] bytes)) throw new BindingFailure("MAPPING_TYPE_ERROR");
            BindingWork.charge(bytes.length, cascade, block);
            return bytes.clone();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        mapping.fields().forEach(field -> values.put(field.field(), source(field.source(), event, cascade, block)));
        if (binding.target() instanceof CommandTarget target) {
            CommandDescriptor command = command(target);
            for (var field : command.fields()) {
                if (values.containsKey(field.name()) && !field.type().accepts(values.get(field.name()))) {
                    throw new BindingFailure("MAPPING_TYPE_ERROR");
                }
            }
            if (command.layout() != CommandDescriptor.Layout.MAP) {
                List<Object> array = new ArrayList<>();
                if (command.layout() == CommandDescriptor.Layout.ARRAY_WITH_OPCODE) array.add(command.opCode());
                command.fields().forEach(field -> array.add(values.get(field.name())));
                BindingWork.charge(BindingWork.encoding(array), cascade, block);
                return BindingCbor.encode(array);
            }
        }
        BindingWork.charge(BindingWork.encoding(values), cascade, block);
        return BindingCbor.encode(values);
    }

    private Object source(BindingSourceV1 source, Map<String, Object> event,
                          BindingExpressionEvaluator.Budget cascade, BindingExpressionEvaluator.Budget block) {
        BindingWork.charge(1, cascade, block);
        if (source instanceof BindingSourceV1.Literal literal) return literal.value();
        if (source instanceof BindingSourceV1.Field field) {
            if (!event.containsKey(field.name())) throw new BindingFailure("MAPPING_MISSING_FIELD");
            return event.get(field.name());
        }
        if (source instanceof BindingSourceV1.Expression expression) {
            return BindingExpressionEvaluator.evaluate(expression.expression(), event, ir.limits(), cascade, block);
        }
        var function = (BindingSourceV1.Function) source;
        List<Object> args = function.arguments().stream().map(argument -> source(argument, event, cascade,
                block)).toList();
        long bytes = args.stream().mapToLong(BindingWork::size).sum();
        if (bytes > ir.limits().maxFunctionInputBytes()) throw new BindingFailure("FUNCTION_INPUT_LIMIT");
        BindingWork.charge(bytes, cascade, block);
        Object first = args.getFirst();
        long outputWork = switch (function.functionId()) {
            case "blake2b-256", "sha-256" -> 32;
            case "byte-length" -> 9;
            case "hex" -> 2L * BindingWork.size(first);
            case "cbor-encode" -> BindingWork.encoding(first);
            case "cbor-field" -> BindingWork.size(first);
            default -> bytes;
        };
        BindingWork.charge(outputWork, cascade, block);
        if (!function.functionId().equals("cbor-field") && !function.functionId().equals("cbor-encode")
                && outputWork > ir.limits().maxFunctionInputBytes()) {
            throw new BindingFailure("FUNCTION_OUTPUT_LIMIT");
        }
        Object result = switch (function.functionId()) {
            case "blake2b-256" -> Blake2bUtil.blake2bHash256(bytes(first));
            case "sha-256" -> sha256(bytes(first));
            case "byte-length" -> (long) bytes(first).length;
            case "hex" -> HexFormat.of().formatHex((byte[]) first);
            case "utf8-bytes" -> bytes(first);
            case "cbor-encode" -> BindingCbor.encode(first);
            case "cbor-field" -> {
                Map<String, Object> fields;
                try { fields = TransitionScalars.decode((byte[]) first); }
                catch (IllegalArgumentException invalid) { throw new BindingFailure("FUNCTION_INVALID_CBOR"); }
                Object value = fields.get((String) args.get(1));
                if (value == null) throw new BindingFailure("FUNCTION_MISSING_FIELD");
                yield value;
            }
            case "concat" -> {
                if (first instanceof String) {
                    StringBuilder builder = new StringBuilder();
                    args.forEach(value -> builder.append((String) value));
                    yield builder.toString();
                }
                byte[] joined = new byte[(int) bytes];
                int offset = 0;
                for (Object argument : args) {
                    byte[] part = (byte[]) argument;
                    System.arraycopy(part, 0, joined, offset, part.length);
                    offset += part.length;
                }
                yield joined;
            }
            default -> throw new IllegalStateException("unvalidated function");
        };
        if (BindingWork.size(result) > ir.limits().maxFunctionInputBytes()) {
            throw new BindingFailure("FUNCTION_OUTPUT_LIMIT");
        }
        return result;
    }
    private static Type requireField(Map<String, Type> fields, String name) {
        Type type = fields.get(name);
        if (type == null) throw invalid("UNKNOWN_EVENT_FIELD", "unknown event field: " + name);
        return type;
    }
    private static int functionCount(BindingSourceV1 source) {
        return source instanceof BindingSourceV1.Function function
                ? 1 + function.arguments().stream().mapToInt(BindingProgram::functionCount).sum() : 0;
    }
    private static boolean equal(Object a, Object b, BindingExpressionEvaluator.Budget cascade,
                                 BindingExpressionEvaluator.Budget block) {
        BindingWork.charge(1L + BindingWork.size(a) + BindingWork.size(b), cascade, block);
        return a instanceof byte[] bytes && b instanceof byte[] other ? Arrays.equals(bytes, other) : a.equals(b);
    }
    private static byte[] bytes(Object value) {
        return value instanceof byte[] bytes ? bytes : ((String) value).getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IllegalArgumentException invalidType() {
        return invalid("BINDING_TYPE_MISMATCH", "binding type mismatch");
    }

    private static BindingValidationException invalid(String code, String message) {
        return BindingValidationException.of(code, message);
    }

    /** Adds declaration-only context without rendering source records, which may contain private literals. */
    private static void validateAt(String location, String fallbackCode, BindingValidationException.Context context,
                                   Runnable validation) {
        try { validation.run(); }
        catch (IllegalArgumentException invalid) { throw at(location, invalid, fallbackCode, context); }
    }

    /** Historical {@code "<location>: <message>"} wrapping, retaining a stable code and structured context. */
    private static IllegalArgumentException at(String location, IllegalArgumentException invalid, String fallbackCode,
                                               BindingValidationException.Context context) {
        return BindingValidationException.wrap(location, invalid, fallbackCode, context);
    }
}
