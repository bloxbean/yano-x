package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.FinalityCert;
import org.yanoproject.x.framework.api.appchain.codec.AppBlockCodec;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoff;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoffGate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.assets.cardano.gateway.AssetAnchorObserverTest.ANCHOR;
import static org.yanoproject.x.assets.cardano.gateway.AssetAnchorObserverTest.MEMBER_A;
import static org.yanoproject.x.assets.cardano.gateway.AssetAnchorObserverTest.MEMBER_B;
import static org.yanoproject.x.assets.cardano.gateway.AssetAnchorObserverTest.MEMBER_C;
import static org.yanoproject.x.assets.cardano.gateway.AssetAnchorObserverTest.THREAD_NAME;
import static org.yanoproject.x.assets.cardano.gateway.AssetAnchorObserverTest.THREAD_POLICY;
import static org.yanoproject.x.assets.cardano.gateway.AssetAnchorObserverTest.anchorDatum;
import static org.yanoproject.x.assets.cardano.gateway.AssetAnchorObserverTest.anchorSettings;
import static org.yanoproject.x.assets.cardano.gateway.SettlementFixtures.*;

/**
 * ADR-059 D8b handoff step 3 and framework addition 4: the {@code asset-anchor-handoff-v1} gate finalizes a pending
 * change only on a finalized {@code asset-anchor-v1} observation of an accepted anchor output carrying exactly the
 * pending set and threshold at an anchored height of at least the change's committed height, and is total.
 */
class AnchorHandoffGateTest {
    static final String OBSERVER = "anchors";

