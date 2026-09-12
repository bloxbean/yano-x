package org.yanoproject.x.eutxo.zk.runtime;

import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.x.eutxo.ledger.EutxoStateMachineProvider;
import org.yanoproject.x.eutxo.zk.zeroj.ZerojPoseidonValidityProvider;

/** Integrated bundle root that owns the optional ZeroJ capability directly. */
public final class EutxoZkStateMachineProvider
        implements AppStateMachineProvider {
    private final EutxoStateMachineProvider delegate =
            new EutxoStateMachineProvider(new ZerojPoseidonValidityProvider());

    @Override public String id() { return delegate.id(); }
    @Override public AppStateMachine create() { return delegate.create(); }
    @Override public AppStateMachine create(AppStateMachineContext context) {
        return delegate.create(context);
    }
}
