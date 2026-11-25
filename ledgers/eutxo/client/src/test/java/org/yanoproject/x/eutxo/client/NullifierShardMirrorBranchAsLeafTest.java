package org.yanoproject.x.eutxo.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nullifier mirror's absence check goes through {@code ProofVerifier.verifyExclusion}: a real branch on a settled
 * id's path, presented as the terminal conflicting leaf, does not make that id look unsettled.
 */
class NullifierShardMirrorBranchAsLeafTest {
    @Test
    void aSettledIdIsNeverShownAbsentByABranchReadAsALeaf() {
        for (int cursor : new int[] {1, 3, 5, 9, 13}) {
            WireBranchAsLeafForgery forgery = WireBranchAsLeafForgery.at(cursor);
            assertThat(NullifierShardMirror.verifyMembership(forgery.root, forgery.key, forgery.inclusionWire))
                    .as("cursor %d: settled", cursor).isTrue();
            assertThat(NullifierShardMirror.verifyAbsence(forgery.root, forgery.key, forgery.forgedExclusionWire))
                    .as("cursor %d: forged absence", cursor).isFalse();
        }
    }
}
