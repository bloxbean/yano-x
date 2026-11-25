package org.yanoproject.x.framework.cardano;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.rollback.RollbackCapableStore;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.utxo.model.AssetAmount;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.runtime.blockproducer.ProtocolParamsMapper;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;
import org.yanoproject.x.framework.api.appchain.FinalityCert;
import org.yanoproject.x.framework.api.appchain.codec.AppBlockCodec;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.x.framework.runtime.appchain.AppChainEngine;
import org.yanoproject.x.framework.runtime.appchain.AppLedgerStore;
import org.yanoproject.x.framework.runtime.appchain.AppMessageSigner;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoff;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.framework.runtime.appchain.AppLedgerTestFixtures.metaPutBytes;
import static org.yanoproject.x.framework.runtime.appchain.AppLedgerTestFixtures.openStore;
import static org.yanoproject.x.framework.runtime.appchain.AppLedgerTestFixtures.signer;

/**
 * ADR-059 framework additions on the script anchor, against a fake L1 UTxO view:
 * <ul>
 *   <li>6: co-sign body hardening (cross-protocol bodies, unknown inputs, the member's own
 *       key, mint/certificate/withdrawal);</li>
 *   <li>5: anchor v2 advances bound to the validity window, pure heartbeats, and the
 *       member-side v2 rules;</li>
 *   <li>3: a pinned identity that is never adopted from a sign request;</li>
 *   <li>4 (anchor side): a committed pending membership as the anchor's next set, co-signed
 *       by the anchor datum's members.</li>
 * </ul>
 */
class ScriptAnchorProfileTest {

    private static final Logger log = LoggerFactory.getLogger(ScriptAnchorProfileTest.class);
    private static final String CHAIN_ID = "profile-chain";
    private static final byte[] L1_TIP_HASH = fill(32, 0x55);
    private static final long ZERO_TIME_MS = 1_700_000_000_000L;
    private static final AnchorProfileSettings.SlotTime SLOT_TIME =
            new AnchorProfileSettings.SlotTime(0, ZERO_TIME_MS, 1_000);
    private static final AnchorProfileSettings V2 =
            new AnchorProfileSettings(2, 600_000, SLOT_TIME, null);
    private static final ProtocolParams DEVNET_PARAMS = loadDevnetParams();
    private static final Network NETWORK = new Network(0, 42);

    @TempDir
    Path tempDir;

    private final List<byte[]> submitted = new ArrayList<>();
    /** Leader submissions fail while set (a failed round); every attempt is counted. */
    private final AtomicBoolean failSubmit = new AtomicBoolean();
    private final AtomicInteger submitAttempts = new AtomicInteger();
    private final long[] tip = {5};
    private final AtomicLong l1Slot = new AtomicLong(500);
    private final AddressedUtxoState utxoState = new AddressedUtxoState();

    private final List<AppLedgerStore> ledgers = new ArrayList<>();
    private final AppMessageSigner leaderSigner = signer("11".repeat(32));
    private final AppMessageSigner followerSigner = signer("22".repeat(32));
    private final AppMessageSigner newcomerSigner = signer("33".repeat(32));
    private Set<String> members;
    private String wallet;

    @BeforeEach
    void setUp() {
        members = Set.of(leaderSigner.publicKeyHex().toLowerCase(), followerSigner.publicKeyHex().toLowerCase());
    }

    @AfterEach
    void tearDown() {
        ledgers.forEach(AppLedgerStore::close);
    }

    // =================================================================== addition 6

    @Test
    void followerRefusesCrossProtocolAndUnsafeBodies() throws Exception {
        Pair pair = pair(AnchorProfileSettings.defaults(), false, () -> Optional.empty());
        Fixture fixture = bootstrapAndAdvance(pair, 9);
        AtomicInteger witnesses = new AtomicInteger();
        ScriptAnchorService probe = probe(AnchorProfileSettings.defaults(), false, Optional::empty,
                followerSigner, witnesses);

        // The honest body is accepted and witnessed.
        deliverRequest(probe, fixture.advance().getBody(), fixture);
        assertThat(witnesses).hasValue(1);

        // A body spending the anchor plus another script input (cross-protocol capture).
        String otherScript = AddressProvider.getEntAddress(Credential.fromScript(fill(28, 0x77)), NETWORK)
                .getAddress();
        utxoState.put(otherScript, List.of(new Utxo(new Outpoint("ab".repeat(32), 0), otherScript,
                BigInteger.valueOf(5_000_000), List.of(), null, null, null, null, false, 50, 0, null)));
        assertRefused(probe, witnesses, fixture, body -> body.getInputs().add(
                new TransactionInput("ab".repeat(32), 0)));

        assertThat(probe.status()).containsEntry("lastCosignRefusalKind", "hazard");

        // An input this node cannot resolve (fail closed), including collateral. Status tells
        // an unresolvable input (a lagging or filtered view) apart from a body hazard.
        assertRefused(probe, witnesses, fixture, body -> body.getInputs().add(
                new TransactionInput("cd".repeat(32), 3)));
        assertThat(probe.status()).containsEntry("lastCosignRefusalKind", "unresolvable-input");
        assertThat((String) probe.status().get("lastCosignRefusal")).contains("cd".repeat(32) + "#3");
        assertThat((String) probe.status().get("lastError")).startsWith("Co-sign refused: unresolvable input");
        assertRefused(probe, witnesses, fixture, body -> body.setCollateral(new ArrayList<>(List.of(
                new TransactionInput("cd".repeat(32), 4)))));

        // An input locked by the member's own key: its witness would authorize that spend.
        String ownAddress = AddressProvider.getEntAddress(
                Credential.fromKey(Blake2bUtil.blake2bHash224(followerSigner.publicKey())), NETWORK).getAddress();
        utxoState.put(ownAddress, List.of(new Utxo(new Outpoint("ef".repeat(32), 1), ownAddress,
                BigInteger.valueOf(9_000_000), List.of(), null, null, null, null, false, 50, 0, null)));
        assertRefused(probe, witnesses, fixture, body -> body.getInputs().add(
                new TransactionInput("ef".repeat(32), 1)));

        // Mint, certificate and withdrawal.
        assertRefused(probe, witnesses, fixture, body -> body.setMint(new ArrayList<>(List.of(
                MultiAsset.builder().policyId("aa".repeat(28)).assets(new ArrayList<>(List.of(
                        Asset.builder().name("0x01").value(BigInteger.ONE).build()))).build()))));
        assertRefused(probe, witnesses, fixture, body -> body.setCerts(new ArrayList<>(List.of(
                new StakeRegistration(StakeCredential.fromKeyHash(fill(28, 0x01)))))));
        assertRefused(probe, witnesses, fixture, body -> body.setWithdrawals(new ArrayList<>(List.of(
                new Withdrawal(AddressProvider.getRewardAddress(Credential.fromKey(fill(28, 0x02)), NETWORK)
                        .getAddress(), BigInteger.ONE)))));
        assertThat(probe.status()).containsEntry("lastCosignRefusalKind", "hazard");
        assertThat((String) probe.status().get("lastError")).startsWith("Co-sign refused: body hazard");

        // The honest body is still accepted afterwards.
        deliverRequest(probe, fixture.advance().getBody(), fixture);
        assertThat(witnesses).hasValue(2);
    }


