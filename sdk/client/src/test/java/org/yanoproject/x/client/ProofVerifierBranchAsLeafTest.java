package org.yanoproject.x.client;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.framework.proof.AppChainProofVerifier;
import org.yanoproject.x.framework.proof.MpfProofWirePreflight;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire path: a real branch on a present key's path, presented as the terminal conflicting {@code Leaf} of an
 * exclusion proof, must never let the same root prove the key
 * both present and absent. {@link ProofVerifier#verifyExclusion} and {@link AppChainProofVerifier} reject it through
 * {@link MpfProofWirePreflight#acceptsExclusion}; honest exclusion proofs still verify.
 */
class ProofVerifierBranchAsLeafTest {
    private static final int[] ODD_CURSORS = {1, 3, 5, 9, 13};

    @Test
    void aBranchReadAsTheConflictingLeafNeverProvesAPresentKeyAbsent() {
        for (int cursor : ODD_CURSORS) {
            WireBranchAsLeafForgery forgery = WireBranchAsLeafForgery.at(cursor);
            assertThat(ProofVerifier.verifyInclusion(forgery.root, forgery.key, forgery.key, forgery.inclusionWire))
                    .as("cursor %d: the key is present", cursor).isTrue();
            assertThat(MpfProofWirePreflight.accepts(forgery.forgedExclusionWire)).isTrue();
            assertThat(MpfProofWirePreflight.acceptsExclusion(forgery.forgedExclusionWire)).isFalse();
            assertThat(ProofVerifier.verifyExclusion(forgery.root, forgery.key, forgery.forgedExclusionWire))
                    .as("cursor %d: ProofVerifier", cursor).isFalse();
            assertThat(AppChainProofVerifier.verifyMpfExclusion(forgery.root, forgery.key,
                    forgery.forgedExclusionWire)).as("cursor %d: AppChainProofVerifier", cursor).isFalse();
        }
    }

    @Test
    void honestExclusionProofsStillVerify() {
        int verified = 0;
        for (int size : new int[] {1, 2, 3, 30, 300}) {
            MpfTrie trie = new MpfTrie(new MapNodeStore());
            for (int i = 0; i < size; i++) {
                byte[] key = ("entry-" + size + "-" + i).getBytes(StandardCharsets.US_ASCII);
                trie.put(key, key);
            }
            for (int i = 0; i < 200; i++) {
                byte[] key = ("missing-" + size + "-" + i).getBytes(StandardCharsets.US_ASCII);
                byte[] wire = trie.getProofWire(key).orElseThrow();
                assertThat(ProofVerifier.verifyExclusion(trie.getRootHash(), key, wire))
                        .as("size %d, missing %d", size, i).isTrue();
                assertThat(AppChainProofVerifier.verifyMpfExclusion(trie.getRootHash(), key, wire)).isTrue();
                verified++;
            }
        }
        assertThat(verified).isEqualTo(1_000);
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();

        @Override
        public byte[] get(byte[] hash) {
            return nodes.get(HexFormat.of().formatHex(hash));
        }

        @Override
        public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(HexFormat.of().formatHex(hash), nodeBytes);
        }

        @Override
        public void delete(byte[] hash) {
            nodes.remove(HexFormat.of().formatHex(hash));
        }
    }
}
