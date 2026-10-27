package org.yanoproject.x.stdlib.contracts;

import org.yanoproject.api.appchain.schema.CborSchema;
import org.junit.jupiter.api.Test;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedMapSchemaTest {

    @Test
    void canonicalIrRoundTripsAndCanonicalizesUnorderedNodes() {
        CborSchema.Node integer = new CborSchema.IntegerNode(
                CborSchema.INTEGER_UINT,
                BigInteger.ONE, BigInteger.valueOf(100));
        CborSchema.Node text = new CborSchema.TextNode(1, 8, null);

        byte[] first = CborSchema.of(new CborSchema.ChoiceNode(
                List.of(text, integer))).definition();
        byte[] second = CborSchema.of(new CborSchema.ChoiceNode(
                List.of(integer, text))).definition();

        assertThat(first).isEqualTo(second);
        assertThat(CborSchema.decode(first).definition()).isEqualTo(first);

        CborSchema.MapNode unordered = new CborSchema.MapNode(List.of(
                new CborSchema.MapField("sku", true, text),
                new CborSchema.MapField("qty", true, integer)));
        CborSchema.MapNode reversed = new CborSchema.MapNode(List.of(
                new CborSchema.MapField("qty", true, integer),
                new CborSchema.MapField("sku", true, text)));
        assertThat(CborSchema.of(unordered).definition())
                .isEqualTo(CborSchema.of(reversed).definition());
    }

    @Test
    void evaluatesExactMapsBoundsChoicesAndOptionalFields() {
        CborSchema.Schema schema = productSchema();

        assertThat(schema.accepts(hex("a2637174790563736b756141"))).isTrue();
        assertThat(schema.accepts(hex(
                "a363717479186463736b75614264746167738261616162"))).isTrue();
        assertThat(schema.accepts(hex("a163736b756141"))).isFalse();
        assertThat(schema.accepts(hex("a3617801637174790563736b756141"))).isFalse();
        assertThat(schema.accepts(hex("a263717479186563736b756141"))).isFalse();
        assertThat(schema.accepts(hex("a2637174790563736b7569414243444546474849")))
                .isFalse();
    }

    @Test
    void boundedArrayMatchingHandlesOptionalAndRepeatedTerms() {
        CborSchema.Schema schema = CborSchema.of(
                new CborSchema.ArrayNode(List.of(
                        new CborSchema.Occurrence(
                                0, 1, CborSchema.IntegerNode.uint()),
                        CborSchema.Occurrence.required(
                                new CborSchema.TextNode(1, 4, null)))));

        assertThat(schema.accepts(hex("816178"))).isTrue();
        assertThat(schema.accepts(hex("82016178"))).isTrue();
        assertThat(schema.accepts(hex("8301026178"))).isFalse();
    }

    @Test
    void supportsTheCompleteNativeCborIntegerDomain() {
        CborSchema.Schema integers = CborSchema.of(
                CborSchema.IntegerNode.integer());

        assertThat(integers.accepts(hex("1bffffffffffffffff"))).isTrue();
        assertThat(integers.accepts(hex("3bffffffffffffffff"))).isTrue();
        assertThat(integers.accepts(hex("00"))).isTrue();
    }

    @Test
    void malformedIrAndValuesFailClosed() {
        CborSchema.Schema schema = productSchema();
        byte[] trailing = java.util.Arrays.copyOf(schema.definition(),
                schema.definition().length + 1);

        assertThatThrownBy(() -> CborSchema.decode(trailing))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(schema.accepts(hex("bf63736b756141ff"))).isFalse();
        assertThat(schema.accepts(hex("a263736b756141637174791805"))).isFalse();

        Random random = new Random(25_001L);
        for (int index = 0; index < 2_000; index++) {
            byte[] input = new byte[random.nextInt(64)];
            random.nextBytes(input);
            assertThatCodeDoesNotEscape(schema, input);
        }
    }

    @Test
    void rejectsDuplicateChoiceAndMapDefinitions() {
        CborSchema.TextNode text = CborSchema.TextNode.any();
        assertThatThrownBy(() -> CborSchema.of(
                new CborSchema.ChoiceNode(List.of(text, text))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new CborSchema.MapNode(List.of(
                new CborSchema.MapField("x", true, text),
                new CborSchema.MapField("x", false, text))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void structuralAndOccurrenceBoundsFailBeforeRuntimeEvaluation() {
        CborSchema.Node nested = new CborSchema.NullNode();
        for (int depth = 0; depth < CborSchema.MAX_SCHEMA_DEPTH; depth++) {
            nested = new CborSchema.ArrayNode(List.of(
                    CborSchema.Occurrence.required(nested)));
        }
        CborSchema.Node tooDeep = nested;
        assertThatThrownBy(() -> CborSchema.of(tooDeep))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("depth");

        List<CborSchema.Node> choices = java.util.stream.IntStream
                .range(0, CborSchema.MAX_CHOICE_OPTIONS + 1)
                .mapToObj(index -> (CborSchema.Node)
                        CborSchema.TextNode.literal("choice-" + index))
                .toList();
        assertThatThrownBy(() -> new CborSchema.ChoiceNode(choices))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CborSchema.Occurrence(
                0, CborSchema.MAX_OCCURRENCE + 1,
                CborSchema.TextNode.any()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsRepeatedLargeLiteralChoicesWithTheCoreWorkBudget() throws Exception {
        // Yano PR #197 charges bytes compared by each literal check, rather than just schema nodes.
        // The prerelease X evaluator accepted this value, so adopting API level 13 is a fresh-chain
        // qualification boundary, not a claim of replay compatibility for every historical schema value.
        List<CborSchema.Node> choices = new ArrayList<>();
        byte[] literal = new byte[2_000];
        Arrays.fill(literal, (byte) 120);
        for (int index = 0; index < 32; index++) {
            literal[literal.length - 1] = (byte) index;
            choices.add(CborSchema.BytesNode.literal(literal));
        }
        CborSchema.Schema schema = CborSchema.of(new CborSchema.ArrayNode(List.of(
                new CborSchema.Occurrence(500, 500, new CborSchema.ChoiceNode(choices)))));
        Array value = new Array();
        for (int index = 0; index < 500; index++) {
            value.add(new ByteString(literal.clone()));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new CborEncoder(bytes).encode(value);
        assertThat(schema.definition()).hasSize(64_400);
        assertThat(bytes.size()).isEqualTo(1_001_503);
        assertThat(schema.accepts(bytes.toByteArray())).isFalse();
    }

    private static CborSchema.Schema productSchema() {
        CborSchema.ArrayNode tags = new CborSchema.ArrayNode(
                List.of(new CborSchema.Occurrence(0, 3,
                        new CborSchema.TextNode(1, 4, null))));
        return CborSchema.of(new CborSchema.MapNode(List.of(
                new CborSchema.MapField("sku", true,
                        new CborSchema.TextNode(1, 8, null)),
                new CborSchema.MapField("qty", true,
                        new CborSchema.IntegerNode(
                                CborSchema.INTEGER_UINT,
                                BigInteger.ONE, BigInteger.valueOf(100))),
                new CborSchema.MapField("tags", false, tags))));
    }

    private static void assertThatCodeDoesNotEscape(
            CborSchema.Schema schema,
            byte[] input
    ) {
        try {
            schema.accepts(input);
        } catch (RuntimeException | StackOverflowError escaped) {
            throw new AssertionError("schema evaluator escaped for bounded input", escaped);
        }
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
