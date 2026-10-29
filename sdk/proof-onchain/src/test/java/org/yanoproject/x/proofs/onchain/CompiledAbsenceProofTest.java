package org.yanoproject.x.proofs.onchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.julclang.core.PlutusData;
import org.julclang.testkit.ContractTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.proofs.MpfNormalizedNonMembershipProof;
import org.yanoproject.x.proofs.MpfNormalizedProof;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evaluates the compiled MPF absence verifiers as UPLC. Julc before 0.1.0-pre17 discarded a {@code return false}
 * inside an {@code if/else} that had statements after it, so the conflicting-leaf checks of {@code verifyAbsence}
 * never ran: a present key's own leaf was accepted as the "conflicting" leaf that proves it absent.
 */
class CompiledAbsenceProofTest extends ContractTest {
    private static final List<Class<?>> VERIFIERS = List.of(
            MpfPairOnChainVerifier.class, AuthenticatedSnapshotOnChainVerifier.class);
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
        return CompiledLoopArithmeticTest.evaluator(verifier).call("verifyAbsence", proof, trie.root()).asBoolean();
    }
}
