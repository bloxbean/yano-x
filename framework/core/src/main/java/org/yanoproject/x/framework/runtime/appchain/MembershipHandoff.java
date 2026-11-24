package org.yanoproject.x.framework.runtime.appchain;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A committed governed membership change waiting for its L1 anchor handoff
 * (ADR-059 D8b, framework addition 4). With {@code membership.anchor-handoff=true}
 * an approved change does not activate at once: it stays pending until a
 * {@link MembershipHandoffGate} reports, from finalized chain content, that an
 * L1-stable anchor output carrying exactly {@link #members()} and {@link #threshold()}
 * (signed by the old threshold) exists. Meanwhile the script-anchor service carries
 * this set as the anchor's next member set.
 *
 * <p>Public for the optional Cardano module within this framework release. It is an
 * implementation collaboration surface, not the Yano Host API or a plugin contract.</p>
 *
 * @param committedHeight the height of the block whose approvals committed the change
 * @param members         the next member set (lowercase hex Ed25519 keys)
 * @param threshold       the next threshold
 * @param activationLag   blocks between gate finalization and the new epoch (at least 1)
 */
public record MembershipHandoff(long committedHeight, Set<String> members, int threshold,
                                long activationLag) {
    /** The opt-in chain setting: {@code membership.anchor-handoff: true}. */
    public static final String SETTING = "membership.anchor-handoff";

    /** Is the handoff gate selected? Only {@code true} and {@code false} (default) are valid. */
    public static boolean selected(java.util.Map<String, String> settings) {
        String value = settings == null ? "false" : settings.getOrDefault(SETTING, "false").trim();
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(SETTING + " must be true or false");
        }
        return Boolean.parseBoolean(value);
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
