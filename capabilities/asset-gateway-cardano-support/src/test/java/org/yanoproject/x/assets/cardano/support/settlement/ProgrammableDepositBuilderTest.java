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

    /**
     * Codex P1-2, Fable P2-1: the deposit builder needs the live params' four fields to be exactly an approved tuple,
     * not only its dispatcher: an issuance-only change would make the deposit UNCREDITED(WIRING_UNPINNED). A vault
     * transaction still assembles under the same wiring (ProgrammableVaultTxAssemblerTest).
     */
    @Test
    void aDepositNeedsTheExactApprovedTuple() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        byte[] changed = ProgrammableWorld.serialize(com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0,
                ProgrammableWorld.credential(world.dispatcher), ProgrammableWorld.credential(filled(28, 0x5a)),
                ProgrammableWorld.credential(world.transfer), ProgrammableWorld.credential(world.thirdParty),
                ProgrammableWorld.credential(filled(28, 0xc9)),
                com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(1)));
        ResolvedOutput moved = world.add(new ResolvedOutput(world.params.outpoint(), world.params.address(),
                world.params.lovelace(), world.params.assets(), Optional.of(changed), Optional.empty()));
        ProgrammableVaultTxAssembler.LiveWiring wiring = ProgrammableVaultTxAssembler.LiveWiring.of(world.deployment,
                moved, List.of(world.tuple));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> deposit(world, wiring, single(TOKEN, 1_000)))
                .isInstanceOf(ProgrammableVaultTxAssembler.Drift.class).hasMessageContaining("WIRING_UNPINNED");
    }

    /** Codex P2-12: a deposit past the observer's reference-input limit (32) is refused before it is returned. */
    @Test
    void aDepositPastTheObserversReferenceLimitIsRefused() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        List<ResolvedOutput> references = new java.util.ArrayList<>();
        // With the live params UTxO and the token's registry node, 31 more make 33.
        for (int i = 0; i < 31; i++) {
            references.add(world.add(new ResolvedOutput(outpoint(0x6f, i), FEE_ADDRESS, BigInteger.valueOf(1_000_000),
                    new TreeMap<>(), Optional.empty(), Optional.empty())));
        }
        ResolvedOutput input = world.add(new ResolvedOutput(outpoint(0x30, 0), world.keyWallet(ALICE),
                BigInteger.valueOf(3_000_000), single(TOKEN, 1_000), Optional.empty(), Optional.empty()));
        ProgrammableDepositBuilder.Request request = new ProgrammableDepositBuilder.Request(List.of(input), ALICE,
                world.vaultWallet(), TOKEN, BigInteger.valueOf(600), BigInteger.valueOf(2_500_000), filled(28, 0xb5),
                ALICE, new PayoutTxBuilder.Funding(List.of(world.base.fee), FEE_ADDRESS), references, NOW_SLOT,
                NOW_SLOT + 600);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProgrammableDepositBuilder.build(world.deployment,
                world.wiring(), world.l1(), world.adapter(), world.balances(), world.vaultId(), request, PARAMS,
                world.evaluator())).isInstanceOf(PayoutTxBuilder.BuildException.class)
                .hasMessageContaining("more than 32");
        // Within the limit it builds.
        ProgrammableDepositBuilder.Request fits = new ProgrammableDepositBuilder.Request(List.of(input), ALICE,
                world.vaultWallet(), TOKEN, BigInteger.valueOf(600), BigInteger.valueOf(2_500_000), filled(28, 0xb5),
                ALICE, new PayoutTxBuilder.Funding(List.of(world.base.fee), FEE_ADDRESS), references.subList(0, 20),
                NOW_SLOT, NOW_SLOT + 600);
        assertThat(ProgrammableDepositBuilder.build(world.deployment, world.wiring(), world.l1(), world.adapter(),
                world.balances(), world.vaultId(), fits, PARAMS, world.evaluator()).assembled().indexes()
                .referenceOrder()).hasSizeLessThanOrEqualTo(ProgrammableDepositClaimV1.MAX_REFERENCE_INPUTS);
    }

    static TreeMap<AssetId, BigInteger> single(AssetId asset, long quantity) {
        TreeMap<AssetId, BigInteger> held = new TreeMap<>();
        held.put(asset, BigInteger.valueOf(quantity));
        return held;
    }

    static ProgrammableDepositBuilder.Built deposit(ProgrammableWorld world, TreeMap<AssetId, BigInteger> held) {
        return deposit(world, world.wiring(), held);
    }

    static ProgrammableDepositBuilder.Built deposit(ProgrammableWorld world,
                                                    ProgrammableVaultTxAssembler.LiveWiring wiring,
                                                    TreeMap<AssetId, BigInteger> held) {
        ResolvedOutput input = world.add(new ResolvedOutput(outpoint(0x30, 0), world.keyWallet(ALICE),
                BigInteger.valueOf(3_000_000), held, Optional.empty(), Optional.empty()));
        ProgrammableDepositBuilder.Request request = new ProgrammableDepositBuilder.Request(List.of(input), ALICE,
                world.vaultWallet(), TOKEN, BigInteger.valueOf(600), BigInteger.valueOf(2_500_000), filled(28, 0xb5),
                ALICE, new PayoutTxBuilder.Funding(List.of(world.base.fee), FEE_ADDRESS), List.of(), NOW_SLOT,
                NOW_SLOT + 600);
        return ProgrammableDepositBuilder.build(world.deployment, wiring, world.l1(), world.adapter(),
                world.balances(), world.vaultId(), request, PARAMS, world.evaluator());
    }
}
