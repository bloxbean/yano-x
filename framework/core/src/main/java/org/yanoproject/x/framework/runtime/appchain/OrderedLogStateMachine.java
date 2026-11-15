package org.yanoproject.x.framework.runtime.appchain;

import org.yanoproject.x.framework.api.appchain.AppBlockExecutionContext;
import org.yanoproject.x.framework.api.appchain.AppCapabilityIds;
import org.yanoproject.x.framework.api.appchain.AppCapabilityManifest;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateWriter;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.x.framework.api.appchain.proof.ProofSubjectProvider;
import org.yanoproject.x.framework.api.appchain.transition.FinalizedMessageIndex;
import org.yanoproject.x.framework.api.appchain.transition.FinalizedMessageProofSubjectProvider;
import org.yanoproject.x.framework.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.x.framework.api.appchain.transition.TransitionKernel;
import org.yanoproject.x.framework.api.appchain.transition.TransitionPlans;

import java.util.Optional;

/**
 * Built-in default app: an append-only ordered log of opaque messages.
 * For every finalized message it writes
 * {@code key = sha256(namespace || message-id)} →
 * {@code cbor([height, index, topic, sender])}
 * into the state trie — so any consumer can obtain an MPF inclusion proof
 * that a given message was finalized at a given position, verifiable against
 * an anchored state root without trusting the nodes (ADR app-layer/005 D10).
 */
public final class OrderedLogStateMachine implements AppStateMachine {

    public static final String ID = "ordered-log";
    private static final OrderedLogKernel KERNEL = new OrderedLogKernel();

    @Override
    public Optional<TransitionKernel<?, ?>> transitionKernel() {
        return Optional.of(KERNEL);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AppCapabilityManifest capabilityManifest() {
        FinalizedMessageIndex.Config config = FinalizedMessageIndex.Config.allMessages();
        return AppCapabilityManifest.builder(ID, "1.0.0")
                .crossCutting(new AppCapabilityManifest.CrossCutting(
                        AppCapabilityIds.FINALIZED_MESSAGE, "1.0.0", true,
                        java.util.HexFormat.of().formatHex(config.digest()),
                        java.util.Map.of("policy", config.policy().name(),
                                "maxMessagesPerBlock",
                                Integer.toString(config.maxMessagesPerBlock()),
                                "keyNamespace", FinalizedMessageIndex.LOGICAL_NAMESPACE,
                                "keyDerivation", "sha256(namespace || logical-key)"),
                        AppCapabilityManifest.Origin.INTRINSIC))
                .proofSubject(new AppCapabilityManifest.ProofSubject(
                        FinalizedMessageProofSubjectProvider.SUBJECT_ID, 1, "",
                        FinalizedMessageIndex.LOGICAL_NAMESPACE, "state-proof",
                        FinalizedMessageProofSubjectProvider.DESCRIPTOR.descriptorDigest()))
                .build();
    }

    @Override
    public java.util.List<ProofSubjectProvider>
    proofSubjectProviders() {
        return java.util.List.of(new FinalizedMessageProofSubjectProvider());
    }

    @Override
    public void apply(
            AppBlockExecutionContext context,
            AppStateWriter writer,
            AppEffectEmitter effects
    ) {
        TransitionPlans.commit(
                FinalizedMessageIndex.plan(context, FinalizedMessageIndex.Config.allMessages()),
                writer, effects);
    }
}
