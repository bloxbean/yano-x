package org.yanoproject.x.walletauth;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V3 cryptographic and policy oracle: every vector was produced by an independent Python
 * implementation (scripts/generate-cose-vectors.py, using the `cryptography` package and its own
 * CBOR encoder). The Java verifier must reach the same accept/reject verdict for each.
 */
class CoseOracleVectorTest {
    @Test
    void javaVerdictsMatchTheIndependentPythonOracle() throws Exception {
        List<String> lines;
        try (var in = getClass().getResourceAsStream("/walletauth/cose-oracle-vectors.txt")) {
            assertThat(in).isNotNull();
            lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        }
        assertThat(lines).hasSizeGreaterThanOrEqualTo(19);
        HexFormat hex = HexFormat.of();
        int accepted = 0;
        for (String line : lines) {
            String[] f = line.split("\\|", -1);
            byte[] sign1 = hex.parseHex(f[2]);
            byte[] key = hex.parseHex(f[3]);
            if (f[1].equals("accept")) {
                CoseSign1Verifier.Verified verified = CoseSign1Verifier.verify(sign1, key);
                assertThat(verified.credential().kind().name()).as(f[0]).isEqualTo(f[4]);
                assertThat(hex.formatHex(verified.credential().keyHash())).as(f[0]).isEqualTo(f[5]);
                accepted++;
            } else {
                assertThatThrownBy(() -> CoseSign1Verifier.verify(sign1, key))
                        .as(f[0]).isInstanceOf(WalletAuthException.class);
            }
        }
        assertThat(accepted).isEqualTo(7);
    }
}
