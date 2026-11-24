package org.yanoproject.x.framework.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.FinalityCert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-059 D8b / framework addition 4: with {@code membership.anchor-handoff=true} a
 * committed governed change stays pending until the handoff gate reports a finalized
 * L1-stable anchor output carrying the new set; the old set keeps authority meanwhile.
 */
class GovernedMembershipHandoffTest {
    private static final String KEY_A = key(10);
    private static final String KEY_B = key(11);
    private static final String KEY_C = key(12);
    private static final String KEY_D = key(13);

    @Test
    void committedChangeStaysPendingUntilTheGateFinalizesTheHandoff() {
        MemberGroup group = new MemberGroup(Set.of(KEY_A, KEY_B, KEY_C), 2);
        GovernedMembership governed = gated(group);
        AtomicReference<MembershipHandoff> seen = new AtomicReference<>();
        AtomicReference<Long> finalizeAt = new AtomicReference<>(-1L);
        governed.setHandoffGate((block, handoff) -> {
            seen.set(handoff);
            return block.height() == finalizeAt.get();
        });
        byte[] remove = GovernedMembership.encodeCommand(GovernedMembership.OP_REMOVE,
                HexUtil.decodeHexString(KEY_C), 0, 2);

        GovernedMembership.Result committed = governed.processBlock(block(5, remove, KEY_A, KEY_B));

        // D8b step 1: committed as pending, not activated.
        assertThat(committed.effects()).isEmpty();
        assertThat(committed.writes()).extracting(GovernedMembership.MetaWrite::key)
                .containsExactly("gov_pending", "gov_anchor_handoff");
        MembershipHandoff pending = governed.pendingHandoff().orElseThrow();
        assertThat(pending.committedHeight()).isEqualTo(5);
        assertThat(pending.members()).containsExactlyInAnyOrder(KEY_A, KEY_B);
        assertThat(pending.threshold()).isEqualTo(2);
        assertThat(pending.activationLag()).isEqualTo(2);
        assertThat(group.members()).containsExactlyInAnyOrder(KEY_A, KEY_B, KEY_C);

        // A second approved change while one is pending is void; the old set still governs.
        byte[] add = GovernedMembership.encodeCommand(GovernedMembership.OP_ADD,
                HexUtil.decodeHexString(KEY_D), 0, 1);
        GovernedMembership.Result voided = governed.processBlock(block(6, add, KEY_A, KEY_C));
        assertThat(voided.effects()).isEmpty();
        assertThat(governed.pendingHandoff()).contains(pending);
        assertThat(group.history()).hasSize(1);

        // Blocks without the finalized fact keep it pending.
        assertThat(governed.processBlock(block(7, null)).effects()).isEmpty();
        assertThat(seen.get()).isEqualTo(pending);
        assertThat(group.history()).hasSize(1);

        // D8b step 3 -> 4: the gate finalizes at height 8; the epoch starts after the lag.
        finalizeAt.set(8L);
        GovernedMembership.Result activated = governed.processBlock(block(8, null));
        assertThat(activated.effects()).containsExactly(
                new GovernedMembership.EpochEffect(10, Set.of(KEY_A, KEY_B), 2));
        assertThat(activated.writes()).extracting(GovernedMembership.MetaWrite::key)
                .containsExactly("gov_pending", "gov_anchor_handoff", "member_epochs");
        assertThat(governed.pendingHandoff()).isEmpty();
        assertThat(group.membersAt(9)).containsExactlyInAnyOrder(KEY_A, KEY_B, KEY_C);
        assertThat(group.membersAt(10)).containsExactlyInAnyOrder(KEY_A, KEY_B);
    }

    @Test
    void withoutAnInstalledGateTheHandoffNeverActivates() {
        MemberGroup group = new MemberGroup(Set.of(KEY_A, KEY_B, KEY_C), 2);
        GovernedMembership governed = gated(group);
        byte[] remove = GovernedMembership.encodeCommand(GovernedMembership.OP_REMOVE,
                HexUtil.decodeHexString(KEY_C), 0, 1);
        governed.processBlock(block(1, remove, KEY_A, KEY_B));
        for (long height = 2; height < 50; height++) {
            assertThat(governed.processBlock(block(height, null)).effects()).isEmpty();
        }
        assertThat(governed.pendingHandoff()).isPresent();
        assertThat(group.members()).containsExactlyInAnyOrder(KEY_A, KEY_B, KEY_C);
    }

