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
        assertThat(vaultInputs).as("selection stops once the debit and the continuation's requirement are covered")
                .isEqualTo(3);
        assertThat(world.evaluate(built)).hasSize(4);
        long follows = built.transaction().getWitnessSet().getRedeemers().stream()
                .filter(redeemer -> GatewayPlutus.isFollow(redeemer.getData())).count();
        assertThat(follows).isEqualTo(2);
    }

    static final byte[] FLOOR_TOP_UP = {(byte) 0xd8, 0x7b, (byte) 0x80};

    /** Review P1 (dust starves selection): eight dust top-ups at the lowest outpoints never block a Settle. */
    @Test
    void dustTopUpsAtLowOutpointsDoNotStarveTheSelection() throws Exception {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        for (int i = 0; i < 8; i++) {
            world.vaultUtxos.add(world.add(new ResolvedOutput(PayoutWorld.outpoint(0x10, i), world.pins.vaultAddress(),
                    BigInteger.valueOf(1_000_000), new java.util.TreeMap<>(), Optional.of(FLOOR_TOP_UP),
                    Optional.empty())));
        }
        List<ClaimLeafV1> claims = world.claims(2, 4);
        world.anchor(claims, 80_000);
        PayoutTxBuilder.Built built = PayoutTxBuilder.settle(world.context(2), claims, world.mirrors[2],
                PayoutWorld.signers(2), world.evaluator());
        List<ResolvedOutput> vaultInputs = built.resolvedInputs().stream()
                .filter(in -> in.sameAddress(world.pins.vaultAddress())).toList();
        assertThat(vaultInputs).as("the custody that pays, not the dust").singleElement()
                .satisfies(in -> assertThat(in.outpoint()).isEqualTo(PayoutWorld.outpoint(0x61, 0)));
        world.evaluate(built);
    }

    /** Review P2-c: the selection covers the serialized continuation's min-UTxO, not just the floor. */
    @Test
    void theSelectionCoversTheContinuationMinUtxoAboveTheFloor() throws Exception {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        world.vaultUtxos.forEach(utxo -> world.utxos.remove(utxo.outpoint()));
        world.vaultUtxos.clear();
        List<ClaimLeafV1> claims = world.claims(6, 16);
        long vaultLovelace = claims.stream().mapToLong(leaf -> leaf.vaultLovelace().longValueExact()).sum();
        // Exactly the debit plus the floor: a 16-id continuation needs more than the 2 ADA floor.
        world.vaultUtxos.add(world.add(new ResolvedOutput(PayoutWorld.outpoint(0x61, 0), world.pins.vaultAddress(),
                BigInteger.valueOf(vaultLovelace + PayoutWorld.FLOOR), PayoutWorld.assets(PayoutWorld.TOKEN, 1_000_000),
                Optional.of(new byte[] {(byte) 0xd8, 0x7a, (byte) 0x81, (byte) 0x80}), Optional.empty())));
        world.vaultUtxos.add(world.add(new ResolvedOutput(PayoutWorld.outpoint(0x61, 1), world.pins.vaultAddress(),
                BigInteger.valueOf(3_000_000), new java.util.TreeMap<>(), Optional.of(FLOOR_TOP_UP),
                Optional.empty())));
        world.anchor(claims, 81_000);
        PayoutTxBuilder.Built built = PayoutTxBuilder.settle(world.context(6), claims, world.mirrors[6],
                PayoutWorld.signers(2), world.evaluator());
        assertThat(built.resolvedInputs().stream().filter(in -> in.sameAddress(world.pins.vaultAddress())).count())
                .isEqualTo(2);
        world.evaluate(built);
    }

    /** Review P1 (foreign asset in a shard): a shard input holding another token is never spent. */
    @Test
    void aShardInputHoldingAForeignTokenIsRefused() {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        ResolvedOutput honest = world.shards[3];
        java.util.TreeMap<AssetId, BigInteger> assets = new java.util.TreeMap<>(honest.assets());
        assets.put(PayoutWorld.TOKEN, BigInteger.ONE);
        ResolvedOutput dusted = new ResolvedOutput(honest.outpoint(), honest.address(), honest.lovelace(), assets,
                honest.inlineDatum(), honest.referenceScript());
        PayoutTxBuilder.Context base = world.context(3);
        PayoutTxBuilder.Context context = new PayoutTxBuilder.Context(base.pins(), base.vaultUtxos(), dusted,
                base.anchor(), base.exitMode(), base.scriptRefs(), base.funding(), base.validFromSlot(), base.ttlSlot(),
                base.protocolParams(), base.maxVaultInputs());
        assertThatThrownBy(() -> PayoutTxBuilder.settle(context, List.of(world.claim(3, 1)), world.mirrors[3],
                PayoutWorld.signers(2), world.evaluator())).hasMessageContaining("only its thread token");
    }

    /** Review P2-d: margins never take the declared units past the per-transaction limits. */
    @Test
    void declaredUnitsStayWithinTheProtocolLimits() {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        List<ClaimLeafV1> claims = world.claims(7, 1);
        world.anchor(claims, 82_000);
        long maxMem = Long.parseLong(PayoutWorld.PARAMS.getMaxTxExMem());
        long maxSteps = Long.parseLong(PayoutWorld.PARAMS.getMaxTxExSteps());
        // Two redeemers (one vault input, the shard) at 49.5% of each limit: 99% measured, 104% with the margin.
        PayoutTxBuilder.Built built = PayoutTxBuilder.settle(world.context(7), claims, world.mirrors[7],
                PayoutWorld.signers(2), units(maxMem * 495 / 1000, maxSteps * 495 / 1000));
        var redeemers = built.transaction().getWitnessSet().getRedeemers();
        assertThat(redeemers).hasSize(2);
        assertThat(redeemers.stream().mapToLong(r -> r.getExUnits().getMem().longValueExact()).sum())
                .isLessThanOrEqualTo(maxMem).isGreaterThanOrEqualTo(maxMem * 99 / 100);
        assertThat(redeemers.stream().mapToLong(r -> r.getExUnits().getSteps().longValueExact()).sum())
                .isLessThanOrEqualTo(maxSteps);
        // Measured units alone over a limit: refused for a split.
        assertThatThrownBy(() -> PayoutTxBuilder.settle(world.context(7), claims, world.mirrors[7],
                PayoutWorld.signers(2), units(maxMem / 2 + 1, maxSteps / 4)))
                .isInstanceOf(PayoutTxBuilder.LimitExceeded.class);
    }

    /** Review P2-e: maxTxSize is judged on the signed size, with the final witness count. */
    @Test
    void theSizeLimitIsJudgedOnTheSignedTransaction() throws Exception {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        List<ClaimLeafV1> claims = world.claims(8, 4);
        world.anchor(claims, 83_000);
        PayoutTxBuilder.Built built = PayoutTxBuilder.settle(world.context(8), claims, world.mirrors[8],
                PayoutWorld.signers(2), world.evaluator());
        // Two members and the fee wallet sign, as the executor does.
        Transaction signed = Transaction.deserialize(built.cbor());
        List<com.bloxbean.cardano.client.transaction.spec.VkeyWitness> witnesses = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            witnesses.add(new com.bloxbean.cardano.client.transaction.spec.VkeyWitness(PayoutWorld.filled(32, i + 1),
                    PayoutWorld.filled(64, i + 7)));
        }
        signed.getWitnessSet().setVkeyWitnesses(witnesses);
        int size = signed.serialize().length;
        var exact = PayoutWorld.protocolParams();
        exact.setMaxTxSize(size);
        PayoutTxBuilder.settle(world.context(8, PayoutWorld.NOW_SLOT, PayoutWorld.NOW_SLOT + 600, exact), claims,
                world.mirrors[8], PayoutWorld.signers(2), world.evaluator());
        var tight = PayoutWorld.protocolParams();
        tight.setMaxTxSize(size - 1);
        assertThatThrownBy(() -> PayoutTxBuilder.settle(world.context(8, PayoutWorld.NOW_SLOT,
                PayoutWorld.NOW_SLOT + 600, tight), claims, world.mirrors[8], PayoutWorld.signers(2),
                world.evaluator())).isInstanceOf(PayoutTxBuilder.LimitExceeded.class);
    }

    /** A supplement is the payout's min-UTxO above what the vault pays (D11). */
    @Test
    void theSupplementIsTheMinUtxoAboveTheVaultLovelace() {
        PayoutWorld world = token;
        ClaimLeafV1 funded = world.claim(4, 40);
        assertThat(PayoutTxBuilder.payoutSupplement(world.pins, funded, PayoutWorld.PARAMS)).isZero();
        ClaimLeafV1 unfunded = new ClaimLeafV1(funded.claimId(), funded.vaultId(), funded.kind(), funded.value(),
                BigInteger.ZERO, funded.recipient(), funded.reservedHeight(), funded.reservedAtMs());
        assertThat(PayoutTxBuilder.payoutSupplement(world.pins, unfunded, PayoutWorld.PARAMS))
                .isGreaterThan(BigInteger.valueOf(900_000));
    }

    static ResolvedOutput custody(PayoutWorld world, int index, long tokens, long lovelace) {
        boolean topUp = tokens == 0;
        return world.add(new ResolvedOutput(PayoutWorld.outpoint(0x70, index), world.pins.vaultAddress(),
                BigInteger.valueOf(lovelace), PayoutWorld.assets(PayoutWorld.TOKEN, tokens),
                Optional.of(topUp ? FLOOR_TOP_UP : new byte[] {(byte) 0xd8, 0x7a, (byte) 0x81, (byte) 0x80}),
                Optional.empty()));
    }

    static PayoutTxBuilder.Context withCustody(PayoutWorld world, int shard, List<ResolvedOutput> custody,
                                                int maxVaultInputs) {
        PayoutTxBuilder.Context base = world.context(shard);
        return new PayoutTxBuilder.Context(base.pins(), custody, base.shardInput(), base.anchor(), base.exitMode(),
                base.scriptRefs(), base.funding(), base.validFromSlot(), base.ttlSlot(), base.protocolParams(),
                maxVaultInputs);
    }

    /** Re-review N2: once the token debit is covered, the remaining inputs are chosen for their lovelace. */
    @Test
    void afterTheTokenDebitIsCoveredInputsAreChosenByLovelace() {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        ResolvedOutput hundred = custody(world, 0, 100, 2_000_000);
        ResolvedOutput fifty = custody(world, 1, 50, 2_000_000);
        ResolvedOutput lovelace = custody(world, 2, 0, 20_000_000);
        List<ResolvedOutput> selected = PayoutTxBuilder.selectVaultInputs(
                withCustody(world, 0, List.of(hundred, fifty, lovelace), 2), BigInteger.valueOf(100),
                BigInteger.valueOf(4_000_000), List.of(world.claim(0, 1).claimId()));
        assertThat(selected).containsExactly(hundred, lovelace);
    }

    /** Re-review N3: the continuation is priced with its actual lovelace, not the widest coin. */
    @Test
    void aContinuationJustAboveItsMinUtxoBuilds() throws Exception {
        PayoutWorld world = new PayoutWorld(AssetId.ADA);
        world.vaultUtxos.forEach(utxo -> world.utxos.remove(utxo.outpoint()));
        world.vaultUtxos.clear();
        List<ClaimLeafV1> claims = world.claims(11, 16);
        long paid = claims.stream().mapToLong(leaf -> leaf.value().quantityOf(AssetId.ADA).longValueExact()).sum();
        world.vaultUtxos.add(world.add(new ResolvedOutput(PayoutWorld.outpoint(0x61, 0), world.pins.vaultAddress(),
                BigInteger.valueOf(paid + 3_270_000), new java.util.TreeMap<>(),
                Optional.of(new byte[] {(byte) 0xd8, 0x7a, (byte) 0x81, (byte) 0x80}), Optional.empty())));
        world.anchor(claims, 84_000);
        // The case: 3,270,000 lovelace covers the 16-id continuation's min-UTxO, but not its widest-coin price.
        var minAda = new com.bloxbean.cardano.client.api.MinAdaCalculator(PayoutWorld.PARAMS);
        var ids = GatewayPlutus.continuation(claims.stream().map(ClaimLeafV1::claimId).toList());
        assertThat(minAda.calculateMinAda(PayoutTxBuilder.output(world.pins.vaultAddress(),
                BigInteger.valueOf(3_270_000), java.util.Map.of(), ids)))
                .isLessThanOrEqualTo(BigInteger.valueOf(3_270_000));
        assertThat(minAda.calculateMinAda(PayoutTxBuilder.output(world.pins.vaultAddress(),
                BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE), java.util.Map.of(), ids)))
                .isGreaterThan(BigInteger.valueOf(3_270_000));
        PayoutTxBuilder.Built built = PayoutTxBuilder.settle(world.context(11), claims, world.mirrors[11],
                PayoutWorld.signers(2), world.evaluator());
        assertThat(built.transaction().getBody().getOutputs().get(16).getValue().getCoin())
                .isEqualTo(BigInteger.valueOf(3_270_000));
        world.evaluate(built);
    }

    /** Re-review N4 (A): a script out of budget is a per-transaction limit, so the caller splits. */
    @Test
    void anExhaustedBudgetIsALimitTheCallerSplits() {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        List<ClaimLeafV1> claims = world.claims(12, 2);
        world.anchor(claims, 85_000);
        com.bloxbean.cardano.client.api.TransactionEvaluator exhausted = (cbor, inputs) ->
                com.bloxbean.cardano.client.api.model.Result.error("Budget exhausted for Spend[1]");
        assertThatThrownBy(() -> PayoutTxBuilder.settle(world.context(12), claims, world.mirrors[12],
                PayoutWorld.signers(2), exhausted)).isInstanceOf(PayoutTxBuilder.LimitExceeded.class);
        com.bloxbean.cardano.client.api.TransactionEvaluator broken = (cbor, inputs) ->
                com.bloxbean.cardano.client.api.model.Result.error("the vault script failed");
        assertThatThrownBy(() -> PayoutTxBuilder.settle(world.context(12), claims, world.mirrors[12],
                PayoutWorld.signers(2), broken)).isInstanceOf(PayoutTxBuilder.BuildException.class)
                .isNotInstanceOf(PayoutTxBuilder.LimitExceeded.class);
    }

    /** Re-review N4 (B): needing more than max-vault-inputs is a limit; custody that cannot cover is not. */
    @Test
    void exhaustingTheVaultInputCapIsALimitTheCallerSplits() {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        List<ResolvedOutput> fragments = List.of(custody(world, 0, 40, 6_000_000), custody(world, 1, 40, 6_000_000),
                custody(world, 2, 40, 6_000_000));
        List<Hash32> ids = List.of(world.claim(0, 1).claimId());
        assertThatThrownBy(() -> PayoutTxBuilder.selectVaultInputs(withCustody(world, 0, fragments, 2),
                BigInteger.valueOf(100), BigInteger.valueOf(1_000_000), ids))
                .isInstanceOf(PayoutTxBuilder.LimitExceeded.class);
        assertThatThrownBy(() -> PayoutTxBuilder.selectVaultInputs(withCustody(world, 0, fragments, 8),
                BigInteger.valueOf(200), BigInteger.valueOf(1_000_000), ids))
                .isInstanceOf(PayoutTxBuilder.BuildException.class)
                .isNotInstanceOf(PayoutTxBuilder.LimitExceeded.class);
    }

    static com.bloxbean.cardano.client.api.TransactionEvaluator units(long mem, long steps) {
        return (cbor, inputs) -> {
            try {
                List<EvaluationResult> results = new java.util.ArrayList<>();
                for (var redeemer : Transaction.deserialize(cbor).getWitnessSet().getRedeemers()) {
                    EvaluationResult result = new EvaluationResult();
                    result.setRedeemerTag(redeemer.getTag());
                    result.setIndex(redeemer.getIndex().intValue());
                    result.setExUnits(com.bloxbean.cardano.client.plutus.spec.ExUnits.builder()
                            .mem(BigInteger.valueOf(mem)).steps(BigInteger.valueOf(steps)).build());
                    results.add(result);
                }
                return com.bloxbean.cardano.client.api.model.Result.success("ok").withValue(results);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
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
