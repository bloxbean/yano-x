package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code Accept} builder of ADR-059 D8f against the compiled, parameterized vault validator: co-signed and proved
 * batches of 1, 8 and 12 deposits (and 16 co-signed, its cap) on a token and the ADA vault merge into one exact
 * {@code VaultContinuationV1([])} whose value is the sum of the deposits; fees and collateral come from the fee wallet
 * alone. A proof that is not of the deposit's CREDITED record is refused before the script would; the continuation is
 * accepted custody that the next {@code Settle} spends.
 */
class AcceptTxBuilderTest {
    static long units(List<EvaluationResult> results) {
        return results.stream().mapToLong(r -> r.getExUnits().getMem().longValueExact()).sum();
    }

    static void assertExactContinuation(PayoutWorld world, AcceptTxBuilder.Built built) {
        TransactionOutput out = built.transaction().getBody().getOutputs().getFirst();
        BigInteger lovelace = built.deposits().stream().map(ResolvedOutput::lovelace)
                .reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger asset = built.deposits().stream().map(d -> d.quantityOf(world.asset))
                .reduce(BigInteger.ZERO, BigInteger::add);
        assertThat(SettleBodyCheck.addressBytes(out)).isEqualTo(world.pins.vaultAddress());
        assertThat(out.getValue().getCoin()).isEqualTo(lovelace);
        assertThat(SettleBodyCheck.assets(out)).isEqualTo(world.asset.isAda() ? Map.of() : Map.of(world.asset, asset));
        assertThat(GatewayPlutus.decodeContinuation(out.getInlineDatum())).isEmpty();
        assertThat(built.transaction().getBody().getOutputs()).hasSize(2);
        // Only the deposits and the fee wallet are spent; the fee wallet alone pays the fee (I15).
        assertThat(built.transaction().getBody().getInputs()).hasSize(built.deposits().size() + 1);
        assertThat(built.transaction().getBody().getCollateral()).singleElement()
                .satisfies(in -> assertThat(in.getTransactionId()).isEqualTo(world.fee.outpoint().txIdHex()));
        assertThat(PayoutTxBuilder.accepted(built.continuation(), world.pins)).isTrue();
    }

    void cosignedPasses(AssetId asset, int count) throws Exception {
        PayoutWorld world = new PayoutWorld(asset);
        List<ResolvedOutput> deposits = world.deposits(0, count);
        world.credit(deposits, 900, 1_000);
        AcceptTxBuilder.Built built = AcceptTxBuilder.cosigned(world.acceptContext(), deposits,
                PayoutWorld.signers(PayoutWorld.THRESHOLD), world.evaluator());
        long mem = units(world.evaluate(built));
        System.out.printf("ACCEPT| cosigned %s n=%d mem=%d bytes=%d fee=%s%n", asset.isAda() ? "ada" : "token", count,
                mem, built.cbor().length, built.fee());
        assertThat(mem).isLessThan(16_500_000L);
        assertExactContinuation(world, built);
        assertThat(built.transaction().getBody().getRequiredSigners()).hasSize(PayoutWorld.THRESHOLD);
    }

    void provedPasses(AssetId asset, int count) throws Exception {
        PayoutWorld world = new PayoutWorld(asset);
        List<ResolvedOutput> deposits = world.deposits(0, count);
        world.credit(deposits, 900, 1_000);
        AcceptTxBuilder.Built built = AcceptTxBuilder.proved(world.acceptContext(), deposits,
                key -> Optional.of(world.state.proof(key)), world.evaluator());
        long mem = units(world.evaluate(built));
        System.out.printf("ACCEPT| proved %s n=%d mem=%d bytes=%d fee=%s%n", asset.isAda() ? "ada" : "token", count,
                mem, built.cbor().length, built.fee());
        assertThat(mem).isLessThan(16_500_000L);
        assertExactContinuation(world, built);
        assertThat(built.transaction().getBody().getRequiredSigners()).isEmpty();
    }

    @Test
    void coSignedAcceptsOfOneEightAndTwelveDepositsPassTheCompiledValidator() throws Exception {
        for (AssetId asset : List.of(PayoutWorld.TOKEN, AssetId.ADA)) {
            for (int count : new int[] {1, 8, 12}) cosignedPasses(asset, count);
        }
        cosignedPasses(PayoutWorld.TOKEN, AcceptTxBuilder.MAX_COSIGNED);
    }

    @Test
    void provedAcceptsOfOneEightAndTwelveDepositsPassTheCompiledValidator() throws Exception {
        for (AssetId asset : List.of(PayoutWorld.TOKEN, AssetId.ADA)) {
            for (int count : new int[] {1, 8, 12}) provedPasses(asset, count);
        }
    }

