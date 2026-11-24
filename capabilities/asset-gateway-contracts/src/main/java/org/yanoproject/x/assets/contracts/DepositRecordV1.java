package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The write-once record of one processed vault output (ADR-059 D6 step 5), stored at
 * {@code prefix ‖ "deposit/" ‖ DepositId}. Its presence is what makes credit exactly-once (I1).
 *
 * <p>Wire form: {@code [1, deposit_id, vault_id, tx_id, output_index, slot, height, shape, status,
 * reason, beneficiary, registered_quantity, lovelace, other_asset_count, value_hash,
 * credited_amount, credited_lovelace]}. {@code beneficiary} is the 28-byte account for a decoded
 * deposit datum and empty otherwise. {@code credited_*} are zero unless the status is
 * {@link Status#CREDITED}.
 */
public record DepositRecordV1(Hash32 depositId, Hash32 vaultId, Hash32 txId, long outputIndex, long slot,
                              long height, VaultOutputShape shape, Status status, DepositAdmission.Reason reason,
                              byte[] beneficiary, BigInteger registeredQuantity, BigInteger lovelace,
                              long otherAssetCount, Hash32 valueHash, BigInteger creditedAmount,
                              BigInteger creditedLovelace) {
    public static final int VERSION = 1;

    /** What processing the output produced. */
    public enum Status {
        CREDITED(0),
        UNCREDITED(1),
        UNATTRIBUTED(2),
        OPERATIONAL(3);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static Status fromCode(long code) {
            for (Status status : values()) if (status.code == code) return status;
            throw AssetCbor.malformed("unknown deposit status");
        }
    }

    public DepositRecordV1 {
        Objects.requireNonNull(depositId, "deposit id");
        Objects.requireNonNull(vaultId, "vault id");
        Objects.requireNonNull(txId, "tx id");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(valueHash, "value hash");
        if (outputIndex < 0 || slot < 0 || height < 0 || otherAssetCount < 0) {
            throw new IllegalArgumentException("record positions are non-negative");
        }
        if (beneficiary == null || (beneficiary.length != 0 && beneficiary.length != 28)) {
            throw new IllegalArgumentException("beneficiary is empty or 28 bytes");
        }
        Quantities.requireU64(registeredQuantity, "registered quantity");
        Quantities.requireU64(lovelace, "lovelace");
        Quantities.requireU64(creditedAmount, "credited amount");
        Quantities.requireU64(creditedLovelace, "credited lovelace");
        boolean credited = status == Status.CREDITED;
        if (credited != (reason == DepositAdmission.Reason.NONE)
                || (!credited && (creditedAmount.signum() != 0 || creditedLovelace.signum() != 0))
                || (credited && (beneficiary.length == 0 || creditedAmount.signum() == 0))) {
            throw new IllegalArgumentException("deposit record status, reason and credit disagree");
        }
        beneficiary = beneficiary.clone();
    }

    public byte[] encode() {
        Array array = new Array();
        array.add(AssetCbor.uint(VERSION));
        array.add(AssetCbor.bstr(depositId.bytes()));
        array.add(AssetCbor.bstr(vaultId.bytes()));
        array.add(AssetCbor.bstr(txId.bytes()));
        array.add(AssetCbor.uint(outputIndex));
        array.add(AssetCbor.uint(slot));
        array.add(AssetCbor.uint(height));
        array.add(AssetCbor.uint(shape.code()));
        array.add(AssetCbor.uint(status.code()));
        array.add(AssetCbor.uint(reason.code()));
        array.add(AssetCbor.bstr(beneficiary));
        array.add(AssetCbor.uint(registeredQuantity));
        array.add(AssetCbor.uint(lovelace));
        array.add(AssetCbor.uint(otherAssetCount));
        array.add(AssetCbor.bstr(valueHash.bytes()));
        array.add(AssetCbor.uint(creditedAmount));
        array.add(AssetCbor.uint(creditedLovelace));
        return AssetCbor.encode(array);
    }

    public static DepositRecordV1 decode(byte[] cbor) {
        List<DataItem> items = AssetCbor.decodeArray(cbor, 17).getDataItems();
        if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("deposit record version");
        try {
            return new DepositRecordV1(hash(items.get(1)), hash(items.get(2)), hash(items.get(3)),
                    AssetCbor.uintLong(items.get(4)), AssetCbor.uintLong(items.get(5)),
                    AssetCbor.uintLong(items.get(6)),
                    VaultOutputShape.fromCode(AssetCbor.uintLong(items.get(7))),
                    Status.fromCode(AssetCbor.uintLong(items.get(8))),
                    DepositAdmission.Reason.fromCode(AssetCbor.uintLong(items.get(9))),
                    AssetCbor.bytes(items.get(10), 0, 28),
                    AssetCbor.uint(items.get(11), Quantities.MAX_U64),
                    AssetCbor.uint(items.get(12), Quantities.MAX_U64),
                    AssetCbor.uintLong(items.get(13)), hash(items.get(14)),
                    AssetCbor.uint(items.get(15), Quantities.MAX_U64),
                    AssetCbor.uint(items.get(16), Quantities.MAX_U64));
        } catch (IllegalArgumentException invalid) {
            throw AssetCbor.malformed(invalid.getMessage());
        }
    }

    private static Hash32 hash(DataItem item) {
        return Hash32.of(AssetCbor.bytes(item, 32, 32));
    }

    @Override
    public byte[] beneficiary() {
        return beneficiary.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DepositRecordV1 that && Arrays.equals(encode(), that.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }
}
