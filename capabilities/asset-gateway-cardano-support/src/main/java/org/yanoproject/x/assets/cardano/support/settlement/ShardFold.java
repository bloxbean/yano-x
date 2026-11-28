package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The shard validator's root fold, off chain (ADR-059 D8c, D8h §1 step 5): from the live shard root, every insert of
 * a shard redeemer must be absent under the running root (its exclusion proof folds to that root), and the root then
 * advances to the post-insert root of that proof. It is a port of the {@code including} and {@code excluding} walks
 * of the on-chain Merkle Patricia Forestry library ({@code AssetShardValidator} and {@code MerklePatriciaForestry} in
 * {@code asset-gateway-onchain}); the value of an id is the id itself. The member body check uses it to require the
 * shard continuation's datum root to be exactly the fold of the redeemer's inserts.
 *
 * <p>Before an exclusion root is accepted, each proof passes the script's own shape rules, {@code isWellFormed} and
 * {@code excludedTerminalIsUnambiguous} of the on-chain library ({@code miss}), so this fold accepts exactly the
 * proofs the shard validator accepts: a step whose shape the script rejects (a leaf that does not fork from the path
 * at its branch, a neighbor on the path's own branch) is refused here too, even when its roots would fold.
 */
final class ShardFold {
    private static final byte[] NULL_HASH = new byte[32];
    private static final byte[] NULL_HASH_2 = combine(NULL_HASH, NULL_HASH);
    private static final byte[] NULL_HASH_4 = combine(NULL_HASH_2, NULL_HASH_2);
    private static final byte[] NULL_HASH_8 = combine(NULL_HASH_4, NULL_HASH_4);

    private sealed interface Step permits Branch, Fork, Leaf {
        int skip();
    }

    private record Branch(int skip, byte[] neighbors) implements Step {
    }

    private record Fork(int skip, int nibble, byte[] prefix, byte[] root) implements Step {
    }

    private record Leaf(int skip, byte[] key, byte[] value) implements Step {
    }

    private ShardFold() {
    }

    /**
     * The root after the inserts of {@code shardRedeemer} from {@code root}, or empty when an id is malformed, routes
     * to another shard, or is not proved absent under the running root.
     */
    static Optional<byte[]> postInsertRoot(byte[] root, int shard, PlutusData shardRedeemer) {
        return fold(root, shard, shardRedeemer, true);
    }

    /** The fold, with the script's shape rules or (for tests that forge a shape) without them. */
    static Optional<byte[]> fold(byte[] root, int shard, PlutusData shardRedeemer, boolean checkShape) {
        try {
            byte[] running = root.clone();
            List<PlutusData> inserts = GatewayPlutus.listOf(
                    GatewayPlutus.constr(shardRedeemer, 0, 1).getData().getPlutusDataList().getFirst());
            if (inserts.isEmpty()) return Optional.empty();
            for (PlutusData insert : inserts) {
                List<PlutusData> fields = GatewayPlutus.constr(insert, 0, 2).getData().getPlutusDataList();
                byte[] id = GatewayPlutus.bytes(fields.get(0));
                if (id.length != 32 || (id[31] & 0x0f) != shard) return Optional.empty();
                List<Step> proof = steps(fields.get(1));
                byte[] path = Blake2bUtil.blake2bHash256(id);
                if (checkShape && (!wellFormed(path, 0, proof) || !unambiguousTerminal(path, 0, proof))) {
                    return Optional.empty();
                }
                if (!Arrays.equals(excluding(path, 0, proof), running)) return Optional.empty();
                running = including(path, Blake2bUtil.blake2bHash256(id), 0, proof);
            }
            return Optional.of(running);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static List<Step> steps(PlutusData proof) {
        List<Step> steps = new ArrayList<>();
        for (PlutusData item : GatewayPlutus.listOf(proof)) {
            ConstrPlutusData step = (ConstrPlutusData) item;
            List<PlutusData> f = step.getData().getPlutusDataList();
            int skip = GatewayPlutus.integer(f.get(0)).intValueExact();
            switch ((int) step.getAlternative()) {
                case 0 -> {
                    byte[] neighbors = GatewayPlutus.bytes(f.get(1));
                    if (f.size() != 2 || neighbors.length != 128) throw new IllegalArgumentException("branch step");
                    steps.add(new Branch(skip, neighbors));
                }
                case 1 -> {
                    List<PlutusData> n = GatewayPlutus.constr(f.get(1), 0, 3).getData().getPlutusDataList();
                    if (f.size() != 2) throw new IllegalArgumentException("fork step");
                    steps.add(new Fork(skip, GatewayPlutus.integer(n.get(0)).intValueExact(),
                            GatewayPlutus.bytes(n.get(1)), GatewayPlutus.bytes(n.get(2))));
                }
                case 2 -> {
                    if (f.size() != 3) throw new IllegalArgumentException("leaf step");
                    steps.add(new Leaf(skip, GatewayPlutus.bytes(f.get(1)), GatewayPlutus.bytes(f.get(2))));
                }
                default -> throw new IllegalArgumentException("unknown proof step");
            }
        }
        return steps;
    }

    // ------------------------------------------------------------------ the script's shape rules (miss)

    /** {@code isWellFormed}: every step's branch is below nibble 64 and each step has the shape of its kind. */
    private static boolean wellFormed(byte[] path, int cursor, List<Step> proof) {
        if (proof.isEmpty()) return true;
        Step step = proof.getFirst();
        List<Step> rest = proof.subList(1, proof.size());
        int skip = step.skip();
        if (skip < 0 || skip >= 64 - cursor) return false;
        int at = cursor + skip;
        return switch (step) {
            case Branch b -> b.neighbors().length == 128 && wellFormed(path, at + 1, rest);
            case Fork f -> f.nibble() >= 0 && f.nibble() <= 15 && f.nibble() != nibble(path, at)
                    && at + 1 + f.prefix().length < 64 && allNibbles(f.prefix(), 0) && f.root().length == 32
                    && wellFormed(path, at + 1, rest);
            case Leaf l -> l.key().length == 32 && l.value().length == 32 && forksAt(l.key(), path, at)
                    && wellFormed(path, at + 1, rest);
        };
    }

    /** {@code excludedTerminalIsUnambiguous}: the terminal step cannot also read as the other node kind. */
    private static boolean unambiguousTerminal(byte[] path, int cursor, List<Step> proof) {
        if (proof.isEmpty()) return true;
        Step step = proof.getFirst();
        List<Step> rest = proof.subList(1, proof.size());
        if (!rest.isEmpty()) return unambiguousTerminal(path, cursor + step.skip() + 1, rest);
        return switch (step) {
            case Branch b -> true;
            case Fork f -> {
                int first = f.skip() == 0 ? f.nibble() : nibble(path, cursor);
                yield cursor % 2 == 0 || first != 0 || f.skip() + 1 + f.prefix().length != 2 + (63 - cursor) / 2;
            }
            case Leaf l -> cursor % 2 == 0 || !allNibbles(l.key(), (cursor + 1) / 2);
        };
    }

    /** True when {@code key} shares nibbles {@code [0, at)} with {@code path} and differs from it at {@code at}. */
    private static boolean forksAt(byte[] key, byte[] path, int at) {
        int half = at / 2;
        boolean samePrefix = Arrays.equals(Arrays.copyOf(key, half), Arrays.copyOf(path, half))
                && (at % 2 == 0 || nibble(key, at - 1) == nibble(path, at - 1));
        return samePrefix && nibble(key, at) != nibble(path, at);
    }

    private static boolean allNibbles(byte[] bytes, int from) {
        for (int i = from; i < bytes.length; i++) {
            if ((bytes[i] & 0xff) > 15) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ the walks (MerklePatriciaForestry)

    private static byte[] including(byte[] path, byte[] value, int cursor, List<Step> proof) {
        if (proof.isEmpty()) return combine(suffix(path, cursor), value);
        Step step = proof.getFirst();
        List<Step> rest = proof.subList(1, proof.size());
        int next = cursor + step.skip();
        byte[] root = including(path, value, next + 1, rest);
        return switch (step) {
            case Branch b -> branch(path, cursor, next, root, b.neighbors());
            case Fork f -> fork(path, cursor, next, root, f.nibble(), f.prefix(), f.root());
            case Leaf l -> fork(path, cursor, next, root, nibble(l.key(), next), suffix(l.key(), next + 1), l.value());
        };
    }

    private static byte[] excluding(byte[] path, int cursor, List<Step> proof) {
        if (proof.isEmpty()) return NULL_HASH.clone();
        Step step = proof.getFirst();
        List<Step> rest = proof.subList(1, proof.size());
        if (rest.isEmpty()) {
            return switch (step) {
                case Branch b -> branch(path, cursor, cursor + b.skip(), NULL_HASH, b.neighbors());
                case Fork f -> {
                    byte[] neighborPrefix = cons(f.nibble(), f.prefix());
                    byte[] prefix = f.skip() == 0 ? neighborPrefix
                            : concat(nibbles(path, cursor, cursor + f.skip()), neighborPrefix);
                    yield combine(prefix, f.root());
                }
                case Leaf l -> combine(suffix(l.key(), cursor), l.value());
            };
        }
        int next = cursor + step.skip();
        byte[] root = excluding(path, next + 1, rest);
        return switch (step) {
            case Branch b -> branch(path, cursor, next, root, b.neighbors());
            case Fork f -> fork(path, cursor, next, root, f.nibble(), f.prefix(), f.root());
            case Leaf l -> fork(path, cursor, next, root, nibble(l.key(), next), suffix(l.key(), next + 1), l.value());
        };
    }

    private static byte[] branch(byte[] path, int cursor, int next, byte[] root, byte[] neighbors) {
        return combine(nibbles(path, cursor, next), merkle16(nibble(path, next), root,
                Arrays.copyOfRange(neighbors, 0, 32), Arrays.copyOfRange(neighbors, 32, 64),
                Arrays.copyOfRange(neighbors, 64, 96), Arrays.copyOfRange(neighbors, 96, 128)));
    }

    private static byte[] fork(byte[] path, int cursor, int next, byte[] root, int neighborNibble,
                               byte[] neighborPrefix, byte[] neighborRoot) {
        int branch = nibble(path, next);
        if (branch == neighborNibble) throw new IllegalArgumentException("a fork neighbor on the path's own branch");
        return combine(nibbles(path, cursor, next),
                sparseMerkle16(branch, root, neighborNibble, combine(neighborPrefix, neighborRoot)));
    }

    // ------------------------------------------------------------------ merkle placement

    private static byte[] merkle4(int branch, byte[] root, byte[] n2, byte[] n1) {
        return switch (branch) {
            case 0 -> combine(combine(root, n1), n2);
            case 1 -> combine(combine(n1, root), n2);
            case 2 -> combine(n2, combine(root, n1));
            default -> combine(n2, combine(n1, root));
        };
    }

    private static byte[] merkle8(int branch, byte[] root, byte[] n4, byte[] n2, byte[] n1) {
        return branch <= 3 ? combine(merkle4(branch, root, n2, n1), n4)
                : combine(n4, merkle4(branch - 4, root, n2, n1));
    }

    private static byte[] merkle16(int branch, byte[] root, byte[] n8, byte[] n4, byte[] n2, byte[] n1) {
        return branch <= 7 ? combine(merkle8(branch, root, n4, n2, n1), n8)
                : combine(n8, merkle8(branch - 8, root, n4, n2, n1));
    }

    private static byte[] sparseMerkle4(int me, byte[] meHash, int neighbor, byte[] neighborHash) {
        if (me <= 1) {
            if (neighbor <= 1) return combine(combineAt(meHash, neighborHash, me), NULL_HASH_2);
            return combine(combineAt(meHash, NULL_HASH, me), combineAt(neighborHash, NULL_HASH, neighbor));
        }
        if (neighbor >= 2) return combine(NULL_HASH_2, combineAt(meHash, neighborHash, me));
        return combine(combineAt(neighborHash, NULL_HASH, neighbor), combineAt(meHash, NULL_HASH, me));
    }

    private static byte[] sparseMerkle8(int me, byte[] meHash, int neighbor, byte[] neighborHash) {
        if (me <= 3) {
            if (neighbor <= 3) return combine(sparseMerkle4(me, meHash, neighbor, neighborHash), NULL_HASH_4);
            return combine(merkle4(me, meHash, NULL_HASH_2, NULL_HASH),
                    merkle4(neighbor - 4, neighborHash, NULL_HASH_2, NULL_HASH));
        }
        if (neighbor >= 4) return combine(NULL_HASH_4, sparseMerkle4(me - 4, meHash, neighbor - 4, neighborHash));
        return combine(merkle4(neighbor, neighborHash, NULL_HASH_2, NULL_HASH),
                merkle4(me - 4, meHash, NULL_HASH_2, NULL_HASH));
    }

    private static byte[] sparseMerkle16(int me, byte[] meHash, int neighbor, byte[] neighborHash) {
        if (me <= 7) {
            if (neighbor <= 7) return combine(sparseMerkle8(me, meHash, neighbor, neighborHash), NULL_HASH_8);
            return combine(merkle8(me, meHash, NULL_HASH_4, NULL_HASH_2, NULL_HASH),
                    merkle8(neighbor - 8, neighborHash, NULL_HASH_4, NULL_HASH_2, NULL_HASH));
        }
        if (neighbor >= 8) return combine(NULL_HASH_8, sparseMerkle8(me - 8, meHash, neighbor - 8, neighborHash));
        return combine(merkle8(neighbor, neighborHash, NULL_HASH_4, NULL_HASH_2, NULL_HASH),
                merkle8(me - 8, meHash, NULL_HASH_4, NULL_HASH_2, NULL_HASH));
    }

    // ------------------------------------------------------------------ byte helpers

    private static byte[] combine(byte[] left, byte[] right) {
        return Blake2bUtil.blake2bHash256(concat(left, right));
    }

    private static byte[] combineAt(byte[] hash, byte[] other, int position) {
        return position % 2 == 0 ? combine(hash, other) : combine(other, hash);
    }

    private static int nibble(byte[] bytes, int index) {
        int value = bytes[index / 2] & 0xff;
        return index % 2 == 0 ? value >>> 4 : value & 0x0f;
    }

    private static byte[] nibbles(byte[] path, int start, int end) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int index = start; index < end; index++) out.write(nibble(path, index));
        return out.toByteArray();
    }

    private static byte[] suffix(byte[] path, int cursor) {
        if (cursor % 2 == 0) return cons(0xff, Arrays.copyOfRange(path, cursor / 2, path.length));
        return cons(0, cons(nibble(path, cursor), Arrays.copyOfRange(path, (cursor + 1) / 2, path.length)));
    }

    private static byte[] cons(int head, byte[] tail) {
        byte[] out = new byte[tail.length + 1];
        out[0] = (byte) head;
        System.arraycopy(tail, 0, out, 1, tail.length);
        return out;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
