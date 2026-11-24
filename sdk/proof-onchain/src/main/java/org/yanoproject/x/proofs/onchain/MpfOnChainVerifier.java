package org.yanoproject.x.proofs.onchain;

import org.julclang.core.types.JulcList;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.OnchainLibrary;

import java.math.BigInteger;

/**
 * Bounded MPF inclusion and exclusion verifiers reusable by application validators.
 *
 * <p>{@link #verifyAbsence} is the exclusion check lifted unchanged from
 * {@code MpfPairOnChainVerifier}'s private helper (ADR-059 §7 framework addition 9). Its
 * {@link AbsenceProof} has the same seven-field layout as {@code MpfPairOnChainVerifier.Proof}, so
 * one off-chain encoder serves both.
 */
@OnchainLibrary
public final class MpfOnChainVerifier {
    public static final long PATH_NIBBLES = 64;
    public static final long MAX_KEY_BYTES = 256;
    public static final long MAX_VALUE_BYTES = 8 * 1024;
    public static final long MAX_FOLDS = 32;

    public record InclusionFold(
            BigInteger cursor,
            byte[] prefix,
            BigInteger nibble,
            byte[] neighbor1,
            byte[] neighbor2,
            byte[] neighbor3,
            byte[] neighbor4,
            byte[] branchValueHash
    ) {
    }

    public record Proof(
            byte[] key,
            byte[] value,
            byte[] leafSuffix,
            JulcList<InclusionFold> folds
    ) {
    }

    /**
     * An exclusion proof of {@code key}. {@code value} is unused and should be empty. An empty
     * {@code leafSuffix} means the path ends at an empty branch slot; otherwise it ends at a
     * different leaf, whose key hash and value hash are given. {@code terminalCursor} is the path
     * depth, in nibbles, at which the folds start.
     */
    public record AbsenceProof(
            byte[] key,
            byte[] value,
            byte[] leafSuffix,
            JulcList<InclusionFold> folds,
            BigInteger terminalCursor,
            byte[] conflictingKeyHash,
            byte[] conflictingValueHash
    ) {
    }

    private MpfOnChainVerifier() {
    }

    /** True when {@code proof.key()} is absent from the mpf-blake2b256-v1 trie with root {@code expectedRoot}. */
    public static boolean verifyAbsence(AbsenceProof proof, byte[] expectedRoot) {
        long keyLength = Builtins.lengthOfByteString(proof.key());
        long suffixLength = Builtins.lengthOfByteString(proof.leafSuffix());
        long cursorEnd = proof.terminalCursor().longValue();
        if (Builtins.lengthOfByteString(expectedRoot) != 32
                || keyLength < 1 || keyLength > MAX_KEY_BYTES
                || cursorEnd < 0 || cursorEnd > PATH_NIBBLES
                || proof.folds().size() > MAX_FOLDS) return false;
        byte[] queryPath = Builtins.blake2b_256(proof.key());
        boolean missing = suffixLength == 0;
        if (missing) {
            if (Builtins.lengthOfByteString(proof.conflictingKeyHash()) != 0
                    || Builtins.lengthOfByteString(proof.conflictingValueHash()) != 0) return false;
        } else {
            if (suffixLength > 33
                    || Builtins.lengthOfByteString(proof.conflictingKeyHash()) != 32
                    || Builtins.lengthOfByteString(proof.conflictingValueHash()) != 32
                    || Builtins.equalsByteString(queryPath, proof.conflictingKeyHash())
                    || !validLeafSuffix(proof.conflictingKeyHash(), cursorEnd,
                    proof.leafSuffix())) return false;
        }
        byte[] child = missing ? new byte[]{0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0}
                : hash(proof.leafSuffix(), proof.conflictingValueHash());
        return Builtins.equalsByteString(expectedRoot,
                foldAbsence(queryPath, child, cursorEnd, proof.folds()));
    }

