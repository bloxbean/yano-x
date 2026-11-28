package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An off-chain mirror of one nullifier shard (ADR-059 D8c, D8h §6): the Aiken-format MPF trie of the paid ids of one
 * shard of one vault, where the value of an id is the id itself, exactly as {@code AssetShardValidator} folds it.
 *
 * <p>The executor builds it from the confirmed inserts ({@code PAID} claims in committed state) plus its own
 * submitted inserts that are not yet stable, and checks the result against the live shard datum root before using it
 * ({@link #matches}). A mismatch means a third-party {@code Exit} or a rollback; the caller then drops the optimistic
 * part and waits for confirmations.
 */
public final class ShardMirror {
    /** One insert of a shard redeemer: the id and its exclusion proof under the running root. */
    public record Insert(Hash32 id, PlutusData proof) {
    }

    /** A planned batch: the inserts in order, and the roots before and after. */
    public record Plan(List<Insert> inserts, byte[] before, byte[] after) {
        public Plan {
            inserts = List.copyOf(inserts);
            before = before.clone();
            after = after.clone();
        }
    }

    private final int shardIndex;
    private final MpfTrie trie = new MpfTrie(new MapNodeStore());
    private final Set<Hash32> ids = new LinkedHashSet<>();

    public ShardMirror(int shardIndex) {
        if (shardIndex < 0 || shardIndex >= SettlementBatchV1.SHARDS) {
            throw new IllegalArgumentException("shard index is 0-15");
        }
        this.shardIndex = shardIndex;
    }

    /** A mirror holding exactly {@code paid}. */
    public static ShardMirror of(int shardIndex, Collection<Hash32> paid) {
        ShardMirror mirror = new ShardMirror(shardIndex);
        paid.forEach(mirror::insert);
        return mirror;
    }

    public int shardIndex() {
        return shardIndex;
    }

    public boolean contains(Hash32 id) {
        return ids.contains(id);
    }

    public Set<Hash32> ids() {
        return Set.copyOf(ids);
    }

    /** The current root; the empty shard's root is 32 zero bytes (the bootstrap datum). */
    public byte[] root() {
        byte[] root = trie.getRootHash();
        return root == null ? new byte[Hash32.BYTES] : root;
    }

    /** True when this mirror's root equals the live shard datum's root. */
    public boolean matches(Hash32 liveRoot) {
        return java.util.Arrays.equals(root(), liveRoot.bytes());
    }

    public void insert(Hash32 id) {
        requireShard(id);
        if (!ids.add(id)) throw new IllegalArgumentException("id " + id + " is already in the shard");
        trie.put(id.bytes(), id.bytes());
    }

    /**
     * The insert batch for {@code batch} from the current root: each id's exclusion proof at the running root, then
     * the insert. The mirror is left unchanged; call {@link #apply} once the transaction is submitted.
     */
    public Plan plan(List<Hash32> batch) {
        Objects.requireNonNull(batch, "batch");
        byte[] before = root();
        List<Insert> inserts = new ArrayList<>();
        List<Hash32> done = new ArrayList<>();
        try {
            for (Hash32 id : batch) {
                requireShard(id);
                if (ids.contains(id)) throw new IllegalArgumentException("id " + id + " is already paid");
                PlutusData proof = trie.getProofPlutusData(id.bytes())
                        .orElseThrow(() -> new IllegalStateException("no proof for " + id));
                inserts.add(new Insert(id, proof));
                insert(id);
                done.add(id);
            }
            return new Plan(inserts, before, root());
        } finally {
            for (Hash32 id : done) remove(id);
        }
    }

    /** Applies a planned batch optimistically (D8h §6). */
    public void apply(Plan plan) {
        if (!java.util.Arrays.equals(plan.before(), root())) {
            throw new IllegalStateException("the plan was made against another root");
        }
        plan.inserts().forEach(insert -> insert(insert.id()));
    }

    private void remove(Hash32 id) {
        ids.remove(id);
        trie.delete(id.bytes());
    }

    private void requireShard(Hash32 id) {
        if (SettlementBatchV1.shardOf(id) != shardIndex) {
            throw new IllegalArgumentException("id " + id + " routes to another shard");
        }
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();

        @Override
        public byte[] get(byte[] hash) {
            return nodes.get(HexFormat.of().formatHex(hash));
        }

        @Override
        public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(HexFormat.of().formatHex(hash), nodeBytes);
        }

        @Override
        public void delete(byte[] hash) {
            nodes.remove(HexFormat.of().formatHex(hash));
        }
    }
}
