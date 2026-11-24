package org.yanoproject.x.framework.runtime.appchain;

import org.yanoproject.x.framework.api.appchain.AppBlock;

/**
 * The consensus side of the ADR-059 D8b membership handoff (framework addition 4).
 *
 * <p>Called once per finalized block, on every member, before the block's governance
 * commands are counted. It must be a deterministic function of the finalized block
 * (for example its certified L1 observation inputs): it returns true only when the
 * block carries a finalized fact that an L1-stable anchor output with exactly the
 * handoff's member set and threshold exists. Node-local L1 views must never decide it,
 * or members would diverge.</p>
 *
 * <p>The framework ships only {@link #NONE}. The fact source, an anchor observer such
 * as ADR-059's {@code asset-anchor-v1}, belongs to the capability that needs the gate
 * and installs it through {@code AppLedgerRuntime#membershipHandoffGate()}. Until one
 * is installed, a chain with {@code membership.anchor-handoff=true} keeps every
 * committed change pending, and the old members keep authority (fail-safe).</p>
 */
@FunctionalInterface
public interface MembershipHandoffGate {

    /** Never finalizes a handoff. */
    MembershipHandoffGate NONE = (block, handoff) -> false;

    /** Does this finalized block finalize the pending handoff? */
    boolean finalizesHandoff(AppBlock block, MembershipHandoff handoff);
}
