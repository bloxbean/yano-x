package org.yanoproject.x.proofs.onchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.julclang.core.PlutusData;
import org.julclang.testkit.ContractTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.proofs.MpfNormalizedNonMembershipProof;
import org.yanoproject.x.proofs.MpfNormalizedProof;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evaluates the compiled MPF absence verifiers as UPLC. Julc before 0.1.0-pre17 discarded a {@code return false}
 * inside an {@code if/else} that had statements after it, so the conflicting-leaf checks of {@code verifyAbsence}
 * never ran: a present key's own leaf was accepted as the "conflicting" leaf that proves it absent. The public
 * {@code MpfOnChainVerifier.verifyAbsence} (ADR-059 framework addition 9) is the same check, lifted, and runs the same
 * cases. It also authenticates the key's own suffix, not the conflicting key hash's prefix, and accepts a third
 * terminal (a branch whose prefix leaves the path); those cases run against it alone.
 */
class CompiledAbsenceProofTest extends ContractTest {
    private static final List<Class<?>> VERIFIERS = List.of(
            MpfOnChainVerifier.class, MpfPairOnChainVerifier.class, AuthenticatedSnapshotOnChainVerifier.class);
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

    @Test
    void everyAbsentKeyIsProvableOnChainWhateverItsTerminal() {
        Map<MpfTestTrie.Terminal, Integer> seen = new EnumMap<>(MpfTestTrie.Terminal.class);
        for (int i = 0; i < 400; i++) {
            MpfNormalizedNonMembershipProof absent = trie.absence(MpfTestTrie.key("random/", i));
            assertThat(verifyAbsence(MpfOnChainVerifier.class, MpfTestTrie.absenceData(absent)))
                    .as("random/%d (%s)", i, MpfTestTrie.terminal(absent)).isTrue();
            seen.merge(MpfTestTrie.terminal(absent), 1, Integer::sum);
        }
        assertThat(seen).containsOnlyKeys(MpfTestTrie.Terminal.values());
    }

    /**
     * The conflicting key hash is free redeemer data above the terminal: only its suffix below {@code terminalCursor}
     * is authenticated. A hash that differs from the present key's own hash in one nibble above the leaf must not make
     * the key's own leaf a "conflicting" one, at any depth.
     */
    @Test
    void aFlippedPrefixNibbleInTheConflictingKeyHashNeverProvesAPresentKeyAbsent() {
        for (int member = 0; member < 48; member += 5) {
            MpfNormalizedProof present = trie.inclusion(MpfTestTrie.key("member/", member));
            long cursor = MpfTestTrie.leafCursor(present.leafSuffix());
            byte[] keyHash = Blake2bUtil.blake2bHash256(present.key());
            for (long flip : new long[] {0, cursor / 2, cursor - 1}) {
                byte[] forged = keyHash.clone();
                forged[(int) flip / 2] ^= (byte) (flip % 2 == 0 ? 0x10 : 0x01);
                PlutusData claim = MpfTestTrie.absenceData(present.key(), present.leafSuffix(), present.folds(),
                        cursor, forged, Blake2bUtil.blake2bHash256(present.value()));
                assertThat(verifyAbsence(MpfOnChainVerifier.class, claim))
                        .as("member/%d, flipped nibble %d of %d", member, flip, cursor).isFalse();
            }
        }
    }

    @Test
    void aConflictingBranchMustBeAuthenticWellFormedAndLeaveThePath() {
        MpfNormalizedNonMembershipProof honest = trie.firstAbsence("absent/", MpfTestTrie.Terminal.CONFLICTING_BRANCH);
        assertThat(verifyAbsence(MpfOnChainVerifier.class, MpfTestTrie.absenceData(honest))).isTrue();
        byte[] prefix = honest.conflictingLeafSuffix();
        long cursor = honest.terminalCursor();

        byte[] altered = prefix.clone();
        altered[altered.length - 1] = (byte) ((altered[altered.length - 1] + 1) % 16);
        assertThat(verifyAbsence(MpfOnChainVerifier.class, MpfTestTrie.absenceData(honest.key(), altered,
                honest.folds(), cursor, new byte[0], honest.conflictingValueHash()))).isFalse();

        byte[] notANibble = prefix.clone();
        notANibble[0] = (byte) (notANibble[0] + 16);
        assertThat(verifyAbsence(MpfOnChainVerifier.class, MpfTestTrie.absenceData(honest.key(), notANibble,
                honest.folds(), cursor, new byte[0], honest.conflictingValueHash()))).isFalse();

        byte[] tooLong = Arrays.copyOf(prefix, (int) (MpfNormalizedProof.PATH_NIBBLES - cursor));
        assertThat(verifyAbsence(MpfOnChainVerifier.class, MpfTestTrie.absenceData(honest.key(), tooLong,
                honest.folds(), cursor, new byte[0], honest.conflictingValueHash()))).isFalse();
    }

    /**
     * A two-key trie whose root is a branch with a non-empty prefix: a present key's own root branch, read as a
     * conflicting branch, is authentic but does not leave its path, so it proves nothing.
     */
    @Test
    void aPresentKeysOwnBranchIsNotAConflictingBranch() {
        List<byte[]> keys = sharedFirstNibble();
        MpfTestTrie pair = MpfTestTrie.ofKeys(keys);
        MpfNormalizedProof present = pair.inclusion(keys.get(0));
        MpfNormalizedProof.FoldStep top = present.folds().getLast();
        assertThat(top.cursor()).isZero();
        assertThat(top.prefix()).isNotEmpty();
        byte[] merkle = MpfTestTrie.branchMerkle(present, present.folds().size() - 1);
        assertThat(MpfTestTrie.hash(top.prefix(), merkle)).isEqualTo(pair.root());
        PlutusData ownBranch = MpfTestTrie.absenceData(present.key(), top.prefix(), List.of(), 0, new byte[0],
                merkle);
        assertThat(CompiledLoopArithmeticTest.evaluator(MpfOnChainVerifier.class)
                .call("verifyAbsence", ownBranch, pair.root()).asBoolean()).isFalse();
        // A key that leaves the root prefix is proved absent by that same branch.
        for (int i = 0; ; i++) {
            MpfNormalizedNonMembershipProof absent = pair.absence(MpfTestTrie.key("outside/", i));
            if (absent.conflictingBranch()) {
                assertThat(absent.terminalCursor()).isZero();
                assertThat(CompiledLoopArithmeticTest.evaluator(MpfOnChainVerifier.class)
                        .call("verifyAbsence", MpfTestTrie.absenceData(absent), pair.root()).asBoolean()).isTrue();
                break;
            }
        }
    }

    private static List<byte[]> sharedFirstNibble() {
        List<byte[]> keys = new ArrayList<>();
        byte[] first = MpfTestTrie.key("pair/", 0);
        keys.add(first);
        int nibble = (Blake2bUtil.blake2bHash256(first)[0] & 0xff) >> 4;
        for (int i = 1; ; i++) {
            byte[] candidate = MpfTestTrie.key("pair/", i);
            if ((Blake2bUtil.blake2bHash256(candidate)[0] & 0xff) >> 4 == nibble) {
                keys.add(candidate);
                return keys;
            }
        }
    }

    private static boolean verifyAbsence(Class<?> verifier, PlutusData proof) {
        return CompiledLoopArithmeticTest.evaluator(verifier).call("verifyAbsence", proof, trie.root()).asBoolean();
    }
}
