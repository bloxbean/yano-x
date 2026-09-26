package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.codec.MessageCodec;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.EventDescriptor;
import org.yanoproject.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.x.composite.bindings.BindingProgram;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The exported authoring-language tables must describe exactly what the unchanged compiler, profile constructor
 * and receipt engine accept. These tests exercise the real implementations; the table is never trusted by itself.
 */
class BindingAuthoringLanguageConformanceTest {
    private static final List<String> TYPES = List.of("integer", "text", "bytes", "boolean", "opaque");
    private static final Map<String, String> FIELD = Map.of("integer", "i", "text", "t", "bytes", "b",
            "boolean", "o");

    @Test
    void functionTableMatchesProfileConstructionForEveryArgumentTypeCombination() {
        var functions = BindingAuthoringLanguage.functions();
        assertThat(functions.stream().map(value -> (String) value.get("id")).toList())
                .containsExactlyInAnyOrderElementsOf(BindingDocumentCompiler.FUNCTIONS);
        for (var function : functions) {
            String id = (String) function.get("id");
            for (int arity = 1; arity <= 8; arity++) {
                for (List<String> argumentTypes : combinations(arity)) {
                    String expected = expectedResult(function, argumentTypes);
                    Set<String> accepted = new TreeSet<>();
                    for (String destination : List.of("integer", "text", "bytes", "boolean")) {
                        if (constructs(id, argumentTypes, destination)) accepted.add(destination);
                    }
                    String description = id + argumentTypes;
                    if (expected == null) assertThat(accepted).as(description).isEmpty();
                    else if (expected.equals("opaque")) {
                        assertThat(accepted).as(description).containsExactly("boolean", "bytes", "integer", "text");
                    } else assertThat(accepted).as(description).containsExactly(expected);
                }
            }
        }
    }

