package org.yanoproject.x.devtools;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.Assignment;
import org.yanoproject.x.composite.contracts.BindingIrV1.Binding;
import org.yanoproject.x.composite.contracts.BindingIrV1.Clause;
import org.yanoproject.x.composite.contracts.BindingIrV1.CommandTarget;
import org.yanoproject.x.composite.contracts.BindingIrV1.Component;
import org.yanoproject.x.composite.contracts.BindingIrV1.EffectTarget;
import org.yanoproject.x.composite.contracts.BindingIrV1.Expectation;
import org.yanoproject.x.composite.contracts.BindingIrV1.ExpressionClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.FieldClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.Limits;
import org.yanoproject.x.composite.contracts.BindingIrV1.LookupClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.Mapping;
import org.yanoproject.x.composite.contracts.BindingIrV1.Operator;
import org.yanoproject.x.composite.contracts.BindingIrV1.Target;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Offline, strict YAML authoring front end for ADR-031.1's canonical binding IR.
 *
 * <p>The input is the blueprint's {@code composite} object, either directly or under one {@code composite}
 * property, not an entire node configuration or blueprint.
 * Components and bindings retain declaration order. Configuration keys and assignment keys do not: maps
 * are normalized so changing YAML map order does not change committed bytes. Duplicate keys, unknown
 * properties, scalar type mismatches, aliases, custom tags, and additional YAML documents fail closed.
 *
 * <p>This compiler never discovers or instantiates plugins. The caller supplies descriptors from its exact
 * selected catalog, and must subsequently construct the real declarative profile to validate command
 * layouts, evidence rules, graph cycles, and runtime compatibility. Successful parsing alone is not profile
 * validation and does not authorize a profile activation.
 */
public final class BindingDocumentCompiler {
    /** Maximum UTF-16 source length accepted before invoking the bounded YAML parser. */
    public static final int MAX_SOURCE_CHARACTERS = 262_144;

    /**
     * Catalog-backed descriptor access used during authoring; implementations must fail for unknown machines/events.
     *
     * <p>The component passed to {@link #eventFields} already contains all normalized configuration defaults,
     * allowing configuration-dependent event schemas without consulting arbitrary node-local settings.
     */
    public interface DescriptorCatalog {
        /**
         * Returns the exact selected machine's accepted configuration and committed defaults.
         *
         * @param machineId selected provider id
         * @return its configuration schema, including defaults
         */
        ConfigurationDescriptor configuration(String machineId);

        /**
         * Resolves configuration-dependent descriptors when a provider cannot be constructed without settings.
         * Implementations may use these authored settings to resolve the selected kernel, but must not add
         * node-local defaults or select an uncatalogued provider. The default uses a static descriptor.
         *
         * @param machineId selected provider id
         * @param authoredConfig parsed scalar settings, before descriptor defaults have been filled
         * @return the descriptor used to normalize and commit the configuration
         */
        default ConfigurationDescriptor configuration(String machineId, Map<String, Object> authoredConfig) {
            return configuration(machineId);
        }

        /**
         * Returns scalar fields for one native or baseline event of this normalized component instance.
         *
         * @param component normalized component generation
         * @param eventId declared native or framework baseline event id
         * @return the event's scalar field types
         */
        Map<String, Type> eventFields(Component component, String eventId);
    }

    /** Limit field names in {@code LimitsV1} wire order; shared with the authoring-language export. */
    static final List<String> LIMIT_NAMES = List.of("maxCascadeDepth", "maxDerivedPerSourceMessage",
            "maxDerivedPerBlock", "maxEventPayloadBytes", "maxLookupsPerCondition", "maxFunctionCallsPerMapping",
            "maxFunctionInputBytes", "maxExpressionNodes", "maxExpressionDepth", "maxExpressionValueBytes",
            "maxExpressionWorkPerCascade", "maxExpressionWorkPerBlock");
    /** Function identifiers accepted by authoring; profile construction still validates signatures. */
    static final Set<String> FUNCTIONS = Set.of("blake2b-256", "sha-256", "concat", "utf8-bytes",
            "hex", "byte-length", "cbor-encode", "cbor-field");
    private static final BindingDocumentPath ROOT = BindingDocumentPath.ROOT;

