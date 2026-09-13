package org.yanoproject.x.showcase;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.effects.EffectId;
import org.yanoproject.api.appchain.effects.EffectIntent;
import org.yanoproject.api.appchain.effects.FinalityGate;
import org.yanoproject.api.appchain.effects.ResultPolicy;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionPlans;
import org.yanoproject.x.composite.ComponentGeneration;
import org.yanoproject.x.composite.CompositeWorkflow;
import org.yanoproject.x.composite.CompositeWorkflowContext;
import org.yanoproject.x.composite.WorkflowDescriptor;
import org.yanoproject.x.stdlib.ApprovalsStateMachine;
import org.yanoproject.x.stdlib.DocTrailStateMachine;
import org.yanoproject.x.stdlib.DocTrailTransitions;
import org.yanoproject.x.stdlib.KvRegistryStateMachine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/** Atomic order binding, approval check, audit append, and outbox emission. */
final class ShowcaseReleaseWorkflow implements CompositeWorkflow {
    static final String ID = "showcase-order-release";
    static final String TOPIC = "showcase.release.v1";

    private final WorkflowDescriptor descriptor;
    private final ComponentGeneration orders;
    private final ComponentGeneration approvals;
    private final ComponentGeneration audit;
    private final ComponentGeneration release;
    private final DocTrailStateMachine auditMachine;
    private final DocTrailTransitions auditTransitions = new DocTrailTransitions();
    private final ShowcaseReleaseStateMachine releaseMachine;

    ShowcaseReleaseWorkflow(WorkflowDescriptor descriptor, ComponentGeneration orders,
                            ComponentGeneration approvals, ComponentGeneration audit,
                            ComponentGeneration release, DocTrailStateMachine auditMachine,
                            ShowcaseReleaseStateMachine releaseMachine) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.orders = orders;
        this.approvals = approvals;
        this.audit = audit;
        this.release = release;
        this.auditMachine = auditMachine;
        this.releaseMachine = releaseMachine;
        if (!descriptor.workflowId().equals(ID) || !descriptor.topic().equals(TOPIC)
                || !descriptor.participants().equals(List.of(orders, approvals, audit, release))) {
            throw new IllegalArgumentException("showcase release descriptor mismatch");
        }
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage message) {
        try {
            ShowcaseReleaseCommand.decode(message.getBody());
            return AppStateMachine.AdmissionResult.accept();
        } catch (RuntimeException malformed) {
            return AppStateMachine.AdmissionResult.reject("INVALID_SHOWCASE_RELEASE_COMMAND");
        }
    }

    @Override
    public void apply(AppBlockExecutionContext execution, CompositeWorkflowContext context) {
        AppBlock block = execution.block();
        int visibleIndex = 0;
        for (AppMessage source : execution.messages()) {
            int originalIndex = execution.originalMessageIndex(visibleIndex++);
            ShowcaseReleaseCommand command;
            try {
                command = ShowcaseReleaseCommand.decode(source.getBody());
            } catch (RuntimeException malformed) {
                continue;
            }
            AppStateWriter orderState = context.state(orders);
            AppStateWriter approvalState = context.state(approvals);
            AppStateWriter auditState = context.state(audit);
            AppStateWriter releaseState = context.state(release);
            byte[] orderEntry = orderState.get(command.orderKey()).orElse(null);
            byte[] approvalEntry = approvalState.get(
                    ApprovalsStateMachine.itemKey(command.approvalId())).orElse(null);
            if (orderEntry == null || approvalEntry == null) {
                continue;
            }
            byte[] order = KvRegistryStateMachine.decodeValue(orderEntry);
            byte[] orderHash = Blake2bUtil.blake2bHash256(order);
            ApprovalsStateMachine.Item item = ApprovalsStateMachine.decodeItem(approvalEntry);
            if (item.status() != ApprovalsStateMachine.STATUS_APPROVED
                    || !MessageDigest.isEqual(item.payloadHash(), orderHash)) {
                continue;
            }
            AppMessage auditMessage = routed(source, ShowcaseCompositePreset.AUDIT_TOPIC,
                    DocTrailStateMachine.append(command.releaseId(), orderHash,
                            "showcase-order:" + new String(command.orderKey(), StandardCharsets.UTF_8)));
            if (!auditMachine.validate(auditMessage).isAccepted()) {
                continue;
            }
            if (context.claim(command.releaseId(), command.commandHash())
                    != CompositeWorkflowContext.ClaimResult.CLAIMED) {
                continue;
            }
            TransitionPlans.commitIfApproved(auditTransitions.decide(
                            auditMessage.getBody(),
                            new TransitionContext(block.height(), block.timestamp(), originalIndex,
                                    source.getMessageId(), auditMessage.getTopic(), source.getSender()),
                            auditState),
                    auditState,
                    AppEffectEmitter.rejecting("document trail does not emit effects"));
            EffectId effectId = context.effects(release).emit(
                    EffectIntent.of(ShowcaseOutboxExecutor.TYPE, order)
                            .scope(ShowcaseReleaseStateMachine.SCOPE_PREFIX + command.releaseId())
                            .gate(FinalityGate.APP_FINAL)
                            .result(ResultPolicy.CHAIN)
                            .expiryBlocks(100)
                            .sourceMessageId(source.getMessageId())
                            .build());
            releaseState.put(ShowcaseReleaseStateMachine.key(command.releaseId()),
                    ShowcaseReleaseStateMachine.pending(
                            effectId, orderHash, command.approvalId()).encode());
        }
    }

    private static AppMessage routed(AppMessage source, String topic, byte[] body) {
        return AppMessage.builder()
                .version(source.getVersion()).messageId(source.getMessageId())
                .chainId(source.getChainId()).topic(topic).sender(source.getSender())
                .senderSeq(source.getSenderSeq()).expiresAt(source.getExpiresAt())
                .body(body).authScheme(source.getAuthScheme()).authProof(source.getAuthProof())
                .build();
    }

}
