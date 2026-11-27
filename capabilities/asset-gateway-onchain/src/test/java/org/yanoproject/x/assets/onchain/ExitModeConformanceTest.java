package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;

import java.util.Optional;

/**
 * ADR-059 D8g §3: the {@code ExitModeV1} thread policy and validator. {@code EnterExitMode} is permissionless and
 * terminal; it needs one of the three triggers, and freezes the referenced v2 anchor's root, height and time.
 */
class ExitModeConformanceTest extends GatewayTestBase {
    private static final byte[] REQUEST_ID = GatewayIds.forcedRequestId(Hash32.of(filled(0x77, 32)), 0).bytes();

    private static TxInInfo thread(PlutusData datum) {
        return new TxInInfo(ref(0x64, 0), new TxOut(GatewayFixtures.scriptAddress(EXIT_HASH),
                lovelace(2_000_000).merge(token(EXIT_POLICY, new byte[0], 1)),
                new OutputDatum.OutputDatumInline(datum), Optional.empty()));
    }

    private static PlutusData frozen(byte[] root, long height, long atMs) {
        return PlutusData.constr(1, PlutusData.bytes(root), PlutusData.integer(height), PlutusData.integer(atMs));
    }

    /** EnterExitMode with trigger {@code redeemer}, the anchor at {@code root} updated at {@code updatedAtMs}. */
    private static Tx enter(PlutusData redeemer, byte[] root, long updatedAtMs, long lowerBound) {
        Tx tx = new Tx();
        tx.spend(thread(normalMode()), redeemer);
        tx.spend(keyInput(FEE_REF, 5_000_000L), null);
        tx.output(out(GatewayFixtures.scriptAddress(EXIT_HASH),
                lovelace(2_000_000).merge(token(EXIT_POLICY, new byte[0], 1)), frozen(root, 120, updatedAtMs)));
        tx.reference(anchorInput(anchorDatum(2, root, 120, MEMBERS, THRESHOLD, updatedAtMs)));
        tx.range = range(lowerBound, lowerBound + 600_000);
        return tx;
    }

    private static PlutusData stale() {
        return PlutusData.constr(0);
    }

    // ---- (a) stale anchor

    @Test
    void aStaleAnchorEntersExitModeAndAFreshOneDoesNot() {
        long updated = NOW - HEARTBEAT;
        ok(exitModeValidator(), enter(stale(), filled(0x55, 32), updated, NOW).spending(ref(0x64, 0)));
        rejected(exitModeValidator(), enter(stale(), filled(0x55, 32), updated + 1, NOW).spending(ref(0x64, 0)));
    }

    @Test
    void theContinuationFreezesExactlyTheAnchorsRootHeightAndTime() {
        Tx wrongRoot = enter(stale(), filled(0x55, 32), NOW - HEARTBEAT, NOW);
        wrongRoot.outputs.set(0, out(GatewayFixtures.scriptAddress(EXIT_HASH),
                lovelace(2_000_000).merge(token(EXIT_POLICY, new byte[0], 1)),
                frozen(filled(0x56, 32), 120, NOW - HEARTBEAT)));
        rejected(exitModeValidator(), wrongRoot.spending(ref(0x64, 0)));

        Tx tokenless = enter(stale(), filled(0x55, 32), NOW - HEARTBEAT, NOW);
        tokenless.outputs.set(0, out(GatewayFixtures.scriptAddress(EXIT_HASH), lovelace(2_000_000),
                frozen(filled(0x55, 32), 120, NOW - HEARTBEAT)));
        rejected(exitModeValidator(), tokenless.spending(ref(0x64, 0)));

        Tx staked = enter(stale(), filled(0x55, 32), NOW - HEARTBEAT, NOW);
        TxOut next = staked.outputs.get(0);
        staked.outputs.set(0, new TxOut(GatewayFixtures.scriptAddressWithStake(EXIT_HASH, filled(0x99, 28)),
                next.value(), next.datum(), Optional.empty()));
        rejected(exitModeValidator(), staked.spending(ref(0x64, 0)));
    }

