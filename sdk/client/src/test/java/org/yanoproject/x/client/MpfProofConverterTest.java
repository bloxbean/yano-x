package org.yanoproject.x.client;

import org.yanoproject.x.client.MpfProofConverter;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.proofs.MpfNormalizedProof;
import org.yanoproject.x.proofs.MpfNormalizedNonMembershipProof;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MpfProofConverterTest {
    @Test
    void everyRealInclusionProofNormalizesAndReconstructsTheSameRoot() {
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        Map<byte[], byte[]> entries = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            byte[] key = ("claim-key-" + i).getBytes(StandardCharsets.US_ASCII);
            byte[] value = ("claim-value-" + i).getBytes(StandardCharsets.US_ASCII);
            entries.put(key, value);
            trie.put(key, value);
        }
        byte[] root = trie.getRootHash();

        for (Map.Entry<byte[], byte[]> entry : entries.entrySet()) {
            AppChainClient.Proof wire = proof(
                    trie, root, entry.getKey(), entry.getValue());
            MpfNormalizedProof converted = MpfProofConverter.convert(wire);

            assertThat(converted.verify()).isTrue();
            assertThat(converted.stateRoot()).isEqualTo(root);
            assertThat(converted.committedHeight()).isEqualTo(42);
        }
    }

    @Test
    void conversionFailsClosedForTamperingAndNonAtomicProofs() {
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        byte[] key = "claim".getBytes(StandardCharsets.US_ASCII);
        byte[] value = "value".getBytes(StandardCharsets.US_ASCII);
        trie.put(key, value);
        AppChainClient.Proof valid = proof(
                trie, trie.getRootHash(), key, value);

        AppChainClient.Proof tampered = TestProofs.mpf(
                valid.keyHex(),
                valid.chainId(),
                valid.stateRootHex(),
                valid.proofWireHex(),
                HexFormat.of().formatHex(
                        "other".getBytes(StandardCharsets.US_ASCII)),
                valid.finalizedAtHeight(),
                valid.committedHeight());
        AppChainClient.Proof nonAtomic = TestProofs.mpf(
                valid.keyHex(),
                valid.chainId(),
                valid.stateRootHex(),
                valid.proofWireHex(),
                valid.valueHex(),
                valid.finalizedAtHeight(), null);

        assertThatThrownBy(() -> MpfProofConverter.convert(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid MPF");
        assertThatThrownBy(() -> MpfProofConverter.convert(nonAtomic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root-fixed");
    }

    @Test
    void missingBranchAndConflictingLeafAbsenceProofsNormalize() {
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        for (int i = 0; i < 24; i++) {
            trie.put(("claim-key-" + i).getBytes(StandardCharsets.US_ASCII),
                    ("claim-value-" + i).getBytes(StandardCharsets.US_ASCII));
        }
        byte[] root = trie.getRootHash();
        for (String missing : java.util.List.of("absent", "claim-key-240")) {
            byte[] key = missing.getBytes(StandardCharsets.US_ASCII);
            AppChainClient.Proof wire = proof(trie, root, key, null);
            MpfNormalizedNonMembershipProof converted =
                    MpfProofConverter.convertAbsence(wire);

            assertThat(converted.verify()).isTrue();
            assertThat(converted.stateRoot()).isEqualTo(root);
            assertThat(converted.key()).isEqualTo(key);
        }
    }

    @Test
    void aikenCanonicalLeafAndForkStepsNormalizeForPresentAndMissingKeys() {
        Set<Long> inclusionSteps = new HashSet<>();
        Set<Long> absenceEnds = new HashSet<>();
        int unsupportedAbsences = 0;
        for (int size : new int[] {1, 2, 3, 30, 300}) {
            MpfTrie trie = new MpfTrie(new MapNodeStore());
            List<byte[]> keys = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                byte[] key = ("entry-" + size + "-" + i).getBytes(StandardCharsets.US_ASCII);
                keys.add(key);
                trie.put(key, ("value-" + i).getBytes(StandardCharsets.US_ASCII));
            }
            byte[] root = trie.getRootHash();
            for (int i = 0; i < size; i++) {
                byte[] value = ("value-" + i).getBytes(StandardCharsets.US_ASCII);
                AppChainClient.Proof wire = proof(trie, root, keys.get(i), value);
                inclusionSteps.addAll(stepTags(wire));

                MpfNormalizedProof converted = MpfProofConverter.convert(wire);

                assertThat(converted.verify()).isTrue();
                assertThat(converted.stateRoot()).isEqualTo(root);
            }
            for (int i = 0; i < 200; i++) {
                byte[] key = ("missing-" + size + "-" + i).getBytes(StandardCharsets.US_ASCII);
                AppChainClient.Proof wire = proof(trie, root, key, null);
                List<Long> steps = stepTags(wire);
                absenceEnds.add(steps.getLast());
                if (steps.getLast() == 122L) {
                    assertThatThrownBy(() -> MpfProofConverter.convertAbsence(wire))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("leave a branch prefix");
                    unsupportedAbsences++;
                    continue;
                }

                MpfNormalizedNonMembershipProof converted = MpfProofConverter.convertAbsence(wire);

                assertThat(converted.verify()).isTrue();
                assertThat(converted.stateRoot()).isEqualTo(root);
                assertThat(converted.conflictingLeaf()).isEqualTo(steps.getLast() == 123L);
            }
        }
        // Every step shape occurs, so the conversions above covered Leaf and Fork steps mid-path.
        assertThat(inclusionSteps).containsExactlyInAnyOrder(121L, 122L, 123L);
        assertThat(absenceEnds).contains(121L, 123L);
        assertThat(unsupportedAbsences).isLessThan(500);
    }

    /**
     * The folds of a normalized absence proof authenticate only the query's path above the terminal cursor. A
     * conflicting key hash that differs from a present key only above that cursor still yields the present leaf's
     * suffix, so it names that key's own leaf and must not prove the key absent.
     */
    @Test
    void normalizedAbsenceRejectsAConflictingKeyThatDiffersOnlyAboveTheCursor() {
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            byte[] key = ("present-" + i).getBytes(StandardCharsets.US_ASCII);
            keys.add(key);
            trie.put(key, key);
        }
        byte[] root = trie.getRootHash();
        Set<Integer> depths = new TreeSet<>();
        for (byte[] key : keys) {
            MpfNormalizedProof present = MpfProofConverter.convert(proof(trie, root, key, key));
            byte[] suffix = present.leafSuffix();
            int cursor = MpfNormalizedProof.PATH_NIBBLES
                    - ((suffix[0] & 0xff) == 0 ? 1 + (suffix.length - 2) * 2 : (suffix.length - 1) * 2);
            byte[] keyHash = Blake2bUtil.blake2bHash256(key);
            byte[] valueHash = Blake2bUtil.blake2bHash256(key);
            depths.add(cursor);
            assertThat(absence(root, key, cursor, suffix, keyHash, valueHash, present).verify()).isFalse();
            for (int nibble = 0; nibble < cursor; nibble++) {
                byte[] forged = keyHash.clone();
                forged[nibble / 2] ^= (byte) (nibble % 2 == 0 ? 0x10 : 0x01);
                assertThat(MpfNormalizedProof.encodeLeafSuffix(Arrays.copyOfRange(
                        MpfNormalizedProof.nibbles(forged), cursor, MpfNormalizedProof.PATH_NIBBLES)))
                        .isEqualTo(suffix);
                assertThat(absence(root, key, cursor, suffix, forged, valueHash, present).verify())
                        .as("cursor %d, flipped nibble %d", cursor, nibble).isFalse();
            }
        }
        assertThat(depths).hasSizeGreaterThanOrEqualTo(2);
        for (int i = 0; i < 50; i++) {
            byte[] missing = ("missing-" + i).getBytes(StandardCharsets.US_ASCII);
            try {
                assertThat(MpfProofConverter.convertAbsence(proof(trie, root, missing, null)).verify()).isTrue();
            } catch (IllegalArgumentException unsupportedTerminal) {
                assertThat(unsupportedTerminal).hasMessageContaining("leave a branch prefix");
            }
        }
    }

    private static MpfNormalizedNonMembershipProof absence(byte[] root, byte[] key, int cursor, byte[] suffix,
                                                           byte[] conflictingKeyHash, byte[] conflictingValueHash,
                                                           MpfNormalizedProof present) {
        return new MpfNormalizedNonMembershipProof(root, key, cursor, suffix, conflictingKeyHash,
                conflictingValueHash, present.folds(), 42L);
    }

    private static List<Long> stepTags(AppChainClient.Proof proof) {
        try {
            DataItem decoded = new CborDecoder(new ByteArrayInputStream(
                    HexFormat.of().parseHex(proof.proofWireHex()))).decode().getFirst();
            List<Long> tags = new ArrayList<>();
            for (DataItem step : ((Array) decoded).getDataItems()) {
                tags.add(step.getTag() == null ? 121L : step.getTag().getValue());
            }
            return tags;
        } catch (CborException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static AppChainClient.Proof proof(
            MpfTrie trie,
            byte[] root,
            byte[] key,
            byte[] value
    ) {
        return TestProofs.mpf(
                HexFormat.of().formatHex(key),
                "eutxo",
                HexFormat.of().formatHex(root),
                HexFormat.of().formatHex(
                        trie.getProofWire(key).orElseThrow()),
                value == null ? null : HexFormat.of().formatHex(value),
                42L,
                42L);
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
