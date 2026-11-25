package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The consensus records {@link PayoutConfirmation} keeps (ADR-059 D7, D10), in the strict contract subset
 * ({@code asset-gateway-settlement-v1.cddl}).
 */
public final class SettlementRecords {
    private SettlementRecords() {
    }

    /** Claim status, the consensus lifecycle of D7: {@code RESERVED → PAID}, or {@code PAID_MISMATCH}. */
    public enum ClaimStatus {
        RESERVED(0),
        PAID(1),
        PAID_MISMATCH(2);

        private final int code;

        ClaimStatus(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static ClaimStatus fromCode(long code) {
            for (ClaimStatus status : values()) if (status.code == code) return status;
            throw AssetCbor.malformed("unknown claim status");
        }
    }

    /**
     * {@code [1, claim_id, status, reserved_height, resolved_tx_id: bytes .size 0 / hash32, resolved_height]}: the
     * mutable status of one claim, kept apart from its immutable {@link ClaimLeafV1}. {@code resolvedTxId} is the
     * payout transaction that moved it to {@code PAID} or {@code PAID_MISMATCH}.
     */
    public record ClaimStatusV1(Hash32 claimId, ClaimStatus status, long reservedHeight, Optional<Hash32> resolvedTxId,
                                long resolvedHeight) {
        public static final int VERSION = 1;

        public ClaimStatusV1 {
            Objects.requireNonNull(claimId, "claim id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(resolvedTxId, "resolved tx id");
            if (reservedHeight < 0 || resolvedHeight < 0) {
                throw new IllegalArgumentException("heights are non-negative");
            }
            if ((status == ClaimStatus.RESERVED) != resolvedTxId.isEmpty()) {
                throw new IllegalArgumentException("only a resolved claim names its payout transaction");
            }
        }

        public static ClaimStatusV1 reserved(Hash32 claimId, long reservedHeight) {
            return new ClaimStatusV1(claimId, ClaimStatus.RESERVED, reservedHeight, Optional.empty(), 0);
        }

        public ClaimStatusV1 resolve(ClaimStatus next, Hash32 txId, long height) {
            return new ClaimStatusV1(claimId, next, reservedHeight, Optional.of(txId), height);
        }

        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.uint(VERSION));
            array.add(AssetCbor.bstr(claimId.bytes()));
            array.add(AssetCbor.uint(status.code()));
            array.add(AssetCbor.uint(reservedHeight));
            array.add(AssetCbor.bstr(resolvedTxId.map(Hash32::bytes).orElse(new byte[0])));
            array.add(AssetCbor.uint(resolvedHeight));
            return AssetCbor.encode(array);
        }

        public static ClaimStatusV1 decode(byte[] cbor) {
            List<DataItem> items = AssetCbor.decodeArray(cbor, 6).getDataItems();
            if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("claim status version");
            byte[] tx = AssetCbor.bytes(items.get(4), 0, 32);
            if (tx.length != 0 && tx.length != 32) throw AssetCbor.malformed("resolved tx id length");
            try {
                return new ClaimStatusV1(Hash32.of(AssetCbor.bytes(items.get(1), 32, 32)),
                        ClaimStatus.fromCode(AssetCbor.uintLong(items.get(2))), AssetCbor.uintLong(items.get(3)),
                        tx.length == 0 ? Optional.empty() : Optional.of(Hash32.of(tx)),
                        AssetCbor.uintLong(items.get(5)));
            } catch (IllegalArgumentException invalid) {
                throw AssetCbor.malformed(invalid.getMessage());
            }
        }
    }

    /**
     * {@code [1, outcome, reason, height]}: the write-once record of one confirmation, keyed by
     * {@code (GatewayDomain, L1 tx id, ClaimId)}. A redelivered observation finds it and writes nothing.
     */
    public record PayoutRecordV1(PayoutConfirmation.Outcome outcome, PayoutConfirmation.Reason reason, long height) {
        public static final int VERSION = 1;

