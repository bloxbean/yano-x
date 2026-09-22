package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.AppCapabilityManifest;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.proof.ProofSubjectProvider;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionPlans;
import org.yanoproject.x.stdlib.contracts.BalancesContract;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Standard-library state machine {@code balances} (ADR app-layer/006 E2.3):
 * account balances over a shared ledger with per-sender authorization and
 * non-negativity enforced deterministically. Every account balance is a
 * provable state key.
 * <p>
 * Commands (CBOR body):
 * <pre>
 *   [0, to(tstr), amount(uint)]           MINT     — only the configured minter member may mint
 *   [1, to(tstr), amount(uint)]           TRANSFER — moves from the SENDER's account to `to`
 * </pre>
 * Rules (all deterministic):
 * <ul>
 *   <li>Accounts are arbitrary application strings (the sender's own account is
 *       keyed by its member public key hex, so a member can only spend its own
 *       balance).</li>
 *   <li>MINT credits {@code to}; if {@code minter} is configured only that
 *       member's mints apply (others are no-ops).</li>
 *   <li>TRANSFER debits the sender's account (key = sender pubkey hex) and
 *       credits {@code to}; rejected as a no-op if the sender has insufficient
 *       balance — balances never go negative.</li>
 * </ul>
 * State entry (CBOR): {@code "b/" + account → amount(uint big-endian)}.
 * <p>
 * Use cases: netting, loyalty points, internal credits, x402 receipt balances.
 */
public final class BalancesStateMachine implements AppStateMachine {

    public static final String ID = "balances";
    public static final int OP_MINT = 0;
    public static final int OP_TRANSFER = 1;

    private final BalancesTransitions transitions;
    private static final ProofSubjectProvider PROOF_SUBJECT =
            StdlibProofSubjectProviders.balances();

    public BalancesStateMachine() {
        this("");
    }

    public BalancesStateMachine(String minterHex) {
        String normalized = minterHex != null ? minterHex.trim().toLowerCase() : "";
        if (!normalized.isEmpty() && !normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "machines.balances.minter must be a 32-byte hex Ed25519 member public key: " + minterHex);
        }
        this.transitions = new BalancesTransitions(normalized);
    }

    /** Exposes the same pure decision used by standalone execution, with the versioned composition event schema. */
    @Override public Optional<TransitionKernel<?, ?>> transitionKernel() {
        return Optional.of(StockTransitionKernels.balances(transitions));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AppCapabilityManifest capabilityManifest() {
        return StdlibCapabilityManifests.component(
                        ID, org.yanoproject.x.stdlib.contracts
                                .BalancesContract.DEFAULT_TOPIC)
                .proofSubject(StdlibProofSubjectProviders.manifest(PROOF_SUBJECT, "b/"))
                .build();
    }

    @Override
    public List<ProofSubjectProvider> proofSubjectProviders() {
        return List.of(PROOF_SUBJECT);
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        try {
            Command.decode(message.getBody());
            return AdmissionResult.accept();
        } catch (Exception e) {
            return AdmissionResult.reject("Malformed balances command: " + e.getMessage());
        }
    }

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                      AppEffectEmitter effects) {
        int visibleIndex = 0;
        for (AppMessage message : context.messages()) {
            int originalIndex = context.originalMessageIndex(visibleIndex++);
            BalancesContract.Command command;
            try {
                command = BalancesContract.decodeCommand(message.getBody());
            } catch (Exception e) {
                continue;
            }
            TransitionContext transition = TransitionContext.of(context.block(), originalIndex, message);
            TransitionPlans.commitIfApproved(transitions.decide(command, transition,
                    BalancesTransitions.facts(command, transition, writer)), writer, effects);
        }
    }

    // ------------------------------------------------------------------
    // Client/helper encoding + queries
    // ------------------------------------------------------------------

    public static byte[] mint(String toAccount, BigInteger amount) {
        return BalancesContract.mint(toAccount, amount);
    }

    public static byte[] transfer(String toAccount, BigInteger amount) {
        return BalancesContract.transfer(toAccount, amount);
    }

    public static byte[] accountKey(String account) {
        return BalancesContract.accountKey(account);
    }

    public static BigInteger decodeBalance(byte[] entry) {
        return BalancesContract.decodeBalance(entry);
    }

    // ------------------------------------------------------------------

    record Command(int op, String to, BigInteger amount) {
        static Command decode(byte[] body) {
            BalancesContract.Command decoded = BalancesContract.decodeCommand(body);
            return new Command(decoded.operation(), decoded.account(), decoded.amount());
        }
    }
}
