package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.assets.contracts.SettlementRecords;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The member body check of ADR-059 D8h §1 for {@code Settle}: an honest body is signable (and its hash is the
 * transaction id), and every hazard is refused: an unknown claim, a claim reserved above the referenced anchor (V12),
 * a wrong recipient or amount, an extra script input, an input locked by the member's own key, an input the member
 * cannot resolve, a missing script_data_hash, and a domain that is not ACTIVE.
 */
class SettleBodyCheckTest {
    static PayoutWorld world;
    static byte[] memberKey;

    @BeforeAll
    static void world() {
        world = new PayoutWorld(PayoutWorld.TOKEN);
        memberKey = Blake2bUtil.blake2bHash224(PayoutWorld.MEMBERS.get(2));
    }

    /** The member's committed state: the given leaves, RESERVED, and an ACTIVE domain unless overridden. */
    static final class State implements SettleBodyCheck.Committed {
        final Map<Hash32, SettlementQueries.ClaimView> claims = new HashMap<>();
        DomainStatusV1 status = new DomainStatusV1(DomainStatusV1.Status.ACTIVE, 0, 0, PayoutWorld.EXIT_DELAY);

        State(List<ClaimLeafV1> leaves) {
            for (ClaimLeafV1 leaf : leaves) {
                claims.put(leaf.claimId(), new SettlementQueries.ClaimView(leaf,
                        SettlementRecords.ClaimStatusV1.reserved(leaf.claimId(), leaf.reservedHeight())));
            }
        }

        @Override
        public Optional<SettlementQueries.ClaimView> claim(Hash32 claimId) {
            return Optional.ofNullable(claims.get(claimId));
        }

        @Override
        public Optional<DomainStatusV1> domainStatus() {
            return Optional.of(status);
        }
    }

    static SettleBodyCheck.Outcome check(byte[] tx, State state) {
        return SettleBodyCheck.check(tx, world.pins, memberKey, world::resolve, state,
                PayoutTxBuilder.costModels(PayoutWorld.PARAMS));
    }

    static PayoutTxBuilder.Built build(int shard, List<ClaimLeafV1> claims) {
        return PayoutTxBuilder.settle(world.context(shard), claims, world.mirrors[shard],
                PayoutWorld.signers(2), world.evaluator());
    }

    static void refused(SettleBodyCheck.Outcome outcome, String reason) {
        assertThat(outcome).isInstanceOf(SettleBodyCheck.Refused.class);
        assertThat(((SettleBodyCheck.Refused) outcome).refusal().detail()).contains(reason);
    }

    @Test
    void anHonestBodyIsSignableAndItsHashIsTheTransactionId() throws Exception {
        List<ClaimLeafV1> claims = world.claims(3, 4);
        world.anchor(claims, 20_000);
        PayoutTxBuilder.Built built = build(3, claims);
        world.evaluate(built);
        SettleBodyCheck.Outcome outcome = check(built.cbor(), new State(claims));
        assertThat(outcome).isInstanceOf(SettleBodyCheck.Signable.class);
        SettleBodyCheck.Verified verified = ((SettleBodyCheck.Signable) outcome).verified();
        assertThat(Hash32.of(verified.bodyHash())).isEqualTo(built.txId());
        assertThat(verified.claimIds()).isEqualTo(built.claimIds());
        assertThat(verified.anchor().height()).isEqualTo(20_000);
    }

    @Test
    void anUnknownOrResolvedClaimIsRefused() {
        List<ClaimLeafV1> claims = world.claims(5, 3);
        world.anchor(claims, 20_001);
        PayoutTxBuilder.Built built = build(5, claims);
        State missing = new State(claims.subList(0, 2));
        refused(check(built.cbor(), missing), "unknown claim");
        State paid = new State(claims);
        ClaimLeafV1 first = claims.getFirst();
        paid.claims.put(first.claimId(), new SettlementQueries.ClaimView(first,
                SettlementRecords.ClaimStatusV1.reserved(first.claimId(), 1)
                        .resolve(SettlementRecords.ClaimStatus.PAID, Hash32.of(new byte[32]), 9)));
        refused(check(built.cbor(), paid), "resolved");
    }

    @Test
    void aClaimReservedAboveTheReferencedAnchorIsRefused() {
        List<ClaimLeafV1> claims = world.claims(6, 2);
        // The claims are reserved at heights 6100 and 6101; the anchor covers only up to 6100 (V12).
        world.anchor(claims, claims.getFirst().reservedHeight());
        refused(check(build(6, claims).cbor(), new State(claims)), "above the referenced anchor");
    }

    @Test
    void aWrongRecipientOrAmountIsRefused() {
        List<ClaimLeafV1> claims = world.claims(7, 2);
        world.anchor(claims, 20_002);
        ClaimLeafV1 honest = claims.getFirst();
        ClaimLeafV1 redirected = new ClaimLeafV1(honest.claimId(), honest.vaultId(), honest.kind(), honest.value(),
                honest.vaultLovelace(), PayoutWorld.cat(new byte[] {0x60}, PayoutWorld.filled(28, 0x13)),
                honest.reservedHeight(), honest.reservedAtMs());
        refused(check(build(7, List.of(redirected, claims.get(1))).cbor(), new State(claims)),
                "differ from the committed leaf");
        ClaimLeafV1 inflated = new ClaimLeafV1(honest.claimId(), honest.vaultId(), honest.kind(),
                AssetValue.single(PayoutWorld.TOKEN, honest.value().quantityOf(PayoutWorld.TOKEN).add(BigInteger.TEN)),
                honest.vaultLovelace(), honest.recipient(), honest.reservedHeight(), honest.reservedAtMs());
        refused(check(build(7, List.of(inflated, claims.get(1))).cbor(), new State(claims)),
                "differ from the committed leaf");
    }

