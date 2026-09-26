package org.yanoproject.x.composite.bindings;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.util.HexFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pinned semantic vectors exercise every v1 stock function through the actual mapping evaluator. */
class BindingFunctionsTest {
    @Test
    void hashAndEncodingVectorsArePinned() {
        assertThat((byte[]) evaluate("sha-256", "abc")).isEqualTo(hex(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        assertThat((byte[]) evaluate("blake2b-256", "abc")).isEqualTo(hex(
                "bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319"));
        assertThat(evaluate("byte-length", "é😀")).isEqualTo(6L);
        assertThat(evaluate("hex", hex("00ff"))).isEqualTo("00ff");
        assertThat((byte[]) evaluate("utf8-bytes", "é😀")).isEqualTo(hex("c3a9f09f9880"));
        assertThat((byte[]) evaluate("cbor-encode", 24L)).isEqualTo(hex("1818"));
        assertThat((byte[]) evaluate("cbor-encode", -25L)).isEqualTo(hex("3818"));
        assertThat((byte[]) evaluate("cbor-encode", true)).isEqualTo(hex("f5"));
        assertThat((byte[]) evaluate("cbor-encode", "é")).isEqualTo(hex("62c3a9"));
    }

    @Test
    void concatAndScalarFieldExtractionKeepTheirExactTypes() {
        assertThat(evaluate("concat", "a", "é", "😀")).isEqualTo("aé😀");
        assertThat((byte[]) evaluate("concat", hex("00"), hex("ff00"))).isEqualTo(hex("00ff00"));
        byte[] fields = TransitionScalars.encode(Map.of("n", -2L, "b", true, "s", "é", "raw", hex("ff")));
        assertThat(evaluate("cbor-field", fields, "n")).isEqualTo(-2L);
        assertThat(evaluate("cbor-field", fields, "b")).isEqualTo(true);
        assertThat(evaluate("cbor-field", fields, "s")).isEqualTo("é");
        assertThat((byte[]) evaluate("cbor-field", fields, "raw")).isEqualTo(hex("ff"));
        assertThatThrownBy(() -> evaluate("cbor-field", fields, "missing"))
                .isInstanceOf(BindingFailure.class).hasMessage("FUNCTION_MISSING_FIELD");
        assertThatThrownBy(() -> evaluate("cbor-field", hex("ff"), "n"))
                .isInstanceOf(BindingFailure.class).hasMessage("FUNCTION_INVALID_CBOR");
    }

    private static Object evaluate(String function, Object... arguments) {
        var sources = Arrays.stream(arguments)
                .map(value -> (BindingSourceV1) new BindingSourceV1.Literal(value)).toList();
        var mapping = BindingIrV1.Mapping.fields(List.of(new BindingIrV1.Assignment("value",
                new BindingSourceV1.Function(function, sources))));
        var binding = new BindingIrV1.Binding("vector", "log", BindingProgram.BASELINE, List.of(),
                new BindingIrV1.EffectTarget("vector", "app-final", "none", 0, mapping));
        var ir = new BindingIrV1(List.of(new BindingIrV1.Component("log", "ordered-log", "log.v1", Map.of(), 0)),
                List.of(binding), BindingIrV1.Limits.DEFAULT);
        var program = new BindingProgram(ir, Map.of("log", new OrderedLogKernel()));
        byte[] result = program.payload(binding, Map.of(), new byte[0],
                new BindingExpressionEvaluator.Budget(1_000_000),
                new BindingExpressionEvaluator.Budget(1_000_000));
        return ((Map<?, ?>) BindingCbor.decode(result, 65_536)).get("value");
    }

    private static byte[] hex(String value) { return HexFormat.of().parseHex(value); }
}
