package org.yanoproject.x.assetpayments.ledger;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assets.cardano.gateway.AssetVaultDepositObserverProvider;
import org.yanoproject.x.assets.cardano.support.settlement.AcceptBodyCheck;
import org.yanoproject.x.assets.cardano.support.settlement.AcceptTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.cardano.support.settlement.SettleBodyCheck;
import org.yanoproject.x.assets.cardano.support.settlement.ShardMirror;
import org.yanoproject.x.assets.cardano.support.settlement.VaultPins;
import org.yanoproject.x.assets.contracts.AssetDepositClaimV1;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DepositDatumV1;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.assets.contracts.VaultOutputShape;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.WalletAuthorizer;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsSettlementEndToEndTest.FEE_ADDRESS;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsSettlementEndToEndTest.FINGERPRINT;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsSettlementEndToEndTest.MEMBER;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsSettlementEndToEndTest.bytes;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsSettlementEndToEndTest.fixedUnits;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsSettlementEndToEndTest.out;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsSettlementEndToEndTest.params;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsSettlementEndToEndTest.usdmPins;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.*;

/**
 * ADR-059 D8f in process: a deposit observed by the real deposit observer is credited by the ledger (D6); the
 * owner's {@code Accept} is built from that L1 output and the ledger's committed record, and a member's {@code Accept}
 * body check signs it against the same committed state; included, its output is a continuation, never a deposit, and
 * it is accepted custody (V10) that the next {@code Settle} spends, which a member's {@code Settle} body check signs.
 */
class AssetPaymentsAcceptEndToEndTest {
    static final IntentEnvelopeV1.Owner OWNER = new IntentEnvelopeV1.Owner();

    final TestWallet alice = TestWallet.of(1);
    int nonce = 7_000;

    IntentEnvelopeV1 env(String action, byte[] body) {
        return envelope(alice.accountId(), action, OWNER, ++nonce, T0 + 600_000, body);
    }

    @Test
    void aCreditedDepositIsAcceptedAndItsContinuationIsCustodyTheNextSettleSpends() throws Exception {
        PaymentsHarness ledger = new PaymentsHarness(AssetPaymentsWithdrawalTest.settings("settlement.batch-size",
                "1"), true);
        ledger.block(T0, intent(owner(alice, env(WalletAuthorizer.REGISTER_ACCOUNT, PaymentsBodies.REGISTER_ACCOUNT))));

        // 1. The deposit, observed on L1 by the real observer, is credited (D6).
        AssetPaymentsStateMachineTest observed = new AssetPaymentsStateMachineTest();
        Message[] messages = observed.l1(AssetPaymentsStateMachineTest.output(USDM_ADDRESS, alice,
                AssetPaymentsStateMachineTest.lovelace(1_500_000), AssetPaymentsStateMachineTest.token(USDM, 100)));
        ledger.block(T0 + 1_000, messages);
        AssetDepositClaimV1 claim = AssetDepositClaimV1.decode(L1Observation.decode(messages[0].body()).claim());
        DepositRecordV1 record = SettlementQueries.decodeDeposit(claim.depositId(),
                ledger.query(SettlementQueries.DEPOSIT, claim.depositId().bytes())).orElseThrow();
        assertThat(record.status()).isEqualTo(DepositRecordV1.Status.CREDITED);
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(100));

