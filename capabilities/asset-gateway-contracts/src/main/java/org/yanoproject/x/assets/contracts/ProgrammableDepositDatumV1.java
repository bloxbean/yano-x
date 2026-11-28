package org.yanoproject.x.assets.contracts;

import org.yanoproject.x.assets.contracts.internal.PlutusDataReader;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ADR-061 D6: the inline datum of a deposit into a CIP-113 vault.
 *
 * <pre>
 * ProgrammableDepositDatumV1 = Constr 0 [beneficiary: hash28, refund_address: bytes .size (1..64),
 *                                        refund_key_hash: hash28,
 *                                        origin: Constr 0 [origin_tx_id: hash32, origin_index: uint,
 *                                                          output_index: uint]]
 * </pre>
 *
 * <p>{@link #depositShaped} is the one total predicate that {@code Accept}, the sweep and the refunds apply on chain
 * ({@code ProgrammableVaultLib.depositShaped}) and that admission applies off chain: a constructor-0 datum of exactly
 * four fields that decode as above, each uint below 2^64, whose refund address is a key-owned smart wallet of the
 * pinned deployment ({@link CardanoAddresses#payableSmartWallet}). It judges the decoded Plutus Data, so any
 * encoding the ledger accepts (definite or indefinite lists, any constructor form) gives the same answer, and it
 * never throws.
 */
public record ProgrammableDepositDatumV1(byte[] beneficiary, byte[] refundAddress, byte[] refundKeyHash,
                                         Hash32 originTxId, long originIndex, long outputIndex) {
    public static final int HASH28 = 28;

    /** The shape of an inline datum at a CIP-113 vault, as admission and the custody scripts classify it. */
    public enum Shape {
        /** {@link #depositShaped}: a deposit attempt keyed by its origin. */
        DEPOSIT_SHAPED,
        /** A constructor-0 datum that is not deposit-shaped (a 3-field 059 datum, wrong arity or types, bad refund). */
        MALFORMED,
        /** Any other datum, or none. */
        OTHER
    }

    public ProgrammableDepositDatumV1 {
        requireLength(beneficiary, HASH28, "beneficiary");
        if (refundAddress == null || refundAddress.length < 1 || refundAddress.length > 64) {
            throw new IllegalArgumentException("refund address must be 1-64 bytes");
        }
        requireLength(refundKeyHash, HASH28, "refund key hash");
        Objects.requireNonNull(originTxId, "origin tx id");
        if (originIndex < 0 || outputIndex < 0) {
            throw new IllegalArgumentException("origin and output indexes are unsigned");
        }
        beneficiary = beneficiary.clone();
        refundAddress = refundAddress.clone();
        refundKeyHash = refundKeyHash.clone();
    }

    /** The deposit-shaped datum, or empty for any other datum. Total: never throws. */
    public static Optional<ProgrammableDepositDatumV1> depositShaped(byte[] inlineDatum, byte[] plbHash,
                                                                     int networkId) {
        return depositShaped(PlutusDataReader.read(inlineDatum), plbHash, networkId);
    }

    /** {@link #depositShaped} over an already decoded Plutus Data value. */
    public static Optional<ProgrammableDepositDatumV1> depositShaped(Object decoded, byte[] plbHash, int networkId) {
        if (!(decoded instanceof PlutusDataReader.Constr constr) || constr.index() != 0 || constr.fields().size() != 4) {
            return Optional.empty();
        }
        List<Object> f = constr.fields();
        if (!(f.get(0) instanceof byte[] beneficiary) || beneficiary.length != HASH28
                || !(f.get(1) instanceof byte[] refund)
                || !CardanoAddresses.payableSmartWallet(refund, plbHash, networkId)
                || !(f.get(2) instanceof byte[] refundKey) || refundKey.length != HASH28
                || !(f.get(3) instanceof PlutusDataReader.Constr origin) || origin.index() != 0
                || origin.fields().size() != 3
                || !(origin.fields().get(0) instanceof byte[] originTx) || originTx.length != Hash32.BYTES
                || !(origin.fields().get(1) instanceof BigInteger originIndex) || !uint64(originIndex)
                || !(origin.fields().get(2) instanceof BigInteger outputIndex) || !uint64(outputIndex)) {
            return Optional.empty();
        }
        // An index at or above 2^63 cannot name an input or an output; it is deposit-shaped but never origin-valid.
        long origin0 = originIndex.bitLength() > 63 ? Long.MAX_VALUE : originIndex.longValue();
        long output0 = outputIndex.bitLength() > 63 ? Long.MAX_VALUE : outputIndex.longValue();
        return Optional.of(new ProgrammableDepositDatumV1(beneficiary, refund, refundKey, Hash32.of(originTx),
                origin0, output0));
    }

    /** The shape of {@code inlineDatum} (null for none). */
    public static Shape classify(byte[] inlineDatum, byte[] plbHash, int networkId) {
        if (inlineDatum == null) return Shape.OTHER;
        Object decoded = PlutusDataReader.read(inlineDatum);
        if (depositShaped(decoded, plbHash, networkId).isPresent()) return Shape.DEPOSIT_SHAPED;
        return decoded instanceof PlutusDataReader.Constr constr && constr.index() == 0 ? Shape.MALFORMED : Shape.OTHER;
    }

    private static boolean uint64(BigInteger value) {
        return value.signum() >= 0 && value.bitLength() <= 64;
    }

    /** The origin-keyed {@code DepositId} in {@code vaultId}. */
    public Hash32 depositId(Hash32 vaultId) {
        return GatewayIds.programmableDepositId(vaultId, originTxId, originIndex, outputIndex);
    }

    /** True when the origin is {@code inputs}' member and {@code createdAt} is the output's own index (D6 rule 1). */
    public boolean originSpent(List<Outpoint> inputs, long createdAt) {
        if (outputIndex != createdAt) return false;
        for (Outpoint input : inputs) {
            if (input.txId().equals(originTxId) && input.index() == originIndex) return true;
        }
        return false;
    }

    /** A transaction input. */
    public record Outpoint(Hash32 txId, long index) {
        public Outpoint {
            Objects.requireNonNull(txId, "tx id");
            if (index < 0) throw new IllegalArgumentException("index is unsigned");
        }
    }

    /** The canonical Plutus Data CBOR of this datum (definite lists, compact constructor tags). */
    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(160);
        out.writeBytes(new byte[] {(byte) 0xd8, 0x79, (byte) 0x84});
        bytes(out, beneficiary);
        bytes(out, refundAddress);
        bytes(out, refundKeyHash);
        out.writeBytes(new byte[] {(byte) 0xd8, 0x79, (byte) 0x83});
        bytes(out, originTxId.bytes());
        out.writeBytes(cborUInt(originIndex));
        out.writeBytes(cborUInt(outputIndex));
        return out.toByteArray();
    }

    private static void bytes(ByteArrayOutputStream out, byte[] value) {
        if (value.length < 24) {
            out.write(0x40 + value.length);
        } else {
            out.write(0x58);
            out.write(value.length);
        }
        out.writeBytes(value);
    }

    static byte[] cborUInt(long n) {
        if (n < 24) return new byte[] {(byte) n};
        if (n < 256) return new byte[] {0x18, (byte) n};
        if (n < 65_536) return new byte[] {0x19, (byte) (n >> 8), (byte) n};
        if (n < 4_294_967_296L) {
            return new byte[] {0x1a, (byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) n};
        }
        byte[] out = new byte[9];
        out[0] = 0x1b;
        for (int i = 0; i < 8; i++) out[8 - i] = (byte) (n >>> (8 * i));
        return out;
    }

    private static void requireLength(byte[] value, int length, String name) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(name + " must be " + length + " bytes");
        }
    }

    @Override
    public byte[] beneficiary() {
        return beneficiary.clone();
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
        return other instanceof ProgrammableDepositDatumV1 that && Arrays.equals(beneficiary, that.beneficiary)
                && Arrays.equals(refundAddress, that.refundAddress) && Arrays.equals(refundKeyHash, that.refundKeyHash)
                && originTxId.equals(that.originTxId) && originIndex == that.originIndex
                && outputIndex == that.outputIndex;
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(beneficiary), originTxId, originIndex, outputIndex);
    }
}
