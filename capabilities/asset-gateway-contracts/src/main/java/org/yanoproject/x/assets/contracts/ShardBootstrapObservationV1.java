package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The claim of one {@code asset-shard-bootstrap-v1} observation (ADR-059 D8c, D8h §3): a phase-2-valid L1
 * transaction that mints a vault's one-shot shard thread policy. The policy requires the mint to be exactly the 16
 * names {@code 0x00..0x0F} at +1, each in an output holding that one token and {@code ShardDatumV1(1, i, 32 zero
 * bytes)}, but it cannot pin the address (the shard script hash depends on the vault, which depends on the policy), so
 * the observer checks that each of the 16 outputs sits at the vault's pinned shard address. The consumer admits
 * deposits to a vault only after a finalized {@link Status#VERIFIED} bootstrap (the D8b/D8c gate). Because the policy
 * is one-shot, a {@link Status#REJECTED} bootstrap leaves the vault permanently unbootstrapped; a new vault is needed.
 *
 * <p>Wire form: {@code [1, domain_id, vault_id, tx_id, slot, block_hash, status, reason, [* [shard_index,
 * output_index]]]}, the 16 shard outputs in shard order when verified, none otherwise.
 */
public record ShardBootstrapObservationV1(Hash32 domainId, Hash32 vaultId, Hash32 txId, long slot, Hash32 blockHash,
                                          Reason reason, List<Long> shardOutputs) {
    public static final int VERSION = 1;
    public static final String CLAIM_SCHEMA = "asset-shard-bootstrap-claim-v1";
    public static final int SHARDS = 16;

    public enum Status {
        VERIFIED(0),
        REJECTED(1);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /** Why a bootstrap was rejected. The code is the wire value. */
    public enum Reason {
        NONE(0),
        /** The mint under the policy is not exactly the 16 shard names at +1. */
        MINT_NOT_EXACT(1),
        /** A shard token sits outside the pinned shard address. */
        WRONG_ADDRESS(2),
        /** An output holds more than one token of the policy, or a quantity other than 1. */
        NOT_ONE_TOKEN(3),
        /** A shard output's datum is not {@code ShardDatumV1(1, i, empty root)} for its own name. */
        BAD_DATUM(4),
        /** Not every one of the 16 shards has its own output at the shard address. */
        MISSING_SHARD(5);

        private final int code;

        Reason(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static Reason fromCode(long code) {
            for (Reason reason : values()) if (reason.code == code) return reason;
            throw AssetCbor.malformed("unknown shard bootstrap reason");
        }
    }

    public ShardBootstrapObservationV1 {
        Objects.requireNonNull(domainId, "domain id");
        Objects.requireNonNull(vaultId, "vault id");
        Objects.requireNonNull(txId, "tx id");
        Objects.requireNonNull(blockHash, "block hash");
        Objects.requireNonNull(reason, "reason");
        shardOutputs = List.copyOf(shardOutputs);
        if (slot < 0) throw new IllegalArgumentException("slot is non-negative");
        if ((reason == Reason.NONE) != (shardOutputs.size() == SHARDS) || (reason != Reason.NONE
                && !shardOutputs.isEmpty())) {
            throw new IllegalArgumentException("a verified bootstrap lists exactly its 16 shard outputs");
        }
        for (long index : shardOutputs) {
            if (index < 0 || index > PayoutClaimV1.MAX_INDEX) throw new IllegalArgumentException("output index");
        }
    }

    public Status status() {
        return reason == Reason.NONE ? Status.VERIFIED : Status.REJECTED;
    }

    public byte[] encode() {
        Array array = new Array();
        array.add(AssetCbor.uint(VERSION));
        array.add(AssetCbor.bstr(domainId.bytes()));
        array.add(AssetCbor.bstr(vaultId.bytes()));
        array.add(AssetCbor.bstr(txId.bytes()));
        array.add(AssetCbor.uint(slot));
        array.add(AssetCbor.bstr(blockHash.bytes()));
        array.add(AssetCbor.uint(status().code()));
        array.add(AssetCbor.uint(reason.code()));
        Array shards = new Array();
        for (int i = 0; i < shardOutputs.size(); i++) {
            Array shard = new Array();
            shard.add(AssetCbor.uint(i));
            shard.add(AssetCbor.uint(shardOutputs.get(i)));
            shards.add(shard);
        }
        array.add(shards);
        return AssetCbor.encode(array);
    }

    public static ShardBootstrapObservationV1 decode(byte[] cbor) {
        List<DataItem> items = AssetCbor.decodeArray(cbor, 9).getDataItems();
        if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("shard bootstrap version");
        List<Long> outputs = new ArrayList<>();
        List<DataItem> shards = AssetCbor.array(items.get(8), 0, SHARDS).getDataItems();
        for (int i = 0; i < shards.size(); i++) {
            List<DataItem> shard = AssetCbor.array(shards.get(i), 2, 2).getDataItems();
            if (AssetCbor.uintLong(shard.get(0)) != i) throw AssetCbor.malformed("shards out of order");
            outputs.add(AssetCbor.uintLong(shard.get(1)));
        }
        try {
            ShardBootstrapObservationV1 claim = new ShardBootstrapObservationV1(
                    Hash32.of(AssetCbor.bytes(items.get(1), 32, 32)), Hash32.of(AssetCbor.bytes(items.get(2), 32, 32)),
                    Hash32.of(AssetCbor.bytes(items.get(3), 32, 32)), AssetCbor.uintLong(items.get(4)),
                    Hash32.of(AssetCbor.bytes(items.get(5), 32, 32)),
                    Reason.fromCode(AssetCbor.uintLong(items.get(7))), outputs);
            if (claim.status().code() != AssetCbor.uintLong(items.get(6))) {
                throw AssetCbor.malformed("status disagrees with reason");
            }
            return claim;
        } catch (IllegalArgumentException invalid) {
            throw AssetCbor.malformed(invalid.getMessage());
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ShardBootstrapObservationV1 that && Arrays.equals(encode(), that.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }
}
