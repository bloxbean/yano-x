package org.yanoproject.x.devtools;

import org.yanoproject.x.composite.bindings.BindingProgram;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.CompositeCommitmentV1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tooling export of the ADR-031.1 authoring language for editors (ADR-031.2 contract C1).
 *
 * <p>This is a description, not an implementation: the runtime keeps its own validation and evaluation code
 * unchanged. Conformance tests exercise every function signature, operator, limit, structural bound, baseline
 * field and framework receipt code against the real compiler, {@code BindingProgram} and receipt engine, so this
 * table cannot silently drift from the language. It is exported so browsers never keep a handwritten copy.
 */
final class BindingAuthoringLanguage {
    static final List<String> SCALAR_TYPES = List.of("integer", "text", "bytes", "boolean");

    private BindingAuthoringLanguage() { }

    /** Complete language section for catalogs. */
    static Map<String, Object> language() {
        Map<String, Object> language = new LinkedHashMap<>();
        language.put("irVersion", 1);
        language.put("functionCatalog", BindingIrV1.FUNCTIONS);
        language.put("expressionDialect", BindingExpressionV1.DIALECT);
        language.put("functions", functions());
        language.put("expressionOperators", operators());
        language.put("limits", limits());
        language.put("structuralLimits", structuralLimits());
        language.put("baselineEvent", baselineEvent());
        language.put("receiptCodes", receiptCodes());
        return language;
    }

    /**
     * Stock function signatures. {@code arguments} lists the accepted types per position; {@code homogeneous}
     * means every argument must share one of those types; result {@code same-as-arguments} returns that shared
     * type and {@code opaque} is typed only at execution (the destination is checked on the resulting value).
     * {@code any} accepts every scalar type and opaque values.
     */
    static List<Map<String, Object>> functions() {
        List<Map<String, Object>> functions = new ArrayList<>();
        functions.add(function("blake2b-256", 1, 1, List.of(List.of("bytes", "text")), false, "bytes",
                "total input at most maxFunctionInputBytes"));
        functions.add(function("sha-256", 1, 1, List.of(List.of("bytes", "text")), false, "bytes",
                "total input at most maxFunctionInputBytes"));
        functions.add(function("concat", 2, 8, List.of(List.of("text", "bytes")), true, "same-as-arguments",
                "total input and output at most maxFunctionInputBytes"));
        functions.add(function("utf8-bytes", 1, 1, List.of(List.of("text")), false, "bytes",
                "total input at most maxFunctionInputBytes"));
        functions.add(function("hex", 1, 1, List.of(List.of("bytes")), false, "text",
                "output (two characters per byte) at most maxFunctionInputBytes"));
        functions.add(function("byte-length", 1, 1, List.of(List.of("bytes", "text")), false, "integer",
                "total input at most maxFunctionInputBytes"));
        functions.add(function("cbor-encode", 1, 1, List.of(List.of("any")), false, "bytes",
                "total input at most maxFunctionInputBytes"));
        functions.add(function("cbor-field", 2, 2, List.of(List.of("bytes"), List.of("text")), false, "opaque",
                "one-level canonical scalar map; missing field or invalid CBOR rejects the cascade"));
        return List.copyOf(functions);
    }

