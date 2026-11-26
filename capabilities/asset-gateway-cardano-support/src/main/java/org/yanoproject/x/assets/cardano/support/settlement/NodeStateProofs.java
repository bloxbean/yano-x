package org.yanoproject.x.assets.cardano.support.settlement;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Tag;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.x.proofs.MpfNormalizedProof;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * State proofs for {@code Exit} and proved {@code Accept}, served by the node's proof service at the referenced
 * anchor's height (ADR-059 D8b, D9 step 2). A node keeps proofs from the latest L1-stable anchor height onward (the
 * proof floor), so a relayer that references that anchor can always get them.
 *
 * <p>{@link #at} binds a {@link StateProof.Source} to one anchored {@code (height, root)}: every native proof it hands
 * out is an inclusion proof of the requested key at that height, under that root, and its normalized folds
 * reconstruct the root ({@link #normalize}). Anything else reads as unavailable, so the caller builds nothing.
 */
public final class NodeStateProofs {
    private static final long TAG_BRANCH = 121;
    private static final long TAG_FORK = 122;
    private static final long TAG_LEAF = 123;

    private NodeStateProofs() {
    }

    /**
     * A node's native {@code mpf-blake2b256-v1} inclusion proof of {@code key} at {@code height}: the committed value,
     * the root it is proved under, and the proof wire (the canonical CBOR step array).
     */
    public record NativeProof(long height, byte[] stateRoot, byte[] key, byte[] value, byte[] wire) {
        public NativeProof {
            stateRoot = Objects.requireNonNull(stateRoot, "state root").clone();
            key = Objects.requireNonNull(key, "key").clone();
            value = Objects.requireNonNull(value, "value").clone();
            wire = Objects.requireNonNull(wire, "wire").clone();
        }

        @Override
        public byte[] stateRoot() {
            return stateRoot.clone();
        }

        @Override
        public byte[] key() {
            return key.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public byte[] wire() {
            return wire.clone();
        }
    }

    /** The node's proof service: the inclusion proof of a key at a finalized height, while it is retained. */
    @FunctionalInterface
    public interface AtHeight {
        Optional<NativeProof> inclusion(long height, byte[] key);
    }

    /**
     * The proofs under the anchored state {@code (height, root)}: each is the node's proof of the key at exactly that
     * height and root, normalized and checked to reconstruct the root. A node that has none (pruned, not yet
     * committed, absent key) or answers another height, root or key yields empty.
     */
    public static StateProof.Source at(long height, byte[] root, AtHeight node) {
        byte[] anchoredRoot = root.clone();
        return key -> {
            Optional<NativeProof> proof;
            try {
                proof = node.inclusion(height, key);
            } catch (RuntimeException unavailable) {
                return Optional.empty();
            }
            return proof.filter(p -> p.height() == height && Arrays.equals(p.stateRoot(), anchoredRoot)
                    && Arrays.equals(p.key(), key)).flatMap(p -> {
                try {
                    return Optional.of(normalize(p));
                } catch (IllegalArgumentException invalid) {
                    return Optional.empty();
                }
            });
        };
    }

    /**
     * The on-chain form of a native inclusion proof: the leaf's encoded suffix and the folds from the leaf up to the
     * root, as {@code MpfOnChainVerifier} reads them. The result must reconstruct {@code proof.stateRoot()}, or this
     * throws.
     */
    public static StateProof normalize(NativeProof proof) {
        byte[] key = proof.key();
        byte[] path = MpfNormalizedProof.nibbles(Blake2bUtil.blake2bHash256(key));
        List<DataItem> decoded;
        try {
            decoded = new CborDecoder(new ByteArrayInputStream(proof.wire())).decode();
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid MPF proof CBOR", failure);
        }
        if (decoded.size() != 1 || !(decoded.getFirst() instanceof Array steps)
                || steps.getDataItems().size() > MpfNormalizedProof.MAX_FOLDS) {
            throw new IllegalArgumentException("MPF proof must be one step array of at most "
                    + MpfNormalizedProof.MAX_FOLDS + " steps");
        }
        int cursor = 0;
        List<MpfNormalizedProof.FoldStep> forward = new ArrayList<>();
        for (DataItem item : steps.getDataItems()) {
            if (!(item instanceof Array step)) throw new IllegalArgumentException("MPF proof step must be an array");
            long tag = tag(step);
            int skip = uint(step, 0);
            int next = Math.addExact(cursor, Math.addExact(skip, 1));
            if (next > MpfNormalizedProof.PATH_NIBBLES) {
                throw new IllegalArgumentException("MPF proof consumes beyond the hashed key path");
            }
            byte[] prefix = Arrays.copyOfRange(path, cursor, next - 1);
            int nibble = path[next - 1] & 0xff;
            List<byte[]> neighbors;
            byte[] branchValueHash = new byte[0];
            if (tag == TAG_BRANCH) {
                int size = step.getDataItems().size();
                if (size != 2 && size != 3) throw new IllegalArgumentException("MPF branch step has the wrong shape");
                byte[] joined = bytes(step.getDataItems().get(1), 4 * MpfNormalizedProof.HASH_BYTES);
                neighbors = new ArrayList<>(4);
                for (int i = 0; i < 4; i++) {
                    neighbors.add(Arrays.copyOfRange(joined, i * MpfNormalizedProof.HASH_BYTES,
                            (i + 1) * MpfNormalizedProof.HASH_BYTES));
                }
                if (size == 3) branchValueHash = bytes(step.getDataItems().get(2), MpfNormalizedProof.HASH_BYTES);
            } else if (tag == TAG_FORK) {
                if (step.getDataItems().size() != 2 || !(step.getDataItems().get(1) instanceof Array neighbor)
                        || tag(neighbor) != TAG_BRANCH || neighbor.getDataItems().size() != 3) {
                    throw new IllegalArgumentException("MPF fork step has the wrong shape");
                }
                int neighborNibble = uint(neighbor, 0);
                if (neighborNibble > 15 || neighborNibble == nibble) {
                    throw new IllegalArgumentException("MPF fork neighbor nibble is invalid");
                }
                byte[] neighborPrefix = bytes(neighbor.getDataItems().get(1), -1);
                for (byte value : neighborPrefix) {
                    if ((value & 0xff) > 15) throw new IllegalArgumentException("MPF fork prefix is not nibbles");
                }
                byte[] neighborRoot = bytes(neighbor.getDataItems().get(2), MpfNormalizedProof.HASH_BYTES);
                neighbors = MpfNormalizedProof.sparseNeighbors(nibble, neighborNibble,
                        Blake2bUtil.blake2bHash256(concat(neighborPrefix, neighborRoot)));
            } else if (tag == TAG_LEAF) {
                if (step.getDataItems().size() != 3) {
                    throw new IllegalArgumentException("MPF leaf step has the wrong shape");
                }
                byte[] keyHash = bytes(step.getDataItems().get(1), MpfNormalizedProof.HASH_BYTES);
                byte[] valueHash = bytes(step.getDataItems().get(2), MpfNormalizedProof.HASH_BYTES);
                byte[] neighborPath = MpfNormalizedProof.nibbles(keyHash);
                int neighborNibble = neighborPath[next - 1] & 0xff;
                if (neighborNibble == nibble) throw new IllegalArgumentException("MPF leaf neighbor nibble is invalid");
                byte[] leaf = MpfNormalizedProof.commitLeaf(MpfNormalizedProof.encodeLeafSuffix(
                        Arrays.copyOfRange(neighborPath, next, MpfNormalizedProof.PATH_NIBBLES)), valueHash);
                neighbors = MpfNormalizedProof.sparseNeighbors(nibble, neighborNibble, leaf);
            } else {
                throw new IllegalArgumentException("unsupported MPF proof step tag " + tag);
            }
            forward.add(new MpfNormalizedProof.FoldStep(cursor, prefix, nibble, neighbors, branchValueHash));
            cursor = next;
        }
        List<MpfNormalizedProof.FoldStep> folds = new ArrayList<>(forward);
        Collections.reverse(folds);
        MpfNormalizedProof normalized = new MpfNormalizedProof(proof.stateRoot(), key, proof.value(),
                MpfNormalizedProof.encodeLeafSuffix(Arrays.copyOfRange(path, cursor, MpfNormalizedProof.PATH_NIBBLES)),
                folds, Math.max(0, proof.height()));
        if (!normalized.verify()) {
            throw new IllegalArgumentException("the normalized MPF proof does not reconstruct its state root");
        }
        List<StateProof.Fold> out = new ArrayList<>();
        for (MpfNormalizedProof.FoldStep step : normalized.folds()) {
            out.add(new StateProof.Fold(step.cursor(), step.prefix(), step.nibble(), step.neighbors(),
                    step.branchValueHash()));
        }
        return new StateProof(normalized.key(), normalized.value(), normalized.leafSuffix(), out);
    }

    private static long tag(Array value) {
        Tag tag = value.getTag();
        return tag == null ? TAG_BRANCH : tag.getValue();
    }

    private static int uint(Array value, int index) {
        if (index >= value.getDataItems().size() || !(value.getDataItems().get(index) instanceof UnsignedInteger u)) {
            throw new IllegalArgumentException("MPF step field " + index + " must be an unsigned integer");
        }
        try {
            return u.getValue().intValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("MPF step field " + index + " exceeds integer range", failure);
        }
    }

    /** The bytes of {@code value}; of exactly {@code length} bytes unless {@code length} is negative. */
    private static byte[] bytes(DataItem value, int length) {
        if (!(value instanceof ByteString bytes) || (length >= 0 && bytes.getBytes().length != length)) {
            throw new IllegalArgumentException("MPF step field has the wrong bytes");
        }
        return bytes.getBytes();
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
