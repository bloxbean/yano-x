package org.yanoproject.x.stdlib;

import org.yanoproject.api.appchain.l1view.L1EpochObserver;
import org.yanoproject.api.appchain.l1view.L1EpochObserverProvider;
import org.yanoproject.api.appchain.l1view.L1ObserverConsensusIdentity;
import org.yanoproject.x.stdlib.contracts.EpochGovernanceContract;

import java.util.Map;

/** Plugin provider for the out-of-box governance-history observer. */
public final class EpochGovernanceObserverProvider implements L1EpochObserverProvider {
    @Override
    public String type() {
        return EpochGovernanceContract.OBSERVER_TYPE;
    }

    @Override
    public L1ObserverConsensusIdentity consensusIdentity(
            String observerId, Map<String, String> settings) {
        Config config = config(settings);
        return ObserverConsensusIdentity.of("epoch-governance-observation-v2",
                "include-proposals", Boolean.toString(config.proposals()),
                "include-drep-distribution", Boolean.toString(config.dreps()),
                "drep-chunk-entries", Integer.toString(config.chunks()));
    }
    @Override
    public L1EpochObserver create(String observerId, Map<String, String> settings) {
        Config config = config(settings);
        return new EpochGovernanceObserver(
                observerId, config.proposals(), config.dreps(), config.chunks());
    }
    private static Config config(Map<String, String> settings) {
        boolean proposals = strictBoolean(settings, "include-proposals", true);
        boolean dreps = strictBoolean(settings, "include-drep-distribution", false);
        int chunks;
        try {
            chunks = Integer.parseInt(settings.getOrDefault("drep-chunk-entries",
                    Integer.toString(EpochGovernanceContract.DEFAULT_DREP_CHUNK_ENTRIES)));
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(
                    "drep-chunk-entries must be an integer", malformed);
        }
        return new Config(proposals, dreps, chunks);
    }

    private static boolean strictBoolean(Map<String, String> settings, String key, boolean fallback) {
        String value = settings.get(key);
        if (value == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be true or false");
    }

    private record Config(boolean proposals, boolean dreps, int chunks) { }
}
