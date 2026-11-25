package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.BalanceExitObservationV1;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.PayoutClaimV1;
import org.yanoproject.x.assets.contracts.PayoutConfirmation;
import org.yanoproject.x.assets.contracts.PayoutObservationV1;
import org.yanoproject.x.assets.contracts.SettlementRecords;
import org.yanoproject.x.assets.contracts.SweepPaidV1;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverConsensusIdentity;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.assets.cardano.gateway.SettlementFixtures.*;

/**
 * ADR-059 D10 and D8h §4 through the {@code asset-vault-payout-v1} observer: thread authentication, the r15/r16
 * discriminator (Settle, Exit, SweepExit, BalanceExit), look-alikes that never emit or halt (I2, I18), and the
 * consumer side through {@link PayoutConfirmation}, including the PAID_MISMATCH halt and a re-payment after a
 * rolled-back payout.
 */
class AssetVaultPayoutObserverTest {
    static final String OBSERVER = "payouts";
    final AssetVaultPayoutObserver observer = new AssetVaultPayoutObserver(OBSERVER, shardSettings());

    List<L1Observation> observe(Block block) {
        return observer.observe(SLOT, BLOCK_HASH, block);
    }

    List<L1Observation> observe(TransactionBody tx, Witnesses witnesses) {
        return observe(block(List.of(), List.of(tx), List.of(witnesses)));
    }

    static PayoutClaimV1 claim(L1Observation observation) {
        return PayoutClaimV1.decode(observation.claim());
    }

    /** A USDM Settle (or Exit) paying two claims on shard 3: payouts 0..1, continuation 2, shard output 3. */
    static TransactionBody settle(int hashByte, Hash32 first, Hash32 second) {
        return tx(hashByte, List.of(),
                output(ALICE, null, lovelace(1_800_000), usdm(100)),
                output(BOB, null, lovelace(1_500_000), usdm(25)),
                output(USDM_VAULT, continuation(first, second), lovelace(5_000_000), usdm(875)),
                output(USDM_SHARD, shardDatum(3, filled(32, 0x99)), lovelace(1_200_000),
                        shardToken(USDM_SHARD_POLICY, 3)));
    }

    static Witnesses settleWitnesses(Hash32 first, Hash32 second) {
        return spends(settleRedeemer(3, first, second), followRedeemer(), shardRedeemer(first, second));
    }

    @Test
    void aSettleIsObservedWithItsClaimsFromTheContinuationAndItsPayoutSummaries() {
        Hash32 first = claimOnShard(1, 3);
        Hash32 second = claimOnShard(2, 3);
        List<L1Observation> observations = observe(settle(0x31, first, second), settleWitnesses(first, second));

        assertThat(observations).singleElement().satisfies(observation -> {
            assertThat(observation.observerId()).isEqualTo(OBSERVER);
            assertThat(observation.eventOrdinal()).isEqualTo(3);
            assertThat(observation.transactionAnchor().transactionHash()).isEqualTo(filled(32, 0x31));
        });
        PayoutObservationV1 payout = (PayoutObservationV1) claim(observations.getFirst());
        assertThat(payout.domainId()).isEqualTo(DOMAIN);
        assertThat(payout.vaultId()).isEqualTo(USDM_ID);
        assertThat(payout.shardIndex()).isEqualTo(3);
        assertThat(payout.source()).isEqualTo(PayoutObservationV1.Source.CONTINUATION);
        assertThat(payout.claimIds()).containsExactly(first, second);
        assertThat(payout.payouts()).containsExactly(
                new PayoutClaimV1.Payout(raw(ALICE), BigInteger.valueOf(100), BigInteger.valueOf(1_800_000)),
                new PayoutClaimV1.Payout(raw(BOB), BigInteger.valueOf(25), BigInteger.valueOf(1_500_000)));
        assertThat(payout.continuation()).hasValueSatisfying(continuation -> {
            assertThat(continuation.outputIndex()).isEqualTo(2);
            assertThat(continuation.registeredQuantity()).isEqualTo(BigInteger.valueOf(875));
            assertThat(continuation.lovelace()).isEqualTo(BigInteger.valueOf(5_000_000));
            assertThat(continuation.otherAssetCount()).isZero();
        });
    }