    @Test
    void batchCapsAreEnforced() {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        List<ResolvedOutput> deposits = world.deposits(0, AcceptTxBuilder.MAX_COSIGNED + 1);
        assertThatThrownBy(() -> AcceptTxBuilder.cosigned(world.acceptContext(), deposits,
                PayoutWorld.signers(2), world.evaluator())).hasMessageContaining("at most");
        assertThatThrownBy(() -> AcceptTxBuilder.proved(world.acceptContext(),
                deposits.subList(0, AcceptTxBuilder.MAX_PROVED + 1), key -> Optional.empty(), world.evaluator()))
                .hasMessageContaining("at most");
    }

    @Test
    void aProofOfAnUncreditedRecordOrAnotherKeyIsRefused() {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        List<ResolvedOutput> deposits = world.deposits(0, 2);
        world.credit(deposits.subList(0, 1), 900, 1_000);
        DepositRecordV1 uncredited = world.record(deposits.get(1), 900, DepositAdmission.Reason.DEPOSITS_PAUSED);
        world.state.put(AcceptTxBuilder.depositKey(world.pins, uncredited.depositId()), uncredited.encode());
        world.anchor(List.of(), 1_001);
        assertThatThrownBy(() -> AcceptTxBuilder.proved(world.acceptContext(), deposits,
                key -> Optional.of(world.state.proof(key)), world.evaluator()))
                .hasMessageContaining("is not of its CREDITED record");
        // Deposit 0's proof offered for deposit 1.
        byte[] first = AcceptTxBuilder.depositKey(world.pins, AcceptTxBuilder.depositId(world.pins,
                deposits.getFirst().outpoint()));
        assertThatThrownBy(() -> AcceptTxBuilder.proved(world.acceptContext(), deposits.subList(1, 2),
                key -> Optional.of(world.state.proof(first)), world.evaluator()))
                .hasMessageContaining("is not of its CREDITED record");
    }

    @Test
    void onlyDepositShapedInputsAreMergedAndTheThresholdIsChecked() {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        List<ResolvedOutput> deposits = world.deposits(0, 2);
        world.credit(deposits, 900, 1_000);
        // Accepted custody is not a deposit.
        assertThatThrownBy(() -> AcceptTxBuilder.cosigned(world.acceptContext(), List.of(world.vaultUtxos.getFirst()),
                PayoutWorld.signers(2), world.evaluator())).hasMessageContaining("not a deposit-shaped");
        // One member key below the threshold: the compiled validator refuses (V9).
        assertThatThrownBy(() -> AcceptTxBuilder.cosigned(world.acceptContext(), deposits, PayoutWorld.signers(1),
                world.evaluator())).hasMessageContaining("evaluation failed");
    }

    /** D8f: the Accept continuation is accepted custody (V10), and the next Settle spends it. */
    @Test
    void theAcceptedContinuationIsCustodyTheNextSettleSpends() throws Exception {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        List<ResolvedOutput> deposits = world.deposits(0, 3);
        world.credit(deposits, 900, 1_000);
        AcceptTxBuilder.Built accept = AcceptTxBuilder.cosigned(world.acceptContext(), deposits,
                PayoutWorld.signers(2), world.evaluator());
        world.evaluate(accept);
        ResolvedOutput continuation = world.include(accept);
        // The continuation is the vault's only custody now.
        world.vaultUtxos.removeIf(out -> !out.outpoint().equals(continuation.outpoint()));

        ClaimLeafV1 claim = world.claim(5, 7);
        ClaimLeafV1 small = new ClaimLeafV1(claim.claimId(), claim.vaultId(), claim.kind(),
                org.yanoproject.x.assets.contracts.AssetValue.single(world.asset, BigInteger.valueOf(100)),
                BigInteger.valueOf(1_500_000), claim.recipient(), claim.reservedHeight(), claim.reservedAtMs());
        world.anchor(List.of(small), 1_002);
        PayoutTxBuilder.Built settle = PayoutTxBuilder.settle(world.context(5), List.of(small), world.mirrors[5],
                PayoutWorld.signers(2), world.evaluator());
        world.evaluate(settle);
        assertThat(settle.transaction().getBody().getInputs()).anySatisfy(in -> assertThat(
                new ResolvedOutput.Outpoint(Hash32.ofHex(in.getTransactionId()), in.getIndex()))
                .isEqualTo(continuation.outpoint()));
    }
}
