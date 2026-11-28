package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.api.model.EvaluationResult;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ProgrammableDepositClaimV1;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.FEE_ADDRESS;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.NOW_SLOT;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.PARAMS;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.TOKEN;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.filled;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.outpoint;

/**
 * ADR-061 D6: the deposit builder makes an ordinary CIP-113 transfer into the vault smart wallet that passes the
 * pinned scripts, whose datum's origin is the depositor's own spent input and whose output index is the deposit's.
 */
class ProgrammableDepositBuilderTest {
    static final byte[] ALICE = filled(28, 0xa7);

    @Test
    void aDepositPassesTheCip113ScriptsAndIsOriginBound() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        ProgrammableDepositBuilder.Built built = deposit(world, single(TOKEN, 1_000));
        List<EvaluationResult> results = world.evaluate(built.assembled());
        // PLB for the one depositor input, the dispatcher, the transfer delegate and the token's hook.
        assertThat(results).hasSize(4);
        ProgrammableDepositDatumV1 datum = built.datum();
        assertThat(datum.outputIndex()).isZero();
        assertThat(datum.originTxId()).isEqualTo(outpoint(0x30, 0).txId());
        var outputs = built.assembled().transaction().getBody().getOutputs();
        byte[] inline = outputs.getFirst().getInlineDatum().serializeToBytes();
        // The datum is deposit-shaped by the shared total predicate, and its origin is spent by its own transaction.
        Optional<ProgrammableDepositDatumV1> shaped = ProgrammableDepositDatumV1.depositShaped(inline, world.plb, 0);
        assertThat(shaped).contains(datum);
        assertThat(ProgrammableDepositClaimV1.Shape.of(inline, world.plb, 0))
                .isEqualTo(ProgrammableDepositClaimV1.Shape.DEPOSIT);
        assertThat(datum.originSpent(List.of(new ProgrammableDepositDatumV1.Outpoint(outpoint(0x30, 0).txId(), 0)),
                0)).isTrue();
        assertThat(built.depositId()).isEqualTo(datum.depositId(world.vaultId()));
    }

    @Test
    void aDepositFromAWalletHoldingSeveralPoliciesProvesEachAndCallsEachHook() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        TreeMap<AssetId, BigInteger> held = single(TOKEN, 1_000);
        held.put(ProgrammableWorld.ASSET_B, BigInteger.valueOf(40));
        held.put(ProgrammableWorld.UNREGISTERED, BigInteger.valueOf(3));
        ProgrammableDepositBuilder.Built built = deposit(world, held);
        world.evaluate(built.assembled());
        assertThat(built.assembled().requirements().proofs()).hasSize(3);
        assertThat(built.assembled().requirements().hooks()).hasSize(2);
    }

    @Test
    void aDepositWithdrawsTheDispatchersLiveBalance() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        world.rewards.put(HexFormat.of().formatHex(world.dispatcher), BigInteger.valueOf(2_000_000));
        ProgrammableDepositBuilder.Built built = deposit(world, single(TOKEN, 1_000));
        world.evaluate(built.assembled());
        assertThat(built.assembled().withdrawalAmounts().get("0" + HexFormat.of().formatHex(world.dispatcher)))
                .isEqualTo(BigInteger.valueOf(2_000_000));
    }

    static TreeMap<AssetId, BigInteger> single(AssetId asset, long quantity) {
        TreeMap<AssetId, BigInteger> held = new TreeMap<>();
        held.put(asset, BigInteger.valueOf(quantity));
        return held;
    }

    static ProgrammableDepositBuilder.Built deposit(ProgrammableWorld world, TreeMap<AssetId, BigInteger> held) {
        ResolvedOutput input = world.add(new ResolvedOutput(outpoint(0x30, 0), world.keyWallet(ALICE),
                BigInteger.valueOf(3_000_000), held, Optional.empty(), Optional.empty()));
        ProgrammableDepositBuilder.Request request = new ProgrammableDepositBuilder.Request(List.of(input), ALICE,
                world.vaultWallet(), TOKEN, BigInteger.valueOf(600), BigInteger.valueOf(2_500_000), filled(28, 0xb5),
                ALICE, new PayoutTxBuilder.Funding(List.of(world.base.fee), FEE_ADDRESS), List.of(), NOW_SLOT,
                NOW_SLOT + 600);
        return ProgrammableDepositBuilder.build(world.deployment, world.wiring(), world.l1(), world.adapter(),
                world.balances(), world.vaultId(), request, PARAMS, world.evaluator());
    }
}
