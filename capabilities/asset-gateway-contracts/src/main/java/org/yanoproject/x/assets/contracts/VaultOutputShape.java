package org.yanoproject.x.assets.contracts;

/**
 * The disjoint shapes of an output at a vault address (ADR-059 D5). The shape is decided by the
 * inline datum's Plutus-Data constructor alone, so the observer never resolves inputs.
 *
 * <p>Every vault datum is a Plutus-Data constructor encoded with its compact CBOR tag
 * ({@code 121 + index}, RFC 8949 tag head {@code d8 79..7f}):
 * <ul>
 *   <li>index 0, {@code d8 79}: {@link #DEPOSIT} ({@link DepositDatumV1});</li>
 *   <li>index 1, {@code d8 7a}: {@link #CONTINUATION} ({@code VaultContinuationV1(claimIds)}, M4);</li>
 *   <li>index 2, {@code d8 7b}: {@link #FLOOR_TOP_UP} ({@code FloorTopUpV1}, exactly
 *       {@code d8 7b 80} or {@code d8 7b 9f ff}: a constructor with no fields);</li>
 *   <li>index 3, {@code d8 7c}: {@link #MIGRATION_IN} ({@code MigrationInV1}, M4b).</li>
 * </ul>
 * Anything else, including no datum, a datum hash, a non-minimal tag head, the general
 * {@code tag 102} constructor form, or a {@code FloorTopUpV1} tag with fields, is {@link #OTHER}:
 * unattributed custody, never credited.
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
     * inline datum. Reads at most three bytes, so it is safe on any public L1 content.
     */
    public static VaultOutputShape classify(byte[] inlineDatum) {
        if (inlineDatum == null || inlineDatum.length < 3 || (inlineDatum[0] & 0xff) != 0xd8) {
            return OTHER;
        }
        return switch (inlineDatum[1] & 0xff) {
            case DEPOSIT_CONSTRUCTOR_TAG -> DEPOSIT;
            case CONTINUATION_CONSTRUCTOR_TAG -> CONTINUATION;
            case MIGRATION_IN_CONSTRUCTOR_TAG -> MIGRATION_IN;
            case FLOOR_TOP_UP_CONSTRUCTOR_TAG -> isEmptyFields(inlineDatum) ? FLOOR_TOP_UP : OTHER;
            default -> OTHER;
        };
    }

    /** The exact {@code FloorTopUpV1} datum: constructor 2 with no fields, definite form. */
    public static byte[] floorTopUpDatum() {
        return new byte[] {(byte) 0xd8, (byte) FLOOR_TOP_UP_CONSTRUCTOR_TAG, (byte) 0x80};
    }

    private static boolean isEmptyFields(byte[] datum) {
        return (datum.length == 3 && (datum[2] & 0xff) == 0x80)
                || (datum.length == 4 && (datum[2] & 0xff) == 0x9f && (datum[3] & 0xff) == 0xff);
    }
}
