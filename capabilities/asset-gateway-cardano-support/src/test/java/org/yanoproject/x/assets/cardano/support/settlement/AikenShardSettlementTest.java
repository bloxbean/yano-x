package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-059 D8c, r23: with {@code shard-backend = aiken} the builders, the shard mirror and the members' body check
 * are unchanged. They read the pinned shard hash and address, and the proofs they build and check pass the Aiken
 * shard validator, evaluated on the julc phase-2 evaluator like the julc one.
 */
class AikenShardSettlementTest {
    private static final DomainStatusV1 ACTIVE = new DomainStatusV1(DomainStatusV1.Status.ACTIVE, 50, 0,
            PayoutWorld.EXIT_DELAY);

    @Test
    void theAikenWorldPinsTheAikenShard() {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN, ShardBackend.AIKEN);
        assertThat(world.pins.shardBackend()).isEqualTo(ShardBackend.AIKEN);
        assertThat(world.pins.shardScript().scriptHash()).isEqualTo(world.shardHash);
        assertThat(ShardBackend.JULC.derive(PayoutWorld.SHARD_POLICY, world.vaultHash, 0).scriptHash())
                .isNotEqualTo(world.shardHash);
        // Pins that name the Aiken shard under the julc backend are refused.
        VaultPins pins = world.pins;
        assertThatThrownBy(() -> new VaultPins(pins.vaultId(), pins.asset(), 0, pins.vaultScriptHash(),
                pins.vaultAddress(), pins.shardPolicyId(), pins.shardScriptHash(), pins.shardAddress(), pins.anchor(),
                pins.exitModePolicyId(), pins.exitModeScriptHash(), pins.claimKeyPrefix(), pins.depositKeyPrefix(),
                pins.domainStatusKey(), pins.continuationFloor(), pins.maxSettleBatch(), pins.maxExitBatch(),
                pins.maxPauseMs(), ShardBackend.JULC)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("julc");
    }

    @Test
    void aSettleBuiltForTheAikenShardPassesItAndTheMembersBodyCheck() throws Exception {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN, ShardBackend.AIKEN);
        for (int k : new int[] {1, 16}) {
            int shard = k % 16;
            List<ClaimLeafV1> claims = world.claims(shard, k);
            world.anchor(claims, 20_000 + k);
            PayoutTxBuilder.Built built = PayoutTxBuilder.settle(world.context(shard), claims, world.mirrors[shard],
                    PayoutWorld.signers(PayoutWorld.THRESHOLD), world.evaluator());
            List<EvaluationResult> results = world.evaluate(built);
            long mem = results.stream().mapToLong(r -> r.getExUnits().getMem().longValueExact()).sum();
            System.out.printf("SETTLE(aiken)| token k=%d mem=%d bytes=%d fee=%s%n", k, mem, built.cbor().length,
                    built.fee());
            assertThat(mem).isLessThan(16_500_000L);

            SettleBodyCheckTest.State state = new SettleBodyCheckTest.State(claims);
            SettleBodyCheck.Outcome outcome = SettleBodyCheck.check(built.cbor(), world.pins,
                    Blake2bUtil.blake2bHash224(PayoutWorld.MEMBERS.get(2)), world::resolve, state,
                    PayoutTxBuilder.costModels(PayoutWorld.PARAMS), PayoutWorld.tip());
            assertThat(outcome).as("k = %d", k).isInstanceOf(SettleBodyCheck.Signable.class);
            world.confirm(built);
        }
    }

    @Test
    void aRelayedExitBuiltForTheAikenShardPassesIt() throws Exception {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN, ShardBackend.AIKEN);
        int shard = 2;
        List<ClaimLeafV1> claims = world.claims(shard, 8);
        world.anchor(claims, 50_000);
        List<ExitRelayer.Batch> batches = ExitRelayer.plan(claims.stream()
                        .map(leaf -> new ExitRelayer.Candidate(leaf, ClaimStatus.RESERVED)).toList(), world.pins,
                world.exitMode, world.anchorHeight, ACTIVE, PayoutWorld.NOW_MS, index -> world.mirrors[index]);
        assertThat(batches).singleElement();
        PayoutTxBuilder.Built built = ExitRelayer.build(world.context(shard), batches.getFirst(),
                key -> Optional.of(world.state.proof(key)), world.mirrors[shard], world.evaluator());
        long mem = world.evaluate(built).stream().mapToLong(r -> r.getExUnits().getMem().longValueExact()).sum();
        System.out.printf("EXIT(aiken)| token k=8 mem=%d bytes=%d fee=%s%n", mem, built.cbor().length, built.fee());
        assertThat(mem).isLessThan(16_500_000L);
    }
}
