package org.yanoproject.x.devtools;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Structured binding-tooling diagnostic, {@code BindingDiagnosticV1} (ADR-031.2 contract C3).
 *
 * <p>{@code code} is a stable machine-readable identifier from {@link #CODES}; {@code message} is controlled
 * template text for that code; {@code detail} is the bounded, single-line historical diagnostic text with absolute
 * filesystem paths redacted. When {@code detailMayContainInput} is true the detail comes from a parser, expression
 * compiler, provider or kernel and may quote document or fixture content, so consumers display it only as text.
 * The location contains only fields the producing Java code knows exactly; missing data is omitted, never guessed.
 *
 * @param code stable diagnostic code
 * @param severity always {@code error} in this version
 * @param message controlled description of the code
 * @param detail bounded historical message text, or {@code null}
 * @param detailMayContainInput whether {@code detail} may quote input values
 * @param location structured location, never {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record BindingDiagnostic(String code, String severity, String message, String detail, boolean detailMayContainInput,
                         Location location) {
    /** Maximum retained detail length, matching the historical CLI diagnostic bound. */
    static final int MAX_DETAIL = 512;
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?<![\\w.~])/(?:[^\\s/'\"():]+/)+[^\\s'\"():]*|\\b[A-Za-z]:\\\\[^\\s'\"]+|\\\\\\\\[^\\s'\"]+");
    /** A path that starts with a known local prefix may contain spaces; it ends at a quote, bracket or separator. */
    private static final String KNOWN_PATH_TAIL = "(?:[^'\"()\\r\\n,:]|:(?! ))*";
    /**
     * Stable part vocabulary shared by report locations and {@code BindingValidationException}, ADR-031.2 C3.
     */
    static final List<String> PARTS = List.of("component", "component-config", "binding", "source", "source-event",
            "condition", "expression", "lookup-key", "lookup-operand", "target", "target-command", "target-field",
            "mapping", "raw-body", "effect", "limits");

    /**
     * Closed diagnostic code catalog with controlled messages. Adding a code is additive; changing a code's meaning
     * requires a new report schema version.
     */
    static final Map<String, String> CODES = Map.ofEntries(
            Map.entry("DOCUMENT_TOO_LARGE", "The binding document exceeds a source, token or file size limit"),
            Map.entry("YAML_SYNTAX", "The binding document is not valid YAML"),
            Map.entry("YAML_FORBIDDEN_CONSTRUCT", "YAML aliases, anchors and explicit tags are not supported"),
            Map.entry("YAML_MULTIPLE_DOCUMENTS", "Only one YAML document is allowed"),
            Map.entry("YAML_DUPLICATE_KEY", "A mapping key is repeated"),
            Map.entry("IR_INVALID", "The supplied binding IR is not canonical version-one IR"),
            Map.entry("UNKNOWN_FIELD", "The field is not part of the binding document schema"),
            Map.entry("REQUIRED_FIELD", "A required field is missing"),
            Map.entry("EXPECTED_OBJECT", "An object is required here"),
            Map.entry("EXPECTED_ARRAY", "A bounded list is required here"),
            Map.entry("EXPECTED_TEXT", "Text is required here"),
            Map.entry("EXPECTED_INTEGER", "An integer within the permitted range is required here"),
            Map.entry("EXPECTED_SCALAR", "An int64, text, boolean or {bytesHex} literal is required here"),
            Map.entry("INVALID_BYTES_LITERAL", "The bytesHex literal is not valid hexadecimal within its limit"),
            Map.entry("EXACTLY_ONE_REQUIRED", "Exactly one of the alternative fields must be present"),
            Map.entry("MUST_BE_TRUE", "This operator accepts only true; use the opposite operator"),
            Map.entry("ONLY_FUNCTIONS_ACCEPT_ARGS", "Only function sources accept args"),
            Map.entry("FUNCTION_NESTING_LIMIT", "Function calls may be nested at most two levels"),
            Map.entry("UNKNOWN_FUNCTION", "The function is not in yano-x-binding-functions-v1"),
            Map.entry("UNKNOWN_COMPONENT", "The component is not declared in this document"),
            Map.entry("DUPLICATE_COMPONENT", "The component id is declared more than once"),
            Map.entry("UNKNOWN_EVENT_FIELD", "The event does not declare this field"),
            Map.entry("NESTED_WRAPPER", "Only one composite wrapper is allowed"),
            Map.entry("COMPONENT_CONFIGURATION_INVALID",
                    "The selected machine rejected this component or its configuration"),
            Map.entry("EVENT_UNAVAILABLE", "The selected component does not publish this event"),
            Map.entry("EXPRESSION_INVALID", "The restricted CEL expression is invalid for this location"),
            Map.entry("DOCUMENT_STRUCTURE_INVALID", "The document violates a binding IR structural rule"),
            Map.entry("DOCUMENT_SIZE_LIMIT", "The compiled binding IR exceeds its byte limit"),
            Map.entry("BINDING_TYPE_MISMATCH", "The source and destination types do not match"),
            Map.entry("BINDING_EVIDENCE_UNSATISFIABLE",
                    "Evidence fields must be copied directly from an event field"),
            Map.entry("UNKNOWN_EVENT", "The source component does not publish this event"),
            Map.entry("UNKNOWN_TARGET_COMMAND", "The target component does not declare this command"),
            Map.entry("MISSING_TARGET_FIELD", "A target command field is not mapped"),
            Map.entry("UNKNOWN_TARGET_FIELD", "The target command does not declare a mapped field"),
            Map.entry("RAW_TARGET_REQUIRES_RAW_MAPPING", "Opaque-bytes commands require a rawBody mapping"),
            Map.entry("LOOKUP_LIMIT", "The condition exceeds its lookup limit"),
            Map.entry("FUNCTION_CALL_LIMIT", "The mapping exceeds its function-call limit"),
            Map.entry("CYCLIC_BINDING_GRAPH", "Command bindings form a cycle"),
            Map.entry("KERNEL_CONTRACT_INVALID", "A selected kernel publishes an invalid descriptor or declaration"),
            Map.entry("PROFILE_CONSTRUCTION_FAILED",
                    "The catalog-selected provider rejected the profile; no exact location is known"),
            Map.entry("JSON_INVALID", "A JSON input is malformed or does not match its schema"),
            Map.entry("CONTEXT_INVALID", "The explicit chain context is invalid"),
            Map.entry("INPUT_TOO_LARGE", "An input file exceeds its size limit"),
            Map.entry("FIXTURE_INVALID", "The rehearsal fixture is invalid"),
            Map.entry("FIXTURE_ADMISSION_REJECTED", "A fixture message was rejected before execution"),
            Map.entry("CONTINUATION_MISMATCH", "The prior rehearsal result does not continue this rehearsal"),
            Map.entry("PLUGIN_CATALOG_INVALID", "The plugin directory is not a valid manifested catalog"),
            Map.entry("MACHINE_CONSTRUCTION_FAILED",
                    "The selected machine could not be constructed with this configuration and context"),
            Map.entry("MACHINE_NOT_COMPOSABLE", "The selected machine exposes no transition kernel"),
            Map.entry("CONFIGURATION_NOT_NORMALIZED",
                    "The machine's configuration descriptor rejects this authored configuration"),
            Map.entry("UNCLASSIFIED", "The input was rejected; no more specific classification is available"));

    BindingDiagnostic {
        Objects.requireNonNull(location, "location");
        if (!CODES.containsKey(code)) throw new IllegalArgumentException("unknown diagnostic code: " + code);
        if (!"error".equals(severity)) throw new IllegalArgumentException("unsupported diagnostic severity");
        if (!CODES.get(code).equals(message)) {
            throw new IllegalArgumentException("diagnostic message is not controlled");
        }
    }

    /** Creates an error with controlled message text and a sanitized historical detail. */
    static BindingDiagnostic error(String code, String detail, boolean detailMayContainInput, Location location) {
        String sanitized = sanitize(detail);
        return new BindingDiagnostic(code, "error", CODES.get(code), sanitized,
                sanitized != null && detailMayContainInput, location);
    }

    /**
     * Bounded, single-line, control-free text with absolute filesystem paths redacted; {@code null} stays null.
     * Paths under the user's home, working or temporary directory are redacted first because those prefixes may
     * contain spaces; any other POSIX, drive-letter or UNC absolute path is then redacted by shape.
     */
    static String sanitize(String text) {
        if (text == null) return null;
        String line = text.lines().findFirst().orElse("").replaceAll("[\\p{Cntrl}\\u2028\\u2029\\u0085]", " ");
        for (String prefix : knownPrefixes()) {
            line = Pattern.compile(Pattern.quote(prefix) + KNOWN_PATH_TAIL).matcher(line).replaceAll("<path>");
        }
        line = ABSOLUTE_PATH.matcher(line).replaceAll("<path>");
        return line.length() > MAX_DETAIL ? line.substring(0, MAX_DETAIL) : line;
    }

    private static List<String> knownPrefixes() {
        return java.util.stream.Stream.of("user.home", "user.dir", "java.io.tmpdir").map(System::getProperty)
                .filter(Objects::nonNull).map(value -> value.endsWith("/") || value.endsWith("\\")
                        ? value.substring(0, value.length() - 1) : value)
                .filter(value -> value.length() > 1).distinct()
                .sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList();
    }

    /**
     * Exact structured location. Indexes are zero-based and refer to authored lists; {@code line} and
     * {@code column} are one-based UTF-16 source positions; expression positions are relative to the CEL text.
     * {@code pathSegments} is authoritative; {@code path} is its display rendering.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Location(String input, List<Object> pathSegments, String path, Integer line, Integer column,
                    Integer componentIndex, String setting, Integer bindingIndex, String bindingId,
                    Integer clauseIndex, String part, String targetField, List<Integer> argumentPath,
                    Integer expressionLine, Integer expressionColumn) {
        Location {
            pathSegments = pathSegments == null ? null : List.copyOf(pathSegments);
            argumentPath = argumentPath == null || argumentPath.isEmpty() ? null : List.copyOf(argumentPath);
        }

        static Location input(String input) {
            return new Location(input, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null);
        }

        /**
         * Derives authored indexes, names and the {@link #PARTS part} from exact compiler path segments by position
         * in the binding document shape; nothing is inferred from segment names outside their defined position.
         */
        static Location fromSegments(String input, BindingDocumentPath path, Integer line, Integer column) {
            List<Object> segments = path.segments();
            int base = "composite".equals(segment(segments, 0)) ? 1 : 0;
            Integer component = null; Integer binding = null; Integer clause = null;
            String setting = null; String field = null; String part = null;
            List<Integer> arguments = List.of();
            Object head = segment(segments, base);
            if ("components".equals(head) && segment(segments, base + 1) instanceof Integer index) {
                component = index;
                part = "component";
                if ("config".equals(segment(segments, base + 2))) {
                    part = "component-config";
                    if (segment(segments, base + 3) instanceof String name) setting = name;
                }
            } else if ("components".equals(head)) {
                part = "component";
            } else if ("bindings".equals(head) && segment(segments, base + 1) instanceof Integer index) {
                binding = index;
                part = "binding";
                Object section = segment(segments, base + 2);
                int next = base + 3;
                if ("from".equals(section)) {
                    part = "event".equals(segment(segments, next)) ? "source-event" : "source";
                } else if ("when".equals(section)) {
                    part = "condition";
                    if (segment(segments, next) instanceof Integer index2) {
                        clause = index2;
                        Object kind = segment(segments, next + 1);
                        Object operand = segment(segments, next + 2);
                        if ("expr".equals(kind)) part = "expression";
                        else if ("lookup".equals(kind) && "key".equals(operand)) part = "lookup-key";
                        else if ("lookup".equals(kind) && "eq".equals(operand)) part = "lookup-operand";
                        if (part.startsWith("lookup-")) arguments = arguments(segments, next + 3);
                    }
                } else if ("to".equals(section)) {
                    part = "target";
                    if ("effect".equals(segment(segments, next))) {
                        part = "effect";
                        next++;
                    }
                    Object kind = segment(segments, next);
                    if ("command".equals(kind)) part = "target-command";
                    else if ("rawBody".equals(kind)) part = "raw-body";
                    else if ("map".equals(kind)) {
                        part = "mapping";
                        if (segment(segments, next + 1) instanceof String name) {
                            field = name;
                            arguments = arguments(segments, next + 2);
                        }
                    }
                }
            } else if ("limits".equals(head)) {
                part = "limits";
            }
            return new Location(input, segments, path.toString(), line, column, component, setting, binding, null,
                    clause, part, field, arguments, null, null);
        }

        /** Returns the known part name, or {@code null} for an unknown or absent part. */
        static String part(String name) {
            return name != null && PARTS.contains(name) ? name : null;
        }

        private static Object segment(List<Object> segments, int index) {
            return index < segments.size() ? segments.get(index) : null;
        }

        /** Function-argument indexes of consecutive {@code args[n]} pairs starting at {@code from}. */
        private static List<Integer> arguments(List<Object> segments, int from) {
            List<Integer> result = new java.util.ArrayList<>();
            for (int index = from; index + 1 < segments.size(); index += 2) {
                if (!"args".equals(segments.get(index)) || !(segments.get(index + 1) instanceof Integer argument)) {
                    break;
                }
                result.add(argument);
            }
            return result;
        }

        /** Replaces the part, target field and argument path with exact values when they are known. */
        Location withPart(String exactPart, String exactField, List<Integer> exactArguments) {
            return new Location(input, pathSegments, path, line, column, componentIndex, setting, bindingIndex,
                    bindingId, clauseIndex, exactPart != null ? exactPart : part,
                    exactField != null ? exactField : targetField,
                    exactArguments != null && !exactArguments.isEmpty() ? exactArguments : argumentPath,
                    expressionLine, expressionColumn);
        }

        Location withBinding(Integer index, String id) {
            return new Location(input, pathSegments, path, line, column, componentIndex, setting, index, id,
                    clauseIndex, part, targetField, argumentPath, expressionLine, expressionColumn);
        }

        Location withExpression(Integer expressionLine, Integer expressionColumn) {
            return new Location(input, pathSegments, path, line, column, componentIndex, setting, bindingIndex,
                    bindingId, clauseIndex, part, targetField, argumentPath, expressionLine, expressionColumn);
        }
    }
}