    /** Review P2-1: a filtered UTxO store is checked at startup and surfaced in status. */
    @Test
    void filteredUtxoViewFailsWhereCertainAndWarnsWhereNot() throws Exception {
        Pair pair = pair(AnchorProfileSettings.defaults(), false, () -> Optional.empty());
        Fixture fixture = bootstrapAndAdvance(pair, 9);
        String scriptCredential = fixture.scriptHash();

        // Unfiltered (default, or enabled with no lists): nothing to report.
        pair.leader().checkUtxoViewFilter(L1UtxoViewFilter.fromGlobals(Map.of()));
        pair.leader().checkUtxoViewFilter(L1UtxoViewFilter.fromGlobals(Map.of(L1UtxoViewFilter.ENABLED, "true")));
        assertThat(pair.leader().status()).doesNotContainKey("utxoViewFiltered");

        // The leader's own wallet filtered out: certain misconfiguration, startup fails.
        L1UtxoViewFilter scriptOnly = L1UtxoViewFilter.fromGlobals(Map.of(L1UtxoViewFilter.ENABLED, "true",
                L1UtxoViewFilter.PAYMENT_CREDENTIALS, scriptCredential));
        assertThatThrownBy(() -> pair.leader().checkUtxoViewFilter(scriptOnly))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("anchor wallet")
                .hasMessageContaining(L1UtxoViewFilter.ADDRESSES);
        // The known anchor script address filtered out: startup fails on any member that
        // holds the identity.
        L1UtxoViewFilter walletOnly = L1UtxoViewFilter.fromGlobals(Map.of(L1UtxoViewFilter.ENABLED, "true",
                L1UtxoViewFilter.ADDRESSES, List.of(wallet)));
        assertThatThrownBy(() -> pair.leader().checkUtxoViewFilter(walletOnly))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("anchor script address");

        // Both admitted: the leader starts, with a prominent warning in status; a follower
        // (which cannot know the leader's wallet) gets the same warning.
        L1UtxoViewFilter both = L1UtxoViewFilter.fromGlobals(Map.of(L1UtxoViewFilter.ENABLED, "true",
                L1UtxoViewFilter.ADDRESSES, wallet + ", " + fixture.scriptAddress()));
        pair.leader().checkUtxoViewFilter(both);
        pair.follower().checkUtxoViewFilter(both);
        for (ScriptAnchorService node : List.of(pair.leader(), pair.follower())) {
            assertThat(node.status()).containsEntry("utxoViewFiltered", true);
            assertThat((String) node.status().get("utxoViewWarning")).contains("leader's wallet");
        }

        // A filtered follower that cannot resolve the leader's fee input refuses, and says why.
        AtomicInteger witnesses = new AtomicInteger();
        ScriptAnchorService probe = probe(AnchorProfileSettings.defaults(), false, Optional::empty,
                followerSigner, witnesses);
        probe.checkUtxoViewFilter(L1UtxoViewFilter.fromGlobals(Map.of(L1UtxoViewFilter.ENABLED, "true",
                L1UtxoViewFilter.ADDRESSES, fixture.scriptAddress())));
        assertRefused(probe, witnesses, fixture, body -> body.getInputs().add(
                new TransactionInput("cd".repeat(32), 3)));
        assertThat(probe.status()).containsEntry("lastCosignRefusalKind", "unresolvable-input");
        assertThat((String) probe.status().get("lastCosignRefusal")).contains(L1UtxoViewFilter.ENABLED);
    }

