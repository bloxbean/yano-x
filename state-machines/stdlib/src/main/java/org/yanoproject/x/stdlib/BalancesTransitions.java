package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.transition.StateMutation;
import org.yanoproject.api.appchain.transition.TransitionCapability;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionPlan;
import org.yanoproject.x.stdlib.contracts.BalancesContract;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * Pure balance decision shared by standalone application and declarative composition.
 * Authorization is derived only from the original sender and the configured minter. The decision reads
 * immutable facts, never a writer, and preserves the existing unsigned-balance state encoding (including
 * {@link BigInteger#toByteArray()} sign padding). Zero balances delete their state key.
 */
public final class BalancesTransitions implements
        TransitionCapability<BalancesContract.Command, BalancesTransitions.Facts> {
    private final String minterHex;

    /** @param minterHex normalized member public-key hex, or empty to permit any authenticated member to mint */
    public BalancesTransitions(String minterHex) {
        this.minterHex = Objects.requireNonNull(minterHex, "minterHex");
    }

    /** Immutable pre-state amounts; sender and recipient may name the same account. */
    public record Facts(BigInteger senderBalance, BigInteger recipientBalance) {
        public Facts {
            if (senderBalance.signum() < 0 || recipientBalance.signum() < 0) {
                throw new IllegalArgumentException("negative balance facts");
            }
        }
    }

    /** Reads only exact account keys from the supplied component view, including any cascade overlay. */
    public static Facts facts(BalancesContract.Command command, TransitionContext context, AppStateReader state) {
        String sender = HexUtil.encodeHexString(context.sender());
        return new Facts(balance(state, sender), balance(state, command.account()));
    }

    /**
     * Produces at most two unique writes. Self-transfers still enforce sufficient funds but produce one
     * write with the unchanged amount, matching the old debit-then-credit result without duplicate plan keys.
     * Rejections are deterministic business no-ops for standalone callers and reject an enclosing cascade.
     */
    @Override
    public TransitionDecision decide(BalancesContract.Command command, TransitionContext context, Facts facts) {
        if (command.operation() != BalancesContract.OP_MINT && command.operation() != BalancesContract.OP_TRANSFER
                || command.amount() == null || command.amount().signum() <= 0) {
            throw new IllegalArgumentException("invalid balance command");
        }
        BalancesContract.accountKey(command.account());
        String sender = HexUtil.encodeHexString(context.sender());
        if (command.operation() == BalancesContract.OP_MINT) {
            if (!minterHex.isEmpty() && !minterHex.equals(sender)) {
                return TransitionDecision.reject("BALANCE_NOT_MINTER", "sender is not the configured minter");
            }
            return TransitionDecision.approve(TransitionPlan.mutations(List.of(
                    write(command.account(), facts.recipientBalance().add(command.amount())))));
        }
        if (facts.senderBalance().compareTo(command.amount()) < 0) {
            return TransitionDecision.reject("BALANCE_INSUFFICIENT", "sender has insufficient balance");
        }
        if (sender.equals(command.account())) {
            return TransitionDecision.approve(TransitionPlan.mutations(List.of(write(sender, facts.senderBalance()))));
        }
        return TransitionDecision.approve(TransitionPlan.mutations(List.of(
                write(sender, facts.senderBalance().subtract(command.amount())),
                write(command.account(), facts.recipientBalance().add(command.amount())))));
    }

    private static BigInteger balance(AppStateReader state, String account) {
        return state.get(BalancesContract.accountKey(account)).map(BalancesContract::decodeBalance)
                .orElse(BigInteger.ZERO);
    }

    private static StateMutation write(String account, BigInteger amount) {
        byte[] key = BalancesContract.accountKey(account);
        return amount.signum() == 0 ? StateMutation.delete(key) : StateMutation.put(key, amount.toByteArray());
    }
}
