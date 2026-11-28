package org.yanoproject.x.assets.cardano.gateway;

import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverConsensusIdentity;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverProvider;

import java.util.Map;

/**
 * Service-loaded {@code asset-shard-bootstrap-v1} observer (ADR-059 D8c). It activates only through
 * {@code yano.app-chain.observers.<id>.type=asset-shard-bootstrap-v1}; installing the bundle alone changes nothing.
 * It takes the same settings as {@code asset-vault-payout-v1}.
 */
public final class AssetShardBootstrapObserverProvider implements L1ObserverProvider {
    public static final String TYPE = "asset-shard-bootstrap-v1";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public L1ObserverConsensusIdentity consensusIdentity(String observerId, Map<String, String> settings) {
        return new AssetShardBootstrapObserver(observerId, settings).consensusIdentity();
    }

    @Override
    public L1Observer create(String observerId, Map<String, String> settings) {
        return new AssetShardBootstrapObserver(observerId, settings);
    }
}