    @Test
    void anExtraScriptInputOrAnInputOfMyOwnKeyIsRefused() {
        List<ClaimLeafV1> claims = world.claims(8, 1);
        world.anchor(claims, 20_003);
        ResolvedOutput foreign = world.add(new ResolvedOutput(PayoutWorld.outpoint(0x6a, 0),
                PayoutWorld.cat(new byte[] {0x70}, PayoutWorld.filled(28, 0x5a)), BigInteger.valueOf(5_000_000),
                new TreeMap<>(), Optional.empty(), Optional.empty()));
        refused(check(withFunding(8, claims, foreign).cbor(), new State(claims)), "script input other than");
        ResolvedOutput mine = world.add(new ResolvedOutput(PayoutWorld.outpoint(0x6b, 0),
                PayoutWorld.cat(new byte[] {0x60}, memberKey), BigInteger.valueOf(5_000_000), new TreeMap<>(),
                Optional.empty(), Optional.empty()));
        refused(check(withFunding(8, claims, mine).cbor(), new State(claims)), "my own member key");
    }

    @Test
    void anUnresolvableInputIsRefusedAsUnavailable() {
        List<ClaimLeafV1> claims = world.claims(10, 1);
        world.anchor(claims, 20_004);
        PayoutTxBuilder.Built built = build(10, claims);
        SettleBodyCheck.Outcome outcome = SettleBodyCheck.check(built.cbor(), world.pins, memberKey,
                outpoint -> outpoint.equals(world.fee.outpoint()) ? Optional.empty() : world.resolve(outpoint),
                new State(claims), PayoutTxBuilder.costModels(PayoutWorld.PARAMS));
        assertThat(((SettleBodyCheck.Refused) outcome).refusal().kind())
                .isEqualTo(SettleBodyCheck.Refusal.Kind.UNRESOLVED_INPUT);
    }

    @Test
    void aMissingOrStaleScriptDataHashIsRefused() throws Exception {
        List<ClaimLeafV1> claims = world.claims(11, 1);
        world.anchor(claims, 20_005);
        PayoutTxBuilder.Built built = build(11, claims);
        Transaction stripped = Transaction.deserialize(built.cbor());
        stripped.getBody().setScriptDataHash(null);
        refused(check(stripped.serialize(), new State(claims)), "no script_data_hash");
        Transaction swapped = Transaction.deserialize(built.cbor());
        swapped.getWitnessSet().getRedeemers().getFirst().setData(GatewayPlutus.follow());
        refused(check(swapped.serialize(), new State(claims)), "script_data_hash does not commit");
    }

    @Test
    void aDomainThatIsNotActiveIsRefused() {
        List<ClaimLeafV1> claims = world.claims(12, 1);
        world.anchor(claims, 20_006);
        State paused = new State(claims);
        paused.status = new DomainStatusV1(DomainStatusV1.Status.PAUSED, 1, 1, PayoutWorld.EXIT_DELAY);
        refused(check(build(12, claims).cbor(), paused), "not ACTIVE");
    }

    static PayoutTxBuilder.Built withFunding(int shard, List<ClaimLeafV1> claims, ResolvedOutput extra) {
        PayoutTxBuilder.Context base = world.context(shard);
        PayoutTxBuilder.Context context = new PayoutTxBuilder.Context(base.pins(), base.vaultUtxos(),
                base.shardInput(), base.anchor(), base.exitMode(), base.scriptRefs(),
                new PayoutTxBuilder.Funding(List.of(world.fee, extra), PayoutWorld.FEE_ADDRESS), base.validFromSlot(),
                base.ttlSlot(), base.protocolParams(), base.maxVaultInputs());
        // A forged body never passes phase 2; fixed units stand in, since only the member check is under test.
        return PayoutTxBuilder.settle(context, claims, world.mirrors[shard], PayoutWorld.signers(2), fixedUnits());
    }

    /** An evaluator that prices every redeemer at fixed units, for bodies phase 2 would reject anyway. */
    static com.bloxbean.cardano.client.api.TransactionEvaluator fixedUnits() {
        return (cbor, inputs) -> {
            try {
                List<com.bloxbean.cardano.client.api.model.EvaluationResult> results = new java.util.ArrayList<>();
                for (var redeemer : Transaction.deserialize(cbor).getWitnessSet().getRedeemers()) {
                    var result = new com.bloxbean.cardano.client.api.model.EvaluationResult();
                    result.setRedeemerTag(redeemer.getTag());
                    result.setIndex(redeemer.getIndex().intValue());
                    result.setExUnits(com.bloxbean.cardano.client.plutus.spec.ExUnits.builder()
                            .mem(BigInteger.valueOf(2_000_000)).steps(BigInteger.valueOf(600_000_000)).build());
                    results.add(result);
                }
                return com.bloxbean.cardano.client.api.model.Result.success("ok").withValue(results);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
    }
}
