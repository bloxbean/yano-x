package org.yanoproject.x.composite.contracts;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BindingWireTest {
    @Test
    void authoringDefaultsGrowWithoutRewritingExplicitOlderLimits() {
        var defaults = BindingIrV1.Limits.DEFAULT;
        assertThat(defaults.maxEventPayloadBytes()).isEqualTo(65536);
        assertThat(defaults.maxFunctionInputBytes()).isEqualTo(65536);
        assertThat(defaults.maxExpressionValueBytes()).isEqualTo(65536);
        assertThat(defaults.maxExpressionWorkPerCascade()).isEqualTo(1048576);
        assertThat(defaults.maxExpressionWorkPerBlock()).isEqualTo(33554432);
        var older = new BindingIrV1.Limits(8, 32, 4096, 4096, 2, 8, 4096,
                128, 16, 4096, 262144, 4194304);
        var document = new BindingIrV1(List.of(
                new BindingIrV1.Component("source", "test", "source.v1", Map.of(), 0)), List.of(), older);
        var decoded = BindingIrV1.decode(document.encode());
        assertThat(decoded.limits()).isEqualTo(older).isNotEqualTo(defaults);
        assertThat(decoded.encode()).containsExactly(document.encode());
    }

    @Test
    void scalarGoldenVectorsAndCanonicalMapOrdering() {
        assertThat(hex(BindingCbor.encode(Map.of("long", true, "a", 42L))))
                .isEqualTo("a26161182a646c6f6e67f5");
        assertThat(hex(BindingCbor.encode(Long.MIN_VALUE))).isEqualTo("3b7fffffffffffffff");
        assertThat(hex(BindingCbor.encode(Long.MAX_VALUE))).isEqualTo("1b7fffffffffffffff");
        assertThat(BindingCbor.decode(BindingCbor.encode(Long.MIN_VALUE), 9)).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void rejectsAlternateEncodingsTagsDuplicatesTrailingRootsAndOversizedIntegers() {
        for (String vector : List.of("1801", "9f01ff", "c001", "0101", "a2616101616102",
                "1b8000000000000000", "f90000", "a2646c6f6e67f56161182a")) {
            assertThatThrownBy(() -> BindingCbor.decode(HexFormat.of().parseHex(vector), 65536))
                    .as(vector).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void preflightRejectsDeepOrTruncatedInputsBeforeLibraryDecode() {
        byte[] deep = new byte[80];
        Arrays.fill(deep, (byte) 0x81);
        deep[79] = 0;
        assertThatThrownBy(() -> BindingCbor.decode(deep, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BindingCbor.decode(new byte[]{0x59, 0x7f, (byte) 0xff}, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentRoundTripsAndPinsCatalogsAndDeclarationOrder() {
        var source = new BindingIrV1.Component("source", "test", "source.v1", Map.of(), 0);
        var target = new BindingIrV1.Component("target", "test", "target.v1", Map.of(), 0);
        var binding = new BindingIrV1.Binding("forward", "source", "composite.command-accepted.v1", List.of(),
                new BindingIrV1.CommandTarget("target", "append", BindingIrV1.Mapping.raw("body")));
        var document = new BindingIrV1(List.of(source, target), List.of(binding), BindingIrV1.Limits.DEFAULT);
        assertThat(BindingIrV1.decode(document.encode()).encode()).containsExactly(document.encode());
        assertThat(new BindingIrV1(List.of(target, source), List.of(binding), BindingIrV1.Limits.DEFAULT).encode())
                .isNotEqualTo(document.encode());
        List<Object> root = new ArrayList<>((List<?>) BindingCbor.decode(document.encode(), 65536));
        root.set(1, "unrecognized-functions");
        assertThatThrownBy(() -> BindingIrV1.decode(BindingCbor.encode(root)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("binding catalog");
    }

    @Test
    void receiptGoldenHeaderAndDefensiveCopies() {
        byte[] source = new byte[32];
        var receipt = new BindingReceiptV1(source, 1, true, null, "", List.of());
        source[0] = 1;
        receipt.sourceMessageId()[0] = 2;
        String expected = "87015820" + "00".repeat(32) + "01684143434550544544f66080";
        assertThat(hex(receipt.encode())).isEqualTo(expected);
        assertThat(BindingReceiptV1.decode(receipt.encode()).encode()).containsExactly(receipt.encode());
    }

    @Test
    void receiptRejectsContradictoryOutcomeAndRoundTripsRejectedStep() {
        assertThatThrownBy(() -> new BindingReceiptV1(new byte[32], 1, true, 0, "FAIL", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var step = new BindingReceiptV1.Step(1, 1, "forward", "target", new byte[32], List.of(), List.of(),
                "REJECTED", "AUTHORIZATION", true);
        var receipt = new BindingReceiptV1(new byte[32], 1, false, 1, "AUTHORIZATION", List.of(step));
        assertThat(BindingReceiptV1.decode(receipt.encode()).steps().getFirst().rawBody()).isTrue();
        assertThat(BindingReceiptV1.decode(receipt.encode()).encode()).containsExactly(receipt.encode());
    }

    @Test
    void noResultEffectCannotSpecifyResultExpiry() {
        assertThatThrownBy(() -> new BindingIrV1.EffectTarget("test", "app-final", "none", 1,
                BindingIrV1.Mapping.identity())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void denseReceiptWithinByteCapRoundTripsDespiteMoreThanThirtyTwoThousandItems() {
        var events = java.util.Collections.nCopies(128, "e");
        var conditions = java.util.Collections.nCopies(256, new BindingReceiptV1.Condition("b", -1));
        var steps = new ArrayList<BindingReceiptV1.Step>();
        for (int i = 0; i < 42; i++) {
            steps.add(new BindingReceiptV1.Step(i, i == 0 ? 0 : 1, i == 0 ? null : "b", "c", new byte[32],
                    events, conditions, "PLANNED", "", false));
        }
        var receipt = new BindingReceiptV1(new byte[32], 1, true, null, "", steps);
        byte[] encoded = receipt.encode();
        assertThat(encoded.length).isLessThan(65536);
        assertThat(BindingReceiptV1.decode(encoded).encode()).containsExactly(encoded);
    }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