        // 2. A withdrawal is reserved; the anchor then covers both the credit and the reservation.
        IntentEnvelopeV1 request = env(AssetPaymentsContract.REQUEST_WITHDRAWAL, new PaymentsBodies.RequestWithdrawal(
                USDM_VAULT, alice.address(), BigInteger.valueOf(40), BigInteger.valueOf(1_500_000)).encode());
        ledger.block(T0 + 2_000, intent(owner(alice, request)));
        assertThat(ledger.receipt(request).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        SettlementBatchV1 batch = SettlementBatchV1.decode(ledger.emitted.getFirst().getValue().payload());

        VaultPins pins = usdmPins();
        ResolvedOutput deposit = new ResolvedOutput(new ResolvedOutput.Outpoint(claim.txId(),
                (int) claim.outputIndex()), pins.vaultAddress(), BigInteger.valueOf(1_500_000), values(USDM, 100),
                Optional.of(new DepositDatumV1(alice.accountId(), alice.address(), alice.keyHash()).encode()),
                Optional.empty());
        assertThat(AcceptTxBuilder.depositShaped(deposit, pins)).isTrue();
        assertThat(AcceptTxBuilder.depositId(pins, deposit.outpoint())).isEqualTo(claim.depositId());
        // A floor top-up keeps the vault's continuation above the floor once the claim is paid.
        ResolvedOutput floor = out(0x61, 1, pins.vaultAddress(), 2_000_000, Map.of(),
                VaultOutputShape.floorTopUpDatum(), null);
        ResolvedOutput anchor = out(0x63, 0, TestWallet.cat(new byte[] {0x70},
                AssetPaymentsSettlementEndToEndTest.ANCHOR_HASH), 2_000_000, Map.of(pins.anchorToken(), 1L),
                bytes(anchorDatum(ledger.height)), null);
        ResolvedOutput exitMode = out(0x64, 0, TestWallet.cat(new byte[] {0x70},
                AssetPaymentsSettlementEndToEndTest.EXIT_HASH), 2_000_000, Map.of(pins.exitModeToken(), 1L),
                HexFormat.of().parseHex("d87980"), null);
        ResolvedOutput vaultRef = out(0x65, 0, FEE_ADDRESS, 40_000_000, Map.of(), null, USDM_HASH);
        ResolvedOutput shardRef = out(0x65, 1, FEE_ADDRESS, 20_000_000, Map.of(), null,
                AssetPaymentsPayoutTest.SHARD_HASH);
        ResolvedOutput fee = out(0x60, 0, FEE_ADDRESS, 50_000_000, Map.of(), null, null);
        Map<ResolvedOutput.Outpoint, ResolvedOutput> l1 = new LinkedHashMap<>();
        for (ResolvedOutput o : List.of(deposit, floor, anchor, exitMode, vaultRef, shardRef, fee)) {
            l1.put(o.outpoint(), o);
        }

        // 3. The co-signed Accept, signed by a member that checks it against the ledger's committed records.
        AcceptTxBuilder.Built accept = AcceptTxBuilder.cosigned(new AcceptTxBuilder.Context(pins, anchor, exitMode,
                        List.of(vaultRef), new PayoutTxBuilder.Funding(List.of(fee), FEE_ADDRESS), 1_000, 1_600,
                        params()), List.of(deposit), List.of(Blake2bUtil.blake2bHash224(MEMBER)), fixedUnits());
        AcceptBodyCheck.Deposits committedDeposits = id -> SettlementQueries.decodeDeposit(id,
                ledger.query(SettlementQueries.DEPOSIT, id.bytes()));
        assertThat(AcceptBodyCheck.check(accept.cbor(), pins, filled(28, 0x99), outpoint ->
                        Optional.ofNullable(l1.get(outpoint)), committedDeposits,
                PayoutTxBuilder.costModels(params()), new SettleBodyCheck.Tip(1_000, 1_000)))
                .isInstanceOf(SettleBodyCheck.Signable.class);

        // 4. Included: its output is a clean continuation, vault custody the deposit observer reports nothing about,
        // so it is never credited again.
        List<DataItem> items = ((Array) CborSerializationUtil.deserialize(accept.cbor())).getDataItems();
        byte[] block = L1BlockBytes.blockOf(93, 9_100, List.of(items.get(0)), List.of(items.get(1)));
        L1Observer deposits = new AssetVaultDepositObserverProvider().create(OBSERVER, observerSettings());
        assertThat(deposits.observe(9_100, filled(32, 0xba), L1BlockBytes.parse(block))).isEmpty();
        assertThat(VaultOutputShape.classify(accept.continuation().inlineDatum().orElseThrow()))
                .isEqualTo(VaultOutputShape.CONTINUATION);
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(60));
        ledger.checkInvariants();
        l1.remove(deposit.outpoint());
        l1.remove(fee.outpoint());
        ResolvedOutput continuation = accept.continuation();
        assertThat(PayoutTxBuilder.accepted(continuation, pins)).isTrue();
        l1.put(continuation.outpoint(), continuation);
        ResolvedOutput change = out(0x66, 0, FEE_ADDRESS, 40_000_000, Map.of(), null, null);
        l1.put(change.outpoint(), change);

