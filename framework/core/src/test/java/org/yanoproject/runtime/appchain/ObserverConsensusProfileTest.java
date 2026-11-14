package org.yanoproject.runtime.appchain;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.l1view.L1ObserverConsensusIdentity;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObserverConsensusProfileTest {
    @Test
    void emptyObserverIdentityRetainsTheExistingConsensusBytes() {
        byte[] network = ObserverConsensusProfile.networkGenesisId(Map.of(), 424242);
        assertThat(HexFormat.of().formatHex(network))
                .isEqualTo("97a2093ee107f26c65563cd8f011e25ed99dad28ec87c29c673df2818ba55b69");
        byte[] profile = ObserverConsensusProfile.digest(Map.of(), (id, settings) -> {
            throw new AssertionError("Empty observer configuration must not load a Cardano provider");
        }, network);
        assertThat(HexFormat.of().formatHex(profile))
                .isEqualTo("6dfe78db0afe82fbbfd421c2184a377c17cebe2b799af291f04d2931e0aee6fa");
    }

    @Test
    void observerOrderIsCanonicalAndIdentityChangesRemainConsensusVisible() {
        var settings = new LinkedHashMap<String, String>();
        settings.put("observers.z.type", "custom");
        settings.put("observers.a.type", "custom");
        byte[] first = ObserverConsensusProfile.digest(settings,
                (id, values) -> new L1ObserverConsensusIdentity(1, "claims", 1, new byte[]{1}), new byte[32]);
        byte[] reordered = ObserverConsensusProfile.digest(settings.reversed(),
                (id, values) -> new L1ObserverConsensusIdentity(1, "claims", 1, new byte[]{1}), new byte[32]);
        byte[] changed = ObserverConsensusProfile.digest(settings,
                (id, values) -> new L1ObserverConsensusIdentity(1, "claims", 2, new byte[]{1}), new byte[32]);
        assertThat(first).isEqualTo(reordered).isNotEqualTo(changed);
        assertThatThrownBy(() -> ObserverConsensusProfile.networkGenesisId(settings, 424242))
                .hasMessageContaining("observation.l1-network-genesis-id");
    }
}