    private BindingDocumentCompiler() { }

    /**
     * Parses a standalone composite document, normalizes configuration, and lowers restricted CEL expressions.
     *
     * @param yaml bounded YAML document containing {@code components}, {@code bindings}, and optional limits/heights
     * @param catalog descriptors obtained from the selected plugin catalog, never a hard-coded fallback
     * @return structurally checked IR whose {@code encode()} bytes are the runtime input
     * @throws IllegalArgumentException for malformed, ambiguous, unsupported, or oversized authoring input
     */
    public static BindingIrV1 compile(String yaml, DescriptorCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        return compile(parseDocument(yaml), catalog);
    }

    /**
     * Retains authored values for a blueprint while enforcing the same lexical bounds as direct compilation.
     * Failures are {@link BindingAuthoringException}s with one-based UTF-16 source positions where known.
     */
    static JsonNode parseDocument(String yaml) {
        if (yaml == null || yaml.length() > MAX_SOURCE_CHARACTERS) {
            throw fail("DOCUMENT_TOO_LARGE", ROOT, "source limit exceeded");
        }
        YAMLFactory factory = YAMLFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(48)
                        .maxStringLength(65_536).maxNumberLength(32).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        ObjectMapper mapper = new ObjectMapper(factory);
        // Inspect tokens before building a tree: aliases and tags must not disappear into scalar coercions.
        try (YAMLParser scan = factory.createParser(yaml)) {
            int count = 0;
            while (scan.nextToken() != null) {
                if (++count > 32_768) throw fail("DOCUMENT_TOO_LARGE", ROOT, "document token limit");
                if (scan.isCurrentAlias() || scan.getCurrentAnchor() != null || scan.getTypeId() != null) {
                    throw located(fail("YAML_FORBIDDEN_CONSTRUCT", ROOT,
                            "YAML aliases, anchors, and explicit tags are not supported"), scan.currentLocation(),
                            yaml);
                }
            }
        } catch (IOException error) {
            throw yamlFailure(error, yaml);
        }
        try (JsonParser parser = factory.createParser(yaml)) {
            JsonNode root = mapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw located(fail("YAML_MULTIPLE_DOCUMENTS", ROOT, "only one YAML document is allowed"),
                        parser.currentLocation(), yaml);
            }
            return root;
        } catch (IOException error) {
            throw yamlFailure(error, yaml);
        }
    }

    /**
     * Compiles a parsed blueprint's {@code composite} subtree using the same closed authoring schema.
     * The blueprint parser is responsible for duplicate-key, alias, input-size, and document-count checks.
     *
     * @param root the composite object, optionally wrapped in a single {@code composite} property
     * @param catalog catalog-backed descriptors for normalization and CEL checking
     * @return a bounded, canonical-serializable binding IR
     */
    public static BindingIrV1 compile(JsonNode root, DescriptorCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        if (root != null && root.has("composite")) {
            object(root, ROOT, "composite");
            JsonNode composite = root.get("composite");
            if (composite.has("composite")) {
                throw fail("NESTED_WRAPPER", ROOT.field("composite"), "nested wrappers are not supported");
            }
            try { return compile(composite, catalog); }
            catch (IllegalArgumentException error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                String rerooted = message.startsWith("$") ? "$.composite" + message.substring(1)
                        : "$.composite: " + message;
                if (error instanceof BindingAuthoringException authored) throw authored.under("composite", rerooted);
                throw new BindingAuthoringException("UNCLASSIFIED", ROOT.field("composite"), rerooted, error);
            }
        }
        object(root, ROOT, "components", "bindings", "limits", "workflowFromHeight");
        Limits limits = at("DOCUMENT_STRUCTURE_INVALID", ROOT.field("limits"), () -> limits(root.get("limits")));
        List<Component> components = new ArrayList<>();
        Map<String, Component> byId = new LinkedHashMap<>();
        JsonNode componentNodes = array(required(root, "components", ROOT), ROOT.field("components"), 16);
        for (int i = 0; i < componentNodes.size(); i++) {
            // Read and normalize each component before the next, preserving historical first-error order.
            AuthoredComponent authored = authoredComponent(componentNodes.get(i), i);
            BindingDocumentPath path = authored.path();
            JsonNode node = authored.node();
            Map<String, Object> supplied = authored.configuration();
            Map<String, BindingSourceV1.Literal> normalized = new LinkedHashMap<>();
            at("COMPONENT_CONFIGURATION_INVALID", path.field("config"), () -> {
                catalog.configuration(authored.machine(), Map.copyOf(supplied)).normalize(supplied)
                        .forEach((key, value) -> normalized.put(key, new BindingSourceV1.Literal(value)));
                return normalized;
            });
            Component component = at("DOCUMENT_STRUCTURE_INVALID", path, () -> new Component(authored.id(),
                    authored.machine(), optionalText(node, "topic", authored.id() + ".command.v1", path), normalized,
                    integer(node, "maxEffectsPerBlock", 0, path), number(node, "fromHeight", 1, path)));
            if (byId.putIfAbsent(authored.id(), component) != null) {
                throw fail("DUPLICATE_COMPONENT", path.field("id"), "duplicate component");
            }
            components.add(component);
        }
        List<Binding> bindings = new ArrayList<>();
        JsonNode bindingNodes = array(required(root, "bindings", ROOT), ROOT.field("bindings"), 256);
        for (int i = 0; i < bindingNodes.size(); i++) {
            BindingDocumentPath path = ROOT.field("bindings").index(i);
            JsonNode node = bindingNodes.get(i);
            object(node, path, "id", "from", "when", "to");
            JsonNode from = required(node, "from", path);
            object(from, path.field("from"), "component", "event");
            String source = text(required(from, "component", path.field("from")),
                    path.field("from").field("component"));
            String event = text(required(from, "event", path.field("from")), path.field("from").field("event"));
            Component component = byId.get(source);
            if (component == null) {
                throw fail("UNKNOWN_COMPONENT", path.field("from").field("component"), "unknown component");
            }
            Map<String, Type> fields = at("EVENT_UNAVAILABLE", path.field("from").field("event"),
                    () -> Map.copyOf(catalog.eventFields(component, event)));
            List<Clause> clauses = new ArrayList<>();
            if (node.has("when")) {
                JsonNode conditions = array(node.get("when"), path.field("when"), 8);
                for (int c = 0; c < conditions.size(); c++) {
                    BindingDocumentPath clausePath = path.field("when").index(c);
                    JsonNode condition = conditions.get(c);
                    clauses.add(at("DOCUMENT_STRUCTURE_INVALID", clausePath,
                            () -> clause(condition, clausePath, fields, limits)));
                }
            }
            Target target = at("DOCUMENT_STRUCTURE_INVALID", path.field("to"),
                    () -> target(required(node, "to", path), path.field("to"), fields, limits));
            if (target instanceof CommandTarget command && !byId.containsKey(command.component())) {
                throw fail("UNKNOWN_COMPONENT", path.field("to").field("component"), "unknown component");
            }
            for (Clause clause : clauses) {
                if (clause instanceof LookupClause lookup && !byId.containsKey(lookup.participant())) {
                    throw fail("UNKNOWN_COMPONENT", path.field("when"), "unknown lookup component");
                }
            }
            bindings.add(at("DOCUMENT_STRUCTURE_INVALID", path, () -> new Binding(
                    text(required(node, "id", path), path.field("id")), source, event, clauses, target)));
        }
        BindingIrV1 ir = at("DOCUMENT_STRUCTURE_INVALID", ROOT, () -> new BindingIrV1(components, bindings, limits,
                number(root, "workflowFromHeight", 1, ROOT)));
        // Enforce the canonical envelope byte limit before returning an apparently valid result.
        at("DOCUMENT_SIZE_LIMIT", ROOT, ir::encode);
        return ir;
    }

    /**
     * One authored component before catalog normalization: exact machine id and exact typed configuration as
     * written. Used for descriptor probing; it never materializes defaults.
     *
     * @param index zero-based position in {@code components}
     * @param path exact document path of the component object
     * @param id authored instance id
     * @param machine authored machine selector
     * @param configuration authored settings in document order (values are Long, String, Boolean or byte[])
     * @param node the authored component object
     */
    record AuthoredComponent(int index, BindingDocumentPath path, String id, String machine,
                             Map<String, Object> configuration, JsonNode node) { }

    /**
     * Reads the authored components of a composite body (not the wrapper) with the compiler's own structural rules,
     * without consulting any catalog.
     */
    static List<AuthoredComponent> authoredComponents(JsonNode root) {
        if (root != null && root.has("composite")) {
            object(root, ROOT, "composite");
            try { return authoredComponents(root.get("composite")); }
            catch (BindingAuthoringException error) {
                String message = error.getMessage();
                throw error.under("composite", message.startsWith("$") ? "$.composite" + message.substring(1)
                        : "$.composite: " + message);
            }
        }
        object(root, ROOT, "components", "bindings", "limits", "workflowFromHeight");
        List<AuthoredComponent> result = new ArrayList<>();
        JsonNode componentNodes = array(required(root, "components", ROOT), ROOT.field("components"), 16);
        for (int i = 0; i < componentNodes.size(); i++) result.add(authoredComponent(componentNodes.get(i), i));
        return List.copyOf(result);
    }

    private static AuthoredComponent authoredComponent(JsonNode node, int index) {
        BindingDocumentPath path = ROOT.field("components").index(index);
        object(node, path, "id", "machine", "topic", "config", "maxEffectsPerBlock", "fromHeight");
        String id = text(required(node, "id", path), path.field("id"));
        String machine = text(required(node, "machine", path), path.field("machine"));
        Map<String, Object> supplied = new LinkedHashMap<>();
        if (node.has("config")) {
            JsonNode config = node.get("config");
            if (!config.isObject() || config.size() > 64) {
                throw fail("EXPECTED_OBJECT", path.field("config"), "expected bounded map");
            }
            config.properties().forEach(entry -> supplied.put(entry.getKey(),
                    scalar(entry.getValue(), path.field("config").field(entry.getKey()))));
        }
        return new AuthoredComponent(index, path, id, machine, java.util.Collections.unmodifiableMap(supplied), node);
    }

    private static Clause clause(JsonNode node, BindingDocumentPath path, Map<String, Type> fields, Limits limits) {
        if (node != null && node.has("expr")) {
            object(node, path, "expr");
            // Historical messages name the clause, not its expr field; the location still records the field.
            String source = text(node.get("expr"), path.field("expr"));
            return new ExpressionClause(at("EXPRESSION_INVALID", path, path.field("expr"),
                    () -> BindingExpressionCompiler.compile(source, fields, limits)));
        }
        if (node != null && node.has("lookup")) {
            object(node, path, "lookup");
            JsonNode lookup = node.get("lookup");
            BindingDocumentPath lookupPath = path.field("lookup");
            object(lookup, lookupPath, "component", "key", "exists", "absent", "eq");
            String operator = exactlyOne(lookup, lookupPath, List.of("exists", "absent", "eq"));
            Expectation expectation;
            BindingSourceV1 operand = null;
            if (operator.equals("eq")) {
                operand = source(lookup.get(operator), lookupPath.field("eq"), fields, limits, 0);
                expectation = operand instanceof BindingSourceV1.Field ? Expectation.EQUAL_EVENT
                        : Expectation.EQUAL_LITERAL;
            } else {
                requireTrue(lookup.get(operator), lookupPath.field(operator));
                expectation = operator.equals("exists") ? Expectation.EXISTS : Expectation.ABSENT;
            }
            return new LookupClause(text(required(lookup, "component", path), lookupPath.field("component")),
                    source(required(lookup, "key", path), lookupPath.field("key"), fields, limits, 0),
                    expectation, operand);
        }
        object(node, path, "field", "eq", "ne", "lt", "le", "gt", "ge", "in", "exists", "absent");
        String operator = exactlyOne(node, path, List.of("eq", "ne", "lt", "le", "gt", "ge", "in",
                "exists", "absent"));
        String field = text(required(node, "field", path), path.field("field"));
        if (!fields.containsKey(field)) throw fail("UNKNOWN_EVENT_FIELD", path.field("field"), "unknown event field");
        List<BindingSourceV1.Literal> values = new ArrayList<>();
        if (operator.equals("in")) {
            JsonNode options = array(node.get(operator), path.field("in"), 64);
            for (JsonNode option : options) values.add(new BindingSourceV1.Literal(scalar(option, path.field("in"))));
        } else if (operator.equals("exists") || operator.equals("absent")) {
            requireTrue(node.get(operator), path.field(operator));
        } else values.add(new BindingSourceV1.Literal(scalar(node.get(operator), path.field(operator))));
        return new FieldClause(field, Operator.valueOf(operator.toUpperCase(Locale.ROOT)), values);
    }

    private static Target target(JsonNode node, BindingDocumentPath path, Map<String, Type> fields, Limits limits) {
        if (node != null && node.has("effect")) {
            object(node, path, "effect");
            JsonNode effect = node.get("effect");
            BindingDocumentPath effectPath = path.field("effect");
            object(effect, effectPath, "type", "gate", "result", "expiryBlocks", "map", "rawBody");
            return new EffectTarget(text(required(effect, "type", path), effectPath.field("type")),
                    optionalText(effect, "gate", "app-final", path), optionalText(effect, "result", "none", path),
                    number(effect, "expiryBlocks", 0, path), mapping(effect, effectPath, fields, limits));
        }
        object(node, path, "component", "command", "map", "rawBody");
        return new CommandTarget(text(required(node, "component", path), path.field("component")),
                text(required(node, "command", path), path.field("command")), mapping(node, path, fields, limits));
    }

    private static Mapping mapping(JsonNode parent, BindingDocumentPath path, Map<String, Type> fields, Limits limits) {
        String kind = exactlyOne(parent, path, List.of("map", "rawBody"));
        JsonNode node = parent.get(kind);
        if (kind.equals("rawBody")) {
            String field = text(node, path.field("rawBody"));
            if (fields.get(field) != Type.BYTES) {
                throw fail("BINDING_TYPE_MISMATCH", path.field("rawBody"), "expected bytes-typed event field");
            }
            return Mapping.raw(field);
        }
        if (node.isTextual() && node.textValue().equals("identity")) return Mapping.identity();
        if (!node.isObject() || node.isEmpty() || node.size() > 16) {
            throw fail("EXPECTED_OBJECT", path.field("map"), "expected field map");
        }
        List<Assignment> assignments = node.properties().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new Assignment(entry.getKey(),
                        source(entry.getValue(), path.field("map").field(entry.getKey()), fields, limits, 0))).toList();
        return Mapping.fields(assignments);
    }

    private static BindingSourceV1 source(JsonNode node, BindingDocumentPath path, Map<String, Type> fields,
                                           Limits limits, int depth) {
        if (depth > 2) throw fail("FUNCTION_NESTING_LIMIT", path, "function nesting limit");
        object(node, path, "field", "literal", "fn", "args", "expr");
        String kind = exactlyOne(node, path, List.of("field", "literal", "fn", "expr"));
        if (!kind.equals("fn") && node.has("args")) {
            throw fail("ONLY_FUNCTIONS_ACCEPT_ARGS", path.field("args"), "only functions accept args");
        }
        return switch (kind) {
            case "field" -> {
                String field = text(node.get(kind), path.field("field"));
                if (!fields.containsKey(field)) {
                    throw fail("UNKNOWN_EVENT_FIELD", path, "unknown event field: " + field);
                }
                yield new BindingSourceV1.Field(field);
            }
            case "literal" -> new BindingSourceV1.Literal(scalar(node.get(kind), path.field("literal")));
            case "expr" -> new BindingSourceV1.Expression(expression(node.get(kind), path.field("expr"), fields,
                    limits));
            case "fn" -> {
                String function = text(node.get(kind), path.field("fn"));
                if (!FUNCTIONS.contains(function)) {
                    throw fail("UNKNOWN_FUNCTION", path.field("fn"), "unknown function: " + function);
                }
                List<BindingSourceV1> arguments = new ArrayList<>();
                JsonNode args = array(required(node, "args", path), path.field("args"), 8);
                for (int i = 0; i < args.size(); i++) {
                    arguments.add(source(args.get(i), path.field("args").index(i), fields, limits, depth + 1));
                }
                yield new BindingSourceV1.Function(function, arguments);
            }
            default -> throw new IllegalStateException("unreachable source kind");
        };
    }

    /** Compiles restricted CEL text found at {@code path}; failures keep their expression-relative position. */
    private static BindingExpressionV1 expression(JsonNode node, BindingDocumentPath path, Map<String, Type> fields,
                                                  Limits limits) {
        String source = text(node, path);
        return at("EXPRESSION_INVALID", path, path, () -> BindingExpressionCompiler.compile(source, fields, limits));
    }

    private static Limits limits(JsonNode node) {
        if (node == null) return Limits.DEFAULT;
        BindingDocumentPath path = ROOT.field("limits");
        object(node, path, LIMIT_NAMES.toArray(String[]::new));
        Limits defaults = Limits.DEFAULT;
        int[] values = {defaults.maxCascadeDepth(), defaults.maxDerivedPerSourceMessage(),
                defaults.maxDerivedPerBlock(), defaults.maxEventPayloadBytes(), defaults.maxLookupsPerCondition(),
                defaults.maxFunctionCallsPerMapping(),
                defaults.maxFunctionInputBytes(), defaults.maxExpressionNodes(), defaults.maxExpressionDepth(),
                defaults.maxExpressionValueBytes(), defaults.maxExpressionWorkPerCascade(),
                defaults.maxExpressionWorkPerBlock()};
        for (int i = 0; i < values.length; i++) values[i] = integer(node, LIMIT_NAMES.get(i), values[i], path);
        return new Limits(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7],
                values[8], values[9], values[10], values[11]);
    }

    private static Object scalar(JsonNode node, BindingDocumentPath path) {
        if (node == null) throw fail("EXPECTED_SCALAR", path, "missing scalar");
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber() && node.canConvertToLong()) return node.longValue();
        if (node.isObject()) {
            object(node, path, "bytesHex");
            String hex = text(required(node, "bytesHex", path), path.field("bytesHex"));
            if (hex.length() > 131_072) throw fail("INVALID_BYTES_LITERAL", path, "byte literal limit");
            try { return HexFormat.of().parseHex(hex); }
            catch (IllegalArgumentException error) {
                throw fail("INVALID_BYTES_LITERAL", path, "invalid bytesHex literal");
            }
        }
        throw fail("EXPECTED_SCALAR", path, "expected int64, text, boolean, or {bytesHex: hexadecimal-text}");
    }

    private static void object(JsonNode node, BindingDocumentPath path, String... allowed) {
        if (node == null || !node.isObject()) throw fail("EXPECTED_OBJECT", path, "expected object");
        Set<String> names = Set.of(allowed);
        node.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) throw fail("UNKNOWN_FIELD", path.field(name), "unknown field");
        });
    }

    private static JsonNode array(JsonNode node, BindingDocumentPath path, int maximum) {
        if (node == null || !node.isArray() || node.size() > maximum) {
            throw fail("EXPECTED_ARRAY", path, "expected bounded array");
        }
        return node;
    }

    private static JsonNode required(JsonNode node, String key, BindingDocumentPath path) {
        if (!node.has(key)) throw fail("REQUIRED_FIELD", path.field(key), "required field");
        return node.get(key);
    }

    private static String text(JsonNode node, BindingDocumentPath path) {
        if (node == null || !node.isTextual()) throw fail("EXPECTED_TEXT", path, "expected text");
        return node.textValue();
    }

    private static String optionalText(JsonNode node, String key, String fallback, BindingDocumentPath path) {
        return node.has(key) ? text(node.get(key), path.field(key)) : fallback;
    }

    private static long number(JsonNode node, String key, long fallback, BindingDocumentPath path) {
        if (!node.has(key)) return fallback;
        JsonNode value = node.get(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw fail("EXPECTED_INTEGER", path.field(key), "expected int64");
        }
        return value.longValue();
    }

    private static int integer(JsonNode node, String key, int fallback, BindingDocumentPath path) {
        long value = number(node, key, fallback, path);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw fail("EXPECTED_INTEGER", path.field(key), "expected int32");
        }
        return (int) value;
    }

    private static String exactlyOne(JsonNode node, BindingDocumentPath path, List<String> options) {
        List<String> present = options.stream().filter(node::has).toList();
        if (present.size() != 1) throw fail("EXACTLY_ONE_REQUIRED", path, "expected exactly one of " + options);
        return present.getFirst();
    }

    private static void requireTrue(JsonNode node, BindingDocumentPath path) {
        if (!node.isBoolean() || !node.booleanValue()) {
            throw fail("MUST_BE_TRUE", path, "must be true; use the opposite operator");
        }
    }

    private static BindingAuthoringException fail(String code, BindingDocumentPath path, String message) {
        return new BindingAuthoringException(code, path, path + ": " + message, null);
    }

    /**
     * Adds the parser's source position, converted from SnakeYAML code points to one-based UTF-16 units. SnakeYAML
     * also breaks lines at CR, NEL, LS and PS; positions are omitted for such sources rather than misreported.
     */
    private static BindingAuthoringException located(BindingAuthoringException error,
                                                     com.fasterxml.jackson.core.JsonLocation location, String yaml) {
        if (location == null || location.getLineNr() < 1 || location.getColumnNr() < 1) return error;
        if (BindingExpressionCompiler.hasNonLineFeedBreak(yaml)) return error;
        Integer column = BindingExpressionCompiler.utf16Column(yaml, location.getLineNr(), location.getColumnNr() - 1);
        return error.at(location.getLineNr(), column);
    }

    private static BindingAuthoringException yamlFailure(IOException error, String yaml) {
        String detail = error instanceof JsonProcessingException processing ? processing.getOriginalMessage()
                : error.getMessage();
        String code = detail != null && detail.startsWith("Duplicate field") ? "YAML_DUPLICATE_KEY" : "YAML_SYNTAX";
        var location = error instanceof JsonProcessingException processing ? processing.getLocation() : null;
        BindingAuthoringException failure = new BindingAuthoringException(code, ROOT,
                "$: invalid binding YAML: " + error.getMessage(), error, null, null, null, null, true);
        return located(failure, location, yaml);
    }

    /**
     * Runs a construction step, attaching the path and the given code to failures that lack a document path.
     * Restricted-CEL failures always use {@code EXPRESSION_INVALID} and keep their expression position.
     */
    private static <T> T at(String code, BindingDocumentPath path, Supplier<T> action) {
        return at(code, path, path, action);
    }

    /** As {@link #at(String, BindingDocumentPath, Supplier)}, with a message path that may be an ancestor. */
    private static <T> T at(String code, BindingDocumentPath messagePath, BindingDocumentPath path,
                            Supplier<T> action) {
        try { return action.get(); }
        catch (RuntimeException error) {
            if (error instanceof BindingAuthoringException authored) throw authored;
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if (error instanceof IllegalArgumentException && message.startsWith("$")) {
                throw new BindingAuthoringException(code, path, message, error);
            }
            if (error instanceof BindingExpressionCompiler.ExpressionException expression) {
                throw new BindingAuthoringException("EXPRESSION_INVALID", path, messagePath + ": " + message, error,
                        null, null, expression.line(), expression.column(), true);
            }
            throw new BindingAuthoringException(code, path, messagePath + ": " + message, error, null, null, null, null,
                    "COMPONENT_CONFIGURATION_INVALID".equals(code) || "EVENT_UNAVAILABLE".equals(code));
        }
    }
}