        // 5. The next Settle spends the accepted continuation, and a member's Settle body check signs it.
        int shard = batch.shardIndex();
        ResolvedOutput shardIn = out(0x62, shard, pins.shardAddress(), 1_200_000, Map.of(pins.shardToken(shard), 1L),
                bytes(ConstrPlutusData.of(0, BigIntPlutusData.of(1), BigIntPlutusData.of(shard),
                        BytesPlutusData.of(new byte[32]))), null);
        l1.put(shardIn.outpoint(), shardIn);
        SettleBodyCheck.Committed committed = new SettleBodyCheck.Committed() {
            @Override
            public Optional<SettlementQueries.ClaimView> claim(Hash32 id) {
                return SettlementQueries.decodeClaim(id, ledger.query(SettlementQueries.CLAIM, id.bytes()));
            }

            @Override
            public Optional<DomainStatusV1> domainStatus() {
                return SettlementQueries.decodeDomainStatus(ledger.query(SettlementQueries.DOMAIN_STATUS,
                        new byte[0]));
            }
        };
        ClaimLeafV1 leaf = committed.claim(batch.claimIds().getFirst()).orElseThrow().leaf();
        PayoutTxBuilder.Built settle = PayoutTxBuilder.settle(new PayoutTxBuilder.Context(pins,
                        List.of(continuation, floor), shardIn, anchor, exitMode, List.of(vaultRef, shardRef),
                        new PayoutTxBuilder.Funding(List.of(change), FEE_ADDRESS), 1_000, 1_600, params(), 8),
                List.of(leaf), new ShardMirror(shard), List.of(Blake2bUtil.blake2bHash224(MEMBER)), fixedUnits());
        assertThat(settle.resolvedInputs()).extracting(ResolvedOutput::outpoint).contains(continuation.outpoint());
        assertThat(SettleBodyCheck.check(settle.cbor(), pins, filled(28, 0x99),
                outpoint -> Optional.ofNullable(l1.get(outpoint)), committed, PayoutTxBuilder.costModels(params()),
                new SettleBodyCheck.Tip(1_000, 1_000))).isInstanceOf(SettleBodyCheck.Signable.class);
    }

    static PlutusData anchorDatum(long height) {
        return ConstrPlutusData.of(0, BigIntPlutusData.of(2), BytesPlutusData.of(CHAIN.getBytes()),
                BytesPlutusData.of(HexFormat.of().parseHex(GENESIS)), BytesPlutusData.of("asset-payments".getBytes()),
                BytesPlutusData.of("mpf-blake2b256-v1".getBytes()), BytesPlutusData.of(FINGERPRINT),
                BigIntPlutusData.of(height), BytesPlutusData.of(filled(32, 0x66)),
                BytesPlutusData.of(filled(32, 0x55)), ListPlutusData.of(BytesPlutusData.of(MEMBER)),
                BigIntPlutusData.of(1), BigIntPlutusData.of(T0));
    }

    static java.util.TreeMap<AssetId, BigInteger> values(AssetId asset, long quantity) {
        java.util.TreeMap<AssetId, BigInteger> values = new java.util.TreeMap<>();
        values.put(asset, BigInteger.valueOf(quantity));
        return values;
    }
}
