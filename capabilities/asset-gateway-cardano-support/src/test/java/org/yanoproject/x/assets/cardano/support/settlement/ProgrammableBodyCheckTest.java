package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.FEE_ADDRESS;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.filled;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.outpoint;

/**
 * ADR-061 D13, I41: a member signs a co-signed CIP-113 vault transaction only when its withdrawals, redeemers,
 * reference inputs, PLB outputs and signers are exactly what it derives from its own view.
 */
class ProgrammableBodyCheckTest {
    @Test
    void anHonestAssembledSettlePasses() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        Transaction tx = ProgrammableVaultTxAssemblerTest.settle(world, 3, 4).transaction();
        assertThat(ProgrammableBodyCheck.check(tx, view(world))).isEmpty();
    }

    @Test
    void everyDeviationIsRefused() throws Exception {
        byte[] plb = new ProgrammableWorld().plb;
        assertRefused(tx -> tx.getBody().setMint(List.of(new MultiAsset(HexFormat.of().formatHex(filled(28, 0x41)),
                List.of(new Asset("0x01", BigInteger.ONE))))), "mints");
        assertRefused(tx -> tx.getBody().getWithdrawals().add(new Withdrawal(AddressProvider.getRewardAddress(
                Credential.fromKey(filled(28, 0x42)), Networks.testnet()).toBech32(), BigInteger.ZERO)),
                "key-credential withdrawal");
        assertRefused(tx -> tx.getBody().getWithdrawals().add(new Withdrawal(AddressProvider.getRewardAddress(
                Credential.fromScript(filled(28, 0x43)), Networks.testnet()).toBech32(), BigInteger.ZERO)),
                "not exactly");
        assertRefused(tx -> {
            Withdrawal first = tx.getBody().getWithdrawals().getFirst();
            tx.getBody().getWithdrawals().set(0, new Withdrawal(first.getRewardAddress(), BigInteger.valueOf(5)));
        }, "not the live balance");
        assertRefused(tx -> {
            for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
                if (redeemer.getTag() == RedeemerTag.Spend) {
                    redeemer.setData(ConstrPlutusData.of(0, ProgrammableWorld.integer(0), ProgrammableWorld.integer(9)));
                }
            }
        }, "BaseSpendRedeemer");
        assertRefused(tx -> {
            for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
                if (redeemer.getTag() == RedeemerTag.Reward && redeemer.getData() instanceof ConstrPlutusData c
                        && !c.getData().getPlutusDataList().isEmpty()
                        && c.getData().getPlutusDataList().getFirst()
                        instanceof com.bloxbean.cardano.client.plutus.spec.ListPlutusData) {
                    redeemer.setData(ConstrPlutusData.of(0, GatewayPlutus.list(List.of())));
                }
            }
        }, "not the derived one");
        assertRefused(tx -> tx.getBody().getReferenceInputs().add(new TransactionInput(
                HexFormat.of().formatHex(filled(32, 0x6d)), 0)), "unknown to this member");
        assertRefused(tx -> tx.getBody().getRequiredSigners().add(filled(28, 0x44)), "not a member");
        assertRefused(tx -> tx.getBody().getOutputs().add(1, PayoutTxBuilder.output(
                ProgrammableBackend.smartWallet(plb, filled(28, 0x45), 0), BigInteger.TWO,
                Map.of(), null)), "neither a payout nor the continuation");
    }

    @Test
    void anUnusedReferenceInputIsRefusedAndAMissingOneToo() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        ResolvedOutput stranger = world.add(new ResolvedOutput(outpoint(0x6e, 0), FEE_ADDRESS,
                BigInteger.valueOf(2_000_000), new TreeMap<>(), Optional.empty(), Optional.empty()));
        Transaction tx = ProgrammableVaultTxAssemblerTest.settle(world, 1, 4).transaction();
        Transaction extra = Transaction.deserialize(tx.serialize());
        extra.getBody().getReferenceInputs().add(PayoutTxBuilder.input(stranger.outpoint()));
        assertThat(ProgrammableBodyCheck.check(extra, view(world))).anyMatch(r -> r.contains("not one the"));
        Transaction missing = Transaction.deserialize(tx.serialize());
        missing.getBody().getReferenceInputs().removeIf(in -> in.getTransactionId()
                .equals(world.params.outpoint().txIdHex()));
        assertThat(ProgrammableBodyCheck.check(missing, view(world))).anyMatch(r -> r.contains("missing"));
    }

    @Test
    void anotherOwnersPlbInputIsRefused() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        ResolvedOutput foreign = world.add(new ResolvedOutput(outpoint(0x31, 0), world.keyWallet(filled(28, 0x46)),
                BigInteger.valueOf(2_000_000), new TreeMap<>(), Optional.empty(), Optional.empty()));
        Transaction tx = Transaction.deserialize(ProgrammableVaultTxAssemblerTest.settle(world, 1, 4).transaction()
                .serialize());
        tx.getBody().getInputs().add(PayoutTxBuilder.input(foreign.outpoint()));
        assertThat(ProgrammableBodyCheck.check(tx, view(world))).anyMatch(r -> r.contains("is not the vault's"));
    }

    @Test
    void protocolLevelDriftRefusesToSign() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        ResolvedOutput moved = world.add(new ResolvedOutput(world.params.outpoint(), world.params.address(),
                world.params.lovelace(), world.params.assets(), Optional.of(world.paramsDatum(filled(28, 0x47))),
                Optional.empty()));
        // The member resolves its own live wiring first; a dispatcher in no approved tuple stops it there.
        assertThatThrownBy(() -> ProgrammableVaultTxAssembler.LiveWiring.of(world.deployment, moved,
                List.of(world.tuple))).isInstanceOf(ProgrammableVaultTxAssembler.Drift.class);
    }

    static void assertRefused(Consumer<Transaction> mutation, String reason) throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        Transaction tx = Transaction.deserialize(ProgrammableVaultTxAssemblerTest.settle(world, 2, 4).transaction()
                .serialize());
        if (tx.getBody().getRequiredSigners() != null) {
            tx.getBody().setRequiredSigners(new ArrayList<>(tx.getBody().getRequiredSigners()));
        }
        tx.getBody().setWithdrawals(new ArrayList<>(tx.getBody().getWithdrawals()));
        tx.getBody().setReferenceInputs(new ArrayList<>(tx.getBody().getReferenceInputs()));
        tx.getBody().setOutputs(new ArrayList<>(tx.getBody().getOutputs()));
        mutation.accept(tx);
        assertThat(ProgrammableBodyCheck.check(tx, view(world))).as(reason).anyMatch(r -> r.contains(reason));
    }

    static ProgrammableBodyCheck.View view(ProgrammableWorld world) {
        List<byte[]> members = PayoutWorld.signers(3);
        return new ProgrammableBodyCheck.View(world.deployment, world.wiring(), world.l1(), world.adapter(),
                world.custody.custodyHash(), world.vaultWallet(), Optional.of(world.shard.address()),
                List.of(world.base.anchor, world.base.exitMode), members, FEE_ADDRESS,
                Optional.of(world.balances()));
    }
}
