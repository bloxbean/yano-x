package org.yanoproject.x.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import org.yanoproject.api.appchain.observation.ObservationReport;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationQualificationCadenceTest {
    @Test
    void operatorWaitBudgetIsBoundedWithoutChangingLogicalRoundDeadlines() {
        assertThat(ObservationQualificationBaseline.checkpointTimeout("300").toSeconds()).isEqualTo(300);
        assertThat(ObservationQualificationBaseline.checkpointTimeout("1800").toSeconds()).isEqualTo(1800);
        for (String invalid : new String[]{"0", "-1", "1801", "unbounded"}) {
            assertThatThrownBy(() -> ObservationQualificationBaseline.checkpointTimeout(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void recoveryRequiresExactlyTheOpeningBoundaryAndOneOpenRound() {
        assertThat(ObservationQualificationCadence.validStartingBoundary(42, 4, 1, true)).isTrue();
        assertThat(ObservationQualificationCadence.validStartingBoundary(41, 4, 0, false)).isTrue();
        assertThat(ObservationQualificationCadence.validStartingBoundary(42, 4, 1, false)).isFalse();
        assertThat(ObservationQualificationCadence.validStartingBoundary(41, 4, 1, true)).isFalse();
        assertThat(ObservationQualificationCadence.validStartingBoundary(43, 4, 1, true)).isFalse();
        assertThat(ObservationQualificationCadence.validStartingBoundary(42, 4, 0, true)).isFalse();
        assertThat(ObservationQualificationCadence.validStartingBoundary(42, 4, 2, true)).isFalse();
    }

    @Test
    void adversarialModeSignsTwoConflictingClaimsForOnlyOnePinnedSubject() {
        byte[] seed = new byte[32];
        seed[0] = 17;
        byte[] key = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        var template = new ObservationReport(1, new byte[32], "fixture", new byte[32], new byte[32],
                new byte[32], new byte[32], 5, new byte[32], new byte[32], key,
                new byte[]{2}, new byte[]{1}, new byte[0], new byte[]{1}, 0, 52, new byte[64]);
        var reports = ObservationQualificationCadence.adversarialReports(template, seed);
        assertThat(reports).hasSize(2);
        assertThat(reports.get(0).value()).isNotEqualTo(reports.get(1).value());
        for (var report : reports) {
            assertThat(report.reporterPublicKey()).isEqualTo(key);
            assertThat(report.sourceId()).isEqualTo(template.sourceId());
            assertThat(report.roundNumber()).isEqualTo(5);
            assertThat(report.subscriptionId()).isEqualTo(template.subscriptionId());
            assertThat(CryptoConfiguration.INSTANCE.getSigningProvider().verify(
                    report.signature(), report.signingDigest(), key)).isTrue();
        }
        assertThatThrownBy(() -> ObservationQualificationCadence.adversarialReports(template, new byte[32]))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");
    }

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
