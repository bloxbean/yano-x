package com.bloxbean.cardano.yano.appchain.devtools;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationQualificationCadenceTest {
    @Test
    void faultPlansNeverAccidentallySignTwiceForTheSameReporterSource() {
        for (var scenario : ObservationQualificationCadence.Scenario.values()) {
            var subjects = new HashSet<String>();
            for (var claim : ObservationQualificationCadence.claims(scenario)) {
                assertThat(subjects.add(claim.reporter() + "/" + claim.source())).isTrue();
            }
        }
    }

    @Test
    void unavailableAndDisagreeingSourcesCannotReachFourMatchingReports() {
        var unavailable = ObservationQualificationCadence.claims(
                ObservationQualificationCadence.Scenario.SOURCE_UNAVAILABLE);
        assertThat(unavailable).hasSize(8).noneMatch(claim -> claim.source() == 2);
        var split = ObservationQualificationCadence.claims(
                ObservationQualificationCadence.Scenario.SOURCE_DISAGREEMENT);
        assertThat(split).hasSize(12);
        assertThat(split.stream().filter(claim -> claim.source() == 2 && claim.value().equals("0.502000"))).hasSize(2);
        assertThat(split.stream().filter(claim -> claim.source() == 2 && claim.value().equals("0.503000"))).hasSize(2);
        assertThat(ObservationQualificationCadence.expectsExpiry(
                ObservationQualificationCadence.Scenario.SOURCE_DISAGREEMENT)).isTrue();
    }

    @Test
    void boundedCadenceAlternatesFailuresAndRecovery() {
        assertThat(ObservationQualificationCadence.scenario(1))
                .isEqualTo(ObservationQualificationCadence.Scenario.SOURCE_UNAVAILABLE);
        assertThat(ObservationQualificationCadence.scenario(3))
                .isEqualTo(ObservationQualificationCadence.Scenario.DELAYED_REPORTER);
        assertThat(ObservationQualificationCadence.scenario(4))
                .isEqualTo(ObservationQualificationCadence.Scenario.COMPLETE);
        assertThatThrownBy(() -> ObservationQualificationCadence.scenario(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObservationQualificationCadence.scenario(100)).isInstanceOf(IllegalArgumentException.class);
    }
}
