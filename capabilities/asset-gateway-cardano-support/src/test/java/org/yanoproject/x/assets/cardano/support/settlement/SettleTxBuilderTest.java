package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.GatewayDatums;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code Settle} builder (ADR-059 D9): the built transaction passes the compiled, parameterized
 * {@code AssetVaultValidator} and {@code AssetShardValidator} on the julc phase-2 evaluator for k = 1, 8 and 16
 * claims, on the ADA vault and a token vault, within the profile cap and the transaction size limit.
 */
class SettleTxBuilderTest {
    static PayoutWorld token;
    static PayoutWorld ada;

    @BeforeAll
    static void worlds() {
        token = new PayoutWorld(PayoutWorld.TOKEN);
        ada = new PayoutWorld(AssetId.ADA);
    }

    @TestFactory
    Stream<DynamicTest> theBuiltSettlePassesTheCompiledVaultAndShardValidators() {
        return Stream.of(1, 8, 16).flatMap(k -> Stream.of("token", "ada").map(vault ->
                DynamicTest.dynamicTest(vault + " vault, k = " + k, () -> settle(vault, k))));
    }

    void settle(String vault, int k) throws Exception {
        PayoutWorld world = vault.equals("ada") ? ada : token;
        int shard = k % 16;
        List<ClaimLeafV1> claims = world.claims(shard, k);
        world.anchor(claims, world.anchorHeight + 1);

        PayoutTxBuilder.Built built = PayoutTxBuilder.settle(world.context(shard), claims, world.mirrors[shard],
                PayoutWorld.signers(PayoutWorld.THRESHOLD), world.evaluator());

        List<EvaluationResult> results = world.evaluate(built);
        int vaultInputs = (int) built.resolvedInputs().stream().filter(in -> in.sameAddress(world.pins.vaultAddress()))
                .count();
        assertThat(results).as("one redeemer per vault input and the shard").hasSize(vaultInputs + 1);
        long mem = results.stream().mapToLong(r -> r.getExUnits().getMem().longValueExact()).sum();
        assertThat(mem).isLessThan(16_500_000L);
        assertThat(built.cbor().length + 3 * PayoutTxBuilder.VKEY_WITNESS_BYTES).isLessThanOrEqualTo(16_384);
        System.out.printf("SETTLE| %s k=%d vaultInputs=%d mem=%d bytes=%d fee=%s%n", vault, k, vaultInputs, mem,
                built.cbor().length, built.fee());

        // The layout the payout observer and the scripts read: payouts, then the exact continuation, then the shard.
        Transaction tx = built.transaction();
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < k; i++) {
            ClaimLeafV1 leaf = claims.get(i);
            assertThat(new com.bloxbean.cardano.client.address.Address(outputs.get(i).getAddress()).getBytes())
                    .isEqualTo(leaf.recipient());
        }
        assertThat(GatewayPlutus.decodeContinuation(outputs.get(k).getInlineDatum()))
                .containsExactlyElementsOf(claims.stream().map(ClaimLeafV1::claimId).toList());
        assertThat(GatewayDatums.shardDatum(outputs.get(k + 1).getInlineDatum().serializeToBytes()).orElseThrow()
                .nullifierRoot()).isEqualTo(Hash32.of(built.shardPlan().after()));
        assertThat(tx.getBody().getScriptDataHash()).hasSize(32);
        assertThat(tx.getBody().getRequiredSigners()).hasSize(PayoutWorld.THRESHOLD);
        world.confirm(built);
    }

    @Test
    void aSecondBatchOnTheSameShardChainsOnTheMirror() throws Exception {
        PayoutWorld world = token;
        List<ClaimLeafV1> first = List.of(world.claim(9, 90_001), world.claim(9, 90_002));
        List<ClaimLeafV1> second = List.of(world.claim(9, 90_003));
        world.anchor(List.of(first.get(0), first.get(1), second.getFirst()), world.anchorHeight + 1);
        PayoutTxBuilder.Built one = PayoutTxBuilder.settle(world.context(9), first, world.mirrors[9],
                PayoutWorld.signers(2), world.evaluator());
        world.evaluate(one);
        world.confirm(one);
        PayoutTxBuilder.Built two = PayoutTxBuilder.settle(world.context(9), second, world.mirrors[9],
                PayoutWorld.signers(2), world.evaluator());
        world.evaluate(two);
        // A claim already in the shard is never built again (I6).
        assertThatThrownBy(() -> PayoutTxBuilder.settle(world.context(9), first.subList(0, 1), world.mirrors[9],
                PayoutWorld.signers(2), world.evaluator())).isInstanceOf(PayoutTxBuilder.BuildException.class)
                .hasMessageContaining("already paid");
    }

    @Test
    void fragmentedCustodyIsSpentAsALeadAndFollowers() throws Exception {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        world.vaultUtxos.forEach(utxo -> world.utxos.remove(utxo.outpoint()));
        world.vaultUtxos.clear();
        for (int i = 0; i < 4; i++) {
            world.vaultUtxos.add(world.add(new ResolvedOutput(PayoutWorld.outpoint(0x67, i), world.pins.vaultAddress(),
                    BigInteger.valueOf(6_000_000), PayoutWorld.assets(PayoutWorld.TOKEN, 40_000),
                    Optional.of(new byte[] {(byte) 0xd8, 0x7a, (byte) 0x81, (byte) 0x80}), Optional.empty())));
        }
        List<ClaimLeafV1> claims = world.claims(13, 8); // about 112,000 tokens and 12 ADA of vaultLovelace
        world.anchor(claims, 70_000);
        PayoutTxBuilder.Built built = PayoutTxBuilder.settle(world.context(13), claims, world.mirrors[13],
                PayoutWorld.signers(2), world.evaluator());
        long vaultInputs = built.resolvedInputs().stream().filter(in -> in.sameAddress(world.pins.vaultAddress()))
                .count();
        assertThat(vaultInputs).as("canonical-order selection stops once the debit and floor are covered")
                .isEqualTo(3);
        assertThat(world.evaluate(built)).hasSize(4);
        long follows = built.transaction().getWitnessSet().getRedeemers().stream()
                .filter(redeemer -> GatewayPlutus.isFollow(redeemer.getData())).count();
        assertThat(follows).isEqualTo(2);
    }

    @Test
    void theBuilderRefusesBatchesItCannotPay() {
        PayoutWorld world = token;
        List<ClaimLeafV1> seventeen = world.claims(4, 17);
        assertThatThrownBy(() -> PayoutTxBuilder.settle(world.context(4), seventeen, world.mirrors[4],
                PayoutWorld.signers(2), world.evaluator())).hasMessageContaining("at most 16");
        List<ClaimLeafV1> mixed = List.of(world.claim(4, 1), world.claim(5, 2));
        assertThatThrownBy(() -> PayoutTxBuilder.settle(world.context(4), mixed, world.mirrors[4],
                PayoutWorld.signers(2), world.evaluator())).hasMessageContaining("two shards");
        // A stale mirror (a third-party payout the executor has not seen) is refused, not built (D8h §6).
        ShardMirror stale = ShardMirror.of(4, List.of(world.claim(4, 77).claimId()));
        assertThatThrownBy(() -> PayoutTxBuilder.settle(world.context(4), List.of(world.claim(4, 3)), stale,
                PayoutWorld.signers(2), world.evaluator())).hasMessageContaining("mirror");
        assertThat(PayoutTxBuilder.refScriptFee(PayoutWorld.PARAMS, 25_600 + 100))
                .isEqualTo(BigInteger.valueOf(25_600L * 15 + 18 * 100));
    }
}
