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
                PayoutTxBuilder.costModels(PayoutWorld.PARAMS), PayoutWorld.tip());
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
                new State(claims), PayoutTxBuilder.costModels(PayoutWorld.PARAMS), PayoutWorld.tip());
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

    /** Review P1 (foreign asset in a shard): the shard input, as this member resolves it, holds a foreign token. */
    @Test
    void aShardInputHoldingAForeignTokenIsRefused() {
        List<ClaimLeafV1> claims = world.claims(13, 1);
        world.anchor(claims, 20_007);
        PayoutTxBuilder.Built built = build(13, claims);
        ResolvedOutput honest = world.shards[13];
        TreeMap<org.yanoproject.x.assets.contracts.AssetId, BigInteger> assets = new TreeMap<>(honest.assets());
        assets.put(PayoutWorld.TOKEN, BigInteger.ONE);
        ResolvedOutput dusted = new ResolvedOutput(honest.outpoint(), honest.address(), honest.lovelace(), assets,
                honest.inlineDatum(), honest.referenceScript());
        SettleBodyCheck.Outcome outcome = SettleBodyCheck.check(built.cbor(), world.pins, memberKey,
                outpoint -> outpoint.equals(honest.outpoint()) ? Optional.of(dusted) : world.resolve(outpoint),
                new State(claims), PayoutTxBuilder.costModels(PayoutWorld.PARAMS), PayoutWorld.tip());
        refused(outcome, "besides its thread token");
    }

    /** Review P2-f: the shard continuation must be the exact post-insert shard. */
    @Test
    void aShardContinuationThatIsNotTheFoldOfTheInsertsIsRefused() throws Exception {
        List<ClaimLeafV1> claims = world.claims(14, 2);
        world.anchor(claims, 20_008);
        PayoutTxBuilder.Built built = build(14, claims);
        int shardOutput = claims.size() + 1;

        Transaction wrongRoot = Transaction.deserialize(built.cbor());
        wrongRoot.getBody().getOutputs().get(shardOutput).setInlineDatum(GatewayPlutus.shardDatum(14,
                PayoutWorld.filled(32, 0x5c)));
        refused(check(wrongRoot.serialize(), new State(claims)), "post-insert shard");

        Transaction drained = Transaction.deserialize(built.cbor());
        var value = drained.getBody().getOutputs().get(shardOutput).getValue();
        value.setCoin(value.getCoin().subtract(BigInteger.ONE));
        refused(check(drained.serialize(), new State(claims)), "post-insert shard");

        Transaction withScript = Transaction.deserialize(built.cbor());
        withScript.getBody().getOutputs().get(shardOutput).setScriptRef(world.shardScript.scriptRefBytes());
        refused(check(withScript.serialize(), new State(claims)), "post-insert shard");

        // The honest body passes, so each refusal above is the continuation's.
        assertThat(check(built.cbor(), new State(claims))).isInstanceOf(SettleBodyCheck.Signable.class);
    }

    /** Review P2-g: a request that is not its own re-serialization is refused, so the signed hash is its id. */
    @Test
    void aRequestThatDoesNotReserializeToItsOwnBytesIsRefused() {
        List<ClaimLeafV1> claims = world.claims(15, 1);
        world.anchor(claims, 20_009);
        PayoutTxBuilder.Built built = build(15, claims);
        byte[] cbor = built.cbor();
        long fee = built.fee().longValueExact();
        assertThat(fee).isBetween(65_536L, 0xffff_ffffL);
        // The body's fee as a non-minimal 8-byte integer: the same decoded body, but other body bytes and id.
        byte[] minimal = {0x02, 0x1a, (byte) (fee >>> 24), (byte) (fee >>> 16), (byte) (fee >>> 8), (byte) fee};
        int at = indexOf(cbor, minimal);
        assertThat(at).isPositive();
        java.io.ByteArrayOutputStream padded = new java.io.ByteArrayOutputStream();
        padded.write(cbor, 0, at);
        padded.writeBytes(new byte[] {0x02, 0x1b, 0, 0, 0, 0});
        padded.write(cbor, at + 2, cbor.length - at - 2);
        refused(check(padded.toByteArray(), new State(claims)), "re-serialize");
    }

    /** Review P3-1: the validity interval is bounded by the member's own L1 tip. */
    @Test
    void aBodyWithoutATtlOrValidFarPastMyTipIsRefused() throws Exception {
        List<ClaimLeafV1> claims = world.claims(1, 1);
        world.anchor(claims, 20_010);
        State state = new State(claims);
        long now = PayoutWorld.NOW_SLOT;
        PayoutTxBuilder.Built farTtl = PayoutTxBuilder.settle(world.context(1, now, now + 901, PayoutWorld.PARAMS),
                claims, world.mirrors[1], PayoutWorld.signers(2), fixedUnits());
        SettleBodyCheck.Outcome tooFar = check(farTtl.cbor(), state);
        refused(tooFar, "TTL lies more than");
        assertThat(((SettleBodyCheck.Refused) tooFar).refusal().kind())
                .as("reported apart, so a lagging member clock shows in status")
                .isEqualTo(SettleBodyCheck.Refusal.Kind.L1_TIP_BOUND);
        PayoutTxBuilder.Built lateStart = PayoutTxBuilder.settle(world.context(1, now + 301, now + 400,
                PayoutWorld.PARAMS), claims, world.mirrors[1], PayoutWorld.signers(2), fixedUnits());
        refused(check(lateStart.cbor(), state), "validity start lies more than");
        Transaction noTtl = Transaction.deserialize(PayoutTxBuilder.settle(world.context(1, now, now + 100,
                PayoutWorld.PARAMS), claims, world.mirrors[1], PayoutWorld.signers(2), fixedUnits()).cbor());
        noTtl.getBody().setTtl(0);
        refused(check(noTtl.serialize(), state), "no TTL");
        // Inside both bounds, the same member signs.
        PayoutTxBuilder.Built inside = PayoutTxBuilder.settle(world.context(1, now + 300, now + 900,
                PayoutWorld.PARAMS), claims, world.mirrors[1], PayoutWorld.signers(2), fixedUnits());
        assertThat(check(inside.cbor(), state)).isInstanceOf(SettleBodyCheck.Signable.class);
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
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

    /** Codex P3-8: a body whose required signers name fewer distinct anchor members than its threshold is refused. */
    @Test
    void requiredSignersBelowTheAnchorThresholdAreRefused() throws Exception {
        List<ClaimLeafV1> claims = world.claims(12, 1);
        world.anchor(claims, 20_006);
        PayoutTxBuilder.Built built = build(12, claims);
        byte[] member = PayoutWorld.signers(1).getFirst();
        for (List<byte[]> signers : List.of(List.of(member), List.of(member, member),
                List.of(member, PayoutWorld.filled(28, 9)))) {
            Transaction tx = Transaction.deserialize(built.cbor());
            tx.getBody().setRequiredSigners(signers);
            refused(check(tx.serialize(), new State(claims)), "below its threshold");
        }
    }
}
