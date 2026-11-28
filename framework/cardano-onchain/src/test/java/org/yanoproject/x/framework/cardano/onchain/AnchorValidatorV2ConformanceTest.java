package org.yanoproject.x.framework.cardano.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.Interval;
import org.julclang.ledger.IntervalBound;
import org.julclang.ledger.IntervalBoundType;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.stdlib.Builtins;
import org.julclang.testkit.ContractTest;
import org.julclang.testkit.ScriptContextTestBuilder;
import org.julclang.testkit.TestDataBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Conformance vectors for the anchor v2 spending validator (ADR-059 framework
 * addition 5, D8g §1; ABI: framework/cardano-api/src/main/cddl/appchain/anchor-v2.cddl).
 * They run the exact checked-in release artifact; the local-only source-compile drift
 * test runs the same vectors against the Julc source.
 *
 * <p>Every v2 rule has a passing and a failing vector: the pure heartbeat (rule 6),
 * {@code updatedAtMs == upperBound} and the bounded window (rule 7), plus the v1
 * rules carried over unchanged.
 */
class AnchorValidatorV2ConformanceTest extends ContractTest {

    static final long MAX_TX_CPU = 10_000_000_000L;
    static final long MAX_TX_MEM = 14_000_000L;

    static final byte[] THREAD_POLICY = fill(28, 0xAA);
    static final long MAX_WINDOW_MS = 600_000L;
    static final byte[] CHAIN_ID = fill(32, 0xC1);
    static final BigInteger LOCKED = BigInteger.valueOf(2_000_000);
    static final long T0 = 1_700_000_000_000L;
    static final long UPPER = T0 + 3_600_000L;

    static final List<byte[]> MEMBERS = List.of(memberKey(1), memberKey(2), memberKey(3));
    static final List<byte[]> MEMBERS_32 = members(32);

    static Program releaseProgram;

    @BeforeAll
    static void setUp() {
        initCrypto();
    }

    Program program() {
        if (releaseProgram == null) {
            releaseProgram = BundledAnchorArtifacts.load(
                    "META-INF/plutus/AnchorValidatorV2.plutus.json",
                    PlutusData.bytes(THREAD_POLICY),
                    PlutusData.integer(MAX_WINDOW_MS));
        }
        return releaseProgram;
    }

    // ----------------------------------------------------------------- advances

    @Test
    void advance_succeedsWithinBudget() {
        var result = evaluate(program(), ctx(advance()).buildPlutusData());
        assertSuccess(result);
        assertBudgetUnder(result, MAX_TX_CPU, MAX_TX_MEM);
        System.out.println("[" + getClass().getSimpleName() + "] advance budget: "
                + result.budgetConsumed());
    }

    @Test
    void advanceMayMoveUpdatedAtBackwardOrKeepIt() {
        // Only a heartbeat needs a strictly newer updatedAtMs; a height advance
        // is bound by rule 7 alone.
        assertSuccess(evaluate(program(), ctx(advance().updatedAtMs(T0 - 1)
                .window(T0 - 1 - 60_000, T0 - 1)).buildPlutusData()));
        assertSuccess(evaluate(program(), ctx(advance().updatedAtMs(T0)
                .window(T0 - 60_000, T0)).buildPlutusData()));
    }

    @Test
    void heightRegression_fails() {
        assertFailure(evaluate(program(), ctx(advance().nextHeight(9)).buildPlutusData()));
    }

    @Test
    void membershipRotationAdvance_succeedsSignedByOldThreshold() {
        assertSuccess(evaluate(program(), ctx(advance()
                .nextMembers(List.of(memberKey(4), memberKey(5)))
                .nextThreshold(2)).buildPlutusData()));
    }

    @Test
    void membershipRotationAdvance_signedOnlyByNewMembers_fails() {
        assertFailure(evaluate(program(), ctx(advance()
                .nextMembers(List.of(memberKey(4), memberKey(5)))
                .nextThreshold(2)
                .signers(List.of(memberKey(4), memberKey(5)))).buildPlutusData()));
    }

