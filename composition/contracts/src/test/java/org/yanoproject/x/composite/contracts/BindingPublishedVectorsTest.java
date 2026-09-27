package org.yanoproject.x.composite.contracts;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Quantifier;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1.AdmissionRule;
import org.yanoproject.x.composite.contracts.BindingIrV1.Expectation;
import org.yanoproject.x.composite.contracts.BindingIrV1.ExpressionClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.LookupClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.Parameter;
import org.yanoproject.x.composite.contracts.BindingIrV1.ParameterType;
import org.yanoproject.x.composite.contracts.BindingIrV1.RuleAttachment;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleFailure;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleTrace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins independently published wire examples to the production codecs, not a second Java encoder. The vectors
 * were built with an unrelated CBOR encoder and validated against the published CDDL (ADR-031.3 Phase 0, amended by
 * ADR-031.4 Phase 0); the production codecs must reproduce them byte for byte and decode them without normalization.
 */
class BindingPublishedVectorsTest {
    private static final String ROOT = "/cddl/declarative-bindings-v1";
    private static final byte[] ZERO = new byte[32];
    private static final byte[] DERIVED = filled((byte) 0x11);

    @Test
    void publishedVectorsMatchConstructedDocumentsAndRoundTripWithoutNormalization() throws IOException {
        Properties vectors = new Properties();
        try (var input = getClass().getResourceAsStream(ROOT + "-golden-vectors.properties")) {
            assertThat(input).isNotNull();
            vectors.load(input);
        }
        Map<String, byte[]> encoded = new LinkedHashMap<>();
        encoded.put("expression.threshold", BindingCbor.encode(threshold().wire()));
        encoded.put("expression.fact-role", BindingCbor.encode(expression(Type.BOOLEAN,
                new Call("in", List.of(field(Scope.PARAMS, "role"), field(Scope.FACTS, "roles")))).wire()));
        encoded.put("expression.read-value", BindingCbor.encode(expression(Type.BOOLEAN, new Call("le", List.of(
                field(Scope.COMMAND, "amount"), Field.readValue("limits", "max")))).wire()));
        encoded.put("expression.write-scope", BindingCbor.encode(writeScope().wire()));
        encoded.put("expression.write-role", BindingCbor.encode(expression(Type.BOOLEAN, new Quantifier(false,
                new Call("or", List.of(
                        new Call("and", List.of(ne(Field.element("op"), "REVOKE"),
                                ne(Field.element("op"), "RESTORE"))),
                        new Call("and", List.of(eq(Field.element("coverage"), "direct"), new Call("in", List.of(
                                field(Scope.PARAMS, "role"), Field.element("actorRoles"))))))))).wire()));
        encoded.put("expression.size", BindingCbor.encode(expression(Type.BOOLEAN, new Call("le", List.of(
                new Call("size", List.of(new Field("memo"))), new Literal(64L)))).wire()));
        encoded.put("ir.forward", forward().encode());
        encoded.put("ir.admission", admission().encode());
        encoded.put("ir.typed-views", typedViews().encode());
        encoded.put("receipt.accepted", new BindingReceiptV1(ZERO, 1, true, null, "", List.of(
                new BindingReceiptV1.Step(0, 0, null, "wallet", ZERO,
                        List.of("recorded.v1", "composite.command-accepted.v1"), List.of(), new RuleTrace(1, null),
                        "PLANNED", "", false))).encode());
        encoded.put("receipt.rejected", new BindingReceiptV1(ZERO, 1, false, 1, "AUTHORIZATION", List.of(
                new BindingReceiptV1.Step(1, 1, "forward", "target", ZERO, List.of(), List.of(), RuleTrace.NONE,
                        "REJECTED", "AUTHORIZATION", true))).encode());
        encoded.put("receipt.rule-denied", rejectedSource("orders", new RuleTrace(0,
                new RuleFailure("registered-supplier", 0, "NOT_A_REGISTERED_SUPPLIER")), "ADMISSION_RULE_DENIED"));
        encoded.put("receipt.rule-error", rejectedSource("wallet", new RuleTrace(0,
                new RuleFailure("transfer-limit", 0, null)), "ADMISSION_RULE_ERROR"));
        encoded.put("receipt.rule-input", rejectedSource("wallet", new RuleTrace(0,
                new RuleFailure("transfer-limit", -1, null)), "ADMISSION_RULE_INPUT"));
        encoded.put("receipt.fact-denied", rejectedSource("registry", new RuleTrace(0,
                new RuleFailure("operator-for-direct-writes", 0, "ROLE_REQUIRED")), "ADMISSION_RULE_DENIED"));
        encoded.put("receipt.derived-denied", new BindingReceiptV1(ZERO, 7, false, 1, "ADMISSION_RULE_DENIED",
                List.of(new BindingReceiptV1.Step(0, 0, null, "orders", ZERO,
                                List.of("kv-registry.entry-put.v1", "composite.command-accepted.v1"),
                                List.of(new BindingReceiptV1.Condition("order-to-approval", -1)),
                                new RuleTrace(1, null), "PLANNED", "", false),
                        new BindingReceiptV1.Step(1, 1, "order-to-approval", "approvals", DERIVED, List.of(),
                                List.of(), new RuleTrace(0, new RuleFailure("minimum-quorum", 0, "QUORUM_TOO_LOW")),
                                "REJECTED", "ADMISSION_RULE_DENIED", false))).encode());
        encoded.put("receipt.rule-capacity", rejectedSource("wallet", new RuleTrace(1,
                new RuleFailure("registered-sender", 1, null)), "EXPRESSION_CAPACITY_EXCEEDED"));
        encoded.put("receipt.fact-input", rejectedSource("registry", new RuleTrace(1,
                new RuleFailure("operator-for-direct-writes", -1, null)), "ADMISSION_RULE_INPUT"));
        encoded.put("receipt.write-denied", rejectedSource("registry", new RuleTrace(0,
                new RuleFailure("insert-only-observations", 0, "OBSERVATION_NOT_INSERT", 3)),
                "ADMISSION_RULE_DENIED"));
        encoded.put("receipt.write-error", rejectedSource("registry", new RuleTrace(2,
                new RuleFailure("feed-open-and-in-range", 1, null, 127)), "ADMISSION_RULE_ERROR"));
        encoded.put("receipt.read-error", rejectedSource("token", new RuleTrace(1,
                new RuleFailure("tier-limit", -1, null)), "ADMISSION_RULE_ERROR"));
        assertThat(vectors).hasSize(encoded.size() * 2);
        String schema;
        try (var input = getClass().getResourceAsStream(ROOT + ".cddl")) {
            assertThat(input).isNotNull();
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (var vector : encoded.entrySet()) {
            byte[] frozen = HexFormat.of().parseHex(vectors.getProperty(vector.getKey()));
            assertThat(vector.getValue()).as(vector.getKey()).containsExactly(frozen);
            String root = vectors.getProperty(vector.getKey() + ".cddl-root");
            assertThat(schema).contains(root + " = ");
            byte[] decoded = switch (root) {
                case "binding-ir-v1" -> BindingIrV1.decode(frozen).encode();
                case "binding-receipt-v1" -> BindingReceiptV1.decode(frozen).encode();
                case "binding-expression-v1" -> BindingCbor.encode(
                        BindingExpressionV1.fromWire(BindingCbor.decode(frozen, 65536)).wire());
                default -> throw new AssertionError("unknown published CDDL root: " + root);
            };
            assertThat(decoded).as(vector.getKey()).containsExactly(frozen);
        }
    }

    static BindingExpressionV1 threshold() {
        return expression(Type.BOOLEAN, new Call("ge", List.of(new Field("amount"), new Literal(10L))));
    }

    /** The historical forwarding document in the amended layout: no rules and no attachments. */
    static BindingIrV1 forward() {
        return new BindingIrV1(List.of(
                new BindingIrV1.Component("source", "test", "source.v1", Map.of(), 0),
                new BindingIrV1.Component("target", "test", "target.v1", Map.of(), 0)),
                List.of(new BindingIrV1.Binding("forward", "source", "composite.command-accepted.v1", List.of(),
                        new BindingIrV1.CommandTarget("target", "append", BindingIrV1.Mapping.raw("body")))),
                // Published wire examples retain their explicitly encoded original limits across default changes.
                new BindingIrV1.Limits(8, 32, 4096, 4096, 2, 8, 4096, 128, 16, 4096, 262144, 4194304, 4));
    }

    /**
     * A wire-only document (machine ids are {@code test}; it is not a buildable profile) with every scope at every
     * legal use site, both clause kinds, defaults of several kinds, byte-wise parameter order ({@code aa} before
     * {@code b}) against length-first attachment-map key order, a binding-typed parameter, a lookup keyed on a
     * fact, and {@code in} over a kernel-declared text set.
     */
    static BindingIrV1 admission() {
        var actorRegistered = new AdmissionRule("actor-registered", "ACTOR_NOT_REGISTERED", null, List.of(),
                List.of(new LookupClause("suppliers", new BindingSourceV1.Function("utf8-bytes",
                        List.of(new BindingSourceV1.Field(Scope.FACTS, "actorId"))), Expectation.EXISTS, null)));
        var flagged = new AdmissionRule("flagged", "FLAGGED", null,
                List.of(new Parameter("aa", ParameterType.INTEGER, null),
                        new Parameter("b", ParameterType.BOOLEAN, new BindingSourceV1.Literal(true))),
                List.of(new ExpressionClause(expression(Type.BOOLEAN, new Call("or", List.of(
                        field(Scope.PARAMS, "b"),
                        new Call("gt", List.of(field(Scope.PARAMS, "aa"), new Literal(0L)))))))));
        var onlyViaBinding = new AdmissionRule("only-via-binding", "DIRECT_SUBMISSION_FORBIDDEN", null,
                List.of(new Parameter("binding", ParameterType.BINDING, null)),
                List.of(new ExpressionClause(expression(Type.BOOLEAN, new Call("and", List.of(
                        field(Scope.CONTEXT, "derived"),
                        new Call("eq", List.of(field(Scope.CONTEXT, "binding"), field(Scope.PARAMS, "binding")))))))));
        var operator = new AdmissionRule("operator-for-direct-writes", "ROLE_REQUIRED", null,
                List.of(new Parameter("role", ParameterType.TEXT, null)),
                List.of(new ExpressionClause(expression(Type.BOOLEAN, new Call("or", List.of(
                        new Call("eq", List.of(field(Scope.FACTS, "directActorCount"), new Literal(0L))),
                        new Call("in", List.of(field(Scope.PARAMS, "role"), field(Scope.FACTS, "roles")))))))));
        var sender = new BindingSourceV1.Field(Scope.CONTEXT, "sender");
        var registered = new AdmissionRule("registered-sender", "NOT_REGISTERED", null,
                List.of(new Parameter("tier", ParameterType.BYTES, new BindingSourceV1.Literal(new byte[]{1}))),
                List.of(new LookupClause("suppliers", sender, Expectation.EXISTS, null),
                        new LookupClause("suppliers", sender, Expectation.EQUAL_FIELD,
                                new BindingSourceV1.Field(Scope.PARAMS, "tier"))));
        var transferLimit = new AdmissionRule("transfer-limit", "TRANSFER_LIMIT_EXCEEDED", "transfer",
                List.of(new Parameter("maxAmount", ParameterType.INTEGER, new BindingSourceV1.Literal(10000L))),
                List.of(new ExpressionClause(expression(Type.BOOLEAN, new Call("and", List.of(
                                new Call("gt", List.of(field(Scope.COMMAND, "amount"), new Literal(0L))),
                                new Call("le", List.of(field(Scope.COMMAND, "amount"),
                                        field(Scope.PARAMS, "maxAmount"))))))),
                        new ExpressionClause(expression(Type.BOOLEAN, new Call("ne", List.of(
                                field(Scope.CONFIG, "minter"), new Literal("")))))));
        var binding = new BindingIrV1.Binding("wallet-to-audit", "wallet", "recorded.v1", List.of(
                new BindingIrV1.FieldClause("amount", BindingIrV1.Operator.GE,
                        List.of(new BindingSourceV1.Literal(1L))),
                new ExpressionClause(expression(Type.BOOLEAN, new Call("not", List.of(field(Scope.CONTEXT,
                        "derived"))))),
                new LookupClause("suppliers", sender, Expectation.EQUAL_FIELD, new BindingSourceV1.Field("memo"))),
                new BindingIrV1.CommandTarget("audit", "append", BindingIrV1.Mapping.fields(List.of(
                        new BindingIrV1.Assignment("entityId", new BindingSourceV1.Field("to")),
                        new BindingIrV1.Assignment("entryHash", new BindingSourceV1.Function("blake2b-256",
                                List.of(new BindingSourceV1.Field("memo")))),
                        new BindingIrV1.Assignment("reference", new BindingSourceV1.Expression(expression(Type.TEXT,
                                new Call("concat", List.of(new Literal("wallet:"),
                                        field(Scope.CONTEXT, "binding"))))))))));
        return new BindingIrV1(List.of(
                new BindingIrV1.Component("suppliers", "test", "suppliers.command.v1",
                        Map.of("value-format", new BindingSourceV1.Literal("utf8")), 0, 1, List.of(
                                new RuleAttachment("flagged", ordered("b", true, "aa", 1L)))),
                new BindingIrV1.Component("wallet", "test", "wallet.command.v1",
                        Map.of("minter", new BindingSourceV1.Literal("treasury")), 0, 1, List.of(
                                new RuleAttachment("transfer-limit",
                                        Map.of("maxAmount", new BindingSourceV1.Literal(10000L))),
                                new RuleAttachment("registered-sender",
                                        Map.of("tier", new BindingSourceV1.Literal(new byte[]{1}))))),
                new BindingIrV1.Component("registry", "test", "registry.command.v1", Map.of(), 0, 1, List.of(
                        new RuleAttachment("flagged", ordered("b", false, "aa", 2L)),
                        new RuleAttachment("operator-for-direct-writes",
                                Map.of("role", new BindingSourceV1.Literal("operator"))),
                        new RuleAttachment("actor-registered", Map.of()))),
                new BindingIrV1.Component("audit", "test", "audit.command.v1", Map.of(), 0, 1, List.of(
                        new RuleAttachment("only-via-binding",
                                Map.of("binding", new BindingSourceV1.Literal("wallet-to-audit")))))),
                List.of(actorRegistered, flagged, onlyViaBinding, operator, registered, transferLimit),
                List.of(binding),
                BindingIrV1.Limits.DEFAULT, 1);
    }

    /** ADR-031.4: a write-scope rule body, with {@code startsWith} over coverage fields. */
    static BindingExpressionV1 writeScope() {
        return expression(Type.BOOLEAN, new Quantifier(false, new Call("or", List.of(
                new Call("ne", List.of(Field.element("collection"), field(Scope.PARAMS, "collection"))),
                new Call("and", List.of(eq(Field.element("coverage"), "direct"), new Call("startsWith", List.of(
                        Field.element("keyText"), new Call("concat", List.of(Field.element("actorOrganizationId"),
                                new Literal("/")))))))))));
    }

    /**
     * ADR-031.4: a wire-only document with reads keyed by a literal, the sender, and a parameter-derived function;
     * entry and value fields and {@code present}; content quantifiers ({@code all} and a negated {@code exists});
     * {@code startsWith} and {@code size}.
     */
    static BindingIrV1 typedViews() {
        var feed = new AdmissionRule("feed-open-and-in-range", "OBSERVATION_OUT_OF_RANGE", null, List.of(),
                List.of(new BindingIrV1.Read("feed", "registry", "feeds", new BindingSourceV1.Literal("main"))),
                List.of(clause(new Call("and", List.of(Field.read("feed", "present"),
                                eq(Field.readValue("feed", "status"), "OPEN")))),
                        clause(new Quantifier(false, new Call("or", List.of(
                                ne(Field.element("collection"), "observations"),
                                new Call("and", List.of(
                                        new Call("ge", List.of(Field.elementValue("price"),
                                                Field.readValue("feed", "min"))),
                                        new Call("le", List.of(Field.elementValue("price"),
                                                Field.readValue("feed", "max")))))))))));
        var governed = new AdmissionRule("governed-transfer-limit", "TRANSFER_LIMIT_EXCEEDED", "transfer", List.of(),
                List.of(new BindingIrV1.Read("limits", "registry", "settings",
                        new BindingSourceV1.Literal("transfer"))),
                List.of(clause(new Call("le", List.of(field(Scope.COMMAND, "amount"),
                        Field.readValue("limits", "max"))))));
        var insertOnly = new AdmissionRule("insert-only-observations", "OBSERVATION_NOT_INSERT", null,
                List.of(new Parameter("collection", ParameterType.TEXT, new BindingSourceV1.Literal("observations"))),
                List.of(), List.of(clause(new Quantifier(false, new Call("or", List.of(
                        new Call("ne", List.of(Field.element("collection"), field(Scope.PARAMS, "collection"))),
                        eq(Field.element("op"), "PUT_IF_ABSENT")))))));
        var noEmptyRevoke = new AdmissionRule("no-empty-revoke", "EMPTY_KEY", null, List.of(), List.of(),
                List.of(clause(new Call("not", List.of(new Quantifier(true, new Call("and", List.of(
                        eq(Field.element("op"), "REVOKE"),
                        new Call("eq", List.of(new Call("size", List.of(Field.element("key"))),
                                new Literal(0L)))))))))));
        var tier = new AdmissionRule("tier-limit", "TIER_LIMIT_EXCEEDED", "transfer",
                List.of(new Parameter("tiers", ParameterType.TEXT, new BindingSourceV1.Literal("holders"))),
                List.of(new BindingIrV1.Read("holder", "registry", "holders",
                                new BindingSourceV1.Field(Scope.CONTEXT, "sender")),
                        new BindingIrV1.Read("tier", "registry", "tiers", new BindingSourceV1.Function("utf8-bytes",
                                List.of(new BindingSourceV1.Field(Scope.PARAMS, "tiers"))))),
                List.of(clause(new Call("and", List.of(Field.read("holder", "present"),
                                eq(Field.read("holder", "status"), "ACTIVE")))),
                        clause(new Call("le", List.of(field(Scope.COMMAND, "amount"),
                                Field.readValue("holder", "maxTransfer"))))));
        return new BindingIrV1(List.of(
                new BindingIrV1.Component("registry", "test", "registry.command.v1", Map.of(), 0, 1, List.of(
                        new RuleAttachment("insert-only-observations",
                                Map.of("collection", new BindingSourceV1.Literal("observations"))),
                        new RuleAttachment("no-empty-revoke", Map.of()),
                        new RuleAttachment("feed-open-and-in-range", Map.of()))),
                new BindingIrV1.Component("token", "test", "token.command.v1",
                        Map.of("minter", new BindingSourceV1.Literal("")), 0, 1, List.of(
                                new RuleAttachment("governed-transfer-limit", Map.of()),
                                new RuleAttachment("tier-limit",
                                        Map.of("tiers", new BindingSourceV1.Literal("holders")))))),
                List.of(feed, governed, insertOnly, noEmptyRevoke, tier), List.of(), BindingIrV1.Limits.DEFAULT, 1);
    }

    private static ExpressionClause clause(BindingExpressionV1.Node node) {
        return new ExpressionClause(expression(Type.BOOLEAN, node));
    }

    private static Call eq(BindingExpressionV1.Node node, String text) {
        return new Call("eq", List.of(node, new Literal(text)));
    }

    private static Call ne(BindingExpressionV1.Node node, String text) {
        return new Call("ne", List.of(node, new Literal(text)));
    }

    /** Parameters in an authored order; the codec writes them in canonical (length-first) key order. */
    private static Map<String, BindingSourceV1.Literal> ordered(String first, Object firstValue, String second,
                                                             Object secondValue) {
        Map<String, BindingSourceV1.Literal> parameters = new LinkedHashMap<>();
        parameters.put(first, new BindingSourceV1.Literal(firstValue));
        parameters.put(second, new BindingSourceV1.Literal(secondValue));
        return parameters;
    }

    private static byte[] rejectedSource(String component, RuleTrace rules, String code) {
        return new BindingReceiptV1(ZERO, 7, false, 0, code, List.of(new BindingReceiptV1.Step(0, 0, null, component,
                ZERO, List.of(), List.of(), rules, "REJECTED", code, false))).encode();
    }

    private static BindingExpressionV1 expression(Type type, BindingExpressionV1.Node root) {
        return new BindingExpressionV1(type, root);
    }

    private static Field field(Scope scope, String name) { return new Field(scope, name); }

    private static byte[] filled(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }
}
