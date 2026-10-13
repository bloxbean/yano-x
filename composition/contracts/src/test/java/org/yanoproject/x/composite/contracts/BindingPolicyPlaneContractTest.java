package org.yanoproject.x.composite.contracts;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1.AdmissionRule;
import org.yanoproject.x.composite.contracts.BindingIrV1.Clause;
import org.yanoproject.x.composite.contracts.BindingIrV1.Component;
import org.yanoproject.x.composite.contracts.BindingIrV1.Expectation;
import org.yanoproject.x.composite.contracts.BindingIrV1.ExpressionClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.Limits;
import org.yanoproject.x.composite.contracts.BindingIrV1.LookupClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.Parameter;
import org.yanoproject.x.composite.contracts.BindingIrV1.ParameterType;
import org.yanoproject.x.composite.contracts.BindingIrV1.RuleAttachment;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleFailure;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleTrace;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-031.3 in-place amendment of the v1 binding contracts. */
class BindingPolicyPlaneContractTest {
    // The vectors published before ADR-031.3 (ADR-031.1 layouts).
    private static final String PRE_ADR_IR = "8701781b79616e6f2d782d62696e64696e672d66756e6374696f6e732d76316d79616e6f"
            + "2d782d63656c2d763101828666736f75726365647465737469736f757263652e7631a0000186667461726765746474657374"
            + "697461726765742e7631a00001818567666f727761726466736f75726365781d636f6d706f736974652e636f6d6d616e642d"
            + "61636365707465642e76318084006674617267657466617070656e64820264626f64798c0818201910001910000208191000"
            + "1880101910001a000400001a00400000";
    private static final String PRE_ADR_RECEIPT = "870158200000000000000000000000000000000000000000000000000000000000"
            + "000000016852454a4543544544016d415554484f52495a4154494f4e818a010167666f7277617264667461726765745820000000"
            + "000000000000000000000000000000000000000000000000000000000080806852454a45435445446d415554484f52495a4154"
            + "494f4ef5";
    private static final String PRE_ADR_EXPRESSION = "830103830262676582820166616d6f756e7482000a";

    @Test
    void preAdrBytesFailDecodeWithAnExplicitError() {
        assertThatThrownBy(() -> BindingIrV1.decode(HexFormat.of().parseHex(PRE_ADR_IR)))
                .hasMessageContaining("predates ADR-031.3");
        assertThatThrownBy(() -> BindingReceiptV1.decode(HexFormat.of().parseHex(PRE_ADR_RECEIPT)))
                .hasMessageContaining("predates ADR-031.3");
        assertThatThrownBy(() -> BindingExpressionV1.fromWire(BindingCbor.decode(
                HexFormat.of().parseHex(PRE_ADR_EXPRESSION), 65536))).hasMessageContaining("predates ADR-031.3");
        assertThatThrownBy(() -> BindingSourceV1.fromWire(List.of(0L, "amount")))
                .hasMessageContaining("predates ADR-031.3");
    }

    @Test
    void eachAmendedStructureRejectsItsPreAdrArityExplicitly() {
        var forward = BindingPublishedVectorsTest.forward();
        List<Object> root = new ArrayList<>((List<?>) BindingCbor.decode(forward.encode(), 65536));
        List<Object> limits = new ArrayList<>((List<?>) root.get(7));
        limits.removeLast();
        root.set(7, limits);
        assertThatThrownBy(() -> BindingIrV1.decode(BindingCbor.encode(root))).hasMessageContaining("binding limits");

        List<Object> components = new ArrayList<>((List<?>) BindingCbor.decode(forward.encode(), 65536));
        List<Object> first = new ArrayList<>((List<?>) ((List<?>) components.get(4)).getFirst());
        first.removeLast();
        components.set(4, List.of(first, ((List<?>) components.get(4)).get(1)));
        assertThatThrownBy(() -> BindingIrV1.decode(BindingCbor.encode(components)))
                .hasMessageContaining("component");

        List<Object> expectation = List.of(1L, "suppliers", List.of(0L, 4L, "sender"), List.of(3L, "memo"));
        byte[] admission = BindingPublishedVectorsTest.admission().encode();
        List<Object> wire = new ArrayList<>((List<?>) BindingCbor.decode(admission, 65536));
        List<Object> edge = new ArrayList<>((List<?>) ((List<?>) wire.get(6)).getFirst());
        edge.set(3, List.of(expectation));
        wire.set(6, List.of(edge));
        assertThatThrownBy(() -> BindingIrV1.decode(BindingCbor.encode(wire)))
                .hasMessageContaining("unscoped lookup expectation");
    }