    /** Review P2-3: a governed cancel withdraws exactly the named pending handoff. */
    @Test
    void aGovernedCancelWithdrawsExactlyTheNamedPendingHandoff() {
        MemberGroup group = new MemberGroup(Set.of(KEY_A, KEY_B, KEY_C), 2);
        GovernedMembership governed = gated(group);
        governed.setHandoffGate((block, handoff) -> false);
        byte[] remove = GovernedMembership.encodeCommand(GovernedMembership.OP_REMOVE,
                HexUtil.decodeHexString(KEY_C), 0, 2);
        governed.processBlock(block(5, remove, KEY_A, KEY_B));
        assertThat(governed.pendingHandoff()).isPresent();

        // A cancel naming another height, even fully approved, is a no-op.
        GovernedMembership.Result wrong = governed.processBlock(
                block(6, GovernedMembership.encodeCancelHandoff(4), KEY_A, KEY_B));
        assertThat(wrong.writes()).extracting(GovernedMembership.MetaWrite::key)
                .containsExactly("gov_pending");
        assertThat(governed.pendingHandoff()).isPresent();

        // One approval is not enough (threshold 2); a non-member's approval never counts.
        byte[] cancel = GovernedMembership.encodeCancelHandoff(5);
        governed.processBlock(block(7, cancel, KEY_A, KEY_D));
        assertThat(governed.pendingHandoff()).isPresent();

        // The second member approval within the window withdraws it, persisted with the block.
        GovernedMembership.Result cancelled = governed.processBlock(block(8, cancel, KEY_C));
        assertThat(cancelled.effects()).isEmpty();
        assertThat(cancelled.writes()).extracting(GovernedMembership.MetaWrite::key)
                .containsExactly("gov_pending", "gov_anchor_handoff");
        assertThat(cancelled.writes().get(1).value()).isEmpty();
        assertThat(governed.pendingHandoff()).isEmpty();
        assertThat(group.members()).containsExactlyInAnyOrder(KEY_A, KEY_B, KEY_C);

        // A new change can now be committed; a stale cancel for the old height does not touch it.
        byte[] raise = GovernedMembership.encodeCommand(GovernedMembership.OP_SET_THRESHOLD, null, 3, 1);
        governed.processBlock(block(9, raise, KEY_A, KEY_B));
        assertThat(governed.pendingHandoff()).map(MembershipHandoff::committedHeight).contains(9L);
        governed.processBlock(block(10, cancel, KEY_A, KEY_B));
        assertThat(governed.pendingHandoff()).map(MembershipHandoff::committedHeight).contains(9L);

        // Codec: only positive heights and a zero lag decode.
        assertThat(GovernedMembership.decodeCommand(cancel).handoffHeight()).isEqualTo(5);
        assertThatThrownBy(() -> GovernedMembership.encodeCancelHandoff(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Ungated chains keep treating the cancel op as malformed, exactly as before it existed. */
    @Test
    void ungatedChainsIgnoreTheCancelOp() {
        MemberGroup group = new MemberGroup(Set.of(KEY_A, KEY_B, KEY_C), 2);
        GovernedMembership plain = new GovernedMembership(group, "", 100, 0,
                LoggerFactory.getLogger(getClass()));
        assertThat(plain.processBlock(block(1, GovernedMembership.encodeCancelHandoff(1), KEY_A, KEY_B)))
                .isSameAs(GovernedMembership.Result.EMPTY);
    }

    @Test
    void ungatedChainsActivateAsBeforeAndHandoffRecordsRoundTrip() {
        MemberGroup group = new MemberGroup(Set.of(KEY_A, KEY_B, KEY_C), 2);
        GovernedMembership plain = new GovernedMembership(group, "", 100, 0,
                LoggerFactory.getLogger(getClass()));
        byte[] remove = GovernedMembership.encodeCommand(GovernedMembership.OP_REMOVE,
                HexUtil.decodeHexString(KEY_C), 0, 1);
        GovernedMembership.Result result = plain.processBlock(block(1, remove, KEY_A, KEY_B));
        assertThat(result.effects()).containsExactly(
                new GovernedMembership.EpochEffect(2, Set.of(KEY_A, KEY_B), 2));
        assertThat(result.writes()).extracting(GovernedMembership.MetaWrite::key)
                .containsExactly("gov_pending", "member_epochs");
        assertThat(plain.handoffGated()).isFalse();
        assertThat(plain.pendingHandoff()).isEmpty();

        MembershipHandoff handoff = new MembershipHandoff(4, Set.of(KEY_B, KEY_A), 2, 3);
        assertThat(GovernedMembership.decodeHandoff(GovernedMembership.encodeHandoff(handoff)))
                .isEqualTo(handoff);
        assertThat(handoff.sameSet(Set.of(KEY_A.toUpperCase(), KEY_B), 2)).isTrue();
        assertThat(handoff.sameSet(Set.of(KEY_A, KEY_B), 1)).isFalse();
        assertThat(handoff.sameSet(Set.of(KEY_A, KEY_C), 2)).isFalse();

        assertThat(GovernedMembership.handoffSelected(Map.of())).isFalse();
        assertThat(GovernedMembership.handoffSelected(
                Map.of(GovernedMembership.HANDOFF_SETTING, "true"))).isTrue();
        assertThatThrownBy(() -> GovernedMembership.handoffSelected(
                Map.of(GovernedMembership.HANDOFF_SETTING, "yes")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Optional.of(handoff)).isPresent();
    }

    private static GovernedMembership gated(MemberGroup group) {
        return new GovernedMembership(group, "", 100, 0, true,
                LoggerFactory.getLogger(GovernedMembershipHandoffTest.class));
    }

    /** A finalized block carrying the identical command from each approver (or nothing). */
    private static AppBlock block(long height, byte[] command, String... approvers) {
        List<AppMessage> messages = new ArrayList<>();
        if (command != null) {
            for (String approver : approvers) {
                messages.add(AppMessage.builder()
                        .version(AppMessage.ENVELOPE_VERSION)
                        .messageId(new byte[32])
                        .chainId("handoff-chain")
                        .topic(GovernedMembership.TOPIC)
                        .sender(HexUtil.decodeHexString(approver))
                        .senderSeq(height)
                        .expiresAt(1)
                        .body(command)
                        .authScheme(FinalityCert.SCHEME_ED25519)
                        .authProof(new byte[]{1})
                        .build());
            }
        }
        return new AppBlock(AppBlock.BLOCK_VERSION, "handoff-chain", height, new byte[32],
                0, new byte[0], 1_000 + height, new byte[32], new byte[32], messages,
                new byte[32], FinalityCert.empty());
    }

    private static String key(int value) {
        return String.format("%064x", value);
    }
}
