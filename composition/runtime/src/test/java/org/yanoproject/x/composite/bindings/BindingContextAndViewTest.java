package org.yanoproject.x.composite.bindings;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-031.3 Phase 2: binding {@code context.*}, the descriptor command view, and the scoped dialect. */
class BindingContextAndViewTest {
    private static final byte[] VALUE = {5, 6};

    @Test
    void conditionsAndMappingsSeeTheProducingStepContextAtEachDepth() {
        var first = new BindingIrV1.Binding("first", "a", "recorded.v1", List.of(
                condition(new Call("and", List.of(new Call("not", List.of(context("derived"))),
                        new Call("and", List.of(eq(context("depth"), new Literal(0L)),
                                eq(context("binding"), new Literal(""))))))),
                condition(eq(context("height"), new Literal(5L))),
                condition(eq(context("sender"), new Literal(CascadeHarness.sender())))),
                recordTarget("b", new BindingSourceV1.Field(Scope.CONTEXT, "binding")));
        var second = new BindingIrV1.Binding("second", "b", "recorded.v1", List.of(
                condition(new Call("and", List.of(context("derived"),
                        new Call("and", List.of(eq(context("depth"), new Literal(1L)),
                                eq(context("binding"), new Literal("first")))))))),
                recordTarget("c", new BindingSourceV1.Field(Scope.CONTEXT, "binding")));
        var harness = new CascadeHarness(document(List.of(first, second)));
        var message = CascadeHarness.message(1, "a.v1", CascadeHarness.record(VALUE, "source"));
        harness.apply(5, List.of(message));

        var receipt = harness.receipt(message);
        assertThat(receipt.accepted()).as(receipt.code()).isTrue();
        assertThat(receipt.steps()).extracting(step -> step.bindingId()).containsExactly(null, "first", "second");
        assertThat(stored(harness, "b")).containsExactly(BindingCbor.encode(Arrays.asList(1L, VALUE, "", VALUE)));
        assertThat(stored(harness, "c")).containsExactly(BindingCbor.encode(Arrays.asList(1L, VALUE, "first",
                VALUE)));
        // A derived command still carries the originator: the context is descriptive, never authority.
        assertThat(harness.kernels.get("c").decided.getFirst().sender()).containsExactly(CascadeHarness.sender());
    }

    @Test
    void contextIsNeverEvidence() {
        var evidence = new BindingIrV1.Binding("forge", "a", "recorded.v1", List.of(),
                new BindingIrV1.CommandTarget("b", "record", BindingIrV1.Mapping.fields(List.of(
                        new BindingIrV1.Assignment("value", new BindingSourceV1.Field("value")),
                        new BindingIrV1.Assignment("note", new BindingSourceV1.Literal("x")),
                        new BindingIrV1.Assignment("proof", new BindingSourceV1.Field(Scope.CONTEXT, "sender"))))));
        assertThatThrownBy(() -> new CascadeHarness(document(List.of(evidence))))
                .isInstanceOfSatisfying(BindingValidationException.class,
                        failure -> assertThat(failure.code()).isEqualTo("BINDING_EVIDENCE_UNSATISFIABLE"));
    }

    @Test
    void contextHasNoTimestampOrOtherField() {
        assertThatThrownBy(() -> context("timestamp")).hasMessageContaining("unknown context field");
        assertThatThrownBy(() -> new BindingSourceV1.Field(Scope.CONTEXT, "topic"))
                .hasMessageContaining("unknown context field");
    }

