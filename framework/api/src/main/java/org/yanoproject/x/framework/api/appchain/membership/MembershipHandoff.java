package org.yanoproject.x.framework.api.appchain.membership;

import org.yanoproject.x.framework.api.appchain.AppChainConsensusProfile;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A committed governed membership change waiting for its L1 anchor handoff
 * (ADR-059 D8b, framework addition 4). With {@code membership.anchor-handoff=true}
 * an approved change does not activate at once: it stays pending until the chain's
 * {@link MembershipHandoffGate} reports, from finalized chain content, that an
 * L1-stable anchor output carrying exactly {@link #members()} and {@link #threshold()}
 * (signed by the old threshold) exists for this change. Meanwhile the script-anchor
 * service carries this set as the anchor's next member set.
 *
 * <p>Part of the {@code membership-handoff-gate} contribution contract
 * ({@link MembershipHandoffGateProvider}).</p>
 *
 * @param committedHeight the height of the block whose approvals committed the change
 * @param members         the next member set (lowercase hex Ed25519 keys)
 * @param threshold       the next threshold
 * @param activationLag   blocks between gate finalization and the new epoch (at least 1)
 */
public record MembershipHandoff(long committedHeight, Set<String> members, int threshold,
                                long activationLag) {
    /** The opt-in chain setting: {@code membership.anchor-handoff: true}. */
    public static final String SETTING = AppChainConsensusProfile.MEMBERSHIP_ANCHOR_HANDOFF_SETTING;
    /** The chain setting naming the registered gate: {@code membership.anchor-handoff-gate: <id>}. */
    public static final String GATE_SETTING = AppChainConsensusProfile.MEMBERSHIP_HANDOFF_GATE_SETTING;

    /**
     * Is the handoff gate selected? Only {@code true} and {@code false} (default) are valid.
     * The switch is consensus-committed: the runtime reads it from the chain's consensus
     * profile ({@link AppChainConsensusProfile#membershipAnchorHandoff()}), which this parse
     * feeds.
     */
    public static boolean selected(java.util.Map<String, String> settings) {
        return AppChainConsensusProfile.membershipAnchorHandoff(settings);
    }

    public MembershipHandoff {
        Objects.requireNonNull(members, "members");
        if (committedHeight < 1 || members.isEmpty() || threshold < 1 || threshold > members.size()
                || activationLag < 1
                || members.stream().anyMatch(key -> key == null || !key.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Invalid membership handoff");
        }
        members = Set.copyOf(new TreeSet<>(members));
    }

    /** True when {@code keysHex} (any case) and {@code threshold} are exactly this handoff's set. */
    public boolean sameSet(Set<String> keysHex, int otherThreshold) {
        if (keysHex == null || otherThreshold != threshold || keysHex.size() != members.size()) {
            return false;
        }
        for (String key : keysHex) {
            if (key == null || !members.contains(key.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
