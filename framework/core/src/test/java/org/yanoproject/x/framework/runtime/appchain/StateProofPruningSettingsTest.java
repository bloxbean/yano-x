package org.yanoproject.x.framework.runtime.appchain;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateProofPruningSettingsTest {
    @Test
    void defaultsToDisabledAndCalculatesAContiguousHorizon() {
        StateProofPruningSettings defaults = StateProofPruningSettings.from(config(
                StateCommitmentProfiles.MPF, Map.of()));
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.retainHeights()).isEqualTo(10_000);
        assertThat(defaults.retainFrom(8_000)).isEqualTo(1);

        StateProofPruningSettings enabled = StateProofPruningSettings.from(config(
                StateCommitmentProfiles.MPF, Map.of(
                        StateProofPruningSettings.ENABLED, "true",
                        StateProofPruningSettings.RETAIN_HEIGHTS, "3",
                        StateProofPruningSettings.INTERVAL_SECONDS, "7")));
        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.retainFrom(8)).isEqualTo(6);
        assertThat(enabled.intervalSeconds()).isEqualTo(7);
    }

    @Test
    void enabledPruningRejectsJmtAndNonPositiveBounds() {
        assertThatThrownBy(() -> StateProofPruningSettings.from(config(
                StateCommitmentProfiles.CLASSIC_JMT,
                Map.of(StateProofPruningSettings.ENABLED, "true"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only by mpf");
        assertThatThrownBy(() -> StateProofPruningSettings.from(config(
                StateCommitmentProfiles.MPF,
                Map.of(StateProofPruningSettings.RETAIN_HEIGHTS, "0"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StateProofPruningSettings.RETAIN_HEIGHTS);
    }

    /** ADR-059 framework addition 2: retainFrom = min(tip retention, L1-stable anchor height). */
    @Test
    void l1StableAnchorFloorKeepsProofsFromTheStableAnchorAndPrunesNothingWithoutOne() {
        StateProofPruningSettings plain = StateProofPruningSettings.from(config(
                StateCommitmentProfiles.MPF, Map.of(
                        StateProofPruningSettings.ENABLED, "true",
                        StateProofPruningSettings.RETAIN_HEIGHTS, "3")));
        assertThat(plain.l1StableAnchorFloor()).isFalse();
        // Without the floor the anchor height is ignored (existing behaviour).
        assertThat(plain.retainFrom(100, 0)).isEqualTo(98);
        assertThat(plain.retainFrom(100, 40)).isEqualTo(98);

        StateProofPruningSettings floored = StateProofPruningSettings.from(config(
                StateCommitmentProfiles.MPF, Map.of(
                        StateProofPruningSettings.ENABLED, "true",
                        StateProofPruningSettings.RETAIN_HEIGHTS, "3",
                        StateProofPruningSettings.L1_STABLE_ANCHOR_FLOOR, "true")));
        assertThat(floored.l1StableAnchorFloor()).isTrue();
        assertThat(floored.retainFrom(100, 40)).isEqualTo(40);
        assertThat(floored.retainFrom(100, 99)).isEqualTo(98);
        assertThat(floored.retainFrom(100, 0)).isZero();
        assertThat(floored.retainFrom(0, 40)).isZero();

        assertThatThrownBy(() -> StateProofPruningSettings.from(config(
                StateCommitmentProfiles.MPF,
                Map.of(StateProofPruningSettings.L1_STABLE_ANCHOR_FLOOR, "yes"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StateProofPruningSettings.L1_STABLE_ANCHOR_FLOOR);
    }

    private static AppChainConfig config(
            org.yanoproject.x.framework.api.appchain.state.StateCommitmentProfile profile,
            Map<String, String> settings) {
        return AppChainConfig.builder("pruning-settings")
                .signingKeyHex("")
                .stateCommitmentIdentity(StateCommitmentIdentity.explicit(
                        profile, new byte[32]))
                .pluginSettings(settings)
                .build();
    }
}
