package org.yanoproject.x.framework.runtime.appchain;

import org.yanoproject.x.framework.api.appchain.AppChainConfig;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentProfiles;

import java.util.Map;

/**
 * Node-local, restart-required proof-retention policy. It never enters consensus identity.
 *
 * <p>{@code l1StableAnchorFloor} (ADR-059 framework addition 2, D8b, D8h §5) keeps every
 * proof from the latest L1-stable anchor height onward:
 * {@code retainFrom = min(tip-derived retention, latest L1-stable anchor height)}. While no
 * anchor is L1-stable yet, nothing is pruned.</p>
 */
record StateProofPruningSettings(boolean enabled, long retainHeights, long intervalSeconds,
                                 boolean l1StableAnchorFloor) {
    static final String ENABLED = "state.proof-pruning.enabled";
    static final String RETAIN_HEIGHTS = "state.proof-pruning.retain-heights";
    static final String INTERVAL_SECONDS = "state.proof-pruning.interval-seconds";
    static final String L1_STABLE_ANCHOR_FLOOR = "state.proof-pruning.l1-stable-anchor-floor";
    static final long DEFAULT_RETAIN_HEIGHTS = 10_000;
    static final long DEFAULT_INTERVAL_SECONDS = 3_600;

    static StateProofPruningSettings from(AppChainConfig config) {
        Map<String, String> settings = config.pluginSettings();
        boolean enabled = Boolean.parseBoolean(settings.getOrDefault(ENABLED, "false"));
        long retainHeights = parsePositive(settings, RETAIN_HEIGHTS,
                DEFAULT_RETAIN_HEIGHTS);
        long intervalSeconds = parsePositive(settings, INTERVAL_SECONDS,
                DEFAULT_INTERVAL_SECONDS);
        String floor = settings.getOrDefault(L1_STABLE_ANCHOR_FLOOR, "false").trim();
        if (!"true".equalsIgnoreCase(floor) && !"false".equalsIgnoreCase(floor)) {
            throw new IllegalArgumentException(L1_STABLE_ANCHOR_FLOOR + " must be true or false");
        }
        boolean l1StableAnchorFloor = Boolean.parseBoolean(floor);
        if (enabled && !StateCommitmentProfiles.MPF.id().equals(
                StateCommitmentIdentity.fromSettings(settings).profile().id())) {
            throw new IllegalArgumentException(
                    "state.proof-pruning.enabled=true is supported only by mpf-blake2b256-v1");
        }
        return new StateProofPruningSettings(enabled, retainHeights, intervalSeconds,
                l1StableAnchorFloor);
    }

    long retainFrom(long tipHeight) {
        if (tipHeight <= 0) {
            return 0;
        }
        return Math.max(1, tipHeight - retainHeights + 1);
    }

    /**
     * The first height to keep proofs for; {@code 0} means prune nothing. With the
     * L1-stable anchor floor, it never exceeds {@code stableAnchorHeight}, and an absent
     * stable anchor ({@code <= 0}) prunes nothing.
     */
    long retainFrom(long tipHeight, long stableAnchorHeight) {
        long tipDerived = retainFrom(tipHeight);
        if (!l1StableAnchorFloor || tipDerived == 0) {
            return tipDerived;
        }
        if (stableAnchorHeight <= 0) {
            return 0;
        }
        return Math.min(tipDerived, stableAnchorHeight);
    }

    private static long parsePositive(Map<String, String> settings, String key,
                                      long defaultValue) {
        String configured = settings.get(key);
        long value;
        try {
            value = configured == null || configured.isBlank()
                    ? defaultValue : Long.parseLong(configured.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " must be a positive integer", failure);
        }
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
        return value;
    }
}
