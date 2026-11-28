package org.yanoproject.x.assets.cardano.gateway;

import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoffGate;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoffGateProvider;

import java.util.Map;

/**
 * The bundle's {@code membership-handoff-gate} contribution (ADR-059 D8b, framework addition 4). A chain selects it
 * with {@code membership.anchor-handoff: true} and {@code membership.anchor-handoff-gate: asset-anchor-handoff-v1},
 * both consensus-committed, and configures one {@code asset-anchor-v1} observer for the pinned anchor identity. See
 * {@link AnchorHandoffGate} for the rule and the startup checks.
 */
public final class AnchorHandoffGateProvider implements MembershipHandoffGateProvider {
    public static final String ID = "asset-anchor-handoff-v1";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public MembershipHandoffGate create(String chainId, Map<String, String> chainSettings) {
        return AnchorHandoffGate.create(chainSettings);
    }
}
