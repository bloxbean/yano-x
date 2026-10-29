package org.yanoproject.x.eutxo.bridge.onchain;

import org.julclang.testkit.JulcEval;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evaluates the vault's MPF prefix check as UPLC. Julc before 0.1.0-pre17 lowered {@code index += 1} in its loop as
 * {@code index = 1}, so a proof step with a prefix of two or more nibbles never terminated on chain.
 */
class ProofVaultCompiledPrefixTest {
    @Test
    void prefixCheckWalksEveryNibbleOfALongPrefix() {
        byte[] none = new byte[0];
        JulcEval eval = JulcEval.forClass(ProofVaultValidator.class, Path.of("src/main/java"),
                none, none, none, none, none, none, none, none);
        byte[] pathHash = new byte[32];
        Arrays.fill(pathHash, (byte) 0x5a);
        // Nibbles from cursor 1: a 5 a 5 ...
        assertThat(eval.call("validPrefix", pathHash, 1L, new byte[] {0x0a, 0x05, 0x0a, 0x05}).asBoolean()).isTrue();
        assertThat(eval.call("validPrefix", pathHash, 1L, new byte[] {0x0a, 0x05, 0x0a, 0x04}).asBoolean()).isFalse();
    }
}
