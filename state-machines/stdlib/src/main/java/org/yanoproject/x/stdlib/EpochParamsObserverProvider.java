package org.yanoproject.x.stdlib;

import org.yanoproject.api.appchain.l1view.L1EpochObserver;
import org.yanoproject.api.appchain.l1view.L1EpochObserverProvider;
import org.yanoproject.api.appchain.l1view.L1ObserverConsensusIdentity;
import org.yanoproject.x.stdlib.contracts.EpochParamsContract;

import java.util.Map;

/** Plugin provider for the out-of-box epoch-params observer. */
public final class EpochParamsObserverProvider implements L1EpochObserverProvider {
    @Override public String type() { return EpochParamsContract.OBSERVER_TYPE; }
    @Override
    public L1ObserverConsensusIdentity consensusIdentity(
            String observerId, Map<String, String> settings) {
        return ObserverConsensusIdentity.of("epoch-params-claim-v1");
    }
    @Override public L1EpochObserver create(String observerId, Map<String, String> settings) {
        return new EpochParamsObserver(observerId);
    }
}
