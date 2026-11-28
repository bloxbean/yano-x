package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.api.model.EvaluationResult;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The exit relayer (ADR-059 D9): eligible claims are planned per shard and the built proof {@code Exit} passes the
 * compiled vault and shard validators, on a token and the ADA vault; ineligible claims (not matured, not anchored,
 * braked by a paused domain, or already nullified on L1) are not relayed, and an aged user claim is relayed despite
 * the brake (V3 maximum claim age).
 */
class ExitRelayerTest {
    static final DomainStatusV1 ACTIVE = new DomainStatusV1(DomainStatusV1.Status.ACTIVE, 50, 0, PayoutWorld.EXIT_DELAY);

    static ClaimLeafV1 reservedAt(ClaimLeafV1 leaf, long atMs) {
        return new ClaimLeafV1(leaf.claimId(), leaf.vaultId(), leaf.kind(), leaf.value(), leaf.vaultLovelace(),
                leaf.recipient(), leaf.reservedHeight(), atMs);
    }

    static List<ExitRelayer.Candidate> reserved(List<ClaimLeafV1> leaves) {
        return leaves.stream().map(leaf -> new ExitRelayer.Candidate(leaf, ClaimStatus.RESERVED)).toList();
    }

    void exitPasses(AssetId asset, int k) throws Exception {
        PayoutWorld world = new PayoutWorld(asset);
        int shard = 2;
        List<ClaimLeafV1> claims = world.claims(shard, k);
        world.anchor(claims, 50_000);
        List<ExitRelayer.Batch> batches = ExitRelayer.plan(reserved(claims), world.pins, world.exitMode,
                world.anchorHeight, ACTIVE,
                PayoutWorld.NOW_MS, index -> world.mirrors[index]);
        assertThat(batches).singleElement().satisfies(batch -> assertThat(batch.claims()).hasSize(k));
        PayoutTxBuilder.Built built = ExitRelayer.build(world.context(shard), batches.getFirst(),
                key -> Optional.of(world.state.proof(key)), world.mirrors[shard], world.evaluator());
        List<EvaluationResult> results = world.evaluate(built);
        long mem = results.stream().mapToLong(r -> r.getExUnits().getMem().longValueExact()).sum();
        System.out.printf("EXIT| %s k=%d mem=%d bytes=%d fee=%s%n", asset.isAda() ? "ada" : "token", k, mem,
                built.cbor().length, built.fee());
        assertThat(mem).isLessThan(16_500_000L);
        assertThat(built.transaction().getBody().getRequiredSigners()).isEmpty();
        assertThat(built.transaction().getBody().getValidityStartInterval()).isEqualTo(PayoutWorld.NOW_SLOT);
    }

    @Test
    void theBuiltTokenExitPassesTheCompiledValidators() throws Exception {
        exitPasses(PayoutWorld.TOKEN, 1);
        exitPasses(PayoutWorld.TOKEN, 8);
    }

    @Test
    void theBuiltAdaExitPassesTheCompiledValidators() throws Exception {
        exitPasses(AssetId.ADA, 1);
        exitPasses(AssetId.ADA, 8);
    }

