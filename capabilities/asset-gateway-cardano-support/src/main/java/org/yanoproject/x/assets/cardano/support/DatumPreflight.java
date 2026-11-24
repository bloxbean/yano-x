package org.yanoproject.x.assets.cardano.support;

/**
 * Bounds an inline datum read from L1 before anything decodes it (ADR-059 D5 datum bounds,
 * ADR-042). Anyone can attach any datum to an output at a public vault address; a recursive
 * decoder can overflow the stack on deep nesting that fits in one transaction, and a
 * {@link StackOverflowError} would stop L1 observation for the chain. This check walks the CBOR
 * structure iteratively, never recursing and never allocating per item, and accepts exactly one
 * well-formed item within the byte, depth and item bounds.
 */
public final class DatumPreflight {
    /** ADR-059 defaults: 16 KiB and depth 16. */
    public static final int DEFAULT_MAX_BYTES = 16_384;
    public static final int DEFAULT_MAX_DEPTH = 16;
    public static final int MAX_ITEMS = 8_192;

    private final int maxBytes;
    private final int maxDepth;

    public DatumPreflight(int maxBytes, int maxDepth) {
        if (maxBytes < 1 || maxBytes > 65_536 || maxDepth < 1 || maxDepth > 64) {
            throw new IllegalArgumentException("datum bounds must be 1..65536 bytes and depth 1..64");
        }
        this.maxBytes = maxBytes;
        this.maxDepth = maxDepth;
    }

    public static DatumPreflight defaults() {
        return new DatumPreflight(DEFAULT_MAX_BYTES, DEFAULT_MAX_DEPTH);
    }

    public int maxBytes() {
        return maxBytes;
    }

    public int maxDepth() {
        return maxDepth;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DatumPreflight that && maxBytes == that.maxBytes && maxDepth == that.maxDepth;
    }

    @Override
    public int hashCode() {
        return 31 * maxBytes + maxDepth;
    }

    @Override
    public String toString() {
        return "DatumPreflight[maxBytes=" + maxBytes + ", maxDepth=" + maxDepth + "]";
    }

    /** True when {@code data} is exactly one well-formed CBOR item inside the bounds. */
    public boolean accepts(byte[] data) {
        if (data == null || data.length == 0 || data.length > maxBytes) return false;
        // left[d]: items still expected in the container open at depth d (-1 = indefinite);
        // chunk[d]: for an indefinite string, the major type its chunks must have, else -1.
        long[] left = new long[maxDepth + 1];
        int[] chunk = new int[maxDepth + 1];
        left[0] = 1;
        chunk[0] = -1;
        int depth = 0;
        int pos = 0;
        int items = 0;
        while (true) {
            if (left[depth] == 0) {
                if (depth == 0) return pos == data.length;
                depth--;
                continue;
            }
            if (pos >= data.length) return false;
            int initial = data[pos++] & 0xff;
            if (initial == 0xff) {
                if (left[depth] != -1 || depth == 0) return false;
                depth--;
                continue;
            }
            if (left[depth] > 0) left[depth]--;
            if (++items > MAX_ITEMS) return false;
            int major = initial >>> 5;
            int info = initial & 0x1f;
            if (chunk[depth] >= 0 && (major != chunk[depth] || info == 31)) return false;
            long argument;
            boolean indefinite = false;
            if (info < 24) {
                argument = info;
            } else if (info <= 27) {
                int size = 1 << (info - 24);
                if (pos + size > data.length) return false;
                argument = 0;
                // An 8-byte argument >= 2^63 reads as negative here. That is fine for scalars
                // (uint64, large negatives, 64-bit tags, doubles); only a length or count that
                // large is out of range, and the string and container cases below reject it.
                for (int i = 0; i < size; i++) argument = (argument << 8) | (data[pos++] & 0xff);
            } else if (info == 31 && major >= 2 && major <= 5) {
                argument = -1;
                indefinite = true;
            } else {
                return false;
            }
            switch (major) {
                case 0, 1 -> {
                }
                case 2, 3 -> {
                    if (indefinite) {
                        if (depth + 1 > maxDepth) return false;
                        depth++;
                        left[depth] = -1;
                        chunk[depth] = major;
                    } else {
                        if (argument < 0 || argument > data.length - pos) return false;
                        pos += (int) argument;
                    }
                }
                case 4, 5 -> {
                    long count = indefinite ? -1 : (major == 5 ? argument * 2 : argument);
                    if (!indefinite && (argument < 0 || argument > data.length || count > data.length - pos)) {
                        return false;
                    }
                    if (depth + 1 > maxDepth) return false;
                    depth++;
                    left[depth] = count;
                    chunk[depth] = -1;
                }
                case 6 -> {
                    if (depth + 1 > maxDepth) return false;
                    depth++;
                    left[depth] = 1;
                    chunk[depth] = -1;
                }
                default -> {
                    // major 7: simple values and floats; their argument bytes were already consumed.
                }
            }
        }
    }
}
