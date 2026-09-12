package org.yanoproject.x.roles;

import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateMachineProvider;

/** Stock provider for domain actors and role-gated payload approvals. */
public final class RoleApprovalsStateMachineProvider implements AppStateMachineProvider {
    public static final String ID = "role-approvals";

    @Override public String id() { return ID; }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("role-approvals requires app-chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        return RoleApprovalsPreset.create(context);
    }
}
