package org.yanoproject.x.composite.contracts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins independently published wire examples to the production codecs, not a second Java encoder. */
class BindingPublishedVectorsTest {
    private static final String ROOT = "/cddl/declarative-bindings-v1";

    @Test
    void publishedVectorsMatchConstructedDocumentsAndRoundTripWithoutNormalization() throws IOException {
        Properties vectors = new Properties();
        try (var input = getClass().getResourceAsStream(ROOT + "-golden-vectors.properties")) {
            assertThat(input).isNotNull();
            vectors.load(input);
        }
        var expression = new BindingExpressionV1(BindingExpressionV1.Type.BOOLEAN,
                new BindingExpressionV1.Call("ge", List.of(new BindingExpressionV1.Field("amount"),
                        new BindingExpressionV1.Literal(10L))));
        var document = new BindingIrV1(List.of(
                new BindingIrV1.Component("source", "test", "source.v1", Map.of(), 0),
                new BindingIrV1.Component("target", "test", "target.v1", Map.of(), 0)),
                List.of(new BindingIrV1.Binding("forward", "source", "composite.command-accepted.v1", List.of(),
                        new BindingIrV1.CommandTarget("target", "append", BindingIrV1.Mapping.raw("body")))),
                // Published wire examples retain their explicitly encoded original limits across default changes.
                new BindingIrV1.Limits(8, 32, 4096, 4096, 2, 8, 4096, 128, 16, 4096, 262144, 4194304));
        var accepted = new BindingReceiptV1(new byte[32], 1, true, null, "", List.of());
        var rejected = new BindingReceiptV1(new byte[32], 1, false, 1, "AUTHORIZATION", List.of(
                new BindingReceiptV1.Step(1, 1, "forward", "target", new byte[32], List.of(), List.of(),
                        "REJECTED", "AUTHORIZATION", true)));
        Map<String, byte[]> encoded = Map.of("expression.threshold", BindingCbor.encode(expression.wire()),
                "ir.forward", document.encode(), "receipt.accepted", accepted.encode(),
                "receipt.rejected", rejected.encode());
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
            assertThat(decoded).containsExactly(frozen);
        }
    }
}