    /**
     * Folds an exclusion path up to the root, under the same per-fold rules as {@link #verifyInclusion}; a
     * malformed fold, or a path that does not end at cursor 0, yields an empty (never matching) root.
     */
    private static byte[] foldAbsence(byte[] queryPath, byte[] child, long cursorEnd,
                                      JulcList<InclusionFold> folds) {
        if (folds.isEmpty()) {
            return cursorEnd == 0 ? child : Builtins.emptyByteString();
        }
        InclusionFold fold = folds.head();
        long cursor = fold.cursor().longValue();
        long prefixLength = Builtins.lengthOfByteString(fold.prefix());
        long nibble = fold.nibble().longValue();
        if (cursor < 0 || nibble < 0 || nibble > 15
                || cursor + prefixLength + 1 != cursorEnd
                || !validPrefix(queryPath, cursor, fold.prefix())
                || pathNibble(queryPath, cursor + prefixLength) != nibble
                || !hashLength(fold.neighbor1()) || !hashLength(fold.neighbor2())
                || !hashLength(fold.neighbor3()) || !hashLength(fold.neighbor4())
                || (Builtins.lengthOfByteString(fold.branchValueHash()) != 0
                && !hashLength(fold.branchValueHash()))) {
            return Builtins.emptyByteString();
        }
        byte[] branch = aggregate(nibble, child, fold.neighbor1(), fold.neighbor2(),
                fold.neighbor3(), fold.neighbor4());
        byte[] merkle = Builtins.lengthOfByteString(fold.branchValueHash()) == 32
                ? hash(branch, hash(Builtins.consByteString(
                        255, Builtins.emptyByteString()), fold.branchValueHash()))
                : branch;
        return foldAbsence(queryPath, hash(fold.prefix(), merkle), cursor, folds.tail());
    }

    private static boolean validLeafSuffix(byte[] pathHash, long cursorEnd, byte[] encoded) {
        long encodedLength = Builtins.lengthOfByteString(encoded);
        long marker = Builtins.indexByteString(encoded, 0);
        boolean odd = marker == 0;
        long suffixNibbles = odd ? 1 + (encodedLength - 2) * 2
                : (encodedLength - 1) * 2;
        return (marker == 0 || marker == 255) && (!odd || encodedLength >= 2)
                && cursorEnd + suffixNibbles == PATH_NIBBLES
                && (!odd || Builtins.indexByteString(encoded, 1)
                == pathNibble(pathHash, cursorEnd))
                && Builtins.equalsByteString(Builtins.sliceByteString(
                odd ? 2 : 1, encodedLength - (odd ? 2 : 1), encoded),
                Builtins.sliceByteString(odd ? (cursorEnd + 1) / 2 : cursorEnd / 2,
                        encodedLength - (odd ? 2 : 1), pathHash));
    }

    /** Reconstruct the exact mpf-blake2b256-v1 root under hostile redeemer bounds. */
    public static boolean verifyInclusion(Proof proof, byte[] expectedRoot) {
        long keyLength = Builtins.lengthOfByteString(proof.key());
        long valueLength = Builtins.lengthOfByteString(proof.value());
        long encodedLength = Builtins.lengthOfByteString(proof.leafSuffix());
        if (Builtins.lengthOfByteString(expectedRoot) != 32
                || keyLength < 1 || keyLength > MAX_KEY_BYTES
                || valueLength < 1 || valueLength > MAX_VALUE_BYTES
                || encodedLength < 1 || encodedLength > 33
                || proof.folds().size() > MAX_FOLDS) {
            return false;
        }
        byte[] pathHash = Builtins.blake2b_256(proof.key());
        long marker = Builtins.indexByteString(proof.leafSuffix(), 0);
        boolean odd = marker == 0;
        long suffixLength = odd
                ? 1 + (encodedLength - 2) * 2
                : (encodedLength - 1) * 2;
        long cursorEnd = PATH_NIBBLES - suffixLength;
        if ((marker != 0 && marker != 255)
                || (odd && encodedLength < 2)
                || cursorEnd < 0
                || (odd && Builtins.indexByteString(
                proof.leafSuffix(), 1) != pathNibble(pathHash, cursorEnd))
                || !Builtins.equalsByteString(
                Builtins.sliceByteString(
                        odd ? 2 : 1,
                        encodedLength - (odd ? 2 : 1),
                        proof.leafSuffix()),
                Builtins.sliceByteString(
                        odd ? (cursorEnd + 1) / 2 : cursorEnd / 2,
                        encodedLength - (odd ? 2 : 1),
                        pathHash))) {
            return false;
        }
        byte[] child = commitLeaf(
                proof.leafSuffix(), Builtins.blake2b_256(proof.value()));
        boolean valid = true;
        for (InclusionFold fold : proof.folds()) {
            long cursor = fold.cursor().longValue();
            long prefixLength = Builtins.lengthOfByteString(fold.prefix());
            long nibble = fold.nibble().longValue();
            if (cursor < 0 || nibble < 0 || nibble > 15
                    || cursor + prefixLength + 1 != cursorEnd
                    || !validPrefix(pathHash, cursor, fold.prefix())
                    || pathNibble(pathHash, cursor + prefixLength) != nibble
                    || !hashLength(fold.neighbor1())
                    || !hashLength(fold.neighbor2())
                    || !hashLength(fold.neighbor3())
                    || !hashLength(fold.neighbor4())
                    || (Builtins.lengthOfByteString(fold.branchValueHash()) != 0
                    && !hashLength(fold.branchValueHash()))) {
                valid = false;
                break;
            }
            byte[] branch = aggregate(nibble, child, fold.neighbor1(), fold.neighbor2(),
                    fold.neighbor3(), fold.neighbor4());
            byte[] merkle = Builtins.lengthOfByteString(fold.branchValueHash()) == 32
                    ? hash(branch, commitLeaf(
                            Builtins.consByteString(255, Builtins.emptyByteString()),
                            fold.branchValueHash()))
                    : branch;
            child = hash(fold.prefix(), merkle);
            cursorEnd = cursor;
        }
        return valid && cursorEnd == 0
                && Builtins.equalsByteString(expectedRoot, child);
    }

