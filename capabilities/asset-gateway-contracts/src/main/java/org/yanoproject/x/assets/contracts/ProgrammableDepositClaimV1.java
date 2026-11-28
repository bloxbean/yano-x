package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ADR-061 D6: the raw facts the deposit observer reports for one output at a CIP-113 vault's smart wallet. The
 * observer does not classify: it reads bounded facts from the block alone, and {@link ProgrammableDepositAdmission}
 * classifies them. One claim per vault output, with {@code eventOrdinal = outputIndex}, like
 * {@link AssetDepositClaimV1}; the transaction-level facts repeat in each claim of the transaction.
 *
 * <ul>
 *   <li>the output: its outpoint, the vault, the bounded value summary of {@link AssetDepositClaimV1} and its
 *       {@link Shape}, with, when deposit-shaped, the decoded datum and {@code originSpent};</li>
 *   <li>the script credentials among the transaction's withdrawals, <b>always</b>, each with the optional outer
 *       constructor index of its reward redeemer (absent when the redeemer is not a constructor), at most
 *       {@value #MAX_WITHDRAWALS}, else {@code OVERFLOW};</li>
 *   <li>whether the transaction mints a positive quantity under the vault asset's policy;</li>
 *   <li>the transaction's reference-input outpoints, at most {@value #MAX_REFERENCE_INPUTS}, else
 *       {@code OVERFLOW}.</li>
 * </ul>
 *
 * <p>Wire form: {@code [1, vault_id, tx_id, output_index, slot, block_hash, shape, datum, registered_quantity,
 * lovelace, other_asset_count, value_hash, withdrawals, positive_mint, reference_inputs]}, with {@code datum} =
 * {@code [beneficiary, refund_address, refund_key_hash, origin_tx_id, origin_index, output_index, origin_spent]}
 * for a deposit-shaped output and {@code []} otherwise; {@code withdrawals} = {@code [0, [* [hash] / [hash,
 * ctor]]]} or {@code [1]}; {@code reference_inputs} = {@code [0, [* [tx_id, index]]]} or {@code [1]}.
 */
public record ProgrammableDepositClaimV1(Hash32 vaultId, Hash32 txId, long outputIndex, long slot, Hash32 blockHash,
                                         Shape shape, Optional<Deposit> deposit, BigInteger registeredQuantity,
                                         BigInteger lovelace, long otherAssetCount, Hash32 valueHash,
                                         Optional<List<Withdrawal>> withdrawals, boolean positiveMint,
                                         Optional<List<ProgrammableDepositDatumV1.Outpoint>> referenceInputs) {
    public static final int VERSION = 1;
    public static final String CLAIM_SCHEMA = "asset-programmable-deposit-claim-v1";
    public static final int MAX_WITHDRAWALS = 16;
    public static final int MAX_REFERENCE_INPUTS = 32;

    /** The output's datum shape at a CIP-113 vault. */
    public enum Shape {
        DEPOSIT(0), MALFORMED(1), CONTINUATION(2), TOP_UP(3), OTHER(4);

        private final int code;

        Shape(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static Shape fromCode(long code) {
            for (Shape shape : values()) if (shape.code == code) return shape;
            throw AssetCbor.malformed("unknown programmable output shape");
        }

        /**
         * The shape of an inline datum (null for none): deposit-shaped by {@link ProgrammableDepositDatumV1}; any
         * other constructor 0 is {@code MALFORMED}; otherwise {@link VaultOutputShape}'s continuation and floor top-up
         * shapes, and {@code OTHER}.
         */
        public static Shape of(byte[] inlineDatum, byte[] plbHash, int networkId) {
            if (inlineDatum == null) return OTHER;
            return switch (ProgrammableDepositDatumV1.classify(inlineDatum, plbHash, networkId)) {
                case DEPOSIT_SHAPED -> DEPOSIT;
                case MALFORMED -> MALFORMED;
                case OTHER -> switch (VaultOutputShape.classify(inlineDatum)) {
                    case CONTINUATION -> CONTINUATION;
                    case FLOOR_TOP_UP -> TOP_UP;
                    case DEPOSIT -> MALFORMED;
                    default -> OTHER;
                };
            };
        }
    }

    /** A deposit-shaped output's datum and whether its origin is one of its own transaction's inputs. */
    public record Deposit(ProgrammableDepositDatumV1 datum, boolean originSpent) {
        public Deposit {
            Objects.requireNonNull(datum, "datum");
        }
    }

    /** A script credential among the withdrawals and the outer constructor of its reward redeemer, if any. */
    public record Withdrawal(byte[] scriptHash, Optional<Long> redeemerConstructor) {
        public Withdrawal {
            if (scriptHash == null || scriptHash.length != 28) throw new IllegalArgumentException("script hash");
            Objects.requireNonNull(redeemerConstructor, "redeemer constructor");
            redeemerConstructor.ifPresent(index -> {
                if (index < 0) throw new IllegalArgumentException("constructor index is unsigned");
            });
            scriptHash = scriptHash.clone();
        }

        @Override
        public byte[] scriptHash() {
            return scriptHash.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Withdrawal that && Arrays.equals(scriptHash, that.scriptHash)
                    && redeemerConstructor.equals(that.redeemerConstructor);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(scriptHash) * 31 + redeemerConstructor.hashCode();
        }
    }

    public ProgrammableDepositClaimV1 {
        Objects.requireNonNull(vaultId, "vault id");
        Objects.requireNonNull(txId, "tx id");
        Objects.requireNonNull(blockHash, "block hash");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(deposit, "deposit");
        Objects.requireNonNull(valueHash, "value hash");
        Objects.requireNonNull(withdrawals, "withdrawals");
        Objects.requireNonNull(referenceInputs, "reference inputs");
        if (outputIndex < 0 || outputIndex > AssetDepositClaimV1.MAX_INDEX || slot < 0 || otherAssetCount < 0
                || otherAssetCount > AssetDepositClaimV1.MAX_INDEX) {
            throw new IllegalArgumentException("output index, slot or asset count out of range");
        }
        if ((shape == Shape.DEPOSIT) != deposit.isPresent()) {
            throw new IllegalArgumentException("only a deposit-shaped output carries its datum");
        }
        Quantities.requireU64(registeredQuantity, "registered quantity");
        Quantities.requireU64(lovelace, "lovelace");
        withdrawals = withdrawals.map(List::copyOf);
        referenceInputs = referenceInputs.map(List::copyOf);
        if (withdrawals.map(List::size).orElse(0) > MAX_WITHDRAWALS
                || referenceInputs.map(List::size).orElse(0) > MAX_REFERENCE_INPUTS) {
            throw new IllegalArgumentException("a reported list over its cap must be OVERFLOW");
        }
    }

    /** {@code outputIndex}'s outpoint. */
    public ProgrammableDepositDatumV1.Outpoint outpoint() {
        return new ProgrammableDepositDatumV1.Outpoint(txId, outputIndex);
    }

    public byte[] encode() {
        Array array = new Array();
        array.add(AssetCbor.uint(VERSION));
        array.add(AssetCbor.bstr(vaultId.bytes()));
        array.add(AssetCbor.bstr(txId.bytes()));
        array.add(AssetCbor.uint(outputIndex));
        array.add(AssetCbor.uint(slot));
        array.add(AssetCbor.bstr(blockHash.bytes()));
        array.add(AssetCbor.uint(shape.code()));
        Array datum = new Array();
        deposit.ifPresent(d -> {
            ProgrammableDepositDatumV1 v = d.datum();
            datum.add(AssetCbor.bstr(v.beneficiary()));
            datum.add(AssetCbor.bstr(v.refundAddress()));
            datum.add(AssetCbor.bstr(v.refundKeyHash()));
            datum.add(AssetCbor.bstr(v.originTxId().bytes()));
            datum.add(AssetCbor.uint(v.originIndex()));
            datum.add(AssetCbor.uint(v.outputIndex()));
            datum.add(AssetCbor.uint(d.originSpent() ? 1 : 0));
        });
        array.add(datum);
        array.add(AssetCbor.uint(registeredQuantity));
        array.add(AssetCbor.uint(lovelace));
        array.add(AssetCbor.uint(otherAssetCount));
        array.add(AssetCbor.bstr(valueHash.bytes()));
        Array wdrl = new Array();
        if (withdrawals.isPresent()) {
            wdrl.add(AssetCbor.uint(0));
            Array list = new Array();
            for (Withdrawal w : withdrawals.get()) {
                Array entry = new Array();
                entry.add(AssetCbor.bstr(w.scriptHash()));
                w.redeemerConstructor().ifPresent(index -> entry.add(AssetCbor.uint(index)));
                list.add(entry);
            }
            wdrl.add(list);
        } else {
            wdrl.add(AssetCbor.uint(1));
        }
        array.add(wdrl);
        array.add(AssetCbor.uint(positiveMint ? 1 : 0));
        Array refs = new Array();
        if (referenceInputs.isPresent()) {
            refs.add(AssetCbor.uint(0));
            Array list = new Array();
            for (ProgrammableDepositDatumV1.Outpoint ref : referenceInputs.get()) {
                Array entry = new Array();
                entry.add(AssetCbor.bstr(ref.txId().bytes()));
                entry.add(AssetCbor.uint(ref.index()));
                list.add(entry);
            }
            refs.add(list);
        } else {
            refs.add(AssetCbor.uint(1));
        }
        array.add(refs);
        return AssetCbor.encode(array);
    }

    public static ProgrammableDepositClaimV1 decode(byte[] cbor) {
        List<DataItem> items = AssetCbor.decodeArray(cbor, 15).getDataItems();
        if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("programmable claim version");
        try {
            Shape shape = Shape.fromCode(AssetCbor.uintLong(items.get(6)));
            List<DataItem> datum = AssetCbor.array(items.get(7), 0, 7).getDataItems();
            Optional<Deposit> deposit = Optional.empty();
            if (!datum.isEmpty()) {
                if (datum.size() != 7) throw AssetCbor.malformed("deposit facts");
                long spent = AssetCbor.uintLong(datum.get(6));
                if (spent > 1) throw AssetCbor.malformed("origin_spent");
                deposit = Optional.of(new Deposit(new ProgrammableDepositDatumV1(AssetCbor.bytes(datum.get(0), 28, 28),
                        AssetCbor.bytes(datum.get(1), 1, 64), AssetCbor.bytes(datum.get(2), 28, 28),
                        hash(datum.get(3)), AssetCbor.uintLong(datum.get(4)), AssetCbor.uintLong(datum.get(5))),
                        spent == 1));
            }
            List<DataItem> wdrl = AssetCbor.array(items.get(12), 1, 2).getDataItems();
            Optional<List<Withdrawal>> withdrawals;
            long wdrlKind = AssetCbor.uintLong(wdrl.getFirst());
            if (wdrlKind == 0 && wdrl.size() == 2) {
                List<Withdrawal> list = new ArrayList<>();
                for (DataItem entry : AssetCbor.array(wdrl.get(1), 0, MAX_WITHDRAWALS).getDataItems()) {
                    List<DataItem> pair = AssetCbor.array(entry, 1, 2).getDataItems();
                    list.add(new Withdrawal(AssetCbor.bytes(pair.getFirst(), 28, 28),
                            pair.size() == 2 ? Optional.of(AssetCbor.uintLong(pair.get(1))) : Optional.empty()));
                }
                withdrawals = Optional.of(list);
            } else if (wdrlKind == 1 && wdrl.size() == 1) {
                withdrawals = Optional.empty();
            } else {
                throw AssetCbor.malformed("withdrawals form");
            }
            long mint = AssetCbor.uintLong(items.get(13));
            if (mint > 1) throw AssetCbor.malformed("positive_mint");
            List<DataItem> refsItem = AssetCbor.array(items.get(14), 1, 2).getDataItems();
            Optional<List<ProgrammableDepositDatumV1.Outpoint>> refs;
            long refsKind = AssetCbor.uintLong(refsItem.getFirst());
            if (refsKind == 0 && refsItem.size() == 2) {
                List<ProgrammableDepositDatumV1.Outpoint> list = new ArrayList<>();
                for (DataItem entry : AssetCbor.array(refsItem.get(1), 0, MAX_REFERENCE_INPUTS).getDataItems()) {
                    List<DataItem> pair = AssetCbor.array(entry, 2, 2).getDataItems();
                    list.add(new ProgrammableDepositDatumV1.Outpoint(hash(pair.get(0)), AssetCbor.uintLong(pair.get(1))));
                }
                refs = Optional.of(list);
            } else if (refsKind == 1 && refsItem.size() == 1) {
                refs = Optional.empty();
            } else {
                throw AssetCbor.malformed("reference inputs form");
            }
            return new ProgrammableDepositClaimV1(hash(items.get(1)), hash(items.get(2)),
                    AssetCbor.uintLong(items.get(3)), AssetCbor.uintLong(items.get(4)), hash(items.get(5)), shape,
                    deposit, AssetCbor.uint(items.get(8), Quantities.MAX_U64),
                    AssetCbor.uint(items.get(9), Quantities.MAX_U64), AssetCbor.uintLong(items.get(10)),
                    hash(items.get(11)), withdrawals, mint == 1, refs);
        } catch (IllegalArgumentException invalid) {
            throw AssetCbor.malformed(invalid.getMessage());
        }
    }

    private static Hash32 hash(DataItem item) {
        return Hash32.of(AssetCbor.bytes(item, 32, 32));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProgrammableDepositClaimV1 that && Arrays.equals(encode(), that.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }
}