    private static Map<String, Object> function(String id, int minimum, int maximum, List<List<String>> arguments,
                                                boolean homogeneous, String result, String bound) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("minArguments", minimum);
        value.put("maxArguments", maximum);
        value.put("arguments", arguments);
        value.put("homogeneous", homogeneous);
        value.put("result", result);
        value.put("bound", bound);
        return value;
    }

    /** Restricted CEL operators accepted by {@code yano-x-cel-v1}, with their operand and result types. */
    static List<Map<String, Object>> operators() {
        List<Map<String, Object>> operators = new ArrayList<>();
        operators.add(operator("==", "eq", "same scalar type", "boolean"));
        operators.add(operator("!=", "ne", "same scalar type", "boolean"));
        for (String[] comparison : new String[][] {{"<", "lt"}, {"<=", "le"}, {">", "gt"}, {">=", "ge"}}) {
            operators.add(operator(comparison[0], comparison[1], "integer, integer", "boolean"));
        }
        operators.add(operator("&&", "and", "boolean, boolean (error-masking)", "boolean"));
        operators.add(operator("||", "or", "boolean, boolean (error-masking)", "boolean"));
        operators.add(operator("!", "not", "boolean", "boolean"));
        operators.add(operator("? :", "if", "boolean, T, T (only the selected branch is evaluated)", "T"));
        operators.add(operator("+", "add", "integer, integer (checked)", "integer"));
        operators.add(operator("+", "concat", "text, text or bytes, bytes", "same as operands"));
        for (String[] arithmetic : new String[][] {{"-", "sub"}, {"*", "mul"}, {"/", "div"}, {"%", "mod"}}) {
            operators.add(operator(arithmetic[0], arithmetic[1], "integer, integer (checked; truncates toward zero)",
                    "integer"));
        }
        operators.add(operator("-x", "neg", "integer (checked)", "integer"));
        return List.copyOf(operators);
    }

    private static Map<String, Object> operator(String cel, String ir, String operands, String result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("cel", cel);
        value.put("ir", ir);
        value.put("operands", operands);
        value.put("result", result);
        return value;
    }

    /** Profile limits in {@code LimitsV1} wire order with authoring defaults and implementation maxima. */
    static List<Map<String, Object>> limits() {
        BindingIrV1.Limits defaults = BindingIrV1.Limits.DEFAULT;
        long[] values = {defaults.maxCascadeDepth(), defaults.maxDerivedPerSourceMessage(),
                defaults.maxDerivedPerBlock(), defaults.maxEventPayloadBytes(), defaults.maxLookupsPerCondition(),
                defaults.maxFunctionCallsPerMapping(), defaults.maxFunctionInputBytes(), defaults.maxExpressionNodes(),
                defaults.maxExpressionDepth(), defaults.maxExpressionValueBytes(),
                defaults.maxExpressionWorkPerCascade(), defaults.maxExpressionWorkPerBlock()};
        List<Map<String, Object>> limits = new ArrayList<>();
        for (int index = 0; index < LIMIT_MAXIMA.length; index++) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", BindingDocumentCompiler.LIMIT_NAMES.get(index));
            value.put("default", Long.toString(values[index]));
            value.put("minimum", "1");
            value.put("maximum", Long.toString(LIMIT_MAXIMA[index]));
            limits.add(value);
        }
        return List.copyOf(limits);
    }

    /** Implementation maxima, in wire order; pinned against {@link BindingIrV1.Limits} by conformance tests. */
    static final long[] LIMIT_MAXIMA = {32, 256, 65_536, 65_536, 4, 16, 65_536, 512, 32, 65_536, 4_194_304,
            67_108_864};

    /** Structural bounds enforced by the compiler and contract constructors. */
    static Map<String, Object> structuralLimits() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("components", 16);
        value.put("bindings", 256);
        value.put("clausesPerCondition", 8);
        value.put("inEntries", 64);
        value.put("mappingFields", 16);
        value.put("configurationSettings", 64);
        value.put("functionArguments", 8);
        value.put("functionNesting", 2);
        value.put("identifierPattern", "[a-z][a-z0-9-]{0,62}");
        value.put("fieldNamePattern", "[a-zA-Z][a-zA-Z0-9_.-]{0,126}");
        value.put("topicMaximumCharacters", 127);
        value.put("expressionSourceCharacters", 8_192);
        value.put("expressionNodesHardMaximum", 512);
        value.put("bytesLiteralHexCharacters", 131_072);
        value.put("irBytes", CompositeCommitmentV1.MAX_PROFILE_BYTES);
        value.put("sourceCharacters", BindingDocumentCompiler.MAX_SOURCE_CHARACTERS);
        value.put("sourceTokens", 32_768);
        value.put("sourceNesting", 48);
        return value;
    }

    /** Framework baseline event available from every component with a kernel. */
    static Map<String, Object> baselineEvent() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("eventId", BindingProgram.BASELINE);
        value.put("fields", List.of(field("topic", "text"), field("sender", "bytes"), field("messageId", "bytes"),
                field("body", "bytes"), field("bodyHash", "bytes"), field("bodyLength", "integer")));
        value.put("materialized", "only when a binding subscribes to it");
        return value;
    }

    private static Map<String, Object> field(String name, String type) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("type", type);
        value.put("required", true);
        return value;
    }

    /**
     * Framework receipt codes (ADR-031.2 contract C6). A target kernel's rejection code is recorded verbatim and
     * may equal any framework code, so every entry has {@code origin: either}: a known code never proves that the
     * engine produced it. {@code levels} lists where the engine can raise the code ({@code source}, {@code step},
     * {@code condition}, {@code mapping}, {@code effect}). {@code locatesLastCondition} describes engine semantics:
     * {@code always} means the engine appends the failing binding and clause as the rejected step's last condition
     * record (the 256-record cap is checked before evaluation, so the append always fits); {@code never} means it
     * does not; {@code ambiguous} means both occur. A consumer may therefore claim a clause location only for an
     * {@code always} code on a rejected step that has at least one condition record: a kernel rejection is decided
     * before any condition of its step is evaluated, so a kernel-originated rejection's step has none.
     */
    static List<Map<String, Object>> receiptCodes() {
        List<Map<String, Object>> codes = new ArrayList<>();
        code(codes, "ADMISSION", "target-rejection", "step", "never");
        code(codes, "MALFORMED_SOURCE_COMMAND", "target-rejection", "source", "never");
        code(codes, "MALFORMED_DERIVED_COMMAND", "target-rejection", "step", "never");
        code(codes, "CAPACITY_EXCEEDED", "resource-exhaustion", "source-or-condition", "ambiguous");
        code(codes, "COMMAND_PAYLOAD_TOO_LARGE", "resource-exhaustion", "step", "never");
        code(codes, "COMMAND_WORK_EXCEEDED", "resource-exhaustion", "step", "never");
        code(codes, "CRYPTO_WORK_EXCEEDED", "resource-exhaustion", "step", "never");
        code(codes, "EVENT_PAYLOAD_TOO_LARGE", "resource-exhaustion", "step", "never");
        code(codes, "EXPRESSION_CAPACITY_EXCEEDED", "resource-exhaustion", "any", "ambiguous");
        code(codes, "LIMIT_DEPTH", "resource-exhaustion", "step", "never");
        code(codes, "LIMIT_FANOUT", "resource-exhaustion", "condition", "ambiguous");
        code(codes, "RECEIPT_CAPACITY_EXCEEDED", "resource-exhaustion", "source", "never");
        code(codes, "EFFECT_CAPACITY_EXCEEDED", "resource-exhaustion", "effect", "never");
        code(codes, "EFFECT_PAYLOAD_LIMIT", "resource-exhaustion", "effect", "never");
        code(codes, "EFFECT_EXPIRY_LIMIT", "resource-exhaustion", "effect", "never");
        code(codes, "EVENT_TYPE_ERROR", "evaluation-error", "step-or-condition", "ambiguous");
        code(codes, "EVENT_MISSING_FIELD", "contract-violation", "step", "never");
        code(codes, "EXPRESSION_DIVISION_BY_ZERO", "evaluation-error", "condition-or-mapping", "ambiguous");
        code(codes, "EXPRESSION_MISSING_FIELD", "evaluation-error", "condition-or-mapping", "ambiguous");
        code(codes, "EXPRESSION_OVERFLOW", "evaluation-error", "condition-or-mapping", "ambiguous");
        code(codes, "EXPRESSION_TYPE_ERROR", "evaluation-error", "condition-or-mapping", "ambiguous");
        code(codes, "EXPRESSION_VALUE_LIMIT", "evaluation-error", "condition-or-mapping", "ambiguous");
        code(codes, "FUNCTION_INPUT_LIMIT", "evaluation-error", "condition-or-mapping", "ambiguous");
        code(codes, "FUNCTION_INVALID_CBOR", "evaluation-error", "condition-or-mapping", "ambiguous");
        code(codes, "FUNCTION_MISSING_FIELD", "evaluation-error", "condition-or-mapping", "ambiguous");
        code(codes, "FUNCTION_OUTPUT_LIMIT", "evaluation-error", "condition-or-mapping", "ambiguous");
        code(codes, "INVALID_UNICODE", "evaluation-error", "condition-or-mapping", "ambiguous");
        code(codes, "LOOKUP_KEY_INVALID", "evaluation-error", "condition", "always");
        code(codes, "LOOKUP_KEY_LIMIT", "evaluation-error", "condition", "always");
        code(codes, "LOOKUP_KEY_TYPE", "evaluation-error", "condition", "always");
        code(codes, "MAPPING_MISSING_FIELD", "evaluation-error", "condition-or-mapping", "ambiguous");
        code(codes, "MAPPING_TYPE_ERROR", "evaluation-error", "mapping", "never");
        code(codes, "CONSUMPTION_CONFLICT", "replay-or-conflict", "step", "never");
        code(codes, "REPLAY_OR_CONFLICT", "replay-or-conflict", "source", "never");
        code(codes, "RESERVED_ACCOUNTING_KEY", "contract-violation", "step", "never");
        code(codes, "RESERVED_EVENT_ID", "contract-violation", "step", "never");
        code(codes, "STATE_KEY_LIMIT", "contract-violation", "step", "never");
        code(codes, "UNDECLARED_WORK_REFERENCE", "contract-violation", "step", "never");
        return List.copyOf(codes);
    }

    private static final Map<String, List<String>> LEVELS = Map.of(
            "source", List.of("source"), "step", List.of("step"), "condition", List.of("condition"),
            "mapping", List.of("mapping"), "effect", List.of("effect"),
            "source-or-condition", List.of("source", "condition"), "step-or-condition", List.of("step", "condition"),
            "condition-or-mapping", List.of("condition", "mapping"),
            "any", List.of("source", "step", "condition", "mapping", "effect"));

    private static void code(List<Map<String, Object>> codes, String code, String category, String level,
                             String locatesLastCondition) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", code);
        value.put("category", category);
        value.put("origin", "either");
        value.put("levels", LEVELS.get(level));
        value.put("locatesLastCondition", locatesLastCondition);
        codes.add(value);
    }
}
