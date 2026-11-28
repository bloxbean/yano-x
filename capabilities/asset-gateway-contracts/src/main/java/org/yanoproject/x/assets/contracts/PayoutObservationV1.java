package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The paid claims of one thread-authenticated payout transaction (ADR-059 D10).
 *
 * <p><b>Which output names the claims (r15).</b> {@link Source#CONTINUATION}: the ids of the vault's
 * {@code VaultContinuationV1} output, which V7 forces to be exactly the paid ids in order ({@code Settle},
 * {@code Exit}); payout {@code i} is output {@code i}. {@link Source#SWEEP_PAID}: a {@code SweepExit}, which leaves no
 * vault continuation; the one id is read from output 0's {@code SweepPaidV1}, the output that pays it. The
 * continuation summary is therefore optional: a {@code SweepExit} has none (r16).
 *
 * <p>Wire form: {@code [1, 0, domain_id, vault_id, tx_id, slot, block_hash, shard_index, shard_output_index,
 * source, [+ claim_id], [+ [recipient, quantity, lovelace]], [] / [output_index, registered_quantity, lovelace,
 * other_asset_count, value_hash]]}, one payout per claim id, in the same order.
 */
public record PayoutObservationV1(Hash32 domainId, Hash32 vaultId, Hash32 txId, long slot, Hash32 blockHash,
                                  int shardIndex, long shardOutputIndex, Source source, List<Hash32> claimIds,
                                  List<Payout> payouts, Optional<Continuation> continuation) implements PayoutClaimV1 {

    /** Where the paid claim ids were read. The code is the wire value. */
    public enum Source {
        CONTINUATION(0),
        SWEEP_PAID(1);

        private final int code;

        Source(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static Source fromCode(long code) {
            for (Source source : values()) if (source.code == code) return source;
            throw AssetCbor.malformed("unknown payout source");
        }
    }

    public PayoutObservationV1 {
        PayoutClaims.requireHeader(domainId, vaultId, txId, slot, blockHash, shardIndex, shardOutputIndex);
        Objects.requireNonNull(source, "source");
        claimIds = List.copyOf(claimIds);
        payouts = List.copyOf(payouts);
        Objects.requireNonNull(continuation, "continuation");
        if (claimIds.isEmpty() || claimIds.size() > MAX_CLAIMS || payouts.size() != claimIds.size()) {
            throw new IllegalArgumentException("a payout names 1-64 claims, one payout output each");
        }
        if (new HashSet<>(claimIds).size() != claimIds.size()) {
            throw new IllegalArgumentException("claim ids are distinct (one nullifier insert each)");
        }
        if (source == Source.SWEEP_PAID && (claimIds.size() != 1 || continuation.isPresent())) {
            throw new IllegalArgumentException("a sweep pays one claim and leaves no continuation");
        }
        if (source == Source.CONTINUATION && continuation.isEmpty()) {
            throw new IllegalArgumentException("a continuation-sourced payout carries its continuation");
        }
    }

    @Override
    public byte[] encode() {
        Array array = new Array();
        PayoutClaims.header(array, PAYOUT, this);
        array.add(AssetCbor.uint(source.code()));
        Array ids = new Array();
        claimIds.forEach(id -> ids.add(AssetCbor.bstr(id.bytes())));
        array.add(ids);
        Array outputs = new Array();
        payouts.forEach(payout -> outputs.add(payout.toItem()));
        array.add(outputs);
        array.add(continuation.map(Continuation::toItem).orElseGet(Array::new));
        return AssetCbor.encode(array);
    }

    static PayoutObservationV1 decodeItems(List<DataItem> items) {
        List<DataItem> ids = AssetCbor.array(items.get(10), 1, MAX_CLAIMS).getDataItems();
        List<DataItem> outputs = AssetCbor.array(items.get(11), 1, MAX_CLAIMS).getDataItems();
        List<DataItem> rest = AssetCbor.array(items.get(12), 0, 5).getDataItems();
        if (!rest.isEmpty() && rest.size() != 5) throw AssetCbor.malformed("continuation summary arity");
        List<Hash32> claimIds = new ArrayList<>(ids.size());
        ids.forEach(id -> claimIds.add(PayoutClaims.hash(id)));
        List<Payout> payouts = new ArrayList<>(outputs.size());
        outputs.forEach(output -> payouts.add(Payout.fromItem(output)));
        return new PayoutObservationV1(PayoutClaims.hash(items.get(2)), PayoutClaims.hash(items.get(3)),
                PayoutClaims.hash(items.get(4)), AssetCbor.uintLong(items.get(5)), PayoutClaims.hash(items.get(6)),
                PayoutClaims.shard(items.get(7)), AssetCbor.uintLong(items.get(8)),
                Source.fromCode(AssetCbor.uintLong(items.get(9))), claimIds, payouts,
                rest.isEmpty() ? Optional.empty() : Optional.of(Continuation.fromFields(rest)));
    }

    public static PayoutObservationV1 decode(byte[] cbor) {
        if (PayoutClaimV1.decode(cbor) instanceof PayoutObservationV1 payout) return payout;
        throw AssetCbor.malformed("not a payout observation");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PayoutObservationV1 that && Arrays.equals(encode(), that.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }
}
