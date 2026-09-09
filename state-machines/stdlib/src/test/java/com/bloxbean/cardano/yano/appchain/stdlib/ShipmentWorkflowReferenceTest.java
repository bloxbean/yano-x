package com.bloxbean.cardano.yano.appchain.stdlib;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.observation.AppObservationEmitter;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationDefinition;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationIntent;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReporterMode;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationMerkleEvidence;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResult;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResultStatus;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSubscriptionId;
import com.bloxbean.cardano.yano.appchain.config.AppChainEffectsConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.Map;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Callback contract fixtures, not a substitute for host L1 admission or a real network run. */
class ShipmentWorkflowReferenceTest {
    private static final byte[] SUBSCRIPTION = hash(1);
    private static final ObservationDefinition DEFINITION_SPEC = new ObservationDefinition(1, "shipment-delivery", 1,
            hash(1), hash(2), hash(3), hash(4), ObservationReporterMode.ACTIVE_MEMBERS,
            ObservationHashes.reporterSetDigest(List.of(hash(6))), 0, 1, 1, false,
            "https-attested-merkle-v1", hash(7), "identity-v1", ObservationMerkleEvidence.VERIFIER_ID,
            "exact-value-quorum-v1", hash(8), hash(9), "one-source-v1", "source-version-v1", "inline-v1",
            1, 1024, 1024, 1024, 1, 1);
    private static final byte[] DEFINITION = DEFINITION_SPEC.digest();
    private static final byte[] RELEASE_TX = hash(3);
    private static final EffectId EFFECT = new EffectId("shipment", 3, 0);

    @Test void providerIsRegisteredAndConfigurationFailsClosed() {
        assertThat(StdlibTestPluginProviders.registry().find(AppStateMachineProvider.class,
                ShipmentWorkflowReferenceStateMachine.ID)).isPresent();
        assertThatThrownBy(() -> new StdlibStateMachineProviders.ShipmentWorkflowReferenceProvider().create())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShipmentWorkflowReferenceStateMachine.Configuration("escrow", "merchant", 4, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShipmentWorkflowReferenceStateMachine.Configuration("", "merchant", 10, 5))
                .isInstanceOf(IllegalArgumentException.class);
        var first = new ShipmentWorkflowReferenceStateMachine(
                new ShipmentWorkflowReferenceStateMachine.Configuration("escrow", "merchant", 10, 5), DEFINITION_SPEC);
        var changed = new ShipmentWorkflowReferenceStateMachine(
                new ShipmentWorkflowReferenceStateMachine.Configuration("escrow", "merchant", 10, 6), DEFINITION_SPEC);
        assertThat(first.capabilityManifest().components()).isNotEqualTo(changed.capabilityManifest().components());
    }

    @Test void receiptDoesNotSettleAndWrongTransactionsCannotComplete() {
        Fixture f = new Fixture();
        f.pay();
        assertThat(f.observations.intents).singleElement().satisfies(intent -> {
            assertThat(intent.parameters()).isEqualTo(hash(4));
            assertThat(intent.definitionId()).isEqualTo("shipment-delivery");
            assertThat(intent.route()).isEqualTo(ShipmentWorkflowReferenceStateMachine.ID);
            assertThat(intent.firstDueAnchor()).isEqualTo(2);
            assertThat(intent.reportDeadlineAnchor()).isEqualTo(4);
            assertThat(intent.cadence()).isZero();
        });
        f.deliver();
        f.receipt();
        assertThat(f.phase()).isEqualTo(ShipmentWorkflowReferenceStateMachine.Phase.WAITING_SETTLEMENT.ordinal());
        f.deposit("shipment-settlement", hash(9), "merchant", 5);
        f.deposit("shipment-settlement", RELEASE_TX, "wrong-address", 5);
        f.deposit("shipment-settlement", RELEASE_TX, "merchant", 4);
        assertThat(f.phase()).isEqualTo(ShipmentWorkflowReferenceStateMachine.Phase.WAITING_SETTLEMENT.ordinal());
        f.deposit("shipment-settlement", RELEASE_TX, "merchant", 5);
        assertThat(f.phase()).isEqualTo(ShipmentWorkflowReferenceStateMachine.Phase.COMPLETE.ordinal());
        byte[] finalState = f.machine.query("workflow", new byte[0], f.state);
        f.deliver();
        f.receipt();
        f.pay();
        assertThat(f.machine.query("workflow", new byte[0], f.state)).isEqualTo(finalState);
        assertThat(f.effects.intents).singleElement().satisfies(intent -> {
            assertThat(intent.type()).isEqualTo("cardano.payment");
            assertThat(intent.scope()).isEqualTo("shipment-reference/release");
            assertThat(intent.sourceMessageId()).isNullOrEmpty();
        });
        assertThat(f.observations.intents).hasSize(1);
    }

