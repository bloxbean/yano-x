package org.yanoproject.x.proofs.onchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.julclang.core.PlutusData;
import org.julclang.testkit.ContractTest;
import org.julclang.testkit.JulcEval;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.proofs.MpfNormalizedNonMembershipProof;
import org.yanoproject.x.proofs.MpfNormalizedProof;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evaluates the compiled MPF absence verifiers as UPLC. Julc before 0.1.0-pre17 discarded a {@code return false}
 * inside an {@code if/else} that had statements after it, so the conflicting-leaf checks of {@code verifyAbsence}
 * never ran: a present key's own leaf was accepted as the "conflicting" leaf that proves it absent.
 */
class CompiledAbsenceProofTest extends ContractTest {
    private static final List<Class<?>> VERIFIERS = List.of(
            MpfPairOnChainVerifier.class, AuthenticatedSnapshotOnChainVerifier.class);
    private static final Map<Class<?>, JulcEval> EVALUATORS = new HashMap<>();
    private static MpfTestTrie trie;

    @BeforeAll
    static void setUp() {
        initCrypto();
        trie = MpfTestTrie.of("member/", 48);
    }

    @Test
    void acceptsHonestAbsenceAtAConflictingLeafAndAtAnEmptySlot() {
        for (boolean conflicting : new boolean[] {true, false}) {
            MpfNormalizedNonMembershipProof absent = trie.firstAbsence("absent/", conflicting);
            for (Class<?> verifier : VERIFIERS) {
                assertThat(verifyAbsence(verifier, MpfTestTrie.absenceData(absent)))
                        .as("%s, conflicting leaf=%s", verifier.getSimpleName(), conflicting).isTrue();
            }
        }
    }

    @Test
    void rejectsAnAbsenceClaimForAPresentKey() {
        MpfNormalizedProof present = trie.inclusion(MpfTestTrie.key("member/", 7));
        PlutusData ownLeafAsConflict = MpfTestTrie.absenceData(present.key(), present.leafSuffix(),
                present.folds(), MpfTestTrie.leafCursor(present.leafSuffix()),
                Blake2bUtil.blake2bHash256(present.key()), Blake2bUtil.blake2bHash256(present.value()));
        for (Class<?> verifier : VERIFIERS) {
            assertThat(verifyAbsence(verifier, ownLeafAsConflict)).as(verifier.getSimpleName()).isFalse();
        }
    }

    @Test
    void acceptsEveryHonestAbsenceProofTheConverterProduces() {
        int conflicting = 0;
        int empty = 0;
        for (int i = 0; i < 24; i++) {
            MpfNormalizedNonMembershipProof absent;
            try {
                absent = trie.absence(MpfTestTrie.key("absent/", i));
            } catch (IllegalArgumentException unsupportedTerminal) {
                continue;
            }
            if (absent.conflictingLeaf()) conflicting++; else empty++;
            for (Class<?> verifier : VERIFIERS) {
                assertThat(verifyAbsence(verifier, trie.root(), MpfTestTrie.absenceData(absent)))
                        .as("%s, absent/%d", verifier.getSimpleName(), i).isTrue();
            }
        }
        assertThat(conflicting).isPositive();
        assertThat(empty).isPositive();
    }

    /**
     * The folds authenticate only the query's own path above the terminal cursor, and the leaf hash commits only to
     * the suffix below it. A conflicting key hash that agrees with a present key's leaf below the cursor but differs
     * above it names that very leaf, so it must not prove the key absent.
     */
    @Test
    void rejectsAPresentKeyWhoseConflictingKeyHashDiffersOnlyAboveTheCursor() {
        Set<Long> depths = new TreeSet<>();
        for (int i = 0; i < 48; i++) {
            depths.add(assertForgeriesRejected(trie, MpfTestTrie.key("member/", i)));
        }
        assertThat(depths).as("leaf depths covered").hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void rejectsTheSameForgeryForADeepLeaf() {
        MpfTestTrie deep = MpfTestTrie.of("deep/", 4096);
        long deepest = 0;
        byte[] deepestKey = null;
        for (int i = 0; i < 512; i++) {
            byte[] key = MpfTestTrie.key("deep/", i);
            long cursor = MpfTestTrie.leafCursor(deep.inclusion(key).leafSuffix());
            if (cursor > deepest) {
                deepest = cursor;
                deepestKey = key;
            }
        }
        assertThat(deepest).isGreaterThanOrEqualTo(4);
        assertForgeriesRejected(deep, deepestKey);
    }

    /** Flips each nibble above the present key's leaf cursor in turn; returns that cursor. */
    private static long assertForgeriesRejected(MpfTestTrie source, byte[] presentKey) {
        MpfNormalizedProof present = source.inclusion(presentKey);
        long cursor = MpfTestTrie.leafCursor(present.leafSuffix());
        byte[] keyHash = Blake2bUtil.blake2bHash256(present.key());
        byte[] valueHash = Blake2bUtil.blake2bHash256(present.value());
        assertThat(cursor).isPositive();
        for (long nibble = 0; nibble < cursor; nibble++) {
            PlutusData forged = MpfTestTrie.absenceData(present.key(), present.leafSuffix(), present.folds(),
                    cursor, MpfTestTrie.flipNibble(keyHash, nibble), valueHash);
            for (Class<?> verifier : VERIFIERS) {
                assertThat(verifyAbsence(verifier, source.root(), forged))
                        .as("%s, cursor %d, flipped nibble %d", verifier.getSimpleName(), cursor, nibble)
                        .isFalse();
            }
        }
        return cursor;
    }

    @Test
    void rejectsAnEmptySlotAbsenceThatAlsoNamesAConflictingLeaf() {
        MpfNormalizedNonMembershipProof absent = trie.firstAbsence("absent/", false);
        byte[] junk = new byte[32];
        Arrays.fill(junk, (byte) 0x77);
        PlutusData withConflict = MpfTestTrie.absenceData(absent.key(), new byte[0], absent.folds(),
                absent.terminalCursor(), junk, junk);
        for (Class<?> verifier : VERIFIERS) {
            assertThat(verifyAbsence(verifier, withConflict)).as(verifier.getSimpleName()).isFalse();
        }
    }

    private static boolean verifyAbsence(Class<?> verifier, PlutusData proof) {
        return verifyAbsence(verifier, trie.root(), proof);
    }

    private static boolean verifyAbsence(Class<?> verifier, byte[] root, PlutusData proof) {
        return EVALUATORS.computeIfAbsent(verifier, CompiledLoopArithmeticTest::evaluator)
                .call("verifyAbsence", proof, root).asBoolean();
    }
}
