package org.yanoproject.x.assets.contracts;

/**
 * Raw Shelley address rules shared by admission and claim reservation (ADR-059 D7, D8d).
 *
 * <p>The vault script never decodes an address: it rebuilds the raw bytes of a payout's Plutus address and compares
 * them with the claim's recipient. For a pointer address it writes slot, transaction index and certificate index as
 * CIP-19 variable-length naturals in their shortest form, so a pointer address with a padded natural (a leading
 * {@code 0x80} digit) or trailing bytes is never payable. A pointer is also canonical only within the Conway ledger's
 * component limits (slot below 2^32, transaction and certificate index below 2^16): outside them the ledger cannot
 * pay it. The ledger therefore rejects such a recipient at
 * reservation ({@link #payableRecipient}), deposit admission treats such a refund address as a malformed datum
 * ({@link DepositDatumV1}), and builders emit only the canonical form.
 */
public final class CardanoAddresses {
    private static final int HASH_BYTES = 28;
    private static final int MAX_NATURAL_BYTES = 9;
    /** Conway pointer limits: slot below 2^32, transaction and certificate index below 2^16. */
    private static final long MAX_SLOT = 1L << 32;
    private static final long MAX_INDEX = 1L << 16;

    private CardanoAddresses() {
    }

    /** True when {@code raw} is a pointer address (header type 4 or 5) that is not in canonical form. */
    public static boolean nonCanonicalPointer(byte[] raw) {
        if (raw == null || raw.length == 0) return false;
        int type = (raw[0] & 0xff) >> 4;
        return (type == 4 || type == 5) && !canonicalPointer(raw);
    }

    /**
     * True when a vault payout to {@code raw} can satisfy the script (D7 feasibility): a Shelley address on
     * {@code networkId} whose payment credential is a key, as a base address (types 0 and 2, 57 bytes), a canonical
     * pointer address (type 4) or an enterprise address (type 6, 29 bytes). Script, reward and Byron addresses, and
     * non-canonical pointers, are infeasible.
     */
    public static boolean payableRecipient(byte[] raw, int networkId) {
        if (raw == null || raw.length == 0 || (raw[0] & 0x0f) != networkId) return false;
        int type = (raw[0] & 0xff) >> 4;
        return switch (type) {
            case 0, 2 -> raw.length == 1 + 2 * HASH_BYTES;
            case 4 -> canonicalPointer(raw);
            case 6 -> raw.length == 1 + HASH_BYTES;
            default -> false;
        };
    }

    /**
     * Header, a 28-byte payment hash, then exactly three shortest-form variable-length naturals within the Conway
     * ledger's pointer limits (cardano-ledger {@code Address.hs}): slot below 2^32, transaction index and
     * certificate index below 2^16. A pointer outside them is never produced by the ledger as a payable address.
     */
    private static boolean canonicalPointer(byte[] raw) {
        int position = 1 + HASH_BYTES;
        for (int natural = 0; natural < 3; natural++) {
            int start = position;
            if (position >= raw.length || (raw[position] & 0xff) == 0x80) return false;
            long value = 0;
            while (position < raw.length && (raw[position] & 0x80) != 0) {
                value = value * 128 + (raw[position] & 0x7f);
                position++;
            }
            if (position >= raw.length || position - start + 1 > MAX_NATURAL_BYTES) return false;
            value = value * 128 + (raw[position] & 0x7f);
            position++;
            if (value >= (natural == 0 ? MAX_SLOT : MAX_INDEX)) return false;
        }
        return position == raw.length;
    }
}
