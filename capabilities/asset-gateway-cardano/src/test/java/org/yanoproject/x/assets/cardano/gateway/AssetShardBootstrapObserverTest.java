package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.ShardBootstrapObservationV1;
import org.yanoproject.x.assets.contracts.ShardBootstrapObservationV1.Reason;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.x.assets.cardano.gateway.SettlementFixtures.*;

/**
 * ADR-059 D8c and D8h §3 fixtures for {@code asset-shard-bootstrap-v1}: the one authentic bootstrap mint is verified
 * when all 16 tokens sit alone at the pinned shard address with the empty-root datum, and is reported rejected (never
 * silently dropped) when the policy's one unpinnable rule, the address, or any other rule is broken.
 */
class AssetShardBootstrapObserverTest {
    static final String OBSERVER = "shards";
    final AssetShardBootstrapObserver observer = new AssetShardBootstrapObserver(OBSERVER, shardSettings());

    static List<Amount> mint() {
        List<Amount> mint = new ArrayList<>();
        for (int i = 0; i < 16; i++) mint.add(shardToken(USDM_SHARD_POLICY, i));
        return mint;
    }

    /** Output 0 is change; outputs 1..16 are shards 15..0 (any order), each edited by {@code edit}. */
    static TransactionBody bootstrap(int hashByte, List<Amount> mint, UnaryOperator<TransactionOutput> edit) {
        List<TransactionOutput> outputs = new ArrayList<>();
        outputs.add(output(ALICE, null, lovelace(50_000_000)));
        for (int i = 15; i >= 0; i--) {
            TransactionOutput shard = output(USDM_SHARD, shardDatum(i, new byte[32]), lovelace(1_200_000),
                    shardToken(USDM_SHARD_POLICY, i));
            outputs.add(i == 5 ? edit.apply(shard) : shard);
        }
        return tx(hashByte, mint, outputs.toArray(TransactionOutput[]::new));
    }

    ShardBootstrapObservationV1 observeOne(TransactionBody tx) {
        Block block = block(List.of(), List.of(tx), List.of(Witnesses.builder().build()));
        List<L1Observation> observations = observer.observe(SLOT, BLOCK_HASH, block);
        assertThat(observations).singleElement().satisfies(observation ->
                assertThat(observation.eventOrdinal()).isEqualTo(1)); // the USDM vault's index
        return ShardBootstrapObservationV1.decode(observations.getFirst().claim());
    }

    @Test
    void anAuthenticBootstrapIsVerifiedWithItsSixteenShardOutputs() {
        ShardBootstrapObservationV1 claim = observeOne(bootstrap(0x91, mint(), UnaryOperator.identity()));
        assertThat(claim.status()).isEqualTo(ShardBootstrapObservationV1.Status.VERIFIED);
        assertThat(claim.vaultId()).isEqualTo(USDM_ID);
        assertThat(claim.domainId()).isEqualTo(DOMAIN);
        // Shard i sits at output 16 - i.
        assertThat(claim.shardOutputs().getFirst()).isEqualTo(16L);
        assertThat(claim.shardOutputs().get(15)).isEqualTo(1L);
    }

    @Test
    void aBootstrapThatBreaksARuleIsReportedRejected() {
        assertThat(observeOne(bootstrap(0x92, mint(), shard -> output(BOB, shardDatum(5, new byte[32]),
                lovelace(1_200_000), shardToken(USDM_SHARD_POLICY, 5)))).reason()).isEqualTo(Reason.WRONG_ADDRESS);
        assertThat(observeOne(bootstrap(0x93, mint(), shard -> output(USDM_SHARD, shardDatum(5, filled(32, 1)),
                lovelace(1_200_000), shardToken(USDM_SHARD_POLICY, 5)))).reason()).isEqualTo(Reason.BAD_DATUM);
        assertThat(observeOne(bootstrap(0x94, mint(), shard -> output(USDM_SHARD, shardDatum(6, new byte[32]),
                lovelace(1_200_000), shardToken(USDM_SHARD_POLICY, 5)))).reason()).isEqualTo(Reason.BAD_DATUM);
        assertThat(observeOne(bootstrap(0x95, mint(), shard -> output(USDM_SHARD, null, lovelace(1_200_000),
                shardToken(USDM_SHARD_POLICY, 5)))).reason()).isEqualTo(Reason.BAD_DATUM);
        assertThat(observeOne(bootstrap(0x96, mint(), shard -> output(USDM_SHARD, shardDatum(5, new byte[32]),
                lovelace(1_200_000), shardToken(USDM_SHARD_POLICY, 5), shardToken(USDM_SHARD_POLICY, 4))))
                .reason()).isEqualTo(Reason.NOT_ONE_TOKEN);
        assertThat(observeOne(bootstrap(0x97, mint(), shard -> output(USDM_SHARD, shardDatum(5, new byte[32]),
                lovelace(1_200_000)))).reason()).isEqualTo(Reason.MISSING_SHARD);
        List<Amount> fifteen = new ArrayList<>(mint());
        fifteen.removeLast();
        assertThat(observeOne(bootstrap(0x98, fifteen, UnaryOperator.identity())).reason())
                .isEqualTo(Reason.MINT_NOT_EXACT);
        assertThat(observeOne(bootstrap(0x99, List.of(token(USDM_SHARD_POLICY, new byte[] {0x10}, 1)),
                UnaryOperator.identity())).status()).isEqualTo(ShardBootstrapObservationV1.Status.REJECTED);
    }

    /** Review P1 (foreign asset in a shard): a shard output carrying another token besides its thread token. */
    @Test
    void aShardOutputHoldingAForeignTokenIsRejected() {
        ShardBootstrapObservationV1 claim = observeOne(bootstrap(0x9d, mint(), shard -> output(USDM_SHARD,
                shardDatum(5, new byte[32]), lovelace(1_200_000), shardToken(USDM_SHARD_POLICY, 5),
                token("ab".repeat(28), new byte[] {1}, 1))));
        assertThat(claim.status()).isEqualTo(ShardBootstrapObservationV1.Status.REJECTED);
        assertThat(claim.reason()).isEqualTo(Reason.NOT_ONE_TOKEN);
    }

    @Test
    void transactionsThatDoNotMintThePolicyAreNotBootstraps() {
        TransactionBody other = bootstrap(0x9a, List.of(token("ab".repeat(28), new byte[] {1}, 1)),
                UnaryOperator.identity());
        TransactionBody none = bootstrap(0x9b, List.of(), UnaryOperator.identity());
        Block block = block(List.of(), List.of(other, none), List.of(Witnesses.builder().build(),
                Witnesses.builder().build()));
        assertThat(observer.observe(SLOT, BLOCK_HASH, block)).isEmpty();

        Block invalid = block(List.of(0), List.of(bootstrap(0x9c, mint(), UnaryOperator.identity())),
                List.of(Witnesses.builder().build()));
        assertThat(observer.observe(SLOT, BLOCK_HASH, invalid)).isEmpty();
    }
}
