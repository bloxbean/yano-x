package org.yanoproject.x.eutxo.bridge.cardano;

import org.yanoproject.api.appchain.l1view.L1Observer;
import org.yanoproject.api.appchain.l1view.L1ObserverConsensusIdentity;
import org.yanoproject.api.appchain.l1view.L1ObserverProvider;

import java.util.Map;

/** Service-loaded observer for A2 batch settlements (ADR-UTXO-009 v3 chains). */
public final class CardanoBatchWithdrawalConfirmationObserverProvider
        implements L1ObserverProvider {
    public static final String TYPE = "eutxo-batch-withdrawal-confirmation-v1";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public L1ObserverConsensusIdentity consensusIdentity(
            String observerId, Map<String, String> settings) {
        return new BatchWithdrawalConfirmationObserver(observerId, settings).consensusIdentity();
    }

    @Override
    public L1Observer create(String observerId, Map<String, String> settings) {
        return new BatchWithdrawalConfirmationObserver(observerId, settings);
    }
}
