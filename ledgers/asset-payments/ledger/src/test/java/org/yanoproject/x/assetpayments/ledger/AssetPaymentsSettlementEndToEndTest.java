package org.yanoproject.x.assetpayments.ledger;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assets.cardano.gateway.AssetVaultPayoutObserverProvider;
import org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.cardano.support.settlement.SettleBodyCheck;
import org.yanoproject.x.assets.cardano.support.settlement.ShardMirror;
import org.yanoproject.x.assets.cardano.support.settlement.VaultPins;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.assets.contracts.SettlementRecords;
import org.yanoproject.x.framework.api.appchain.effects.EffectId;
import org.yanoproject.x.framework.api.appchain.effects.EffectIntent;
import org.yanoproject.x.framework.api.appchain.effects.EffectOutcome;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.WalletAuthorizer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.*;

/**
 * ADR-059 M4 end to end, in process: an owner-signed {@code REQUEST_WITHDRAWAL} reserves a USDM claim and the ledger
 * emits its {@code asset.settlement.batch}; the settlement builder turns the batch's committed claims into a
 * {@code Settle}, which a member's body check (D8h) accepts against the ledger's own committed state; the built
 * transaction, as real Conway block bytes, goes through the real payout observer; the finalized observation moves the
 * claim to {@code PAID}. The batch effect's own result never changes a claim's status (I12): it closes the batch
 * and re-queues the claims still {@code RESERVED}.
 */
class AssetPaymentsSettlementEndToEndTest {
    static final IntentEnvelopeV1.Owner OWNER = new IntentEnvelopeV1.Owner();
    static final byte[] SHARD_POLICY = HexFormat.of().parseHex(AssetPaymentsPayoutTest.SHARD_POLICY);
    static final byte[] ANCHOR_HASH = filled(28, 0x21);
    static final byte[] ANCHOR_POLICY = filled(28, 0xc1);
    static final byte[] EXIT_HASH = filled(28, 0x31);
    static final byte[] EXIT_POLICY = filled(28, 0xd1);
    static final byte[] FINGERPRINT = filled(32, 0xfa);
    static final byte[] MEMBER = filled(32, 0x0a);
    static final byte[] FEE_ADDRESS = TestWallet.cat(new byte[] {0x60}, filled(28, 0xee));

    final TestWallet alice = TestWallet.of(1);
    int nonce = 5_000;

    IntentEnvelopeV1 env(String action, byte[] body) {
        return envelope(alice.accountId(), action, OWNER, ++nonce, T0 + 600_000, body);
    }

    static VaultPins usdmPins() {
        return new VaultPins(USDM_VAULT, USDM, 0, USDM_HASH, TestWallet.cat(new byte[] {0x70}, USDM_HASH), SHARD_POLICY,
                AssetPaymentsPayoutTest.SHARD_HASH, TestWallet.cat(new byte[] {0x70}, AssetPaymentsPayoutTest.SHARD_HASH),
                new VaultPins.Anchor(ANCHOR_POLICY, new byte[0], ANCHOR_HASH, HexFormat.of().parseHex(GENESIS),
                        "asset-payments".getBytes(), "mpf-blake2b256-v1".getBytes(), FINGERPRINT),
                EXIT_POLICY, EXIT_HASH, "ap/claim/".getBytes(), "ap/domain-status".getBytes(),
                BigInteger.valueOf(2_000_000), 16, 8, 7L * 86_400_000);
    }

    static ResolvedOutput out(int fill, int index, byte[] address, long lovelace, Map<AssetId, Long> assets,
                              byte[] datum, byte[] scriptHash) {
        TreeMap<AssetId, BigInteger> values = new TreeMap<>();
        assets.forEach((asset, quantity) -> values.put(asset, BigInteger.valueOf(quantity)));
        return new ResolvedOutput(new ResolvedOutput.Outpoint(Hash32.of(filled(32, fill)), index), address,
                BigInteger.valueOf(lovelace), values, Optional.ofNullable(datum), scriptHash == null ? Optional.empty()
                : Optional.of(new ResolvedOutput.ReferenceScript(scriptHash, 15_000, Optional.empty())));
    }

