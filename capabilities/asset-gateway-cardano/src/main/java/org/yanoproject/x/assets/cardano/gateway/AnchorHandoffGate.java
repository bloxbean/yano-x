package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.x.assets.contracts.AnchorObservationV1;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoff;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoffGate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The ADR-059 D8b membership handoff gate (framework addition 4), fed by the chain's {@code asset-anchor-v1}
 * observer. It finalizes a pending handoff exactly when the finalized block carries an observation of that observer
 * whose claim is an {@code ACCEPTED} anchor output with
 * <ul>
 *   <li>exactly the handoff's member set and threshold, and</li>
 *   <li>an anchored app height of at least the handoff's committed height, which binds the L1 fact to this pending
 *       change: an advance carrying a pending set is always built at or above the height that committed it, so a
 *       stale anchor output with an equal set (a cancelled and re-proposed change, or an earlier anchor) never
 *       finalizes it.</li>
 * </ul>
 * The block's L1 observations were verified by every member against its own L1 view and released at
 * {@code l1.stability-depth}, so this is a deterministic function of finalized chain content: no node-local L1 view
 * decides it. The anchor validator requires the old threshold to sign the advance that carries the new set.
 *
 * <p>Total: it returns false, and never throws, on any block.
 */
final class AnchorHandoffGate implements MembershipHandoffGate {
    static final String PINNED_PREFIX = "anchor-profile.pinned.";
    static final String ANCHOR_PROFILE_VERSION = "anchor-profile.version";

    private final String observerId;
    private final String topic;

    private AnchorHandoffGate(String observerId) {
        this.observerId = observerId;
        this.topic = L1Observation.TOPIC_PREFIX + observerId;
    }

    String observerId() {
        return observerId;
    }

    /**
     * Creates the gate for one chain. Startup fails unless the chain configures exactly one {@code asset-anchor-v1}
     * observer with valid settings and uses anchor profile v2, the only datum the observer accepts, and, when the
     * chain pins its anchor identity ({@code anchor-profile.pinned.*}, D8b, framework addition 3, which the framework
     * enforces against the anchor itself), the pin names exactly the identity that observer watches.
     */
    static AnchorHandoffGate create(Map<String, String> chainSettings) {
        Map<String, String> settings = chainSettings == null ? Map.of() : chainSettings;
        List<String> observers = new ArrayList<>();
        settings.forEach((key, value) -> {
            if (key.startsWith("observers.") && key.endsWith(".type") && value != null
                    && AssetAnchorObserverProvider.TYPE.equals(value.trim())) {
                observers.add(key.substring("observers.".length(), key.length() - ".type".length()));
            }
        });
        if (observers.size() != 1) {
            throw new IllegalArgumentException(AnchorHandoffGateProvider.ID + " needs exactly one observer of type "
                    + AssetAnchorObserverProvider.TYPE + ", found " + observers.size());
        }
        String observerId = observers.getFirst();
        String prefix = "observers." + observerId + ".";
        Map<String, String> observerSettings = new TreeMap<>();
        settings.forEach((key, value) -> {
            if (key.startsWith(prefix)) observerSettings.put(key.substring(prefix.length()), value);
        });
        AssetAnchorSettings anchor = AssetAnchorSettings.parse(observerSettings);
        if (!"2".equals(trim(settings.get(ANCHOR_PROFILE_VERSION)))) {
            throw new IllegalArgumentException(AnchorHandoffGateProvider.ID + " requires "
                    + ANCHOR_PROFILE_VERSION + "=2 (ADR-059 framework addition 5)");
        }
        boolean pinned = settings.keySet().stream().anyMatch(key -> key.startsWith(PINNED_PREFIX));
        anchor.pinnedIdentity().forEach((key, expected) -> {
            if (!pinned) return;
            String value = trim(settings.get(PINNED_PREFIX + key));
            boolean hex = !key.equals("application-id") && !key.equals("commitment-profile");
            if (value == null || !(hex ? value.toLowerCase(Locale.ROOT) : value).equals(expected)) {
                throw new IllegalArgumentException(AnchorHandoffGateProvider.ID + " requires " + PINNED_PREFIX + key
                        + " to pin the identity observer '" + observerId + "' watches (ADR-059 D8b)");
            }
        });
        return new AnchorHandoffGate(observerId);
    }

    @Override
    public boolean finalizesHandoff(AppBlock block, MembershipHandoff handoff) {
        try {
            if (block == null || handoff == null || block.messages() == null) return false;
            for (AppMessage message : block.messages()) {
                if (message != null && topic.equals(message.getTopic()) && carries(message.getBody(), handoff)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException unexpected) {
            return false;
        }
    }

    private boolean carries(byte[] body, MembershipHandoff handoff) {
        L1Observation observation = L1Observation.decode(body);
        if (observation == null || !observerId.equals(observation.observerId())
                || !(observation.anchor() instanceof L1Observation.TransactionAnchor anchor)) {
            return false;
        }
        AnchorObservationV1 claim;
        try {
            claim = AnchorObservationV1.decode(observation.claim());
        } catch (IllegalArgumentException undecodable) {
            return false;
        }
        if (!Arrays.equals(anchor.transactionHash(), claim.txId().bytes())
                || observation.eventOrdinal() != claim.outputIndex() || observation.slot() != claim.slot()
                || !Arrays.equals(observation.blockHash(), claim.blockHash().bytes())) {
            return false;
        }
        return claim.anchored()
                .filter(anchored -> anchored.height() >= handoff.committedHeight())
                .filter(anchored -> handoff.sameSet(anchored.memberKeysHex(), anchored.threshold()))
                .isPresent();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