    @Test
    void anExitIsReadTheSameWayAndAnAdaPayoutReportsLovelaceAsItsQuantity() {
        Hash32 claimId = claimOnShard(4, 7);
        byte[] exit = constr(1, list(constr(0, constr(0, bytes(claimId.bytes())), emptyProof())), emptyProof(),
                integer(7));
        TransactionBody tx = tx(0x32, List.of(),
                output(ALICE, null, lovelace(5_100_000)),
                output(ADA_VAULT, continuation(claimId), lovelace(20_000_000)),
                output(ADA_SHARD, shardDatum(7, filled(32, 0x98)), lovelace(1_200_000),
                        shardToken(ADA_SHARD_POLICY, 7)));
        PayoutObservationV1 payout = (PayoutObservationV1) claim(observe(tx, spends(exit, shardRedeemer(claimId)))
                .getFirst());
        assertThat(payout.vaultId()).isEqualTo(ADA_ID);
        assertThat(payout.claimIds()).containsExactly(claimId);
        assertThat(payout.payouts().getFirst().quantity()).isEqualTo(BigInteger.valueOf(5_100_000));
        assertThat(payout.payouts().getFirst().lovelace()).isEqualTo(BigInteger.valueOf(5_100_000));
    }

    @Test
    void aSweepExitReadsOutputZerosSweepPaidAndCarriesNoContinuation() {
        Hash32 claimId = claimOnShard(5, 9);
        TransactionBody tx = tx(0x33, List.of(),
                output(ALICE, new SweepPaidV1(claimId).encode(), lovelace(2_300_000), usdm(7)),
                output(USDM_SHARD, shardDatum(9, filled(32, 0x97)), lovelace(1_200_000),
                        shardToken(USDM_SHARD_POLICY, 9)));
        byte[] sweep = constr(2, bytes(claimId.bytes()), bytes(raw(ALICE)), integer(1), integer(1), list(),
                emptyProof(), emptyProof(), integer(0), integer(9));
        PayoutObservationV1 payout = (PayoutObservationV1) claim(observe(tx, spends(sweep, shardRedeemer(claimId)))
                .getFirst());
        assertThat(payout.source()).isEqualTo(PayoutObservationV1.Source.SWEEP_PAID);
        assertThat(payout.claimIds()).containsExactly(claimId);
        assertThat(payout.continuation()).isEmpty();
        assertThat(payout.payouts().getFirst().quantity()).isEqualTo(BigInteger.valueOf(7));
    }

    @Test
    void aBalanceExitHasItsOwnObservationThatRecordsTheEscapeNullifier() {
        byte[] holder = filled(28, 0x44);
        Hash32 nullifier = GatewayIds.escapeNullifier(USDM_ID, holder);
        int shard = nullifier.bytes()[31] & 0x0f;
        TransactionBody tx = tx(0x34, List.of(),
                output(ALICE, null, lovelace(1_000_000), usdm(40)),
                output(USDM_VAULT, continuation(nullifier), lovelace(3_000_000), usdm(60)),
                output(USDM_SHARD, shardDatum(shard, filled(32, 0x96)), lovelace(1_200_000),
                        shardToken(USDM_SHARD_POLICY, shard)));
        List<L1Observation> observations = observe(tx, spends(balanceExitRedeemer(holder, shard),
                shardRedeemer(nullifier)));

        BalanceExitObservationV1 exit = (BalanceExitObservationV1) claim(observations.getFirst());
        assertThat(exit.escapeNullifier()).isEqualTo(nullifier);
        assertThat(exit.holderId()).isEqualTo(holder);
        assertThat(exit.payout()).isEqualTo(new PayoutClaimV1.Payout(raw(ALICE), BigInteger.valueOf(40),
                BigInteger.valueOf(1_000_000)));
        assertThat(exit.continuation().outputIndex()).isEqualTo(1);

        // A BalanceExit redeemer whose holder does not derive the listed nullifier is no observation at all.
        assertThat(observe(tx, spends(balanceExitRedeemer(filled(28, 0x45), shard), shardRedeemer(nullifier))))
                .isEmpty();
    }