    @Test
    void interimGuardRejectsRulesUntilTheyAreEnforced() {
        var rule = new BindingIrV1.AdmissionRule("deny-all", "DENIED", null, List.of(),
                List.of(condition(context("derived"))));
        var ir = new BindingIrV1(List.of(component("a", List.of(new BindingIrV1.RuleAttachment("deny-all", Map.of()))),
                component("b", List.of())), List.of(rule), List.of(), BindingIrV1.Limits.DEFAULT, 1);
        assertThatThrownBy(() -> new CascadeHarness(ir))
                .isInstanceOfSatisfying(BindingValidationException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RULE_UNSUPPORTED"));
    }

    @Test
    void selectabilityFollowsTheDescriptorRule() {
        var kernel = new CascadeHarness.RecordKernel();
        assertThat(BindingCommandView.unselectableReason(kernel.commands())).isNull();
        var one = new CommandDescriptor("put", CommandDescriptor.Layout.MAP, 0, List.of(
                new CommandDescriptor.Field("key", TransitionScalars.Type.BYTES, true, CommandDescriptor.Role.DATA)));
        var array = new CommandDescriptor("set", CommandDescriptor.Layout.ARRAY, 0, List.of(
                new CommandDescriptor.Field("key", TransitionScalars.Type.BYTES, true, CommandDescriptor.Role.DATA)));
        var raw = new CommandDescriptor("append", CommandDescriptor.Layout.RAW_BYTES, 0, List.of());
        var opcodeOne = new CommandDescriptor("a", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 1, List.of());
        var alsoOne = new CommandDescriptor("b", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 1, List.of());
        assertThat(BindingCommandView.selectable(List.of(one))).isTrue();
        assertThat(BindingCommandView.selectable(List.of(array))).isTrue();
        assertThat(BindingCommandView.unselectableReason(List.of(raw))).isEqualTo("the only command is raw bytes");
        assertThat(BindingCommandView.unselectableReason(List.of(opcodeOne, alsoOne))).isEqualTo("duplicate opcode 1");
        assertThat(BindingCommandView.unselectableReason(List.of(one, array)))
                .isEqualTo("several commands without distinct opcodes");
        assertThat(BindingCommandView.unselectableReason(List.of())).isEqualTo("the kernel declares no command");
    }

    @Test
    void viewSelectsByOpcodeExposesOnlyDataAndRoundTripsByteExactly() {
        var commands = new CascadeHarness.RecordKernel().commands();
        var record = BindingCommandView.decode(commands, BindingCbor.encode(Arrays.asList(1L, VALUE, "n",
                new byte[]{9})));
        assertThat(record.command().commandName()).isEqualTo("record");
        assertThat(record.data()).containsOnlyKeys("value", "note");
        assertThat(BindingCommandView.decode(commands, CascadeHarness.record(VALUE, null)).data())
                .containsOnlyKeys("value");
        var transfer = BindingCommandView.decode(commands, CascadeHarness.transfer(5, "m"));
        assertThat(transfer.command().commandName()).isEqualTo("transfer");
        assertThat(transfer.data()).containsEntry("amount", 5L).containsEntry("memo", "m");

        byte[] canonical = CascadeHarness.transfer(5, "m");
        for (byte[] invalid : List.of(Arrays.copyOf(canonical, canonical.length + 1),
                BindingCbor.encode(List.of(2L, "5", "m")), BindingCbor.encode(List.of(3L, 5L, "m")),
                BindingCbor.encode(Arrays.asList(1L, null, null, null)), BindingCbor.encode(List.of(2L, 5L, "m", 1L)),
                BindingCbor.encode(Map.of("amount", 5L)))) {
            assertThatThrownBy(() -> BindingCommandView.decode(commands, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var map = List.of(new CommandDescriptor("put", CommandDescriptor.Layout.MAP, 0, List.of(
                new CommandDescriptor.Field("key", TransitionScalars.Type.BYTES, true, CommandDescriptor.Role.DATA))));
        assertThat(BindingCommandView.decode(map, BindingCbor.encode(Map.of("key", VALUE))).data())
                .containsOnlyKeys("key");
        assertThatThrownBy(() -> BindingCommandView.decode(map, BindingCbor.encode(Map.of("key", VALUE, "x", 1L))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void programViewChargesBothCountersAndFailsClosed() {
        var harness = new CascadeHarness(document(List.of()));
        var cascade = new BindingExpressionEvaluator.Budget(1_000);
        var block = new BindingExpressionEvaluator.Budget(1_000);
        byte[] body = CascadeHarness.transfer(5, "m");
        harness.program.commandView("a", body, cascade, block);
        long encoding = BindingWork.encoding(List.of(2L, 5L, "m"));
        assertThat(cascade.used()).isEqualTo(1 + body.length + encoding).isEqualTo(block.used());
        assertThatThrownBy(() -> harness.program.commandView("a", Arrays.copyOf(body, body.length + 1),
                new BindingExpressionEvaluator.Budget(1_000), new BindingExpressionEvaluator.Budget(1_000)))
                .isInstanceOfSatisfying(BindingFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("ADMISSION_RULE_INPUT"));
        assertThatThrownBy(() -> harness.program.commandView("a", body, new BindingExpressionEvaluator.Budget(3),
                new BindingExpressionEvaluator.Budget(1_000)))
                .isInstanceOfSatisfying(BindingFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("EXPRESSION_CAPACITY_EXCEEDED"));
    }

    @Test
    void inTestsTextMembershipAndChargesEachComparedCandidate() {
        var roles = IntStream.range(0, 64).mapToObj(index -> String.format("role-%02d", index)).toList();
        var expression = new BindingExpressionV1(Type.BOOLEAN, new Call("in", List.of(new Field(Scope.PARAMS, "role"),
                new Field(Scope.FACTS, "roles"))));
        var types = new BindingExpressionEvaluator.Scoped<>(Map.of(Scope.PARAMS, Map.of("role", Type.TEXT),
                Scope.FACTS, Map.of("roles", Type.TEXT_SET)));
        BindingExpressionEvaluator.validate(expression, types, BindingIrV1.Limits.DEFAULT);
        assertThat(evaluate(expression, "role-00", List.of())).isFalse();
        assertThat(evaluate(expression, "role-00", roles)).isTrue();
        assertThat(evaluate(expression, "role-63", roles)).isTrue();
        assertThat(evaluate(expression, "auditor", roles)).isFalse();
        var cascade = new BindingExpressionEvaluator.Budget(10_000);
        BindingExpressionEvaluator.evaluate(expression, inputs("role-00", roles), BindingIrV1.Limits.DEFAULT,
                cascade, new BindingExpressionEvaluator.Budget(10_000));
        assertThat(cascade.used()).isEqualTo(3 + (7 + 7));
        var last = new BindingExpressionEvaluator.Budget(10_000);
        BindingExpressionEvaluator.evaluate(expression, inputs("role-63", roles), BindingIrV1.Limits.DEFAULT,
                last, new BindingExpressionEvaluator.Budget(10_000));
        assertThat(last.used()).isEqualTo(3 + 64 * (7 + 7));
        assertThatThrownBy(() -> BindingExpressionEvaluator.evaluate(expression,
                new BindingExpressionEvaluator.Scoped<>(Map.of(Scope.PARAMS, Map.of("role", "x"))),
                BindingIrV1.Limits.DEFAULT, new BindingExpressionEvaluator.Budget(100),
                new BindingExpressionEvaluator.Budget(100)))
                .isInstanceOfSatisfying(BindingFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("EXPRESSION_MISSING_FIELD"));
    }

    @Test
    void textSetsAreUsableOnlyAsTheSetOperandOfIn() {
        var types = new BindingExpressionEvaluator.Scoped<>(Map.of(Scope.FACTS,
                Map.of("roles", Type.TEXT_SET, "role", Type.TEXT, "count", Type.INTEGER)));
        for (var invalid : List.of(
                new Call("eq", List.of(new Field(Scope.FACTS, "roles"), new Field(Scope.FACTS, "roles"))),
                new Call("in", List.of(new Field(Scope.FACTS, "role"), new Field(Scope.FACTS, "role"))),
                new Call("in", List.of(new Field(Scope.FACTS, "count"), new Field(Scope.FACTS, "roles"))),
                new Call("in", List.of(new Field(Scope.FACTS, "role"), new Call("if", List.of(new Literal(true),
                        new Field(Scope.FACTS, "roles"), new Field(Scope.FACTS, "roles"))))))) {
            assertThatThrownBy(() -> BindingExpressionEvaluator.validate(new BindingExpressionV1(Type.BOOLEAN,
                    invalid), types, BindingIrV1.Limits.DEFAULT)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static boolean evaluate(BindingExpressionV1 expression, String role, List<String> roles) {
        return (Boolean) BindingExpressionEvaluator.evaluate(expression, inputs(role, roles),
                BindingIrV1.Limits.DEFAULT, new BindingExpressionEvaluator.Budget(100_000),
                new BindingExpressionEvaluator.Budget(100_000));
    }

    private static BindingExpressionEvaluator.Scoped<Object> inputs(String role, List<String> roles) {
        return new BindingExpressionEvaluator.Scoped<>(Map.of(Scope.PARAMS, Map.of("role", role),
                Scope.FACTS, Map.of("roles", roles)));
    }

    private static byte[] stored(CascadeHarness harness, String component) {
        var values = harness.states.get(component).values;
        assertThat(harness.states.get(component).businessKeys()).hasSize(1);
        return values.get(harness.states.get(component).businessKeys().getFirst());
    }

    static BindingIrV1.ExpressionClause condition(BindingExpressionV1.Node node) {
        return new BindingIrV1.ExpressionClause(new BindingExpressionV1(Type.BOOLEAN, node));
    }

    static Field context(String name) { return new Field(Scope.CONTEXT, name); }

    static Call eq(BindingExpressionV1.Node left, BindingExpressionV1.Node right) {
        return new Call("eq", List.of(left, right));
    }

    /** Positional commands map every field; the optional evidence {@code proof} copies the event's value. */
    static BindingIrV1.CommandTarget recordTarget(String component, BindingSourceV1 note) {
        return new BindingIrV1.CommandTarget(component, "record", BindingIrV1.Mapping.fields(List.of(
                new BindingIrV1.Assignment("value", new BindingSourceV1.Field("value")),
                new BindingIrV1.Assignment("note", note),
                new BindingIrV1.Assignment("proof", new BindingSourceV1.Field("value")))));
    }

    static BindingIrV1.Component component(String id, List<BindingIrV1.RuleAttachment> attachments) {
        return new BindingIrV1.Component(id, "record", id + ".v1",
                Map.of("tier", new BindingSourceV1.Literal("standard")), 0, 1, attachments);
    }

    static BindingIrV1 document(List<BindingIrV1.Binding> bindings) {
        return new BindingIrV1(List.of(component("a", List.of()), component("b", List.of()),
                component("c", List.of())), List.of(), bindings, BindingIrV1.Limits.DEFAULT, 1);
    }
}
