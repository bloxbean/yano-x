package org.yanoproject.x.stdlib;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.codec.internal.CborStructurePreflight;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.observation.AppObservationEmitter;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationAnchorType;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationDefinition;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationIntent;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationMerkleEvidence;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResult;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResultStatus;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReporterMode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Single-order teaching workflow. Never treats an effect receipt or generic report as Cardano settlement. */
public final class ShipmentWorkflowReferenceStateMachine implements AppStateMachine {
    public static final String ID = "shipment-workflow-reference-v1";
    public static final String DEFINITION_ID = "shipment-delivery";
    public static final String ADVANCE_TOPIC = "shipment/advance";
    public static final String PAYMENT_OBSERVER = "shipment-payment";
    public static final String SETTLEMENT_OBSERVER = "shipment-settlement";
    private static final String RELEASE_TYPE = "cardano.payment";
    private static final String RELEASE_SCOPE = "shipment-reference/release";
    private static final byte[] DELIVERED = bytes("DELIVERED");
    private static final byte[] PHASE = key("phase");
    private static final byte[] PAYMENT = key("payment");
    private static final byte[] SUBSCRIPTION = key("subscription");
    private static final byte[] OBSERVATION = key("observation");
    private static final byte[] RELEASE_ID = key("release-id");
    private static final byte[] RELEASE_RESULT = key("release-result");
    private static final byte[] RELEASE_TX = key("release-tx");
    private static final byte[] SETTLEMENT = key("settlement");
    private static final byte[] CANDIDATE_COUNT = key("candidate-count");
    private static final byte[] OVERFLOW = key("candidate-overflow");
    private static final int MAX_SETTLEMENT_CANDIDATES = 32;
    private static final CborStructurePreflight.Limits CLAIM_LIMITS =
            new CborStructurePreflight.Limits(512, 2, 4, 2, 256);
    private final Configuration configuration;
    private final byte[] definitionDigest;

    public enum Phase {
        WAITING_PAYMENT, WAITING_SHIPMENT, RELEASE_PENDING, WAITING_SETTLEMENT,
        COMPLETE, SHIPMENT_UNRESOLVED, RELEASE_FAILED
    }

    public record Configuration(String paymentAddress, String settlementAddress,
                                 long minimumPaymentLovelace, long releaseLovelace) {
        public Configuration {
            paymentAddress = address(paymentAddress);
            settlementAddress = address(settlementAddress);
            if (minimumPaymentLovelace <= 0 || releaseLovelace <= 0 || releaseLovelace > minimumPaymentLovelace) {
                throw new IllegalArgumentException("Invalid shipment payment/release bounds");
            }
        }

        private byte[] encode() {
            return CborSerializationUtil.serialize(new Array().add(new UnsignedInteger(1))
                    .add(new UnicodeString(paymentAddress)).add(new UnicodeString(settlementAddress))
                    .add(new UnsignedInteger(minimumPaymentLovelace)).add(new UnsignedInteger(releaseLovelace)));
        }

        private static String address(String value) {
            if (value == null || value.isBlank() || value.length() > 256
                    || !StandardCharsets.US_ASCII.newEncoder().canEncode(value)) {
                throw new IllegalArgumentException("A bounded public Cardano address is required");
            }
            return value.trim();
        }
    }

    public ShipmentWorkflowReferenceStateMachine(Configuration configuration, ObservationDefinition definition) {
        if (!DEFINITION_ID.equals(definition.id())
                || !ObservationMerkleEvidence.VERIFIER_ID.equals(definition.evidenceVerifierId())
                || definition.reporterMode() != ObservationReporterMode.ACTIVE_MEMBERS
                || !"exact-value-quorum-v1".equals(definition.reconciliationPolicyId())
                || definition.maxParameterBytes() < 32 || definition.maxValueBytes() < DELIVERED.length) {
            throw new IllegalArgumentException("Shipment reference requires signed Merkle receipt evidence");
        }
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.definitionDigest = definition.digest();
    }

    @Override public String id() { return ID; }

