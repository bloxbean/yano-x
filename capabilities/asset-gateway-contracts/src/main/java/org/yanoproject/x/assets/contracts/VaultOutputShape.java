package org.yanoproject.x.assets.contracts;

import org.yanoproject.x.assets.contracts.internal.PlutusDataReader;

import java.util.List;

/**
 * The disjoint shapes of an output at a vault address (ADR-059 D5). The shape is decided by the
 * inline datum alone, so the observer never resolves inputs.
 *
 * <p>Every vault datum is Plutus Data, classified by its <b>decoded</b> structure, which is all a
 * validator ever sees: the vault script accepts {@code VaultContinuationV1} and
 * {@code FloorTopUpV1} custody, and treats constructor 0 as deposit-shaped, whatever CBOR
 * serialization the output used. Constructors are read from any form Plutus accepts (compact tags
 * {@code 121..127} and {@code 1280..1400}, or the general {@code 102 [index, fields]}), with
 * definite or indefinite lists and byte strings:
 * <ul>
 *   <li>constructor 0: {@link #DEPOSIT} ({@link DepositDatumV1}; malformed fields still classify
 *       as a deposit, which the observer reports as {@code MALFORMED});</li>
 *   <li>constructor 1 with one field, a list of at most {@value #MAX_CONTINUATION_CLAIMS} 32-byte
 *       claim ids: {@link #CONTINUATION} ({@code VaultContinuationV1(claimIds)}, M4);</li>
 *   <li>constructor 2 with no fields: {@link #FLOOR_TOP_UP} ({@code FloorTopUpV1});</li>
 *   <li>constructor 3 with two 32-byte fields: {@link #MIGRATION_IN}
 *       ({@code MigrationInV1(sourceVaultId, claimId)}, M4b).</li>
 * </ul>
 * Anything else, including no datum, a datum hash, bytes that are not Plutus Data, and a
 * look-alike with the right constructor and the wrong fields, is {@link #OTHER}: unattributed
 * custody, never credited. One exception keeps deposit attempts visible: bytes the bounded reader
 * cannot decode (too deep, too long, or not Plutus Data) that start with the compact constructor-0
 * head {@code d8 79} are {@link #DEPOSIT}, which the observer records as malformed. The vault validator's {@code acceptedDatum} applies the same rules to
 * continuations and top-ups ({@code GatewayShapeCrossTest} feeds both the same bytes).
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
     * Classifies an inline datum by its decoded Plutus Data structure. {@code null} means the
     * output has no inline datum. The read is bounded ({@link PlutusDataReader}), so it is safe on
     * any public L1 content.
     */
    public static VaultOutputShape classify(byte[] inlineDatum) {
        if (!(PlutusDataReader.read(inlineDatum) instanceof PlutusDataReader.Constr constr)) {
            // Beyond the reader's bounds (or not Plutus Data at all), a compact constructor-0 head still marks a
            // deposit attempt, which the observer records as malformed and never credits.
            return inlineDatum != null && inlineDatum.length >= 2 && (inlineDatum[0] & 0xff) == 0xd8
                    && (inlineDatum[1] & 0xff) == DEPOSIT_CONSTRUCTOR_TAG ? DEPOSIT : OTHER;
        }
        List<Object> fields = constr.fields();
        if (constr.index() == 0) return DEPOSIT;
        if (constr.index() == 1) {
            return fields.size() == 1 && fields.getFirst() instanceof List<?> ids
                    && ids.size() <= MAX_CONTINUATION_CLAIMS && ids.stream().allMatch(VaultOutputShape::hash32)
                    ? CONTINUATION : OTHER;
        }
        if (constr.index() == 2) return fields.isEmpty() ? FLOOR_TOP_UP : OTHER;
        if (constr.index() == 3) {
            return fields.size() == 2 && hash32(fields.get(0)) && hash32(fields.get(1)) ? MIGRATION_IN : OTHER;
        }
        return OTHER;
    }

    private static boolean hash32(Object value) {
        return value instanceof byte[] bytes && bytes.length == 32;
    }

    /** The exact {@code FloorTopUpV1} datum: constructor 2 with no fields, definite form. */
    public static byte[] floorTopUpDatum() {
        return new byte[] {(byte) 0xd8, (byte) FLOOR_TOP_UP_CONSTRUCTOR_TAG, (byte) 0x80};
    }
}