        public PayoutRecordV1 {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
            if (height < 0) throw new IllegalArgumentException("height is non-negative");
        }

        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.uint(VERSION));
            array.add(AssetCbor.uint(outcome.code()));
            array.add(AssetCbor.uint(reason.code()));
            array.add(AssetCbor.uint(height));
            return AssetCbor.encode(array);
        }

        public static PayoutRecordV1 decode(byte[] cbor) {
            List<DataItem> items = AssetCbor.decodeArray(cbor, 4).getDataItems();
            if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("payout record version");
            try {
                return new PayoutRecordV1(PayoutConfirmation.Outcome.fromCode(AssetCbor.uintLong(items.get(1))),
                        PayoutConfirmation.Reason.fromCode(AssetCbor.uintLong(items.get(2))),
                        AssetCbor.uintLong(items.get(3)));
            } catch (IllegalArgumentException invalid) {
                throw AssetCbor.malformed(invalid.getMessage());
            }
        }
    }

    /**
     * {@code [1, escape_nullifier, vault_id, holder_id, tx_id, recipient, quantity, lovelace, height]}: an exit leaf
     * redeemed by a thread-authenticated {@code BalanceExit} (D10 r16). Written once; the claim lifecycle never sees
     * it.
     */
    public record EscapeRedemptionV1(Hash32 escapeNullifier, Hash32 vaultId, byte[] holderId, Hash32 txId,
                                     byte[] recipient, BigInteger quantity, BigInteger lovelace, long height) {
        public static final int VERSION = 1;

        public EscapeRedemptionV1 {
            Objects.requireNonNull(escapeNullifier, "escape nullifier");
            Objects.requireNonNull(vaultId, "vault id");
            Objects.requireNonNull(txId, "tx id");
            if (holderId == null || holderId.length == 0 || holderId.length > GatewayIds.MAX_HOLDER_ID_BYTES) {
                throw new IllegalArgumentException("holder id must be 1-64 bytes");
            }
            if (recipient == null || recipient.length == 0 || recipient.length > GatewayIds.MAX_ADDRESS_BYTES) {
                throw new IllegalArgumentException("recipient must be 1-128 bytes");
            }
            Quantities.requireU64(quantity, "quantity");
            Quantities.requireU64(lovelace, "lovelace");
            if (height < 0) throw new IllegalArgumentException("height is non-negative");
            holderId = holderId.clone();
            recipient = recipient.clone();
        }

        @Override
        public byte[] holderId() {
            return holderId.clone();
        }

        @Override
        public byte[] recipient() {
            return recipient.clone();
        }

        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.uint(VERSION));
            array.add(AssetCbor.bstr(escapeNullifier.bytes()));
            array.add(AssetCbor.bstr(vaultId.bytes()));
            array.add(AssetCbor.bstr(holderId));
            array.add(AssetCbor.bstr(txId.bytes()));
            array.add(AssetCbor.bstr(recipient));
            array.add(AssetCbor.uint(quantity));
            array.add(AssetCbor.uint(lovelace));
            array.add(AssetCbor.uint(height));
            return AssetCbor.encode(array);
        }

        public static EscapeRedemptionV1 decode(byte[] cbor) {
            List<DataItem> items = AssetCbor.decodeArray(cbor, 9).getDataItems();
            if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("escape record version");
            try {
                return new EscapeRedemptionV1(Hash32.of(AssetCbor.bytes(items.get(1), 32, 32)),
                        Hash32.of(AssetCbor.bytes(items.get(2), 32, 32)),
                        AssetCbor.bytes(items.get(3), 1, GatewayIds.MAX_HOLDER_ID_BYTES),
                        Hash32.of(AssetCbor.bytes(items.get(4), 32, 32)),
                        AssetCbor.bytes(items.get(5), 1, GatewayIds.MAX_ADDRESS_BYTES),
                        AssetCbor.uint(items.get(6), Quantities.MAX_U64),
                        AssetCbor.uint(items.get(7), Quantities.MAX_U64),
                        AssetCbor.uintLong(items.get(8)));
            } catch (IllegalArgumentException invalid) {
                throw AssetCbor.malformed(invalid.getMessage());
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EscapeRedemptionV1 that && Arrays.equals(encode(), that.encode());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(encode());
        }
    }
}