    @Test
    void memberProfile32Of32_succeedsWithinBudget() {
        var result = evaluate(program(), ctx(heartbeat()
                .members(MEMBERS_32).threshold(32)
                .nextMembers(MEMBERS_32).nextThreshold(32)
                .signers(MEMBERS_32)).buildPlutusData());
        assertSuccess(result);
        assertBudgetUnder(result, MAX_TX_CPU, MAX_TX_MEM);
        System.out.println("[" + getClass().getSimpleName() + "] 32-of-32 heartbeat budget: "
                + result.budgetConsumed());
    }

    // ---------------------------------------------------------- rule 6: heartbeat

    @Test
    void pureHeartbeat_succeeds() {
        var result = evaluate(program(), ctx(heartbeat()).buildPlutusData());
        assertSuccess(result);
        assertBudgetUnder(result, MAX_TX_CPU, MAX_TX_MEM);
    }

    @Test
    void heartbeatWithEqualUpdatedAt_fails() {
        assertFailure(evaluate(program(), ctx(heartbeat().currentUpdatedAtMs(UPPER))
                .buildPlutusData()));
    }

    @Test
    void heartbeatWithOlderUpdatedAt_fails() {
        assertFailure(evaluate(program(), ctx(heartbeat().currentUpdatedAtMs(UPPER + 1))
                .buildPlutusData()));
    }

    @Test
    void heartbeatChangingBlockHash_fails() {
        assertFailure(evaluate(program(), ctx(heartbeat().nextBlockHash(fill(32, 0xB9)))
                .buildPlutusData()));
    }

    @Test
    void heartbeatChangingStateRoot_fails() {
        assertFailure(evaluate(program(), ctx(heartbeat().nextStateRoot(fill(32, 0x59)))
                .buildPlutusData()));
    }

    @Test
    void heartbeatChangingMembers_fails() {
        assertFailure(evaluate(program(), ctx(heartbeat()
                .nextMembers(List.of(memberKey(1), memberKey(2), memberKey(4))))
                .buildPlutusData()));
    }

    @Test
    void heartbeatChangingThreshold_fails() {
        assertFailure(evaluate(program(), ctx(heartbeat().nextThreshold(3)).buildPlutusData()));
    }

    @Test
    void heartbeatChangingIdentity_fails() {
        assertFailure(evaluate(program(), ctx(heartbeat().nextChainId(fill(32, 0xC2)))
                .buildPlutusData()));
        assertFailure(evaluate(program(), ctx(heartbeat().nextFormatFingerprint(fill(32, 0x42)))
                .buildPlutusData()));
    }

    @Test
    void heartbeatBelowThreshold_fails() {
        assertFailure(evaluate(program(), ctx(heartbeat().signers(List.of(MEMBERS.get(0))))
                .buildPlutusData()));
    }

    // ------------------------------------------------- rule 7: validity window

    @Test
    void updatedAtNotUpperBound_fails() {
        assertFailure(evaluate(program(), ctx(advance().updatedAtMs(UPPER - 1))
                .buildPlutusData()));
        assertFailure(evaluate(program(), ctx(advance().updatedAtMs(UPPER + 1))
                .buildPlutusData()));
    }

    @Test
    void windowExactlyMax_succeeds() {
        assertSuccess(evaluate(program(), ctx(advance().window(UPPER - MAX_WINDOW_MS, UPPER))
                .buildPlutusData()));
    }

    @Test
    void windowAboveMax_fails() {
        assertFailure(evaluate(program(), ctx(advance().window(UPPER - MAX_WINDOW_MS - 1, UPPER))
                .buildPlutusData()));
        assertFailure(evaluate(program(), ctx(heartbeat().window(UPPER - MAX_WINDOW_MS - 1, UPPER))
                .buildPlutusData()));
    }

