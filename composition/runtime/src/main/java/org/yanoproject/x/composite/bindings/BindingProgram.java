package org.yanoproject.x.composite.bindings;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.api.appchain.transition.TransitionWorkBudget;
import org.yanoproject.api.appchain.transition.TransitionWorkReference;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator.Scoped;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.AdmissionRule;
import org.yanoproject.x.composite.contracts.BindingIrV1.Binding;
import org.yanoproject.x.composite.contracts.BindingIrV1.CommandTarget;
import org.yanoproject.x.composite.contracts.BindingIrV1.ExpressionClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.FieldClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.LookupClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.RuleAttachment;
import org.yanoproject.x.composite.contracts.BindingSourceV1;
import org.yanoproject.x.composite.CompositeStateKeys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
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
 *
 * <p>Admission rules (ADR-031.3) are validated here too: every attachment is type-checked against the kernel of
 * the component it is attached to, and resolved into that component's admission and verified-fact slots
 * ({@link BindingRules}). Rules can only deny; nothing here widens what a kernel accepts.
 */
public final class BindingProgram {
    public static final String BASELINE = "composite.command-accepted.v1";
    /**
     * The {@code context.*} fields (ADR-031.3 §5.3). In a binding they describe the step that produced the event:
     * its source block height, the originator's sender, whether it was derived, its depth, and the binding that
     * derived it ({@code ""} for the source step). They are consensus values the engine already holds.
     */
    public static final Map<String, Type> CONTEXT_FIELDS = Map.of("height", Type.INTEGER, "sender", Type.BYTES,
            "derived", Type.BOOLEAN, "depth", Type.INTEGER, "binding", Type.TEXT);
    private final BindingIrV1 ir;
    private final Map<String, TransitionKernel<?, ?>> kernels;
    private final Map<String, List<CommandDescriptor>> commands;
    private final Map<String, List<String>> readParticipants;
    private final Map<TransitionWorkReference, TransitionWorkBudget> workBudgets;
    private final Map<String, Set<TransitionWorkReference>> workReferences;
    private final Map<String, Set<String>> accountingKeys;
    private final BindingRules rules;

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
        Map<String, List<CommandDescriptor>> commandSnapshots = new LinkedHashMap<>();
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
            var commands = List.copyOf(kernel.commands());
            if (commands.stream().map(CommandDescriptor::commandName).distinct().count() != commands.size()) {
                throw invalid("KERNEL_CONTRACT_INVALID", "duplicate command name");
            }
            commandSnapshots.put(entry.getKey(), commands);
        }
        this.commands = Map.copyOf(commandSnapshots);
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
        this.rules = new BindingRules(this, admissionRules());
    }

    /** The validated admission rules of this program. */
    BindingRules rules() { return rules; }

    /**
     * Resolves every attachment into its component's slots, validating rules, parameters, command selection and
     * field reads against the attached kernel. Fact declarations are read once, here, never during execution.
     */
    private Map<String, BindingRules.Component> admissionRules() {
        Map<String, BindingRules.Component> resolved = new LinkedHashMap<>();
        Set<String> attached = new HashSet<>();
        for (int index = 0; index < ir.components().size(); index++) {
            var component = ir.components().get(index);
            if (component.admission().isEmpty()) continue;
            Map<String, RuleFact.Type> declared = declaredFacts(component.id());
            Map<String, Object> configuration = new LinkedHashMap<>();
            component.configuration().forEach((name, value) -> configuration.put(name, value.value()));
            List<BindingRules.Attached> admission = new ArrayList<>();
            List<BindingRules.Attached> facts = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int position = 0; position < component.admission().size(); position++) {
                RuleAttachment attachment = component.admission().get(position);
                var context = BindingValidationException.Context.attachment(component.id(), position,
                        attachment.rule());
                try {
                    AdmissionRule rule = ir.rule(attachment.rule());
                    if (rule == null) throw invalid("RULE_UNKNOWN", "unknown rule: " + attachment.rule());
                    if (!seen.add(rule.id())) throw invalid("RULE_DUPLICATE", "rule attached twice: " + rule.id());
                    attached.add(rule.id());
                    Map<String, Object> parameters = ruleParameters(rule, attachment, component.id());
                    validateRule(rule, component, configuration, declared);
                    var resolvedRule = new BindingRules.Attached(position, rule, parameters, rule.readsFacts(),
                            rule.isStatic());
                    (resolvedRule.factRule() ? facts : admission).add(resolvedRule);
                } catch (IllegalArgumentException invalid) {
                    throw BindingValidationException.wrap("component '" + component.id() + "' rule attachment["
                            + position + "] '" + attachment.rule() + "'", invalid, "UNCLASSIFIED", context);
                }
            }
            resolved.put(component.id(), new BindingRules.Component(admission, facts, configuration, declared));
        }
        for (AdmissionRule rule : ir.rules()) {
            if (!attached.contains(rule.id())) {
                throw BindingValidationException.wrap("rule '" + rule.id() + "'",
                        invalid("RULE_UNATTACHED", "rule is not attached to any component"), "UNCLASSIFIED",
                        BindingValidationException.Context.rule(rule.id(), null));
            }
        }
        return resolved;
    }

    /** A kernel's fact declarations, read once at construction and checked against the host contract. */
    private Map<String, RuleFact.Type> declaredFacts(String component) {
        List<RuleFact> declared;
        try {
            declared = List.copyOf(kernel(component).ruleFacts());
        } catch (RuntimeException invalid) {
            throw invalid("KERNEL_CONTRACT_INVALID", "invalid rule fact declarations: " + component);
        }
        Map<String, RuleFact.Type> types = new LinkedHashMap<>();
        for (RuleFact fact : declared) {
            if (types.putIfAbsent(fact.name(), fact.type()) != null) {
                throw invalid("KERNEL_CONTRACT_INVALID", "duplicate rule fact: " + fact.name());
            }
        }
        if (types.size() > RuleFact.MAX_FACTS) {
            throw invalid("KERNEL_CONTRACT_INVALID", "too many rule facts: " + component);
        }
        return types;
    }

    /** The attachment's parameters, exactly the rule's declarations with values of the declared types. */
    private Map<String, Object> ruleParameters(AdmissionRule rule, RuleAttachment attachment, String component) {
        for (String name : attachment.parameters().keySet()) {
            if (rule.parameter(name) == null) {
                throw BindingValidationException.annotate(invalid("RULE_PARAMETER_UNKNOWN",
                        "undeclared parameter: " + name), BindingValidationException.Context.field("rule-param", name));
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (var parameter : rule.parameters()) {
            var context = BindingValidationException.Context.field("rule-param", parameter.name());
            var value = attachment.parameters().get(parameter.name());
            if (value == null) {
                throw BindingValidationException.annotate(invalid("RULE_PARAMETER_MISSING",
                        "missing parameter: " + parameter.name()), context);
            }
            if (!parameter.type().accepts(value.value())) {
                throw BindingValidationException.annotate(invalid("RULE_PARAMETER_TYPE",
                        "parameter type: " + parameter.name()), context);
            }
            if (parameter.type() == BindingIrV1.ParameterType.BINDING && ir.bindings().stream().noneMatch(binding ->
                    binding.id().equals(value.value()) && binding.target() instanceof CommandTarget target
                            && target.component().equals(component))) {
                throw BindingValidationException.annotate(invalid("RULE_PARAMETER_TYPE", "parameter "
                        + parameter.name() + " must name a binding that targets " + component), context);
            }
            values.put(parameter.name(), value.value());
        }
        return values;
    }

    /** Type-checks one rule against the kernel and configuration of the component it is attached to. */
    private void validateRule(AdmissionRule rule, BindingIrV1.Component component,
                              Map<String, Object> configuration, Map<String, RuleFact.Type> declared) {
        Map<Scope, Map<String, Type>> scopes = new EnumMap<>(Scope.class);
        Set<String> evidence = new HashSet<>();
        if (rule.command() != null) {
            String reason = BindingCommandView.unselectableReason(commands.get(component.id()));
            if (reason != null) {
                throw BindingValidationException.annotate(invalid("RULE_COMMAND_UNSELECTABLE",
                        "rule selects command " + rule.command() + ", but " + reason),
                        BindingValidationException.Context.part("rule"));
            }
            var command = commands.get(component.id()).stream()
                    .filter(candidate -> candidate.commandName().equals(rule.command())).findFirst()
                    .orElseThrow(() -> BindingValidationException.annotate(invalid("RULE_COMMAND_UNKNOWN",
                            "unknown command: " + rule.command()), BindingValidationException.Context.part("rule")));
            Map<String, Type> data = new LinkedHashMap<>();
            for (var field : command.fields()) {
                if (field.role() == CommandDescriptor.Role.DATA) {
                    data.put(field.name(), Type.valueOf(field.type().name()));
                } else {
                    evidence.add(field.name());
                }
            }
            scopes.put(Scope.COMMAND, data);
        }
        Map<String, Type> parameterTypes = new LinkedHashMap<>();
        rule.parameters().forEach(parameter -> parameterTypes.put(parameter.name(), parameter.type().valueType()));
        Map<String, Type> configurationTypes = new LinkedHashMap<>();
        configuration.forEach((name, value) -> configurationTypes.put(name, BindingExpressionEvaluator.type(value)));
        Map<String, Type> factTypes = new LinkedHashMap<>();
        declared.forEach((name, type) -> factTypes.put(name, Type.valueOf(type.name())));
        scopes.put(Scope.PARAMS, parameterTypes);
        scopes.put(Scope.CONFIG, configurationTypes);
        scopes.put(Scope.CONTEXT, CONTEXT_FIELDS);
        scopes.put(Scope.FACTS, factTypes);
        Scoped<Type> schema = new Scoped<>(scopes);
        for (int index = 0; index < rule.clauses().size(); index++) {
            var clause = rule.clauses().get(index);
            var context = new BindingValidationException.Context(null, null, index, "rule", null, null);
            try {
                for (var field : fields(clause)) requireRuleField(field, schema, evidence);
                if (clause instanceof ExpressionClause expression) {
                    try {
                        BindingExpressionEvaluator.validate(expression.expression(), schema, ir.limits());
                    } catch (IllegalArgumentException invalid) {
                        String message = invalid.getMessage() == null ? "" : invalid.getMessage();
                        throw invalid(message.contains("exceeds") ? "RULE_LIMIT" : "EXPRESSION_INVALID", message);
                    }
                } else if (clause instanceof LookupClause lookup) {
                    if (!kernels.containsKey(lookup.participant())) {
                        throw invalid("RULE_LOOKUP_COMPONENT_UNKNOWN", "unknown lookup component: "
                                + lookup.participant());
                    }
                    if (sourceType(lookup.key(), schema) != Type.BYTES) throw invalidType();
                    if (lookup.operand() != null && sourceType(lookup.operand(), schema) != Type.BYTES) {
                        throw invalidType();
                    }
                }
            } catch (IllegalArgumentException invalid) {
                throw at("clause[" + index + "]", invalid, "UNCLASSIFIED", context);
            }
        }
    }

    /** Classifies an unknown or forbidden field read by a rule. */
    private static void requireRuleField(BindingSourceV1.Field field, Scoped<Type> schema, Set<String> evidence) {
        if (schema.of(field.scope()).containsKey(field.name())) return;
        if (field.scope() == Scope.COMMAND && evidence.contains(field.name())) {
            throw invalid("RULE_EVIDENCE_READ", "rules cannot read evidence field command." + field.name());
        }
        throw invalid(field.scope() == Scope.FACTS ? "RULE_FACT_UNKNOWN" : "RULE_FIELD_UNKNOWN",
                "unknown " + field.scope().label() + " field: " + field.name());
    }

    /** Every scoped field a rule clause reads, from expressions, lookup keys and operands alike. */
    private static List<BindingSourceV1.Field> fields(BindingIrV1.Clause clause) {
        List<BindingSourceV1.Field> fields = new ArrayList<>();
        if (clause instanceof ExpressionClause expression) collect(expression.expression().root(), fields);
        if (clause instanceof LookupClause lookup) {
            collect(lookup.key(), fields);
            if (lookup.operand() != null) collect(lookup.operand(), fields);
        }
        return fields;
    }

    private static void collect(BindingSourceV1 source, List<BindingSourceV1.Field> fields) {
        switch (source) {
            case BindingSourceV1.Field field -> fields.add(field);
            case BindingSourceV1.Function function ->
                    function.arguments().forEach(argument -> collect(argument, fields));
            case BindingSourceV1.Expression expression -> collect(expression.expression().root(), fields);
            case BindingSourceV1.Literal ignored -> { }
        }
    }

    private static void collect(BindingExpressionV1.Node node, List<BindingSourceV1.Field> fields) {
        if (node instanceof BindingExpressionV1.Field field) {
            fields.add(new BindingSourceV1.Field(field.scope(), field.name()));
        } else if (node instanceof BindingExpressionV1.Call call) {
            call.arguments().forEach(argument -> collect(argument, fields));
        }
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
        kernel(target.component());
        var matches = commands.get(target.component()).stream()
                .filter(command -> command.commandName().equals(target.command())).toList();
        if (matches.size() != 1) throw invalid("UNKNOWN_TARGET_COMMAND", "unknown or duplicate target command");
        return matches.getFirst();
    }

    /** Returns the construction-time snapshot of a component kernel's command descriptors. */
    public List<CommandDescriptor> commands(String component) {
        kernel(component);
        return commands.get(component);
    }

    /**
     * Builds the descriptor view of a command body (ADR-031.3 §5.4), charging both work counters before each
     * allocation. The component's kernel must be command-selectable.
     *
     * @throws BindingFailure {@code ADMISSION_RULE_INPUT} when the body has no canonical view, or
     *                        {@code EXPRESSION_CAPACITY_EXCEEDED} when a counter is exhausted
     */
    public BindingCommandView.View commandView(String component, byte[] body,
                                               BindingExpressionEvaluator.Budget cascade,
                                               BindingExpressionEvaluator.Budget block) {
        // Descriptors resolve outside the failure mapping: an unknown component is an engine bug, not input.
        List<CommandDescriptor> descriptors = commands(component);
        try {
            return BindingCommandView.decode(descriptors, body, units -> BindingWork.charge(units, cascade, block));
        } catch (IllegalArgumentException | ArithmeticException noView) {
            throw new BindingFailure("ADMISSION_RULE_INPUT");
        }
    }

    /** Declared binding inputs for one source event: its fields and the producing step's context. */
    private static Scoped<Type> bindingScope(Map<String, Type> event) {
        return new Scoped<>(Map.of(Scope.EVENT, event, Scope.CONTEXT, CONTEXT_FIELDS));
    }

    private void validate(Binding binding) {
        Map<String, Type> eventSchema;
        try { eventSchema = schema(binding.sourceComponent(), binding.eventId()); }
        catch (IllegalArgumentException invalid) {
            throw BindingValidationException.annotate(invalid,
                    BindingValidationException.Context.part("source-event"));
        }
        Scoped<Type> schema = bindingScope(eventSchema);
        int lookups = 0;
        for (int index = 0; index < binding.conditions().size(); index++) {
            var clause = binding.conditions().get(index);
            String location = "condition[" + index + "]";
            if (clause instanceof FieldClause field) location += " field '" + field.field() + "'";
            try {
                if (clause instanceof FieldClause field) {
                    Type type = requireField(eventSchema, field.field());
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
                        if (requireField(eventSchema, mapping.bodyField()) != Type.BYTES) throw invalidType();
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
                for (var candidate : commands(target.component())) {
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
                    // Evidence is copied byte for byte from an event field; context values are never evidence.
                    if (field.role() == CommandDescriptor.Role.EVIDENCE
                            && !(source instanceof BindingSourceV1.Field copied && copied.scope() == Scope.EVENT)) {
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

    private Type sourceType(BindingSourceV1 source, Scoped<Type> schema) {
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

    private Type sourceTypeAt(BindingSourceV1 source, Scoped<Type> schema) {
        if (source instanceof BindingSourceV1.Field field) return requireField(schema, field);
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
    public int condition(Binding binding, Map<String, Object> event, Map<String, Object> context,
                         Function<String, AppStateReader> views,
                         BindingExpressionEvaluator.Budget cascade, BindingExpressionEvaluator.Budget block) {
        Scoped<Object> inputs = new Scoped<>(Map.of(Scope.EVENT, event, Scope.CONTEXT, context));
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
                    matches = lookup(lookup, inputs, views, cascade, block);
                } else {
                    Object result = BindingExpressionEvaluator.evaluate(((ExpressionClause) clause).expression(),
                            inputs, ir.limits(), cascade, block);
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
     * Evaluates one lookup clause against the supplied cascade overlays, for binding conditions and rules alike.
     * The key is built from the clause's sources, resolved through the owner's {@code lookupKey}, and read through
     * the overlay, so earlier steps of the same cascade are visible.
     *
     * @throws BindingFailure when the key cannot be built, or a work budget is exhausted
     */
    boolean lookup(LookupClause lookup, Scoped<Object> inputs, Function<String, AppStateReader> views,
                   BindingExpressionEvaluator.Budget cascade, BindingExpressionEvaluator.Budget block) {
        Object key = source(lookup.key(), inputs, cascade, block);
        if (!(key instanceof byte[] bytes)) throw new BindingFailure("LOOKUP_KEY_TYPE");
        if (bytes.length == 0 || bytes.length > ir.limits().maxFunctionInputBytes()) {
            throw new BindingFailure("LOOKUP_KEY_LIMIT");
        }
        BindingWork.charge(bytes.length, cascade, block);
        byte[] localKey;
        try {
            localKey = kernel(lookup.participant()).lookupKey(bytes.clone());
            if (localKey == null || localKey.length == 0) throw new IllegalArgumentException("empty local key");
            CompositeStateKeys.componentKey(lookup.participant(), localKey);
        } catch (IllegalArgumentException malformed) {
            throw new BindingFailure("LOOKUP_KEY_INVALID");
        }
        BindingWork.charge(1L + localKey.length, cascade, block);
        var current = views.apply(lookup.participant()).get(localKey);
        if (current.isPresent()) BindingWork.charge(current.get().length, cascade, block);
        return switch (lookup.expectation()) {
            case EXISTS -> current.isPresent();
            case ABSENT -> current.isEmpty();
            case EQUAL_LITERAL, EQUAL_FIELD -> current.isPresent()
                    && equal(current.get(), source(lookup.operand(), inputs, cascade, block), cascade, block);
        };
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
    public byte[] payload(Binding binding, Map<String, Object> event, Map<String, Object> context, byte[] eventBytes,
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
        Scoped<Object> inputs = new Scoped<>(Map.of(Scope.EVENT, event, Scope.CONTEXT, context));
        Map<String, Object> values = new LinkedHashMap<>();
        mapping.fields().forEach(field -> values.put(field.field(), source(field.source(), inputs, cascade, block)));
        if (binding.target() instanceof CommandTarget target) {
            CommandDescriptor command = command(target);
            for (var field : command.fields()) {
                if (values.containsKey(field.name()) && !field.type().accepts(values.get(field.name()))) {
                    throw new BindingFailure("MAPPING_TYPE_ERROR");
                }
            }
            // One encoder serves mapped commands and, inverted, the ADR-031.3 command view.
            Object tree = BindingCommandView.tree(command, values);
            BindingWork.charge(BindingWork.encoding(tree), cascade, block);
            return BindingCbor.encode(tree);
        }
        BindingWork.charge(BindingWork.encoding(values), cascade, block);
        return BindingCbor.encode(values);
    }

    private Object source(BindingSourceV1 source, Scoped<Object> inputs,
                          BindingExpressionEvaluator.Budget cascade, BindingExpressionEvaluator.Budget block) {
        BindingWork.charge(1, cascade, block);
        if (source instanceof BindingSourceV1.Literal literal) return literal.value();
        if (source instanceof BindingSourceV1.Field field) {
            Map<String, Object> scope = inputs.of(field.scope());
            if (!scope.containsKey(field.name())) throw new BindingFailure("MAPPING_MISSING_FIELD");
            Object value = scope.get(field.name());
            return value instanceof byte[] bytes ? bytes.clone() : value;
        }
        if (source instanceof BindingSourceV1.Expression expression) {
            return BindingExpressionEvaluator.evaluate(expression.expression(), inputs, ir.limits(), cascade, block);
        }
        var function = (BindingSourceV1.Function) source;
        List<Object> args = function.arguments().stream().map(argument -> source(argument, inputs, cascade,
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
    private static Type requireField(Scoped<Type> fields, BindingSourceV1.Field field) {
        if (field.scope() == Scope.EVENT) return requireField(fields.of(Scope.EVENT), field.name());
        // Context fields are fixed by the contract, which rejects any other name at construction.
        Type type = fields.of(field.scope()).get(field.name());
        if (type == null) throw invalid("UNCLASSIFIED", "unknown " + field.scope().label() + " field: " + field.name());
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
