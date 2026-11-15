package org.yanoproject.x.framework.runtime.appchain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceLimitsTest {
    @Test
    void evidenceChainLimitCountsInclusiveEndpoints() {
        assertThat(AppLedgerRuntime.evidenceChainFits(10, 4_105)).isTrue();
        assertThat(AppLedgerRuntime.evidenceChainFits(10, 4_106)).isFalse();
        assertThat(AppLedgerRuntime.evidenceChainFits(0, 1)).isFalse();
        assertThat(AppLedgerRuntime.evidenceChainFits(10, 9)).isFalse();
        assertThat(AppLedgerRuntime.evidenceBytesFit(0, 100, 100)).isTrue();
        assertThat(AppLedgerRuntime.evidenceBytesFit(99, 1, 100)).isTrue();
        assertThat(AppLedgerRuntime.evidenceBytesFit(100, 1, 100)).isFalse();
        assertThat(AppLedgerRuntime.evidenceBytesFit(
                Long.MAX_VALUE, 1, Long.MAX_VALUE)).isFalse();
        assertThat(AppLedgerRuntime.evidenceBytesFit(-1, 1, 100)).isFalse();
    }

}
