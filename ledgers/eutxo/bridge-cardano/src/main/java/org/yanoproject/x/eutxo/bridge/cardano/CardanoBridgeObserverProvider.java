package org.yanoproject.x.eutxo.bridge.cardano;

import org.yanoproject.api.appchain.l1view.L1Observer;
import org.yanoproject.api.appchain.l1view.L1ObserverConsensusIdentity;
import org.yanoproject.api.appchain.l1view.L1ObserverProvider;

import java.util.Map;

/** Service-loaded exact observer for stable, accepted EUTxO vault deposits. */
public final class CardanoBridgeObserverProvider implements L1ObserverProvider {
    public static final String TYPE = "eutxo-vault-deposit-v1";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public L1ObserverConsensusIdentity consensusIdentity(
            String observerId, Map<String, String> settings) {
        return new AcceptedVaultDepositObserver(observerId, settings).consensusIdentity();
    }

    @Override
    public L1Observer create(String observerId, Map<String, String> settings) {
        return new AcceptedVaultDepositObserver(observerId, settings);
    }
}
