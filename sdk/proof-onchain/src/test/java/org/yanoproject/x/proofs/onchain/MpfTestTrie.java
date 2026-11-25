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

    /** Exactly {@code keys}, each holding its own key bytes as value. */
    static MpfTestTrie ofKeys(List<byte[]> keys) {
        MpfTestTrie result = new MpfTestTrie();
        for (byte[] key : keys) {
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

    /** The terminal an absence proof ends at. */
    enum Terminal { EMPTY_SLOT, CONFLICTING_LEAF, CONFLICTING_BRANCH }

    static Terminal terminal(MpfNormalizedNonMembershipProof proof) {
        return proof.conflictingLeaf() ? Terminal.CONFLICTING_LEAF
                : proof.conflictingBranch() ? Terminal.CONFLICTING_BRANCH : Terminal.EMPTY_SLOT;
    }

    /** The first absent {@code <prefix>i} whose proof ends at a conflicting leaf, or at an empty branch slot. */
    MpfNormalizedNonMembershipProof firstAbsence(String prefix, boolean conflictingLeaf) {
        return firstAbsence(prefix, conflictingLeaf ? Terminal.CONFLICTING_LEAF : Terminal.EMPTY_SLOT);
    }

    MpfNormalizedNonMembershipProof firstAbsence(String prefix, Terminal terminal) {
        for (int i = 0; ; i++) {
            MpfNormalizedNonMembershipProof proof = absence(key(prefix, i));
            if (terminal(proof) == terminal) {
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

    /**
     * The 16-child merkle root of the branch an inclusion proof passes through at fold {@code foldIndex} (leaf
     * first): the node at that fold's cursor is {@code blake2b-256(prefix ‖ merkle)}.
     */
    static byte[] branchMerkle(MpfNormalizedProof proof, int foldIndex) {
        byte[] child = hash(proof.leafSuffix(), Blake2bUtil.blake2bHash256(proof.value()));
        byte[] merkle = null;
        for (int i = 0; i <= foldIndex; i++) {
            MpfNormalizedProof.FoldStep fold = proof.folds().get(i);
            merkle = child;
            for (int level = 0; level < 4; level++) {
                byte[] sibling = fold.neighbors().get(3 - level);
                merkle = ((fold.nibble() >> level) & 1) == 0 ? hash(merkle, sibling) : hash(sibling, merkle);
            }
            if (fold.branchValueHash().length == 32) {
                merkle = hash(merkle, hash(new byte[] {(byte) 0xff}, fold.branchValueHash()));
            }
            child = hash(fold.prefix(), merkle);
        }
        return merkle;
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

    /** A synthetic forgery: a real branch on a present key's own path presented as a conflicting leaf. */
    record BranchAsLeaf(byte[] root, MpfNormalizedProof inclusion, MpfNormalizedNonMembershipProof forgedAbsence) {
    }

    /** {@link #branchReadAsLeaf(int)} at cursor 1. */
    static BranchAsLeaf branchReadAsLeaf() {
        return branchReadAsLeaf(1);
    }

    /**
     * A trie whose root branch (cursor 0, prefix {@code path[0, c-1)}, nibble {@code path[c-1]}) leads to a branch
     * at odd cursor {@code c} with a {@code 2 + (63 - c) / 2}-nibble prefix starting with nibble 0, under which the
     * victim key's leaf sits. That branch, {@code blake2b-256(prefix ‖ merkle)}, has exactly the shape of an odd
     * leaf {@code blake2b-256(00 n raw ‖ valueHash)}: a conflicting key hash sharing the path above {@code c} and
     * carrying the prefix's nibbles as raw bytes makes it a "conflicting leaf" that proves the present victim
     * absent, unless the verifier requires a raw byte above 15. (A real trie needs about 2^70 partial-collision
     * grinding for such a long shared prefix.)
     */
    static BranchAsLeaf branchReadAsLeaf(int c) {
        if (c % 2 == 0 || c < 1 || c > 61) throw new IllegalArgumentException("odd cursor 1..61");
        int length = 2 + (63 - c) / 2;
        byte[] key = null;
        byte[] path = null;
        for (int i = 0; ; i++) {
            key = key("victim/" + c + "/", i);
            path = MpfNormalizedProof.nibbles(Blake2bUtil.blake2bHash256(key));
            if (path[c] == 0 && path[c + 1] != 0) break;
        }
        byte[] prefix = Arrays.copyOfRange(path, c, c + length);
        byte[] leafSuffix = MpfNormalizedProof.encodeLeafSuffix(Arrays.copyOfRange(path, c + length + 1, 64));
        byte[] leaf = hash(leafSuffix, Blake2bUtil.blake2bHash256(key));
        List<byte[]> lower = MpfNormalizedProof.sparseNeighbors(path[c + length], (path[c + length] + 1) % 16,
                Blake2bUtil.blake2bHash256(new byte[] {1}));
        byte[] branchMerkle = merkle(path[c + length], leaf, lower);
        byte[] branch = hash(prefix, branchMerkle);
        List<byte[]> upper = MpfNormalizedProof.sparseNeighbors(path[c - 1], (path[c - 1] + 1) % 16,
                Blake2bUtil.blake2bHash256(new byte[] {2}));
        byte[] rootPrefix = Arrays.copyOfRange(path, 0, c - 1);
        byte[] root = hash(rootPrefix, merkle(path[c - 1], branch, upper));
        MpfNormalizedProof.FoldStep top = new MpfNormalizedProof.FoldStep(0, rootPrefix, path[c - 1], upper,
                new byte[0]);
        MpfNormalizedProof inclusion = new MpfNormalizedProof(root, key, key, leafSuffix, List.of(
                new MpfNormalizedProof.FoldStep(c, prefix, path[c + length], lower, new byte[0]), top), 42);
        byte[] keyHash = Blake2bUtil.blake2bHash256(key);
        byte[] conflicting = new byte[32];
        System.arraycopy(keyHash, 0, conflicting, 0, (c - 1) / 2);
        conflicting[(c - 1) / 2] = (byte) ((path[c - 1] << 4) | prefix[1]);
        System.arraycopy(prefix, 2, conflicting, (c + 1) / 2, length - 2);
        MpfNormalizedNonMembershipProof forged = new MpfNormalizedNonMembershipProof(root, key, c, prefix,
                conflicting, branchMerkle, List.of(top), 42);
        return new BranchAsLeaf(root, inclusion, forged);
    }

    /**
     * The converse forgery at odd cursor {@code c}: the victim key's own leaf sits at {@code c} under the root
     * branch, and every raw path byte of its suffix {@code 00 <nibble> <raw bytes>} is below 16. Read as a branch
     * with prefix {@code 0 <nibble> <raw bytes as nibbles>} and merkle root the value hash, it leaves the key's path
     * at its first nibble and hashes to the very node, so it is a "conflicting branch" that proves the present key
     * absent, unless the verifier refuses the odd-leaf shape. The key needs about {@code 2^(4 (63 - c) / 2)} tries
     * (a real trie about 2^84 work at the best cursor).
     */
    static BranchAsLeaf ownLeafReadAsBranch(int c) {
        if (c % 2 == 0 || c < 1 || c > 59) throw new IllegalArgumentException("odd cursor 1..59");
        byte[] key = null;
        byte[] path = null;
        search:
        for (int i = 0; ; i++) {
            key = key("own-leaf/" + c + "/", i);
            path = MpfNormalizedProof.nibbles(Blake2bUtil.blake2bHash256(key));
            if (path[c] == 0) continue;
            for (int j = c + 1; j < 64; j += 2) {
                if (path[j] != 0) continue search;
            }
            break;
        }
        byte[] leafSuffix = MpfNormalizedProof.encodeLeafSuffix(Arrays.copyOfRange(path, c, 64));
        byte[] valueHash = Blake2bUtil.blake2bHash256(key);
        byte[] leaf = hash(leafSuffix, valueHash);
        List<byte[]> upper = MpfNormalizedProof.sparseNeighbors(path[c - 1], (path[c - 1] + 1) % 16,
                Blake2bUtil.blake2bHash256(new byte[] {2}));
        byte[] rootPrefix = Arrays.copyOfRange(path, 0, c - 1);
        byte[] root = hash(rootPrefix, merkle(path[c - 1], leaf, upper));
        MpfNormalizedProof.FoldStep top = new MpfNormalizedProof.FoldStep(0, rootPrefix, path[c - 1], upper,
                new byte[0]);
        MpfNormalizedProof inclusion = new MpfNormalizedProof(root, key, key, leafSuffix, List.of(top), 42);
        byte[] branchPrefix = new byte[leafSuffix.length];
        System.arraycopy(leafSuffix, 0, branchPrefix, 0, leafSuffix.length);
        MpfNormalizedNonMembershipProof forged = new MpfNormalizedNonMembershipProof(root, key, c, branchPrefix,
                new byte[0], valueHash, List.of(top), 42);
        return new BranchAsLeaf(root, inclusion, forged);
    }

    private static byte[] merkle(int nibble, byte[] child, List<byte[]> neighbors) {
        byte[] merkle = child;
        for (int level = 0; level < 4; level++) {
            byte[] sibling = neighbors.get(3 - level);
            merkle = ((nibble >> level) & 1) == 0 ? hash(merkle, sibling) : hash(sibling, merkle);
        }
        return merkle;
    }

    static byte[] hash(byte[] left, byte[] right) {
        byte[] joined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        return Blake2bUtil.blake2bHash256(joined);
    }

    /** A copy of {@code hash} with path nibble {@code index} changed. */
    static byte[] flipNibble(byte[] hash, long index) {
        byte[] result = hash.clone();
        result[(int) (index / 2)] ^= (byte) (index % 2 == 0 ? 0x10 : 0x01);
        return result;
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
