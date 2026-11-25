package org.yanoproject.x.framework.proof;

/**
 * Non-recursive structural preflight for the MPF proof CBOR consumed by the
 * upstream verifier. It accepts the serializer's definite-length v1 grammar
 * (plus legacy untagged branch steps) and rejects nested or trailing data
 * before a general-purpose recursive CBOR decoder sees untrusted bytes.
 *
 * <p>Proofs follow the Aiken library's canonical steps: a branch whose only
 * other child is a leaf is a {@code Leaf} step, wherever it is on the path.
 *
 * <p>{@link #acceptsExclusion} adds one semantic rule for non-membership proofs.
 * Leaf and branch commitments carry no domain tag: an odd leaf is
 * {@code blake2b-256(00 <nibble> <raw path bytes> ‖ valueHash)} and a branch
 * {@code blake2b-256(prefix nibbles ‖ merkle)}. A real branch on the key's own
 * path at an odd cursor, whose prefix starts with nibble 0, therefore has the
 * shape of an odd leaf, and a terminal {@code Leaf} step whose key hash carries
 * that prefix's nibbles as raw bytes would let the proof show a
 * present key absent. A terminal {@code Leaf} at an odd cursor must therefore
 * have a raw key byte above 15 (branch prefixes are all nibbles). An honest
 * leaf fails with probability {@code 16^-((63 - cursor) / 2)}, which depends on
 * the depth and is negligible at any reachable one.
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

    private MpfProofWirePreflight(byte[] wire) {
        this.wire = wire;
    }

    public static boolean accepts(byte[] wire) {
        return parse(wire) >= -1;
    }

    /**
     * {@link #accepts}, plus the rule for a non-membership proof: a terminal
     * {@code Leaf} step at an odd cursor must have a raw key byte above 15, so a
     * branch on the key's own path cannot pass as the conflicting leaf.
     */
    public static boolean acceptsExclusion(byte[] wire) {
        return parse(wire) == -1;
    }

    /**
     * -2 when the wire is malformed; -1 when it is well formed and the exclusion
     * rule holds; 0 when it is well formed but its terminal leaf reads as a branch.
     */
    private static int parse(byte[] wire) {
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
            return steps == 0 || parser.lastTag != TAG_LEAF || lastCursor % 2 == 0
                    || parser.hasRawByteAbove15(parser.lastKeyStart + (lastCursor + 1) / 2,
                    parser.lastKeyStart + HASH_BYTES) ? -1 : 0;
        } catch (IllegalArgumentException malformed) {
            return -2;
        }
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
            if (readUnsigned(0) > 15) {
                throw malformed();
            }
            readNibbleByteString(remainingNibbles - skip - 1);
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

    private void readNibbleByteString(int maximumLength) {
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