    @Test
    void ineligibleClaimsAreNotRelayed() throws Exception {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        ClaimLeafV1 immature = reservedAt(world.claim(4, 1), PayoutWorld.NOW_MS - PayoutWorld.HOUR);
        ClaimLeafV1 unanchored = world.claim(4, 9); // reserved at height 109, above the anchor
        ClaimLeafV1 paidElsewhere = world.claim(4, 3);
        ClaimLeafV1 eligible = world.claim(4, 4);
        world.anchor(List.of(immature, paidElsewhere, eligible), unanchored.reservedHeight() - 1);
        world.mirrors[4].insert(paidElsewhere.claimId());
        List<ExitRelayer.Candidate> candidates = new ArrayList<>(reserved(List.of(immature, unanchored, paidElsewhere,
                eligible)));
        candidates.add(new ExitRelayer.Candidate(world.claim(4, 5), ClaimStatus.PAID));
        List<ExitRelayer.Batch> batches = ExitRelayer.plan(candidates, world.pins, world.exitMode,
                world.anchorHeight, ACTIVE,
                PayoutWorld.NOW_MS, index -> world.mirrors[index]);
        assertThat(batches).singleElement().satisfies(batch -> assertThat(batch.claims()).containsExactly(eligible));

        // A paused domain brakes every claim, except a user claim past its maximum age (V3).
        DomainStatusV1 paused = new DomainStatusV1(DomainStatusV1.Status.PAUSED, 50, 0, PayoutWorld.EXIT_DELAY);
        ClaimLeafV1 aged = reservedAt(world.claim(4, 6),
                PayoutWorld.NOW_MS - PayoutWorld.EXIT_DELAY - PayoutWorld.MAX_PAUSE);
        assertThat(ExitRelayer.plan(reserved(List.of(eligible, aged)), world.pins, world.exitMode,
                world.anchorHeight, paused,
                PayoutWorld.NOW_MS, index -> world.mirrors[index]))
                .singleElement().satisfies(batch -> assertThat(batch.claims()).containsExactly(aged));

        // Forcing an immature claim past the relayer gets it rejected by the vault script on phase 2 (V3).
        PayoutWorld fresh = new PayoutWorld(PayoutWorld.TOKEN);
        ClaimLeafV1 young = reservedAt(fresh.claim(4, 7), PayoutWorld.NOW_MS - PayoutWorld.HOUR);
        fresh.anchor(List.of(young), 60_000);
        assertThatThrownBy(() -> ExitRelayer.build(fresh.context(4), new ExitRelayer.Batch(4, List.of(young)),
                key -> Optional.of(fresh.state.proof(key)), fresh.mirrors[4], fresh.evaluator()))
                .isInstanceOf(PayoutTxBuilder.BuildException.class).hasMessageContaining("evaluation failed");
    }

    /** Review P2-14 (deferred to M4a): this normal-mode relayer refuses once the exit-mode thread is in EXIT. */
    @Test
    void exitModeRelayingIsRefusedUntilM4a() {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        List<ClaimLeafV1> claims = world.claims(3, 1);
        world.anchor(claims, 50_000);
        ResolvedOutput exit = new ResolvedOutput(world.exitMode.outpoint(), world.exitMode.address(),
                world.exitMode.lovelace(), world.exitMode.assets(),
                Optional.of(new byte[] {(byte) 0xd8, 0x7a, (byte) 0x80}), Optional.empty());
        assertThatThrownBy(() -> ExitRelayer.plan(reserved(claims), world.pins, exit, world.anchorHeight, ACTIVE,
                PayoutWorld.NOW_MS, index -> world.mirrors[index])).hasMessage(ExitRelayer.EXIT_MODE_REFUSAL);
        PayoutTxBuilder.Context base = world.context(3);
        PayoutTxBuilder.Context context = new PayoutTxBuilder.Context(base.pins(), base.vaultUtxos(),
                base.shardInput(), base.anchor(), exit, base.scriptRefs(), base.funding(), base.validFromSlot(),
                base.ttlSlot(), base.protocolParams(), base.maxVaultInputs());
        assertThatThrownBy(() -> ExitRelayer.build(context, new ExitRelayer.Batch(3, claims),
                key -> Optional.of(world.state.proof(key)), world.mirrors[3], world.evaluator()))
                .hasMessage(ExitRelayer.EXIT_MODE_REFUSAL);
    }

    @Test
    void batchesAreCappedAtMaxExitBatchAndAProofOfAnotherLeafIsRefused() {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        List<ClaimLeafV1> claims = world.claims(1, 11);
        world.anchor(claims, 50_000);
        List<ExitRelayer.Batch> batches = ExitRelayer.plan(reserved(claims), world.pins, world.exitMode,
                world.anchorHeight, ACTIVE,
                PayoutWorld.NOW_MS, index -> world.mirrors[index]);
        assertThat(batches).extracting(batch -> batch.claims().size()).containsExactly(8, 3);
        ClaimLeafV1 first = claims.getFirst();
        StateProof other = world.state.proof(PayoutWorld.cat(PayoutWorld.CLAIM_PREFIX, claims.get(1).claimId().bytes()));
        assertThatThrownBy(() -> ExitRelayer.build(world.context(1), new ExitRelayer.Batch(1, List.of(first)),
                key -> Optional.of(other), world.mirrors[1], world.evaluator()))
                .hasMessageContaining("not of its committed leaf");
    }
}
