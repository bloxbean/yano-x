package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * The payload of one {@code asset.settlement.batch} effect (ADR-059 D7 step 4, D9): the ordered claim ids of one
 * shard of one vault that the consumer's ledger queued for co-signed settlement.
 *
 * <p>Wire form: {@code [1, vault_id: hash32, shard_index: 0..15, [+ claim_id: hash32]]}. Every id routes to
 * {@code shardIndex} by its last nibble (V4), ids are distinct, and a batch holds at most
 * {@link #MAX_CLAIMS} (the on-chain bound of a continuation's id list; a deployment's {@code maxSettleBatch} is
 * smaller). The payload names claims only: the executor reads every claim field from committed state, so a payload
 * can never author a claim (D7 "who authors a claim").
 */
public record SettlementBatchV1(Hash32 vaultId, int shardIndex, List<Hash32> claimIds) {
    public static final int VERSION = 1;
    /** The effect type the consumer emits and the {@code asset-settlement} executor owns. */
    public static final String EFFECT_TYPE = "asset.settlement.batch";
    /** Every batch effect's idempotency scope starts with this, followed by the batch sequence. */
    public static final String SCOPE_PREFIX = "asset-settlement/";
    public static final int MAX_CLAIMS = VaultOutputShape.MAX_CONTINUATION_CLAIMS;
    public static final int SHARDS = 16;

    public SettlementBatchV1 {
        Objects.requireNonNull(vaultId, "vault id");
        if (shardIndex < 0 || shardIndex >= SHARDS) throw new IllegalArgumentException("shard index is 0-15");
        claimIds = List.copyOf(claimIds);
        if (claimIds.isEmpty() || claimIds.size() > MAX_CLAIMS) {
            throw new IllegalArgumentException("a batch holds 1-" + MAX_CLAIMS + " claims");
        }
        if (new HashSet<>(claimIds).size() != claimIds.size()) throw new IllegalArgumentException("duplicate claim");
        for (Hash32 id : claimIds) {
            if (shardOf(id) != shardIndex) throw new IllegalArgumentException("claim routes to another shard");
        }
    }

    /** The shard a claim id routes to: its last nibble (V4). */
    public static int shardOf(Hash32 claimId) {
        return claimId.bytes()[Hash32.BYTES - 1] & 0x0f;
    }

    /** The effect scope of batch {@code sequence}. */
    public static String scope(long sequence) {
        if (sequence < 0) throw new IllegalArgumentException("sequence is non-negative");
        return SCOPE_PREFIX + sequence;
    }

    /** The batch sequence a scope names, or -1 when it is not a settlement batch scope. */
    public static long sequenceOf(String scope) {
        if (scope == null || !scope.startsWith(SCOPE_PREFIX)) return -1;
        String digits = scope.substring(SCOPE_PREFIX.length());
        if (digits.isEmpty() || digits.length() > 19 || !digits.chars().allMatch(Character::isDigit)
                || (digits.length() > 1 && digits.charAt(0) == '0')) {
            return -1;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException overflow) {
            return -1;
        }
    }

    public byte[] encode() {
        Array array = new Array();
        array.add(AssetCbor.uint(VERSION));
        array.add(AssetCbor.bstr(vaultId.bytes()));
        array.add(AssetCbor.uint(shardIndex));
        Array ids = new Array();
        claimIds.forEach(id -> ids.add(AssetCbor.bstr(id.bytes())));
        array.add(ids);
        return AssetCbor.encode(array);
    }

    public static SettlementBatchV1 decode(byte[] cbor) {
        List<DataItem> items = AssetCbor.decodeArray(cbor, 4).getDataItems();
        if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("settlement batch version");
        long shard = AssetCbor.uintLong(items.get(2));
        if (shard >= SHARDS) throw AssetCbor.malformed("shard index");
        List<Hash32> ids = new ArrayList<>();
        for (DataItem item : AssetCbor.array(items.get(3), 1, MAX_CLAIMS).getDataItems()) {
            ids.add(Hash32.of(AssetCbor.bytes(item, 32, 32)));
        }
        try {
            return new SettlementBatchV1(Hash32.of(AssetCbor.bytes(items.get(1), 32, 32)), (int) shard, ids);
        } catch (IllegalArgumentException invalid) {
            throw AssetCbor.malformed(invalid.getMessage());
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SettlementBatchV1 that && Arrays.equals(encode(), that.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }
}