    @Test
    void bindingsReadOnlyEventAndContextScopes() {
        for (Scope scope : List.of(Scope.COMMAND, Scope.PARAMS, Scope.CONFIG, Scope.FACTS)) {
            assertThatThrownBy(() -> binding(List.of(condition(new Field(scope, "x"))), "to"))
                    .as(scope.name()).hasMessageContaining(scope.label() + " scope is not available in a binding");
            assertThatThrownBy(() -> binding(List.of(new LookupClause("suppliers",
                    new BindingSourceV1.Field(scope, "x"), Expectation.EXISTS, null)), "to"))
                    .as(scope.name()).hasMessageContaining("scope is not available");
            assertThatThrownBy(() -> new BindingIrV1.Binding("b", "source", "e", List.of(),
                    new BindingIrV1.CommandTarget("target", "put", BindingIrV1.Mapping.fields(List.of(
                            new BindingIrV1.Assignment("value", new BindingSourceV1.Field(scope, "x")))))))
                    .as(scope.name()).hasMessageContaining("scope is not available");
        }
        var legal = binding(List.of(condition(new Field(Scope.CONTEXT, "derived")),
                new LookupClause("suppliers", new BindingSourceV1.Field(Scope.CONTEXT, "sender"),
                        Expectation.EQUAL_FIELD, new BindingSourceV1.Field("memo"))), "to");
        assertThat(legal.conditions()).hasSize(2);
    }

    @Test
    void rulesReadOnlyRuleScopesAndCommandOnlyWithASelector() {
        assertThatThrownBy(() -> rule("r", null, condition(new Field("amount"))))
                .hasMessageContaining("event scope is not available");
        assertThatThrownBy(() -> rule("r", null, condition(new Field(Scope.COMMAND, "amount"))))
                .hasMessageContaining("command scope is not available in a rule without a command selector");
        assertThatThrownBy(() -> rule("r", null, new LookupClause("suppliers",
                new BindingSourceV1.Field(Scope.EVENT, "sender"), Expectation.EXISTS, null)))
                .hasMessageContaining("event scope is not available");
        assertThatThrownBy(() -> rule("r", null, new BindingIrV1.FieldClause("amount", BindingIrV1.Operator.EXISTS,
                List.of()))).hasMessageContaining("expression and lookup clauses only");
        for (Scope scope : List.of(Scope.PARAMS, Scope.CONFIG, Scope.CONTEXT, Scope.FACTS)) {
            assertThat(rule("r", null, condition(new Field(scope, "derived"))).scopes()).containsExactly(scope);
        }
        assertThat(rule("r", "transfer", condition(new Field(Scope.COMMAND, "amount"))).isStatic()).isTrue();
    }

    @Test
    void rulesClassifyIntoSlotsAndStaticness() {
        var staticRule = rule("s", "transfer", condition(new Call("and", List.of(
                new Field(Scope.COMMAND, "flag"), new Call("eq", List.of(new Field(Scope.PARAMS, "p"),
                        new Field(Scope.CONFIG, "c")))))));
        assertThat(staticRule.isStatic()).isTrue();
        assertThat(staticRule.readsFacts()).isFalse();
        var contextual = rule("c", null, condition(new Field(Scope.CONTEXT, "derived")));
        assertThat(contextual.isStatic()).isFalse();
        var lookup = rule("l", null, new LookupClause("suppliers", new BindingSourceV1.Field(Scope.PARAMS, "key"),
                Expectation.EXISTS, null));
        assertThat(lookup.isStatic()).isFalse();
        assertThat(lookup.readsFacts()).isFalse();
        var factKey = rule("f", null, new LookupClause("suppliers", new BindingSourceV1.Function("utf8-bytes",
                List.of(new BindingSourceV1.Field(Scope.FACTS, "actorId"))), Expectation.EXISTS, null));
        assertThat(factKey.readsFacts()).isTrue();
    }

