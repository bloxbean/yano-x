package org.yanoproject.x.evidence.profile;

import org.yanoproject.x.composite.CompositeStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateMachineContext;
import org.yanoproject.x.framework.api.appchain.AppStateMachineProvider;

/** Manifested provider for the configuration-only stock composite presets. */
public final class EvidenceCompositeStateMachineProvider implements AppStateMachineProvider {
    @Override
    public String id() {
        return CompositeStateMachine.ID;
    }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("The composite state machine requires chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        return EvidenceCompositePresets.create(context);
    }
}
