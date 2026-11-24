package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ADR-059 {@code Exit} (V1-V3) in NORMAL and EXIT mode, and the governed {@code SweepExit} (D8d), against a real
 * anchored state trie: forged roots, wrong keys and values, maturity, the brake and the maximum claim age.
 */
class VaultExitConformanceTest extends GatewayTestBase {

    /** An Exit of {@code claims} with the given status leaf, proved against a trie that holds them. */
    private Payout exit(AssetId asset, List<ClaimFx> claims, byte[] status) {
        Hash32 vaultId = asset.isAda() ? ADA_VAULT : TOKEN_VAULT;
        StateTrie state = stateWith(64, status, vaultId, asset, claims);
        return exit(asset, claims, state, state.root(), state);
    }

    private Payout exit(AssetId asset, List<ClaimFx> claims, StateTrie proofs, byte[] anchorRoot,
                        StateTrie statusFrom) {
        return exitWith(asset, claims, claimProofs(proofs, claims), statusFrom.proof(STATUS_KEY), anchorRoot);
    }

    private Payout exitWith(AssetId asset, List<ClaimFx> claims, List<PlutusData> proofs, PlutusData status,
                            byte[] anchorRoot) {
        Payout payout = new Payout();
        payout.asset = asset;
        payout.shard = 0;
        payout.shardTrie = new ShardTrie(0).seed(8);
        payout.claims.addAll(claims);
        payout.vaultRedeemer = exitRedeemer(claims, proofs, status, 0);
        payout.anchor = anchorDatum(2, anchorRoot, 120, MEMBERS, THRESHOLD, NOW - HOUR);
        payout.signers = 0;
        return payout;
    }