    /** Review P3: switching anchor-profile.version on an existing thread fails with its cause. */
    @Test
    void persistedIdentityFromAnotherValidatorVersionFailsStartup() throws Exception {
        Pair pair = pair(AnchorProfileSettings.defaults(), false, Optional::empty);
        bootstrapAndAdvance(pair, 9);
        pair.leader().verifyPersistedScriptHash();
        pair.follower().verifyPersistedScriptHash();

        ScriptAnchorService switched = service(true, pair.leaderLedger(), leaderSigner,
                new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults(), V2), (topic, body) -> { });
        switched.wireAnchorProfile(V2);
        assertThatThrownBy(switched::verifyPersistedScriptHash)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("anchor-profile.version=2")
                .hasMessageContaining("different validator");
        // No identity yet: nothing to compare.
        ScriptAnchorService fresh = service(true, ledger("fresh"), leaderSigner,
                new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults(), V2), (topic, body) -> { });
        fresh.verifyPersistedScriptHash();
    }

    // =================================================================== addition 5

    @Test
    void v2AdvanceBindsUpdatedAtToTheValidityUpperBoundAndHeartbeatsKeepTheAnchorFresh() throws Exception {
        Pair pair = pair(V2, false, Optional::empty);
        Fixture fixture = bootstrapAndAdvance(pair, 9);

        AnchorDatumCodec.AnchorDatum genesis = AnchorDatumCodec.decode(
                fixture.bootstrapOut().getInlineDatum().serializeToBytes());
        assertThat(genesis.version()).isEqualTo(2);
        assertThat(genesis.updatedAtMs()).isEqualTo(ZERO_TIME_MS + 500_000);

        TransactionBody body = fixture.advance().getBody();
        assertThat(body.getValidityStartInterval()).isEqualTo(500);
        assertThat(body.getTtl()).isEqualTo(1_100); // 600 000 ms window at 1 s slots
        AnchorDatumCodec.AnchorDatum advanced = AnchorDatumCodec.decode(
                fixture.advanceOut().getInlineDatum().serializeToBytes());
        assertThat(advanced.version()).isEqualTo(2);
        assertThat(advanced.height()).isEqualTo(9);
        assertThat(advanced.updatedAtMs()).isEqualTo(ZERO_TIME_MS + 1_100_000);
        assertThat(fixture.advance().getWitnessSet().getVkeyWitnesses()).hasSize(3);

        // L1 confirms the advance; the follower observes it.
        String advanceHash = fixture.advanceHash();
        Utxo advancedUtxo = anchorUtxo(advanceHash, fixture.advanceOutIndex(), fixture.advanceOut(),
                fixture.policy(), 200);
        utxoState.put(fixture.scriptAddress(), List.of(advancedUtxo));
        pair.leader().onL1Block(200, l1Hash(200), List.of(advanceHash));
        assertThat(pair.leader().lastAnchoredHeight()).isEqualTo(9);
        assertThat(tickConfirmed(pair.follower())).isNotNull();
        assertThat(pair.follower().status()).containsEntry("anchorVersion", 2)
                .containsEntry("anchorUpdatedAtMs", advanced.updatedAtMs());

        // No new blocks and the anchor is fresh: no heartbeat.
        TransactionOutput change = outputTo(fixture.advance(), wallet);
        utxoState.put(wallet, List.of(walletUtxo(advanceHash,
                fixture.advance().getBody().getOutputs().indexOf(change), change.getValue().getCoin().longValue())));
        AtomicLong now = new AtomicLong(advanced.updatedAtMs() + 59 * 60_000L);
        pair.leader().setWallClock(now::get);
        pair.leader().tick();
        assertThat(submitted).hasSize(2);

        // max-interval-minutes (60) elapsed: the leader sends a pure heartbeat.
        now.set(advanced.updatedAtMs() + 61 * 60_000L);
        l1Slot.set(4_000);
        pair.leader().tick();
        assertThat(submitted).hasSize(3);
        Transaction heartbeat = Transaction.deserialize(submitted.get(2));
        TransactionOutput heartbeatOut = outputTo(heartbeat, fixture.scriptAddress());
        AnchorDatumCodec.AnchorDatum beat = AnchorDatumCodec.decode(heartbeatOut.getInlineDatum().serializeToBytes());
        assertThat(beat.height()).isEqualTo(9);
        assertThat(beat.blockHash()).isEqualTo(advanced.blockHash());
        assertThat(beat.stateRoot()).isEqualTo(advanced.stateRoot());
        assertThat(beat.memberKeys()).hasSize(2);
        assertThat(beat.updatedAtMs()).isEqualTo(ZERO_TIME_MS + 4_600_000);
        assertThat(heartbeat.getBody().getTtl()).isEqualTo(4_600);
        assertThat(heartbeat.getWitnessSet().getVkeyWitnesses()).hasSize(3); // follower co-signed

        // Member-side heartbeat rules, against the confirmed advance (height 9).
        AtomicInteger witnesses = new AtomicInteger();
        ScriptAnchorService probe = probe(V2, false, Optional::empty, followerSigner, witnesses);
        Fixture beatFixture = new Fixture(fixture.bootstrap(), fixture.bootstrapOut(), fixture.bootstrapHash(),
                fixture.scriptAddress(), fixture.policy(), fixture.scriptHash(), heartbeat, heartbeatOut,
                heartbeat.getBody().getOutputs().indexOf(heartbeatOut), txHash(submitted.get(2)));
        deliverRequest(probe, heartbeat.getBody(), beatFixture);
        assertThat(witnesses).hasValue(1);
        // Not newer than the anchored updatedAtMs (time rules hold: ub = 1100).
        assertRefused(probe, witnesses, beatFixture, stale -> {
            stale.setValidityStartInterval(500);
            stale.setTtl(1_100);
            outputTo(stale, fixture.scriptAddress()).setInlineDatum(AnchorDatumCodec.encode(
                    beat.withUpdatedAtMs(advanced.updatedAtMs())));
        });
        // Same height but a different member threshold: not a pure heartbeat.
        assertRefused(probe, witnesses, beatFixture, reshaped -> outputTo(reshaped, fixture.scriptAddress())
                .setInlineDatum(AnchorDatumCodec.encode(new AnchorDatumCodec.AnchorDatum(2, beat.chainId(),
                        beat.chainGenesisId(), beat.applicationId(), beat.commitmentProfileId(),
                        beat.formatFingerprint(), beat.height(), beat.blockHash(), beat.stateRoot(),
                        beat.memberKeys(), 1, beat.updatedAtMs()))));

        // The heartbeat is journaled without an anchored-range event, on both nodes.
        String heartbeatHash = txHash(submitted.get(2));
        List<AnchorService.ConfirmedAnchor> events = new ArrayList<>();
        pair.leader().setConfirmationListener(events::add);
        pair.leader().onL1Block(300, l1Hash(300), List.of(heartbeatHash));
        assertThat(events).isEmpty();
        assertThat(pair.leader().lastAnchoredHeight()).isEqualTo(9);
        assertThat(pair.leader().status()).containsEntry("lastAnchorTx", heartbeatHash);
        utxoState.put(fixture.scriptAddress(), List.of(anchorUtxo(heartbeatHash,
                heartbeat.getBody().getOutputs().indexOf(heartbeatOut), heartbeatOut, fixture.policy(), 300)));
        assertThat(tickConfirmed(pair.follower())).isNull();
        assertThat(pair.follower().status()).containsEntry("lastAnchorTx", heartbeatHash);
        assertThat(AnchorService.ConfirmationHistory.decode(
                pair.followerLedger().metaBytes("anchor_confirmation_history_v1")))
                .extracting(AnchorService.Confirmation::toHeight).containsExactly(9L, 9L);
        // A rollback of the heartbeat restores the advance as the frontier.
        pair.follower().onL1Rollback(250);
        assertThat(pair.follower().lastAnchoredHeight()).isEqualTo(9);
        assertThat(pair.follower().status()).containsEntry("lastAnchorTx", advanceHash);
    }

    /**
     * Review P2-4: the v2 time trigger runs from the anchor's updatedAtMs, not from the last
     * round start, so pending blocks below every-blocks and a failed round never let the
     * anchor go stale for another full interval.
     */
    @Test
    void v2TimeTriggerFollowsTheAnchorAgeThroughFailedRounds() throws Exception {
        Pair pair = pair(V2, false, Optional::empty);
        Fixture fixture = bootstrapAndAdvance(pair, 9);
        AnchorDatumCodec.AnchorDatum advanced = AnchorDatumCodec.decode(
                fixture.advanceOut().getInlineDatum().serializeToBytes());
        utxoState.put(fixture.scriptAddress(), List.of(anchorUtxo(fixture.advanceHash(),
                fixture.advanceOutIndex(), fixture.advanceOut(), fixture.policy(), 200)));
        pair.leader().onL1Block(200, l1Hash(200), List.of(fixture.advanceHash()));
        TransactionOutput change = outputTo(fixture.advance(), wallet);
        utxoState.put(wallet, List.of(walletUtxo(fixture.advanceHash(),
                fixture.advance().getBody().getOutputs().indexOf(change), change.getValue().getCoin().longValue())));
        assertThat(pair.leader().lastAnchoredHeight()).isEqualTo(9);
        // The confirmation journal is not L1-stable before slot 200; it is at 200.
        assertThat(AnchorService.stableAnchorAbsenceReason(pair.leaderLedger(), 150))
                .contains("l1.stability-depth deep");
        assertThat(AnchorService.stableAnchorAbsenceReason(pair.leaderLedger(), 200)).isNull();

        // Three pending blocks (below every-blocks 10) and a fresh anchor: no round.
        tip[0] = 12;
        AtomicLong now = new AtomicLong(advanced.updatedAtMs() + 30 * 60_000L);
        pair.leader().setWallClock(now::get);
        int attempts = submitAttempts.get();
        pair.leader().tick();
        assertThat(submitAttempts.get()).isEqualTo(attempts);

        // The anchor is max-interval old: a round starts at once (the last round START was
        // moments ago, which under the v1 rule would hold it back a full interval).
        now.set(advanced.updatedAtMs() + 61 * 60_000L);
        l1Slot.set(4_000);
        failSubmit.set(true);
        pair.leader().tick();
        assertThat(submitAttempts.get()).isEqualTo(attempts + 1);
        assertThat(pair.leader().status()).containsKey("lastError");

        // The round failed; the anchor is still stale, so the very next tick retries.
        pair.leader().tick();
        assertThat(submitAttempts.get()).isEqualTo(attempts + 2);

        failSubmit.set(false);
        pair.leader().tick();
        assertThat(submitted).hasSize(3);
        AnchorDatumCodec.AnchorDatum next = AnchorDatumCodec.decode(outputTo(
                Transaction.deserialize(submitted.get(2)), fixture.scriptAddress()).getInlineDatum().serializeToBytes());
        assertThat(next.height()).isEqualTo(12);
        assertThat(next.updatedAtMs()).isEqualTo(ZERO_TIME_MS + 4_600_000);
    }

    @Test
    void v2MembersRefuseBodiesThatBreakTheTimeOrHeartbeatRules() throws Exception {
        Pair pair = pair(V2, false, Optional::empty);
        Fixture fixture = bootstrapAndAdvance(pair, 9);
        AtomicInteger witnesses = new AtomicInteger();
        ScriptAnchorService probe = probe(V2, false, Optional::empty, followerSigner, witnesses);

        deliverRequest(probe, fixture.advance().getBody(), fixture);
        assertThat(witnesses).hasValue(1);

        // updatedAtMs is not the upper bound.
        assertRefused(probe, witnesses, fixture, body -> body.setTtl(1_099));
        // The window is wider than max-window-ms.
        assertRefused(probe, witnesses, fixture, body -> body.setValidityStartInterval(499));
        // No lower bound.
        assertRefused(probe, witnesses, fixture, body -> body.setValidityStartInterval(0));
        // A v1 datum on a v2 chain.
        assertRefused(probe, witnesses, fixture, body -> {
            TransactionOutput out = outputTo(body, fixture.scriptAddress());
            AnchorDatumCodec.AnchorDatum next = AnchorDatumCodec.decode(out.getInlineDatum().serializeToBytes());
            out.setInlineDatum(AnchorDatumCodec.encode(new AnchorDatumCodec.AnchorDatum(1, next.chainId(),
                    next.chainGenesisId(), next.applicationId(), next.commitmentProfileId(),
                    next.formatFingerprint(), next.height(), next.blockHash(), next.stateRoot(),
                    next.memberKeys(), next.threshold())));
        });
        deliverRequest(probe, fixture.advance().getBody(), fixture);
        assertThat(witnesses).hasValue(2);
    }

    @Test
    void v2ProfileNeedsASlotTimeOffThePublicNetworksAndBuiltinV2Artifacts() {
        assertThatThrownBy(() -> AnchorProfileSettings.from(Map.of(AnchorProfileSettings.VERSION, "2"), 42))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AnchorProfileSettings.SLOT_ZERO);
        assertThat(AnchorProfileSettings.from(Map.of(AnchorProfileSettings.VERSION, "2"),
                AnchorProfileSettings.PREVIEW_MAGIC).slotTime().toPosixMs(10)).isEqualTo(1_666_656_010_000L);
        assertThatThrownBy(() -> AnchorProfileSettings.from(Map.of(AnchorProfileSettings.VERSION, "3"), 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnchorProfileSettings.from(Map.of("anchor-profile.unknown", "1"), 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnchorProfileSettings.from(Map.of(AnchorProfileSettings.SLOT_ZERO, "1"), 42))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AnchorProfileSettings.from(Map.of(), 42)).isEqualTo(AnchorProfileSettings.defaults());

        AnchorScriptArtifacts v1 = new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults());
        AnchorScriptArtifacts v2 = new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults(), V2);
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(AnchorScriptArtifacts.scriptHash(v2.validator(fill(28, 1))))
                .isNotEqualTo(AnchorScriptArtifacts.scriptHash(v1.validator(fill(28, 1))));
        ScriptAnchorService mismatched = service(true, ledger("mismatch"), leaderSigner,
                new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults()), (topic, body) -> { });
        assertThatThrownBy(() -> mismatched.wireAnchorProfile(V2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =================================================================== addition 3

    @Test
    void pinnedIdentityIsInstalledFromConfigurationAndNeverAdoptedFromARequest() throws Exception {
        Pair pair = pair(AnchorProfileSettings.defaults(), false, Optional::empty);
        Fixture fixture = bootstrapAndAdvance(pair, 9);
        byte[] policy = HexUtil.decodeHexString(fixture.policy());
        byte[] scriptHash = HexUtil.decodeHexString(fixture.scriptHash());

        AnchorProfileSettings pinned = pinnedProfile(policy, CHAIN_ID.getBytes(StandardCharsets.UTF_8), scriptHash);
        AtomicInteger witnesses = new AtomicInteger();
        ScriptAnchorService follower = probe(pinned, false, Optional::empty, followerSigner, witnesses);
        follower.installPinnedIdentity();
        assertThat(follower.bootstrapped()).isTrue();
        assertThat(follower.status()).containsEntry("threadPolicyId", fixture.policy())
                .containsEntry("identityCandidatePending", false)
                .containsEntry("identityPinned", true);
        deliverRequest(follower, fixture.advance().getBody(), fixture);
        assertThat(witnesses).hasValue(1);
        // Idempotent across restarts; a rollback never clears a pinned identity.
        follower.installPinnedIdentity();
        follower.onL1Rollback(-1);
        assertThat(follower.bootstrapped()).isTrue();

        // A request for any other identity is refused: no adoption while pinned.
        AnchorProfileSettings otherPolicy = pinnedProfile(fill(28, 0x42),
                CHAIN_ID.getBytes(StandardCharsets.UTF_8), AnchorScriptArtifacts.scriptHash(
                        new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults())
                                .validator(fill(28, 0x42))));
        AtomicInteger otherWitnesses = new AtomicInteger();
        ScriptAnchorService pinnedElsewhere = probe(otherPolicy, false, Optional::empty, followerSigner,
                otherWitnesses);
        pinnedElsewhere.installPinnedIdentity();
        deliverRequest(pinnedElsewhere, fixture.advance().getBody(), fixture);
        assertThat(otherWitnesses).hasValue(0);
        assertThat(pinnedElsewhere.status()).containsEntry("identityCandidatePending", false);

        // A pinned thread asset name that does not match the thread UTxO: never the anchor.
        AtomicInteger wrongNameWitnesses = new AtomicInteger();
        ScriptAnchorService wrongName = probe(pinnedProfile(policy, "other".getBytes(StandardCharsets.UTF_8),
                scriptHash), false, Optional::empty, followerSigner, wrongNameWitnesses);
        wrongName.installPinnedIdentity();
        deliverRequest(wrongName, fixture.advance().getBody(), fixture);
        assertThat(wrongNameWitnesses).hasValue(0);
    }

    @Test
    void pinnedIdentityFailsClosedOnEveryMismatch() throws Exception {
        Pair pair = pair(AnchorProfileSettings.defaults(), false, Optional::empty);
        Fixture fixture = bootstrapAndAdvance(pair, 9);
        byte[] policy = HexUtil.decodeHexString(fixture.policy());
        byte[] scriptHash = HexUtil.decodeHexString(fixture.scriptHash());
        byte[] name = CHAIN_ID.getBytes(StandardCharsets.UTF_8);

        assertPinRejected(new AnchorProfileSettings.PinnedIdentity(policy, name, fill(28, 9),
                fill(32, 0x31), "ordered-log", "mpf-blake2b256-v1", formatFingerprint()), "script-hash");
        assertPinRejected(new AnchorProfileSettings.PinnedIdentity(policy, name, scriptHash,
                fill(32, 0x32), "ordered-log", "mpf-blake2b256-v1", formatFingerprint()), "genesis-id");
        assertPinRejected(new AnchorProfileSettings.PinnedIdentity(policy, name, scriptHash,
                fill(32, 0x31), "other-app", "mpf-blake2b256-v1", formatFingerprint()), "application-id");
        assertPinRejected(new AnchorProfileSettings.PinnedIdentity(policy, name, scriptHash,
                fill(32, 0x31), "ordered-log", "jmt-blake2b256-v1", formatFingerprint()), "commitment-profile");
        assertPinRejected(new AnchorProfileSettings.PinnedIdentity(policy, name, scriptHash,
                fill(32, 0x31), "ordered-log", "mpf-blake2b256-v1", fill(32, 0x01)), "format-fingerprint");

        // A node that already persisted (adopted or bootstrapped) a different identity.
        AppLedgerStore persisted = ledger("persisted");
        metaPutBytes(persisted, "anchor_script_policy_id", fill(28, 0x42));
        metaPutBytes(persisted, "anchor_script_hash", fill(28, 0x43));
        ScriptAnchorService node = service(false, persisted, followerSigner,
                new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults()), (topic, body) -> { });
        node.wireAnchorProfile(pinnedProfile(policy, name, scriptHash));
        assertThatThrownBy(node::installPinnedIdentity)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persisted anchor identity");

        assertThatThrownBy(() -> AnchorProfileSettings.from(Map.of(
                AnchorProfileSettings.PINNED_THREAD_POLICY_ID, fixture.policy()), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every key");
    }

    // =================================================================== addition 4

    @Test
    void handoffCarriesTheCommittedPendingSetSignedByTheAnchorDatumMembers() throws Exception {
        String newcomer = newcomerSigner.publicKeyHex().toLowerCase();
        Set<String> next = new TreeSet<>(members);
        next.add(newcomer);
        AtomicReference<Optional<MembershipHandoff>> pending = new AtomicReference<>(Optional.empty());
        Pair pair = pair(AnchorProfileSettings.defaults(), true, pending::get);
        Fixture boot = bootstrap(pair);
        pending.set(Optional.of(new MembershipHandoff(3, next, 2, 10)));

        // D8b step 2: the advance carries the pending set, co-signed by the CURRENT anchor
        // datum's members (the bootstrap set).
        tip[0] = 9;
        pair.leader().tick();
        assertThat(submitted).hasSize(2);
        Transaction advance = Transaction.deserialize(submitted.get(1));
        TransactionOutput out = outputTo(advance, boot.scriptAddress());
        AnchorDatumCodec.AnchorDatum datum = AnchorDatumCodec.decode(out.getInlineDatum().serializeToBytes());
        assertThat(datum.memberKeys()).hasSize(3);
        assertThat(datum.threshold()).isEqualTo(2);
        assertThat(advance.getBody().getRequiredSigners()).hasSize(2);
        assertThat(advance.getWitnessSet().getVkeyWitnesses()).hasSize(3);

        // A member without the handoff gate refuses a datum that differs from its epoch.
        Fixture fixture = new Fixture(boot.bootstrap(), boot.bootstrapOut(), boot.bootstrapHash(),
                boot.scriptAddress(), boot.policy(), boot.scriptHash(), advance, out,
                advance.getBody().getOutputs().indexOf(out), txHash(submitted.get(1)));
        AtomicInteger ungated = new AtomicInteger();
        deliverRequest(probe(AnchorProfileSettings.defaults(), false, Optional::empty, followerSigner, ungated),
                advance.getBody(), fixture);
        assertThat(ungated).hasValue(0);

        // Once the handoff anchor is on L1, the next advance is co-signed by the NEW set,
        // which the validator now authorizes against, although consensus has not activated it.
        utxoState.put(boot.scriptAddress(), List.of(anchorUtxo(fixture.advanceHash(), fixture.advanceOutIndex(),
                out, boot.policy(), 200)));
        pair.leader().onL1Block(200, l1Hash(200), List.of(fixture.advanceHash()));
        TransactionOutput change = outputTo(advance, wallet);
        utxoState.put(wallet, List.of(walletUtxo(fixture.advanceHash(),
                advance.getBody().getOutputs().indexOf(change), change.getValue().getCoin().longValue())));
        // The datum's members include the newcomer, who is not yet a consensus member and may
        // not answer: the round starts with old ∩ new (2, meeting the datum threshold) instead
        // of waiting out a timeout, and completes at once.
        tip[0] = 20;
        assertThat(pair.leader().status()).containsEntry("pendingHandoffMembers", 3);
        pair.leader().tick();
        assertThat(submitted).hasSize(3);
        Transaction handoffSigned = Transaction.deserialize(submitted.get(2));
        assertThat(handoffSigned.getBody().getRequiredSigners()).hasSize(2);
        assertThat(handoffSigned.getWitnessSet().getVkeyWitnesses()).hasSize(3);
        assertThat(AnchorDatumCodec.decode(outputTo(handoffSigned, boot.scriptAddress()).getInlineDatum()
                .serializeToBytes()).memberKeys()).hasSize(3);
    }

    // ================================================================= fixtures

    private record Pair(ScriptAnchorService leader, ScriptAnchorService follower,
                        AppLedgerStore leaderLedger, AppLedgerStore followerLedger) {
    }

    private record Fixture(Transaction bootstrap, TransactionOutput bootstrapOut, String bootstrapHash,
                           String scriptAddress, String policy, String scriptHash,
                           Transaction advance, TransactionOutput advanceOut, int advanceOutIndex,
                           String advanceHash) {
    }

    private Pair pair(AnchorProfileSettings profile, boolean handoff,
                      java.util.function.Supplier<Optional<MembershipHandoff>> pending) {
        AppLedgerStore leaderLedger = ledger("leader");
        AppLedgerStore followerLedger = ledger("follower");
        ScriptAnchorService[] follower = new ScriptAnchorService[1];
        ScriptAnchorService[] leader = new ScriptAnchorService[1];
        leader[0] = service(true, leaderLedger, leaderSigner,
                new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults(), profile),
                (topic, body) -> deliver(follower[0], leaderSigner.publicKey(), topic, body));
        follower[0] = service(false, followerLedger, followerSigner,
                new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults(), profile),
                (topic, body) -> deliver(leader[0], followerSigner.publicKey(), topic, body));
        for (ScriptAnchorService service : List.of(leader[0], follower[0])) {
            service.wireAnchorProfile(profile);
            service.wireMembershipHandoff(handoff, pending);
        }
        wallet = leader[0].anchorAddress();
        return new Pair(leader[0], follower[0], leaderLedger, followerLedger);
    }

    private ScriptAnchorService probe(AnchorProfileSettings profile, boolean handoff,
                                      java.util.function.Supplier<Optional<MembershipHandoff>> pending,
                                      AppMessageSigner member, AtomicInteger witnesses) {
        ScriptAnchorService probe = service(false, ledger("probe-" + witnesses.hashCode() + "-" + ledgers.size()),
                member, new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults(), profile),
                (topic, body) -> {
                    if (ScriptAnchorService.TOPIC_SIG.equals(topic)) {
                        witnesses.incrementAndGet();
                    }
                });
        probe.wireAnchorProfile(profile);
        probe.wireMembershipHandoff(handoff, pending);
        return probe;
    }

    private ScriptAnchorService service(boolean leader, AppLedgerStore ledger, AppMessageSigner member,
                                        AnchorScriptArtifacts artifacts,
                                        java.util.function.BiConsumer<String, byte[]> diffuser) {
        AppChainConfig.AnchorConfig config = leader
                ? new AppChainConfig.AnchorConfig(true, "aa".repeat(32), 10, 60, 7014,
                        AppChainConfig.AnchorConfig.DEFAULT_VALIDITY_SLOTS,
                        AppChainConfig.AnchorConfig.DEFAULT_FALLBACK_FEE_LOVELACE,
                        AppChainConfig.AnchorConfig.MODE_SCRIPT, null)
                : new AppChainConfig.AnchorConfig(false, "", 0, 0, 0);
        ScriptAnchorService service = new ScriptAnchorService(CHAIN_ID, "ordered-log", config, ledger,
                leader ? cbor -> {
                    submitAttempts.incrementAndGet();
                    if (failSubmit.get()) {
                        throw new IllegalStateException("submit rejected");
                    }
                    submitted.add(cbor);
                    return txHash(cbor);
                } : cbor -> {
                    throw new AssertionError("A follower never submits");
                },
                () -> utxoState, this::blockAt, () -> tip[0], artifacts, member, () -> members, () -> 2,
                diffuser, leader, 42, log);
        service.wireTxPricing(() -> DEVNET_PARAMS, () -> new AppChainEngine.L1Ref(l1Slot.get(), L1_TIP_HASH));
        service.wireCanonicalHashAtSlot(ScriptAnchorProfileTest::l1Hash);
        return service;
    }

    private Fixture bootstrap(Pair pair) throws Exception {
        utxoState.put(wallet, List.of(walletUtxo("cc".repeat(32), 0, 100_000_000)));
        Map<String, Object> boot = pair.leader().bootstrap();
        Transaction bootstrap = Transaction.deserialize(submitted.getFirst());
        String bootstrapHash = (String) boot.get("txHash");
        String scriptAddress = (String) boot.get("scriptAddress");
        String policy = (String) boot.get("threadPolicyId");
        pair.leader().onL1Block(100, l1Hash(100), List.of(bootstrapHash));
        TransactionOutput bootstrapOut = outputTo(bootstrap, scriptAddress);
        utxoState.put(scriptAddress, List.of(anchorUtxo(bootstrapHash,
                bootstrap.getBody().getOutputs().indexOf(bootstrapOut), bootstrapOut, policy, 100)));
        TransactionOutput change = outputTo(bootstrap, wallet);
        utxoState.put(wallet, List.of(walletUtxo(bootstrapHash,
                bootstrap.getBody().getOutputs().indexOf(change), change.getValue().getCoin().longValue())));
        return new Fixture(bootstrap, bootstrapOut, bootstrapHash, scriptAddress, policy,
                (String) boot.get("scriptHash"), null, null, -1, null);
    }

    private Fixture bootstrapAndAdvance(Pair pair, long height) throws Exception {
        Fixture boot = bootstrap(pair);
        tip[0] = height;
        pair.leader().tick();
        assertThat(submitted).hasSize(2);
        Transaction advance = Transaction.deserialize(submitted.get(1));
        TransactionOutput out = outputTo(advance, boot.scriptAddress());
        return new Fixture(boot.bootstrap(), boot.bootstrapOut(), boot.bootstrapHash(), boot.scriptAddress(),
                boot.policy(), boot.scriptHash(), advance, out, advance.getBody().getOutputs().indexOf(out),
                txHash(submitted.get(1)));
    }

    private void assertRefused(ScriptAnchorService probe, AtomicInteger witnesses, Fixture fixture,
                               Consumer<TransactionBody> mutation) throws Exception {
        int before = witnesses.get();
        TransactionBody body = Transaction.deserialize(fixture.advance().serialize()).getBody();
        mutation.accept(body);
        deliverRequest(probe, body, fixture);
        assertThat(witnesses).hasValue(before);
    }

    private void deliverRequest(ScriptAnchorService target, TransactionBody body, Fixture fixture)
            throws Exception {
        deliver(target, leaderSigner.publicKey(), ScriptAnchorService.TOPIC_SIGN,
                ScriptAnchorService.encodeSignRequest(CborSerializationUtil.serialize(body.serialize(Era.Conway)),
                        HexUtil.decodeHexString(fixture.policy()), HexUtil.decodeHexString(fixture.scriptHash())));
    }

    private void assertPinRejected(AnchorProfileSettings.PinnedIdentity pin, String reason) {
        ScriptAnchorService node = service(false, ledger("pin-" + reason), followerSigner,
                new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults()), (topic, body) -> { });
        node.wireAnchorProfile(new AnchorProfileSettings(1, 600_000, null, pin));
        assertThatThrownBy(node::installPinnedIdentity)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reason);
        assertThat(node.bootstrapped()).isFalse();
    }

    private AnchorProfileSettings pinnedProfile(byte[] policy, byte[] assetName, byte[] scriptHash) {
        return new AnchorProfileSettings(1, 600_000, null, new AnchorProfileSettings.PinnedIdentity(
                policy, assetName, scriptHash, fill(32, 0x31), "ordered-log", "mpf-blake2b256-v1",
                formatFingerprint()));
    }

    private static byte[] formatFingerprint() {
        return stateIdentity().profile().formatFingerprint();
    }

    private AppLedgerStore ledger(String name) {
        AppLedgerStore ledger = openStore(tempDir.resolve(name + "-" + ledgers.size()).toString(), log,
                stateIdentity());
        ledgers.add(ledger);
        return ledger;
    }

    private static StateCommitmentIdentity stateIdentity() {
        return StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF, fill(32, 0x31));
    }

    private void deliver(ScriptAnchorService target, byte[] sender, String topic, byte[] body) {
        if (target == null) {
            return;
        }
        target.onAnchorMessage(AppMessage.builder()
                .messageId(new byte[32])
                .chainId(CHAIN_ID)
                .topic(topic)
                .sender(sender)
                .body(body)
                .build());
    }

    private AppBlock blockAt(long height) {
        return new AppBlock(AppBlock.BLOCK_VERSION, CHAIN_ID, height, new byte[32],
                0, new byte[0], 1_000_000 + height, new byte[32], fill(32, (int) height),
                List.of(), new byte[32], FinalityCert.empty());
    }

    private static AnchorService.ConfirmedAnchor tickConfirmed(ScriptAnchorService service) {
        List<AnchorService.ConfirmedAnchor> confirmed = new ArrayList<>();
        service.setConfirmationListener(confirmed::add);
        try {
            service.tick();
        } finally {
            service.setConfirmationListener(anchor -> { });
        }
        return confirmed.isEmpty() ? null : confirmed.getLast();
    }

    private static TransactionOutput outputTo(Transaction tx, String address) {
        return outputTo(tx.getBody(), address);
    }

    private static TransactionOutput outputTo(TransactionBody body, String address) {
        return body.getOutputs().stream()
                .filter(o -> address.equals(o.getAddress()))
                .findFirst().orElseThrow();
    }

    private Utxo walletUtxo(String txHash, int index, long lovelace) {
        return new Utxo(new Outpoint(txHash, index), wallet, BigInteger.valueOf(lovelace),
                List.of(), null, null, null, null, false, 0, 0, null);
    }

    private static Utxo anchorUtxo(String txHash, int index, TransactionOutput anchorOut, String policyIdHex,
                                   long slot) {
        String assetName = anchorOut.getValue().getMultiAssets().stream()
                .filter(ma -> policyIdHex.equalsIgnoreCase(ma.getPolicyId()))
                .flatMap(ma -> ma.getAssets().stream())
                .map(asset -> {
                    String hex = asset.getNameAsHex();
                    return hex.startsWith("0x") ? hex.substring(2) : hex;
                })
                .findFirst().orElse("");
        return new Utxo(new Outpoint(txHash, index), anchorOut.getAddress(), anchorOut.getValue().getCoin(),
                List.of(new AssetAmount(policyIdHex, assetName, BigInteger.ONE)),
                null, anchorOut.getInlineDatum().serializeToBytes(), null, null, false, slot, 0, null);
    }

    private static String txHash(byte[] txCbor) {
        try {
            return com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(
                    Transaction.deserialize(txCbor));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] l1Hash(long slot) {
        return fill(32, (int) (slot % 251) + 1);
    }

    private static byte[] fill(int len, int b) {
        byte[] bytes = new byte[len];
        java.util.Arrays.fill(bytes, (byte) b);
        return bytes;
    }

    private static ProtocolParams loadDevnetParams() {
        try (InputStream input = Objects.requireNonNull(ScriptAnchorProfileTest.class
                .getResourceAsStream("/cardano-fixtures/devnet-protocol-param.json"), "devnet params")) {
            return ProtocolParamsMapper.fromNodeProtocolParamToCardanoClient(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8), 0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Address-keyed UTxO view (the shared fake "L1"). */
    private final class AddressedUtxoState implements UtxoState, RollbackCapableStore {
        private final Map<String, List<Utxo>> byAddress = new HashMap<>();

        void put(String address, List<Utxo> utxos) {
            byAddress.put(address, new ArrayList<>(utxos));
        }

        @Override
        public List<Utxo> getUtxosByAddress(String address, int page, int pageSize) {
            return page <= 1 ? byAddress.getOrDefault(address, List.of()) : List.of();
        }

        @Override
        public List<Utxo> getUtxosByPaymentCredential(String credential, int page, int pageSize) {
            return List.of();
        }

        @Override
        public Optional<Utxo> getUtxo(Outpoint outpoint) {
            return byAddress.values().stream().flatMap(List::stream)
                    .filter(u -> u.outpoint().equals(outpoint)).findFirst();
        }

        @Override
        public List<Utxo> getOutputsByTxHash(String txHash) {
            return byAddress.values().stream().flatMap(List::stream)
                    .filter(utxo -> txHash.equals(utxo.outpoint().txHash())).toList();
        }

        @Override public boolean isEnabled() { return true; }
        @Override public String storeName() { return "test-utxo"; }
        @Override public long getLatestAppliedSlot() { return l1Slot.get(); }
        @Override public AppliedPoint getLatestAppliedPoint() {
            return new AppliedPoint(l1Slot.get(), HexUtil.encodeHexString(L1_TIP_HASH));
        }
        @Override public long getRollbackFloorSlot() { return 0L; }
        @Override public void rollbackToSlot(long targetSlot) { }
    }
}