    @Test
    void infiniteLowerBound_fails() {
        assertFailure(evaluate(program(), ctx(advance().range(new Interval(
                new IntervalBound(new IntervalBoundType.NegInf(), true),
                new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(UPPER)), false))))
                .buildPlutusData()));
    }

    @Test
    void infiniteUpperBound_fails() {
        assertFailure(evaluate(program(), ctx(advance().range(new Interval(
                new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(UPPER - 1_000)), true),
                new IntervalBound(new IntervalBoundType.PosInf(), true))))
                .buildPlutusData()));
    }

    @Test
    void negativeUpdatedAt_fails() {
        assertFailure(evaluate(program(), ctx(advance().currentUpdatedAtMs(-1)).buildPlutusData()));
    }

    // ------------------------------------------------------ v1 rules carried over

    @Test
    void v1ShapedDatums_fail() {
        assertFailure(evaluate(program(), ctx(advance().version(1).nextVersion(1))
                .buildPlutusData()));
        assertFailure(evaluate(program(), ctx(advance().dropUpdatedAtFields(true))
                .buildPlutusData()));
    }

    @Test
    void versionChange_fails() {
        assertFailure(evaluate(program(), ctx(advance().nextVersion(3)).buildPlutusData()));
    }

    @Test
    void extraDatumField_fails() {
        assertFailure(evaluate(program(), ctx(advance().extraCurrentField(true)).buildPlutusData()));
        assertFailure(evaluate(program(), ctx(advance().extraNextField(true)).buildPlutusData()));
    }

    @Test
    void missingThreadToken_fails() {
        assertFailure(evaluate(program(), ctx(advance().outputCarriesToken(false)).buildPlutusData()));
    }

    @Test
    void nonInlineNextDatum_fails() {
        assertFailure(evaluate(program(), ctx(advance().inlineNextDatum(false)).buildPlutusData()));
    }

    @Test
    void drain_fails() {
        assertFailure(evaluate(program(), ctx(advance()
                .outputLovelace(LOCKED.subtract(BigInteger.ONE))).buildPlutusData()));
    }

    @Test
    void duplicateContinuingOutput_fails() {
        assertFailure(evaluate(program(), ctx(advance().duplicateContinuingOutput(true))
                .buildPlutusData()));
    }

    @Test
    void invalidSuccessorMemberProfile_fails() {
        assertFailure(evaluate(program(), ctx(advance()
                .nextMembers(List.of(memberKey(2), memberKey(1)))).buildPlutusData()));
        assertFailure(evaluate(program(), ctx(advance().nextThreshold(4)).buildPlutusData()));
    }

    @Test
    void identitySubstitution_fails() {
        assertFailure(evaluate(program(), ctx(advance()
                .nextApplicationId("other-app".getBytes(StandardCharsets.US_ASCII)))
                .buildPlutusData()));
        assertFailure(evaluate(program(), ctx(advance().nextChainGenesisId(fill(32, 0x41)))
                .buildPlutusData()));
    }

    // ----------------------------------------------------------------- fixture

    static class Vector {
        long version = 2;
        long nextVersion = 2;
        byte[] chainId = CHAIN_ID;
        byte[] nextChainId = CHAIN_ID;
        byte[] genesisId = fill(32, 0x31);
        byte[] nextGenesisId = fill(32, 0x31);
        byte[] applicationId = "ordered-log".getBytes(StandardCharsets.US_ASCII);
        byte[] nextApplicationId = applicationId;
        byte[] profileId = "mpf-blake2b256-v1".getBytes(StandardCharsets.US_ASCII);
        byte[] formatFingerprint = fill(32, 0x32);
        byte[] nextFormatFingerprint = fill(32, 0x32);
        long height = 10;
        long nextHeight = 11;
        byte[] blockHash = fill(32, 0xB0);
        byte[] nextBlockHash = fill(32, 0xB1);
        byte[] stateRoot = fill(32, 0x50);
        byte[] nextStateRoot = fill(32, 0x51);
        List<byte[]> members = MEMBERS;
        long threshold = 2;
        List<byte[]> nextMembers = MEMBERS;
        long nextThreshold = 2;
        long currentUpdatedAtMs = T0;
        long updatedAtMs = UPPER;
        Interval range = AnchorValidatorV2ConformanceTest.window(UPPER - 120_000, UPPER);
        List<byte[]> signers = List.of(MEMBERS.get(0), MEMBERS.get(1));
        boolean outputCarriesToken = true;
        boolean inlineNextDatum = true;
        boolean duplicateContinuingOutput = false;
        BigInteger outputLovelace = LOCKED;
        boolean extraCurrentField = false;
        boolean extraNextField = false;
        boolean dropUpdatedAtFields = false;

        Vector version(long v) { version = v; return this; }
        Vector nextVersion(long v) { nextVersion = v; return this; }
        Vector nextChainId(byte[] v) { nextChainId = v; return this; }
        Vector nextChainGenesisId(byte[] v) { nextGenesisId = v; return this; }
        Vector nextApplicationId(byte[] v) { nextApplicationId = v; return this; }
        Vector nextFormatFingerprint(byte[] v) { nextFormatFingerprint = v; return this; }
        Vector nextHeight(long v) { nextHeight = v; return this; }
        Vector nextBlockHash(byte[] v) { nextBlockHash = v; return this; }
        Vector nextStateRoot(byte[] v) { nextStateRoot = v; return this; }
        Vector members(List<byte[]> v) { members = v; return this; }
        Vector threshold(long v) { threshold = v; return this; }
        Vector nextMembers(List<byte[]> v) { nextMembers = v; return this; }
        Vector nextThreshold(long v) { nextThreshold = v; return this; }
        Vector currentUpdatedAtMs(long v) { currentUpdatedAtMs = v; return this; }
        Vector updatedAtMs(long v) { updatedAtMs = v; return this; }
        Vector window(long lower, long upper) { range = AnchorValidatorV2ConformanceTest.window(lower, upper); return this; }
        Vector range(Interval v) { range = v; return this; }
        Vector signers(List<byte[]> v) { signers = v; return this; }
        Vector outputCarriesToken(boolean v) { outputCarriesToken = v; return this; }
        Vector inlineNextDatum(boolean v) { inlineNextDatum = v; return this; }
        Vector duplicateContinuingOutput(boolean v) { duplicateContinuingOutput = v; return this; }
        Vector outputLovelace(BigInteger v) { outputLovelace = v; return this; }
        Vector extraCurrentField(boolean v) { extraCurrentField = v; return this; }
        Vector extraNextField(boolean v) { extraNextField = v; return this; }
        Vector dropUpdatedAtFields(boolean v) { dropUpdatedAtFields = v; return this; }
    }

    /** Passing height advance 10 -> 11, updatedAtMs = upper bound, 2-minute window. */
    static Vector advance() {
        return new Vector();
    }

    /** Passing pure heartbeat at height 10: only updatedAtMs moves forward. */
    static Vector heartbeat() {
        Vector v = new Vector();
        v.nextHeight = v.height;
        v.nextBlockHash = v.blockHash;
        v.nextStateRoot = v.stateRoot;
        return v;
    }

    /** The ledger's translation of a validity interval: inclusive lower, exclusive upper. */
    static Interval window(long lower, long upper) {
        return new Interval(
                new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(lower)), true),
                new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(upper)), false));
    }

    ScriptContextTestBuilder ctx(Vector v) {
        var scriptAddress = new Address(
                new Credential.ScriptCredential(new ScriptHash(fill(28, 0x5C))),
                Optional.empty());
        var currentDatum = datum(v.version, v.chainId, v.genesisId, v.applicationId,
                v.profileId, v.formatFingerprint, v.height, v.blockHash, v.stateRoot,
                v.members, v.threshold, v.currentUpdatedAtMs, v.extraCurrentField,
                v.dropUpdatedAtFields);
        var nextDatum = datum(v.nextVersion, v.nextChainId, v.nextGenesisId, v.nextApplicationId,
                v.profileId, v.nextFormatFingerprint, v.nextHeight, v.nextBlockHash,
                v.nextStateRoot, v.nextMembers, v.nextThreshold, v.updatedAtMs,
                v.extraNextField, v.dropUpdatedAtFields);

        var ownRef = new TxOutRef(new org.julclang.ledger.TxId(fill(32, 0x11)), BigInteger.ZERO);
        var ownInput = new TxInInfo(ownRef, new TxOut(scriptAddress, lockedValue(LOCKED, true),
                new OutputDatum.OutputDatumInline(currentDatum), Optional.empty()));
        var nextOut = new TxOut(scriptAddress, lockedValue(v.outputLovelace, v.outputCarriesToken),
                v.inlineNextDatum ? new OutputDatum.OutputDatumInline(nextDatum)
                        : new OutputDatum.NoOutputDatum(),
                Optional.empty());

        var builder = spendingContext(ownRef, currentDatum)
                .redeemer(PlutusData.integer(0))
                .input(ownInput)
                .output(nextOut)
                .validRange(v.range);
        if (v.duplicateContinuingOutput) {
            builder.output(TestDataBuilder.txOut(scriptAddress, Value.lovelace(LOCKED)));
        }
        for (byte[] signer : v.signers) {
            builder.signer(Builtins.blake2b_224(signer));
        }
        return builder;
    }

    static Value lockedValue(BigInteger lovelace, boolean withToken) {
        var value = Value.lovelace(lovelace);
        if (withToken) {
            value = value.merge(Value.singleton(
                    new PolicyId(THREAD_POLICY), new TokenName(new byte[0]), BigInteger.ONE));
        }
        return value;
    }

    static PlutusData datum(long version, byte[] chainId, byte[] genesisId, byte[] applicationId,
                            byte[] profileId, byte[] fingerprint, long height, byte[] blockHash,
                            byte[] stateRoot, List<byte[]> memberKeys, long threshold,
                            long updatedAtMs, boolean extraField, boolean dropUpdatedAt) {
        List<PlutusData> fields = new ArrayList<>(List.of(
                PlutusData.integer(version), PlutusData.bytes(chainId),
                PlutusData.bytes(genesisId), PlutusData.bytes(applicationId),
                PlutusData.bytes(profileId), PlutusData.bytes(fingerprint),
                PlutusData.integer(height), PlutusData.bytes(blockHash),
                PlutusData.bytes(stateRoot),
                PlutusData.list(memberKeys.stream().map(PlutusData::bytes).toArray(PlutusData[]::new)),
                PlutusData.integer(threshold)));
        if (!dropUpdatedAt) {
            fields.add(PlutusData.integer(updatedAtMs));
        }
        if (extraField) {
            fields.add(PlutusData.integer(999));
        }
        return PlutusData.constr(0, fields.toArray(PlutusData[]::new));
    }

    static byte[] fill(int len, int b) {
        byte[] bytes = new byte[len];
        java.util.Arrays.fill(bytes, (byte) b);
        return bytes;
    }

    static byte[] memberKey(int i) {
        byte[] key = new byte[32];
        key[30] = (byte) (i >>> 8);
        key[31] = (byte) i;
        return key;
    }

    static List<byte[]> members(int count) {
        List<byte[]> keys = new ArrayList<>(count);
        IntStream.rangeClosed(1, count).mapToObj(AnchorValidatorV2ConformanceTest::memberKey)
                .forEach(keys::add);
        return List.copyOf(keys);
    }
}
