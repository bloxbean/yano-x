package org.yanoproject.x.composite;

import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;

/** Capability-limited views for only the participant generations declared by a workflow. */
public interface CompositeWorkflowContext {
    enum ClaimResult {
        CLAIMED,
        EXACT_REPLAY,
        CONFLICT
    }

    AppStateWriter state(ComponentGeneration participant);

    AppEffectEmitter effects(ComponentGeneration owner);

    /** Authenticated state scoped to the workflow id, retained across profile epochs. */
    default AppStateWriter workflowState() {
        throw new UnsupportedOperationException("workflow state is unavailable");
    }

    /** Remaining reserved quota, including effects emitted by prior cascades in this block. */
    default int remainingEffectCapacity() {
        throw new UnsupportedOperationException("workflow quota view is unavailable");
    }

    /**
     * Claim one stable workflow operation id inside the current atomic block.
     * The existing claim is never overwritten. Callers must treat both replay
     * outcomes as deterministic business no-ops rather than throwing from
     * block application and repeatedly poisoning proposer selection.
     */
    ClaimResult claim(String operationId, byte[] commandHash);
}
