package org.yanoproject.x.examples.evidence;

import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateMachineProvider;

/** ServiceLoader entry point for one configured evidence-registry chain. */
public final class EvidenceRegistryStateMachineProvider implements AppStateMachineProvider {
    @Override
    public String id() {
        return EvidenceContract.STATE_MACHINE_ID;
    }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("The evidence-registry state machine requires chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        return new EvidenceRegistryStateMachine(EvidenceRegistryConfig.from(context));
    }
}