    @Test void activationRequiresStableDepositsEffectsAndExplicitAmounts() {
        Map<String, String> settings = new LinkedHashMap<>(Map.of(
                "effects.enabled", "true",
                "observers.shipment-payment.type", "address-deposit",
                "observers.shipment-payment.address", "escrow",
                "observers.shipment-settlement.type", "address-deposit",
                "observers.shipment-settlement.address", "merchant",
                "machines.shipment-workflow-reference-v1.minimum-payment-lovelace", "10",
                "machines.shipment-workflow-reference-v1.release-lovelace", "5"));
        var provider = new StdlibStateMachineProviders.ShipmentWorkflowReferenceProvider();
        assertThat(provider.create(context(settings, 1))).isInstanceOf(ShipmentWorkflowReferenceStateMachine.class);
        assertThatThrownBy(() -> provider.create(context(settings, 0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stable L1");
        settings.put("observers.shipment-payment.type", "metadata-label");
        assertThatThrownBy(() -> provider.create(context(settings, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("address-deposit");
        settings.put("observers.shipment-payment.type", "address-deposit");
        settings.put("effects.enabled", "false");
        assertThatThrownBy(() -> provider.create(context(settings, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("effects");
        settings.put("effects.enabled", "true");
        settings.remove("machines.shipment-workflow-reference-v1.release-lovelace");
        assertThatThrownBy(() -> provider.create(context(settings, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bounds");
    }

    private static AppStateMachineContext context(Map<String, String> settings, int stability) {
        AppChainConfig config = AppChainConfig.builder("shipment").signingKeyHex("61".repeat(32))
                .l1StabilityDepth(stability)
                .pluginSettings(settings).build();
        ObservationProfileV1 profile = new ObservationProfileV1(1, true, 1, 1, 1, 1, 1, 1, 1,
                List.of(DEFINITION_SPEC), 100, 100, 100, 10, 100, 1, 1,
                4096, 1024, 8192, 10, 16384, 1, 20, 3);
        return new AppStateMachineContext() {
            @Override public String chainId() { return "shipment"; }
            @Override public Map<String, String> settings() { return settings; }
            @Override public Optional<AppChainConsensusProfile> consensusProfile() {
                return Optional.of(AppChainEffectsConfig.from(config).consensusProfile(config));
            }
            @Override public Optional<ObservationProfileV1> observationProfile() { return Optional.of(profile); }
        };
    }

    @Test void settlementBeforeReceiptIsRetainedAndReplayIsDeterministic() {
        Fixture first = new Fixture();
        Fixture replay = new Fixture();
        for (Fixture f : List.of(first, replay)) {
            f.pay();
            f.deliver();
            f.deposit("shipment-settlement", RELEASE_TX, "merchant", 5);
            assertThat(f.phase()).isEqualTo(ShipmentWorkflowReferenceStateMachine.Phase.RELEASE_PENDING.ordinal());
            f.receipt();
            assertThat(f.phase()).isEqualTo(ShipmentWorkflowReferenceStateMachine.Phase.COMPLETE.ordinal());
        }
        assertThat(first.state.values.keySet()).isEqualTo(replay.state.values.keySet());
        first.state.values.forEach((key, value) -> assertThat(replay.state.values.get(key)).isEqualTo(value));
        MemoryState reopened = new MemoryState();
        first.state.values.forEach((key, value) -> reopened.values.put(key, value.clone()));
        assertThat(replay.machine.query("workflow", new byte[0], reopened))
                .isEqualTo(first.machine.query("workflow", new byte[0], first.state));
    }

    @Test void candidateOverflowIsBoundedVisibleAndNeverInventsSettlement() {
        Fixture f = new Fixture();
        f.pay();
        f.deliver();
        for (int i = 32; i < 64; i++) f.deposit("shipment-settlement", hash(i), "merchant", 5);
        int before = f.state.values.size();
        f.deposit("shipment-settlement", RELEASE_TX, "merchant", 5);
        assertThat(f.state.values.size()).isEqualTo(before + 1); // The single overflow flag.
        f.receipt();
        assertThat(f.phase()).isEqualTo(ShipmentWorkflowReferenceStateMachine.Phase.WAITING_SETTLEMENT.ordinal());
        assertThat(((UnsignedInteger) f.view().getDataItems().get(9)).getValue().intValue()).isEqualTo(1);
    }

    @Test void knownReleaseTransactionDoesNotCompeteWithPreReceiptCandidates() {
        Fixture f = new Fixture();
        f.pay();
        f.deliver();
        for (int i = 32; i < 64; i++) f.deposit("shipment-settlement", hash(i), "merchant", 5);
        f.receipt();
        int before = f.state.values.size();
        f.deposit("shipment-settlement", hash(99), "merchant", 5);
        assertThat(f.state.values.size()).isEqualTo(before);
        f.deposit("shipment-settlement", RELEASE_TX, "merchant", 5);
        assertThat(f.phase()).isEqualTo(ShipmentWorkflowReferenceStateMachine.Phase.COMPLETE.ordinal());
    }

    @Test void missingShipmentOrInsufficientPaymentCannotRelease() {
        Fixture f = new Fixture();
        f.deposit("shipment-payment", hash(4), "escrow", 9);
        f.deposit("untrusted-observer", hash(4), "escrow", 10);
        assertThat(f.observations.intents).isEmpty();
        assertThat(f.effects.intents).isEmpty();
        f.pay();
        f.machine.onObservationResult(f.context(), result(ObservationResultStatus.EXPIRED),
                f.state, f.effects, f.observations);
        assertThat(f.phase()).isEqualTo(ShipmentWorkflowReferenceStateMachine.Phase.SHIPMENT_UNRESOLVED.ordinal());
        assertThat(f.effects.intents).isEmpty();
    }

    @Test void unrelatedReceiptIsIgnoredAndMalformedConfirmedReferenceFailsClosed() {
        Fixture f = new Fixture();
        f.pay();
        f.deliver();
        f.machine.onEffectResult(f.context(), new EffectResult(new EffectId("shipment", 3, 1),
                "cardano.payment", "shipment-reference/release", EffectOutcome.CONFIRMED,
                new byte[0], null, 4), f.state, f.effects, f.observations);
        assertThat(f.phase()).isEqualTo(ShipmentWorkflowReferenceStateMachine.Phase.RELEASE_PENDING.ordinal());
        f.machine.onEffectResult(f.context(), new EffectResult(EFFECT, "cardano.payment", "shipment-reference/release",
                EffectOutcome.CONFIRMED, "not-a-transaction".getBytes(StandardCharsets.US_ASCII), null, 4),
                f.state, f.effects, f.observations);
        assertThat(f.phase()).isEqualTo(ShipmentWorkflowReferenceStateMachine.Phase.RELEASE_FAILED.ordinal());
        f.deposit("shipment-settlement", RELEASE_TX, "merchant", 5);
        f.receipt();
        assertThat(f.phase()).isEqualTo(ShipmentWorkflowReferenceStateMachine.Phase.RELEASE_FAILED.ordinal());
    }

    private static ObservationResult result(ObservationResultStatus status) {
        byte[] value = status == ObservationResultStatus.VALUE ? "DELIVERED".getBytes(StandardCharsets.US_ASCII)
                : new byte[0];
        byte[] digest = ObservationHashes.digest(value);
        return new ObservationResult(1, ObservationHashes.resultId(SUBSCRIPTION, 0, DEFINITION, status, digest),
                SUBSCRIPTION, 0, DEFINITION, status, value, digest,
                status == ObservationResultStatus.VALUE ? hash(5) : null, 1, 1, new byte[0], 3);
    }

    private static class Fixture {
        final MemoryState state = new MemoryState();
        final RecordingEffects effects = new RecordingEffects();
        final RecordingObservations observations = new RecordingObservations();
        final ShipmentWorkflowReferenceStateMachine machine;

        Fixture() {
            machine = new ShipmentWorkflowReferenceStateMachine(
                    new ShipmentWorkflowReferenceStateMachine.Configuration("escrow", "merchant", 10, 5),
                    DEFINITION_SPEC);
        }

        AppBlockExecutionContext context(AppMessage... messages) {
            return AppBlockExecutionContext.fromValidatedBlock(new AppBlock(AppBlock.BLOCK_VERSION, "shipment", 1,
                    hash(0), 100, hash(8), 100, hash(6), hash(7), List.of(messages), hash(9), FinalityCert.empty()));
        }

        void deposit(String observer, byte[] tx, String address, long amount) {
            byte[] claim = CborSerializationUtil.serialize(new Array().add(new UnicodeString(address))
                    .add(new UnsignedInteger(amount)));
            L1Observation fact = L1Observation.transaction(observer, tx, 100, hash(8), claim);
            AppMessage message = AppMessage.builder().version(1).messageId(hash(7)).chainId("shipment")
                    .topic(fact.topic()).sender(hash(9)).senderSeq(1).expiresAt(Long.MAX_VALUE)
                    .body(fact.encode()).authScheme(0).authProof(new byte[64]).build();
            AppBlockExecutionContext context = context(message);
            machine.apply(context, state, effects, observations);
        }

        void pay() { deposit("shipment-payment", hash(4), "escrow", 10); }
        void deliver() { machine.onObservationResult(context(), result(ObservationResultStatus.VALUE),
                state, effects, observations); }
        void receipt() {
            machine.onEffectResult(context(), new EffectResult(EFFECT, "cardano.payment", "shipment-reference/release",
                    EffectOutcome.CONFIRMED, HexFormat.of().formatHex(RELEASE_TX).getBytes(StandardCharsets.US_ASCII),
                    null, 4), state, effects, observations);
        }
        Array view() {
            return (Array) CborSerializationUtil.deserializeOne(machine.query("workflow", new byte[0], state));
        }
        int phase() { return ((UnsignedInteger) view().getDataItems().get(1)).getValue().intValueExact(); }
    }

    private static class RecordingEffects implements AppEffectEmitter {
        final List<EffectIntent> intents = new ArrayList<>();
        @Override public EffectId emit(EffectIntent intent) { intents.add(intent); return EFFECT; }
        @Override public long pendingCount() { return intents.size(); }
    }

    private static class RecordingObservations implements AppObservationEmitter {
        final List<ObservationIntent> intents = new ArrayList<>();
        @Override public ObservationSubscriptionId watch(ObservationIntent intent) {
            intents.add(intent);
            return new ObservationSubscriptionId(SUBSCRIPTION);
        }
        @Override public void cancel(ObservationSubscriptionId id) { throw new AssertionError("Unexpected cancel"); }
        @Override public long activeCount() { return intents.size(); }
    }

    private static class MemoryState implements AppStateWriter, AppQueryContext {
        final TreeMap<String, byte[]> values = new TreeMap<>();
        @Override public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(values.get(HexFormat.of().formatHex(key))).map(byte[]::clone);
        }
        @Override public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(HexFormat.of().formatHex(key)); }
        @Override public byte[] stateRoot() { throw new UnsupportedOperationException("Callback fixture has no MPF"); }
        @Override public long committedHeight() { return 4; }
    }

    private static byte[] hash(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
