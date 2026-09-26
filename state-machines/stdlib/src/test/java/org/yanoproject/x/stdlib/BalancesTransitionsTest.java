package org.yanoproject.x.stdlib;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.transition.StateMutation;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.x.stdlib.contracts.BalancesContract;

import java.math.BigInteger;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class BalancesTransitionsTest {
    private static final TransitionContext CONTEXT = new TransitionContext(1, 1, 0, new byte[32], "balances",
            new byte[32]);
    private static final String SENDER = HexFormat.of().formatHex(CONTEXT.sender());

    @Test
    void mintPreservesLegacyPositiveSignPadding() {
        var decision = (TransitionDecision.Approved) new BalancesTransitions("").decide(
                command(0, "recipient", 128), CONTEXT, facts(0, 0));
        assertThat(decision.plan().mutations().getFirst().key())
                .containsExactly(BalancesContract.accountKey("recipient"));
        assertThat(decision.plan().mutations().getFirst().value()).containsExactly(0, (byte) 128);
    }

    @Test
    void transferDeletesDepletedAccountAndCreditsRecipient() {
        var decision = (TransitionDecision.Approved) new BalancesTransitions("").decide(
                command(1, "recipient", 10), CONTEXT, facts(10, 5));
        assertThat(decision.plan().mutations()).hasSize(2);
        assertThat(decision.plan().mutations().getFirst().kind()).isEqualTo(StateMutation.Kind.DELETE);
        assertThat(decision.plan().mutations().getLast().value()).containsExactly(15);
    }

    @Test
    void selfTransferHasOneUnchangedWriteAndStillRequiresFunds() {
        var transitions = new BalancesTransitions("");
        var approved = (TransitionDecision.Approved) transitions.decide(command(1, SENDER, 5), CONTEXT, facts(10, 10));
        assertThat(approved.plan().mutations()).hasSize(1);
        assertThat(approved.plan().mutations().getFirst().value()).containsExactly(10);
        var rejected = (TransitionDecision.Rejected) transitions.decide(command(1, SENDER, 11), CONTEXT, facts(10, 10));
        assertThat(rejected.rejection().code()).isEqualTo("BALANCE_INSUFFICIENT");
    }

    @Test
    void minterCheckUsesOriginalSender() {
        var rejected = (TransitionDecision.Rejected) new BalancesTransitions("01".repeat(32))
                .decide(command(0, "recipient", 1), CONTEXT, facts(0, 0));
        assertThat(rejected.rejection().code()).isEqualTo("BALANCE_NOT_MINTER");
    }

    @Test
    void composedEventsRejectNumericOverflowWhilePureStandalonePlanRemainsExact() {
        var transitions = new BalancesTransitions("");
        var facts = new BalancesTransitions.Facts(BigInteger.ZERO, BigInteger.valueOf(Long.MAX_VALUE));
        var command = command(0, "recipient", 1);
        var pure = (TransitionDecision.Approved) transitions.decide(command, CONTEXT, facts);
        assertThat(new BigInteger(1, pure.plan().mutations().getFirst().value()))
                .isEqualTo(BigInteger.ONE.shiftLeft(63));
        var composed = (TransitionDecision.Rejected) StockTransitionKernels.balances(transitions)
                .decide(command, CONTEXT, facts);
        assertThat(composed.rejection().code()).isEqualTo("BALANCE_EVENT_RANGE");
    }

    @Test
    void selfTransferEventReportsUnchangedBalances() {
        var decision = (TransitionDecision.Approved) StockTransitionKernels.balances(new BalancesTransitions(""))
                .decide(command(1, SENDER, 3), CONTEXT, facts(10, 10));
        assertThat(TransitionScalars.decode(decision.plan().events().getFirst().payload()))
                .containsEntry("amount", 3L).containsEntry("fromBalanceAfter",
                        10L).containsEntry("toBalanceAfter", 10L);
    }

    private static BalancesContract.Command command(int op, String to, long amount) {
        return new BalancesContract.Command(op, to, BigInteger.valueOf(amount));
    }
    private static BalancesTransitions.Facts facts(long sender, long recipient) {
        return new BalancesTransitions.Facts(BigInteger.valueOf(sender), BigInteger.valueOf(recipient));
    }
}
