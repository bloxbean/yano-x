package org.yanoproject.x.composite.bindings;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BindingWorkTest {
    @Test
    void utf8SizingIsExactWithoutAllocatingAndRejectsUnpairedSurrogates() {
        assertThat(BindingWork.size("aé€😀")).isEqualTo(10);
        assertThatThrownBy(() -> BindingWork.size("\uD800"))
                .isInstanceOf(BindingFailure.class).hasMessage("INVALID_UNICODE");
        assertThatThrownBy(() -> BindingWork.size("\uDC00"))
                .isInstanceOf(BindingFailure.class).hasMessage("INVALID_UNICODE");
    }

    @Test
    void bothBudgetsRetainAttemptedChargesWhenCascadeExhausts() {
        var cascade = new BindingExpressionEvaluator.Budget(2);
        var block = new BindingExpressionEvaluator.Budget(100);
        assertThatThrownBy(() -> BindingWork.charge(8, cascade, block))
                .isInstanceOf(BindingFailure.class).hasMessage("EXPRESSION_CAPACITY_EXCEEDED");
        assertThat(cascade.used()).isEqualTo(2);
        assertThat(block.used()).isEqualTo(8);
    }

    @Test
    void falseNonCelConditionsConsumeWorkAndCannotBypassBlockFence() {
        var binding = new BindingIrV1.Binding("skip", "log", BindingProgram.BASELINE,
                List.of(new BindingIrV1.FieldClause("body", BindingIrV1.Operator.EQ,
                        List.of(new BindingSourceV1.Literal(new byte[32])))),
                new BindingIrV1.EffectTarget("test", "app-final", "none", 0, BindingIrV1.Mapping.identity()));
        var program = program(binding);
        byte[] unequal = new byte[32];
        unequal[0] = 1;
        var block = new BindingExpressionEvaluator.Budget(100);
        var first = new BindingExpressionEvaluator.Budget(100);
        assertThat(program.condition(binding, Map.of("body", unequal), ignored -> null, first, block)).isZero();
        assertThat(block.used()).isEqualTo(67);
        assertThatThrownBy(() -> program.condition(binding, Map.of("body", unequal), ignored -> null,
                new BindingExpressionEvaluator.Budget(100), block))
                .isInstanceOf(BindingFailure.class).hasMessage("EXPRESSION_CAPACITY_EXCEEDED");
        assertThat(block.used()).isEqualTo(100);
    }

    @Test
    void rawCopiesAreChargedBeforePayloadAllocation() {
        var binding = new BindingIrV1.Binding("copy", "log", BindingProgram.BASELINE, List.of(),
                new BindingIrV1.EffectTarget("test", "app-final", "none", 0, BindingIrV1.Mapping.raw("body")));
        var block = new BindingExpressionEvaluator.Budget(100);
        assertThatThrownBy(() -> program(binding).payload(binding, Map.of("body", new byte[32]), new byte[0],
                new BindingExpressionEvaluator.Budget(16), block))
                .isInstanceOf(BindingFailure.class).hasMessage("EXPRESSION_CAPACITY_EXCEEDED");
        assertThat(block.used()).isEqualTo(33);
    }

    private static BindingProgram program(BindingIrV1.Binding binding) {
        var ir = new BindingIrV1(List.of(new BindingIrV1.Component("log", "ordered-log", "log.v1", Map.of(), 0)),
                List.of(binding), BindingIrV1.Limits.DEFAULT);
        return new BindingProgram(ir, Map.of("log", new OrderedLogKernel()));
    }
}
