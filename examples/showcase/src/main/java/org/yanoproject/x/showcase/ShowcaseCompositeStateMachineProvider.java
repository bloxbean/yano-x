package org.yanoproject.x.showcase;

import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateMachineProvider;

/** Demo-only provider for the order approval and local-outbox showcase. */
public final class ShowcaseCompositeStateMachineProvider implements AppStateMachineProvider {
    @Override
    public String id() {
        return ShowcaseCompositePreset.ID;
    }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("showcase-composite requires chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        return ShowcaseCompositePreset.create(context);
    }
}
