package org.yanoproject.x.framework.api.appchain.membership;

import java.util.Map;

/**
 * Plugin contribution of a {@link MembershipHandoffGate} (contribution kind
 * {@code membership-handoff-gate}, ADR-059 D8b). A capability bundle that observes
 * L1-stable anchor outputs implements it and registers it in its plugin manifest; a chain
 * selects it by {@link #id()} with {@code membership.anchor-handoff-gate}, which is part of
 * the consensus profile, so every member runs the same gate.
 */
public interface MembershipHandoffGateProvider {

    /** The selector a chain names in {@code membership.anchor-handoff-gate}. */
    String id();

    /**
     * Create the gate for one chain. Called once per runtime generation, at startup, with the
     * chain id and its settings. The returned gate must be deterministic (see
     * {@link MembershipHandoffGate}) and must not be shared with another chain.
     */
    MembershipHandoffGate create(String chainId, Map<String, String> chainSettings);
}