    private static boolean validPrefix(byte[] pathHash, long cursor, byte[] prefix) {
        long length = Builtins.lengthOfByteString(prefix);
        long index = 0;
        boolean valid = true;
        while (index < length) {
            long nibble = Builtins.indexByteString(prefix, index);
            if (nibble < 0 || nibble > 15
                    || nibble != pathNibble(pathHash, cursor + index)) {
                valid = false;
                break;
            }
            index += 1;
        }
        return valid;
    }

    private static long pathNibble(byte[] pathHash, long index) {
        long value = Builtins.indexByteString(pathHash, index / 2);
        return index % 2 == 0 ? value / 16 : value % 16;
    }

    private static byte[] commitLeaf(byte[] encodedSuffix, byte[] valueHash) {
        return hash(encodedSuffix, valueHash);
    }

    private static byte[] aggregate(long nibble, byte[] me, byte[] lvl1,
                                    byte[] lvl2, byte[] lvl3, byte[] lvl4) {
        if (nibble == 0) return hash(hash(hash(hash(me, lvl4), lvl3), lvl2), lvl1);
        if (nibble == 1) return hash(hash(hash(hash(lvl4, me), lvl3), lvl2), lvl1);
        if (nibble == 2) return hash(hash(hash(lvl3, hash(me, lvl4)), lvl2), lvl1);
        if (nibble == 3) return hash(hash(hash(lvl3, hash(lvl4, me)), lvl2), lvl1);
        if (nibble == 4) return hash(hash(lvl2, hash(hash(me, lvl4), lvl3)), lvl1);
        if (nibble == 5) return hash(hash(lvl2, hash(hash(lvl4, me), lvl3)), lvl1);
        if (nibble == 6) return hash(hash(lvl2, hash(lvl3, hash(me, lvl4))), lvl1);
        if (nibble == 7) return hash(hash(lvl2, hash(lvl3, hash(lvl4, me))), lvl1);
        if (nibble == 8) return hash(lvl1, hash(hash(hash(me, lvl4), lvl3), lvl2));
        if (nibble == 9) return hash(lvl1, hash(hash(hash(lvl4, me), lvl3), lvl2));
        if (nibble == 10) return hash(lvl1, hash(hash(lvl3, hash(me, lvl4)), lvl2));
        if (nibble == 11) return hash(lvl1, hash(hash(lvl3, hash(lvl4, me)), lvl2));
        if (nibble == 12) return hash(lvl1, hash(lvl2, hash(hash(me, lvl4), lvl3)));
        if (nibble == 13) return hash(lvl1, hash(lvl2, hash(hash(lvl4, me), lvl3)));
        if (nibble == 14) return hash(lvl1, hash(lvl2, hash(lvl3, hash(me, lvl4))));
        return hash(lvl1, hash(lvl2, hash(lvl3, hash(lvl4, me))));
    }

    private static boolean hashLength(byte[] value) {
        return Builtins.lengthOfByteString(value) == 32;
    }

    private static byte[] hash(byte[] left, byte[] right) {
        return Builtins.blake2b_256(Builtins.appendByteString(left, right));
    }
}
