package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.Hash32;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The off-chain shard root fold (ADR-059 D8c) agrees with the shard mirror: the inserts of a planned batch fold from
 * the plan's root to its post-insert root, on an empty and on a populated shard, and an insert that is already
 * present, or that routes to another shard, folds to nothing.
 */
class ShardFoldTest {
    static Hash32 id(int shard, int seed) {
        byte[] id = Blake2bUtil.blake2bHash256(("fold-" + seed).getBytes());
        id[31] = (byte) ((id[31] & 0xf0) | shard);
        return Hash32.of(id);
    }

    static List<Hash32> ids(int shard, int from, int count) {
        List<Hash32> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) ids.add(id(shard, from + i));
        return ids;
    }

    @Test
    void theFoldOfAPlannedBatchIsThePlansPostInsertRoot() {
        ShardMirror mirror = new ShardMirror(5);
        for (int round = 0; round < 4; round++) {
            ShardMirror.Plan plan = mirror.plan(ids(5, round * 100, round + 1));
            PlutusData redeemer = GatewayPlutus.shardRedeemer(plan.inserts());
            assertThat(ShardFold.postInsertRoot(plan.before(), 5, redeemer)).hasValueSatisfying(root ->
                    assertThat(root).isEqualTo(plan.after()));
            mirror.apply(plan);
        }
    }

    @Test
    void aStaleRootOrAnotherShardFoldsToNothing() {
        ShardMirror mirror = ShardMirror.of(9, ids(9, 0, 3));
        ShardMirror.Plan plan = mirror.plan(ids(9, 50, 2));
        PlutusData redeemer = GatewayPlutus.shardRedeemer(plan.inserts());
        assertThat(ShardFold.postInsertRoot(new byte[32], 9, redeemer)).isEmpty();
        assertThat(ShardFold.postInsertRoot(plan.before(), 8, redeemer)).isEmpty();
    }
}
