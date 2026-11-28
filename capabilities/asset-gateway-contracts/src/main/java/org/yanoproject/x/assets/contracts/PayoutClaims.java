package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.util.Objects;

/** Wire helpers shared by the two {@link PayoutClaimV1} forms. */
final class PayoutClaims {
    private PayoutClaims() {
    }

    /** Shared envelope fields, wire positions 2-8. */
    static void header(Array array, int form, PayoutClaimV1 claim) {
        array.add(AssetCbor.uint(PayoutClaimV1.VERSION));
        array.add(AssetCbor.uint(form));
        array.add(AssetCbor.bstr(claim.domainId().bytes()));
        array.add(AssetCbor.bstr(claim.vaultId().bytes()));
        array.add(AssetCbor.bstr(claim.txId().bytes()));
        array.add(AssetCbor.uint(claim.slot()));
        array.add(AssetCbor.bstr(claim.blockHash().bytes()));
        array.add(AssetCbor.uint(claim.shardIndex()));
        array.add(AssetCbor.uint(claim.shardOutputIndex()));
    }

    static void requireHeader(Hash32 domainId, Hash32 vaultId, Hash32 txId, long slot, Hash32 blockHash,
                              int shardIndex, long shardOutputIndex) {
        Objects.requireNonNull(domainId, "domain id");
        Objects.requireNonNull(vaultId, "vault id");
        Objects.requireNonNull(txId, "tx id");
        Objects.requireNonNull(blockHash, "block hash");
        if (slot < 0 || shardIndex < 0 || shardIndex > 15 || shardOutputIndex < 0
                || shardOutputIndex > PayoutClaimV1.MAX_INDEX) {
            throw new IllegalArgumentException("slot, shard index or shard output index out of range");
        }
    }

    static Hash32 hash(DataItem item) {
        return Hash32.of(AssetCbor.bytes(item, 32, 32));
    }

    static int shard(DataItem item) {
        long value = AssetCbor.uintLong(item);
        if (value > 15) throw AssetCbor.malformed("shard index out of range");
        return (int) value;
    }
}
