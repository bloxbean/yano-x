package org.yanoproject.x.evidence.profile;

import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateMachineProvider;

/** Manifested complete provider for the stock role-gated evidence profile. */
public final class RoleEvidenceStateMachineProvider implements AppStateMachineProvider {
    public static final String ID = "role-evidence";

    @Override public String id() { return ID; }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("role-evidence requires app-chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        return RoleEvidencePreset.create(context);
    }
}
