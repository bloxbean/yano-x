package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A thread-authenticated {@code BalanceExit} (ADR-059 D10 r16, D8g §4). Its continuation lists one id, but that id is
 * the escape nullifier {@code blake2b-256("escape\0" ‖ VaultId ‖ holderId)}, not a {@code ClaimId}, so the consumer
 * records the nullifier as redeemed for that exit leaf and never looks it up as a claim: no
 * {@code RESERVED → PAID} transition. The discriminator is the vault redeemer, which names the holder; the observer
 * emits this form only when the nullifier it recomputes from that holder id equals the continuation's id.
 *
 * <p>Wire form: {@code [1, 1, domain_id, vault_id, tx_id, slot, block_hash, shard_index, shard_output_index,
 * escape_nullifier, holder_id, [recipient, quantity, lovelace], [output_index, registered_quantity, lovelace,
 * other_asset_count, value_hash]]}. The payout is output 0 and the continuation output 1.
 */
public record BalanceExitObservationV1(Hash32 domainId, Hash32 vaultId, Hash32 txId, long slot, Hash32 blockHash,
                                       int shardIndex, long shardOutputIndex, Hash32 escapeNullifier, byte[] holderId,
                                       Payout payout, Continuation continuation) implements PayoutClaimV1 {

    public BalanceExitObservationV1 {
        PayoutClaims.requireHeader(domainId, vaultId, txId, slot, blockHash, shardIndex, shardOutputIndex);
        Objects.requireNonNull(escapeNullifier, "escape nullifier");
        Objects.requireNonNull(payout, "payout");
        Objects.requireNonNull(continuation, "continuation");
        if (holderId == null || holderId.length == 0 || holderId.length > GatewayIds.MAX_HOLDER_ID_BYTES) {
            throw new IllegalArgumentException("holder id must be 1-64 bytes");
        }
        holderId = holderId.clone();
    }

    @Override
    public byte[] holderId() {
        return holderId.clone();
    }

    /** True when the nullifier is the one the vault script derives for this vault and holder. */
    public boolean nullifierMatchesHolder() {
        return GatewayIds.escapeNullifier(vaultId, holderId).equals(escapeNullifier);
    }

    @Override
    public byte[] encode() {
        Array array = new Array();
        PayoutClaims.header(array, BALANCE_EXIT, this);
        array.add(AssetCbor.bstr(escapeNullifier.bytes()));
        array.add(AssetCbor.bstr(holderId));
        array.add(payout.toItem());
        array.add(continuation.toItem());
        return AssetCbor.encode(array);
    }

    static BalanceExitObservationV1 decodeItems(List<DataItem> items) {
        return new BalanceExitObservationV1(PayoutClaims.hash(items.get(2)), PayoutClaims.hash(items.get(3)),
                PayoutClaims.hash(items.get(4)), AssetCbor.uintLong(items.get(5)), PayoutClaims.hash(items.get(6)),
                PayoutClaims.shard(items.get(7)), AssetCbor.uintLong(items.get(8)), PayoutClaims.hash(items.get(9)),
                AssetCbor.bytes(items.get(10), 1, GatewayIds.MAX_HOLDER_ID_BYTES), Payout.fromItem(items.get(11)),
                Continuation.fromFields(AssetCbor.array(items.get(12), 5, 5).getDataItems()));
    }

    public static BalanceExitObservationV1 decode(byte[] cbor) {
        if (PayoutClaimV1.decode(cbor) instanceof BalanceExitObservationV1 exit) return exit;
        throw AssetCbor.malformed("not a balance-exit observation");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BalanceExitObservationV1 that && Arrays.equals(encode(), that.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }
}