    /** P3-13: the EXIT continuation holds only lovelace and the thread token, and no reference script. */
    @Test
    void theContinuationHoldsOnlyTheThreadTokenAndNoReferenceScript() {
        Tx extra = enter(stale(), filled(0x55, 32), NOW - HEARTBEAT, NOW);
        TxOut next = extra.outputs.get(0);
        extra.outputs.set(0, new TxOut(next.address(), next.value().merge(token(filled(0x7c, 28), new byte[] {1}, 1)),
                next.datum(), Optional.empty()));
        rejected(exitModeValidator(), extra.spending(ref(0x64, 0)));

        Tx script = enter(stale(), filled(0x55, 32), NOW - HEARTBEAT, NOW);
        script.outputs.set(0, new TxOut(next.address(), next.value(), next.datum(),
                Optional.of(new org.julclang.ledger.ScriptHash(filled(0x5b, 28)))));
        rejected(exitModeValidator(), script.spending(ref(0x64, 0)));
    }

    @Test
    void exitModeIsTerminalAndNeedsTheV2Anchor() {
        Tx again = enter(stale(), filled(0x55, 32), NOW - HEARTBEAT, NOW);
        again.inputs.set(0, new Spent(thread(frozen(filled(0x55, 32), 120, NOW - HEARTBEAT)), stale()));
        rejected(exitModeValidator(), again.spending(ref(0x64, 0)));

        Tx v1 = enter(stale(), filled(0x55, 32), NOW - HEARTBEAT, NOW);
        v1.references.clear();
        v1.reference(anchorInput(anchorDatum(1, filled(0x55, 32), 120, MEMBERS, THRESHOLD, 0)));
        rejected(exitModeValidator(), v1.spending(ref(0x64, 0)));
    }

    @Test
    void timeoutsOutsideTheirBoundsDisableEveryTrigger() {
        Program shortHeartbeat = program(ExitModeThreadValidator.class, PlutusData.bytes(EXIT_POLICY),
                PlutusData.bytes(ANCHOR_POLICY), PlutusData.bytes(ANCHOR_NAME), PlutusData.bytes(ANCHOR_HASH),
                PlutusData.bytes(GENESIS), PlutusData.bytes(APPLICATION), PlutusData.bytes(PROFILE),
                PlutusData.bytes(FINGERPRINT), PlutusData.bytes(INBOX_HASH), PlutusData.integer(HOUR),
                PlutusData.integer(FORCED_DEADLINE), PlutusData.integer(MAX_PAUSE), PlutusData.bytes(FORCED_PREFIX),
                PlutusData.bytes(STATUS_KEY));
        rejected(shortHeartbeat, enter(stale(), filled(0x55, 32), NOW - 30 * DAY, NOW).spending(ref(0x64, 0)));
    }

    // ---- (b) censored request

    private static TxInInfo request(long postedAtMs, byte[] address) {
        PlutusData datum = PlutusData.constr(0, PlutusData.bytes(filled(0xa1, 28)),
                PlutusData.bytes(TOKEN_VAULT.bytes()), PlutusData.constr(1),
                PlutusData.bytes(GatewayFixtures.enterpriseKeyAddress(filled(0xa2, 28))),
                PlutusData.bytes(filled(0xa3, 28)), PlutusData.integer(postedAtMs));
        return new TxInInfo(ref(0x78, 0), new TxOut(GatewayFixtures.scriptAddress(address),
                lovelace(2_000_000).merge(token(INBOX_HASH, REQUEST_ID, 1)), new OutputDatum.OutputDatumInline(datum),
                Optional.empty()));
    }

    private static Tx censored(StateTrie state, long posted, long updatedAtMs, long lowerBound, byte[] inbox) {
        PlutusData redeemer = PlutusData.constr(1, PlutusData.bytes(REQUEST_ID),
                state.absence(GatewayFixtures.concat(FORCED_PREFIX, REQUEST_ID)));
        Tx tx = enter(redeemer, state.root(), updatedAtMs, lowerBound);
        tx.reference(request(posted, inbox));
        return tx;
    }

