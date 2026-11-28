package org.yanoproject.x.assets.cardano.gateway;

import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverConsensusIdentity;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverProvider;

import java.util.Map;

/**
 * Service-loaded {@code asset-vault-deposit-v1} observer (ADR-059 D5). It activates only through
 * {@code yano.app-chain.observers.<id>.type=asset-vault-deposit-v1}; installing the bundle alone
 * changes nothing (D1, I14).
 */
public final class AssetVaultDepositObserverProvider implements L1ObserverProvider {
    public static final String TYPE = "asset-vault-deposit-v1";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public L1ObserverConsensusIdentity consensusIdentity(String observerId, Map<String, String> settings) {
        return new AssetVaultDepositObserver(observerId, settings).consensusIdentity();
    }

    @Override
    public L1Observer create(String observerId, Map<String, String> settings) {
        return new AssetVaultDepositObserver(observerId, settings);
    }
}
