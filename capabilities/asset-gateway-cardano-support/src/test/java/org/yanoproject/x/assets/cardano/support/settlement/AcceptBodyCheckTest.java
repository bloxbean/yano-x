package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The member body check of ADR-059 D8h §1 for a co-signed {@code Accept} (step 4): an honest body is signable and its
 * hash is the transaction id; it is refused for an UNCREDITED or unknown deposit, a deposit credited above the
 * referenced anchor, an extra vault output, a wrong continuation (value or ids), a foreign script input, a shard input,
 * an input locked by the member's own key, and a request that does not re-serialize to its own bytes.
 */
class AcceptBodyCheckTest {
    static final byte[] MEMBER_KEY = Blake2bUtil.blake2bHash224(PayoutWorld.MEMBERS.get(2));

    /** The member's committed deposit records. */
    static final class Records implements AcceptBodyCheck.Deposits {
        final Map<Hash32, DepositRecordV1> records = new HashMap<>();

        Records(List<DepositRecordV1> records) {
            records.forEach(record -> this.records.put(record.depositId(), record));
        }

        @Override
        public Optional<DepositRecordV1> deposit(Hash32 depositId) {
            return Optional.ofNullable(records.get(depositId));
        }
    }

    record Fixture(PayoutWorld world, List<ResolvedOutput> deposits, List<DepositRecordV1> records,
                   AcceptTxBuilder.Built built) {
    }

    static Fixture fixture(int count) {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        List<ResolvedOutput> deposits = world.deposits(0, count);
        List<DepositRecordV1> records = world.credit(deposits, 900, 1_000);
        AcceptTxBuilder.Built built = AcceptTxBuilder.cosigned(world.acceptContext(), deposits,
                PayoutWorld.signers(2), world.evaluator());
        return new Fixture(world, deposits, records, built);
    }

    static SettleBodyCheck.Outcome check(PayoutWorld world, byte[] tx, AcceptBodyCheck.Deposits records) {
        return check(world, tx, records, MEMBER_KEY);
    }

    static SettleBodyCheck.Outcome check(PayoutWorld world, byte[] tx, AcceptBodyCheck.Deposits records,
                                         byte[] ownKey) {
        return AcceptBodyCheck.check(tx, world.pins, ownKey, world::resolve, records,
                PayoutTxBuilder.costModels(PayoutWorld.PARAMS), PayoutWorld.tip());
    }

    static void refused(SettleBodyCheck.Outcome outcome, String reason) {
        assertThat(outcome).isInstanceOf(SettleBodyCheck.Refused.class);
        assertThat(((SettleBodyCheck.Refused) outcome).refusal().detail()).contains(reason);
    }

    /** {@code built} with its body changed by {@code change}, re-serialized (redeemers unchanged). */
    static byte[] mutate(AcceptTxBuilder.Built built, java.util.function.Consumer<Transaction> change)
            throws Exception {
        Transaction tx = Transaction.deserialize(built.cbor());
        change.accept(tx);
        return tx.serialize();
    }

    @Test
    void anHonestBodyIsSignableAndItsHashIsTheTransactionId() throws Exception {
        Fixture f = fixture(3);
        f.world().evaluate(f.built());
        SettleBodyCheck.Outcome outcome = check(f.world(), f.built().cbor(), new Records(f.records()));
        assertThat(outcome).isInstanceOf(SettleBodyCheck.Signable.class);
        SettleBodyCheck.Verified verified = ((SettleBodyCheck.Signable) outcome).verified();
        assertThat(Hash32.of(verified.bodyHash())).isEqualTo(f.built().txId());
        assertThat(verified.anchor().height()).isEqualTo(1_000);
        assertThat(verified.claimIds()).isEmpty();
        assertThat(AcceptBodyCheck.isAcceptRequest(f.built().cbor())).isTrue();
    }

    @Test
    void anUncreditedOrUnknownDepositIsRefused() {
        Fixture f = fixture(2);
        List<DepositRecordV1> uncredited = new ArrayList<>(f.records());
        uncredited.set(1, f.world().record(f.deposits().get(1), 900, DepositAdmission.Reason.DEPOSITS_PAUSED));
        refused(check(f.world(), f.built().cbor(), new Records(uncredited)), "is not CREDITED");
        refused(check(f.world(), f.built().cbor(), new Records(f.records().subList(0, 1))), "unknown deposit");
    }

