package org.yanoproject.x.devtools;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
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

    private static final List<String> LIMIT_NAMES = List.of("maxCascadeDepth", "maxDerivedPerSourceMessage",
            "maxDerivedPerBlock", "maxEventPayloadBytes", "maxLookupsPerCondition", "maxFunctionCallsPerMapping",
            "maxFunctionInputBytes", "maxExpressionNodes", "maxExpressionDepth", "maxExpressionValueBytes",
            "maxExpressionWorkPerCascade", "maxExpressionWorkPerBlock");
    private static final Set<String> FUNCTIONS = Set.of("blake2b-256", "sha-256", "concat", "utf8-bytes",
            "hex", "byte-length", "cbor-encode", "cbor-field");

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

    /** Retains authored values for a blueprint while enforcing the same lexical bounds as direct compilation. */
    static JsonNode parseDocument(String yaml) {
        if (yaml == null || yaml.length() > MAX_SOURCE_CHARACTERS) throw fail("$", "source limit exceeded");
        YAMLFactory factory = YAMLFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(48)
                        .maxStringLength(65_536).maxNumberLength(32).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        ObjectMapper mapper = new ObjectMapper(factory);
        // Inspect tokens before building a tree: aliases and tags must not disappear into scalar coercions.
        try (YAMLParser scan = factory.createParser(yaml)) {
            int count = 0;
            while (scan.nextToken() != null) {
                if (++count > 32_768) throw fail("$", "document token limit");
                if (scan.isCurrentAlias() || scan.getCurrentAnchor() != null || scan.getTypeId() != null) {
                    throw fail("$", "YAML aliases, anchors, and explicit tags are not supported");
                }
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("$: invalid binding YAML: " + error.getMessage(), error);
        }
        try (JsonParser parser = factory.createParser(yaml)) {
            JsonNode root = mapper.readTree(parser);
            if (parser.nextToken() != null) throw fail("$", "only one YAML document is allowed");
            return root;
        } catch (IOException error) {
            throw new IllegalArgumentException("$: invalid binding YAML: " + error.getMessage(), error);
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
            object(root, "$", "composite");
            JsonNode composite = root.get("composite");
            if (composite.has("composite")) throw fail("$.composite", "nested wrappers are not supported");
            try { return compile(composite, catalog); }
            catch (IllegalArgumentException error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                throw new IllegalArgumentException(message.startsWith("$")
                        ? "$.composite" + message.substring(1) : "$.composite: " + message, error);
            }
        }
        object(root, "$", "components", "bindings", "limits", "workflowFromHeight");
        Limits limits = at("$.limits", () -> limits(root.get("limits")));
        List<Component> components = new ArrayList<>();
        Map<String, Component> byId = new LinkedHashMap<>();
        JsonNode componentNodes = array(required(root, "components", "$"), "$.components", 16);
        for (int i = 0; i < componentNodes.size(); i++) {
            String path = "$.components[" + i + "]";
            JsonNode node = componentNodes.get(i);
            object(node, path, "id", "machine", "topic", "config", "maxEffectsPerBlock", "fromHeight");
            String id = text(required(node, "id", path), path + ".id");
            String machine = text(required(node, "machine", path), path + ".machine");
            Map<String, Object> supplied = new LinkedHashMap<>();
            if (node.has("config")) {
                JsonNode config = node.get("config");
                if (!config.isObject() || config.size() > 64) throw fail(path + ".config", "expected bounded map");
                config.properties().forEach(entry -> supplied.put(entry.getKey(),
                        scalar(entry.getValue(), path + ".config." + entry.getKey())));
            }
            Map<String, BindingSourceV1.Literal> normalized = new LinkedHashMap<>();
            at(path + ".config", () -> {
                catalog.configuration(machine, Map.copyOf(supplied)).normalize(supplied).forEach((key, value) ->
                        normalized.put(key, new BindingSourceV1.Literal(value)));
                return normalized;
            });
            Component component = at(path, () -> new Component(id, machine,
                    optionalText(node, "topic", id + ".command.v1", path), normalized,
                    integer(node, "maxEffectsPerBlock", 0, path), number(node, "fromHeight", 1, path)));
            if (byId.putIfAbsent(id, component) != null) throw fail(path + ".id", "duplicate component");
            components.add(component);
        }
        List<Binding> bindings = new ArrayList<>();
        JsonNode bindingNodes = array(required(root, "bindings", "$"), "$.bindings", 256);
        for (int i = 0; i < bindingNodes.size(); i++) {
            String path = "$.bindings[" + i + "]";
            JsonNode node = bindingNodes.get(i);
            object(node, path, "id", "from", "when", "to");
            JsonNode from = required(node, "from", path);
            object(from, path + ".from", "component", "event");
            String source = text(required(from, "component", path + ".from"), path + ".from.component");
            String event = text(required(from, "event", path + ".from"), path + ".from.event");
            Component component = byId.get(source);
            if (component == null) throw fail(path + ".from.component", "unknown component");
            Map<String, Type> fields = at(path + ".from.event",
                    () -> Map.copyOf(catalog.eventFields(component, event)));
            List<Clause> clauses = new ArrayList<>();
            if (node.has("when")) {
                JsonNode conditions = array(node.get("when"), path + ".when", 8);
                for (int c = 0; c < conditions.size(); c++) {
                    String clausePath = path + ".when[" + c + "]";
                    JsonNode condition = conditions.get(c);
                    clauses.add(at(clausePath, () -> clause(condition, clausePath, fields, limits)));
                }
            }
            Target target = at(path + ".to", () -> target(required(node, "to", path), path + ".to", fields, limits));
            if (target instanceof CommandTarget command && !byId.containsKey(command.component())) {
                throw fail(path + ".to.component", "unknown component");
            }
            for (Clause clause : clauses) {
                if (clause instanceof LookupClause lookup && !byId.containsKey(lookup.participant())) {
                    throw fail(path + ".when", "unknown lookup component");
                }
            }
            bindings.add(at(path, () -> new Binding(text(required(node, "id", path), path + ".id"),
                    source, event, clauses, target)));
        }
        BindingIrV1 ir = at("$", () -> new BindingIrV1(components, bindings, limits,
                number(root, "workflowFromHeight", 1, "$")));
        at("$", ir::encode); // Enforce the canonical envelope byte limit before returning an apparently valid result.
        return ir;
    }

    private static Clause clause(JsonNode node, String path, Map<String, Type> fields, Limits limits) {
        if (node != null && node.has("expr")) {
            object(node, path, "expr");
            return new ExpressionClause(BindingExpressionCompiler.compile(
                    text(node.get("expr"), path + ".expr"), fields, limits));
        }
        if (node != null && node.has("lookup")) {
            object(node, path, "lookup");
            JsonNode lookup = node.get("lookup");
            object(lookup, path + ".lookup", "component", "key", "exists", "absent", "eq");
            String operator = exactlyOne(lookup, path + ".lookup", List.of("exists", "absent", "eq"));
            Expectation expectation;
            BindingSourceV1 operand = null;
            if (operator.equals("eq")) {
                operand = source(lookup.get(operator), path + ".lookup.eq", fields, limits, 0);
                expectation = operand instanceof BindingSourceV1.Field ? Expectation.EQUAL_EVENT
                        : Expectation.EQUAL_LITERAL;
            } else {
                requireTrue(lookup.get(operator), path + ".lookup." + operator);
                expectation = operator.equals("exists") ? Expectation.EXISTS : Expectation.ABSENT;
            }
            return new LookupClause(text(required(lookup, "component", path), path + ".lookup.component"),
                    source(required(lookup, "key", path), path + ".lookup.key", fields, limits, 0),
                    expectation, operand);
        }
        object(node, path, "field", "eq", "ne", "lt", "le", "gt", "ge", "in", "exists", "absent");
        String operator = exactlyOne(node, path, List.of("eq", "ne", "lt", "le", "gt", "ge", "in",
                "exists", "absent"));
        String field = text(required(node, "field", path), path + ".field");
        if (!fields.containsKey(field)) throw fail(path + ".field", "unknown event field");
        List<BindingSourceV1.Literal> values = new ArrayList<>();
        if (operator.equals("in")) {
            JsonNode options = array(node.get(operator), path + ".in", 64);
            for (JsonNode option : options) values.add(new BindingSourceV1.Literal(scalar(option, path + ".in")));
        } else if (operator.equals("exists") || operator.equals("absent")) {
            requireTrue(node.get(operator), path + "." + operator);
        } else values.add(new BindingSourceV1.Literal(scalar(node.get(operator), path + "." + operator)));
        return new FieldClause(field, Operator.valueOf(operator.toUpperCase(Locale.ROOT)), values);
    }

    private static Target target(JsonNode node, String path, Map<String, Type> fields, Limits limits) {
        if (node != null && node.has("effect")) {
            object(node, path, "effect");
            JsonNode effect = node.get("effect");
            object(effect, path + ".effect", "type", "gate", "result", "expiryBlocks", "map", "rawBody");
            return new EffectTarget(text(required(effect, "type", path), path + ".effect.type"),
                    optionalText(effect, "gate", "app-final", path), optionalText(effect, "result", "none", path),
                    number(effect, "expiryBlocks", 0, path), mapping(effect, path + ".effect", fields, limits));
        }
        object(node, path, "component", "command", "map", "rawBody");
        return new CommandTarget(text(required(node, "component", path), path + ".component"),
                text(required(node, "command", path), path + ".command"), mapping(node, path, fields, limits));
    }

    private static Mapping mapping(JsonNode parent, String path, Map<String, Type> fields, Limits limits) {
        String kind = exactlyOne(parent, path, List.of("map", "rawBody"));
        JsonNode node = parent.get(kind);
        if (kind.equals("rawBody")) {
            String field = text(node, path + ".rawBody");
            if (fields.get(field) != Type.BYTES) throw fail(path + ".rawBody", "expected bytes-typed event field");
            return Mapping.raw(field);
        }
        if (node.isTextual() && node.textValue().equals("identity")) return Mapping.identity();
        if (!node.isObject() || node.isEmpty() || node.size() > 16) throw fail(path + ".map", "expected field map");
        List<Assignment> assignments = node.properties().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new Assignment(entry.getKey(),
                        source(entry.getValue(), path + ".map." + entry.getKey(), fields, limits, 0))).toList();
        return Mapping.fields(assignments);
    }

    private static BindingSourceV1 source(JsonNode node, String path, Map<String, Type> fields,
                                           Limits limits, int depth) {
        if (depth > 2) throw fail(path, "function nesting limit");
        object(node, path, "field", "literal", "fn", "args", "expr");
        String kind = exactlyOne(node, path, List.of("field", "literal", "fn", "expr"));
        if (!kind.equals("fn") && node.has("args")) throw fail(path + ".args", "only functions accept args");
        return switch (kind) {
            case "field" -> {
                String field = text(node.get(kind), path + ".field");
                if (!fields.containsKey(field)) throw fail(path, "unknown event field: " + field);
                yield new BindingSourceV1.Field(field);
            }
            case "literal" -> new BindingSourceV1.Literal(scalar(node.get(kind), path + ".literal"));
            case "expr" -> at(path + ".expr", () -> new BindingSourceV1.Expression(BindingExpressionCompiler.compile(
                    text(node.get(kind), path + ".expr"), fields, limits)));
            case "fn" -> {
                String function = text(node.get(kind), path + ".fn");
                if (!FUNCTIONS.contains(function)) throw fail(path + ".fn", "unknown function: " + function);
                List<BindingSourceV1> arguments = new ArrayList<>();
                JsonNode args = array(required(node, "args", path), path + ".args", 8);
                for (int i = 0; i < args.size(); i++) {
                    arguments.add(source(args.get(i), path + ".args[" + i + "]", fields, limits, depth + 1));
                }
                yield new BindingSourceV1.Function(function, arguments);
            }
            default -> throw new IllegalStateException("unreachable source kind");
        };
    }

    private static Limits limits(JsonNode node) {
        if (node == null) return Limits.DEFAULT;
        object(node, "$.limits", LIMIT_NAMES.toArray(String[]::new));
        Limits defaults = Limits.DEFAULT;
        int[] values = {defaults.maxCascadeDepth(), defaults.maxDerivedPerSourceMessage(),
                defaults.maxDerivedPerBlock(), defaults.maxEventPayloadBytes(), defaults.maxLookupsPerCondition(),
                defaults.maxFunctionCallsPerMapping(),
                defaults.maxFunctionInputBytes(), defaults.maxExpressionNodes(), defaults.maxExpressionDepth(),
                defaults.maxExpressionValueBytes(), defaults.maxExpressionWorkPerCascade(),
                defaults.maxExpressionWorkPerBlock()};
        for (int i = 0; i < values.length; i++) values[i] = integer(node, LIMIT_NAMES.get(i), values[i], "$.limits");
        return new Limits(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7],
                values[8], values[9], values[10], values[11]);
    }

    private static Object scalar(JsonNode node, String path) {
        if (node == null) throw fail(path, "missing scalar");
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber() && node.canConvertToLong()) return node.longValue();
        if (node.isObject()) {
            object(node, path, "bytesHex");
            String hex = text(required(node, "bytesHex", path), path + ".bytesHex");
            if (hex.length() > 131_072) throw fail(path, "byte literal limit");
            try { return HexFormat.of().parseHex(hex); }
            catch (IllegalArgumentException error) { throw fail(path, "invalid bytesHex literal"); }
        }
        throw fail(path, "expected int64, text, boolean, or {bytesHex: hexadecimal-text}");
    }

    private static void object(JsonNode node, String path, String... allowed) {
        if (node == null || !node.isObject()) throw fail(path, "expected object");
        Set<String> names = Set.of(allowed);
        node.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) throw fail(path + "." + name, "unknown field");
        });
    }

    private static JsonNode array(JsonNode node, String path, int maximum) {
        if (node == null || !node.isArray() || node.size() > maximum) throw fail(path, "expected bounded array");
        return node;
    }

    private static JsonNode required(JsonNode node, String key, String path) {
        if (!node.has(key)) throw fail(path + "." + key, "required field");
        return node.get(key);
    }

    private static String text(JsonNode node, String path) {
        if (node == null || !node.isTextual()) throw fail(path, "expected text");
        return node.textValue();
    }

    private static String optionalText(JsonNode node, String key, String fallback, String path) {
        return node.has(key) ? text(node.get(key), path + "." + key) : fallback;
    }

    private static long number(JsonNode node, String key, long fallback, String path) {
        if (!node.has(key)) return fallback;
        JsonNode value = node.get(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw fail(path + "." + key, "expected int64");
        return value.longValue();
    }

    private static int integer(JsonNode node, String key, int fallback, String path) {
        long value = number(node, key, fallback, path);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw fail(path + "." + key, "expected int32");
        return (int) value;
    }

    private static String exactlyOne(JsonNode node, String path, List<String> options) {
        List<String> present = options.stream().filter(node::has).toList();
        if (present.size() != 1) throw fail(path, "expected exactly one of " + options);
        return present.getFirst();
    }

    private static void requireTrue(JsonNode node, String path) {
        if (!node.isBoolean() || !node.booleanValue()) throw fail(path, "must be true; use the opposite operator");
    }

    private static IllegalArgumentException fail(String path, String message) {
        return new IllegalArgumentException(path + ": " + message);
    }

    private static <T> T at(String path, Supplier<T> action) {
        try { return action.get(); }
        catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException argument && error.getMessage() != null
                    && error.getMessage().startsWith("$")) throw argument;
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            throw new IllegalArgumentException(path + ": " + message, error);
        }
    }
}
