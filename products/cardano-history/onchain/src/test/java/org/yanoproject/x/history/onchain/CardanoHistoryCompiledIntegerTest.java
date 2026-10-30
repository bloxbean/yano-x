package org.yanoproject.x.history.onchain;

import org.julclang.core.PlutusData;
import org.julclang.testkit.JulcEval;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evaluates the parameters validator's CBOR integer decoder as UPLC. Julc before 0.1.0-pre17 lowered
 * {@code cursor += 1} in its loops as {@code cursor = 1}, so an integer encoded in two or more bytes, such as most
 * protocol parameters, never finished decoding on chain.
 */
class CardanoHistoryCompiledIntegerTest {
    @Test
    void decodesMultiByteIntegersAndBignums() {
        BigInteger zero = BigInteger.ZERO;
        byte[] none = new byte[0];
        JulcEval eval = JulcEval.forClass(CardanoHistoryParametersValidator.class,
                Path.of("build/generated/julc-test-sources"),
                none, none, none, none, none, none, none, none, zero, zero, zero, zero, zero, none);
        // 0x1a 001e8480 = 2,000,000 (four-byte head); 0xc2 0x49 <9 bytes> = a bignum.
        assertThat(decoded(eval, new byte[] {0x1a, 0x00, 0x1e, (byte) 0x84, (byte) 0x80}))
                .isEqualTo(List.of(BigInteger.valueOf(2_000_000), BigInteger.valueOf(5)));
        assertThat(decoded(eval, new byte[] {0x19, 0x01, 0x31}))
                .isEqualTo(List.of(BigInteger.valueOf(305), BigInteger.valueOf(3)));
        byte[] bignum = {(byte) 0xc2, 0x49, 0x01, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThat(decoded(eval, bignum))
                .isEqualTo(List.of(BigInteger.ONE.shiftLeft(64), BigInteger.valueOf(11)));
    }

    private static List<BigInteger> decoded(JulcEval eval, byte[] encoded) {
        PlutusData result = eval.call("readUInt", encoded, 0L).asData();
        List<PlutusData> fields = ((PlutusData.ConstrData) result).fields();
        return fields.stream().map(field -> ((PlutusData.IntData) field).value()).toList();
    }
}
