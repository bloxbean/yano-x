package org.yanoproject.x.proofs.onchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.julclang.core.PlutusData;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.MpfProofConverter;
import org.yanoproject.x.client.ProofVerifier;
import org.yanoproject.x.proofs.MpfNormalizedNonMembershipProof;
import org.yanoproject.x.proofs.MpfNormalizedProof;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** A real mpf-blake2b256-v1 trie and the Data encodings the on-chain verifiers read, for compiled-UPLC tests. */
final class MpfTestTrie {
    private final MpfTrie trie = new MpfTrie(new MapNodeStore());

    /** Keys {@code <prefix>0 .. <prefix>(count-1)}, each holding its own key bytes as value. */
    static MpfTestTrie of(String prefix, int count) {
        MpfTestTrie result = new MpfTestTrie();
        for (int i = 0; i < count; i++) {
            byte[] key = key(prefix, i);
            result.trie.put(key, key);
        }
        return result;
    }

    static byte[] key(String prefix, int index) {
        return (prefix + index).getBytes(StandardCharsets.US_ASCII);
    }

    byte[] root() {
        return trie.getRootHash();
    }

    MpfNormalizedProof inclusion(byte[] key) {
        return MpfProofConverter.convert(wire(key, trie.get(key)));
    }

    MpfNormalizedNonMembershipProof absence(byte[] key) {
        return MpfProofConverter.convertAbsence(wire(key, null));
    }

    /** The first absent {@code <prefix>i} whose path ends at a conflicting leaf, or at an empty branch slot. */
    MpfNormalizedNonMembershipProof firstAbsence(String prefix, boolean conflictingLeaf) {
        for (int i = 0; ; i++) {
            MpfNormalizedNonMembershipProof proof = absence(key(prefix, i));
            if (proof.conflictingLeaf() == conflictingLeaf) {
                return proof;
            }
        }
    }

    private AppChainClient.Proof wire(byte[] key, byte[] value) {
        ProofVerifier.ProfileMetadata metadata = ProofVerifier.profileMetadata(
                ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow();
        HexFormat hex = HexFormat.of();
        return new AppChainClient.Proof(
                hex.formatHex(key), "test", hex.formatHex(root()),
                hex.formatHex(trie.getProofWire(key).orElseThrow()),
                value == null ? null : hex.formatHex(value), 42L, 42L, 1,
                ProofVerifier.MPF_BLAKE2B256_V1, metadata.backend(),
                metadata.commitmentFormatId(), metadata.formatFingerprintHex(),
                "11".repeat(32), metadata.proofEncodingId(), metadata.nativeVersioning(),
                metadata.physicalDelete(), 42L,
                value == null ? AppChainClient.ProofPresence.ABSENT : AppChainClient.ProofPresence.PRESENT,
                null, null);
    }

    /** {@code Proof(key, value, leafSuffix, folds)} as read by {@link MpfOnChainVerifier}. */
    static PlutusData inclusionData(MpfNormalizedProof proof, int foldIndex, byte[] branchValueHash) {
        return PlutusData.constr(0, PlutusData.bytes(proof.key()), PlutusData.bytes(proof.value()),
                PlutusData.bytes(proof.leafSuffix()), folds(proof.folds(), foldIndex, branchValueHash));
    }

    /** The seven-field absence {@code Proof} read by the pair and snapshot verifiers. */
    static PlutusData absenceData(byte[] key, byte[] leafSuffix, List<MpfNormalizedProof.FoldStep> folds,
                                  long terminalCursor, byte[] conflictingKeyHash, byte[] conflictingValueHash) {
        return PlutusData.constr(0, PlutusData.bytes(key), PlutusData.bytes(new byte[0]),
                PlutusData.bytes(leafSuffix), folds(folds, -1, null), PlutusData.integer(terminalCursor),
                PlutusData.bytes(conflictingKeyHash), PlutusData.bytes(conflictingValueHash));
    }

    static PlutusData absenceData(MpfNormalizedNonMembershipProof proof) {
        return absenceData(proof.key(), proof.conflictingLeafSuffix(), proof.folds(), proof.terminalCursor(),
                proof.conflictingKeyHash(), proof.conflictingValueHash());
    }

    /** Fold {@code foldIndex} (leaf first) carries {@code branchValueHash}; the others keep theirs. */
    private static PlutusData folds(List<MpfNormalizedProof.FoldStep> folds, int foldIndex, byte[] branchValueHash) {
        PlutusData[] result = new PlutusData[folds.size()];
        for (int i = 0; i < folds.size(); i++) {
            MpfNormalizedProof.FoldStep fold = folds.get(i);
            List<byte[]> neighbors = fold.neighbors();
            result[i] = PlutusData.constr(0, PlutusData.integer(fold.cursor()), PlutusData.bytes(fold.prefix()),
                    PlutusData.integer(fold.nibble()), PlutusData.bytes(neighbors.get(0)),
                    PlutusData.bytes(neighbors.get(1)), PlutusData.bytes(neighbors.get(2)),
                    PlutusData.bytes(neighbors.get(3)),
                    PlutusData.bytes(i == foldIndex ? branchValueHash : fold.branchValueHash()));
        }
        return PlutusData.list(result);
    }

    /** Fold the proof to its root, committing {@code branchValueHash} at fold {@code foldIndex} (-1 for none). */
    static byte[] root(MpfNormalizedProof proof, int foldIndex, byte[] branchValueHash) {
        byte[] child = hash(proof.leafSuffix(), Blake2bUtil.blake2bHash256(proof.value()));
        for (int i = 0; i < proof.folds().size(); i++) {
            MpfNormalizedProof.FoldStep fold = proof.folds().get(i);
            byte[] merkle = child;
            for (int level = 0; level < 4; level++) {
                byte[] sibling = fold.neighbors().get(3 - level);
                merkle = ((fold.nibble() >> level) & 1) == 0 ? hash(merkle, sibling) : hash(sibling, merkle);
            }
            if (i == foldIndex) {
                merkle = hash(merkle, hash(new byte[] {(byte) 0xff}, branchValueHash));
            }
            child = hash(fold.prefix(), merkle);
        }
        return child;
    }

    static byte[] hash(byte[] left, byte[] right) {
        byte[] joined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        return Blake2bUtil.blake2bHash256(joined);
    }

    /** Leaf-path nibbles below {@code leafSuffix}: the cursor an absence proof of a present key would claim. */
    static long leafCursor(byte[] leafSuffix) {
        long nibbles = (leafSuffix[0] & 0xff) == 0 ? 1 + (leafSuffix.length - 2) * 2L : (leafSuffix.length - 1) * 2L;
        return MpfNormalizedProof.PATH_NIBBLES - nibbles;
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
