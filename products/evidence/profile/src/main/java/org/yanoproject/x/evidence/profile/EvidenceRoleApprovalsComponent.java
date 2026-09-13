package org.yanoproject.x.evidence.profile;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppChainInfo;
import org.yanoproject.api.appchain.AppQueryContext;
import org.yanoproject.api.appchain.AppQueryException;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.effects.EffectResult;
import org.yanoproject.x.composite.ComponentDescriptor;
import org.yanoproject.x.evidence.profile.contracts.RoleEvidenceKeys;
import org.yanoproject.x.roles.RoleAwareApprovalsComponent;
import org.yanoproject.x.roles.contracts.RoleWorkflowIdentifiers;

import java.nio.charset.StandardCharsets;

/** Evidence-specific query adapter over the reusable role-approvals component. */
final class EvidenceRoleApprovalsComponent implements AppStateMachine {
    static final String QUERY_EVIDENCE_APPROVAL = "evidence-approval";

    private final RoleAwareApprovalsComponent delegate;

    EvidenceRoleApprovalsComponent(ComponentDescriptor descriptor) {
        delegate = new RoleAwareApprovalsComponent(descriptor);
    }

    public ComponentDescriptor descriptor() {
        return delegate.descriptor();
    }

    @Override
    public String id() {
        return descriptor().componentId();
    }

    @Override
    public void init(AppStateReader ownState, AppChainInfo chain) {
        delegate.init(ownState, chain);
    }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage routedMessage) {
        return delegate.validate(routedMessage);
    }

    @Override
    public AppStateMachine.AdmissionResult validateForBlock(
            AppMessage routedMessage,
            long candidateHeight,
            AppStateReader ownState
    ) {
        return delegate.validateForBlock(routedMessage, candidateHeight, ownState);
    }

    @Override
    public void apply(
            AppBlockExecutionContext execution,
            AppStateWriter ownState,
            AppEffectEmitter ownedEffects
    ) {
        delegate.apply(execution, ownState, ownedEffects);
    }

    @Override
    public void onEffectResult(AppBlockExecutionContext execution, EffectResult result,
                               AppStateWriter ownState, AppEffectEmitter ownedEffects) {
        delegate.onEffectResult(execution, result, ownState, ownedEffects);
    }

    @Override
    public byte[] query(String localPath, byte[] params, AppQueryContext ownState) {
        if (!QUERY_EVIDENCE_APPROVAL.equals(localPath)) {
            return delegate.query(localPath, params, ownState);
        }
        QueryRef ref = queryRef(params);
        return ownState.get(RoleEvidenceKeys.evidenceApproval(
                ref.evidenceId(), ref.businessVersion())).orElse(new byte[0]);
    }

    private static QueryRef queryRef(byte[] params) {
        try {
            String value = new String(params, StandardCharsets.US_ASCII);
            String[] fields = value.split("@", -1);
            if (fields.length != 2 || fields[0].isEmpty()) {
                throw new IllegalArgumentException();
            }
            String evidenceId = RoleWorkflowIdentifiers.id(fields[0], "evidenceId");
            long businessVersion = Long.parseLong(fields[1]);
            if (businessVersion < 1) {
                throw new IllegalArgumentException();
            }
            return new QueryRef(evidenceId, businessVersion);
        } catch (RuntimeException invalid) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "query must be evidenceId@businessVersion");
        }
    }

    private record QueryRef(String evidenceId, long businessVersion) {
    }
}