    @Test
    void aRequestUnprocessedPastItsDeadlineEntersExitModeWhileTheAnchorIsFresh() {
        StateTrie state = new StateTrie().fill("ap/account/", 64);
        long posted = NOW - FORCED_DEADLINE - HOUR;
        // The anchor is fresh (no stale trigger) and was updated after the deadline.
        ok(exitModeValidator(), censored(state, posted, NOW - HOUR, NOW, INBOX_HASH).spending(ref(0x64, 0)));
    }

    @Test
    void aProcessedRequestCannotEnterExitMode() {
        StateTrie processed = new StateTrie().fill("ap/account/", 64)
                .put(GatewayFixtures.concat(FORCED_PREFIX, REQUEST_ID), new byte[] {(byte) 0x82, 0, 0});
        StateTrie without = new StateTrie().fill("ap/account/", 64);
        long posted = NOW - FORCED_DEADLINE - HOUR;
        Tx tx = censored(without, posted, NOW - HOUR, NOW, INBOX_HASH);
        // The anchor carries the root that holds the record; the absence proof is from a root without it.
        tx.references.removeIf(r -> r.outRef().equals(ref(0x63, 0)));
        tx.reference(anchorInput(anchorDatum(2, processed.root(), 120, MEMBERS, THRESHOLD, NOW - HOUR)));
        tx.outputs.set(0, out(GatewayFixtures.scriptAddress(EXIT_HASH),
                lovelace(2_000_000).merge(token(EXIT_POLICY, new byte[0], 1)),
                frozen(processed.root(), 120, NOW - HOUR)));
        rejected(exitModeValidator(), tx.spending(ref(0x64, 0)));
    }

    /**
     * P0-1: a processed request's own record leaf, named through a conflicting key hash that differs from the forced
     * key's hash in one nibble above the leaf, is no absence proof, wherever the flipped nibble is.
     */
    @Test
    void anAbsenceProofOfAProcessedRequestCannotEnterExitMode() {
        byte[] key = GatewayFixtures.concat(FORCED_PREFIX, REQUEST_ID);
        StateTrie processed = new StateTrie().fill("ap/account/", 64).put(key, new byte[] {(byte) 0x82, 0, 0});
        int cursor = (int) leafCursor(processed.inclusion(key).leafSuffix());
        long posted = NOW - FORCED_DEADLINE - HOUR;
        for (int flip : new int[] {0, cursor / 2, cursor - 1}) {
            Tx tx = enter(PlutusData.constr(1, PlutusData.bytes(REQUEST_ID), processed.absenceOfPresentKey(key, flip)),
                    processed.root(), NOW - HOUR, NOW);
            tx.reference(request(posted, INBOX_HASH));
            rejected(exitModeValidator(), tx.spending(ref(0x64, 0)));
        }
    }

    /** P1-2: a censored request whose key leaves a branch's compressed prefix still triggers exit mode. */
    @Test
    void aCensoredRequestWhoseKeyLeavesABranchPrefixEntersExitMode() {
        StateTrie state = branchPrefixTrie(GatewayFixtures.concat(FORCED_PREFIX, REQUEST_ID));
        ok(exitModeValidator(), censored(state, NOW - FORCED_DEADLINE - HOUR, NOW - HOUR, NOW, INBOX_HASH)
                .spending(ref(0x64, 0)));
    }

    @Test
    void theCensorshipTriggerWaitsForTheDeadlineInL1TimeAndInAnchorTime() {
        StateTrie state = new StateTrie().fill("ap/account/", 64);
        long posted = NOW - FORCED_DEADLINE;
        ok(exitModeValidator(), censored(state, posted, NOW, NOW, INBOX_HASH).spending(ref(0x64, 0)));
        // One millisecond early in L1 time (the anchor is past the deadline).
        rejected(exitModeValidator(), censored(state, posted, NOW + HOUR, NOW - 1, INBOX_HASH)
                .spending(ref(0x64, 0)));
        // The anchor has not advanced past the deadline: the ledger had no chance to record the request.
        rejected(exitModeValidator(), censored(state, posted, posted + FORCED_DEADLINE - 1, NOW, INBOX_HASH)
                .spending(ref(0x64, 0)));
        // A look-alike request at another address.
        rejected(exitModeValidator(), censored(state, posted, NOW, NOW, OTHER_SCRIPT).spending(ref(0x64, 0)));
    }

