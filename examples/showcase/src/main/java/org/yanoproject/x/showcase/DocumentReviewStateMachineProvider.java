package org.yanoproject.x.showcase;

import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateMachineProvider;

/** Demo provider for document trail + domain actors + role-aware approval composition. */
public final class DocumentReviewStateMachineProvider implements AppStateMachineProvider {
    public static final String ID = "document-review";

    @Override public String id() { return ID; }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("document-review requires app-chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        return DocumentReviewPreset.create(context);
    }
}
