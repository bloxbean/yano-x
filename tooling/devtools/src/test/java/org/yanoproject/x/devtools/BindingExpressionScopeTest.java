package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator.Scoped;
import org.yanoproject.x.composite.bindings.BindingProgram;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-031.3: CEL scopes per use site, lowering of scoped fields and {@code in}, and positioned scope misuse. */
class BindingExpressionScopeTest {
    private static final BindingIrV1.Limits LIMITS = BindingIrV1.Limits.DEFAULT;
    private static final Scoped<Type> BINDING = new Scoped<>(Map.of(Scope.EVENT, Map.of("amount", Type.INTEGER),
            Scope.CONTEXT, BindingProgram.CONTEXT_FIELDS));
    private static final Scoped<Type> RULE = new Scoped<>(Map.of(
            Scope.COMMAND, Map.of("amount", Type.INTEGER),
            Scope.PARAMS, Map.of("maxAmount", Type.INTEGER, "role", Type.TEXT, "binding", Type.TEXT),
            Scope.CONFIG, Map.of("minter", Type.TEXT),
            Scope.CONTEXT, BindingProgram.CONTEXT_FIELDS,
            Scope.FACTS, Map.of("roles", Type.TEXT_SET, "directActorCount", Type.INTEGER)));

    @Test
    void bindingsReadEventAndProducingStepContext() {
        var expression = compile("event.amount > 0 && !context.derived && context.binding == \"\"", BINDING,
                "a binding");
        assertThat(expression.scopes()).containsExactlyInAnyOrder(Scope.EVENT, Scope.CONTEXT);
        var height = compile("context.height", BINDING, "a binding");
        assertThat(height.root()).isEqualTo(new Field(Scope.CONTEXT, "height"));
        assertThat(height.resultType()).isEqualTo(Type.INTEGER);
    }

    @Test
    void rulesReadEveryRuleScopeAndInLowersToTheInOperator() {
        var limit = compile("command.amount > 0 && command.amount <= params.maxAmount && config.minter != \"\"",
                RULE, "an admission rule");
        assertThat(limit.scopes()).containsExactlyInAnyOrder(Scope.COMMAND, Scope.PARAMS, Scope.CONFIG);
        var role = compile("facts.directActorCount == 0 || params.role in facts.roles", RULE, "an admission rule");
        var in = (Call) ((Call) role.root()).arguments().get(1);
        assertThat(in).isEqualTo(new Call("in", java.util.List.of(new Field(Scope.PARAMS, "role"),
                new Field(Scope.FACTS, "roles"))));
        var arrival = compile("context.derived && context.binding == params.binding", RULE, "an admission rule");
        assertThat(arrival.scopes()).containsExactlyInAnyOrder(Scope.CONTEXT, Scope.PARAMS);
    }

    @Test
    void scopeMisuseNamesTheUnavailableScopeWithItsPosition() {
        assertThatThrownBy(() -> compile("true && facts.roles == facts.roles", BINDING, "a binding"))
                .isInstanceOfSatisfying(BindingExpressionCompiler.ExpressionException.class, failure -> {
                    assertThat(failure.getMessage()).startsWith("facts scope is not available in a binding");
                    assertThat(failure.line()).isEqualTo(1);
                    assertThat(failure.column()).isEqualTo(9);
                });
        assertThatThrownBy(() -> compile("event.amount > 0", RULE, "an admission rule"))
                .isInstanceOfSatisfying(BindingExpressionCompiler.ExpressionException.class, failure ->
                        assertThat(failure.getMessage())
                                .startsWith("event scope is not available in an admission rule"));
        assertThatThrownBy(() -> compile("command.amount > 0", BINDING, "a binding"))
                .hasMessageStartingWith("command scope is not available in a binding");
        assertThatThrownBy(() -> compile("context.timestamp > 0", BINDING, "a binding"))
                .hasMessageContaining("undeclared reference");
        assertThatThrownBy(() -> compile("event.amount > 0 && event.amout > 0", BINDING, "a binding"))
                .hasMessageStartingWith("invalid binding expression: event has no field 'amout' in a binding (declared: ")
                .hasMessageContaining("undeclared reference");
    }

    @Test
    void textSetsAreReadableOnlyThroughIn() {
        assertThatThrownBy(() -> compile("facts.roles == facts.roles", RULE, "an admission rule"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compile("size(facts.roles) > 0", RULE, "an admission rule"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compile("facts.roles.exists(r, r == \"a\")", RULE, "an admission rule"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compile("\"a\" in [\"a\", \"b\"]", RULE, "an admission rule"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BindingExpressionV1 compile(String source, Scoped<Type> fields, String useSite) {
        return BindingExpressionCompiler.compile(source, fields, LIMITS, useSite);
    }
}