    // ---- (c) prolonged pause

    private static Tx paused(DomainStatusV1.Status status, long sinceMs) {
        StateTrie state = new StateTrie().fill("ap/account/", 64).put(STATUS_KEY,
                statusLeaf(status, sinceMs, EXIT_DELAY));
        return enter(PlutusData.constr(2, state.proof(STATUS_KEY)), state.root(), NOW - HOUR, NOW);
    }

    @Test
    void aPauseLongerThanMaxPauseEntersExitMode() {
        ok(exitModeValidator(), paused(DomainStatusV1.Status.PAUSED, NOW - MAX_PAUSE).spending(ref(0x64, 0)));
        ok(exitModeValidator(), paused(DomainStatusV1.Status.HALTED, NOW - MAX_PAUSE).spending(ref(0x64, 0)));
        rejected(exitModeValidator(), paused(DomainStatusV1.Status.PAUSED, NOW - MAX_PAUSE + 1)
                .spending(ref(0x64, 0)));
        rejected(exitModeValidator(), paused(DomainStatusV1.Status.ACTIVE, NOW - 30 * DAY).spending(ref(0x64, 0)));
    }

    // ---- ExitModeThreadPolicy

    private static Program threadPolicy() {
        return program(ExitModeThreadPolicy.class, PlutusData.bytes(filled(0x6d, 32)), PlutusData.integer(0));
    }

    private static Tx mintThread(Value mint, boolean seed) {
        Tx tx = new Tx();
        tx.spend(keyInput(seed ? ref(0x6d, 0) : ref(0x6d, 1), 10_000_000L), null);
        tx.mint = mint;
        tx.output(out(GatewayFixtures.scriptAddress(EXIT_HASH), lovelace(2_000_000).merge(mint), normalMode()));
        return tx;
    }

    @Test
    void theThreadPolicyMintsOneEmptyNamedTokenOnce() {
        Value one = token(EXIT_POLICY, new byte[0], 1);
        ok(threadPolicy(), mintThread(one, true).minting(EXIT_POLICY, PlutusData.UNIT));
        rejected(threadPolicy(), mintThread(one, false).minting(EXIT_POLICY, PlutusData.UNIT));
        rejected(threadPolicy(), mintThread(token(EXIT_POLICY, new byte[0], 2), true)
                .minting(EXIT_POLICY, PlutusData.UNIT));
        rejected(threadPolicy(), mintThread(token(EXIT_POLICY, new byte[] {1}, 1), true)
                .minting(EXIT_POLICY, PlutusData.UNIT));
        rejected(threadPolicy(), mintThread(one.merge(token(EXIT_POLICY, new byte[] {1}, 1)), true)
                .minting(EXIT_POLICY, PlutusData.UNIT));
        rejected(threadPolicy(), mintThread(token(EXIT_POLICY, new byte[0], -1), true)
                .minting(EXIT_POLICY, PlutusData.UNIT));
    }

    /** P3-14: the minted thread starts NORMAL. */
    @Test
    void theThreadIsMintedIntoANormalDatum() {
        Value one = token(EXIT_POLICY, new byte[0], 1);
        for (PlutusData datum : new PlutusData[] {exitMode(filled(0x55, 32)), PlutusData.constr(0,
                PlutusData.integer(1))}) {
            Tx tx = mintThread(one, true);
            tx.outputs.set(0, out(GatewayFixtures.scriptAddress(EXIT_HASH), lovelace(2_000_000).merge(one), datum));
            rejected(threadPolicy(), tx.minting(EXIT_POLICY, PlutusData.UNIT));
        }
        Tx none = mintThread(one, true);
        none.outputs.set(0, out(GatewayFixtures.scriptAddress(EXIT_HASH), lovelace(2_000_000).merge(one), null));
        rejected(threadPolicy(), none.minting(EXIT_POLICY, PlutusData.UNIT));
    }
}
