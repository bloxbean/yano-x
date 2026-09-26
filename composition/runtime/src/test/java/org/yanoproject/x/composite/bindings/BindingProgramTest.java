package org.yanoproject.x.composite.bindings;

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
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class BindingProgramTest {
    @Test
    void rawMappingCannotSelectEvidenceOpcodeThroughAnUnprotectedDescriptor() {
        var source = kernel(List.of());
        var target = kernel(List.of(
                new CommandDescriptor("read", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 0, List.of()),
                new CommandDescriptor("approve", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 1, List.of(
                        new CommandDescriptor.Field("signature", TransitionScalars.Type.BYTES, true,
                                CommandDescriptor.Role.EVIDENCE)))));

        assertThatThrownBy(() -> new BindingProgram(document("source", "read"),
                Map.of("source", source, "target", target)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("binding 'forward' (source/composite.command-accepted.v1): raw body field 'body', "
                        + "target command 'approve' evidence field 'signature': BINDING_EVIDENCE_UNSATISFIABLE")
                .satisfies(error -> located(error, "BINDING_EVIDENCE_UNSATISFIABLE", null, "raw-body", null));
    }

    @Test
    void destinationMismatchIdentifiesOnlyTheOffendingAssignment() {
        assertThatThrownBy(() -> mappedProgram(List.of(), BindingIrV1.Mapping.fields(List.of(
                assignment("first", new BindingSourceV1.Field("body")),
                assignment("second", new BindingSourceV1.Literal("private-value"))))))
                .hasMessage(context() + "target field 'second': binding type mismatch")
                .hasMessageNotContaining("private-value")
                .satisfies(error -> located(error, "BINDING_TYPE_MISMATCH", null, "target-field", "second"));
    }

    @Test
    void unknownAssignmentsAreNamedInDeclarationOrder() {
        assertThatThrownBy(() -> mappedProgram(List.of(), BindingIrV1.Mapping.fields(List.of(
                assignment("first", new BindingSourceV1.Field("body")),
                assignment("unexpectedB", new BindingSourceV1.Literal("private-value")),
                assignment("unexpectedA", new BindingSourceV1.Field("body"))))))
                .hasMessage(context() + "unknown target fields: unexpectedB, unexpectedA")
                .satisfies(error -> located(error, "UNKNOWN_TARGET_FIELD", null, "target-field", "unexpectedB"));
    }

    @Test
    void nestedFunctionFailureRetainsAssignmentAndArgumentPath() {
        var invalid = new BindingSourceV1.Function("concat", List.of(new BindingSourceV1.Field("body"),
                new BindingSourceV1.Function("hex", List.of(new BindingSourceV1.Literal("private-value")))));
        assertThatThrownBy(() -> mappedProgram(List.of(), BindingIrV1.Mapping.fields(List.of(
                assignment("first", new BindingSourceV1.Field("body")), assignment("second", invalid)))))
                .hasMessage(context() + "mapping field 'second': function 'concat': argument[1]: "
                        + "function 'hex': binding type mismatch")
                .satisfies(error -> {
                    located(error, "BINDING_TYPE_MISMATCH", null, "mapping", "second");
                    assertThat(((BindingValidationException) error).argumentPath()).containsExactly(1);
                });
    }

    @Test
    void expressionFailureRetainsTheOffendingAssignment() {
        var expression = new BindingSourceV1.Expression(invalidExpression());
        assertThatThrownBy(() -> mappedProgram(List.of(), BindingIrV1.Mapping.fields(List.of(
                assignment("first", new BindingSourceV1.Field("body")), assignment("second", expression)))))
                .hasMessage(context() + "mapping field 'second': expression: expression operand type mismatch")
                .hasMessageNotContaining("private-value")
                .satisfies(error -> located(error, "EXPRESSION_INVALID", null, "mapping", "second"));
    }

    @Test
    void repeatedConditionFieldsRetainTheExactClauseIndex() {
        var valid = new BindingIrV1.FieldClause("topic", BindingIrV1.Operator.EQ,
                List.of(new BindingSourceV1.Literal("private-value")));
        var invalid = new BindingIrV1.FieldClause("topic", BindingIrV1.Operator.EQ,
                List.of(new BindingSourceV1.Literal(7L)));
        assertThatThrownBy(() -> mappedProgram(List.of(valid, invalid), validMapping()))
                .hasMessage(context() + "condition[1] field 'topic': binding type mismatch")
                .satisfies(error -> located(error, "BINDING_TYPE_MISMATCH", 1, "condition", null));
    }

    @Test
    void nestedExpressionFailureRetainsTheExactConditionIndex() {
        var valid = new BindingIrV1.ExpressionClause(new BindingExpressionV1(BindingExpressionV1.Type.BOOLEAN,
                new BindingExpressionV1.Literal(true)));
        assertThatThrownBy(() -> mappedProgram(List.of(valid,
                new BindingIrV1.ExpressionClause(invalidExpression())), validMapping()))
                .hasMessage(context() + "condition[1]: expression: expression operand type mismatch")
                .hasMessageNotContaining("private-value")
                .satisfies(error -> located(error, "EXPRESSION_INVALID", 1, "expression", null));
    }

    @Test
    void lookupFailuresDistinguishKeyAndOperandInTheExactClause() {
        var valid = new BindingIrV1.LookupClause("source", new BindingSourceV1.Field("body"),
                BindingIrV1.Expectation.EXISTS, null);
        var invalidKey = new BindingIrV1.LookupClause("source", new BindingSourceV1.Field("topic"),
                BindingIrV1.Expectation.EXISTS, null);
        var invalidOperand = new BindingIrV1.LookupClause("source", new BindingSourceV1.Field("body"),
                BindingIrV1.Expectation.EQUAL_EVENT, new BindingSourceV1.Field("topic"));
        assertThatThrownBy(() -> mappedProgram(List.of(valid, invalidKey), validMapping()))
                .hasMessage(context() + "condition[1]: lookup key: binding type mismatch")
                .satisfies(error -> located(error, "BINDING_TYPE_MISMATCH", 1, "lookup-key", null));
        assertThatThrownBy(() -> mappedProgram(List.of(valid, invalidOperand), validMapping()))
                .hasMessage(context() + "condition[1]: lookup operand: binding type mismatch")
                .satisfies(error -> located(error, "BINDING_TYPE_MISMATCH", 1, "lookup-operand", null));
    }

    @Test
    void rawMappingMismatchIdentifiesTheBodyField() {
        assertThatThrownBy(() -> mappedProgram(List.of(), BindingIrV1.Mapping.raw("topic")))
                .hasMessage(context() + "raw body field 'topic': binding type mismatch")
                .satisfies(error -> located(error, "BINDING_TYPE_MISMATCH", null, "raw-body", null));
    }

    @Test
    void unknownSourceFieldRetainsItsAssignment() {
        assertThatThrownBy(() -> mappedProgram(List.of(), BindingIrV1.Mapping.fields(List.of(
                assignment("first", new BindingSourceV1.Field("body")),
                assignment("second", new BindingSourceV1.Field("missing"))))))
                .hasMessage(context() + "mapping field 'second': source: unknown event field: missing")
                .satisfies(error -> located(error, "UNKNOWN_EVENT_FIELD", null, "mapping", "second"));
    }

    @Test
    void remainingConstructionFailuresKeepHistoricalTextAndGainStableCodes() {
        var mapping = validMapping();
        assertThatThrownBy(() -> program(new BindingIrV1.Binding("forward", "source", "missing.event.v1",
                List.of(), new BindingIrV1.CommandTarget("target", "copy", mapping))))
                .hasMessage("binding 'forward' (source/missing.event.v1): unknown or duplicate event: missing.event.v1")
                .satisfies(error -> located(error, "UNKNOWN_EVENT", null, "source-event", null));
        assertThatThrownBy(() -> program(new BindingIrV1.Binding("forward", "source", BindingProgram.BASELINE,
                List.of(), new BindingIrV1.CommandTarget("target", "absent", mapping))))
                .hasMessage(context() + "unknown or duplicate target command")
                .satisfies(error -> located(error, "UNKNOWN_TARGET_COMMAND", null, "target-command", null));
        assertThatThrownBy(() -> program(new BindingIrV1.Binding("forward", "source", BindingProgram.BASELINE,
                List.of(), new BindingIrV1.CommandTarget("target", "copy", BindingIrV1.Mapping.fields(List.of(
                        assignment("second", new BindingSourceV1.Field("body"))))))))
                .hasMessage(context() + "target field 'first': missing mapped field: first")
                .satisfies(error -> located(error, "MISSING_TARGET_FIELD", null, "target-field", "first"));
        var lookup = new BindingIrV1.LookupClause("source", new BindingSourceV1.Field("body"),
                BindingIrV1.Expectation.EXISTS, null);
        assertThatThrownBy(() -> program(new BindingIrV1.Binding("forward", "source", BindingProgram.BASELINE,
                List.of(lookup, lookup, lookup), new BindingIrV1.CommandTarget("target", "copy", mapping))))
                .hasMessage(context() + "condition[2]: lookup limit")
                .satisfies(error -> located(error, "LOOKUP_LIMIT", 2, "condition", null));
        assertThatThrownBy(() -> program(new BindingIrV1.Binding("forward", "source", BindingProgram.BASELINE,
                List.of(), new BindingIrV1.CommandTarget("target", "copy", BindingIrV1.Mapping.fields(List.of(
                        assignment("first", new BindingSourceV1.Function("undefined-fn",
                                List.of(new BindingSourceV1.Field("body"))))))))))
                .hasMessage(context() + "mapping field 'first': function 'undefined-fn': "
                        + "unknown binding function: undefined-fn")
                .satisfies(error -> located(error, "UNKNOWN_FUNCTION", null, "mapping", "first"));
        var loop = new BindingIrV1.Binding("back", "target", BindingProgram.BASELINE, List.of(),
                new BindingIrV1.CommandTarget("source", "copy", mapping));
        var base = document("source", "copy");
        var command = copyCommand();
        assertThatThrownBy(() -> new BindingProgram(new BindingIrV1(base.components(), List.of(
                new BindingIrV1.Binding("forward", "source", BindingProgram.BASELINE, List.of(),
                        new BindingIrV1.CommandTarget("target", "copy", mapping)), loop), base.limits()),
                Map.of("source", kernel(List.of(command)), "target", kernel(List.of(command)))))
                .hasMessage("cyclic binding graph")
                .satisfies(error -> {
                    assertThat(error).isInstanceOf(BindingValidationException.class);
                    assertThat(((BindingValidationException) error).code()).isEqualTo("CYCLIC_BINDING_GRAPH");
                    assertThat(((BindingValidationException) error).bindingIndex()).isNull();
                });
        var evidence = new CommandDescriptor("copy", CommandDescriptor.Layout.MAP, 0, List.of(
                new CommandDescriptor.Field("first", TransitionScalars.Type.BYTES, true,
                        CommandDescriptor.Role.EVIDENCE)));
        assertThatThrownBy(() -> new BindingProgram(new BindingIrV1(base.components(), List.of(
                new BindingIrV1.Binding("forward", "source", BindingProgram.BASELINE, List.of(),
                        new BindingIrV1.CommandTarget("target", "copy", BindingIrV1.Mapping.fields(List.of(
                                assignment("first", new BindingSourceV1.Literal(new byte[] {1}))))))),
                base.limits()), Map.of("source", kernel(List.of()), "target", kernel(List.of(evidence)))))
                .hasMessage(context() + "target field 'first' (evidence): BINDING_EVIDENCE_UNSATISFIABLE")
                .satisfies(error -> located(error, "BINDING_EVIDENCE_UNSATISFIABLE", null, "target-field", "first"));
    }

    @Test
    void causeChainsKeepTheirHistoricalDepthAndMessages() {
        var invalid = new BindingSourceV1.Function("concat", List.of(new BindingSourceV1.Field("body"),
                new BindingSourceV1.Function("hex", List.of(new BindingSourceV1.Literal("private-value")))));
        Throwable error = catchThrowable(() -> mappedProgram(List.of(), BindingIrV1.Mapping.fields(List.of(
                assignment("first", new BindingSourceV1.Field("body")), assignment("second", invalid)))));
        List<String> chain = new java.util.ArrayList<>();
        for (Throwable current = error; current != null; current = current.getCause()) chain.add(current.getMessage());
        assertThat(chain).containsExactly(
                context() + "mapping field 'second': function 'concat': argument[1]: function 'hex': "
                        + "binding type mismatch",
                "mapping field 'second': function 'concat': argument[1]: function 'hex': binding type mismatch",
                "function 'concat': argument[1]: function 'hex': binding type mismatch",
                "argument[1]: function 'hex': binding type mismatch",
                "function 'hex': binding type mismatch",
                "binding type mismatch");
        Throwable unknownEvent = catchThrowable(() -> program(new BindingIrV1.Binding("forward", "source",
                "missing.event.v1", List.of(), new BindingIrV1.CommandTarget("target", "copy", validMapping()))));
        assertThat(unknownEvent.getCause()).hasMessage("unknown or duplicate event: missing.event.v1").hasNoCause();
    }

    @Test
    void runtimeLookupsThrowTheSameMessagesAsBefore() {
        var program = program(new BindingIrV1.Binding("forward", "source", BindingProgram.BASELINE, List.of(),
                new BindingIrV1.CommandTarget("target", "copy", validMapping())));
        assertThatThrownBy(() -> program.kernel("absent")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown component: absent").hasNoCause();
        assertThatThrownBy(() -> program.schema("source", "absent.v1")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown or duplicate event: absent.v1").hasNoCause();
        assertThatThrownBy(() -> program.command(new BindingIrV1.CommandTarget("target", "absent", validMapping())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("unknown or duplicate target command")
                .hasNoCause();
    }

    @Test
    void kernelContractFailuresAreProgramWide() {
        var target = kernel(List.of(new CommandDescriptor("append", CommandDescriptor.Layout.RAW_BYTES, 0, List.of())));
        assertThatThrownBy(() -> new BindingProgram(document("source", "append"),
                Map.of("source", kernel(List.of(), List.of("missing")), "target", target)))
                .isInstanceOfSatisfying(BindingValidationException.class, error -> {
                    assertThat(error.code()).isEqualTo("KERNEL_CONTRACT_INVALID");
                    assertThat(error.bindingIndex()).isNull();
                });
    }

    @Test
    void faultyKernelDescriptorsAreKernelContractFailuresNotAuthoredReferences() {
        IllegalArgumentException original = new IllegalArgumentException("descriptor callback failed");
        // Passes the constructor's kernel-contract pass, then fails when the binding's source schema is read.
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var faulty = new TransitionKernel<byte[], Boolean>() {
            @Override public MessageCodec<byte[]> codec() { return new OrderedLogKernel().codec(); }
            @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state) {
                throw new UnsupportedOperationException("schema-only fixture");
            }
            @Override public TransitionDecision decide(byte[] command, TransitionContext context, Boolean facts) {
                throw new UnsupportedOperationException("schema-only fixture");
            }
            @Override public List<CommandDescriptor> commands() { return List.of(); }
            @Override public List<EventDescriptor> events() {
                if (calls.getAndIncrement() == 0) return List.of();
                throw original;
            }
            @Override public ConfigurationDescriptor configuration() { return ConfigurationDescriptor.empty(); }
        };
        var base = document("source", "copy");
        Throwable error = catchThrowable(() -> new BindingProgram(new BindingIrV1(base.components(), List.of(
                new BindingIrV1.Binding("forward", "source", "published.v1", List.of(),
                        new BindingIrV1.CommandTarget("target", "copy", validMapping()))), base.limits()),
                Map.of("source", faulty, "target", kernel(List.of(copyCommand())))));
        assertThat(error).hasMessage("binding 'forward' (source/published.v1): descriptor callback failed");
        located(error, "KERNEL_CONTRACT_INVALID", null, null, null);
        assertThat(error.getCause()).hasMessage("descriptor callback failed").hasNoCause();
        assertThat(error.getCause().getSuppressed()).containsExactly(original);
    }

    /** Structured fields are additive: the binding index and id are always the failing binding's. */
    private static void located(Throwable error, String code, Integer clause, String part, String field) {
        assertThat(error).isInstanceOf(BindingValidationException.class);
        var structured = (BindingValidationException) error;
        assertThat(structured.code()).isEqualTo(code);
        assertThat(structured.bindingIndex()).isZero();
        assertThat(structured.bindingId()).isEqualTo("forward");
        assertThat(structured.clauseIndex()).isEqualTo(clause);
        assertThat(structured.part()).isEqualTo(part);
        assertThat(structured.field()).isEqualTo(field);
    }

    private static CommandDescriptor copyCommand() {
        return new CommandDescriptor("copy", CommandDescriptor.Layout.MAP, 0, List.of(
                new CommandDescriptor.Field("first", TransitionScalars.Type.BYTES, true, CommandDescriptor.Role.DATA),
                new CommandDescriptor.Field("second", TransitionScalars.Type.BYTES, false,
                        CommandDescriptor.Role.DATA)));
    }

    private static BindingProgram program(BindingIrV1.Binding binding) {
        var base = document("source", "copy");
        return new BindingProgram(new BindingIrV1(base.components(), List.of(binding), base.limits()),
                Map.of("source", kernel(List.of()), "target", kernel(List.of(copyCommand()))));
    }

    private static BindingExpressionV1 invalidExpression() {
        return new BindingExpressionV1(BindingExpressionV1.Type.BOOLEAN,
                new BindingExpressionV1.Call("and", List.of(new BindingExpressionV1.Literal(true),
                        new BindingExpressionV1.Call("eq", List.of(new BindingExpressionV1.Field("body"),
                                new BindingExpressionV1.Literal("private-value"))))));
    }

    private static String context() {
        return "binding 'forward' (source/composite.command-accepted.v1): ";
    }

    private static BindingIrV1.Assignment assignment(String field, BindingSourceV1 source) {
        return new BindingIrV1.Assignment(field, source);
    }

    private static BindingIrV1.Mapping validMapping() {
        return BindingIrV1.Mapping.fields(List.of(assignment("first", new BindingSourceV1.Field("body"))));
    }

    private static BindingProgram mappedProgram(List<BindingIrV1.Clause> conditions, BindingIrV1.Mapping mapping) {
        var base = document("source", "copy");
        var ir = new BindingIrV1(base.components(), List.of(new BindingIrV1.Binding("forward", "source",
                BindingProgram.BASELINE, conditions, new BindingIrV1.CommandTarget("target", "copy", mapping))),
                base.limits());
        var command = new CommandDescriptor("copy", CommandDescriptor.Layout.MAP, 0, List.of(
                new CommandDescriptor.Field("first", TransitionScalars.Type.BYTES, true, CommandDescriptor.Role.DATA),
                new CommandDescriptor.Field("second", TransitionScalars.Type.BYTES, false,
                        CommandDescriptor.Role.DATA)));
        return new BindingProgram(ir, Map.of("source", kernel(List.of()), "target", kernel(List.of(command))));
    }

    @Test
    void baselineDoesNotMakeAnUnknownSourceComponentValid() {
        var target = kernel(List.of(new CommandDescriptor("append", CommandDescriptor.Layout.RAW_BYTES, 0, List.of())));
        assertThatThrownBy(() -> new BindingProgram(document("missing", "append"),
                Map.of("source", kernel(List.of()), "target", target)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown component: missing");
    }

    @Test
    void rawMappingRemainsAvailableForEvidenceFreeKernels() {
        var target = kernel(List.of(new CommandDescriptor("append", CommandDescriptor.Layout.RAW_BYTES, 0, List.of())));
        assertThatCode(() -> new BindingProgram(document("source", "append"),
                Map.of("source", kernel(List.of()), "target", target))).doesNotThrowAnyException();
    }

    @Test
    void participantReadsRejectUnknownSelfAndDuplicateIds() {
        var target = kernel(List.of(new CommandDescriptor("append", CommandDescriptor.Layout.RAW_BYTES, 0, List.of())));
        for (List<String> reads : List.of(List.of("missing"), List.of("source"), List.of("target", "target"))) {
            assertThatThrownBy(() -> new BindingProgram(document("source", "append"),
                    Map.of("source", kernel(List.of(), reads), "target", target)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalid kernel read participants");
        }
        var program = new BindingProgram(document("source", "append"),
                Map.of("source", kernel(List.of(), List.of("target")), "target", target));
        assertThat(program.readParticipants("source")).containsExactly("target");
    }

    private static BindingIrV1 document(String source, String command) {
        return new BindingIrV1(List.of(
                new BindingIrV1.Component("source", "test", "source.v1", Map.of(), 0),
                new BindingIrV1.Component("target", "test", "target.v1", Map.of(), 0)),
                List.of(new BindingIrV1.Binding("forward", source, BindingProgram.BASELINE, List.of(),
                        new BindingIrV1.CommandTarget("target", command, BindingIrV1.Mapping.raw("body")))),
                BindingIrV1.Limits.DEFAULT);
    }

    private static TransitionKernel<?, ?> kernel(List<CommandDescriptor> commands) {
        return kernel(commands, List.of());
    }

    private static TransitionKernel<?, ?> kernel(List<CommandDescriptor> commands, List<String> reads) {
        return new TransitionKernel<byte[], Boolean>() {
            @Override public List<String> readParticipants() { return reads; }
            @Override public MessageCodec<byte[]> codec() { return new OrderedLogKernel().codec(); }
            @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state) {
                throw new UnsupportedOperationException("schema-only fixture");
            }
            @Override public TransitionDecision decide(byte[] command, TransitionContext context, Boolean facts) {
                throw new UnsupportedOperationException("schema-only fixture");
            }
            @Override public List<CommandDescriptor> commands() { return commands; }
            @Override public List<EventDescriptor> events() { return List.of(); }
            @Override public ConfigurationDescriptor configuration() { return ConfigurationDescriptor.empty(); }
        };
    }
}
