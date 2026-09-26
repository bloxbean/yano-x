package org.yanoproject.x.stdlib;

import co.nstant.in.cbor.model.ByteString;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.transition.StateMutation;
import org.yanoproject.api.appchain.transition.TransitionCapability;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionPlan;
import org.yanoproject.x.stdlib.contracts.ApprovalsContract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.yanoproject.x.stdlib.ApprovalsStateMachine.OP_APPROVE;
import static org.yanoproject.x.stdlib.ApprovalsStateMachine.OP_PROPOSE;
import static org.yanoproject.x.stdlib.ApprovalsStateMachine.STATUS_APPROVED;
import static org.yanoproject.x.stdlib.ApprovalsStateMachine.STATUS_EXPIRED;
import static org.yanoproject.x.stdlib.ApprovalsStateMachine.STATUS_PENDING;
import static org.yanoproject.x.stdlib.ApprovalsStateMachine.STATUS_REJECTED;

/**
 * Pure member-approval transitions, shared by standalone effects and declarative bindings.
 * The plan does not allocate effect ids. Standalone execution attaches its legacy on-approved effect after
 * committing the domain plan; composition replaces that effect with binding-owned, preflighted intents.
 * Deadlines use the supplied consensus timestamp, and the original message sender supplies the vote identity.
 */
public final class ApprovalsTransitions implements
        TransitionCapability<ApprovalsContract.Command, ApprovalsTransitions.Facts> {
    /** Domain event selected by the decision itself, not inferred by a generic writer adapter. */
    public enum Change { NONE, PROPOSED, APPROVED, REJECTED, EXPIRED, VOTED }

    /**
     * Immutable inputs and explicit execution policy. Composition stages every proposal payload and consumes
     * it on approval; standalone execution stages only while its legacy effect feature is active and lets
     * the existing effect adapter remove that payload after emission.
     */
    public record Facts(Optional<ApprovalsStateMachine.Item> item, Optional<byte[]> stagedPayload,
                        boolean stageProposal, boolean consumeOnApproval) {
        public Facts {
            item = item.map(value -> ApprovalsStateMachine.Item.decode(value.encode()));
            stagedPayload = stagedPayload.map(byte[]::clone);
        }
        @Override public Optional<byte[]> stagedPayload() { return stagedPayload.map(byte[]::clone); }
    }

    /** Complete domain decision plus its typed event projection; {@code item} is null only for no change. */
    public record Result(TransitionPlan plan, Change change, ApprovalsStateMachine.Item item) { }

    /** Reads exact item/payload keys from the caller's component view, never from a node-local index. */
    public static Facts facts(ApprovalsContract.Command command, AppStateReader state,
                              boolean stageProposal, boolean consumeOnApproval) {
        return new Facts(state.get(ApprovalsContract.itemKey(command.itemId())).map(ApprovalsStateMachine.Item::decode),
                state.get(ApprovalsContract.stagedEffectPayloadKey(command.itemId()))
                        .map(ApprovalsTransitions::payload),
                stageProposal, consumeOnApproval);
    }

    @Override
    public TransitionDecision decide(ApprovalsContract.Command command, TransitionContext context, Facts facts) {
        return TransitionDecision.approve(evaluate(command, context, facts).plan());
    }

    /**
     * Applies proposal idempotency, terminal-state immutability, deadline expiry and distinct-member voting.
     * No writes or effects occur here. A duplicate/unknown/terminal command produces an empty plan, preserving
     * the standalone no-op rules. Only the threshold-crossing vote produces {@link Change#APPROVED}.
     */
    public Result evaluate(ApprovalsContract.Command command, TransitionContext context, Facts facts) {
        if (command.operation() < 0 || command.operation() > ApprovalsContract.OP_REJECT
                || command.operation() == OP_PROPOSE && (command.required() <= 0 || command.deadlineMillis() < 0)) {
            throw new IllegalArgumentException("invalid approval command");
        }
        String id = command.itemId();
        ApprovalsContract.effectStateKey(id);
        if (command.operation() == OP_PROPOSE) {
            if (facts.item().isPresent()) return unchanged();
            var item = new ApprovalsStateMachine.Item(STATUS_PENDING, context.sender(),
                    Blake2bUtil.blake2bHash256(command.payload()), command.required(), command.deadlineMillis(),
                    List.of(), new byte[0]);
            List<StateMutation> writes = new ArrayList<>();
            writes.add(StateMutation.put(ApprovalsContract.itemKey(id), item.encode()));
            if (facts.stageProposal()) writes.add(StateMutation.put(ApprovalsContract.stagedEffectPayloadKey(id),
                    CborSerializationUtil.serialize(new ByteString(command.payload()))));
            return new Result(TransitionPlan.mutations(writes), Change.PROPOSED, item);
        }
        if (facts.item().isEmpty()) return unchanged();
        var item = facts.item().orElseThrow();
        if (item.status() != STATUS_PENDING) return unchanged();
        if (item.deadline() > 0 && context.timestamp() > item.deadline()) {
            return changed(id, item.withStatus(STATUS_EXPIRED), Change.EXPIRED, true);
        }
        if (command.operation() == OP_APPROVE) {
            if (item.approvers().stream().anyMatch(sender -> Arrays.equals(sender, context.sender()))) {
                return unchanged();
            }
            List<byte[]> approvers = new ArrayList<>(item.approvers());
            approvers.add(context.sender());
            boolean approved = approvers.size() >= item.required();
            var updated = new ApprovalsStateMachine.Item(approved ? STATUS_APPROVED : STATUS_PENDING, item.proposer(),
                    item.payloadHash(), item.required(), item.deadline(), approvers, item.rejecter());
            return changed(id, updated, approved ? Change.APPROVED : Change.VOTED,
                    approved && facts.consumeOnApproval());
        }
        var rejected = new ApprovalsStateMachine.Item(STATUS_REJECTED, item.proposer(), item.payloadHash(),
                item.required(), item.deadline(), item.approvers(), context.sender());
        return changed(id, rejected, Change.REJECTED, true);
    }

    private static Result unchanged() { return new Result(TransitionPlan.empty(), Change.NONE, null); }

    private static Result changed(String id, ApprovalsStateMachine.Item item, Change change, boolean removePayload) {
        List<StateMutation> writes = new ArrayList<>();
        writes.add(StateMutation.put(ApprovalsContract.itemKey(id), item.encode()));
        if (removePayload) writes.add(StateMutation.delete(ApprovalsContract.stagedEffectPayloadKey(id)));
        return new Result(TransitionPlan.mutations(writes), change, item);
    }

    private static byte[] payload(byte[] encoded) {
        StdlibCbor.requirePersistedEntry(encoded);
        if (!(CborSerializationUtil.deserializeOne(encoded) instanceof ByteString bytes)) {
            throw new IllegalArgumentException("invalid staged approval effect payload");
        }
        return bytes.getBytes();
    }
}
