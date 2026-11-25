package org.yanoproject.x.proofs;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Bounded normalization of an MPF non-membership proof.
 *
 * <p>The terminal node sits at {@code terminalCursor} on the key's path and is one of:</p>
 * <ul>
 *   <li>an empty branch slot: the three conflicting fields are empty;</li>
 *   <li>a conflicting leaf: {@code conflictingKeyHash} names it, {@code conflictingLeafSuffix}
 *       is its encoded suffix at {@code terminalCursor} and {@code conflictingValueHash} its
 *       value hash;</li>
 *   <li>a conflicting branch, whose compressed prefix leaves the key's path:
 *       {@code conflictingKeyHash} is empty, {@code conflictingLeafSuffix} holds the branch prefix
 *       (one nibble per byte) and {@code conflictingValueHash} the branch's 16-child merkle root.</li>
 * </ul>
 * <p>Folds are ordered from that terminal back to the root, matching
 * {@link MpfNormalizedProof}. The field layout is the on-chain {@code AbsenceProof} layout.</p>
 */
public record MpfNormalizedNonMembershipProof(
        byte[] stateRoot,
        byte[] key,
        int terminalCursor,
        byte[] conflictingLeafSuffix,
        byte[] conflictingKeyHash,
        byte[] conflictingValueHash,
        List<MpfNormalizedProof.FoldStep> folds,
        long committedHeight
) {
    public MpfNormalizedNonMembershipProof {
        stateRoot = exact(stateRoot, 32, "state root");
        key = bounded(key, 1, MpfNormalizedProof.MAX_KEY_BYTES, "key");
        if (terminalCursor < 0 || terminalCursor > MpfNormalizedProof.PATH_NIBBLES) {
            throw new IllegalArgumentException("MPF absence terminal cursor is out of range");
        }
        conflictingLeafSuffix = conflictingLeafSuffix == null
                ? new byte[0] : conflictingLeafSuffix.clone();
        conflictingKeyHash = conflictingKeyHash == null
                ? new byte[0] : conflictingKeyHash.clone();
        conflictingValueHash = conflictingValueHash == null
                ? new byte[0] : conflictingValueHash.clone();
        boolean conflicting = conflictingLeafSuffix.length != 0
                || conflictingKeyHash.length != 0 || conflictingValueHash.length != 0;
        if (conflicting && conflictingKeyHash.length == 0) {
            bounded(conflictingLeafSuffix, 1, MpfNormalizedProof.PATH_NIBBLES - 1,
                    "conflicting branch prefix");
            for (byte nibble : conflictingLeafSuffix) {
                if ((nibble & 0xFF) > 15) {
                    throw new IllegalArgumentException("MPF conflicting branch prefix holds a non-nibble");
                }
            }
            exact(conflictingValueHash, 32, "conflicting branch merkle root");
        } else if (conflicting) {
            bounded(conflictingLeafSuffix, 1, 33, "conflicting leaf suffix");
            exact(conflictingKeyHash, 32, "conflicting key hash");
            exact(conflictingValueHash, 32, "conflicting value hash");
        }
        folds = List.copyOf(Objects.requireNonNull(folds, "folds"));
        if (folds.size() > MpfNormalizedProof.MAX_FOLDS) {
            throw new IllegalArgumentException("MPF proof contains too many folds");
        }
        if (committedHeight < 0) {
            throw new IllegalArgumentException("MPF committed height cannot be negative");
        }
    }

    /** The terminal is a leaf of another key. */
    public boolean conflictingLeaf() {
        return conflictingKeyHash.length != 0;
    }

    /** The terminal is a branch whose compressed prefix leaves the key's path. */
    public boolean conflictingBranch() {
        return conflictingKeyHash.length == 0 && conflictingLeafSuffix.length != 0;
    }

    public boolean verify() {
        byte[] queryPath = MpfNormalizedProof.nibbles(Blake2bUtil.blake2bHash256(key));
        byte[] child;
        if (conflictingLeaf()) {
            // Only the suffix below the terminal is authenticated, so the key's own suffix at that depth is what
            // must differ, never the whole conflicting key hash.
            byte[] conflictPath = MpfNormalizedProof.nibbles(conflictingKeyHash);
            byte[] expectedSuffix = MpfNormalizedProof.encodeLeafSuffix(
                    Arrays.copyOfRange(conflictPath, terminalCursor, conflictPath.length));
            byte[] querySuffix = MpfNormalizedProof.encodeLeafSuffix(
                    Arrays.copyOfRange(queryPath, terminalCursor, queryPath.length));
            if (!Arrays.equals(expectedSuffix, conflictingLeafSuffix)
                    || Arrays.equals(querySuffix, conflictingLeafSuffix)
                    || readsAsBranch(conflictingLeafSuffix)) {
                return false;
            }
            child = MpfNormalizedProof.commitLeaf(conflictingLeafSuffix,
                    conflictingValueHash);
        } else if (conflictingBranch()) {
            if (terminalCursor + conflictingLeafSuffix.length >= MpfNormalizedProof.PATH_NIBBLES
                    || Arrays.equals(conflictingLeafSuffix, Arrays.copyOfRange(queryPath, terminalCursor,
                    terminalCursor + conflictingLeafSuffix.length))) {
                return false;
            }
            child = hash(conflictingLeafSuffix, conflictingValueHash);
        } else {
            child = new byte[32];
        }
        int cursorEnd = terminalCursor;
        for (MpfNormalizedProof.FoldStep fold : folds) {
            int cursor = fold.cursor();
            if (cursor + fold.prefix().length + 1 != cursorEnd
                    || !Arrays.equals(fold.prefix(), Arrays.copyOfRange(
                    queryPath, cursor, cursor + fold.prefix().length))
                    || (queryPath[cursor + fold.prefix().length] & 0xFF) != fold.nibble()) {
                return false;
            }
            byte[] merkle = MpfNormalizedProof.aggregateSiblingHashes(
                    fold.nibble(), child, fold.neighbors());
            if (fold.branchValueHash().length == 32) {
                merkle = hash(merkle, MpfNormalizedProof.commitLeaf(
                        MpfNormalizedProof.encodeLeafSuffix(new byte[0]),
                        fold.branchValueHash()));
            }
            child = hash(fold.prefix(), merkle);
            cursorEnd = cursor;
        }
        return cursorEnd == 0 && Arrays.equals(stateRoot, child);
    }

    /**
     * True when an odd leaf suffix ({@code 00 <nibble> <raw bytes>}) has only nibble-valued raw bytes, so its
     * hash could equally be a branch node {@code blake2b-256(prefix ‖ merkle)}; such a "leaf" is never accepted
     * as a conflicting leaf (the on-chain verifiers apply the same rule).
     */
    static boolean readsAsBranch(byte[] encodedSuffix) {
        if (encodedSuffix.length == 0 || encodedSuffix[0] != 0) {
            return false;
        }
        for (int i = 2; i < encodedSuffix.length; i++) {
            if ((encodedSuffix[i] & 0xFF) > 15) {
                return false;
            }
        }
        return true;
    }

    @Override public byte[] stateRoot() { return stateRoot.clone(); }
    @Override public byte[] key() { return key.clone(); }
    @Override public byte[] conflictingLeafSuffix() { return conflictingLeafSuffix.clone(); }
    @Override public byte[] conflictingKeyHash() { return conflictingKeyHash.clone(); }
    @Override public byte[] conflictingValueHash() { return conflictingValueHash.clone(); }

    private static byte[] hash(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) output.writeBytes(value);
        return Blake2bUtil.blake2bHash256(output.toByteArray());
    }

    private static byte[] exact(byte[] value, int length, String field) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException("MPF " + field + " must contain " + length + " bytes");
        }
        return value.clone();
    }

    private static byte[] bounded(byte[] value, int minimum, int maximum, String field) {
        if (value == null || value.length < minimum || value.length > maximum) {
            throw new IllegalArgumentException("MPF " + field + " length is outside bounds");
        }
        return value.clone();
    }
}