    @Test
    void aDepositCreditedAboveTheReferencedAnchorIsRefused() {
        Fixture f = fixture(2);
        List<DepositRecordV1> late = new ArrayList<>(f.records());
        late.set(0, f.world().record(f.deposits().getFirst(), 1_001, DepositAdmission.Reason.NONE));
        refused(check(f.world(), f.built().cbor(), new Records(late)), "above the referenced anchor");
    }

    @Test
    void anExtraVaultOutputIsRefused() throws Exception {
        Fixture f = fixture(2);
        byte[] extra = mutate(f.built(), tx -> tx.getBody().getOutputs().add(PayoutTxBuilder.output(
                f.world().pins.vaultAddress(), BigInteger.valueOf(2_000_000), Map.of(),
                GatewayPlutus.continuation(List.of()))));
        refused(check(f.world(), extra, new Records(f.records())), "another output pays the vault");
    }

    @Test
    void aWrongContinuationIsRefused() throws Exception {
        Fixture f = fixture(2);
        byte[] short_ = mutate(f.built(), tx -> {
            TransactionOutput continuation = tx.getBody().getOutputs().getFirst();
            continuation.getValue().setCoin(continuation.getValue().getCoin().subtract(BigInteger.ONE));
            tx.getBody().getOutputs().getLast().getValue().setCoin(
                    tx.getBody().getOutputs().getLast().getValue().getCoin().add(BigInteger.ONE));
        });
        refused(check(f.world(), short_, new Records(f.records())), "not the exact empty continuation");
        byte[] ids = mutate(f.built(), tx -> tx.getBody().getOutputs().getFirst().setInlineDatum(
                GatewayPlutus.continuation(List.of(Hash32.of(PayoutWorld.filled(32, 7))))));
        refused(check(f.world(), ids, new Records(f.records())), "not the exact empty continuation");
        byte[] elsewhere = mutate(f.built(), tx -> {
            TransactionOutput moved = PayoutTxBuilder.output(PayoutWorld.FEE_ADDRESS,
                    tx.getBody().getOutputs().getFirst().getValue().getCoin(), Map.of(), null);
            moved.setValue(tx.getBody().getOutputs().getFirst().getValue());
            tx.getBody().getOutputs().set(0, moved);
        });
        refused(check(f.world(), elsewhere, new Records(f.records())), "no vault continuation");
    }

    @Test
    void aForeignScriptOrShardInputIsRefused() throws Exception {
        Fixture f = fixture(1);
        ResolvedOutput foreign = f.world().add(new ResolvedOutput(PayoutWorld.outpoint(0x5f, 0),
                PayoutWorld.cat(new byte[] {0x70}, PayoutWorld.filled(28, 0x5f)), BigInteger.valueOf(5_000_000),
                new java.util.TreeMap<>(), Optional.empty(), Optional.empty()));
        byte[] withForeign = mutate(f.built(), tx -> tx.getBody().getInputs().add(
                new TransactionInput(foreign.outpoint().txIdHex(), foreign.outpoint().index())));
        refused(check(f.world(), withForeign, new Records(f.records())), "other than this vault's own");
        ResolvedOutput shard = f.world().shards[0];
        byte[] withShard = mutate(f.built(), tx -> tx.getBody().getInputs().add(
                new TransactionInput(shard.outpoint().txIdHex(), shard.outpoint().index())));
        refused(check(f.world(), withShard, new Records(f.records())), "other than this vault's own");
    }

    @Test
    void anInputOfTheMembersOwnKeyOrANonCanonicalRequestIsRefused() throws Exception {
        Fixture f = fixture(1);
        byte[] feeKey = PayoutWorld.FEE_KEY;
        refused(check(f.world(), f.built().cbor(), new Records(f.records()), feeKey), "my own member key");
        byte[] padded = java.util.Arrays.copyOf(f.built().cbor(), f.built().cbor().length + 1);
        refused(check(f.world(), padded, new Records(f.records())), "");
        // Accepted custody (a continuation) is not a deposit.
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        ResolvedOutput custody = world.vaultUtxos.getFirst();
        Fixture g = fixture(1);
        byte[] withCustody = mutate(g.built(), tx -> tx.getBody().getInputs().add(
                new TransactionInput(custody.outpoint().txIdHex(), custody.outpoint().index())));
        g.world().add(new ResolvedOutput(custody.outpoint(), g.world().pins.vaultAddress(), custody.lovelace(),
                custody.assets(), custody.inlineDatum(), Optional.empty()));
        refused(check(g.world(), withCustody, new Records(g.records())), "not a deposit-shaped");
    }
}
