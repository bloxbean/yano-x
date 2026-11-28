package org.yanoproject.x.assets.contracts;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * The inline datum of a deposit output at a vault address (ADR-059 D5, D8f). It is an L1 datum,
 * so it is Plutus Data: constructor 0 with three byte-string fields.
 *
 * <pre>
 * deposit_datum_v1 = #6.121([ beneficiary_account: bytes .size 28,
 *                             refund_address: bytes .size (1..64),
 *                             refund_key_hash: bytes .size 28 ])
 * </pre>
 *
 * <p><b>Exact bytes produced by {@link #encode()}:</b> {@code d8 79} (tag 121), {@code 83}
 * (definite array of 3), then each field as a definite byte string with its shortest length head
 * ({@code 58 1c} for 28 bytes). Example: {@code d879 83 581c<28 bytes> 5839<57 bytes> 581c<28 bytes>}.
 *
 * <p><b>Accepted input.</b> A depositor's tool chooses the bytes, and Plutus Data has more than one
 * serialization of the same value. {@link #decode} therefore accepts the two forms Cardano tools
 * emit for this value: the field list as a definite array ({@code 83}) or an indefinite one
 * ({@code 9f … ff}), and each byte string as a definite string with a shortest-form head or an
 * indefinite chunked string ({@code 5f … ff}) of definite chunks. Everything else is rejected:
 * another tag head, the general {@code tag 102} constructor form, a non-shortest head, other
 * field types or counts, and trailing bytes. A refund address that is a pointer address with a
 * non-shortest variable-length natural or trailing bytes ({@link CardanoAddresses}) is rejected too,
 * so such a deposit is recorded as malformed and recovered by a governed sweep (D8d); the
 * constructor applies the same rule, so a builder can only emit the canonical form. The parser is
 * flat and iterative, so it is safe on any public L1 content.
 */
public record DepositDatumV1(byte[] beneficiaryAccount, byte[] refundAddress, byte[] refundKeyHash) {
    public static final int ACCOUNT_BYTES = 28;
    public static final int KEY_HASH_BYTES = 28;
    public static final int MAX_REFUND_ADDRESS_BYTES = 64;
    /** Bound on the encoded datum: three fields of at most 64 bytes plus framing. */
    public static final int MAX_ENCODED_BYTES = 512;

    public DepositDatumV1 {
        if (beneficiaryAccount == null || beneficiaryAccount.length != ACCOUNT_BYTES) {
            throw new IllegalArgumentException("beneficiary account must be 28 bytes");
        }
        if (refundAddress == null || refundAddress.length == 0
                || refundAddress.length > MAX_REFUND_ADDRESS_BYTES) {
            throw new IllegalArgumentException("refund address must be 1-64 bytes");
        }
        if (refundKeyHash == null || refundKeyHash.length != KEY_HASH_BYTES) {
            throw new IllegalArgumentException("refund key hash must be 28 bytes");
        }
        if (CardanoAddresses.nonCanonicalPointer(refundAddress)) {
            // The vault script rebuilds only the shortest pointer encoding, so such a refund could never pay.
            throw new IllegalArgumentException("refund address is a non-canonical pointer address");
        }
        beneficiaryAccount = beneficiaryAccount.clone();
        refundAddress = refundAddress.clone();
        refundKeyHash = refundKeyHash.clone();
    }

    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xd8);
        out.write(VaultOutputShape.DEPOSIT_CONSTRUCTOR_TAG);
        out.write(0x83);
        writeBytes(out, beneficiaryAccount);
        writeBytes(out, refundAddress);
        writeBytes(out, refundKeyHash);
        return out.toByteArray();
    }

    /** Decodes a deposit datum; throws {@link IllegalArgumentException} for anything else. */
    public static DepositDatumV1 decode(byte[] datum) {
        if (datum == null || datum.length < 4 || datum.length > MAX_ENCODED_BYTES) {
            throw malformed("deposit datum is empty or too large");
        }
        Reader reader = new Reader(datum);
        if (reader.next() != 0xd8 || reader.next() != VaultOutputShape.DEPOSIT_CONSTRUCTOR_TAG) {
            throw malformed("not a DepositDatumV1 constructor");
        }
        int listHead = reader.next();
        boolean indefinite;
        if (listHead == 0x83) {
            indefinite = false;
        } else if (listHead == 0x9f) {
            indefinite = true;
        } else {
            throw malformed("deposit datum must hold exactly three fields");
        }
        byte[] beneficiary = reader.byteString(ACCOUNT_BYTES);
        byte[] refundAddress = reader.byteString(MAX_REFUND_ADDRESS_BYTES);
        byte[] refundKeyHash = reader.byteString(KEY_HASH_BYTES);
        if (indefinite && reader.next() != 0xff) {
            throw malformed("deposit datum must hold exactly three fields");
        }
        if (!reader.atEnd()) throw malformed("trailing bytes after the deposit datum");
        try {
            return new DepositDatumV1(beneficiary, refundAddress, refundKeyHash);
        } catch (IllegalArgumentException invalid) {
            throw malformed(invalid.getMessage());
        }
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] value) {
        int length = value.length;
        if (length < 24) {
            out.write(0x40 | length);
        } else {
            out.write(0x58);
            out.write(length);
        }
        out.write(value, 0, value.length);
    }

    private static IllegalArgumentException malformed(String reason) {
        return new IllegalArgumentException("invalid DepositDatumV1: " + reason);
    }

    /** A bounded cursor over the datum bytes. */
    private static final class Reader {
        private final byte[] input;
        private int position;

        Reader(byte[] input) {
            this.input = input;
        }

        int next() {
            if (position >= input.length) throw malformed("truncated");
            return input[position++] & 0xff;
        }

        boolean atEnd() {
            return position == input.length;
        }

        /** One byte string of at most {@code maxLength} bytes: definite, or indefinite with definite chunks. */
        byte[] byteString(int maxLength) {
            int head = next();
            if (head == 0x5f) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                while (true) {
                    int chunkHead = next();
                    if (chunkHead == 0xff) break;
                    byte[] chunk = definite(chunkHead, maxLength - out.size());
                    out.write(chunk, 0, chunk.length);
                }
                return out.toByteArray();
            }
            return definite(head, maxLength);
        }

        private byte[] definite(int head, int maxLength) {
            if ((head >>> 5) != 2) throw malformed("expected a byte string");
            int info = head & 0x1f;
            int length;
            if (info < 24) {
                length = info;
            } else if (info == 24) {
                length = next();
                if (length < 24) throw malformed("non-shortest byte string length");
            } else {
                throw malformed("byte string too long");
            }
            if (length > maxLength || position + length > input.length) {
                throw malformed("byte string too long or truncated");
            }
            byte[] value = Arrays.copyOfRange(input, position, position + length);
            position += length;
            return value;
        }
    }

    @Override
    public byte[] beneficiaryAccount() {
        return beneficiaryAccount.clone();
    }

    @Override
    public byte[] refundAddress() {
        return refundAddress.clone();
    }

    @Override
    public byte[] refundKeyHash() {
        return refundKeyHash.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DepositDatumV1 that && Arrays.equals(encode(), that.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }
}