    @Test
    void lookAlikesWithoutAnAuthenticShardThreadOutputAreIgnored() {
        Hash32 first = claimOnShard(1, 3);
        Hash32 second = claimOnShard(2, 3);
        Witnesses witnesses = settleWitnesses(first, second);
        List<TransactionBody> lookAlikes = List.of(
                // A7: a continuation and payouts, but no shard output.
                tx(0x40, List.of(), output(ALICE, null, lovelace(1_800_000), usdm(100)),
                        output(BOB, null, lovelace(1_500_000), usdm(25)),
                        output(USDM_VAULT, continuation(first, second), lovelace(5_000_000), usdm(875))),
                // A token-less output at the shard address.
                tx(0x41, List.of(), output(ALICE, null, lovelace(1_800_000), usdm(100)),
                        output(BOB, null, lovelace(1_500_000), usdm(25)),
                        output(USDM_VAULT, continuation(first, second), lovelace(5_000_000), usdm(875)),
                        output(USDM_SHARD, shardDatum(3, filled(32, 1)), lovelace(1_200_000))),
                // A token of another policy (the ADA vault's thread) at USDM's shard address.
                tx(0x42, List.of(), output(ALICE, null, lovelace(1_800_000), usdm(100)),
                        output(BOB, null, lovelace(1_500_000), usdm(25)),
                        output(USDM_VAULT, continuation(first, second), lovelace(5_000_000), usdm(875)),
                        output(USDM_SHARD, shardDatum(3, filled(32, 1)), lovelace(1_200_000),
                                shardToken(ADA_SHARD_POLICY, 3))),
                // The real thread token, but at another address.
                tx(0x43, List.of(), output(ALICE, null, lovelace(1_800_000), usdm(100)),
                        output(BOB, null, lovelace(1_500_000), usdm(25)),
                        output(USDM_VAULT, continuation(first, second), lovelace(5_000_000), usdm(875)),
                        output(BOB, shardDatum(3, filled(32, 1)), lovelace(1_200_000),
                                shardToken(USDM_SHARD_POLICY, 3))),
                // Two outputs at the shard address.
                tx(0x44, List.of(), output(ALICE, null, lovelace(1_800_000), usdm(100)),
                        output(BOB, null, lovelace(1_500_000), usdm(25)),
                        output(USDM_VAULT, continuation(first, second), lovelace(5_000_000), usdm(875)),
                        output(USDM_SHARD, shardDatum(3, filled(32, 1)), lovelace(1_200_000),
                                shardToken(USDM_SHARD_POLICY, 3)),
                        output(USDM_SHARD, null, lovelace(1_200_000))),
                // The bootstrap mint is never a payout (D8h §4).
                tx(0x45, List.of(shardToken(USDM_SHARD_POLICY, 3)),
                        output(ALICE, null, lovelace(1_800_000), usdm(100)),
                        output(BOB, null, lovelace(1_500_000), usdm(25)),
                        output(USDM_VAULT, continuation(first, second), lovelace(5_000_000), usdm(875)),
                        output(USDM_SHARD, shardDatum(3, filled(32, 1)), lovelace(1_200_000),
                                shardToken(USDM_SHARD_POLICY, 3))),
                // Two tokens of the policy in the shard output.
                tx(0x46, List.of(), output(ALICE, null, lovelace(1_800_000), usdm(100)),
                        output(BOB, null, lovelace(1_500_000), usdm(25)),
                        output(USDM_VAULT, continuation(first, second), lovelace(5_000_000), usdm(875)),
                        output(USDM_SHARD, shardDatum(3, filled(32, 1)), lovelace(1_200_000),
                                shardToken(USDM_SHARD_POLICY, 3), shardToken(USDM_SHARD_POLICY, 4))),
                // A SweepPaidV1 on output 0 of a transaction with no thread token.
                tx(0x47, List.of(), output(ALICE, new SweepPaidV1(first).encode(), lovelace(2_000_000), usdm(7))));
        for (TransactionBody lookAlike : lookAlikes) {
            assertThat(observe(lookAlike, witnesses)).as(lookAlike.getTxHash()).isEmpty();
        }
    }

