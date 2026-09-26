package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator.Budget;
import org.yanoproject.x.composite.bindings.BindingFailure;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1.Limits;

import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BindingExpressionCompilerTest {
    private static final Map<String, Type> FIELDS = Map.of("amountMinor", Type.INTEGER, "currency", Type.TEXT);

    @Test
    void thresholdsAndConditionalValuesCompileIntoPortableIr() {
        var expression = BindingExpressionCompiler.compile(
                "event.amountMinor >= 100000 && event.currency == 'USD'", FIELDS, Limits.DEFAULT);
        byte[] encoded = BindingCbor.encode(expression.wire());
        var decoded = BindingExpressionV1.fromWire(BindingCbor.decode(encoded, 65536));
        assertThat(evaluate(decoded, Map.of("amountMinor", 100000L, "currency", "USD"))).isEqualTo(true);
        assertThat(evaluate(decoded, Map.of("amountMinor", 99999L, "currency", "USD"))).isEqualTo(false);
        var approvals = BindingExpressionCompiler.compile(
                "event.amountMinor >= 1000000 ? 3 : 2", FIELDS, Limits.DEFAULT);
        assertThat(evaluate(approvals, Map.of("amountMinor", 1000000L))).isEqualTo(3L);
        var literal = BindingExpressionCompiler.compile("42", Map.of(), Limits.DEFAULT);
        assertThat(HexFormat.of().formatHex(BindingCbor.encode(literal.wire()))).isEqualTo("8301008200182a");
    }

    @Test
    void rejectsUnsupportedLanguageAndUnknownFields() {
        for (String source : new String[]{"1.2 + 3.4", "[1,2].all(x, x > 0)", "event.unknown",
                "event.currency.matches('.*')", "size(event.currency)", "{'a': 1}", "null"}) {
            assertThatThrownBy(() -> BindingExpressionCompiler.compile(source, FIELDS, Limits.DEFAULT))
                    .as(source).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void arithmeticErrorsAndCelBooleanMaskingAreDeterministic() {
        assertThatThrownBy(() -> evaluate(compile("9223372036854775807 + 1"), Map.of()))
                .isInstanceOf(BindingFailure.class).hasMessage("EXPRESSION_OVERFLOW");
        assertThatThrownBy(() -> evaluate(compile("1 / 0"), Map.of()))
                .hasMessage("EXPRESSION_DIVISION_BY_ZERO");
        assertThat(evaluate(compile("(1 / 0 == 1) || true"), Map.of())).isEqualTo(true);
        assertThat(evaluate(compile("(1 / 0 == 1) && false"), Map.of())).isEqualTo(false);
        assertThat(evaluate(compile("true ? 7 : 1 / 0"), Map.of())).isEqualTo(7L);
        assertThat(evaluate(compile("-7 / 2"), Map.of())).isEqualTo(-3L);
        assertThat(evaluate(compile("'a' + 'b'"), Map.of())).isEqualTo("ab");
    }

    @Test
    void exhaustedWorkCannotBeMaskedAndBothBudgetsAreCharged() {
        Budget cascade = new Budget(1);
        Budget block = new Budget(100);
        assertThatThrownBy(() -> BindingExpressionEvaluator.evaluate(compile("(1 + 1 == 2) || true"),
                Map.of(), Limits.DEFAULT, cascade, block)).hasMessage("EXPRESSION_CAPACITY_EXCEEDED");
        assertThat(cascade.used()).isEqualTo(1);
        assertThat(block.used()).isEqualTo(2);
    }
    private static BindingExpressionV1 compile(String source) {
        return BindingExpressionCompiler.compile(source, Map.of(), Limits.DEFAULT);
    }
    private static Object evaluate(BindingExpressionV1 expression, Map<String, Object> values) {
        return BindingExpressionEvaluator.evaluate(expression, values, Limits.DEFAULT,
                new Budget(262144), new Budget(4194304));
    }
}