    @Test
    void ruleIdentityCodesAndParametersAreCanonical() {
        assertThatThrownBy(() -> new AdmissionRule("r", "ADMISSION_RULE_DENIED", null, List.of(),
                List.of(condition(new Field(Scope.CONTEXT, "derived"))))).hasMessageContaining("deny code");
        assertThatThrownBy(() -> new AdmissionRule("r", "lower", null, List.of(),
                List.of(condition(new Field(Scope.CONTEXT, "derived"))))).hasMessageContaining("deny code");
        assertThatThrownBy(() -> new AdmissionRule("r", "DENY", null, List.of(), List.of()))
                .hasMessageContaining("clause count");
        assertThatThrownBy(() -> new AdmissionRule("r", "DENY", null, List.of(
                new Parameter("b", ParameterType.TEXT, null), new Parameter("a", ParameterType.TEXT, null)),
                List.of(condition(new Field(Scope.CONTEXT, "derived"))))).hasMessageContaining("sorted");
        assertThatThrownBy(() -> new Parameter("limit", ParameterType.INTEGER, new BindingSourceV1.Literal("10")))
                .hasMessageContaining("default type");
        assertThatThrownBy(() -> new Parameter("max-amount", ParameterType.INTEGER, null))
                .hasMessageContaining("parameter name");
        assertThat(new Parameter("binding", ParameterType.BINDING, new BindingSourceV1.Literal("b")).type()
                .valueType()).isEqualTo(Type.TEXT);
        assertThatThrownBy(() -> new RuleAttachment("r", Map.of("a.b", new BindingSourceV1.Literal(1L))))
                .hasMessageContaining("parameter name");
    }

    @Test
    void documentOrdersRulesAndBoundsAttachmentsAndLookups() {
        var a = rule("a", null, condition(new Field(Scope.CONTEXT, "derived")));
        var b = rule("b", null, condition(new Field(Scope.CONTEXT, "derived")));
        assertThatThrownBy(() -> document(List.of(b, a), List.of(), Limits.DEFAULT, List.of()))
                .hasMessageContaining("sorted by id");
        assertThatThrownBy(() -> document(List.of(a), List.of(), Limits.DEFAULT, List.of(binding("a"))))
                .hasMessageContaining("duplicate");
        var tight = new Limits(8, 32, 4096, 65536, 1, 8, 65536, 128, 16, 65536, 1048576, 33554432, 1);
        assertThatThrownBy(() -> document(List.of(a, b), List.of(new RuleAttachment("a", Map.of()),
                new RuleAttachment("b", Map.of())), tight, List.of())).hasMessageContaining("attachments exceed");
        var twoLookups = rule("l", null, new LookupClause("suppliers", new BindingSourceV1.Field(Scope.PARAMS, "k"),
                Expectation.EXISTS, null), new LookupClause("suppliers", new BindingSourceV1.Field(Scope.PARAMS, "k"),
                Expectation.ABSENT, null));
        assertThatThrownBy(() -> document(List.of(twoLookups), List.of(), tight, List.of()))
                .hasMessageContaining("lookup clauses exceed");
        assertThatThrownBy(() -> new Limits(8, 32, 4096, 65536, 2, 8, 65536, 128, 16, 65536, 1048576, 33554432, 17))
                .hasMessageContaining("invalid binding limit");
        var decoded = BindingIrV1.decode(document(List.of(a, b), List.of(new RuleAttachment("b", Map.of()),
                new RuleAttachment("a", Map.of())), Limits.DEFAULT, List.of()).encode());
        assertThat(decoded.components().getFirst().admission()).extracting(RuleAttachment::rule)
                .containsExactly("b", "a");
        assertThat(decoded.rule("b")).isEqualTo(b);
    }

