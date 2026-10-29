package org.yanoproject.x.proofs.onchain;

import org.julclang.stdlib.annotation.Param;
import org.julclang.testkit.JulcEval;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evaluates the compiled loop helpers as UPLC, not as Java. Julc before 0.1.0-pre17 lowered a compound assignment in
 * a loop body from its right-hand side alone ({@code result += 1} became {@code result = 1}), so these helpers
 * compiled to programs that rejected valid proofs or never terminated while the Java tests still passed.
 */
class CompiledLoopArithmeticTest {
    private static final List<Class<?>> PREFIX_VERIFIERS = List.of(
            MpfOnChainVerifier.class, MpfPairOnChainVerifier.class, AuthenticatedSnapshotOnChainVerifier.class);

    @Test
    void blockMessagePathLengthCountsEveryLevel() {
        JulcEval eval = JulcEval.forClass(BlockMessageRootOnChainVerifier.class);
        for (long count = 1; count <= 9; count++) {
            long expected = 0;
            for (long width = count; width > 1; width = (width + 1) / 2) {
                expected++;
            }
            assertThat(eval.call("pathLength", count).asLong()).as("pathLength(%d)", count).isEqualTo(expected);
        }
    }

    @Test
    void mpfPrefixCheckWalksEveryNibbleOfALongPrefix() {
        byte[] pathHash = new byte[32];
        Arrays.fill(pathHash, (byte) 0x5a);
        // Nibbles from cursor 1: a 5 a 5 ...
        byte[] matching = {0x0a, 0x05, 0x0a, 0x05};
        byte[] lastDiffers = {0x0a, 0x05, 0x0a, 0x04};
        for (Class<?> verifier : PREFIX_VERIFIERS) {
            JulcEval eval = evaluator(verifier);
            assertThat(eval.call("validPrefix", pathHash, 1L, matching).asBoolean())
                    .as("%s accepts a matching 4-nibble prefix", verifier.getSimpleName()).isTrue();
            assertThat(eval.call("validPrefix", pathHash, 1L, lastDiffers).asBoolean())
                    .as("%s rejects a prefix whose last nibble differs", verifier.getSimpleName()).isFalse();
        }
    }

    /** A validator's helpers are compiled under its {@code @Param} lambdas, so apply placeholder values first. */
    static JulcEval evaluator(Class<?> type) {
        List<Object> params = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.isAnnotationPresent(Param.class)) {
                params.add(field.getType() == BigInteger.class ? BigInteger.ZERO : new byte[0]);
            }
        }
        return params.isEmpty() ? JulcEval.forClass(type) : JulcEval.forClass(type, params.toArray());
    }
}
