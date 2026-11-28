package org.yanoproject.x.framework.proof;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

/**
 * Non-recursive structural preflight for the MPF proof CBOR consumed by the
 * upstream verifier. It accepts the serializer's definite-length v1 grammar
 * (plus legacy untagged branch steps) and rejects nested or trailing data
 * before a general-purpose recursive CBOR decoder sees untrusted bytes.
 *
 * <p>Proofs follow the Aiken library's canonical steps: a branch whose only
 * other child is a leaf is a {@code Leaf} step, wherever it is on the path.
 *
 * <p>{@link #acceptsExclusion} adds two semantic rules for non-membership proofs.
 * Leaf and branch commitments carry no domain tag: an odd leaf is
 * {@code blake2b-256(00 <nibble> <raw path bytes> ‖ valueHash)} and a branch
 * {@code blake2b-256(prefix nibbles ‖ merkle)}, so the two shapes meet when the
 * leaf's raw bytes are all below 16.
 * <ul>
 *   <li>A real branch on the key's own path at an odd cursor, whose prefix starts
 *       with nibble 0, has the shape of an odd leaf, and a terminal {@code Leaf}
 *       step whose key hash carries that prefix's nibbles as raw bytes would let
 *       the proof show a present key absent. A terminal {@code Leaf}
 *       at an odd cursor must therefore have a raw key byte above 15. An honest
 *       leaf fails with probability {@code 16^-((63 - cursor) / 2)}.</li>
 *   <li>Conversely the present key's own odd leaf, with raw bytes all below 16,
 *       has the shape of a branch, and a terminal {@code Fork} step reconstructs
 *       the node {@code blake2b-256(path[cursor, cursor + skip) ++ nibble ++
 *       neighbor prefix ‖ neighbor root)} exactly as exclusion
 *       verification does. At an odd cursor that prefix must not start
 *       with nibble 0 at exactly the odd-leaf length {@code 2 + (63 - cursor) / 2}.
 *       An honest branch of that shape ends at nibble {@code (cursor + 67) / 2},
 *       so two of its keys share at least 34 nibbles.</li>
 * </ul>
 * Both are negligible for honest proofs at any reachable depth.
 */
public final class MpfProofWirePreflight {
    private static final long TAG_BRANCH = 121;
    private static final long TAG_FORK = 122;
    private static final long TAG_LEAF = 123;
    private static final int HASH_BYTES = 32;
    private static final int BRANCH_NEIGHBOR_BYTES = 4 * HASH_BYTES;
    private static final int MAX_PATH_NIBBLES = 2 * HASH_BYTES;

    private final byte[] wire;
    private int offset;
    private long lastTag = -2;
    private int lastKeyStart = -1;
    private int lastSkip = -1;
    private int lastForkNibble = -1;
    private int lastForkPrefixLength = -1;

    private MpfProofWirePreflight(byte[] wire) {
        this.wire = wire;
    }

    public static boolean accepts(byte[] wire) {
        return parse(wire, null) >= -1;
    }

    /**
     * {@link #accepts}, plus the rules for a non-membership proof of {@code key}
     * (the raw key; its path is {@code blake2b-256(key)}, as upstream): a terminal
     * {@code Leaf} step at an odd cursor must have a raw key byte above 15, so a
     * branch on the key's own path cannot pass as the conflicting leaf, and a
     * terminal {@code Fork} step at an odd cursor must not reconstruct an odd-leaf
     * shaped prefix, so the key's own leaf cannot pass as the remaining branch.
     */
    public static boolean acceptsExclusion(byte[] wire, byte[] key) {
        if (key == null) {
            return false;
        }
        return parse(wire, Blake2bUtil.blake2bHash256(key)) == -1;
    }

    /**
     * -2 when the wire is malformed; -1 when it is well formed and the exclusion
     * rules hold (or {@code path} is null); 0 when it is well formed but its
     * terminal step reads as the other node kind.
     */
    private static int parse(byte[] wire, byte[] path) {
        if (wire == null || wire.length == 0) {
            return -2;
        }
        try {
            MpfProofWirePreflight parser = new MpfProofWirePreflight(wire);
            int steps = parser.readContainerLength(4);
            if (steps > MAX_PATH_NIBBLES) {
                return -2;
            }
            int consumedNibbles = 0;
            int lastCursor = 0;
            for (int index = 0; index < steps; index++) {
                int remainingNibbles = MAX_PATH_NIBBLES - consumedNibbles;
                lastCursor = consumedNibbles;
                int skip = parser.readStep(remainingNibbles);
                consumedNibbles += 1 + skip;
            }
            if (parser.offset != wire.length) {
                return -2;
            }
            if (path == null || steps == 0 || lastCursor % 2 == 0) {
                return -1;
            }
            if (parser.lastTag == TAG_LEAF) {
                return parser.hasRawByteAbove15(parser.lastKeyStart + (lastCursor + 1) / 2,
                        parser.lastKeyStart + HASH_BYTES) ? -1 : 0;
            }
            if (parser.lastTag == TAG_FORK) {
                int first = parser.lastSkip == 0 ? parser.lastForkNibble : nibble(path, lastCursor);
                int length = parser.lastSkip + 1 + parser.lastForkPrefixLength;
                return first == 0 && length == 2 + (MAX_PATH_NIBBLES - 1 - lastCursor) / 2 ? 0 : -1;
            }
            return -1;
        } catch (IllegalArgumentException malformed) {
            return -2;
        }
    }

