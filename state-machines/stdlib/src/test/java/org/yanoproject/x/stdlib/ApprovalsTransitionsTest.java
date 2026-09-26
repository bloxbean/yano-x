package org.yanoproject.x.stdlib;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.x.stdlib.contracts.ApprovalsContract;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalsTransitionsTest {
    private final ApprovalsTransitions transitions = new ApprovalsTransitions();

    @Test
    void standaloneProposalStagesPayloadOnlyForLegacyEffects() {
        var command = new ApprovalsContract.Command(0, "item", new byte[]{42}, 2, 0);
        var plain = transitions.evaluate(command, context(1, 1), facts(null, null, false, false));
        var staged = transitions.evaluate(command, context(1, 1), facts(null, null, true, false));
        assertThat(plain.plan().mutations()).hasSize(1);
        assertThat(staged.plan().mutations()).hasSize(2);
        assertThat(staged.plan().mutations().getFirst().value())
                .containsExactly(plain.plan().mutations().getFirst().value());
        assertThat(staged.plan().mutations().getLast().value()).containsExactly(0x41, 42);
    }

    @Test
    void composedApprovalForwardsStagedPayloadAndConsumesItInSamePlan() {
        var pending = new ApprovalsStateMachine.Item(0, sender(1), new byte[32], 1, 0, List.of(), new byte[0]);
        var decision = (TransitionDecision.Approved) StockTransitionKernels.approvals(transitions).decide(
                vote(), context(2, 1), facts(pending, new byte[]{42}, true, true));
        assertThat(decision.plan().mutations()).hasSize(2);
        assertThat(decision.plan().mutations().getLast().value()).isNull();
        assertThat(TransitionScalars.decode(decision.plan().events().getFirst().payload()).get("payload"))
                .isEqualTo(new byte[]{42});
        assertThat(TransitionScalars.decode(decision.plan().events().getFirst().payload()))
                .containsEntry("approverCount", 1L);
    }

    @Test
    void terminalAndDuplicateVotesCannotRefireApprovedEvent() {
        var approved = new ApprovalsStateMachine.Item(1, sender(1), new byte[32], 1, 0,
                List.of(sender(2)), new byte[0]);
        // A genuinely fresh source identity must not rely on composite receipt replay for one-use behavior.
        var freshVote = new TransitionContext(2, 1, 0, sender(9), "approvals", sender(3));
        var terminal = (TransitionDecision.Approved) StockTransitionKernels.approvals(transitions).decide(
                vote(), freshVote, facts(approved, null, true, true));
        assertThat(terminal.plan().mutations()).isEmpty();
        assertThat(terminal.plan().events()).isEmpty();
        var pending = new ApprovalsStateMachine.Item(0, sender(1), new byte[32], 2, 0,
                List.of(sender(2)), new byte[0]);
        var duplicate = transitions.evaluate(vote(), context(2, 1), facts(pending, null, true, true));
        assertThat(duplicate.change()).isEqualTo(ApprovalsTransitions.Change.NONE);
    }

    @Test
    void deadlineUsesConsensusTimestampAndMissingActionRejectsWithoutApprovalPlan() {
        var pending = new ApprovalsStateMachine.Item(0, sender(1), new byte[32], 1, 10, List.of(), new byte[0]);
        assertThat(transitions.evaluate(vote(), context(2, 11), facts(pending, null, true, true)).change())
                .isEqualTo(ApprovalsTransitions.Change.EXPIRED);
        var missing = (TransitionDecision.Rejected) StockTransitionKernels.approvals(transitions).decide(
                vote(), context(2, 10), facts(pending, null, true, true));
        assertThat(missing.rejection().code()).isEqualTo("APPROVAL_PAYLOAD_UNAVAILABLE");
    }

    @Test
    void itemFactDoesNotExposeMutableMemberKeys() {
        byte[] proposer = sender(1);
        var item = new ApprovalsStateMachine.Item(0, proposer, new byte[32], 1, 0, List.of(), new byte[0]);
        proposer[0] = 9;
        item.proposer()[0] = 8;
        assertThat(item.proposer()).containsExactly(sender(1));
    }

    private static ApprovalsContract.Command vote() {
        return new ApprovalsContract.Command(1, "item", new byte[0], 0, 0);
    }
    private static ApprovalsTransitions.Facts facts(ApprovalsStateMachine.Item item, byte[] payload,
                                                     boolean stage, boolean consume) {
        return new ApprovalsTransitions.Facts(Optional.ofNullable(item), Optional.ofNullable(payload), stage, consume);
    }
    private static TransitionContext context(int sender, long timestamp) {
        return new TransitionContext(1, timestamp, 0, new byte[32], "approvals", sender(sender));
    }
    private static byte[] sender(int value) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) value;
        return bytes;
    }
}