    /** Exhaustive up to three arguments; homogeneous and one mixed combination for four to eight arguments. */
    private static List<List<String>> combinations(int arity) {
        List<List<String>> result = new ArrayList<>();
        if (arity <= 3) {
            result.add(new ArrayList<>());
            for (int position = 0; position < arity; position++) {
                List<List<String>> next = new ArrayList<>();
                for (List<String> prefix : result) {
                    for (String type : TYPES) {
                        List<String> extended = new ArrayList<>(prefix);
                        extended.add(type);
                        next.add(extended);
                    }
                }
                result = next;
            }
            return result;
        }
        for (String type : TYPES) result.add(java.util.Collections.nCopies(arity, type));
        List<String> mixed = new ArrayList<>(java.util.Collections.nCopies(arity - 1, "text"));
        mixed.add("bytes");
        result.add(mixed);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static String expectedResult(Map<String, Object> function, List<String> types) {
        int minimum = (int) function.get("minArguments");
        int maximum = (int) function.get("maxArguments");
        if (types.size() < minimum || types.size() > maximum) return null;
        var positions = (List<List<String>>) function.get("arguments");
        for (int index = 0; index < types.size(); index++) {
            List<String> allowed = positions.get(Math.min(index, positions.size() - 1));
            if (!allowed.contains("any") && !allowed.contains(types.get(index))) return null;
        }
        if ((boolean) function.get("homogeneous") && types.stream().distinct().count() != 1) return null;
        String result = (String) function.get("result");
        return result.equals("same-as-arguments") ? types.getFirst() : result;
    }

    private static boolean constructs(String function, List<String> argumentTypes, String destination) {
        List<BindingSourceV1> arguments = argumentTypes.stream().map(type -> type.equals("opaque")
                ? (BindingSourceV1) new BindingSourceV1.Function("cbor-field",
                        List.of(new BindingSourceV1.Field("b"), new BindingSourceV1.Literal("x")))
                : new BindingSourceV1.Field(FIELD.get(type))).toList();
        BindingSourceV1 source;
        try {
            source = new BindingSourceV1.Function(function, arguments);
        } catch (IllegalArgumentException structural) {
            return false;
        }
        var command = new CommandDescriptor("copy", CommandDescriptor.Layout.MAP, 0, List.of(
                field("ri", TransitionScalars.Type.INTEGER), field("rt", TransitionScalars.Type.TEXT),
                field("rb", TransitionScalars.Type.BYTES), field("ro", TransitionScalars.Type.BOOLEAN)));
        String target = switch (destination) {
            case "integer" -> "ri"; case "text" -> "rt"; case "bytes" -> "rb"; default -> "ro";
        };
        var mapping = BindingIrV1.Mapping.fields(List.of(new BindingIrV1.Assignment(target, source)));
        try {
            program(List.of(), mapping, command);
            return true;
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    @Test
    void operatorTableMatchesTheRestrictedCelCompiler() {
        Map<String, BindingExpressionV1.Type> fields = Map.of("i", BindingExpressionV1.Type.INTEGER,
                "j", BindingExpressionV1.Type.INTEGER, "t", BindingExpressionV1.Type.TEXT,
                "u", BindingExpressionV1.Type.TEXT, "b", BindingExpressionV1.Type.BYTES,
                "c", BindingExpressionV1.Type.BYTES, "o", BindingExpressionV1.Type.BOOLEAN,
                "p", BindingExpressionV1.Type.BOOLEAN);
        var limits = BindingIrV1.Limits.DEFAULT;
        Map<String, String> accepted = Map.ofEntries(
                Map.entry("event.i == event.j", "eq"), Map.entry("event.t != event.u", "ne"),
                Map.entry("event.i < event.j", "lt"), Map.entry("event.i <= event.j", "le"),
                Map.entry("event.i > event.j", "gt"), Map.entry("event.i >= event.j", "ge"),
                Map.entry("event.o && event.p", "and"), Map.entry("event.o || event.p", "or"),
                Map.entry("!event.o", "not"), Map.entry("event.o ? event.t : event.u", "if"),
                Map.entry("event.i + event.j", "add"), Map.entry("event.t + event.u", "concat"),
                Map.entry("event.b + event.c", "concat"), Map.entry("event.i - event.j", "sub"),
                Map.entry("event.i * event.j", "mul"), Map.entry("event.i / event.j", "div"),
                Map.entry("event.i % event.j", "mod"), Map.entry("-event.i", "neg"));
        Set<String> exercised = new TreeSet<>();
        accepted.forEach((source, operator) -> {
            var expression = BindingExpressionCompiler.compile(source, fields, limits);
            assertThat(((BindingExpressionV1.Call) expression.root()).operator()).as(source).isEqualTo(operator);
            exercised.add(operator);
        });
        assertThat(exercised).containsExactlyInAnyOrderElementsOf(BindingAuthoringLanguage.operators().stream()
                .map(value -> (String) value.get("ir")).toList());
        for (String rejected : List.of("event.t < event.u", "event.o + event.p", "event.i && event.o",
                "event.t == event.i", "event.i.size()", "[1, 2].exists(x, x > 1)", "1.5 > 1.0",
                "matches(event.t, 'a')", "event.o ? event.i : event.t")) {
            assertThatThrownBy(() -> BindingExpressionCompiler.compile(rejected, fields, limits))
                    .as(rejected).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void limitTableMatchesDefaultsWireOrderAndConstructorMaxima() {
        var limits = BindingAuthoringLanguage.limits();
        assertThat(limits.stream().map(value -> value.get("name")).toList())
                .isEqualTo(BindingDocumentCompiler.LIMIT_NAMES);
        long[] maxima = BindingAuthoringLanguage.LIMIT_MAXIMA;
        for (int index = 0; index < maxima.length; index++) {
            int position = index;
            assertThatCode(() -> limitsWith(position, (int) maxima[position])).doesNotThrowAnyException();
            assertThatThrownBy(() -> limitsWith(position, (int) maxima[position] + 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> limitsWith(position, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThat(limits.get(index).get("maximum")).isEqualTo(Long.toString(maxima[index]));
        }
        var defaults = BindingIrV1.Limits.DEFAULT;
        assertThat(limits.getFirst().get("default")).isEqualTo(Integer.toString(defaults.maxCascadeDepth()));
        assertThat(limits.getLast().get("default")).isEqualTo(Integer.toString(defaults.maxExpressionWorkPerBlock()));
    }

    private static BindingIrV1.Limits limitsWith(int position, int value) {
        var defaults = BindingIrV1.Limits.DEFAULT;
        int[] values = {defaults.maxCascadeDepth(), defaults.maxDerivedPerSourceMessage(),
                defaults.maxDerivedPerBlock(), defaults.maxEventPayloadBytes(), defaults.maxLookupsPerCondition(),
                defaults.maxFunctionCallsPerMapping(), defaults.maxFunctionInputBytes(), defaults.maxExpressionNodes(),
                defaults.maxExpressionDepth(), defaults.maxExpressionValueBytes(),
                defaults.maxExpressionWorkPerCascade(), defaults.maxExpressionWorkPerBlock()};
        values[position] = value;
        return new BindingIrV1.Limits(values[0], values[1], values[2], values[3], values[4], values[5], values[6],
                values[7], values[8], values[9], values[10], values[11]);
    }

    @Test
    void baselineEventTableMatchesTheProgramSchema() {
        var program = program(List.of(), BindingIrV1.Mapping.fields(List.of(new BindingIrV1.Assignment("rb",
                new BindingSourceV1.Field("b")))), new CommandDescriptor("copy", CommandDescriptor.Layout.MAP, 0,
                List.of(field("rb", TransitionScalars.Type.BYTES))));
        Map<String, BindingExpressionV1.Type> schema = program.schema("source", BindingProgram.BASELINE);
        @SuppressWarnings("unchecked")
        var fields = (List<Map<String, Object>>) BindingAuthoringLanguage.baselineEvent().get("fields");
        assertThat(BindingAuthoringLanguage.baselineEvent().get("eventId")).isEqualTo(BindingProgram.BASELINE);
        assertThat(fields).hasSize(schema.size());
        for (var field : fields) {
            assertThat(schema.get((String) field.get("name")).name().toLowerCase())
                    .as((String) field.get("name")).isEqualTo(field.get("type"));
        }
    }

    @Test
    void structuralLimitsMatchContractConstructors() {
        var limits = BindingAuthoringLanguage.structuralLimits();
        var literal = new BindingSourceV1.Literal("x");
        assertThatCode(() -> new BindingSourceV1.Function("concat",
                java.util.Collections.nCopies((int) limits.get("functionArguments"), literal)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new BindingSourceV1.Function("concat",
                java.util.Collections.nCopies((int) limits.get("functionArguments") + 1, literal)))
                .isInstanceOf(IllegalArgumentException.class);
        var operands = java.util.Collections.nCopies((int) limits.get("inEntries"), literal);
        assertThatCode(() -> new BindingIrV1.FieldClause("t", BindingIrV1.Operator.IN, operands))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new BindingIrV1.FieldClause("t", BindingIrV1.Operator.IN,
                java.util.Collections.nCopies(operands.size() + 1, literal)))
                .isInstanceOf(IllegalArgumentException.class);
        var clause = new BindingIrV1.FieldClause("t", BindingIrV1.Operator.EXISTS, List.of());
        var target = new BindingIrV1.CommandTarget("target", "copy", BindingIrV1.Mapping.raw("b"));
        assertThatCode(() -> new BindingIrV1.Binding("b", "source", "e", java.util.Collections.nCopies(
                (int) limits.get("clausesPerCondition"), clause), target)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new BindingIrV1.Binding("b", "source", "e", java.util.Collections.nCopies(
                (int) limits.get("clausesPerCondition") + 1, clause), target))
                .isInstanceOf(IllegalArgumentException.class);
        List<BindingIrV1.Assignment> assignments = new ArrayList<>();
        for (int index = 0; index <= (int) limits.get("mappingFields"); index++) {
            assignments.add(new BindingIrV1.Assignment("f" + index, literal));
        }
        assertThatCode(() -> BindingIrV1.Mapping.fields(assignments.subList(0, assignments.size() - 1)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> BindingIrV1.Mapping.fields(assignments)).isInstanceOf(IllegalArgumentException.class);
        assertThat(limits.get("sourceCharacters")).isEqualTo(BindingDocumentCompiler.MAX_SOURCE_CHARACTERS);
    }

    @Test
    void receiptCodeTableCoversEveryFrameworkRejectionLiteral() throws IOException {
        Path runtime = Path.of(System.getProperty("yano.test.repo-root"))
                .resolve("composition/runtime/src/main/java/org/yanoproject/x/composite/bindings");
        // Every quoted code inside a rejection constructor or assignment, including conditional expressions.
        Pattern site = Pattern.compile("(?:new BindingFailure\\(|new EffectFailure\\(|failure = )([^;]*)");
        Pattern quoted = Pattern.compile("\"([A-Z][A-Z0-9_]+)\"");
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.list(runtime)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var matcher = site.matcher(Files.readString(file));
                while (matcher.find()) {
                    var codes = quoted.matcher(matcher.group(1));
                    while (codes.find()) found.add(codes.group(1));
                }
            }
        }
        Set<String> table = new TreeSet<>(BindingAuthoringLanguage.receiptCodes().stream()
                .map(value -> (String) value.get("code")).toList());
        assertThat(found).hasSizeGreaterThan(30);
        assertThat(table).isEqualTo(found);
        for (var code : BindingAuthoringLanguage.receiptCodes()) {
            assertThat(Set.of("target-rejection", "resource-exhaustion", "evaluation-error", "replay-or-conflict",
                    "contract-violation")).contains((String) code.get("category"));
            assertThat(Set.of("always", "never", "ambiguous")).contains((String) code.get("locatesLastCondition"));
        }
    }

    /**
     * Codes the engine can raise while evaluating conditions are tagged with the binding and clause, so the table
     * must list the condition level and must not claim that such a code never locates the last condition record.
     * Condition evaluation runs {@code BindingProgram.condition}, {@code BindingProgram.source} (lookup keys and
     * operands), the expression evaluator and work charging.
     */
    @Test
    void conditionCapableCodesAreNeverClassifiedAsUnlocatable() throws IOException {
        Path runtime = Path.of(System.getProperty("yano.test.repo-root"))
                .resolve("composition/runtime/src/main/java/org/yanoproject/x/composite/bindings");
        String program = Files.readString(runtime.resolve("BindingProgram.java"));
        String condition = program.substring(program.indexOf("public int condition("),
                program.indexOf("public byte[] payload("));
        int sourceStart = program.indexOf("private Object source(");
        String source = program.substring(sourceStart, program.indexOf("\n    }\n", sourceStart));
        String text = condition + source + Files.readString(runtime.resolve("BindingExpressionEvaluator.java"))
                + Files.readString(runtime.resolve("BindingWork.java"));
        Matcher codes = Pattern.compile("new BindingFailure\\(\"([A-Z][A-Z0-9_]+)\"").matcher(text);
        Set<String> capable = new TreeSet<>();
        while (codes.find()) capable.add(codes.group(1));
        assertThat(capable).contains("MAPPING_MISSING_FIELD", "LOOKUP_KEY_INVALID", "EVENT_TYPE_ERROR");
        List<String> wrong = new ArrayList<>();
        for (var code : BindingAuthoringLanguage.receiptCodes()) {
            if (!capable.contains((String) code.get("code"))) continue;
            @SuppressWarnings("unchecked") List<String> levels = (List<String>) code.get("levels");
            if (!levels.contains("condition") || "never".equals(code.get("locatesLastCondition"))) {
                wrong.add((String) code.get("code"));
            }
        }
        assertThat(wrong).as("condition-capable codes misclassified").isEmpty();
    }

    @Test
    void rawBodyRestrictionMatchesTheProfileRule() {
        var evidence = new CommandDescriptor("approve", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 1, List.of(
                new CommandDescriptor.Field("signature", TransitionScalars.Type.BYTES, true,
                        CommandDescriptor.Role.EVIDENCE)));
        var plain = new CommandDescriptor("read", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 0, List.of());
        for (var commands : List.of(List.of(plain), List.of(plain, evidence))) {
            var kernel = kernel(commands);
            boolean profileAccepts;
            try {
                new BindingProgram(new BindingIrV1(List.of(
                        new BindingIrV1.Component("source", "test", "source.v1", Map.of(), 0),
                        new BindingIrV1.Component("target", "test", "target.v1", Map.of(), 0)),
                        List.of(new BindingIrV1.Binding("forward", "source", BindingProgram.BASELINE, List.of(),
                                new BindingIrV1.CommandTarget("target", "read", BindingIrV1.Mapping.raw("body")))),
                        BindingIrV1.Limits.DEFAULT), Map.of("source", kernel(List.of()), "target", kernel));
                profileAccepts = true;
            } catch (IllegalArgumentException rejected) {
                profileAccepts = false;
            }
            assertThat(BindingAuthoringCatalog.rawBodyTarget(kernel)).isEqualTo(profileAccepts ? "allowed"
                    : "forbidden-evidence");
        }
    }

    private static CommandDescriptor.Field field(String name, TransitionScalars.Type type) {
        return new CommandDescriptor.Field(name, type, false, CommandDescriptor.Role.DATA);
    }

    private static BindingProgram program(List<BindingIrV1.Clause> conditions, BindingIrV1.Mapping mapping,
                                          CommandDescriptor command) {
        var ir = new BindingIrV1(List.of(
                new BindingIrV1.Component("source", "test", "source.v1", Map.of(), 0),
                new BindingIrV1.Component("target", "test", "target.v1", Map.of(), 0)),
                List.of(new BindingIrV1.Binding("forward", "source", "fixture.v1", conditions,
                        new BindingIrV1.CommandTarget("target", "copy", mapping))), BindingIrV1.Limits.DEFAULT);
        return new BindingProgram(ir, Map.of("source", sourceKernel(), "target", kernel(List.of(command))));
    }

    private static TransitionKernel<?, ?> sourceKernel() {
        var event = new EventDescriptor("fixture.v1", List.of(
                new CommandDescriptor.Field("i", TransitionScalars.Type.INTEGER, true, CommandDescriptor.Role.DATA),
                new CommandDescriptor.Field("t", TransitionScalars.Type.TEXT, true, CommandDescriptor.Role.DATA),
                new CommandDescriptor.Field("b", TransitionScalars.Type.BYTES, true, CommandDescriptor.Role.DATA),
                new CommandDescriptor.Field("o", TransitionScalars.Type.BOOLEAN, true, CommandDescriptor.Role.DATA)));
        return kernel(List.of(), List.of(event));
    }

    private static TransitionKernel<?, ?> kernel(List<CommandDescriptor> commands) {
        return kernel(commands, List.of());
    }

    private static TransitionKernel<?, ?> kernel(List<CommandDescriptor> commands, List<EventDescriptor> events) {
        return new TransitionKernel<byte[], Boolean>() {
            @Override public MessageCodec<byte[]> codec() { return new OrderedLogKernel().codec(); }
            @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state) {
                throw new UnsupportedOperationException("schema-only fixture");
            }
            @Override public TransitionDecision decide(byte[] command, TransitionContext context, Boolean facts) {
                throw new UnsupportedOperationException("schema-only fixture");
            }
            @Override public List<CommandDescriptor> commands() { return commands; }
            @Override public List<EventDescriptor> events() { return events; }
            @Override public ConfigurationDescriptor configuration() { return ConfigurationDescriptor.empty(); }
        };
    }
}