    private static int nibble(byte[] path, int index) {
        int value = path[index / 2] & 0xff;
        return index % 2 == 0 ? value >>> 4 : value & 0x0f;
    }

    private boolean hasRawByteAbove15(int from, int to) {
        for (int index = from; index < to; index++) {
            if ((wire[index] & 0xff) > 15) {
                return true;
            }
        }
        return false;
    }

    private int readStep(int remainingNibbles) {
        long tag = readOptionalTag();
        lastTag = tag;
        lastSkip = -1;
        lastForkNibble = -1;
        lastForkPrefixLength = -1;
        int fields = readContainerLength(4);
        if (tag == -1 || tag == TAG_BRANCH) {
            if (fields != 2 && fields != 3) {
                throw malformed();
            }
            int skip = readSkip(remainingNibbles);
            readFixedByteString(BRANCH_NEIGHBOR_BYTES);
            if (fields == 3) {
                readFixedByteString(HASH_BYTES);
            }
            return skip;
        }
        if (tag == TAG_FORK) {
            if (fields != 2) {
                throw malformed();
            }
            int skip = readSkip(remainingNibbles);
            long neighborTag = readOptionalTag();
            if (neighborTag != TAG_BRANCH) {
                throw malformed();
            }
            if (readContainerLength(4) != 3) {
                throw malformed();
            }
            long nibble = readUnsigned(0);
            if (nibble > 15) {
                throw malformed();
            }
            lastSkip = skip;
            lastForkNibble = (int) nibble;
            lastForkPrefixLength = readNibbleByteString(remainingNibbles - skip - 1);
            readFixedByteString(HASH_BYTES);
            return skip;
        }
        if (tag == TAG_LEAF) {
            if (fields != 3) {
                throw malformed();
            }
            int skip = readSkip(remainingNibbles);
            lastKeyStart = readFixedByteString(HASH_BYTES);
            readFixedByteString(HASH_BYTES);
            return skip;
        }
        throw malformed();
    }

    private int readSkip(int remainingNibbles) {
        long skip = readUnsigned(0);
        if (skip >= remainingNibbles) {
            throw malformed();
        }
        return (int) skip;
    }

    private long readOptionalTag() {
        if (offset >= wire.length || ((wire[offset] & 0xff) >>> 5) != 6) {
            return -1;
        }
        return readUnsigned(6);
    }

    private int readContainerLength(int expectedMajor) {
        long length = readUnsigned(expectedMajor);
        if (length > Integer.MAX_VALUE) {
            throw malformed();
        }
        return (int) length;
    }

    /** Reads a byte string of exactly {@code expectedLength} bytes; returns where its content starts. */
    private int readFixedByteString(int expectedLength) {
        long length = readUnsigned(2);
        if (length != expectedLength || length > wire.length - offset) {
            throw malformed();
        }
        int start = offset;
        offset += (int) length;
        return start;
    }

    /** Reads a byte string of at most {@code maximumLength} nibbles; returns its length. */
    private int readNibbleByteString(int maximumLength) {
        long length = readUnsigned(2);
        if (length > maximumLength || length > wire.length - offset) {
            throw malformed();
        }
        int end = offset + (int) length;
        while (offset < end) {
            if (next() > 15) {
                throw malformed();
            }
        }
        return (int) length;
    }

    private long readUnsigned(int expectedMajor) {
        int initial = next();
        int major = initial >>> 5;
        int additional = initial & 0x1f;
        if (major != expectedMajor || additional >= 28) {
            throw malformed();
        }
        if (additional < 24) {
            return additional;
        }
        int bytes = switch (additional) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw malformed();
        };
        if (bytes > wire.length - offset) {
            throw malformed();
        }
        long value = 0;
        for (int index = 0; index < bytes; index++) {
            int next = next();
            if (index == 0 && bytes == 8 && (next & 0x80) != 0) {
                throw malformed();
            }
            value = (value << 8) | next;
        }
        if ((bytes == 1 && value < 24)
                || (bytes == 2 && value <= 0xffL)
                || (bytes == 4 && value <= 0xffffL)
                || (bytes == 8 && value <= 0xffff_ffffL)) {
            throw malformed();
        }
        return value;
    }

    private int next() {
        if (offset >= wire.length) {
            throw malformed();
        }
        return wire[offset++] & 0xff;
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("invalid MPF proof wire");
    }
}