    @Test
    void decodeEnforcesUseSiteScopesOnCraftedBytes() {
        byte[] admission = BindingPublishedVectorsTest.admission().encode();
        List<Object> wire = new ArrayList<>((List<?>) BindingCbor.decode(admission, 65536));
        List<Object> edge = new ArrayList<>((List<?>) ((List<?>) wire.get(6)).getFirst());
        edge.set(3, List.of(List.of(2L, List.of(1L, 3L, List.of(1L, 5L, "roles")))));
        wire.set(6, List.of(edge));
        assertThatThrownBy(() -> BindingIrV1.decode(BindingCbor.encode(wire)))
                .hasMessageContaining("facts scope is not available in a binding");
    }

    @Test
    void contextHasExactlyFiveFieldsAndInBelongsToRules() {
        for (String name : List.of("height", "sender", "derived", "depth", "binding")) {
            assertThat(new Field(Scope.CONTEXT, name).name()).isEqualTo(name);
        }
        for (String name : List.of("timestamp", "topic", "x")) {
            assertThatThrownBy(() -> new Field(Scope.CONTEXT, name)).hasMessageContaining("unknown context field");
            assertThatThrownBy(() -> new BindingSourceV1.Field(Scope.CONTEXT, name))
                    .hasMessageContaining("unknown context field");
        }
        var in = condition(new Call("in", List.of(new Field("a"), new Field("b"))));
        assertThatThrownBy(() -> binding(List.of(in), "to")).hasMessageContaining("only in admission rules");
        assertThat(rule("r", null, condition(new Call("in", List.of(new Field(Scope.PARAMS, "role"),
                new Field(Scope.FACTS, "roles"))))).readsFacts()).isTrue();
    }