    @Test
    void shapesNoValidVaultSpendProducesAreSkipped() {
        Hash32 first = claimOnShard(1, 3);
        Hash32 second = claimOnShard(2, 3);
        Witnesses witnesses = settleWitnesses(first, second);
        TransactionOutput shard = output(USDM_SHARD, shardDatum(3, filled(32, 1)), lovelace(1_200_000),
                shardToken(USDM_SHARD_POLICY, 3));
        List<TransactionBody> malformed = List.of(
                // A4: a malformed continuation datum.
                tx(0x50, List.of(), output(ALICE, null, lovelace(1_800_000), usdm(100)),
                        output(USDM_VAULT, constr(1, bytes(new byte[3])), lovelace(5_000_000), usdm(875)), shard),
                // The continuation at the wrong index (it lists two ids but sits at output 1).
                tx(0x51, List.of(), output(ALICE, null, lovelace(1_800_000), usdm(100)),
                        output(USDM_VAULT, continuation(first, second), lovelace(5_000_000), usdm(875)), shard),
                // Two outputs at the vault address.
                tx(0x52, List.of(), output(ALICE, null, lovelace(1_800_000), usdm(100)),
                        output(BOB, null, lovelace(1_500_000), usdm(25)),
                        output(USDM_VAULT, continuation(first, second), lovelace(5_000_000), usdm(875)),
                        output(USDM_VAULT, null, lovelace(1_000_000)), shard),
                // An empty continuation (an Accept never spends a shard).
                tx(0x53, List.of(), output(USDM_VAULT, continuation(), lovelace(5_000_000), usdm(875)), shard),
                // No vault output and no SweepPaidV1 on output 0.
                tx(0x54, List.of(), output(ALICE, null, lovelace(1_800_000), usdm(100)), shard));
        for (TransactionBody tx : malformed) {
            assertThat(observe(tx, witnesses)).as(tx.getTxHash()).isEmpty();
        }
        // Without a readable witness set the discriminator is unknown, so nothing is guessed.
        assertThat(observe(block(List.of(), List.of(settle(0x55, first, second)), List.of()))).isEmpty();
    }

    @Test
    void aPhaseTwoInvalidPayoutIsNeverObservedAndObservationIsDeterministic() {
        Hash32 first = claimOnShard(1, 3);
        Hash32 second = claimOnShard(2, 3);
        Block block = block(List.of(0), List.of(settle(0x56, first, second), settle(0x57, first, second)),
                List.of(settleWitnesses(first, second), settleWitnesses(first, second)));
        List<L1Observation> observations = observe(block);
        assertThat(observations).singleElement().satisfies(observation -> assertThat(
                observation.transactionAnchor().transactionHash()).isEqualTo(filled(32, 0x57)));
        assertThat(observe(block)).isEqualTo(observations);
        assertThat(new AssetVaultPayoutObserver(OBSERVER, shardSettings()).observe(SLOT, BLOCK_HASH, block))
                .isEqualTo(observations);
        assertThat(observer.observe(SLOT, null, block)).isEmpty();
        assertThat(observer.observe(SLOT, BLOCK_HASH, null)).isEmpty();
    }

