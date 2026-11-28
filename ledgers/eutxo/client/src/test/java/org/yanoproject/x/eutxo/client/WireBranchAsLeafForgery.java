package org.yanoproject.x.eutxo.client;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.x.proofs.MpfNormalizedProof;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * The branch-read-as-conflicting-leaf forgery in the MPF wire format (proof
 * steps). The root's branch (cursor 0, skip {@code c - 1}) leads to a real branch at odd cursor {@code c} whose
 * {@code 2 + (63 - c) / 2}-nibble prefix starts with nibble 0, and under it sits the victim key's leaf: the
 * inclusion wire proves the key present. The forged exclusion wire replaces that branch by a terminal {@code Leaf}
 * step whose key hash shares the path above {@code c} and carries the branch prefix's nibbles as raw bytes, and
 * whose value hash is the branch's merkle root, so the excluded "leaf" hashes to the very same node.
 *
 * <p>{@link #ownLeafAt} is the other direction: the victim key's own leaf at odd cursor {@code c}, whose raw path
 * bytes are all below 16, read as the branch that remains after the key is removed, through a terminal {@code Fork}
 * step (skip 0, neighbor nibble 0, neighbor prefix the rest of the leaf suffix, neighbor root the value hash).
 */
final class WireBranchAsLeafForgery {
    final byte[] root;
    final byte[] key;
    final byte[] inclusionWire;
    final byte[] forgedExclusionWire;

    private WireBranchAsLeafForgery(byte[] root, byte[] key, byte[] inclusionWire, byte[] forgedExclusionWire) {
        this.root = root;
        this.key = key;
        this.inclusionWire = inclusionWire;
        this.forgedExclusionWire = forgedExclusionWire;
    }

    /** The forgery at odd cursor {@code c}, for a 32-byte key whose value is the key itself. */
    static WireBranchAsLeafForgery at(int c) {
        int length = 2 + (63 - c) / 2;
        byte[] key = null;
        byte[] path = null;
        for (int i = 0; ; i++) {
            key = Blake2bUtil.blake2bHash256(("victim/" + c + "/" + i).getBytes());
            path = MpfNormalizedProof.nibbles(Blake2bUtil.blake2bHash256(key));
            if (path[c] == 0 && path[c + 1] != 0) break;
        }
        byte[] prefix = Arrays.copyOfRange(path, c, c + length);
        byte[] leaf = hash(MpfNormalizedProof.encodeLeafSuffix(Arrays.copyOfRange(path, c + length + 1, 64)),
                Blake2bUtil.blake2bHash256(key));
        byte[] lowerRoot = Blake2bUtil.blake2bHash256(new byte[] {1});
        int lowerNibble = (path[c + length] + 1) % 16;
        byte[] branchMerkle = merkle(path[c + length], leaf, MpfNormalizedProof.sparseNeighbors(path[c + length],
                lowerNibble, Blake2bUtil.blake2bHash256(lowerRoot)));
        byte[] branch = hash(prefix, branchMerkle);
        byte[] upperRoot = Blake2bUtil.blake2bHash256(new byte[] {2});
        int upperNibble = (path[c - 1] + 1) % 16;
        byte[] root = hash(Arrays.copyOfRange(path, 0, c - 1), merkle(path[c - 1], branch,
                MpfNormalizedProof.sparseNeighbors(path[c - 1], upperNibble, Blake2bUtil.blake2bHash256(upperRoot))));
        byte[] keyHash = Blake2bUtil.blake2bHash256(key);
        byte[] conflicting = new byte[32];
        System.arraycopy(keyHash, 0, conflicting, 0, (c - 1) / 2);
        conflicting[(c - 1) / 2] = (byte) ((path[c - 1] << 4) | prefix[1]);
        System.arraycopy(prefix, 2, conflicting, (c + 1) / 2, length - 2);
        byte[] upperStep = fork(c - 1, upperNibble, new byte[0], upperRoot);
        byte[] inclusion = array(upperStep, fork(length, lowerNibble, new byte[0], lowerRoot));
        byte[] forged = array(upperStep, leafStep(0, conflicting, branchMerkle));
        return new WireBranchAsLeafForgery(root, key, inclusion, forged);
    }

    /**
     * The own-leaf forgery at odd cursor {@code c}: the key needs every path byte from {@code (c + 1) / 2} on below
     * 16 (about {@code 2^(4 (63 - c) / 2)} tries; a real trie needs about 2^84 work at the best cursor).
     */
    static WireBranchAsLeafForgery ownLeafAt(int c) {
        byte[] key = null;
        byte[] path = null;
        search:
        for (int i = 0; ; i++) {
            key = Blake2bUtil.blake2bHash256(("own-leaf/" + c + "/" + i).getBytes());
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
        byte[] upperRoot = Blake2bUtil.blake2bHash256(new byte[] {2});
        int upperNibble = (path[c - 1] + 1) % 16;
        byte[] root = hash(Arrays.copyOfRange(path, 0, c - 1), merkle(path[c - 1], leaf,
                MpfNormalizedProof.sparseNeighbors(path[c - 1], upperNibble, Blake2bUtil.blake2bHash256(upperRoot))));
        byte[] upperStep = fork(c - 1, upperNibble, new byte[0], upperRoot);
        byte[] forged = array(upperStep, fork(0, 0, Arrays.copyOfRange(leafSuffix, 1, leafSuffix.length), valueHash));
        return new WireBranchAsLeafForgery(root, key, array(upperStep), forged);
    }

    private static byte[] array(byte[]... steps) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80 + steps.length);
        for (byte[] step : steps) out.writeBytes(step);
        return out.toByteArray();
    }

    /** {@code 122([skip, 121([nibble, prefix, root])])}. */
    private static byte[] fork(int skip, int nibble, byte[] prefix, byte[] root) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xd8, 0x7a, (byte) 0x82});
        out.writeBytes(uint(skip));
        out.writeBytes(new byte[] {(byte) 0xd8, 0x79, (byte) 0x83});
        out.writeBytes(uint(nibble));
        out.write(0x40 + prefix.length);
        out.writeBytes(prefix);
        out.writeBytes(bytes(root));
        return out.toByteArray();
    }

    /** {@code 123([skip, keyHash, valueHash])}. */
    private static byte[] leafStep(int skip, byte[] keyHash, byte[] valueHash) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xd8, 0x7b, (byte) 0x83});
        out.writeBytes(uint(skip));
        out.writeBytes(bytes(keyHash));
        out.writeBytes(bytes(valueHash));
        return out.toByteArray();
    }

    private static byte[] uint(int n) {
        return n < 24 ? new byte[] {(byte) n} : new byte[] {0x18, (byte) n};
    }

    private static byte[] bytes(byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x58);
        out.write(value.length);
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static byte[] merkle(int nibble, byte[] child, List<byte[]> neighbors) {
        byte[] merkle = child;
        for (int level = 0; level < 4; level++) {
            byte[] sibling = neighbors.get(3 - level);
            merkle = ((nibble >> level) & 1) == 0 ? hash(merkle, sibling) : hash(sibling, merkle);
        }
        return merkle;
    }

    private static byte[] hash(byte[] left, byte[] right) {
        byte[] joined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        return Blake2bUtil.blake2bHash256(joined);
    }
}
