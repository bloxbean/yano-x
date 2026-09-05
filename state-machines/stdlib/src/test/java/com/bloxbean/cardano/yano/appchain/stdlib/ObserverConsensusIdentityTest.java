package com.bloxbean.cardano.yano.appchain.stdlib;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObserverConsensusIdentityTest {
    @Test
    void everyEpochProviderDeclaresItsCanonicalIdentity() {
        assertThat(new EpochParamsObserverProvider()
                .consensusIdentity("params", Map.of()).canonicalIdentityBytes()).isNotEmpty();
        assertThat(new EpochStakeObserverProvider()
                .consensusIdentity("stake", Map.of()).canonicalIdentityBytes()).isNotEmpty();
        assertThat(new EpochGovernanceObserverProvider()
                .consensusIdentity("governance", Map.of()).canonicalIdentityBytes()).isNotEmpty();
    }

    @Test
    void equivalentDefaultsMatchAndConsensusSettingsChangeIdentity() {
        EpochStakeObserverProvider provider = new EpochStakeObserverProvider();
        byte[] defaults = provider.consensusIdentity("stake", Map.of()).canonicalIdentityBytes();

        assertThat(provider.consensusIdentity(
                "stake", Map.of("chunk-entries", "25000")).canonicalIdentityBytes())
                .containsExactly(defaults);
        assertThat(provider.consensusIdentity(
                "stake", Map.of("chunk-entries", "1000")).canonicalIdentityBytes())
                .isNotEqualTo(defaults);
    }

    @Test
    void governanceIdentityPinsPreConwayEmptyDatasetSemantics() {
        var actual = new EpochGovernanceObserverProvider()
                .consensusIdentity("governance", Map.of());

        assertThat(actual.claimSchema()).isEqualTo("epoch-governance-observation-v2");
    }

    @Test
    void stakeIdentityPinsPreShelleyEmptyDatasetSemantics() {
        var actual = new EpochStakeObserverProvider()
                .consensusIdentity("stake", Map.of());

        assertThat(actual.claimSchema()).isEqualTo("epoch-stake-observation-v2");
    }
}