    private static List<ClaimFx> claims(AssetId asset, int count) {
        List<ClaimFx> claims = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            claims.add(userClaim(0, 20 + i, asset));
        }
        return claims;
    }

    private static byte[] active() {
        return statusLeaf(DomainStatusV1.Status.ACTIVE, NOW - 30 * DAY, EXIT_DELAY);
    }

    @Test
    void anyoneExitsAnAnchoredMaturedClaimWithNoCustodyKeys() {
        for (AssetId asset : List.of(TOKEN, AssetId.ADA)) {
            for (int count : new int[] {1, 2}) {
                Scenario scenario = exit(asset, claims(asset, count), active()).build();
                ok(vault(asset), scenario.vault());
                ok(shard(), scenario.shard());
            }
        }
    }

    @Test
    void aGovernedOperationalSweepExitsWhenActive() {
        ClaimFx sweep = new ClaimFx(shardId(0, 31), 2, 0, 3_000_000, GatewayFixtures.enterpriseKeyAddress(
                filled(0x8f, 28)), 90, NOW - 2 * DAY);
        ok(vault(TOKEN), exit(TOKEN, List.of(sweep), active()).build().vault());
    }

    // ---- V1

    @Test
    void v1ForgedRootFails() {
        List<ClaimFx> claims = claims(TOKEN, 1);
        StateTrie real = stateWith(64, active(), TOKEN_VAULT, TOKEN, claims);
        // The proofs come from a trie the anchor never signed.
        StateTrie forged = stateWith(65, active(), TOKEN_VAULT, TOKEN, claims);
        rejected(vault(TOKEN), exit(TOKEN, claims, forged, real.root(), real).build().vault());
    }

    @Test
    void v1SpentMissingOrWrongAnchorFails() {
        Payout none = exit(TOKEN, claims(TOKEN, 1), active());
        none.anchor = null;
        rejected(vault(TOKEN), none.build().vault());

        Scenario wrongIdentity = exit(TOKEN, claims(TOKEN, 1), active()).build();
        TxInInfo anchor = wrongIdentity.tx().references.stream()
                .filter(r -> r.outRef().equals(ref(0x63, 0))).findFirst().orElseThrow();
        PlutusData datum = ((OutputDatum.OutputDatumInline) anchor.resolved().datum()).datum();
        List<PlutusData> fields = new ArrayList<>(((PlutusData.ConstrData) datum).fields());
        fields.set(2, PlutusData.bytes(filled(0x34, 32)));
        wrongIdentity.tx().references.remove(anchor);
        wrongIdentity.tx().reference(anchorInput(PlutusData.constr(0, fields.toArray(new PlutusData[0]))));
        rejected(vault(TOKEN), wrongIdentity.vault());

        // Two anchor-thread references (one of them stale) are refused, not chosen between.
        Scenario two = exit(TOKEN, claims(TOKEN, 1), active()).build();
        two.tx().reference(new TxInInfo(ref(0x65, 0), anchor.resolved()));
        rejected(vault(TOKEN), two.vault());
    }

    // ---- V2

    @Test
    void v2ProofOfTheSameBytesUnderAnotherComponentKeyFails() {
        List<ClaimFx> claims = claims(TOKEN, 1);
        ClaimFx claim = claims.get(0);
        byte[] otherKey = GatewayFixtures.concat(ascii("escrow/claim/"), claim.id());
        StateTrie state = new StateTrie().fill("ap/account/", 64).put(STATUS_KEY, active())
                .put(otherKey, claim.leaf(TOKEN_VAULT, TOKEN));
        Payout payout = exitWith(TOKEN, claims, List.of(state.proof(otherKey)), state.proof(STATUS_KEY),
                state.root());
        rejected(vault(TOKEN), payout.build().vault());
        // The same proof with its key relabelled to the expected key no longer folds to the root.
        Payout relabelled = exitWith(TOKEN, claims, List.of(withKey(state.proof(otherKey), claim.key())),
                state.proof(STATUS_KEY), state.root());
        rejected(vault(TOKEN), relabelled.build().vault());
    }

    @Test
    void v2ProofOfAWrongValueFails() {
        List<ClaimFx> claims = claims(TOKEN, 1);
        StateTrie state = stateWith(64, active(), TOKEN_VAULT, TOKEN, claims);
        // The redeemer claims a larger quantity than the anchored leaf.
        ClaimFx inflated = claims.get(0).withQuantity(claims.get(0).quantity() + 1_000_000);
        rejected(vault(TOKEN), exitWith(TOKEN, List.of(inflated), claimProofs(state, claims),
                state.proof(STATUS_KEY), state.root()).build().vault());
        // A proof whose value is swapped for the inflated leaf no longer folds to the root.
        rejected(vault(TOKEN), exitWith(TOKEN, List.of(inflated), List.of(withValue(state.proof(claims.get(0).key()),
                inflated.leaf(TOKEN_VAULT, TOKEN))), state.proof(STATUS_KEY), state.root()).build().vault());
    }

    @Test
    void v2ALeafOfAnotherVaultFails() {
        List<ClaimFx> claims = claims(TOKEN, 1);
        Hash32 otherVault = GatewayIds.vaultId(DOMAIN, TOKEN, 1);
        StateTrie state = stateWith(64, active(), otherVault, TOKEN, claims);
        rejected(vault(TOKEN), exit(TOKEN, claims, state, state.root(), state).build().vault());
    }

    // ---- V3

    @Test
    void v3ImmatureClaimFailsAndTheDelayIsMillisecondExact() {
        List<ClaimFx> claims = List.of(userClaim(0, 20, TOKEN).withAtMs(NOW - EXIT_DELAY + 1));
        rejected(vault(TOKEN), exit(TOKEN, claims, active()).build().vault());
        List<ClaimFx> boundary = List.of(userClaim(0, 20, TOKEN).withAtMs(NOW - EXIT_DELAY));
        ok(vault(TOKEN), exit(TOKEN, boundary, active()).build().vault());
    }

    @Test
    void v3AnInfiniteLowerBoundNeverMatures() {
        Scenario scenario = exit(TOKEN, claims(TOKEN, 1), active()).build();
        scenario.tx().range = new org.julclang.ledger.Interval(
                new org.julclang.ledger.IntervalBound(new org.julclang.ledger.IntervalBoundType.NegInf(), true),
                new org.julclang.ledger.IntervalBound(new org.julclang.ledger.IntervalBoundType.Finite(
                        java.math.BigInteger.valueOf(NOW)), false));
        rejected(vault(TOKEN), scenario.vault());
    }

    @Test
    void v3PausedStatusBlocksAUserClaimUntilItsMaximumAge() {
        byte[] paused = statusLeaf(DomainStatusV1.Status.PAUSED, NOW - DAY, EXIT_DELAY);
        // Matured but not past exitDelay + maxPause.
        rejected(vault(TOKEN), exit(TOKEN, claims(TOKEN, 1), paused).build().vault());
        // Past its maximum age: the PAUSED leaf is still proved, its status ignored.
        ClaimFx aged = userClaim(0, 20, TOKEN).withAtMs(NOW - EXIT_DELAY - MAX_PAUSE);
        ok(vault(TOKEN), exit(TOKEN, List.of(aged), paused).build().vault());
        ClaimFx almost = userClaim(0, 20, TOKEN).withAtMs(NOW - EXIT_DELAY - MAX_PAUSE + 1);
        rejected(vault(TOKEN), exit(TOKEN, List.of(almost), paused).build().vault());
        byte[] halted = statusLeaf(DomainStatusV1.Status.HALTED, NOW - DAY, EXIT_DELAY);
        ok(vault(TOKEN), exit(TOKEN, List.of(aged), halted).build().vault());
    }

    @Test
    void v3AGovernedClaimNeedsActiveEvenPastItsMaximumAge() {
        byte[] paused = statusLeaf(DomainStatusV1.Status.PAUSED, NOW - 30 * DAY, EXIT_DELAY);
        ClaimFx sweep = new ClaimFx(shardId(0, 31), 2, 0, 3_000_000, GatewayFixtures.enterpriseKeyAddress(
                filled(0x8f, 28)), 90, NOW - EXIT_DELAY - 2 * MAX_PAUSE);
        rejected(vault(TOKEN), exit(TOKEN, List.of(sweep), paused).build().vault());
    }

    @Test
    void v3StatusLeafDelayOutsideTheScriptBoundsFails() {
        for (long delay : new long[] {DELAY_FLOOR - 1, DELAY_CEILING + 1}) {
            byte[] status = statusLeaf(DomainStatusV1.Status.ACTIVE, NOW - 30 * DAY, delay);
            ClaimFx old = userClaim(0, 20, TOKEN).withAtMs(NOW - 40 * DAY);
            rejected(vault(TOKEN), exit(TOKEN, List.of(old), status).build().vault());
        }
        byte[] floor = statusLeaf(DomainStatusV1.Status.ACTIVE, NOW - 30 * DAY, DELAY_FLOOR);
        ok(vault(TOKEN), exit(TOKEN, List.of(userClaim(0, 20, TOKEN).withAtMs(NOW - DELAY_FLOOR)), floor)
                .build().vault());
    }

    @Test
    void v3TheStatusLeafMustBeProvedAtThePinnedKey() {
        List<ClaimFx> claims = claims(TOKEN, 1);
        byte[] otherKey = ascii("escrow/domain-status");
        StateTrie state = stateWith(64, active(), TOKEN_VAULT, TOKEN, claims).put(otherKey, active());
        rejected(vault(TOKEN), exitWith(TOKEN, claims, claimProofs(state, claims), state.proof(otherKey),
                state.root()).build().vault());
    }

    @Test
    void migrateAndSweepUnattributedAreNotPaidByExit() {
        for (int kind : new int[] {1, 3}) {
            ClaimFx governed = new ClaimFx(shardId(0, 33), kind, 0, 3_000_000,
                    GatewayFixtures.enterpriseKeyAddress(filled(0x8f, 28)), 90, NOW - 2 * DAY);
            rejected(vault(TOKEN), exit(TOKEN, List.of(governed), active()).build().vault());
        }
    }

    // ---- EXIT mode (D8g §4)

    private Payout exitInExitMode(AssetId asset, List<ClaimFx> claims, StateTrie state, byte[] frozenRoot) {
        Payout payout = exit(asset, claims, state, frozenRoot, state);
        payout.mode = exitMode(frozenRoot);
        payout.anchor = null;
        return payout;
    }

    @Test
    void inExitModeAUserClaimIsPaidAgainstTheFrozenRootWithNoDelayOrStatus() {
        byte[] paused = statusLeaf(DomainStatusV1.Status.PAUSED, NOW - DAY, EXIT_DELAY);
        List<ClaimFx> fresh = List.of(userClaim(0, 20, TOKEN).withAtMs(NOW - 1));
        StateTrie state = stateWith(64, paused, TOKEN_VAULT, TOKEN, fresh);
        ok(vault(TOKEN), exitInExitMode(TOKEN, fresh, state, state.root()).build().vault());
    }

    @Test
    void inExitModeAGovernedClaimIsNeverPaid() {
        ClaimFx sweep = new ClaimFx(shardId(0, 31), 2, 0, 3_000_000, GatewayFixtures.enterpriseKeyAddress(
                filled(0x8f, 28)), 90, NOW - 30 * DAY);
        StateTrie state = stateWith(64, active(), TOKEN_VAULT, TOKEN, List.of(sweep));
        rejected(vault(TOKEN), exitInExitMode(TOKEN, List.of(sweep), state, state.root()).build().vault());
    }

    @Test
    void inExitModeAProofAgainstALaterNonFrozenRootFails() {
        List<ClaimFx> claims = claims(TOKEN, 1);
        StateTrie frozen = stateWith(64, active(), TOKEN_VAULT, TOKEN, List.of());
        StateTrie later = stateWith(64, active(), TOKEN_VAULT, TOKEN, claims);
        Payout payout = exitInExitMode(TOKEN, claims, later, frozen.root());
        // The live anchor carries the later root; exit mode ignores it.
        payout.anchor = anchorDatum(2, later.root(), 130, MEMBERS, THRESHOLD, NOW);
        rejected(vault(TOKEN), payout.build().vault());
    }

    @Test
    void settleIsRejectedInExitMode() {
        List<ClaimFx> claims = claims(TOKEN, 1);
        Payout payout = exit(TOKEN, claims, active());
        payout.mode = exitMode(filled(0x55, 32));
        payout.vaultRedeemer = settleRedeemer(claims, 0);
        payout.signers = THRESHOLD;
        rejected(vault(TOKEN), payout.build().vault());
    }

    // ---- SWEEP_UNATTRIBUTED (D8d)

    /** A governed sweep of one unaccepted outpoint carrying a foreign token. */
    private Scenario sweep(boolean bindOutpoint, boolean fullValue, byte[] status) {
        TxInInfo junk = vaultInput(ref(0x6a, 2), assetValue(TOKEN, 3_000_000, 40)
                .merge(token(filled(0x7c, 28), new byte[] {1}, 5)), PlutusData.integer(7));
        byte[] outpoint = GatewayIds.outpointCbor(Hash32.of(filled(0x6a, 32)), bindOutpoint ? 2 : 3);
        byte[] recipient = GatewayFixtures.enterpriseKeyAddress(filled(0x8e, 28));
        long sequence = 5;
        Hash32 claimId = GatewayIds.claimId(DOMAIN, TOKEN_VAULT,
                org.yanoproject.x.assets.contracts.ClaimKind.SWEEP_UNATTRIBUTED, outpoint, recipient,
                org.yanoproject.x.assets.contracts.AssetValue.single(TOKEN, java.math.BigInteger.valueOf(40)),
                java.math.BigInteger.valueOf(3_000_000), sequence);
        ClaimFx claim = new ClaimFx(claimId.bytes(), 1, 40, 3_000_000, recipient, 95, NOW - 2 * DAY);
        int shard = claimId.bytes()[31] & 0x0f;
        StateTrie state = stateWith(64, status, TOKEN_VAULT, TOKEN, List.of(claim));
        ShardTrie shardTrie = new ShardTrie(shard).seed(4);
        Inserts inserts = shardTrie.plan(List.of(claim.id()));
        PlutusData redeemer = PlutusData.constr(2, PlutusData.constr(0, claim.data(), state.proof(claim.key())),
                state.proof(STATUS_KEY), PlutusData.bytes(outpoint), PlutusData.integer(sequence),
                PlutusData.integer(shard));
        Tx tx = new Tx().spend(junk, redeemer)
                .spend(new TxInInfo(shardRef(shard), new TxOut(GatewayFixtures.scriptAddress(SHARD_HASH),
                        lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {(byte) shard}, 1)),
                        new OutputDatum.OutputDatumInline(shardDatum(shard, inserts.before())), Optional.empty())),
                        inserts.redeemer())
                .spend(keyInput(FEE_REF, 50_000_000L), null)
                .output(payTo(recipient, fullValue ? junk.resolved().value() : assetValue(TOKEN, 3_000_000, 40)))
                .output(out(GatewayFixtures.scriptAddress(SHARD_HASH),
                        lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {(byte) shard}, 1)),
                        shardDatum(shard, inserts.after())))
                .reference(anchorInput(anchorDatum(2, state.root(), 120, MEMBERS, THRESHOLD, NOW - HOUR)))
                .reference(exitModeInput(normalMode()));
        return new Scenario(tx, junk.outRef(), shardRef(shard), inserts);
    }

    @Test
    void aGovernedSweepTakesExactlyItsNamedUnacceptedOutpoint() {
        Scenario scenario = sweep(true, true, active());
        ok(vault(TOKEN), scenario.vault());
        ok(shard(), scenario.shard());
    }

    @Test
    void aSweepOfAnotherOutpointOrWithoutItsForeignTokensFails() {
        rejected(vault(TOKEN), sweep(false, true, active()).vault());
        rejected(vault(TOKEN), sweep(true, false, active()).vault());
        rejected(vault(TOKEN), sweep(true, true,
                statusLeaf(DomainStatusV1.Status.PAUSED, NOW - 30 * DAY, EXIT_DELAY)).vault());
    }

    @Test
    void aSweepIsRejectedInExitMode() {
        Scenario scenario = sweep(true, true, active());
        scenario.tx().references.removeIf(r -> r.outRef().equals(ref(0x64, 0)));
        scenario.tx().reference(exitModeInput(exitMode(filled(0x55, 32))));
        rejected(vault(TOKEN), scenario.vault());
    }
}
