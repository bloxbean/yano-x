package org.yanoproject.x.framework.runtime.appchain;

import org.yanoproject.x.framework.api.appchain.l1view.BridgeDiffusionHandler;
import org.yanoproject.x.framework.api.appchain.observation.ObservationTopics;

/** Classification of reserved app-chain topics that belong to node diffusion, not blocks. */
final class AppChainSystemTopics {
    static final String CONSENSUS_DIFFUSION_PREFIX = "~consensus/";
    static final String ANCHOR_DIFFUSION_PREFIX = "~anchor/";
    static final String BRIDGE_DIFFUSION_PREFIX =
            BridgeDiffusionHandler.TOPIC_PREFIX;

    private AppChainSystemTopics() {
    }

    static boolean isDiffusionOnly(String topic) {
        return topic != null && (topic.startsWith(CONSENSUS_DIFFUSION_PREFIX)
                || topic.startsWith(ANCHOR_DIFFUSION_PREFIX)
                || topic.startsWith(BRIDGE_DIFFUSION_PREFIX)
                || ObservationTopics.isDiffusionOnly(topic));
    }

    /**
     * Control traffic carried in the protocol-100 priority lane (ADR-060 D2): consensus rounds and anchor
     * co-signatures, whose loss stalls the chain. Bridge and observation diffusion stay ordinary.
     */
    static boolean isPriorityDiffusion(String topic) {
        return topic != null && (topic.startsWith(CONSENSUS_DIFFUSION_PREFIX)
                || topic.startsWith(ANCHOR_DIFFUSION_PREFIX));
    }

    static boolean isUnknownObservationTopic(String topic) {
        return ObservationTopics.isReserved(topic) && !ObservationTopics.isAllowed(topic);
    }
}
