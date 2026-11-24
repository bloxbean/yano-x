package org.yanoproject.x.assets.contracts;

/**
 * The disjoint shapes of an output at a vault address (ADR-059 D5). The shape is decided by the
 * inline datum's Plutus-Data constructor alone, so the observer never resolves inputs.
 *
 * <p>Every vault datum is a Plutus-Data constructor encoded with its compact CBOR tag
 * ({@code 121 + index}, RFC 8949 tag head {@code d8 79..7f}):
 * <ul>
 *   <li>index 0, {@code d8 79}: {@link #DEPOSIT} ({@link DepositDatumV1});</li>
 *   <li>index 1, {@code d8 7a}: {@link #CONTINUATION}: {@code VaultContinuationV1(claimIds)}, one
 *       field holding a list of at most {@value #MAX_CONTINUATION_CLAIMS} 32-byte claim ids (M4);</li>
 *   <li>index 2, {@code d8 7b}: {@link #FLOOR_TOP_UP}: {@code FloorTopUpV1}, exactly
 *       {@code d8 7b 80} or {@code d8 7b 9f ff}, a constructor with no fields;</li>
 *   <li>index 3, {@code d8 7c}: {@link #MIGRATION_IN}: {@code MigrationInV1(sourceVaultId, claimId)},
 *       two 32-byte fields (M4b).</li>
 * </ul>
 * A shape is recognised only when the whole datum has that constructor's exact field layout
 * (lists definite or indefinite, byte strings definite with a shortest-form head, no trailing
 * bytes); {@link DepositDatumV1#decode} applies the same rule to deposits, whose malformed
 * datums still classify as {@link #DEPOSIT} so the observer can report them as {@code MALFORMED}.
 * Anything else, including no datum, a datum hash, a non-minimal tag head, the general
 * {@code tag 102} constructor form, or a look-alike with the right tag and the wrong fields, is
 * {@link #OTHER}: unattributed custody, never credited.
 */
public enum VaultOutputShape {
    DEPOSIT(0),
    CONTINUATION(1),
    FLOOR_TOP_UP(2),
    MIGRATION_IN(3),
    OTHER(4);

    /** Plutus-Data compact constructor tags: 121 + constructor index. */
    public static final int DEPOSIT_CONSTRUCTOR_TAG = 121;
    public static final int CONTINUATION_CONSTRUCTOR_TAG = 122;
    public static final int FLOOR_TOP_UP_CONSTRUCTOR_TAG = 123;
    public static final int MIGRATION_IN_CONSTRUCTOR_TAG = 124;
    /** Bound on the claim ids one continuation datum may list. */
    public static final int MAX_CONTINUATION_CLAIMS = 64;

    private final int code;

    VaultOutputShape(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static VaultOutputShape fromCode(long code) {
        for (VaultOutputShape shape : values()) {
            if (shape.code == code) return shape;
        }
        throw new IllegalArgumentException("unknown vault output shape " + code);
    }

    /**
     * Classifies an inline datum by its constructor tag. {@code null} means the output has no
     * inline datum. The read is flat and bounded by the datum length, so it is safe on any public L1
     * content.
     */
    public static VaultOutputShape classify(byte[] inlineDatum) {
        if (inlineDatum == null || inlineDatum.length < 3 || (inlineDatum[0] & 0xff) != 0xd8) {
            return OTHER;
        }
        return switch (inlineDatum[1] & 0xff) {
            case DEPOSIT_CONSTRUCTOR_TAG -> DEPOSIT;
            case CONTINUATION_CONSTRUCTOR_TAG -> isContinuation(inlineDatum) ? CONTINUATION : OTHER;
            case MIGRATION_IN_CONSTRUCTOR_TAG -> isMigrationIn(inlineDatum) ? MIGRATION_IN : OTHER;
            case FLOOR_TOP_UP_CONSTRUCTOR_TAG -> isEmptyFields(inlineDatum) ? FLOOR_TOP_UP : OTHER;
            default -> OTHER;
        };
    }

    /** The exact {@code FloorTopUpV1} datum: constructor 2 with no fields, definite form. */
    public static byte[] floorTopUpDatum() {
        return new byte[] {(byte) 0xd8, (byte) FLOOR_TOP_UP_CONSTRUCTOR_TAG, (byte) 0x80};
    }

    /** {@code #6.122([[* bytes .size 32]])}: one field, a list of claim ids. */
    private static boolean isContinuation(byte[] datum) {
        FlatReader reader = new FlatReader(datum, 2);
        int fields = reader.listHeader();
        if (fields != 1 && fields != FlatReader.INDEFINITE) return false;
        int claims = reader.listHeader();
        if (claims == FlatReader.INVALID) return false;
        int count = 0;
        while (claims == FlatReader.INDEFINITE ? !reader.peekBreak() : count < claims) {
            if (++count > MAX_CONTINUATION_CLAIMS || !reader.hash32()) return false;
        }
        if (claims == FlatReader.INDEFINITE && !reader.breakByte()) return false;
        if (fields == FlatReader.INDEFINITE && !reader.breakByte()) return false;
        return reader.atEnd();
    }

    /** {@code #6.124([source_vault_id: bytes .size 32, claim_id: bytes .size 32])}. */
    private static boolean isMigrationIn(byte[] datum) {
        FlatReader reader = new FlatReader(datum, 2);
        int fields = reader.listHeader();
        if (fields != 2 && fields != FlatReader.INDEFINITE) return false;
        if (!reader.hash32() || !reader.hash32()) return false;
        if (fields == FlatReader.INDEFINITE && !reader.breakByte()) return false;
        return reader.atEnd();
    }

    /** A bounded, non-recursive cursor for the flat datum layouts above. */
    private static final class FlatReader {
        static final int INVALID = -2;
        static final int INDEFINITE = -1;
        private final byte[] input;
        private int position;

        FlatReader(byte[] input, int start) {
            this.input = input;
            this.position = start;
        }

        /** A list head: its definite length (shortest form), {@link #INDEFINITE}, or {@link #INVALID}. */
        int listHeader() {
            if (position >= input.length) return INVALID;
            int head = input[position++] & 0xff;
            if (head == 0x9f) return INDEFINITE;
            if (head >= 0x80 && head <= 0x97) return head - 0x80;
            if (head == 0x98 && position < input.length) {
                int length = input[position++] & 0xff;
                return length >= 24 ? length : INVALID;
            }
            return INVALID;
        }

        boolean hash32() {
            if (position + 34 > input.length || (input[position] & 0xff) != 0x58
                    || (input[position + 1] & 0xff) != 0x20) {
                return false;
            }
            position += 34;
            return true;
        }

        boolean peekBreak() {
            return position < input.length && (input[position] & 0xff) == 0xff;
        }

        boolean breakByte() {
            if (!peekBreak()) return false;
            position++;
            return true;
        }

        boolean atEnd() {
            return position == input.length;
        }
    }

    private static boolean isEmptyFields(byte[] datum) {
        return (datum.length == 3 && (datum[2] & 0xff) == 0x80)
                || (datum.length == 4 && (datum[2] & 0xff) == 0x9f && (datum[3] & 0xff) == 0xff);
    }
}
