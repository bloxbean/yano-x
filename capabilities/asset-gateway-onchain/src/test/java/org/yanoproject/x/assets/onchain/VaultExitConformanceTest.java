package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SweepCommitment;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** P3-12: the profile's exit-delay floor and ceiling must themselves lie within [6 h, 30 d]. */
    @Test
    void v3ExitDelayBoundsOutsideSixHoursToThirtyDaysDisableExit() {
        List<ClaimFx> claims = claims(TOKEN, 1);
        Scenario scenario = exit(TOKEN, claims, active()).build();
        ok(vault(TOKEN), scenario.vault());
        PlutusData[] lowFloor = vaultParams(TOKEN);
        lowFloor[22] = PlutusData.integer(6 * HOUR - 1);
        rejected(program(AssetVaultValidator.class, lowFloor), scenario.vault());
        PlutusData[] highCeiling = vaultParams(TOKEN);
        highCeiling[23] = PlutusData.integer(30 * DAY + 1);
        rejected(program(AssetVaultValidator.class, highCeiling), scenario.vault());
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

    private static final AssetId FOREIGN = AssetId.of(filled(0x7c, 28), new byte[] {1});
    private static final byte[] SWEEP_RECIPIENT = GatewayFixtures.enterpriseKeyAddress(filled(0x8e, 28));
    private static final TxOutRef SWEPT = ref(0x6a, 2);

    /** The unattributed output's value: lovelace, some of the vault asset (token vault) and a foreign token. */
    private static Value sweptValue(AssetId asset) {
        return assetValue(asset, 3_000_000, asset.isAda() ? 0 : 40).merge(token(FOREIGN.policyId(),
                FOREIGN.assetName(), 5));
    }

    /** The canonical commitment of {@link #sweptValue}; {@code withForeign = false} leaves the foreign token out. */
    private static SweepCommitment commitment(AssetId asset, TxOutRef outpoint, boolean withForeign) {
        Map<AssetId, BigInteger> natives = new HashMap<>();
        if (!asset.isAda()) {
            natives.put(asset, BigInteger.valueOf(40));
        }
        if (withForeign) {
            natives.put(FOREIGN, BigInteger.valueOf(5));
        }
        return SweepCommitment.of(asset, Hash32.of(outpoint.txId().hash()), outpoint.index().longValue(),
                BigInteger.valueOf(3_000_000), natives);
    }

    /** The {@code SweepExit} redeemer for {@code commitment}, with its leaf proved in {@code state}. */
    private static PlutusData sweepRedeemer(SweepCommitment commitment, Hash32 claimId, StateTrie state, int shard) {
        List<PlutusData> assets = new ArrayList<>();
        commitment.value().entries().forEach((asset, quantity) -> assets.add(PlutusData.constr(0,
                PlutusData.bytes(asset.policyId()), PlutusData.bytes(asset.assetName()), PlutusData.integer(quantity))));
        return PlutusData.constr(2, PlutusData.bytes(claimId.bytes()), PlutusData.bytes(SWEEP_RECIPIENT),
                PlutusData.integer(95), PlutusData.integer(NOW - 2 * DAY),
                PlutusData.list(assets.toArray(new PlutusData[0])),
                state.proof(GatewayFixtures.concat(CLAIM_PREFIX, claimId.bytes())), state.proof(STATUS_KEY),
                PlutusData.integer(5), PlutusData.integer(shard));
    }

    /** A governed sweep of {@code swept} under {@code commitment} (anchored), paying {@code paid}. */
    private Scenario sweep(AssetId asset, TxInInfo swept, SweepCommitment commitment, byte[] status, Value paid,
                           Tx tx) {
        Hash32 vault = asset.isAda() ? ADA_VAULT : TOKEN_VAULT;
        Hash32 claimId = commitment.claimId(DOMAIN, vault, SWEEP_RECIPIENT, 5);
        int shard = claimId.bytes()[31] & 0x0f;
        StateTrie state = new StateTrie().fill("ap/account/", 64).put(STATUS_KEY, status)
                .put(GatewayFixtures.concat(CLAIM_PREFIX, claimId.bytes()),
                        commitment.leaf(claimId, vault, SWEEP_RECIPIENT, 95, NOW - 2 * DAY).encode());
        Inserts inserts = new ShardTrie(shard).seed(4).plan(List.of(claimId.bytes()));
        tx.spend(swept, sweepRedeemer(commitment, claimId, state, shard))
                .spend(new TxInInfo(shardRef(shard), new TxOut(GatewayFixtures.scriptAddress(SHARD_HASH),
                        lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {(byte) shard}, 1)),
                        new OutputDatum.OutputDatumInline(shardDatum(shard, inserts.before())), Optional.empty())),
                        inserts.redeemer())
                .spend(keyInput(FEE_REF, 50_000_000L), null)
                .output(out(GatewayFixtures.ledgerAddress(SWEEP_RECIPIENT), paid,
                        PlutusData.constr(4, PlutusData.bytes(claimId.bytes()))))
                .output(out(GatewayFixtures.scriptAddress(SHARD_HASH),
                        lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {(byte) shard}, 1)),
                        shardDatum(shard, inserts.after())))
                .reference(anchorInput(anchorDatum(2, state.root(), 120, MEMBERS, THRESHOLD, NOW - HOUR)))
                .reference(exitModeInput(normalMode()));
        return new Scenario(tx, swept.outRef(), shardRef(shard), inserts);
    }

    /** The default sweep: an unaccepted outpoint (a junk integer datum) with a foreign token, fully committed. */
    private Scenario sweep(AssetId asset) {
        TxInInfo junk = vaultInput(SWEPT, sweptValue(asset), PlutusData.integer(7));
        return sweep(asset, junk, commitment(asset, SWEPT, true), active(), sweptValue(asset), new Tx());
    }

    @Test
    void aGovernedSweepTakesExactlyItsNamedUnacceptedOutpointWithItsFullValue() {
        for (AssetId asset : List.of(TOKEN, AssetId.ADA)) {
            Scenario scenario = sweep(asset);
            ok(vault(asset), scenario.vault());
            ok(shard(), scenario.shard());
        }
    }

    @Test
    void theOnChainCommitmentIsTheJavaSweepCommitment() {
        // A leaf committing only the vault asset (no foreign token) is not the swept value.
        TxInInfo junk = vaultInput(SWEPT, sweptValue(TOKEN), PlutusData.integer(7));
        rejected(vault(TOKEN), sweep(TOKEN, junk, commitment(TOKEN, SWEPT, false), active(), sweptValue(TOKEN),
                new Tx()).vault());
        // A commitment of another outpoint.
        rejected(vault(TOKEN), sweep(TOKEN, junk, commitment(TOKEN, ref(0x6a, 3), true), active(),
                sweptValue(TOKEN), new Tx()).vault());
    }

    @Test
    void aSweepPaysTheFullValueWithItsSweepPaidDatum() {
        TxInInfo junk = vaultInput(SWEPT, sweptValue(TOKEN), PlutusData.integer(7));
        // Without its foreign token the payout is short.
        rejected(vault(TOKEN), sweep(TOKEN, junk, commitment(TOKEN, SWEPT, true), active(),
                assetValue(TOKEN, 3_000_000, 40), new Tx()).vault());
        // The payout must name the paid claim: no datum, another id.
        Scenario noDatum = sweep(TOKEN);
        TxOut payout = noDatum.tx().outputs.get(0);
        noDatum.tx().outputs.set(0, new TxOut(payout.address(), payout.value(), new OutputDatum.NoOutputDatum(),
                Optional.empty()));
        rejected(vault(TOKEN), noDatum.vault());
        Scenario otherId = sweep(TOKEN);
        otherId.tx().outputs.set(0, out(payout.address(), payout.value(),
                PlutusData.constr(4, PlutusData.bytes(filled(0x31, 32)))));
        rejected(vault(TOKEN), otherId.vault());
    }

    @Test
    void outputsWithNoDatumOrOnlyADatumHashAreSweepable() {
        TxInInfo datumless = datumlessVaultInput(SWEPT, sweptValue(TOKEN));
        Scenario none = sweep(TOKEN, datumless, commitment(TOKEN, SWEPT, true), active(), sweptValue(TOKEN),
                new Tx());
        ok(vault(TOKEN), none.vault());
        ok(shard(), none.shard());

        Tx tx = new Tx();
        TxInInfo hashed = datumHashVaultInput(tx, SWEPT, sweptValue(TOKEN), continuationDatum(List.of()));
        ok(vault(TOKEN), sweep(TOKEN, hashed, commitment(TOKEN, SWEPT, true), active(), sweptValue(TOKEN), tx)
                .vault());
    }

    @Test
    void acceptedCleanCustodyIsNeverSwept() {
        Value clean = assetValue(TOKEN, 3_000_000, 40);
        Map<AssetId, BigInteger> natives = Map.of(TOKEN, BigInteger.valueOf(40));
        SweepCommitment cleanCommitment = SweepCommitment.of(TOKEN, Hash32.of(SWEPT.txId().hash()), 2,
                BigInteger.valueOf(3_000_000), natives);
        // Control: the same clean value under a junk datum is unattributed, and sweeps.
        ok(vault(TOKEN), sweep(TOKEN, vaultInput(SWEPT, clean, PlutusData.integer(7)), cleanCommitment, active(),
                clean, new Tx()).vault());
        for (PlutusData accepted : List.of(continuationDatum(List.of()), continuationDatum(List.of(shardId(1, 1))),
                floorDatum())) {
            TxInInfo custody = vaultInput(SWEPT, clean, accepted);
            rejected(vault(TOKEN), sweep(TOKEN, custody, cleanCommitment, active(), clean, new Tx()).vault());
        }
        // The same datum on a value with a foreign token is not accepted custody, so it can be swept.
        TxInInfo dirty = vaultInput(SWEPT, sweptValue(TOKEN), continuationDatum(List.of()));
        ok(vault(TOKEN), sweep(TOKEN, dirty, commitment(TOKEN, SWEPT, true), active(), sweptValue(TOKEN),
                new Tx()).vault());
    }

    @Test
    void aSweepNeedsActiveAndNormalMode() {
        TxInInfo junk = vaultInput(SWEPT, sweptValue(TOKEN), PlutusData.integer(7));
        rejected(vault(TOKEN), sweep(TOKEN, junk, commitment(TOKEN, SWEPT, true),
                statusLeaf(DomainStatusV1.Status.PAUSED, NOW - 30 * DAY, EXIT_DELAY), sweptValue(TOKEN), new Tx())
                .vault());
        Scenario scenario = sweep(TOKEN);
        scenario.tx().references.removeIf(r -> r.outRef().equals(ref(0x64, 0)));
        scenario.tx().reference(exitModeInput(exitMode(filled(0x55, 32))));
        rejected(vault(TOKEN), scenario.vault());
    }
}
