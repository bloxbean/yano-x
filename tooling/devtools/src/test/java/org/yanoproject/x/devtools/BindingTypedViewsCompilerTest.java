package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator.Scoped;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Quantifier;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-031.4 authoring: reads, the {@code writes.all} and {@code writes.exists} quantifiers, {@code startsWith} and
 * {@code size} compile from CEL into the frozen dialect, and everything else in those areas is refused with a stable
 * diagnostic code.
 */
class BindingTypedViewsCompilerTest {
    private static Map<String, Type> reads() {
        Map<String, Type> reads = new LinkedHashMap<>();
        reads.put("holder.present", Type.BOOLEAN);
        reads.put("holder.status", Type.TEXT);
        reads.put("holder.value.maxTransfer", Type.INTEGER);
        return reads;
    }

    private static Map<String, Type> writes() {
        Map<String, Type> writes = new LinkedHashMap<>();
        writes.put("index", Type.INTEGER);
        writes.put("collection", Type.TEXT);
        writes.put("key", Type.BYTES);
        writes.put("keyText", Type.TEXT);
        writes.put("op", Type.TEXT);
        writes.put("coverage", Type.TEXT);
        writes.put("actorOrganizationId", Type.TEXT);
        writes.put("actorRoles", Type.TEXT_SET);
        writes.put("value.price", Type.INTEGER);
        return writes;
    }

    /** A rule use site: the attached kernel's command, parameters, context, reads and, optionally, write view. */
    private static Scoped<Type> rule(boolean withWrites) {
        Map<Scope, Map<String, Type>> scopes = new EnumMap<>(Scope.class);
        scopes.put(Scope.COMMAND, Map.of("amount", Type.INTEGER, "memo", Type.TEXT, "blob", Type.BYTES));
        scopes.put(Scope.PARAMS, Map.of("collection", Type.TEXT, "role", Type.TEXT));
        scopes.put(Scope.CONFIG, Map.of());
        scopes.put(Scope.CONTEXT, Map.of("height", Type.INTEGER, "sender", Type.BYTES, "derived", Type.BOOLEAN,
                "depth", Type.INTEGER, "binding", Type.TEXT));
        scopes.put(Scope.FACTS, Map.of());
        scopes.put(Scope.READS, reads());
        if (withWrites) scopes.put(Scope.WRITE_ELEMENT, writes());
        return new Scoped<>(scopes);
    }

    private static BindingExpressionV1 compile(String source) {
        return BindingExpressionCompiler.compile(source, rule(true), BindingIrV1.Limits.DEFAULT, "an admission rule");
    }

    @Test
    void readsCompileToReadFieldsIncludingPresentAndValueFields() {
        assertThat(compile("reads.holder.present && reads.holder.status == \"ACTIVE\"").root()).isEqualTo(
                new Call("and", List.of(Field.read("holder", "present"), new Call("eq",
                        List.of(Field.read("holder", "status"), new Literal("ACTIVE"))))));
        assertThat(compile("command.amount <= reads.holder.value.maxTransfer").root()).isEqualTo(new Call("le",
                List.of(new Field(Scope.COMMAND, "amount"), Field.readValue("holder", "maxTransfer"))));
    }

    @Test
    void quantifiersLowerToTheDialectsAllAndExists() {
        // The frozen expression.write-scope vector, from CEL.
        assertThat(compile("writes.all(w, w.collection != params.collection || (w.coverage == \"direct\" "
                + "&& startsWith(w.keyText, w.actorOrganizationId + \"/\")))").root()).isEqualTo(new Quantifier(false,
                new Call("or", List.of(
                        new Call("ne", List.of(Field.element("collection"), new Field(Scope.PARAMS, "collection"))),
                        new Call("and", List.of(new Call("eq", List.of(Field.element("coverage"),
                                        new Literal("direct"))),
                                new Call("startsWith", List.of(Field.element("keyText"), new Call("concat",
                                        List.of(Field.element("actorOrganizationId"), new Literal("/")))))))))));
        assertThat(compile("!writes.exists(x, x.op == \"REVOKE\" && size(x.key) == 0)").root()).isEqualTo(
                new Call("not", List.of(new Quantifier(true, new Call("and", List.of(
                        new Call("eq", List.of(Field.element("op"), new Literal("REVOKE"))),
                        new Call("eq", List.of(new Call("size", List.of(Field.element("key"))),
                                new Literal(0L)))))))));
        assertThat(compile("writes.all(w, w.value.price <= 10 && params.role in w.actorRoles)").root()).isEqualTo(
                new Quantifier(false, new Call("and", List.of(
                        new Call("le", List.of(Field.elementValue("price"), new Literal(10L))),
                        new Call("in", List.of(new Field(Scope.PARAMS, "role"), Field.element("actorRoles")))))));
        // A body may also compare the element with the rule's reads.
        assertThat(compile("writes.exists(w, w.keyText == reads.holder.status)").quantifies()).isTrue();
    }