    /** Chain settings as one chain configuration carries them: the observer, the pinned identity and v2. */
    static Map<String, String> chainSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("membership.mode", "governed");
        settings.put("membership.anchor-handoff", "true");
        settings.put("membership.anchor-handoff-gate", AnchorHandoffGateProvider.ID);
        anchorSettings().forEach((key, value) -> settings.put("observers." + OBSERVER + "." + key, value));
        settings.put("anchor-profile.version", "2");
        settings.put("anchor-profile.pinned.thread-policy-id", THREAD_POLICY);
        settings.put("anchor-profile.pinned.thread-asset-name", java.util.HexFormat.of().formatHex(THREAD_NAME));
        settings.put("anchor-profile.pinned.script-hash", "21".repeat(28));
        settings.put("anchor-profile.pinned.genesis-id", GENESIS);
        settings.put("anchor-profile.pinned.application-id", "asset-payments");
        settings.put("anchor-profile.pinned.commitment-profile", "mpf-blake2b256-v1");
        settings.put("anchor-profile.pinned.format-fingerprint", "fa".repeat(32));
        return settings;
    }

    final MembershipHandoffGate gate = new AnchorHandoffGateProvider().create(CHAIN, chainSettings());
    final AssetAnchorObserver observer = new AssetAnchorObserver(OBSERVER, anchorSettings());
    final MembershipHandoff handoff = new MembershipHandoff(20, Set.of(MEMBER_A.hex(), MEMBER_B.hex(),
            MEMBER_C.hex()), 2, 1);

    /** The real observer's observations of one anchor output carrying {@code datum}. */
    List<L1Observation> anchorOutput(int hashByte, byte[] datum) {
        TransactionBody tx = tx(hashByte, List.of(), output(ANCHOR, datum, lovelace(2_000_000),
                token(THREAD_POLICY, THREAD_NAME, 1)));
        return observer.observe(SLOT, BLOCK_HASH, block(List.of(), List.of(tx), List.of(Witnesses.builder().build())));
    }

    static AppMessage message(String topic, byte[] body, long seq) {
        byte[] sender = filled(32, 9);
        return AppMessage.builder().messageId(AppMessage.computeMessageId(CHAIN, topic, sender, seq, 4_000_000_000L,
                        body)).chainId(CHAIN).topic(topic).sender(sender).senderSeq(seq).expiresAt(4_000_000_000L)
                .body(body).authScheme(AuthScheme.ED25519.getValue()).authProof(new byte[64]).build();
    }

    static AppBlock blockOf(List<AppMessage> messages) {
        return new AppBlock(AppBlock.BLOCK_VERSION, CHAIN, 30, AppBlock.GENESIS_PREV_HASH, 0, new byte[0], 1_000,
                AppBlockCodec.messagesRoot(messages), new byte[32], messages, new byte[32], FinalityCert.empty());
    }

    static AppBlock blockOf(List<L1Observation> observations, AppMessage... extra) {
        List<AppMessage> messages = new ArrayList<>(List.of(extra));
        long seq = 100;
        for (L1Observation observation : observations) {
            messages.add(message(observation.topic(), observation.encode(), seq++));
        }
        return blockOf(messages);
    }

    @Test
    void anAnchorOutputCarryingThePendingSetAtOrAboveTheCommittedHeightFinalizesIt() {
        assertThat(gate.finalizesHandoff(blockOf(anchorOutput(0xa1,
                anchorDatum(20, 2, 5_000, MEMBER_A, MEMBER_B, MEMBER_C))), handoff)).isTrue();
        assertThat(gate.finalizesHandoff(blockOf(anchorOutput(0xa2,
                anchorDatum(25, 2, 5_000, MEMBER_A, MEMBER_B, MEMBER_C))), handoff)).isTrue();
    }

    @Test
    void anythingElseNeverFinalizesTheHandoff() {
        List<List<L1Observation>> others = List.of(
                anchorOutput(0xa3, anchorDatum(19, 2, 5_000, MEMBER_A, MEMBER_B, MEMBER_C)), // stale: below 20
                anchorOutput(0xa4, anchorDatum(22, 1, 5_000, MEMBER_A, MEMBER_B, MEMBER_C)), // other threshold
                anchorOutput(0xa5, anchorDatum(22, 2, 5_000, MEMBER_A, MEMBER_B)),           // other set
                anchorOutput(0xa6, anchorDatum(1, "6c".repeat(32), 22, 2, 0, MEMBER_A, MEMBER_B, MEMBER_C)), // v1
                List.of());
        for (List<L1Observation> observations : others) {
            assertThat(gate.finalizesHandoff(blockOf(observations), handoff)).isFalse();
        }
        L1Observation real = anchorOutput(0xa7, anchorDatum(22, 2, 5_000, MEMBER_A, MEMBER_B, MEMBER_C)).getFirst();
        // The same fact under another observer's topic, or as an ordinary message, is not this gate's fact.
        L1Observation renamed = L1Observation.transaction("other", real.transactionAnchor().transactionHash(),
                real.eventOrdinal(), real.slot(), real.blockHash(), real.claim());
        assertThat(gate.finalizesHandoff(blockOf(List.of(renamed)), handoff)).isFalse();
        assertThat(gate.finalizesHandoff(blockOf(List.of(), message("anchors", real.encode(), 1)), handoff))
                .isFalse();
        // A claim that does not belong to its envelope.
        L1Observation moved = L1Observation.transaction(OBSERVER, filled(32, 0x01), real.eventOrdinal(), real.slot(),
                real.blockHash(), real.claim());
        assertThat(gate.finalizesHandoff(blockOf(List.of(moved)), handoff)).isFalse();
    }

    @Test
    void theGateIsTotal() {
        AppBlock garbage = blockOf(List.of(), message(L1Observation.TOPIC_PREFIX + OBSERVER, new byte[] {1, 2, 3}, 1),
                message(L1Observation.TOPIC_PREFIX + OBSERVER, L1Observation.transaction(OBSERVER, filled(32, 1), 0,
                        1, filled(32, 2), new byte[] {(byte) 0x80}).encode(), 2));
        assertThat(gate.finalizesHandoff(garbage, handoff)).isFalse();
        assertThat(gate.finalizesHandoff(null, handoff)).isFalse();
        assertThat(gate.finalizesHandoff(blockOf(List.of()), null)).isFalse();
    }

    @Test
    void creationFailsClosedWithoutOneAnchorObserverAnchorV2OrAMatchingPin() {
        Map<String, String> none = new TreeMap<>(chainSettings());
        none.remove("observers." + OBSERVER + ".type");
        Map<String, String> two = new TreeMap<>(chainSettings());
        anchorSettings().forEach((key, value) -> two.put("observers.second." + key, value));
        Map<String, String> unpinned = new TreeMap<>(chainSettings());
        unpinned.remove("anchor-profile.pinned.script-hash");
        Map<String, String> otherPin = new TreeMap<>(chainSettings());
        otherPin.put("anchor-profile.pinned.format-fingerprint", "fb".repeat(32));
        Map<String, String> v1 = new TreeMap<>(chainSettings());
        v1.put("anchor-profile.version", "1");
        for (Map<String, String> settings : List.of(none, two, unpinned, otherPin, v1)) {
            assertThatThrownBy(() -> new AnchorHandoffGateProvider().create(CHAIN, settings))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        Map<String, String> upperCasePin = new TreeMap<>(chainSettings());
        upperCasePin.put("anchor-profile.pinned.script-hash", "21".repeat(28).toUpperCase(java.util.Locale.ROOT));
        assertThat(new AnchorHandoffGateProvider().create(CHAIN, upperCasePin)).isNotNull();
        Map<String, String> noPin = new TreeMap<>(chainSettings());
        noPin.keySet().removeIf(key -> key.startsWith("anchor-profile.pinned."));
        assertThat(new AnchorHandoffGateProvider().create(CHAIN, noPin)).isNotNull();
        assertThat(new AnchorHandoffGateProvider().id()).isEqualTo("asset-anchor-handoff-v1");
        assertThat(Hash32.BYTES).isEqualTo(32);
    }
}
