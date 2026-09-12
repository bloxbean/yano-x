package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppCapabilityManifest;
import org.yanoproject.api.appchain.AppQueryContext;
import org.yanoproject.api.appchain.AppQueryException;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.observation.AppObservationEmitter;
import org.yanoproject.api.appchain.observation.CompleteSourceMedianPolicy;
import org.yanoproject.api.appchain.observation.ObservationAnchorType;
import org.yanoproject.api.appchain.observation.ObservationDefinition;
import org.yanoproject.api.appchain.observation.ObservationFixedPoint;
import org.yanoproject.api.appchain.observation.ObservationHashes;
import org.yanoproject.api.appchain.observation.ObservationIntent;
import org.yanoproject.api.appchain.observation.ObservationReporterMode;
import org.yanoproject.api.appchain.observation.ObservationResult;
import org.yanoproject.api.appchain.observation.ObservationResultStatus;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Bounded ADA/USD teaching example, not a production oracle or an L1 publisher.
 * Three synthetic logical sources each need an external reporter quorum.
 * All acquisition, signing, scheduling and certification belong to the host/reporters.
 */
public final class AdaUsdReferenceStateMachine implements AppStateMachine {
    public static final String ID = "ada-usd-reference-v1";
    public static final String DEFINITION_ID = "ada-usd";
    public static final String ADVANCE_TOPIC = "ada-usd/advance";
    private static final byte[] SUBSCRIPTION = bytes("ada-usd/subscription");
    private static final byte[] LATEST = bytes("ada-usd/latest-result");
    private final byte[] definitionDigest;

    public AdaUsdReferenceStateMachine(ObservationDefinition definition) {
        CompleteSourceMedianPolicy.Parameters parameters = parameters();
        if (!DEFINITION_ID.equals(definition.id())
                || definition.reporterMode() != ObservationReporterMode.EXTERNAL_REPORTERS
                || !CompleteSourceMedianPolicy.ID.equals(definition.reconciliationPolicyId())
                || !Arrays.equals(parameters.digest(), definition.policyParametersDigest())
                || !Arrays.equals(parameters.sourceSetDigest(), definition.sourceConfigurationDigest())
                || definition.sourceThreshold() != 3 || !definition.certificateLocalUniqueness()) {
            throw new IllegalArgumentException("ADA/USD reference requires its pinned complete-source definition");
        }
        definitionDigest = definition.digest();
    }

    /** Synthetic source identities deliberately make no claims about real exchange independence. */
    public static CompleteSourceMedianPolicy.Parameters parameters() {
        List<CompleteSourceMedianPolicy.Source> sources = List.of("venue-a", "venue-b", "venue-c").stream()
                .map(name -> new CompleteSourceMedianPolicy.Source(
                        ObservationHashes.digest(bytes("ada-usd/example/source/" + name)),
                        ObservationHashes.digest(bytes("ada-usd/example/group/" + name))))
                .toList();
        return new CompleteSourceMedianPolicy.Parameters(6, 2, 100_000, BigInteger.valueOf(10_000),
                BigInteger.ONE, BigInteger.valueOf(1_000_000_000), sources);
    }

    @Override public String id() { return ID; }

    @Override public AppCapabilityManifest capabilityManifest() {
        return StdlibCapabilityManifests.component(ID, ADVANCE_TOPIC, List.of("subscription", "latest")).build();
    }

    @Override public AdmissionResult validate(AppMessage message) {
        return ADVANCE_TOPIC.equals(message.getTopic()) && Arrays.equals(message.getBody(), new byte[]{1})
                ? AdmissionResult.accept() : AdmissionResult.reject("Expected v1 ADA/USD advance command");
    }

    @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) {
        throw new IllegalStateException("ADA/USD reference requires the observation-aware execution entry point");
    }

    @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects,
                                AppObservationEmitter observations) {
        if (writer.get(SUBSCRIPTION).isPresent()) return;
        if (context.block().height() != 1) {
            throw new IllegalStateException("ADA/USD reference must be installed from genesis");
        }
        // 100 rounds: due heights 2, 12, ..., 992; inclusive report windows of two blocks.
        // App heights are logical cadence, never a promise of real-time seconds.
        var subscription = observations.watch(new ObservationIntent(DEFINITION_ID, "ada-usd", bytes("ADA/USD"),
                ObservationAnchorType.APP_HEIGHT, 2, 4, 994, 10, 0));
        writer.put(SUBSCRIPTION, subscription.bytes());
    }

    @Override public void onObservationResult(AppBlockExecutionContext context, ObservationResult result,
                                              AppStateWriter writer, AppEffectEmitter effects,
                                              AppObservationEmitter observations) {
        if (!Arrays.equals(writer.get(SUBSCRIPTION).orElseThrow(), result.subscriptionId())
                || !Arrays.equals(definitionDigest, result.definitionDigest())) {
            throw new IllegalArgumentException("Unexpected ADA/USD observation identity");
        }
        if (result.status() == ObservationResultStatus.VALUE) {
            ObservationFixedPoint price = ObservationFixedPoint.decode(result.value());
            if (price.scale() != 6 || price.units().signum() <= 0) {
                throw new IllegalArgumentException("Invalid ADA/USD result");
            }
        }
        // Preserve the whole terminal result, including no-result/expiry. Never silently reuse an old price.
        writer.put(LATEST, result.encode());
    }

    @Override public byte[] query(String path, byte[] params, AppQueryContext state) {
        if (params == null || params.length != 0) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST, "Expected empty query parameters");
        }
        return switch (path) {
            case "subscription" -> state.get(SUBSCRIPTION).orElseGet(() -> new byte[0]);
            case "latest" -> state.get(LATEST).orElseGet(() -> new byte[0]);
            default -> throw new AppQueryException(AppQueryException.Code.UNSUPPORTED, "Unknown ADA/USD query");
        };
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
