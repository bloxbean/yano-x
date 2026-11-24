package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The observed fact for one output at a vault address (ADR-059 D5). An observer emits one claim
 * per vault output, whatever its shape or value, with {@code eventOrdinal = outputIndex}. It
 * reports facts only; {@link DepositAdmission} decides creditability (D6).
 *
 * <p>The value is reported as a bounded summary, so every output yields a bounded observation
 * whatever its asset count: the quantity of the vault's registered asset, the lovelace, the
 * number of other assets, and {@code blake2b-256} of the full value's canonical Cardano CBOR
 * ({@code coin / [coin, multiasset]}, RFC 8949 §4.2.1 key order). For the ADA vault the
 * registered quantity is the lovelace.
 *
 * <p>Wire form (strict contract subset):
 * {@code [1, deposit_id, vault_id, tx_id, output_index, slot, block_hash, shape, beneficiary,
 * registered_quantity, lovelace, other_asset_count, value_hash]}, where {@code beneficiary} is
 * {@code [0, account_id(28)]} for a decoded deposit datum, {@code [1, malformed_reason]} for a
 * deposit-shaped output whose datum does not decode, and {@code [2]} for every other shape.
 */
public record AssetDepositClaimV1(Hash32 depositId, Hash32 vaultId, Hash32 txId, long outputIndex,
                                  long slot, Hash32 blockHash, VaultOutputShape shape,
                                  Beneficiary beneficiary, BigInteger registeredQuantity,
                                  BigInteger lovelace, long otherAssetCount, Hash32 valueHash) {
    public static final int VERSION = 1;
    public static final String CLAIM_SCHEMA = "asset-deposit-claim-v1";
    /** Largest output index and asset count a claim can carry (far beyond any L1 transaction). */
    public static final long MAX_INDEX = 0xFFFF_FFFFL;

    /** Why a deposit-shaped datum could not be decoded. */
    public enum MalformedReason {
        DATUM_UNDECODABLE(1),
        DATUM_OUT_OF_BOUNDS(2);

        private final int code;

        MalformedReason(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static MalformedReason fromCode(long code) {
            for (MalformedReason reason : values()) if (reason.code == code) return reason;
            throw AssetCbor.malformed("unknown malformed reason");
        }
    }

    /** The beneficiary decoded from a deposit datum, or why there is none. */
    public sealed interface Beneficiary permits Account, Malformed, NotApplicable { }

    public record Account(byte[] accountId) implements Beneficiary {
        public Account {
            if (accountId == null || accountId.length != DepositDatumV1.ACCOUNT_BYTES) {
                throw new IllegalArgumentException("beneficiary account must be 28 bytes");
            }
            accountId = accountId.clone();
        }

        @Override
        public byte[] accountId() {
            return accountId.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Account that && Arrays.equals(accountId, that.accountId);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(accountId);
        }
    }

    public record Malformed(MalformedReason reason) implements Beneficiary {
        public Malformed {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record NotApplicable() implements Beneficiary { }

    public AssetDepositClaimV1 {
        Objects.requireNonNull(depositId, "deposit id");
        Objects.requireNonNull(vaultId, "vault id");
        Objects.requireNonNull(txId, "tx id");
        Objects.requireNonNull(blockHash, "block hash");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(beneficiary, "beneficiary");
        Objects.requireNonNull(valueHash, "value hash");
        if (outputIndex < 0 || outputIndex > MAX_INDEX || slot < 0
                || otherAssetCount < 0 || otherAssetCount > MAX_INDEX) {
            throw new IllegalArgumentException("output index, slot or asset count out of range");
        }
        Quantities.requireU64(registeredQuantity, "registered quantity");
        Quantities.requireU64(lovelace, "lovelace");
        if ((shape == VaultOutputShape.DEPOSIT) == (beneficiary instanceof NotApplicable)) {
            throw new IllegalArgumentException("only a deposit-shaped output carries a beneficiary");
        }
    }

    public byte[] encode() {
        Array array = new Array();
        array.add(AssetCbor.uint(VERSION));
        array.add(AssetCbor.bstr(depositId.bytes()));
        array.add(AssetCbor.bstr(vaultId.bytes()));
        array.add(AssetCbor.bstr(txId.bytes()));
        array.add(AssetCbor.uint(outputIndex));
        array.add(AssetCbor.uint(slot));
        array.add(AssetCbor.bstr(blockHash.bytes()));
        array.add(AssetCbor.uint(shape.code()));
        Array who = new Array();
        switch (beneficiary) {
            case Account account -> {
                who.add(AssetCbor.uint(0));
                who.add(AssetCbor.bstr(account.accountId()));
            }
            case Malformed malformed -> {
                who.add(AssetCbor.uint(1));
                who.add(AssetCbor.uint(malformed.reason().code()));
            }
            case NotApplicable ignored -> who.add(AssetCbor.uint(2));
        }
        array.add(who);
        array.add(AssetCbor.uint(registeredQuantity));
        array.add(AssetCbor.uint(lovelace));
        array.add(AssetCbor.uint(otherAssetCount));
        array.add(AssetCbor.bstr(valueHash.bytes()));
        return AssetCbor.encode(array);
    }

    public static AssetDepositClaimV1 decode(byte[] cbor) {
        List<DataItem> items = AssetCbor.decodeArray(cbor, 13).getDataItems();
        if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("deposit claim version");
        List<DataItem> who = AssetCbor.array(items.get(8), 1, 2).getDataItems();
        long kind = AssetCbor.uintLong(who.getFirst());
        Beneficiary beneficiary;
        if (kind == 0 && who.size() == 2) {
            beneficiary = new Account(AssetCbor.bytes(who.get(1), 28, 28));
        } else if (kind == 1 && who.size() == 2) {
            beneficiary = new Malformed(MalformedReason.fromCode(AssetCbor.uintLong(who.get(1))));
        } else if (kind == 2 && who.size() == 1) {
            beneficiary = new NotApplicable();
        } else {
            throw AssetCbor.malformed("unknown beneficiary form");
        }
        try {
            return new AssetDepositClaimV1(
                    hash(items.get(1)), hash(items.get(2)), hash(items.get(3)),
                    AssetCbor.uintLong(items.get(4)), AssetCbor.uintLong(items.get(5)), hash(items.get(6)),
                    VaultOutputShape.fromCode(AssetCbor.uintLong(items.get(7))), beneficiary,
                    AssetCbor.uint(items.get(9), Quantities.MAX_U64),
                    AssetCbor.uint(items.get(10), Quantities.MAX_U64),
                    AssetCbor.uintLong(items.get(11)), hash(items.get(12)));
        } catch (IllegalArgumentException invalid) {
            throw AssetCbor.malformed(invalid.getMessage());
        }
    }

    private static Hash32 hash(DataItem item) {
        return Hash32.of(AssetCbor.bytes(item, 32, 32));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AssetDepositClaimV1 that && Arrays.equals(encode(), that.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }
}
