package org.yanoproject.x.eutxo.bridge.cardano;

import org.yanoproject.api.appchain.l1view.L1Observer;
import org.yanoproject.api.appchain.l1view.L1ObserverConsensusIdentity;
import org.yanoproject.api.appchain.l1view.L1ObserverProvider;

import java.util.Map;

/** Service-loaded observer for stable, claim-bound Cardano payouts. */
public final class CardanoWithdrawalConfirmationObserverProvider
        implements L1ObserverProvider {
    public static final String TYPE = "eutxo-withdrawal-confirmation-v1";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public L1ObserverConsensusIdentity consensusIdentity(
            String observerId, Map<String, String> settings) {
        return new WithdrawalConfirmationObserver(observerId, settings).consensusIdentity();
    }

    @Override
    public L1Observer create(String observerId, Map<String, String> settings) {
        return new WithdrawalConfirmationObserver(observerId, settings);
    }
}