    @Test
    void realBlockBytesCarryTheRedeemersTheDiscriminatorReads() {
        byte[] holder = filled(28, 0x46);
        Hash32 nullifier = GatewayIds.escapeNullifier(USDM_ID, holder);
        int shard = nullifier.bytes()[31] & 0x0f;
        Hash32 first = claimOnShard(1, 3);
        L1BlockBytes.Tx settleTx = new L1BlockBytes.Tx(1, List.of(
                new L1BlockBytes.Output(raw(ALICE), 1_800_000, Map.of(USDM, 100L), null),
                new L1BlockBytes.Output(raw(USDM_VAULT), 5_000_000, Map.of(USDM, 875L), continuation(first)),
                new L1BlockBytes.Output(raw(USDM_SHARD), 1_200_000, Map.of(shardAsset(3), 1L),
                        shardDatum(3, filled(32, 1)))),
                Map.of(), List.of(settleRedeemer(3, first), shardRedeemer(first)));
        L1BlockBytes.Tx exitTx = new L1BlockBytes.Tx(2, List.of(
                new L1BlockBytes.Output(raw(ALICE), 1_000_000, Map.of(USDM, 40L), null),
                new L1BlockBytes.Output(raw(USDM_VAULT), 3_000_000, Map.of(USDM, 60L), continuation(nullifier)),
                new L1BlockBytes.Output(raw(USDM_SHARD), 1_200_000, Map.of(shardAsset(shard), 1L),
                        shardDatum(shard, filled(32, 2)))),
                Map.of(), List.of(balanceExitRedeemer(holder, shard), shardRedeemer(nullifier)));
        Block block = L1BlockBytes.parse(L1BlockBytes.block(91, SLOT, List.of(settleTx, exitTx), List.of()));

        List<L1Observation> observations = observe(block);
        assertThat(observations).hasSize(2);
        assertThat(claim(observations.get(0))).isInstanceOfSatisfying(PayoutObservationV1.class,
                payout -> assertThat(payout.claimIds()).containsExactly(first));
        assertThat(claim(observations.get(1))).isInstanceOfSatisfying(BalanceExitObservationV1.class,
                exit -> assertThat(exit.escapeNullifier()).isEqualTo(nullifier));
    }

    static AssetId shardAsset(int shard) {
        return AssetId.of(HexFormat.of().parseHex(USDM_SHARD_POLICY), new byte[] {(byte) shard});
    }

