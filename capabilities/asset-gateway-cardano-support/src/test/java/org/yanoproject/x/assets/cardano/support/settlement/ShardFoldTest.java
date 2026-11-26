package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
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

    /**
     * Re-review N1, differential against the compiled {@code AssetShardValidator}: an honest exclusion proof is
     * accepted by both, and a terminal leaf whose skip is moved past the nibble where the keys already differ (its
     * exclusion root still folds) is rejected by both. Without the shape rules only the validator rejects it.
     */
    @Test
    void theFoldAcceptsExactlyTheProofsTheShardValidatorAccepts() throws Exception {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        int shard = 4;
        // Two claims of the shard whose paths differ at nibble 0, so the second's exclusion proof is a terminal leaf.
        ClaimLeafV1 first = world.claim(shard, 7_000);
        ClaimLeafV1 second = null;
        for (int seed = 7_001; second == null; seed++) {
            ClaimLeafV1 candidate = world.claim(shard, seed);
            if ((path(candidate)[0] & 0xf0) != (path(first)[0] & 0xf0)) second = candidate;
        }
        world.anchor(List.of(first, second), 90_000);
        PayoutTxBuilder.Built one = PayoutTxBuilder.settle(world.context(shard), List.of(first), world.mirrors[shard],
                PayoutWorld.signers(2), world.evaluator());
        world.evaluate(one);
        world.confirm(one);
        ResolvedOutput shardInput = world.shards[shard];
        PayoutTxBuilder.Built honest = PayoutTxBuilder.settle(world.context(shard), List.of(second),
                world.mirrors[shard], PayoutWorld.signers(2), world.evaluator());
        world.evaluate(honest);
        Transaction tx = Transaction.deserialize(honest.cbor());
        Redeemer shardRedeemer = shardRedeemer(tx, shardInput);
        byte[] before = world.mirrors[shard].root();
        assertThat(ShardFold.postInsertRoot(before, shard, shardRedeemer.getData())).isPresent();

        // The forged shape: the terminal leaf's skip 0 becomes 1.
        ConstrPlutusData insert = (ConstrPlutusData) ((ListPlutusData) ((ConstrPlutusData) shardRedeemer.getData())
                .getData().getPlutusDataList().getFirst()).getPlutusDataList().getFirst();
        ListPlutusData proof = (ListPlutusData) insert.getData().getPlutusDataList().get(1);
        assertThat(proof.getPlutusDataList()).singleElement().satisfies(step -> {
            assertThat(((ConstrPlutusData) step).getAlternative()).isEqualTo(2L);
            assertThat(GatewayPlutus.integer(((ConstrPlutusData) step).getData().getPlutusDataList().getFirst()))
                    .isZero();
        });
        List<PlutusData> leaf = ((ConstrPlutusData) proof.getPlutusDataList().getFirst()).getData()
                .getPlutusDataList();
        PlutusData forgedStep = ConstrPlutusData.of(2, BigIntPlutusData.of(1), leaf.get(1), leaf.get(2));
        PlutusData forged = ConstrPlutusData.of(0, GatewayPlutus.list(List.of(ConstrPlutusData.of(0,
                insert.getData().getPlutusDataList().getFirst(), GatewayPlutus.list(List.of(forgedStep))))));
        byte[] forgedRoot = ShardFold.fold(before, shard, forged, false).orElseThrow();
        assertThat(forgedRoot).as("the forged proof's roots fold").isNotEqualTo(honest.shardPlan().after());
        assertThat(ShardFold.postInsertRoot(before, shard, forged)).as("ShardFold rejects the shape").isEmpty();

        // The validator rejects the same transaction with the forged proof and its own folded root.
        shardRedeemer.setData(forged);
        tx.getBody().getOutputs().get(2).setInlineDatum(GatewayPlutus.shardDatum(shard, forgedRoot));
        PayoutTxBuilder.setScriptDataHash(tx, PayoutTxBuilder.costModels(PayoutWorld.PARAMS));
        var result = world.evaluator().evaluateTx(tx.serialize(), PayoutTxBuilder.utxos(honest.resolvedInputs()));
        assertThat(result.isSuccessful()).as("the shard validator rejects the shape").isFalse();
    }

    static byte[] path(ClaimLeafV1 leaf) {
        return Blake2bUtil.blake2bHash256(leaf.claimId().bytes());
    }

    static Redeemer shardRedeemer(Transaction tx, ResolvedOutput shardInput) {
        List<TransactionInput> inputs = tx.getBody().getInputs();
        int index = -1;
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).getTransactionId().equals(shardInput.outpoint().txIdHex())
                    && inputs.get(i).getIndex() == shardInput.outpoint().index()) {
                index = i;
            }
        }
        int at = index;
        return tx.getWitnessSet().getRedeemers().stream().filter(r -> r.getIndex().intValue() == at).findFirst()
                .orElseThrow();
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