    @Override public AppCapabilityManifest capabilityManifest() {
        byte[] config = configuration.encode();
        byte[] digest = ObservationHashes.digest(ByteBuffer.allocate(config.length + 32)
                .put(config).put(definitionDigest).array());
        return AppCapabilityManifest.builder(ID, "1.0.0")
                .component(new AppCapabilityManifest.Component(ID, "1.0.0", HexFormat.of().formatHex(digest),
                        "shipment-reference/v1", List.of(ADVANCE_TOPIC), List.of("workflow"),
                        AppCapabilityManifest.Origin.INTRINSIC)).build();
    }

    @Override public AdmissionResult validate(AppMessage message) {
        return ADVANCE_TOPIC.equals(message.getTopic()) && Arrays.equals(message.getBody(), new byte[]{1})
                ? AdmissionResult.accept() : AdmissionResult.reject("Expected v1 shipment advance command");
    }

    @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) {
        throw new IllegalStateException("Shipment reference requires observation-aware execution");
    }

    @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects,
                                AppObservationEmitter observations) {
        for (var sequenced : context.l1Observations()) {
            L1Observation fact = sequenced.observation();
            if (!(fact.anchor() instanceof L1Observation.TransactionAnchor transaction)) continue;
            Phase phase = phase(writer);
            if (phase == Phase.WAITING_PAYMENT && PAYMENT_OBSERVER.equals(fact.observerId())
                    && deposit(fact, configuration.paymentAddress(), configuration.minimumPaymentLovelace())) {
                writer.put(PAYMENT, fact.encode());
                long height = context.block().height();
                var subscription = observations.watch(ObservationIntent.oneShot(DEFINITION_ID, ID,
                        transaction.transactionHash(), ObservationAnchorType.APP_HEIGHT,
                        Math.addExact(height, 1), Math.addExact(height, 3), Math.addExact(height, 3)));
                writer.put(SUBSCRIPTION, subscription.bytes());
                setPhase(writer, Phase.WAITING_SHIPMENT);
            } else if ((phase == Phase.RELEASE_PENDING || phase == Phase.WAITING_SETTLEMENT)
                    && SETTLEMENT_OBSERVER.equals(fact.observerId())
                    && deposit(fact, configuration.settlementAddress(), configuration.releaseLovelace())) {
                if (phase == Phase.WAITING_SETTLEMENT) {
                    if (Arrays.equals(writer.get(RELEASE_TX).orElseThrow(), transaction.transactionHash())) {
                        writer.put(SETTLEMENT, fact.encode());
                        setPhase(writer, Phase.COMPLETE);
                    }
                    continue;
                }
                byte[] candidate = candidateKey(transaction.transactionHash());
                if (writer.get(candidate).isEmpty()) {
                    int count = writer.get(CANDIDATE_COUNT).map(value -> Byte.toUnsignedInt(value[0])).orElse(0);
                    if (count >= MAX_SETTLEMENT_CANDIDATES) {
                        writer.put(OVERFLOW, new byte[]{1}); // Bounded, visible failure; never invent settlement.
                        continue;
                    }
                    writer.put(candidate, fact.encode());
                    writer.put(CANDIDATE_COUNT, new byte[]{(byte) (count + 1)});
                }
            }
        }
    }

    @Override public void onObservationResult(AppBlockExecutionContext context, ObservationResult result,
                                              AppStateWriter writer, AppEffectEmitter effects,
                                              AppObservationEmitter observations) {
        if (phase(writer) != Phase.WAITING_SHIPMENT
                || !Arrays.equals(writer.get(SUBSCRIPTION).orElseThrow(), result.subscriptionId())) return;
        if (!Arrays.equals(definitionDigest, result.definitionDigest())) {
            throw new IllegalArgumentException("Shipment observation definition mismatch");
        }
        writer.put(OBSERVATION, result.encode());
        if (result.status() != ObservationResultStatus.VALUE || !Arrays.equals(result.value(), DELIVERED)) {
            setPhase(writer, Phase.SHIPMENT_UNRESOLVED);
            return;
        }
        Map payload = new Map().put(new UnicodeString("to"), new UnicodeString(configuration.settlementAddress()))
                .put(new UnicodeString("lovelace"), new UnsignedInteger(configuration.releaseLovelace()));
        var effect = effects.emit(EffectIntent.of(RELEASE_TYPE, CborSerializationUtil.serialize(payload))
                .scope(RELEASE_SCOPE).gate(FinalityGate.CHAIN_DEFAULT).result(ResultPolicy.CHAIN).build());
        writer.put(RELEASE_ID, bytes(effect.canonical()));
        setPhase(writer, Phase.RELEASE_PENDING);
    }

    @Override public void onEffectResult(AppBlockExecutionContext context, EffectResult result,
                                         AppStateWriter writer, AppEffectEmitter effects,
                                         AppObservationEmitter observations) {
        if (phase(writer) != Phase.RELEASE_PENDING || !RELEASE_TYPE.equals(result.type())
                || !RELEASE_SCOPE.equals(result.scope())
                || !Arrays.equals(writer.get(RELEASE_ID).orElseThrow(), bytes(result.effectId().canonical()))) return;
        writer.put(RELEASE_RESULT, result.encodeEnvelope());
        String reference = new String(result.externalRef(), StandardCharsets.US_ASCII);
        if (!result.confirmed() || !reference.matches("[0-9a-f]{64}")) {
            setPhase(writer, Phase.RELEASE_FAILED);
            return;
        }
        writer.put(RELEASE_TX, HexFormat.of().parseHex(reference));
        setPhase(writer, Phase.WAITING_SETTLEMENT);
        completeIfSettled(writer);
    }

    private static void completeIfSettled(AppStateWriter writer) {
        if (phase(writer) != Phase.WAITING_SETTLEMENT) return;
        writer.get(RELEASE_TX).flatMap(tx -> writer.get(candidateKey(tx))).ifPresent(fact -> {
            writer.put(SETTLEMENT, fact);
            setPhase(writer, Phase.COMPLETE);
        });
    }

    @Override public byte[] query(String path, byte[] params, AppQueryContext state) {
        if (!"workflow".equals(path)) throw new AppQueryException(AppQueryException.Code.UNSUPPORTED, "Unknown query");
        if (params == null || params.length != 0) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST, "Expected empty parameters");
        }
        Array result = new Array().add(new UnsignedInteger(1)).add(new UnsignedInteger(phase(state).ordinal()));
        for (byte[] key : List.of(PAYMENT, SUBSCRIPTION, OBSERVATION, RELEASE_ID,
                RELEASE_RESULT, RELEASE_TX, SETTLEMENT)) {
            result.add(new ByteString(state.get(key).orElseGet(() -> new byte[0])));
        }
        result.add(new UnsignedInteger(state.get(OVERFLOW).isPresent() ? 1 : 0));
        return CborSerializationUtil.serialize(result);
    }

    private static boolean deposit(L1Observation fact, String address, long minimum) {
        byte[] bytes = fact.claim();
        if (!CborStructurePreflight.accepts(bytes, CLAIM_LIMITS)) return false;
        try {
            if (!(CborSerializationUtil.deserializeOne(bytes) instanceof Array claim)
                    || claim.getDataItems().size() != 2
                    || !(claim.getDataItems().get(0) instanceof UnicodeString target)
                    || !(claim.getDataItems().get(1) instanceof UnsignedInteger amount)
                    || !Arrays.equals(bytes, CborSerializationUtil.serialize(claim))) return false;
            return address.equals(target.getString()) && amount.getValue().longValueExact() >= minimum;
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static Phase phase(AppStateReader state) {
        return state.get(PHASE).map(value -> Phase.values()[Byte.toUnsignedInt(value[0])])
                .orElse(Phase.WAITING_PAYMENT);
    }

    private static void setPhase(AppStateWriter writer, Phase phase) {
        writer.put(PHASE, new byte[]{(byte) phase.ordinal()});
    }
    private static byte[] candidateKey(byte[] tx) {
        byte[] prefix = key("candidate/");
        return ByteBuffer.allocate(prefix.length + 32).put(prefix).put(tx).array();
    }
    private static byte[] key(String name) { return bytes("shipment-reference/" + name); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
