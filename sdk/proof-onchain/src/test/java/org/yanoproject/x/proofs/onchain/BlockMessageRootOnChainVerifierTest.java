package org.yanoproject.x.proofs.onchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.testkit.ContractTest;
import org.julclang.testkit.JulcEval;
import org.yanoproject.api.appchain.transition.FinalizedBlockMessageRootIndex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlockMessageRootOnChainVerifierTest extends ContractTest {
    @BeforeAll
    static void crypto() {
        initCrypto();
    }

    @Test
    void verifiesNestedSingleMessageVectorAndRejectsEveryBindingMutation() {
        byte[] messageId = filled(7);
        byte[] key = FinalizedBlockMessageRootIndex.blockKey(9);
        byte[] value = new FinalizedBlockMessageRootIndex.BlockRecord(
                9, messageId, 1).canonicalBytes();
        byte[] path = nibbles(Blake2bUtil.blake2bHash256(key));
        byte[] suffix = encodeLeafSuffix(path);
        byte[] root = Blake2bUtil.blake2bHash256(concat(
                suffix, Blake2bUtil.blake2bHash256(value)));
        var proof = new MpfOnChainVerifier.Proof(key, value, suffix, JulcList.empty());
        var claim = new BlockMessageRootOnChainVerifier.Claim(proof, key,
                BigInteger.valueOf(9), messageId, BigInteger.ZERO, BigInteger.ONE,
                JulcList.empty());

        assertThat(BlockMessageRootOnChainVerifier.verifyAtRoot(claim, root)).isTrue();
        assertThat(BlockMessageRootOnChainVerifier.verifyAtRoot(claim, filled(8))).isFalse();
        assertThat(BlockMessageRootOnChainVerifier.verifyAtRoot(new
                BlockMessageRootOnChainVerifier.Claim(proof, filled(8), BigInteger.valueOf(9),
                messageId, BigInteger.ZERO, BigInteger.ONE, JulcList.empty()), root)).isFalse();
        assertThat(BlockMessageRootOnChainVerifier.verifyAtRoot(new
                BlockMessageRootOnChainVerifier.Claim(proof, key, BigInteger.valueOf(10),
                messageId, BigInteger.ZERO, BigInteger.ONE, JulcList.empty()), root)).isFalse();
        assertThat(BlockMessageRootOnChainVerifier.verifyAtRoot(new
                BlockMessageRootOnChainVerifier.Claim(proof, key, BigInteger.valueOf(9),
                filled(6), BigInteger.ZERO, BigInteger.ONE, JulcList.empty()), root)).isFalse();
    }

    /**
     * Julc 0.1.0-pre16 lowered {@code index /= 2} and {@code result += 1} in the compiled loops as plain assignments,
     * so the script rejected every block of three or more messages although the Java method accepted them.
     */
    @Test
    void compiledVerifierAcceptsEveryMessageAndRejectsASwappedIndex() {
        JulcEval eval = JulcEval.forClass(BlockMessageRootOnChainVerifier.class);
        for (int count = 2; count <= 5; count++) {
            for (int index = 0; index < count; index++) {
                assertThat(verifyCompiled(eval, count, index, index))
                        .as("count %d, index %d", count, index).isTrue();
            }
        }
        for (int real = 0; real < 4; real++) {
            for (int claimed = 0; claimed < 4; claimed++) {
                if (claimed != real) {
                    assertThat(verifyCompiled(eval, 4, real, claimed))
                            .as("message %d claimed at index %d", real, claimed).isFalse();
                }
            }
        }
    }

    @Test
    void referenceValidatorCompilesToTheOnChainTarget() {
        assertThat(compileValidator(BlockMessageRootAnchorValidator.class).program()).isNotNull();
    }

    /** Prove message {@code real} of {@code count} with its own siblings, but claim it at {@code claimed}. */
    private static boolean verifyCompiled(JulcEval eval, int count, int real, int claimed) {
        List<byte[]> level = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            level.add(filled(0x10 + i));
        }
        byte[] messageId = level.get(real);
        List<PlutusData> siblings = new ArrayList<>();
        for (int index = real; level.size() > 1; index /= 2) {
            int width = level.size();
            siblings.add(PlutusData.bytes(level.get(index % 2 == 1 ? index - 1 : Math.min(index + 1, width - 1))));
            List<byte[]> parents = new ArrayList<>();
            for (int i = 0; i < width; i += 2) {
                parents.add(Blake2bUtil.blake2bHash256(concat(level.get(i), level.get(Math.min(i + 1, width - 1)))));
            }
            level = parents;
        }
        byte[] key = FinalizedBlockMessageRootIndex.blockKey(9);
        byte[] value = new FinalizedBlockMessageRootIndex.BlockRecord(9, level.get(0), count).canonicalBytes();
        byte[] suffix = encodeLeafSuffix(nibbles(Blake2bUtil.blake2bHash256(key)));
        byte[] root = Blake2bUtil.blake2bHash256(concat(suffix, Blake2bUtil.blake2bHash256(value)));
        PlutusData proof = PlutusData.constr(0, PlutusData.bytes(key), PlutusData.bytes(value),
                PlutusData.bytes(suffix), PlutusData.list());
        PlutusData claim = PlutusData.constr(0, proof, PlutusData.bytes(key), PlutusData.integer(9),
                PlutusData.bytes(messageId), PlutusData.integer(claimed), PlutusData.integer(count),
                PlutusData.list(siblings.toArray(PlutusData[]::new)));
        return eval.call("verifyAtRoot", claim, root).asBoolean();
    }

    private static byte[] nibbles(byte[] path) {
        byte[] result = new byte[path.length * 2];
        for (int index = 0; index < path.length; index++) {
            result[index * 2] = (byte) ((path[index] >>> 4) & 15);
            result[index * 2 + 1] = (byte) (path[index] & 15);
        }
        return result;
    }
    private static byte[] encodeLeafSuffix(byte[] suffix) {
        byte[] encoded = new byte[1 + suffix.length / 2];
        encoded[0] = (byte) 0xff;
        for (int index = 0; index < suffix.length; index += 2) {
            encoded[1 + index / 2] = (byte) ((suffix[index] << 4) | suffix[index + 1]);
        }
        return encoded;
    }
    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }
    private static byte[] filled(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
