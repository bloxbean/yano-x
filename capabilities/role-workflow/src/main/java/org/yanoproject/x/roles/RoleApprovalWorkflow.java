package org.yanoproject.x.roles;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.x.composite.ComponentGeneration;
import org.yanoproject.x.composite.CompositeWorkflow;
import org.yanoproject.x.composite.CompositeWorkflowContext;
import org.yanoproject.x.composite.WorkflowDescriptor;
import org.yanoproject.x.roles.contracts.ApprovalPolicyV1;
import org.yanoproject.x.roles.contracts.GovernedAuthorizationLimitsV1;
import org.yanoproject.x.roles.contracts.GovernedMutationCommandV1;
import org.yanoproject.x.roles.contracts.PolicyMutationV1;
import org.yanoproject.x.roles.contracts.RoleWorkflowKeys;
import org.yanoproject.x.roles.contracts.SignedActorCommandV1;
import org.yanoproject.x.roles.internal.ActorApprovalProcessor;
import org.yanoproject.x.roles.internal.GovernedMutationProcessor;
import org.yanoproject.x.roles.internal.OverlayState;
import org.yanoproject.x.roles.internal.RoleState;

import java.util.List;
import java.util.Objects;

/** Membership-governed policy route over the shared ADR-019 actor approval lifecycle. */
public final class RoleApprovalWorkflow implements CompositeWorkflow {
    public static final String WORKFLOW_ID = "role-approval";
    public static final String TOPIC = SignedActorCommandV1.DEFAULT_TOPIC;
    /** 2.0.1: the member-governed route refuses direct-policy mutations instead of failing to apply them. */
    public static final String PRODUCT_VERSION = "2.0.1";

    private final WorkflowDescriptor descriptor;
    private final ComponentGeneration registry;
    private final ComponentGeneration approvals;
    private final GovernedMutationProcessor governance;
    private final ActorApprovalProcessor actorApprovals;

    public RoleApprovalWorkflow(WorkflowDescriptor descriptor,
                                ComponentGeneration registry,
                                ComponentGeneration approvals,
                                String chainId,
                                RoleWorkflowGovernanceConfig governanceConfig) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.governance = new GovernedMutationProcessor(governanceConfig);
        this.actorApprovals = new ActorApprovalProcessor(
                chainId, GovernedAuthorizationLimitsV1.defaults());
        if (!descriptor.workflowId().equals(WORKFLOW_ID)
                || !descriptor.topic().equals(TOPIC)
                || !descriptor.participants().equals(List.of(registry, approvals))) {
            throw new IllegalArgumentException("invalid role approval workflow descriptor");
        }
    }

    @Override public WorkflowDescriptor descriptor() { return descriptor; }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage message) {
        try {
            decode(message.getBody());
            return AppStateMachine.AdmissionResult.accept();
        } catch (RuntimeException malformed) {
            return AppStateMachine.AdmissionResult.reject("INVALID_ROLE_APPROVAL_COMMAND");
        }
    }

    @Override
    public void apply(AppBlockExecutionContext execution, CompositeWorkflowContext context) {
        AppBlock block = execution.block();
        AppStateWriter registryState = context.state(registry);
        OverlayState approvalState = new OverlayState(context.state(approvals));
        actorApprovals.prepareHeight(block.height(), approvalState);
        GovernedMutationProcessor.MutationHandler handler = policyHandler();
        for (AppMessage message : execution.messages()) {
            try {
                Object command = decode(message.getBody());
                if (command instanceof GovernedMutationCommandV1 governed) {
                    governance.apply(governed, message.getSender(), block.height(),
                            approvalState, handler);
                } else {
                    actorApprovals.apply((SignedActorCommandV1) command,
                            block.height(), registryState, approvalState);
                }
            } catch (IllegalArgumentException malformed) {
                // Full validation is repeated; malformed finalized commands are no-ops.
            }
        }
    }

    /** Direct role policies are authorized by administrator actors, which this member-governed route lacks. */
    private static PolicyMutationV1 supported(PolicyMutationV1 mutation) {
        if (mutation instanceof PolicyMutationV1.PutDirectPolicy) {
            throw new IllegalArgumentException("direct-role policies are not governed on this route");
        }
        return mutation;
    }

    private Object decode(byte[] body) {
        try {
            GovernedMutationCommandV1 command = GovernedMutationCommandV1.decode(body);
            if (command instanceof GovernedMutationCommandV1.Propose proposed) {
                supported(PolicyMutationV1.decode(proposed.mutation()));
            }
            return command;
        } catch (IllegalArgumentException notGovernance) {
            return SignedActorCommandV1.decode(body);
        }
    }

    private GovernedMutationProcessor.MutationHandler policyHandler() {
        return new GovernedMutationProcessor.MutationHandler() {
            @Override public void validate(byte[] mutation) { supported(PolicyMutationV1.decode(mutation)); }

            @Override
            public boolean activate(byte[] mutation, long height, AppStateWriter state) {
                return switch (PolicyMutationV1.decode(mutation)) {
                    case PolicyMutationV1.PutPolicy put -> {
                        ApprovalPolicyV1 policy = put.policy();
                        long current = RoleState.pointer(state,
                                RoleWorkflowKeys.policyCurrent(policy.policyId()));
                        if (policy.revision() != current + 1) yield false;
                        state.put(RoleWorkflowKeys.policyRevision(
                                policy.policyId(), policy.revision()), policy.encode());
                        RoleState.pointer(state, RoleWorkflowKeys.policyCurrent(
                                policy.policyId()), policy.revision());
                        yield true;
                    }
                    case PolicyMutationV1.CancelProposal cancel ->
                            actorApprovals.cancelByGovernance(cancel.proposalId(), state);
                    // Refused at admission and proposal; a record from before 2.0.1 fails to activate.
                    case PolicyMutationV1.PutDirectPolicy ignored -> false;
                };
            }
        };
    }
}
