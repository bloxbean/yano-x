package org.yanoproject.x.composite.bindings;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator.Budget;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator.Scoped;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Quantifier;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.AdmissionRule;
import org.yanoproject.x.composite.contracts.BindingIrV1.RuleAttachment;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-031.4 dialect operators in the consensus interpreter: {@code startsWith} and {@code size} are live in every
 * use site, charged by operand bytes on both counters; quantifier typing needs a declared write view.
 */
class TypedViewsDialectTest {
    private static final BindingIrV1.Limits LIMITS = BindingIrV1.Limits.DEFAULT;

    private static Object evaluate(BindingExpressionV1.Node root, Type type, Budget cascade, Budget block) {
        return BindingExpressionEvaluator.evaluate(new BindingExpressionV1(type, root),
                new Scoped<>(Map.of(Scope.EVENT, Map.of())), LIMITS, cascade, block);
    }

    private static Object evaluate(BindingExpressionV1.Node root, Type type) {
        return evaluate(root, type, new Budget(1_000_000), new Budget(1_000_000));
    }

    private static Call startsWith(Object value, Object prefix) {
        return new Call("startsWith", List.of(new Literal(value), new Literal(prefix)));
    }

    @Test
    void startsWithComparesTextAndBytePrefixes() {
        assertThat(evaluate(startsWith("org-1/product", "org-1/"), Type.BOOLEAN)).isEqualTo(true);
        assertThat(evaluate(startsWith("org-1/product", "org-2/"), Type.BOOLEAN)).isEqualTo(false);
        assertThat(evaluate(startsWith("abc", ""), Type.BOOLEAN)).isEqualTo(true);
        assertThat(evaluate(startsWith("ab", "abc"), Type.BOOLEAN)).isEqualTo(false);
        assertThat(evaluate(startsWith("résumé", "ré"), Type.BOOLEAN)).isEqualTo(true);
        assertThat(evaluate(startsWith(new byte[]{1, 2, 3}, new byte[]{1, 2}), Type.BOOLEAN)).isEqualTo(true);
        assertThat(evaluate(startsWith(new byte[]{1, 2}, new byte[]{1, 2, 3}), Type.BOOLEAN)).isEqualTo(false);
        assertThat(evaluate(startsWith(new byte[]{1}, new byte[0]), Type.BOOLEAN)).isEqualTo(true);
    }

    @Test
    void sizeIsTheUtf8ByteLengthOrTheByteLength() {
        assertThat(evaluate(new Call("size", List.of(new Literal("abc"))), Type.INTEGER)).isEqualTo(3L);
        // Two-, three- and four-byte UTF-8 sequences.
        assertThat(evaluate(new Call("size", List.of(new Literal("é€😀"))), Type.INTEGER)).isEqualTo(9L);
        assertThat(evaluate(new Call("size", List.of(new Literal(new byte[5]))), Type.INTEGER)).isEqualTo(5L);
        assertThat(evaluate(new Call("size", List.of(new Literal(""))), Type.INTEGER)).isEqualTo(0L);
    }

    @Test
    void bothOperatorsChargeOperandBytesOnBothCounters() {
        var cascade = new Budget(1_000_000);
        var block = new Budget(1_000_000);
        evaluate(startsWith("x".repeat(100), "x".repeat(10)), Type.BOOLEAN, cascade, block);
        // Three visited nodes plus both operands' bytes, on each counter.
        assertThat(cascade.used()).isEqualTo(3 + 110);
        assertThat(block.used()).isEqualTo(cascade.used());
        var sizeCascade = new Budget(1_000_000);
        var sizeBlock = new Budget(1_000_000);
        evaluate(new Call("size", List.of(new Literal("x".repeat(40)))), Type.INTEGER, sizeCascade, sizeBlock);
        assertThat(sizeCascade.used()).isEqualTo(2 + 40);
        assertThat(sizeBlock.used()).isEqualTo(sizeCascade.used());
        assertThatThrownBy(() -> evaluate(startsWith("x".repeat(100), "x"), Type.BOOLEAN, new Budget(50),
                new Budget(1_000_000))).isInstanceOfSatisfying(BindingFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("EXPRESSION_CAPACITY_EXCEEDED"));
    }

    @Test
    void operandTypesAreCheckedStaticallyAndAtEvaluation() {
        var scoped = new Scoped<Type>(Map.of(Scope.EVENT, Map.of("t", Type.TEXT, "b", Type.BYTES, "i",
                Type.INTEGER)));
        for (var root : List.<BindingExpressionV1.Node>of(
                new Call("startsWith", List.of(new Field("t"), new Field("b"))),
                new Call("startsWith", List.of(new Field("i"), new Field("i"))),
                new Call("size", List.of(new Field("i"))))) {
            Type result = root instanceof Call call && call.operator().equals("size") ? Type.INTEGER : Type.BOOLEAN;
            assertThatThrownBy(() -> BindingExpressionEvaluator.validate(new BindingExpressionV1(result, root), scoped,
                    LIMITS)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> evaluate(startsWith("a", new byte[]{1}), Type.BOOLEAN))
                .isInstanceOfSatisfying(BindingFailure.class, failure ->
                        assertThat(failure.code()).isEqualTo("EXPRESSION_TYPE_ERROR"));
    }

    @Test
    void aQuantifierTypeChecksOnlyAgainstADeclaredWriteViewWithABooleanBody() {
        var body = new Quantifier(false, new Call("eq", List.of(Field.element("op"), new Literal("PUT"))));
        var expression = new BindingExpressionV1(Type.BOOLEAN, body);
        assertThatThrownBy(() -> BindingExpressionEvaluator.validate(expression, new Scoped<>(Map.of(Scope.PARAMS,
                Map.of())), LIMITS)).hasMessageContaining("no write view");
        BindingExpressionEvaluator.validate(expression, new Scoped<>(Map.of(Scope.WRITE_ELEMENT, Map.of("op",
                Type.TEXT))), LIMITS);
        // A body that is not boolean is refused, even though the quantifier itself is boolean.
        var textBody = new BindingExpressionV1(Type.BOOLEAN, new Quantifier(true, Field.element("op")));
        assertThatThrownBy(() -> BindingExpressionEvaluator.validate(textBody, new Scoped<>(Map.of(
                Scope.WRITE_ELEMENT, Map.of("op", Type.TEXT))), LIMITS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void untilPhaseThreeAProfileWithReadsOrWriteQuantifiersIsNotConstructed() {
        var reading = new AdmissionRule("reading", "NEVER", null, List.of(), List.of(new BindingIrV1.Read("x", "b",
                "", new BindingSourceV1.Literal("k"))), List.of(BindingContextAndViewTest.condition(
                Field.read("x", "present"))));
        var quantifying = new AdmissionRule("quantifying", "NEVER", null, List.of(), List.of(),
                List.of(BindingContextAndViewTest.condition(new Quantifier(false, new Literal(true)))));
        for (var rule : List.of(reading, quantifying)) {
            var ir = new BindingIrV1(List.of(BindingContextAndViewTest.component("a", List.of(
                    new RuleAttachment(rule.id(), Map.of()))), BindingContextAndViewTest.component("b", List.of())),
                    List.of(rule), List.of(), LIMITS, 1);
            assertThatThrownBy(() -> new CascadeHarness(ir)).as(rule.id())
                    .isInstanceOfSatisfying(BindingValidationException.class, invalid ->
                            assertThat(invalid.code()).isEqualTo("RULE_UNSUPPORTED"));
        }
    }
}
