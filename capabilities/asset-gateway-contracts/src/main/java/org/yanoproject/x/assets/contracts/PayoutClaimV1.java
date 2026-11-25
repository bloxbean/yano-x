package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The claim of one {@code asset-vault-payout-v1} observation (ADR-059 D10): a fact about one phase-2-valid L1
 * transaction that carries one of a vault's shard thread tokens at the vault's pinned shard address. Thread
 * authentication is what makes it a payout: the policy is one-shot and the token never leaves the shard script, so its
 * presence proves the shard validator, and with it the vault validator (V4), accepted the transaction.
 *
 * <p>Two forms share the claim schema {@value #CLAIM_SCHEMA} and are told apart by the second wire field:
 * <ul>
 *   <li>{@link PayoutObservationV1} ({@value #PAYOUT}): the claim ids a {@code Settle}, {@code Exit} or
 *       {@code SweepExit} paid, with each payout output's script-enforced summary;</li>
 *   <li>{@link BalanceExitObservationV1} ({@value #BALANCE_EXIT}): a {@code BalanceExit}, whose continuation lists an
 *       escape nullifier, not a {@code ClaimId}, so it never confirms a claim.</li>
 * </ul>
 * Both are in the strict contract subset ({@code asset-gateway-settlement-v1.cddl}).
 */
public sealed interface PayoutClaimV1 permits PayoutObservationV1, BalanceExitObservationV1 {
    int VERSION = 1;
    String CLAIM_SCHEMA = "asset-payout-claim-v1";
    int PAYOUT = 0;
    int BALANCE_EXIT = 1;
    /** The bound of a continuation's id list, and so of the claims one payout transaction can pay (V7). */
    int MAX_CLAIMS = VaultOutputShape.MAX_CONTINUATION_CLAIMS;
    /** Largest output index a claim carries (far beyond any L1 transaction). */
    long MAX_INDEX = 0xFFFF_FFFFL;

    Hash32 domainId();

    Hash32 vaultId();

    Hash32 txId();

    long slot();

    Hash32 blockHash();

    /** The shard (0..15) whose thread token authenticated the transaction. */
    int shardIndex();

    /** The index of the shard's continuation output; it is the observation's {@code eventOrdinal}. */
    long shardOutputIndex();

    byte[] encode();

    /** Decodes either form from its exact canonical bytes. */
    static PayoutClaimV1 decode(byte[] cbor) {
        List<DataItem> items = AssetCbor.decodeArray(cbor, 13).getDataItems();
        if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("payout claim version");
        long form = AssetCbor.uintLong(items.get(1));
        try {
            if (form == PAYOUT) return PayoutObservationV1.decodeItems(items);
            if (form == BALANCE_EXIT) return BalanceExitObservationV1.decodeItems(items);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw AssetCbor.malformed(invalid.getMessage());
        }
        throw AssetCbor.malformed("unknown payout claim form");
    }

    /** The script-enforced summary of one payout output (D10): recipient bytes, vault-asset quantity, lovelace. */
    record Payout(byte[] recipient, BigInteger quantity, BigInteger lovelace) {
        public Payout {
            if (recipient == null || recipient.length == 0 || recipient.length > GatewayIds.MAX_ADDRESS_BYTES) {
                throw new IllegalArgumentException("recipient must be 1-128 bytes");
            }
            Quantities.requireU64(quantity, "quantity");
            Quantities.requireU64(lovelace, "lovelace");
            recipient = recipient.clone();
        }

        @Override
        public byte[] recipient() {
            return recipient.clone();
        }

        Array toItem() {
            Array array = new Array();
            array.add(AssetCbor.bstr(recipient));
            array.add(AssetCbor.uint(quantity));
            array.add(AssetCbor.uint(lovelace));
            return array;
        }

        static Payout fromItem(DataItem item) {
            List<DataItem> fields = AssetCbor.array(item, 3, 3).getDataItems();
            return new Payout(AssetCbor.bytes(fields.get(0), 1, GatewayIds.MAX_ADDRESS_BYTES),
                    AssetCbor.uint(fields.get(1), Quantities.MAX_U64),
                    AssetCbor.uint(fields.get(2), Quantities.MAX_U64));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Payout that && Arrays.equals(recipient, that.recipient)
                    && quantity.equals(that.quantity) && lovelace.equals(that.lovelace);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(recipient), quantity, lovelace);
        }
    }

    /**
     * The bounded value summary of a vault continuation output, as in a deposit claim (D5): the vault asset's
     * quantity (the lovelace for the ADA vault), lovelace, the count of other assets and the full value's hash.
     */
    record Continuation(long outputIndex, BigInteger registeredQuantity, BigInteger lovelace, long otherAssetCount,
                        Hash32 valueHash) {
        public Continuation {
            if (outputIndex < 0 || outputIndex > MAX_INDEX || otherAssetCount < 0 || otherAssetCount > MAX_INDEX) {
                throw new IllegalArgumentException("continuation index or asset count out of range");
            }
            Quantities.requireU64(registeredQuantity, "registered quantity");
            Quantities.requireU64(lovelace, "lovelace");
            Objects.requireNonNull(valueHash, "value hash");
        }

        Array toItem() {
            Array array = new Array();
            array.add(AssetCbor.uint(outputIndex));
            array.add(AssetCbor.uint(registeredQuantity));
            array.add(AssetCbor.uint(lovelace));
            array.add(AssetCbor.uint(otherAssetCount));
            array.add(AssetCbor.bstr(valueHash.bytes()));
            return array;
        }

        static Continuation fromFields(List<DataItem> fields) {
            return new Continuation(AssetCbor.uintLong(fields.get(0)),
                    AssetCbor.uint(fields.get(1), Quantities.MAX_U64),
                    AssetCbor.uint(fields.get(2), Quantities.MAX_U64), AssetCbor.uintLong(fields.get(3)),
                    Hash32.of(AssetCbor.bytes(fields.get(4), 32, 32)));
        }
    }
}
