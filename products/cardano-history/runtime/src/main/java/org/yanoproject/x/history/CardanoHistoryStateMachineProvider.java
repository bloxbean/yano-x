package org.yanoproject.x.history;

import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateMachineProvider;

/** Thin product provider that resolves a released preset to reusable ADR-028 components. */
public final class CardanoHistoryStateMachineProvider implements AppStateMachineProvider {
    @Override public String id() { return CardanoHistoryProduct.STATE_MACHINE_ID; }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("cardano-history requires chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        return CardanoHistoryStateMachines.create(context);
    }
}