    @Test
    void namesBoundsAndCountsAreEnforcedByTheCodec() {
        for (String reserved : List.of("in", "null", "true", "if", "while")) {
            assertThatThrownBy(() -> new Parameter(reserved, ParameterType.TEXT, null))
                    .as(reserved).hasMessageContaining("parameter name");
        }
        for (String id : List.of("rA", "9r", "r".repeat(64), "-r")) {
            assertThatThrownBy(() -> rule(id, null, condition(new Field(Scope.CONTEXT, "derived"))))
                    .as(id).hasMessageContaining("binding id");
        }
        for (String code : List.of("aDENY", "DENY-X", "D".repeat(64), "ADMISSION_RULE_X")) {
            assertThatThrownBy(() -> new AdmissionRule("r", code, null, List.of(),
                    List.of(condition(new Field(Scope.CONTEXT, "derived"))))).as(code).hasMessageContaining("deny");
        }
        var clause = condition(new Field(Scope.CONTEXT, "derived"));
        assertThatThrownBy(() -> new AdmissionRule("r", "D", null, List.of(), java.util.Collections.nCopies(9, clause)))
                .hasMessageContaining("clause count");
        var parameters = java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> new Parameter(String.format("p%02d", index), ParameterType.TEXT, null)).toList();
        assertThatThrownBy(() -> new AdmissionRule("r", "D", null, parameters, List.of(clause)))
                .hasMessageContaining("too many rule parameters");
        var attachments = java.util.Collections.nCopies(17, new RuleAttachment("r", Map.of()));
        assertThatThrownBy(() -> new Component("c", "test", "c.v1", Map.of(), 0, 1, attachments))
                .hasMessageContaining("attachments exceed");
        var rules = java.util.stream.IntStream.range(0, 65)
                .mapToObj(index -> rule(String.format("r%02d", index), null, clause)).toList();
        assertThatThrownBy(() -> document(rules, List.of(), Limits.DEFAULT, List.of()))
                .hasMessageContaining("exceeds component/rule/binding limits");
    }

    @Test
    void textSetsAreFieldTypesAndInIsBinary() {
        assertThatThrownBy(() -> new BindingExpressionV1(Type.TEXT_SET, new Field(Scope.FACTS, "roles")))
                .hasMessageContaining("field type only");
        assertThatThrownBy(() -> new Call("in", List.of(new Field(Scope.FACTS, "roles"))))
                .hasMessageContaining("arity");
        assertThat(BindingCbor.encode(new Field(Scope.FACTS, "roles").wire()))
                .containsExactly(BindingCbor.encode(List.of(1, 5, "roles")));
    }

    @Test
    void ruleTracesAreCompactAndDenialsNameARealClause() {
        assertThatThrownBy(() -> new RuleFailure("r", -1, "DENY")).hasMessageContaining("rule failure");
        assertThatThrownBy(() -> new RuleFailure("r", 8, null)).hasMessageContaining("rule failure");
        assertThatThrownBy(() -> new RuleTrace(17, null)).hasMessageContaining("rule trace");
        assertThatThrownBy(() -> new RuleTrace(16, new RuleFailure("r", 0, "DENY")))
                .hasMessageContaining("rule trace");
        assertThat(new RuleTrace(16, null).heldCount()).isEqualTo(16);
        assertThatThrownBy(() -> new RuleFailure("r", 0, "ADMISSION_RULE_DENIED"))
                .hasMessageContaining("rule failure");
        var step = new BindingReceiptV1.Step(0, 0, null, "wallet", new byte[32], List.of(), List.of(),
                new RuleTrace(3, new RuleFailure("a".repeat(63), 7, "D".repeat(63))), "REJECTED",
                "ADMISSION_RULE_DENIED", false);
        byte[] withTrace = new BindingReceiptV1(new byte[32], 1, false, 0, "ADMISSION_RULE_DENIED", List.of(step))
                .encode();
        byte[] without = new BindingReceiptV1(new byte[32], 1, false, 0, "ADMISSION_RULE_DENIED", List.of(
                new BindingReceiptV1.Step(0, 0, null, "wallet", new byte[32], List.of(), List.of(), "REJECTED",
                        "ADMISSION_RULE_DENIED", false))).encode();
        // ADR-031.3 §5.8: a rule failure costs at most 134 bytes more than an empty trace.
        assertThat(withTrace.length - without.length).isLessThanOrEqualTo(134);
        assertThat(BindingReceiptV1.decode(withTrace).steps().getFirst().rules()).isEqualTo(step.rules());
    }

    private static ExpressionClause condition(BindingExpressionV1.Node node) {
        return new ExpressionClause(new BindingExpressionV1(Type.BOOLEAN, node));
    }

    private static AdmissionRule rule(String id, String command, Clause... clauses) {
        return new AdmissionRule(id, "DENY", command, List.of(), List.of(clauses));
    }

    private static BindingIrV1.Binding binding(String id) {
        return binding(id, List.of(), "to");
    }

    private static BindingIrV1.Binding binding(List<Clause> conditions, String field) {
        return binding("b", conditions, field);
    }

    private static BindingIrV1.Binding binding(String id, List<Clause> conditions, String field) {
        return new BindingIrV1.Binding(id, "source", "e", conditions, new BindingIrV1.CommandTarget("target", "put",
                BindingIrV1.Mapping.fields(List.of(new BindingIrV1.Assignment("value",
                        new BindingSourceV1.Field(field))))));
    }

    private static BindingIrV1 document(List<AdmissionRule> rules, List<RuleAttachment> attachments, Limits limits,
                                        List<BindingIrV1.Binding> bindings) {
        return new BindingIrV1(List.of(new Component("source", "test", "source.v1", Map.of(), 0, 1, attachments),
                new Component("target", "test", "target.v1", Map.of(), 0)), rules, bindings, limits, 1);
    }
}