    @Test
    void startsWithAndSizeAcceptGlobalAndMemberForms() {
        var global = compile("startsWith(command.memo, \"ab\")");
        assertThat(compile("command.memo.startsWith(\"ab\")")).isEqualTo(global);
        assertThat(global.root()).isEqualTo(new Call("startsWith", List.of(new Field(Scope.COMMAND, "memo"),
                new Literal("ab"))));
        // Byte literals compare by encoding: the IR copies arrays defensively.
        assertThat(BindingCbor.encode(compile("startsWith(command.blob, b'\\x01')").wire())).isEqualTo(
                BindingCbor.encode(new BindingExpressionV1(Type.BOOLEAN, new Call("startsWith", List.of(
                        new Field(Scope.COMMAND, "blob"), new Literal(new byte[]{1})))).wire()));
        assertThat(compile("command.memo.size() == size(command.blob)").root()).isEqualTo(new Call("eq", List.of(
                new Call("size", List.of(new Field(Scope.COMMAND, "memo"))),
                new Call("size", List.of(new Field(Scope.COMMAND, "blob"))))));
        assertThatThrownBy(() -> compile("startsWith(command.memo, command.blob)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compile("command.memo.endsWith(\"b\")"))
                .isInstanceOf(IllegalArgumentException.class);
        // The bytes member form is declared too, so both forms lower alike for bytes.
        assertThat(BindingCbor.encode(compile("command.blob.startsWith(b'\\x01')").wire())).isEqualTo(
                BindingCbor.encode(compile("startsWith(command.blob, b'\\x01')").wire()));
    }

    @Test
    void everyOtherComprehensionOrNestingIsRefused() {
        for (String source : new String[]{
                "writes.all(w, writes.exists(v, v.op == w.op))",
                "writes.exists_one(w, w.op == \"PUT\")",
                "writes.map(w, w.op).size() == 1",
                "writes.filter(w, w.op == \"PUT\").size() == 0",
                "[1, 2].all(x, x > 0)",
                "size(writes) < 3",
                "writes.all(w, w)",
                "writes.all(w, w.value == w.value)",
                "writes.all(w, size(w.actorRoles) > 0)",
                "writes.all(w, has(w.op))",
                "w.op == \"PUT\"",
                "writes.all(command, command.op == \"PUT\")",
                "writes.exists(reads, reads.op == \"PUT\")"}) {
            assertThatThrownBy(() -> compile(source)).as(source).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void unknownReadsAndWritesAndMisplacedScopesHaveStableCodes() {
        assertThat(code(() -> compile("reads.holder.limit > 0"))).isEqualTo("RULE_READ_UNKNOWN_FIELD");
        assertThat(code(() -> compile("reads.other.present"))).isEqualTo("RULE_READ_UNKNOWN_FIELD");
        assertThat(code(() -> compile("writes.all(w, w.owner == \"x\")"))).isEqualTo("RULE_FIELD_UNKNOWN");
        assertThat(code(() -> BindingExpressionCompiler.compile("writes.all(w, w.op == \"PUT\")", rule(false),
                BindingIrV1.Limits.DEFAULT, "an admission rule"))).isEqualTo("RULE_WRITES_UNSUPPORTED");
        Scoped<Type> binding = new Scoped<>(Map.of(Scope.EVENT, Map.of("amount", Type.INTEGER), Scope.CONTEXT,
                Map.of("height", Type.INTEGER)));
        assertThat(code(() -> BindingExpressionCompiler.compile("reads.holder.present", binding,
                BindingIrV1.Limits.DEFAULT, "a binding"))).isEqualTo("RULE_SCOPE_INVALID");
        // Bindings may use startsWith and size, which read nothing rule-only.
        assertThat(BindingExpressionCompiler.compile("size(\"abc\") == event.amount", binding,
                BindingIrV1.Limits.DEFAULT, "a binding").root()).isInstanceOf(Call.class);
    }

    @Test
    void everyCodeTheCompilerCanReportIsARegisteredDiagnostic() {
        for (String code : List.of("RULE_SCOPE_INVALID", "RULE_FACT_UNKNOWN", "RULE_FIELD_UNKNOWN",
                "RULE_READ_UNKNOWN_FIELD", "RULE_WRITES_UNSUPPORTED")) {
            assertThat(BindingDiagnostic.CODES).containsKey(code);
        }
    }

    private static String code(Runnable compile) {
        try {
            compile.run();
        } catch (BindingExpressionCompiler.ExpressionException failure) {
            return failure.code();
        }
        throw new AssertionError("expected a compile failure");
    }
}