    @Test
    void settingsAreValidatedAndEveryValueFeedsTheConsensusIdentity() {
        L1ObserverConsensusIdentity identity = observer.consensusIdentity();
        assertThat(identity.claimSchema()).isEqualTo(PayoutClaimV1.CLAIM_SCHEMA);
        Map<String, String> otherPolicy = new TreeMap<>(shardSettings());
        otherPolicy.put("vaults.1.shard-policy-id", "a3".repeat(28));
        assertThat(new AssetVaultPayoutObserver(OBSERVER, otherPolicy).consensusIdentity().canonicalIdentityBytes())
                .isNotEqualTo(identity.canonicalIdentityBytes());

        Map<String, String> wrongShardAddress = new TreeMap<>(shardSettings());
        wrongShardAddress.put("vaults.1.shard-address", USDM_VAULT);
        assertThatThrownBy(() -> new AssetVaultPayoutObserver(OBSERVER, wrongShardAddress))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, String> sharedPolicy = new TreeMap<>(shardSettings());
        sharedPolicy.put("vaults.1.shard-policy-id", ADA_SHARD_POLICY);
        assertThatThrownBy(() -> new AssetVaultPayoutObserver(OBSERVER, sharedPolicy))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, String> unknown = new TreeMap<>(shardSettings());
        unknown.put("vaults.1.shard-root", "00");
        assertThatThrownBy(() -> new AssetVaultPayoutObserver(OBSERVER, unknown))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AssetVaultPayoutObserverProvider().type()).isEqualTo("asset-vault-payout-v1");
    }

    // ------------------------------------------------------------------ end to end with PayoutConfirmation

    /** The consumer: a staged store, the registry, and PayoutConfirmation over canonical-order observations. */
    static final class Consumer {
        final TreeMap<String, byte[]> state = new TreeMap<>();
        final AssetAccounts.Store store = new AssetAccounts.Store() {
            @Override public Optional<byte[]> get(byte[] key) {
                return Optional.ofNullable(state.get(HexFormat.of().formatHex(key)));
            }
            @Override public void put(byte[] key, byte[] value) {
                state.put(HexFormat.of().formatHex(key), value);
            }
            @Override public void delete(byte[] key) {
                state.remove(HexFormat.of().formatHex(key));
            }
        };
        final byte[] prefix = "ap/".getBytes(StandardCharsets.US_ASCII);
        final PayoutConfirmation confirmation = new PayoutConfirmation(prefix, OBSERVER, DOMAIN, 86_400_000L,
                id -> Map.of(ADA_ID, new PayoutConfirmation.VaultBinding(ADA_ID, AssetId.ADA),
                        USDM_ID, new PayoutConfirmation.VaultBinding(USDM_ID, USDM)).entrySet().stream()
                        .filter(entry -> entry.getKey().equals(id)).map(Map.Entry::getValue).findFirst());
        final AssetAccounts accounts = new AssetAccounts(prefix, store);

        ClaimLeafV1 reserve(String recipient, long amount, long vaultLovelace, long sequence) {
            AssetValue value = AssetValue.single(USDM, BigInteger.valueOf(amount));
            byte[] recipientBytes = raw(recipient);
            Hash32 claimId = GatewayIds.claimId(DOMAIN, USDM_ID, ClaimKind.USER_WITHDRAWAL, new byte[] {1},
                    recipientBytes, value, BigInteger.valueOf(vaultLovelace), sequence);
            ClaimLeafV1 leaf = new ClaimLeafV1(claimId, USDM_ID, ClaimKind.USER_WITHDRAWAL, value,
                    BigInteger.valueOf(vaultLovelace), recipientBytes, 3, 1_000);
            accounts.credit(USDM_ID, ALICE_KEY, ALICE_KEY, BigInteger.valueOf(amount),
                    BigInteger.valueOf(vaultLovelace));
            accounts.reserve(USDM_ID, ALICE_KEY, BigInteger.valueOf(amount), BigInteger.valueOf(vaultLovelace));
            confirmation.writeReserved(store, leaf);
            return leaf;
        }

        List<PayoutConfirmation.Result> apply(List<L1Observation> observations, long height) {
            List<L1Observation> ordered = new ArrayList<>(observations);
            ordered.sort(L1Observation.CANONICAL_ORDER);
            List<PayoutConfirmation.Result> results = new ArrayList<>();
            for (L1Observation observation : ordered) {
                results.add(confirmation.apply(store, new PayoutConfirmation.ObservedPayout(observation.observerId(),
                        observation.transactionAnchor().transactionHash(), observation.eventOrdinal(),
                        observation.slot(), observation.blockHash(), observation.claim()), height, 1_000_000));
            }
            return results;
        }

        SettlementRecords.ClaimStatus status(Hash32 claimId) {
            return confirmation.claimStatus(store, claimId).orElseThrow().status();
        }
    }

    /** A USDM Settle for real claims whose ids route to {@code shard}, built from their leaves. */
    static TransactionBody settleOf(int hashByte, int shard, List<ClaimLeafV1> leaves, List<String> recipients) {
        List<TransactionOutput> outputs = new ArrayList<>();
        for (int i = 0; i < leaves.size(); i++) {
            outputs.add(output(recipients.get(i), null, lovelace(2_000_000),
                    usdm(leaves.get(i).value().quantityOf(USDM).longValueExact())));
        }
        outputs.add(output(USDM_VAULT, continuation(leaves.stream().map(ClaimLeafV1::claimId)
                .toArray(Hash32[]::new)), lovelace(5_000_000), usdm(1)));
        outputs.add(output(USDM_SHARD, shardDatum(shard, filled(32, 3)), lovelace(1_200_000),
                shardToken(USDM_SHARD_POLICY, shard)));
        return tx(hashByte, List.of(), outputs.toArray(TransactionOutput[]::new));
    }

    static Witnesses witnessesFor(int shard, List<ClaimLeafV1> leaves) {
        Hash32[] ids = leaves.stream().map(ClaimLeafV1::claimId).toArray(Hash32[]::new);
        return spends(settleRedeemer(shard, ids), shardRedeemer(ids));
    }

    @Test
    void anObservedSettleConfirmsItsReservedClaimsAndARePaymentAfterRollbackIsANoOp() {
        Consumer consumer = new Consumer();
        ClaimLeafV1 leaf = consumer.reserve(ALICE, 100, 1_500_000, 1);
        int shard = leaf.claimId().bytes()[31] & 0x0f;

        List<L1Observation> first = observe(settleOf(0x61, shard, List.of(leaf), List.of(ALICE)),
                witnessesFor(shard, List.of(leaf)));
        assertThat(consumer.apply(first, 10)).singleElement().satisfies(result ->
                assertThat(result.claims().getFirst().outcome()).isEqualTo(PayoutConfirmation.Outcome.PAID));
        assertThat(consumer.status(leaf.claimId())).isEqualTo(SettlementRecords.ClaimStatus.PAID);
        assertThat(consumer.accounts.counters(USDM_ID).paid()).isEqualTo(BigInteger.valueOf(100));
        // Redelivery (rescan, restart) is a duplicate.
        assertThat(consumer.apply(first, 11).getFirst().disposition())
                .isEqualTo(PayoutConfirmation.Disposition.DUPLICATE);

        // L1 rolled the payout back and someone paid the anchored leaf again: one payout on L1, a no-op here.
        List<L1Observation> again = observe(settleOf(0x62, shard, List.of(leaf), List.of(ALICE)),
                witnessesFor(shard, List.of(leaf)));
        assertThat(consumer.apply(again, 12).getFirst().claims().getFirst().reason())
                .isEqualTo(PayoutConfirmation.Reason.ALREADY_PAID);
        assertThat(consumer.accounts.counters(USDM_ID).paid()).isEqualTo(BigInteger.valueOf(100));
        assertThat(consumer.confirmation.domainStatus(consumer.store)).isEmpty();
        consumer.accounts.checkInvariant(USDM_ID);
    }

    @Test
    void aThreadAuthenticatedPayoutToTheWrongRecipientHaltsButALookAlikeNeverDoes() {
        Consumer consumer = new Consumer();
        ClaimLeafV1 leaf = consumer.reserve(ALICE, 100, 0, 2);
        int shard = leaf.claimId().bytes()[31] & 0x0f;
        TransactionBody stolen = settleOf(0x63, shard, List.of(leaf), List.of(BOB));

        // The same body without its shard output is a look-alike: nothing is observed, nothing halts (I2).
        TransactionBody lookAlike = tx(0x64, List.of(), stolen.getOutputs().subList(0, 2)
                .toArray(TransactionOutput[]::new));
        assertThat(observe(lookAlike, witnessesFor(shard, List.of(leaf)))).isEmpty();

        List<PayoutConfirmation.Result> results = consumer.apply(observe(stolen, witnessesFor(shard, List.of(leaf))),
                13);
        assertThat(results.getFirst().halted()).isTrue();
        assertThat(results.getFirst().claims().getFirst().reason()).isEqualTo(PayoutConfirmation.Reason.RECIPIENT);
        assertThat(consumer.status(leaf.claimId())).isEqualTo(SettlementRecords.ClaimStatus.PAID_MISMATCH);
        assertThat(consumer.confirmation.domainStatus(consumer.store).orElseThrow().status())
                .isEqualTo(DomainStatusV1.Status.HALTED);
        assertThat(Arrays.equals(raw(BOB), leaf.recipient())).isFalse();
    }
}