    static byte[] bytes(PlutusData data) {
        try {
            return data.serializeToBytes();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static ProtocolParams params() {
        ProtocolParams pp = new ProtocolParams();
        pp.setMinFeeA(44);
        pp.setMinFeeB(155_381);
        pp.setMaxTxSize(16_384);
        pp.setPriceMem(new BigDecimal("0.0577"));
        pp.setPriceStep(new BigDecimal("0.0000721"));
        pp.setMaxTxExMem("16500000");
        pp.setMaxTxExSteps("10000000000");
        pp.setCollateralPercent(new BigDecimal(150));
        pp.setCoinsPerUtxoSize("4310");
        pp.setMinFeeRefScriptCostPerByte(new BigDecimal(15));
        LinkedHashMap<String, List<Long>> raw = new LinkedHashMap<>();
        raw.put("PlutusV3", Arrays.stream(CostModelUtil.plutusV3Costs).boxed().toList());
        pp.setCostModelsRaw(raw);
        return pp;
    }

    /** Fixed units: the compiled validators are evaluated on these layouts by the support library's tests. */
    static TransactionEvaluator fixedUnits() {
        return (cbor, inputs) -> {
            try {
                List<EvaluationResult> results = new ArrayList<>();
                for (var redeemer : Transaction.deserialize(cbor).getWitnessSet().getRedeemers()) {
                    EvaluationResult result = new EvaluationResult();
                    result.setRedeemerTag(redeemer.getTag());
                    result.setIndex(redeemer.getIndex().intValue());
                    result.setExUnits(ExUnits.builder().mem(BigInteger.valueOf(3_000_000))
                            .steps(BigInteger.valueOf(900_000_000)).build());
                    results.add(result);
                }
                return Result.success("ok").withValue(results);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
    }

    @Test
    void aReservedClaimIsSettledObservedAndPaid() throws Exception {
        Map<String, String> settings = AssetPaymentsWithdrawalTest.settings("settlement.batch-size", "1");
        PaymentsHarness ledger = new PaymentsHarness(settings, true);
        ledger.block(T0, intent(owner(alice, env(WalletAuthorizer.REGISTER_ACCOUNT, PaymentsBodies.REGISTER_ACCOUNT))));
        ledger.block(T0 + 1_000, new AssetPaymentsStateMachineTest().l1(
                AssetPaymentsStateMachineTest.output(USDM_ADDRESS, alice, AssetPaymentsStateMachineTest.lovelace(1_500_000),
                        AssetPaymentsStateMachineTest.token(USDM, 100))));

        // 1. Reservation (D7) and its batch effect.
        IntentEnvelopeV1 request = env(AssetPaymentsContract.REQUEST_WITHDRAWAL, new PaymentsBodies.RequestWithdrawal(
                USDM_VAULT, alice.address(), BigInteger.valueOf(40), BigInteger.valueOf(1_500_000)).encode());
        ledger.block(T0 + 2_000, intent(owner(alice, request)));
        assertThat(ledger.receipt(request).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        assertThat(ledger.emitted).hasSize(1);
        Map.Entry<EffectId, EffectIntent> effect = ledger.emitted.getFirst();
        SettlementBatchV1 batch = SettlementBatchV1.decode(effect.getValue().payload());
        Hash32 claimId = batch.claimIds().getFirst();
        SettleBodyCheck.Committed committed = new SettleBodyCheck.Committed() {
            @Override
            public Optional<SettlementQueries.ClaimView> claim(Hash32 id) {
                return SettlementQueries.decodeClaim(id, ledger.query(SettlementQueries.CLAIM, id.bytes()));
            }

            @Override
            public Optional<DomainStatusV1> domainStatus() {
                return SettlementQueries.decodeDomainStatus(ledger.query(SettlementQueries.DOMAIN_STATUS, new byte[0]));
            }
        };
        ClaimLeafV1 leaf = committed.claim(claimId).orElseThrow().leaf();

        // 2. The Settle, built from committed state only, over an L1 that holds the vault, shard, anchor and fee wallet.
        VaultPins pins = usdmPins();
        int shard = batch.shardIndex();
        Map<ResolvedOutput.Outpoint, ResolvedOutput> l1 = new LinkedHashMap<>();
        ResolvedOutput vault = out(0x61, 0, pins.vaultAddress(), 3_500_000, Map.of(USDM, 100L),
                HexFormat.of().parseHex("d87a8180"), null);
        ResolvedOutput shardIn = out(0x62, shard, pins.shardAddress(), 1_200_000, Map.of(pins.shardToken(shard), 1L),
                bytes(ConstrPlutusData.of(0, BigIntPlutusData.of(1), BigIntPlutusData.of(shard),
                        BytesPlutusData.of(new byte[32]))), null);
        PlutusData anchorDatum = ConstrPlutusData.of(0, BigIntPlutusData.of(2), BytesPlutusData.of(CHAIN.getBytes()),
                BytesPlutusData.of(HexFormat.of().parseHex(GENESIS)), BytesPlutusData.of("asset-payments".getBytes()),
                BytesPlutusData.of("mpf-blake2b256-v1".getBytes()), BytesPlutusData.of(FINGERPRINT),
                BigIntPlutusData.of(ledger.height), BytesPlutusData.of(filled(32, 0x66)),
                BytesPlutusData.of(filled(32, 0x55)), ListPlutusData.of(BytesPlutusData.of(MEMBER)),
                BigIntPlutusData.of(1), BigIntPlutusData.of(T0));
        ResolvedOutput anchor = out(0x63, 0, TestWallet.cat(new byte[] {0x70}, ANCHOR_HASH), 2_000_000,
                Map.of(pins.anchorToken(), 1L), bytes(anchorDatum), null);
        ResolvedOutput exitMode = out(0x64, 0, TestWallet.cat(new byte[] {0x70}, EXIT_HASH), 2_000_000,
                Map.of(pins.exitModeToken(), 1L), HexFormat.of().parseHex("d87980"), null);
        ResolvedOutput vaultRef = out(0x65, 0, FEE_ADDRESS, 40_000_000, Map.of(), null, USDM_HASH);
        ResolvedOutput shardRef = out(0x65, 1, FEE_ADDRESS, 20_000_000, Map.of(), null,
                AssetPaymentsPayoutTest.SHARD_HASH);
        ResolvedOutput fee = out(0x60, 0, FEE_ADDRESS, 50_000_000, Map.of(), null, null);
        for (ResolvedOutput o : List.of(vault, shardIn, anchor, exitMode, vaultRef, shardRef, fee)) l1.put(o.outpoint(), o);
        ShardMirror mirror = new ShardMirror(shard);
        PayoutTxBuilder.Context context = new PayoutTxBuilder.Context(pins, List.of(vault), shardIn, anchor, exitMode,
                List.of(vaultRef, shardRef), new PayoutTxBuilder.Funding(List.of(fee), FEE_ADDRESS), 1_000, 1_600,
                params(), 8);
        PayoutTxBuilder.Built built = PayoutTxBuilder.settle(context, List.of(leaf), mirror,
                List.of(Blake2bUtil.blake2bHash224(MEMBER)), fixedUnits());

        // A member's body check accepts it against the ledger's own committed state (D8h §1).
        SettleBodyCheck.Outcome check = SettleBodyCheck.check(built.cbor(), pins, filled(28, 0x99),
                outpoint -> Optional.ofNullable(l1.get(outpoint)), committed, PayoutTxBuilder.costModels(params()),
                new SettleBodyCheck.Tip(1_000, 1_000));
        assertThat(check).isInstanceOf(SettleBodyCheck.Signable.class);

        // 3. The included transaction, as Conway block bytes, through the real payout observer.
        List<DataItem> items = ((Array) CborSerializationUtil.deserialize(built.cbor())).getDataItems();
        byte[] block = L1BlockBytes.blockOf(92, 9_000, List.of(items.get(0)), List.of(items.get(1)));
        L1Observer payouts = new AssetVaultPayoutObserverProvider().create(AssetPaymentsPayoutTest.PAYOUTS,
                AssetPaymentsPayoutTest.payoutSettings());
        List<L1Observation> observations = payouts.observe(9_000, filled(32, 0xb9), L1BlockBytes.parse(block));
        assertThat(observations).singleElement();

        // Before the observation, the claim is RESERVED, whatever the effect result says (I12).
        ledger.effectResult(T0 + 3_000, effect.getKey(), effect.getValue(), EffectOutcome.CONFIRMED);
        assertThat(status(ledger, claimId)).isEqualTo(SettlementRecords.ClaimStatus.RESERVED);

        // 4. The finalized observation confirms: PAID, paid counters, no halt.
        ledger.block(T0 + 4_000, observations.stream().map(PaymentsHarness::deposit).toArray(Message[]::new));
        assertThat(status(ledger, claimId)).isEqualTo(SettlementRecords.ClaimStatus.PAID);
        assertThat(ledger.counters(USDM_VAULT).paid()).isEqualTo(BigInteger.valueOf(40));
        assertThat(ledger.counters(USDM_VAULT).lovelacePaid()).isEqualTo(BigInteger.valueOf(1_500_000));
        assertThat(ledger.counters(USDM_VAULT).reserved()).isZero();
        assertThat(SettlementQueries.decodeDomainStatus(ledger.query(SettlementQueries.DOMAIN_STATUS, new byte[0]))
                .orElseThrow().status()).isEqualTo(DomainStatusV1.Status.ACTIVE);
        ledger.checkInvariants();
    }

    static SettlementRecords.ClaimStatus status(PaymentsHarness ledger, Hash32 claimId) {
        return SettlementQueries.decodeClaim(claimId, ledger.query(SettlementQueries.CLAIM, claimId.bytes()))
                .orElseThrow().status().status();
    }
}
