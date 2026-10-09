package org.yanoproject.x.composite.contracts;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Quantifier;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1.AdmissionRule;
import org.yanoproject.x.composite.contracts.BindingIrV1.ExpressionClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.LookupClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.Read;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleFailure;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleTrace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-031.4 contract amendments: reads, the write-view quantifier, {@code startsWith} and {@code size}, and the
 * receipt's write index. Construction and decode enforce the same use-site table and grammars, and every amended
 * structure fails decode in its pre-ADR form with an explicit error.
 */
class BindingTypedViewsContractTest {
    private static final BindingSourceV1 SENDER = new BindingSourceV1.Field(Scope.CONTEXT, "sender");

    private static ExpressionClause clause(BindingExpressionV1.Node node) {
        return new ExpressionClause(new BindingExpressionV1(Type.BOOLEAN, node));
    }

    private static AdmissionRule rule(List<Read> reads, BindingExpressionV1.Node condition) {
        return new AdmissionRule("r", "DENY", null, List.of(), reads, List.of(clause(condition)));
    }

    private static Read read(String name) { return new Read(name, "registry", "holders", SENDER); }

    private static Call eq(BindingExpressionV1.Node left, Object right) {
        return new Call("eq", List.of(left, new Literal(right)));
    }

    @Test
    void readAndElementFieldsEncodeTheirPathsAsSeparateElements() {
        assertThat(Field.read("holder", "status").wire()).isEqualTo(List.of(1, 6, "holder", "status"));
        assertThat(Field.readValue("holder", "max").wire()).isEqualTo(List.of(1, 6, "holder", "value", "max"));
        // A plain field named "value" (kv-registry) is the four-element form, never a value path.
        assertThat(Field.read("kv", "value").wire()).isEqualTo(List.of(1, 6, "kv", "value"));
        assertThat(Field.element("op").wire()).isEqualTo(List.of(1, 7, "op"));
        assertThat(Field.elementValue("price").wire()).isEqualTo(List.of(1, 7, "value", "price"));
        assertThat(Field.readValue("holder", "max").readName()).isEqualTo("holder");
        var quantified = new BindingExpressionV1(Type.BOOLEAN, new Quantifier(true, eq(Field.elementValue("price"),
                1L)));
        assertThat(BindingExpressionV1.fromWire(BindingCbor.decode(BindingCbor.encode(quantified.wire()), 4096)))
                .isEqualTo(quantified);
        for (String bad : new String[]{"holder", "holder.a.b", "holder.value.", "holder.in", "a-b.c", "x.present.y",
                "h".repeat(64) + ".f"}) {
            assertThatThrownBy(() -> new Field(Scope.READS, bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        for (String bad : new String[]{"a.b", "value.a.b", "in", "value."}) {
            assertThatThrownBy(() -> new Field(Scope.WRITE_ELEMENT, bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // Decode checks the element count of each form, the value marker, and that each element is one identifier.
        for (List<Object> wire : List.<List<Object>>of(List.of(1, 6, "x"), List.of(1, 6, "x", "val", "f"),
                List.of(1, 6, "x", "value", "f", "g"), List.of(1, 7, "a", "b"), List.of(1, 7),
                List.of(1, 6, "r", "value.f"), List.of(1, 6, "r.value", "f"), List.of(1, 7, "value.f"))) {
            assertThatThrownBy(() -> BindingExpressionV1.fromWire(wire(List.of(1, 3, wire))))
                    .as(wire.toString()).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void elementsAreReadOnlyInsideAQuantifierAndQuantifiersNeverNest() {
        assertThatThrownBy(() -> new BindingExpressionV1(Type.BOOLEAN, eq(Field.element("op"), "PUT")))
                .hasMessageContaining("inside a quantifier");
        assertThatThrownBy(() -> new Quantifier(false, new Quantifier(true, new Literal(true))))
                .hasMessageContaining("do not nest");
        assertThatThrownBy(() -> BindingExpressionV1.fromWire(wire(List.of(1, 3, List.of(3, 0, List.of(3, 1,
                List.of(0, true))))))).hasMessageContaining("do not nest");
        assertThatThrownBy(() -> BindingExpressionV1.fromWire(wire(List.of(1, 3, List.of(3, 2, List.of(0, true))))))
                .hasMessageContaining("quantifier");
        assertThatThrownBy(() -> BindingExpressionV1.fromWire(wire(List.of(1, 3, List.of(1, 7, "op")))))
                .hasMessageContaining("inside a quantifier");
        var quantified = new BindingExpressionV1(Type.BOOLEAN, new Quantifier(false, new Literal(true)));
        // A quantifier reads the write view even when its body reads no element.
        assertThat(quantified.scopes()).containsExactly(Scope.WRITE_ELEMENT);
        assertThat(quantified.quantifies()).isTrue();
    }

    @Test
    void readsAndQuantifiersAreLegalOnlyInRuleExpressionClauses() {
        var binding = (Function<BindingExpressionV1.Node, BindingIrV1.Binding>) condition ->
                new BindingIrV1.Binding("b", "a", "e.v1", List.of(clause(condition)),
                        new BindingIrV1.CommandTarget("c", "put", BindingIrV1.Mapping.raw("body")));
        assertThatThrownBy(() -> binding.apply(eq(Field.read("x", "status"), "A")))
                .hasMessageContaining("reads scope is not available in a binding");
        assertThatThrownBy(() -> binding.apply(new Quantifier(false, new Literal(true))))
                .hasMessageContaining("writes scope is not available in a binding");
        // Keys never read reads or quantify: no read chaining, in either key position or form.
        var readExpression = new BindingSourceV1.Expression(new BindingExpressionV1(Type.BYTES, new Call("concat",
                List.of(Field.read("y", "key"), new Literal(new byte[]{1})))));
        var quantifiedKey = new BindingSourceV1.Expression(new BindingExpressionV1(Type.BOOLEAN,
                new Quantifier(false, eq(Field.element("op"), "PUT"))));
        for (BindingSourceV1 key : List.of(readExpression, quantifiedKey)) {
            assertThatThrownBy(() -> new AdmissionRule("r", "DENY", null, List.of(), List.of(
                    new Read("x", "registry", "", key)), List.of(clause(new Literal(true)))))
                    .hasMessageContaining("not available in a rule read key");
            assertThatThrownBy(() -> new AdmissionRule("r", "DENY", null, List.of(), List.of(read("y")),
                    List.of(new LookupClause("registry", key, BindingIrV1.Expectation.EXISTS, null))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new BindingSourceV1.Field(Scope.READS, "y.key"))
                .hasMessageContaining("not a binding source");
        assertThatThrownBy(() -> BindingSourceV1.fromWire(wire(List.of(0, 7, "op"))))
                .hasMessageContaining("not a binding source");
        // Command reads still need a selector; an undeclared read cannot be referenced.
        var commandKey = new Read("x", "registry", "", new BindingSourceV1.Field(Scope.COMMAND, "to"));
        assertThatThrownBy(() -> new AdmissionRule("r", "DENY", null, List.of(), List.of(commandKey),
                List.of(clause(new Literal(true))))).hasMessageContaining("command scope");
        assertThatThrownBy(() -> rule(List.of(read("a")), eq(Field.read("b", "status"), "A")))
                .hasMessageContaining("undeclared rule read: b");
    }

    @Test
    void readsAndKeysRejectOtherShapesInBytes() {
        // A read has exactly four elements, and neither a lookup expectation nor a key names a read or an element.
        assertThatThrownBy(() -> BindingIrV1.decode(withRead(List.of("x", "registry", ""))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BindingIrV1.decode(withRead(List.of("x", "registry", "", List.of(1, "k"), 1))))
                .isInstanceOf(IllegalArgumentException.class);
        for (int scope : new int[]{6, 7}) {
            assertThatThrownBy(() -> BindingSourceV1.fromWire(wire(List.of(0, scope, "f"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // A write index is an integer in the receipt, never text or a boolean.
        for (Object index : List.of("3", true)) {
            List<Object> failure = new ArrayList<>(List.of("r", 0L, "DENY"));
            failure.add(index);
            List<Object> step = new ArrayList<>(List.of(0L, 0L));
            step.add(null);
            step.addAll(List.of("a", new byte[32], List.of(), List.of(), List.of(0L, failure), "REJECTED",
                    "ADMISSION_RULE_DENIED", false));
            List<Object> receipt = new ArrayList<>(List.of(1L, new byte[32], 1L, "REJECTED", 0L,
                    "ADMISSION_RULE_DENIED", List.of(step)));
            assertThatThrownBy(() -> BindingReceiptV1.decode(BindingCbor.encode(receipt)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** A rule-bearing document whose first rule's reads are replaced by {@code read}. */
    private static byte[] withRead(List<Object> read) {
        List<Object> root = mutable(BindingCbor.decode(BindingPublishedVectorsTest.typedViews().encode(), 65536));
        @SuppressWarnings("unchecked")
        List<Object> rules = (List<Object>) root.get(5);
        List<Object> rule = mutable(rules.getFirst());
        rule.set(4, List.of(read));
        rules.set(0, rule);
        return BindingCbor.encode(root);
    }

    @Test
    void readsAreBoundedSortedAndWellNamed() {
        List<Read> five = new ArrayList<>();
        for (String name : List.of("a", "b", "c", "d", "e")) five.add(read(name));
        assertThat(rule(five.subList(0, 4), new Literal(true)).reads()).hasSize(AdmissionRule.MAX_READS);
        assertThatThrownBy(() -> rule(five, new Literal(true))).hasMessageContaining("too many rule reads");
        assertThatThrownBy(() -> rule(List.of(read("b"), read("a")), new Literal(true)))
                .hasMessageContaining("sorted");
        assertThatThrownBy(() -> rule(List.of(read("a"), read("a")), new Literal(true)))
                .hasMessageContaining("sorted");
        for (String name : new String[]{"in", "1a", "a.b", "a-b", "a".repeat(64)}) {
            assertThatThrownBy(() -> read(name)).as(name).isInstanceOf(IllegalArgumentException.class);
        }
        for (String namespace : new String[]{"Holders", "-x", "x y", "x".repeat(65)}) {
            assertThatThrownBy(() -> new Read("a", "registry", namespace, SENDER)).as(namespace)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new Read("a", "registry", "1x.y_z-9", SENDER).namespace()).isEqualTo("1x.y_z-9");
    }

    @Test
    void classificationSeesReadsWritesAndFacts() {
        var content = rule(List.of(), new Quantifier(false, eq(Field.element("op"), "PUT")));
        assertThat(content.isStatic()).isTrue();
        assertThat(content.readsWrites()).isTrue();
        var withRead = rule(List.of(read("x")), eq(Field.read("x", "status"), "ACTIVE"));
        assertThat(withRead.isStatic()).isFalse();
        assertThat(withRead.readsWrites()).isFalse();
        var factKey = rule(List.of(new Read("x", "registry", "", new BindingSourceV1.Field(Scope.FACTS, "actorId"))),
                Field.read("x", "present"));
        assertThat(factKey.readsFacts()).isTrue();
    }

    @Test
    void startsWithTakesTwoOperandsAndSizeOne() {
        assertThat(new Call("startsWith", List.of(new Literal("ab"), new Literal("a"))).arguments()).hasSize(2);
        assertThat(new Call("size", List.of(new Literal("ab"))).arguments()).hasSize(1);
        assertThatThrownBy(() -> new Call("startsWith", List.of(new Literal("a"))))
                .hasMessageContaining("arity");
        assertThatThrownBy(() -> new Call("size", List.of(new Literal("a"), new Literal("b"))))
                .hasMessageContaining("arity");
        assertThatThrownBy(() -> new Call("endsWith", List.of(new Literal("a"), new Literal("b"))))
                .hasMessageContaining("unsupported");
    }

    @Test
    void receiptsRecordTheDecidingWriteWithinBounds() {
        var failure = new RuleFailure("r", 0, "DENY", 127);
        assertThat(failure.wire()).isEqualTo(Arrays.asList("r", 0, "DENY", 127));
        assertThat(new RuleFailure("r", 1, null).writeIndex()).isNull();
        assertThatThrownBy(() -> new RuleFailure("r", 0, "DENY", 128)).hasMessageContaining("write index");
        assertThatThrownBy(() -> new RuleFailure("r", 0, "DENY", -1)).hasMessageContaining("write index");
        // A failure before any clause has no deciding write.
        assertThatThrownBy(() -> new RuleFailure("r", -1, null, 0)).hasMessageContaining("write index");
    }

    @Test
    void preTypedViewsStructuresFailDecodeWithExplicitErrors() {
        // A rule-bearing document with its reads list removed has the ADR-031.3 five-element rule layout.
        byte[] current = BindingPublishedVectorsTest.admission().encode();
        List<Object> root = mutable(BindingCbor.decode(current, 65536));
        @SuppressWarnings("unchecked")
        List<Object> rules = (List<Object>) root.get(5);
        for (int index = 0; index < rules.size(); index++) {
            List<Object> legacy = mutable(rules.get(index));
            legacy.remove(4);
            rules.set(index, legacy);
        }
        assertThatThrownBy(() -> BindingIrV1.decode(BindingCbor.encode(root)))
                .hasMessageContaining("binding IR predates ADR-031.4: admission rule");

        byte[] receipt = new BindingReceiptV1(new byte[32], 7, false, 0, "ADMISSION_RULE_DENIED", List.of(
                new BindingReceiptV1.Step(0, 0, null, "orders", new byte[32], List.of(), List.of(),
                        new RuleTrace(0, new RuleFailure("r", 0, "DENY")), "REJECTED", "ADMISSION_RULE_DENIED",
                        false))).encode();
        List<Object> envelope = mutable(BindingCbor.decode(receipt, 65536));
        @SuppressWarnings("unchecked")
        List<Object> steps = (List<Object>) envelope.get(6);
        List<Object> step = mutable(steps.getFirst());
        List<Object> trace = mutable(step.get(7));
        List<Object> legacyFailure = mutable(trace.get(1));
        legacyFailure.remove(3);
        trace.set(1, legacyFailure);
        step.set(7, trace);
        steps.set(0, step);
        assertThatThrownBy(() -> BindingReceiptV1.decode(BindingCbor.encode(envelope)))
                .hasMessageContaining("binding receipt predates ADR-031.4");
        // A receipt without a rule failure has no amended structure and decodes as before.
        var accepted = new BindingReceiptV1(new byte[32], 1, true, null, "", List.of(new BindingReceiptV1.Step(0, 0,
                null, "a", new byte[32], List.of(), List.of(), new RuleTrace(1, null), "PLANNED", "", false)));
        assertThat(BindingReceiptV1.decode(accepted.encode()).encode()).isEqualTo(accepted.encode());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> mutable(Object value) { return new ArrayList<>((List<Object>) value); }

    /** Normalizes a test wire tree to decoded CBOR values (integers become {@code Long}). */
    private static Object wire(Object value) { return BindingCbor.decode(BindingCbor.encode(value), 4096); }
}
