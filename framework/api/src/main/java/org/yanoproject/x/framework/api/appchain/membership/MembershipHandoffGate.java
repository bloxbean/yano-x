package org.yanoproject.x.framework.api.appchain.membership;

import org.yanoproject.x.framework.api.appchain.AppBlock;

/**
 * The consensus side of the ADR-059 D8b membership handoff (framework addition 4).
 *
 * <p>Called once per finalized block, on every member, before the block's governance
 * commands are counted, while a {@link MembershipHandoff} is pending. It must be a
 * deterministic function of the finalized block and the handoff (for example of the
 * block's certified L1 observation inputs): it returns true only when the block carries
 * a finalized fact that an L1-stable anchor output with exactly the handoff's member set
 * and threshold exists. Node-local L1 views must never decide it, or members would
 * diverge.</p>
 *
 * <p><b>Bind the fact to this handoff.</b> A member set can recur: a handoff can be
 * cancelled and the same change re-proposed, or the anchor can already carry an equal
 * set from earlier. A gate must therefore accept only an anchor output that was produced
 * for this pending change, for example by requiring the anchor datum's app height to be
 * at least {@link MembershipHandoff#committedHeight()} (an advance that carries a pending
 * set is always built at or above the height that committed it). Matching the member set
 * and threshold alone would let a stale anchor output finalize a re-proposed handoff.</p>
 *
 * <p><b>Be total.</b> On any finalized block the gate must return (false when the block
 * carries no matching fact) and never throw. The call is part of committing the block, so
 * a gate that throws, or a gate bundle that is not loaded, fails the block commit on that
 * member: the node stops (fail-stop), exactly as a throwing state machine does.</p>
 *
 * <p>Gates are contributed by a capability bundle through
 * {@link MembershipHandoffGateProvider} (contribution kind
 * {@code membership-handoff-gate}) and selected per chain with the consensus-committed
 * {@code membership.anchor-handoff-gate} setting. The framework ships only {@link #NONE},
 * which it never installs: startup fails when {@code membership.anchor-handoff=true}
 * selects no registered gate, so a pending change can never be stranded.</p>
 */
@FunctionalInterface
public interface MembershipHandoffGate {

    /** Never finalizes a handoff. */
    MembershipHandoffGate NONE = (block, handoff) -> false;

    /** Does this finalized block finalize the pending handoff? */
    boolean finalizesHandoff(AppBlock block, MembershipHandoff handoff);
}
