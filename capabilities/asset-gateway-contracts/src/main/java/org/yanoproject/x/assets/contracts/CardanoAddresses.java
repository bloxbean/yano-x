package org.yanoproject.x.assets.contracts;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

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
 *
 * <p>The converse holds for payout <i>outputs</i>: the ledger accepts a pointer output whose naturals are padded, and
 * the script compares the shortest form it rebuilds, so such an output does pay a canonical claim. The payout
 * observer therefore reports a recipient as {@link #scriptRebuiltForm}, never as its raw output bytes (D10).
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
     * The bytes the vault script compares with a claim's recipient for an output paying {@code raw}. The script never
     * sees output address bytes: it rebuilds them from the ledger's Plutus view of the address
     * ({@code GatewayCodec.addressBytes}), and for a pointer address (header type 4 or 5) it writes slot, transaction
     * index and certificate index as shortest-form naturals ({@code GatewayCodec.varNat}). This applies the same
     * rebuild: a pointer's three naturals are decoded and re-encoded by {@link #varNat}, so a padded natural
     * ({@code 0x80 0x05} for 5) reads as its shortest form, and nothing after the third natural is kept, as the Plutus
     * view holds nothing else. Every other address, and a pointer whose naturals cannot be read or exceed 2^63, is
     * returned unchanged; for those the raw bytes are the rebuild (base and enterprise) or no rebuild can equal a
     * claim.
     */
    public static byte[] scriptRebuiltForm(byte[] raw) {
        if (raw == null || raw.length == 0) return raw;
        int type = (raw[0] & 0xff) >> 4;
        if ((type != 4 && type != 5) || raw.length <= 1 + HASH_BYTES) return raw.clone();
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        out.write(raw, 0, 1 + HASH_BYTES);
        int position = 1 + HASH_BYTES;
        for (int natural = 0; natural < 3; natural++) {
            long value = 0;
            int digit;
            do {
                if (position >= raw.length || value > (Long.MAX_VALUE >>> 7)) return raw.clone();
                digit = raw[position++] & 0xff;
                value = (value << 7) | (digit & 0x7f);
            } while ((digit & 0x80) != 0);
            out.writeBytes(varNat(value));
        }
        return out.toByteArray();
    }

    /**
     * A CIP-19 variable-length natural in its shortest form, exactly as the vault script's {@code GatewayCodec.varNat}
     * writes it: big-endian base-128 digits, the high bit set on every byte but the last.
     */
    public static byte[] varNat(long n) {
        if (n < 0) throw new IllegalArgumentException("a variable-length natural is non-negative");
        byte[] digits = new byte[10];
        int start = digits.length - 1;
        digits[start] = (byte) (n & 0x7f);
        for (long rest = n >>> 7; rest != 0; rest >>>= 7) {
            digits[--start] = (byte) (0x80 | (rest & 0x7f));
        }
        return Arrays.copyOfRange(digits, start, digits.length);
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
