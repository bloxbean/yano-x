package org.yanoproject.x.composite.bindings;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.AppStateMachine.AdmissionResult;
import org.yanoproject.api.appchain.FinalityCert;
import org.yanoproject.api.appchain.codec.MessageCodec;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.effects.EffectId;
import org.yanoproject.api.appchain.effects.EffectIntent;
import org.yanoproject.appchain.testkit.AppChainTestProfiles;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.EventDescriptor;
import org.yanoproject.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.api.appchain.transition.StateMutation;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionPlan;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.api.appchain.transition.TransitionWorkBudget;
import org.yanoproject.api.appchain.transition.TransitionWorkReference;
import org.yanoproject.api.appchain.transition.TransitionWorkRequest;
import org.yanoproject.x.composite.ComponentGeneration;
import org.yanoproject.x.composite.CompositeWorkflowContext;
import org.yanoproject.x.composite.WorkflowDescriptor;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventBindingWorkflowTest {
    private static final byte[] KEY = {1};
    private static final byte[] BODY = {42};
    private static final byte[] MESSAGE_ID = new byte[32];
    private static final byte[] WORK_KEY = {9};

    @Test
    void baselineSizingMatchesCanonicalEncodingAndRejectsExactlyBeyondCommittedLimit() {
        for (String topic : List.of("source.v1", "a-topic-with-more-than-23-characters.v1")) {
            for (int size : List.of(0, 23, 24, 255, 256, 32768, 65000)) {
                byte[] body = new byte[size];
                int actual = baseline(topic, body).length;
                assertThat(BindingPayload.estimate(topic, MESSAGE_ID, MESSAGE_ID, body, actual).encodedBytes())
                        .isEqualTo(actual);
                assertThat(BindingPayload.estimate(topic, MESSAGE_ID, MESSAGE_ID, body, actual + 1).encodedBytes())
                        .isEqualTo(actual);
                assertThatThrownBy(() -> BindingPayload.estimate(topic, MESSAGE_ID, MESSAGE_ID, body, actual - 1))
                        .isInstanceOf(BindingFailure.class).hasMessage("COMMAND_PAYLOAD_TOO_LARGE");
            }
            int overhead = baseline(topic, new byte[65000]).length - 65000;
            byte[] exact = new byte[65536 - overhead];
            assertThat(BindingPayload.estimate(topic, MESSAGE_ID, MESSAGE_ID, exact, 65536).encodedBytes())
                    .isEqualTo(65536);
            for (int size : List.of(exact.length + 1, 65535, 65536)) {
                assertThatThrownBy(() -> BindingPayload.estimate(topic, MESSAGE_ID, MESSAGE_ID,
                        new byte[size], 65536)).hasMessage("COMMAND_PAYLOAD_TOO_LARGE");
            }
        }
    }

    private static byte[] baseline(String topic, byte[] body) {
        return TransitionScalars.encode(Map.of("topic", topic, "sender", MESSAGE_ID, "messageId", MESSAGE_ID,
                "body", body, "bodyHash", new byte[32], "bodyLength", (long) body.length));
    }

    @Test
    void largeRawSourceAndDerivedBodySucceedWithoutTruncation() {
        Fixture fixture = new Fixture(false);
        byte[] body = new byte[32768];
        Arrays.fill(body, (byte) 42);
        AppMessage message = bodyMessage(1, body);
        assertThat(fixture.engine.validate(message).isAccepted()).isTrue();
        fixture.apply(1, List.of(message));
        var receipt = BindingReceiptV1.decode(fixture.workflow.get(message.getMessageId()).orElseThrow());
        assertThat(receipt.accepted()).isTrue();
        assertThat(receipt.steps()).hasSize(2);
        assertThat(fixture.source.get(KEY)).hasValue(body);
        assertThat(fixture.target.get(KEY)).hasValue(body);
    }

    @Test
    void maximumBaselineBodyForwardsUnderDefaultsAndPinsTheWholeTeeWorkBoundary() {
        int overhead = baseline("source.v1", new byte[65000]).length - 65000;
        byte[] body = new byte[65536 - overhead];
        Arrays.fill(body, (byte) 42);
        assertThat(body).hasSize(65369);
        assertThat(baseline("source.v1", body)).hasSize(65536);
        AppMessage message = bodyMessage(1, body);
        Fixture defaults = new Fixture(false);
        assertThat(defaults.engine.validate(message).isAccepted()).isTrue();
        defaults.apply(1, List.of(message));
        assertThat(BindingReceiptV1.decode(defaults.workflow.get(message.getMessageId()).orElseThrow()).accepted())
                .isTrue();
        assertThat(defaults.source.get(KEY)).hasValue(body);
        assertThat(defaults.target.get(KEY)).hasValue(body);

        // Selected-event decode + candidate + raw mapping + derived dispatch. Source preparation is not shared.
        long shared = 65537L + 1 + (1 + body.length) + (1 + body.length);
        assertThat(defaults.engine.operationalStatus()).containsEntry("evaluationWork", shared);
        var estimate = BindingPayload.estimate("source.v1", MESSAGE_ID, MESSAGE_ID, body, 65536);
        int total = Math.toIntExact(1L + body.length + estimate.preparationWork() + shared);
        assertThat(total).isEqualTo(392650)
                .isLessThan(BindingIrV1.Limits.DEFAULT.maxExpressionWorkPerCascade());
        for (int allowance : List.of(total - 1, total)) {
            Fixture boundary = new Fixture(false, false, false, List.of(forward()),
                    payloadLimits(65536, allowance, 33554432));
            assertThat(boundary.engine.validate(message).isAccepted()).isTrue();
            boundary.apply(1, List.of(message));
            var receipt = BindingReceiptV1.decode(boundary.workflow.get(message.getMessageId()).orElseThrow());
            assertThat(receipt.accepted()).isEqualTo(allowance == total);
            if (allowance < total) {
                assertThat(receipt.code()).isEqualTo("EXPRESSION_CAPACITY_EXCEEDED");
                assertThat(boundary.source.values).isEmpty();
                assertThat(boundary.target.values).isEmpty();
            }
        }
        for (int allowance : List.of(Math.toIntExact(shared - 1), Math.toIntExact(shared))) {
            Fixture boundary = new Fixture(false, false, false, List.of(forward()),
                    payloadLimits(65536, 1048576, allowance));
            assertThat(boundary.engine.validate(message).isAccepted()).isTrue();
            boundary.apply(1, List.of(message));
            var receipt = BindingReceiptV1.decode(boundary.workflow.get(message.getMessageId()).orElseThrow());
            assertThat(receipt.accepted()).isEqualTo(allowance == shared);
            assertThat(boundary.engine.operationalStatus()).containsEntry("evaluationWork", (long) allowance);
            if (allowance < shared) {
                assertThat(receipt.code()).isEqualTo("EXPRESSION_CAPACITY_EXCEEDED");
                assertThat(boundary.source.values).isEmpty();
                assertThat(boundary.target.values).isEmpty();
                assertThat(boundary.targetKernel.lastContext).isNull();
            }
        }
        var second = new BindingIrV1.Binding("second-tee", "source", BindingProgram.BASELINE, List.of(),
                new BindingIrV1.CommandTarget("target", "put", BindingIrV1.Mapping.raw("body")));
        Fixture fanout = new Fixture(false, false, false, List.of(forward(), second));
        fanout.apply(1, List.of(message));
        var receipt = BindingReceiptV1.decode(fanout.workflow.get(message.getMessageId()).orElseThrow());
        assertThat(receipt.accepted()).isTrue();
        assertThat(receipt.steps()).hasSize(3);
        assertThat(fanout.target.get(KEY)).hasValue(body);
        assertThat(fanout.engine.operationalStatus()).containsEntry("evaluationWork", shared + 130741L);
    }

    @Test
    void admissionAndApplyRejectPredictablePayloadBeforeCodecOrKernel() {
        int exact = baseline("source.v1", BODY).length;
        for (int cap : List.of(exact - 1, exact, exact + 1)) {
            Fixture fixture = new Fixture(false, false, false, List.of(forward()),
                    payloadLimits(cap, 1048576, 16777216));
            AppMessage message = bodyMessage(1, BODY);
            if (cap < exact) fixture.sourceKernel.corruptCodec = true;
            assertThat(fixture.engine.validate(message).isAccepted()).isEqualTo(cap >= exact);
            if (cap < exact) assertThat(fixture.engine.validate(message).reason())
                    .isEqualTo("COMMAND_PAYLOAD_TOO_LARGE");
            fixture.apply(1, List.of(message));
            var receipt = BindingReceiptV1.decode(fixture.workflow.get(message.getMessageId()).orElseThrow());
            assertThat(receipt.accepted()).isEqualTo(cap >= exact);
            if (cap < exact) {
                assertThat(receipt.code()).isEqualTo("COMMAND_PAYLOAD_TOO_LARGE");
                assertThat(fixture.sourceKernel.lastContext).isNull();
            }
        }
    }

    @Test
    void mandatoryWorkAdmissionUsesBothAllowancesAndApplySaturatesWithoutCallingKernel() {
        var estimate = BindingPayload.estimate("source.v1", MESSAGE_ID, MESSAGE_ID, BODY, 65536);
        int mandatory = (int) (1 + BODY.length + estimate.preparationWork() + estimate.decodingWork());
        for (var limits : List.of(payloadLimits(65536, mandatory - 1, mandatory * 3),
                payloadLimits(65536, mandatory * 3, (int) estimate.decodingWork() - 1))) {
            Fixture fixture = new Fixture(false, false, false, List.of(forward()), limits);
            fixture.sourceKernel.corruptCodec = true;
            AppMessage message = bodyMessage(1, BODY);
            assertThat(fixture.engine.validate(message).reason()).isEqualTo("COMMAND_WORK_EXCEEDED");
            fixture.apply(1, List.of(message));
            assertThat(BindingReceiptV1.decode(fixture.workflow.get(message.getMessageId()).orElseThrow()).code())
                    .isEqualTo("COMMAND_WORK_EXCEEDED");
            assertThat(fixture.sourceKernel.lastContext).isNull();
        }
        AppMessage first = bodyMessage(1, BODY);
        AppMessage second = bodyMessage(2, BODY);
        Fixture probe = new Fixture(false);
        probe.apply(1, List.of(first));
        int blockWork = ((Number) probe.engine.operationalStatus().get("evaluationWork")).intValue();
        Fixture fixture = new Fixture(false, false, false, List.of(forward()),
                payloadLimits(65536, 262144, blockWork));
        AppMessage unbound = message(4, "target.v1", 47);
        fixture.apply(1, List.of(first, second, unbound));
        byte[] retained = fixture.workflow.get(first.getMessageId()).orElseThrow();
        assertThat(BindingReceiptV1.decode(retained).accepted()).isTrue();
        assertThat(BindingReceiptV1.decode(fixture.workflow.get(second.getMessageId()).orElseThrow()).code())
                .isEqualTo("EXPRESSION_CAPACITY_EXCEEDED");
        assertThat(BindingReceiptV1.decode(fixture.workflow.get(unbound.getMessageId()).orElseThrow()).accepted())
                .isTrue();
        assertThat(fixture.target.get(KEY)).hasValue(new byte[]{47});
        assertThat(fixture.engine.operationalStatus()).containsEntry("evaluationWork", (long) blockWork);
        fixture.sourceKernel.lastContext = null;
        AppMessage fresh = bodyMessage(3, BODY);
        fixture.apply(2, List.of(first, fresh));
        assertThat(fixture.workflow.get(first.getMessageId())).hasValue(retained);
        assertThat(BindingReceiptV1.decode(fixture.workflow.get(fresh.getMessageId()).orElseThrow()).accepted())
                .isTrue();
        assertThat(fixture.sourceKernel.lastContext.messageId()).containsExactly(fresh.getMessageId());
        assertThat(fixture.engine.operationalStatus()).containsEntry("replayed", 1);
        assertThat(fixture.workflow.get("expression-work".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                .isEmpty();
    }

    @Test
    void unboundSourceAcceptsFullHostBodyWithTinyEventAndSharedWorkLimits() {
        Fixture fixture = new Fixture(false, false, false, List.of(), payloadLimits(1, 262144, 1));
        byte[] body = new byte[65536];
        AppMessage message = bodyMessage(1, body);
        assertThat(fixture.engine.validate(message).isAccepted()).isTrue();
        fixture.apply(1, List.of(message));
        var receipt = BindingReceiptV1.decode(fixture.workflow.get(message.getMessageId()).orElseThrow());
        assertThat(receipt.accepted()).isTrue();
        assertThat(receipt.steps().getFirst().eventsProduced()).containsExactly(BindingProgram.BASELINE);
        assertThat(fixture.source.get(KEY)).hasValue(body);
        assertThat(fixture.engine.operationalStatus()).containsEntry("evaluationWork", 0L);
    }

    @Test
    void derivedDispatchWithoutSubscribersStillReservesBlockWorkBeforeItsCodecAndFacts() {
        AppMessage message = bodyMessage(1, BODY);
        Fixture probe = new Fixture(false);
        probe.apply(1, List.of(message));
        int blockWork = ((Number) probe.engine.operationalStatus().get("evaluationWork")).intValue();
        Fixture fixture = new Fixture(false, false, false, List.of(forward()),
                payloadLimits(65536, 262144, blockWork - 1));
        fixture.targetKernel.corruptCodec = true;
        fixture.apply(1, List.of(message));
        var receipt = BindingReceiptV1.decode(fixture.workflow.get(message.getMessageId()).orElseThrow());
        assertThat(receipt.code()).isEqualTo("EXPRESSION_CAPACITY_EXCEEDED");
        assertThat(fixture.targetKernel.lastContext).isNull();
        assertThat(fixture.source.values).isEmpty();
        assertThat(fixture.target.values).isEmpty();
        assertThat(fixture.engine.operationalStatus()).containsEntry("evaluationWork", (long) blockWork - 1);
    }

    @Test
    void nonArgumentCodecFailuresBecomeMalformedRejectionsButFatalErrorsPropagate() {
        AppMessage message = bodyMessage(1, BODY);
        Fixture sourceFailure = new Fixture(false);
        sourceFailure.sourceKernel.decodeFailure = new IllegalStateException("bad wire");
        assertThat(sourceFailure.engine.validate(message).reason()).isEqualTo("MALFORMED_SOURCE_COMMAND");
        sourceFailure.apply(1, List.of(message));
        assertThat(BindingReceiptV1.decode(sourceFailure.workflow.get(message.getMessageId()).orElseThrow()).code())
                .isEqualTo("MALFORMED_SOURCE_COMMAND");
        assertThat(sourceFailure.sourceKernel.lastContext).isNull();

        Fixture derivedFailure = new Fixture(false);
        derivedFailure.targetKernel.decodeFailure = new IndexOutOfBoundsException("bad derived wire");
        derivedFailure.apply(1, List.of(message));
        assertThat(BindingReceiptV1.decode(derivedFailure.workflow.get(message.getMessageId()).orElseThrow()).code())
                .isEqualTo("MALFORMED_DERIVED_COMMAND");
        assertThat(derivedFailure.source.values).isEmpty();
        assertThat(derivedFailure.targetKernel.lastContext).isNull();

        Fixture fatal = new Fixture(false);
        fatal.sourceKernel.decodeFatal = true;
        assertThatThrownBy(() -> fatal.engine.validate(message)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> fatal.apply(1, List.of(message))).isInstanceOf(AssertionError.class);
        assertThat(fatal.workflow.get(message.getMessageId())).isEmpty();
    }

    @Test
    void statelessAdmissionRunsAfterDecodeAndContextualChecksRemainApplyOnly() {
        AppMessage message = bodyMessage(1, BODY);
        Fixture rejected = new Fixture(false);
        rejected.sourceKernel.statelessRejection = "CONFIGURED_BOUND";
        assertThat(rejected.engine.validate(message).reason()).isEqualTo("CONFIGURED_BOUND");
        assertThat(rejected.sourceKernel.statelessCalls).isEqualTo(1);
        assertThat(rejected.sourceKernel.contextualCalls).isZero();
        rejected.sourceKernel.decodeFailure = new IllegalStateException("wire failure precedes admission");
        assertThat(rejected.engine.validate(message).reason()).isEqualTo("MALFORMED_SOURCE_COMMAND");
        assertThat(rejected.sourceKernel.statelessCalls).isEqualTo(1);

        Fixture contextual = new Fixture(false);
        contextual.sourceKernel.contextualRejection = true;
        assertThat(contextual.engine.validate(message).isAccepted()).isTrue();
        assertThat(contextual.sourceKernel.contextualCalls).isZero();
        contextual.apply(1, List.of(message));
        assertThat(contextual.sourceKernel.contextualCalls).isEqualTo(1);
        assertThat(BindingReceiptV1.decode(contextual.workflow.get(message.getMessageId()).orElseThrow()).code())
                .isEqualTo("ADMISSION");
        assertThat(contextual.sourceKernel.lastContext).isNull();
    }

    @Test
    void unexpectedStatelessAdmissionFailuresAreNotMisclassifiedAsMalformedCommands() {
        Fixture fixture = new Fixture(false);
        AppMessage message = bodyMessage(1, BODY);
        fixture.sourceKernel.admissionFailure = new IllegalArgumentException("broken admission implementation");
        assertThatThrownBy(() -> fixture.engine.validate(message)).isSameAs(fixture.sourceKernel.admissionFailure);
        fixture.sourceKernel.admissionFailure = null;
        fixture.sourceKernel.nullAdmission = true;
        assertThatThrownBy(() -> fixture.engine.validate(message)).isInstanceOf(NullPointerException.class)
                .hasMessage("kernel returned null stateless admission");
    }

    @Test
    void contextualOverrideCannotBypassStatelessAdmissionForDerivedCommands() {
        Fixture fixture = new Fixture(false);
        fixture.targetKernel.statelessRejection = "CONFIGURED_BOUND";
        fixture.targetKernel.contextualAccept = true;
        AppMessage message = bodyMessage(1, BODY);
        fixture.apply(1, List.of(message));
        var receipt = BindingReceiptV1.decode(fixture.workflow.get(message.getMessageId()).orElseThrow());
        assertThat(receipt.code()).isEqualTo("ADMISSION");
        assertThat(receipt.failedStepOrdinal()).isEqualTo(1);
        assertThat(fixture.targetKernel.statelessCalls).isEqualTo(1);
        assertThat(fixture.targetKernel.contextualCalls).isZero();
        assertThat(fixture.targetKernel.lastContext).isNull();
        assertThat(fixture.source.values).isEmpty();
        assertThat(fixture.target.values).isEmpty();
    }

    private static BindingIrV1.Limits payloadLimits(int bytes, int cascade, int block) {
        var defaults = BindingIrV1.Limits.DEFAULT;
        return new BindingIrV1.Limits(8, 32, 4096, bytes, 2, 8, defaults.maxFunctionInputBytes(),
                128, 16, defaults.maxExpressionValueBytes(), cascade, block);
    }

    private static AppMessage bodyMessage(int identity, byte[] body) {
        byte[] id = new byte[32];
        id[31] = (byte) identity;
        return AppMessage.builder().messageId(id).chainId("chain").topic("source.v1").sender(new byte[32])
                .senderSeq(identity).expiresAt(Long.MAX_VALUE).body(body)
                .authScheme(0).authProof(new byte[]{1}).build();
    }

    @Test
    void reachableResourceLimitsRejectWithoutCommittingEarlierBusinessPlansOrCallingEmitter() {
        var ordinary = BindingIrV1.Limits.DEFAULT;
        var cases = List.of(
                new LimitCase("LIMIT_FANOUT", limits(8, 1, 4096, 4096),
                        List.of(forward(), effect("second"))),
                new LimitCase("LIMIT_DEPTH", limits(1, 32, 4096, 4096), List.of(forward(),
                        new BindingIrV1.Binding("too-deep", "target", BindingProgram.BASELINE, List.of(),
                                new BindingIrV1.EffectTarget("test", "app-final", "none", 0,
                                        BindingIrV1.Mapping.identity())))),
                new LimitCase("COMMAND_PAYLOAD_TOO_LARGE", limits(8, 32, 1, 4096), List.of(forward())),
                new LimitCase("FUNCTION_INPUT_LIMIT", limits(8, 32, 4096, 1), List.of(
                        calculatedEffect(new BindingSourceV1.Function("sha-256",
                                List.of(new BindingSourceV1.Field("bodyHash")))))),
                new LimitCase("FUNCTION_OUTPUT_LIMIT", limits(8, 32, 4096, 1), List.of(
                        calculatedEffect(new BindingSourceV1.Function("sha-256",
                                List.of(new BindingSourceV1.Field("body")))))),
                new LimitCase("LOOKUP_KEY_LIMIT", limits(8, 32, 4096, 1),
                        List.of(lookupEffect(new byte[]{1, 2}))),
                new LimitCase("LOOKUP_KEY_INVALID", ordinary, List.of(lookupEffect(new byte[256]))),
                new LimitCase("EFFECT_PAYLOAD_LIMIT", ordinary,
                        List.of(forward(), calculatedEffect(new BindingSourceV1.Literal(new byte[20_000])))),
                new LimitCase("EFFECT_EXPIRY_LIMIT", ordinary, List.of(forward(),
                        new BindingIrV1.Binding("too-late", "target", BindingProgram.BASELINE, List.of(),
                                new BindingIrV1.EffectTarget("test", "app-final", "chain", 100_001,
                                        BindingIrV1.Mapping.identity())))));
        for (var test : cases) {
            Fixture fixture = new Fixture(false, false, false, test.bindings(), test.limits());
            fixture.effectCapacity = 10;
            fixture.apply(); // Any infrastructure exception instead of a receipt fails this test.
            var receipt = BindingReceiptV1.decode(fixture.workflow.get(MESSAGE_ID).orElseThrow());
            assertThat(receipt.accepted()).as(test.code()).isFalse();
            assertThat(receipt.code()).as(test.code()).isEqualTo(test.code());
            if (test.code().equals("COMMAND_PAYLOAD_TOO_LARGE")) {
                assertThat(fixture.sourceKernel.lastContext).isNull();
            } else {
                assertThat(fixture.sourceKernel.lastContext).as("source plan evaluated: " + test.code()).isNotNull();
            }
            assertThat(fixture.source.values).as(test.code()).isEmpty();
            assertThat(fixture.target.values).as(test.code()).isEmpty();
            assertThat(fixture.emitted).as(test.code()).isEmpty();
            assertThat(fixture.claims).as(test.code()).isZero();
            assertThat(receipt.steps()).anySatisfy(step -> {
                assertThat(step.ordinal()).isEqualTo(receipt.failedStepOrdinal());
                assertThat(step.status()).isEqualTo("REJECTED");
                assertThat(step.code()).isEqualTo(test.code());
            });
        }
    }

    private record LimitCase(String code, BindingIrV1.Limits limits, List<BindingIrV1.Binding> bindings) { }

    private static BindingIrV1.Limits limits(int depth, int derived, int eventBytes, int functionBytes) {
        var defaults = BindingIrV1.Limits.DEFAULT;
        return new BindingIrV1.Limits(depth, derived, defaults.maxDerivedPerBlock(), eventBytes,
                defaults.maxLookupsPerCondition(), defaults.maxFunctionCallsPerMapping(), functionBytes,
                defaults.maxExpressionNodes(), defaults.maxExpressionDepth(), defaults.maxExpressionValueBytes(),
                defaults.maxExpressionWorkPerCascade(), defaults.maxExpressionWorkPerBlock());
    }

    private static BindingIrV1.Binding calculatedEffect(BindingSourceV1 source) {
        return new BindingIrV1.Binding("calculated", "source", BindingProgram.BASELINE, List.of(),
                new BindingIrV1.EffectTarget("test", "app-final", "none", 0, BindingIrV1.Mapping.fields(
                        List.of(new BindingIrV1.Assignment("value", source)))));
    }

    private static BindingIrV1.Binding lookupEffect(byte[] key) {
        return new BindingIrV1.Binding("lookup", "source", BindingProgram.BASELINE, List.of(
                new BindingIrV1.LookupClause("target", new BindingSourceV1.Literal(key),
                        BindingIrV1.Expectation.EXISTS, null)),
                new BindingIrV1.EffectTarget("test", "app-final", "none", 0, BindingIrV1.Mapping.identity()));
    }

    @Test
    void interleavedIngressTopicsKeepGlobalOrderIncludingDerivedSteps() {
        Fixture fixture = new Fixture(false);
        List<String> order = new java.util.ArrayList<>();
        fixture.sourceKernel.observer = context -> order.add("source:" + context.originalMessageIndex());
        fixture.targetKernel.observer = context -> order.add("target:" + context.originalMessageIndex());
        var messages = List.of(message(1, "source.v1", 41), message(9, "unrelated.v1", 99),
                message(2, "target.v1", 11), message(3, "source.v1", 43), message(4, "target.v1", 12));
        AppBlock block = new AppBlock(1, "chain", 1, new byte[32], 0, new byte[0], 1,
                new byte[32], new byte[32], messages, new byte[32], FinalityCert.empty());
        fixture.engine.apply(AppBlockExecutionContext.fromValidatedBlock(block)
                .routeToMessageIndexes(List.of(0, 2, 3, 4)), fixture);
        assertThat(order).containsExactly("source:0", "target:0", "target:2",
                "source:3", "target:3", "target:4");
        assertThat(fixture.source.get(KEY)).hasValue(new byte[]{43});
        assertThat(fixture.target.get(KEY)).hasValue(new byte[]{12});
        for (AppMessage message : messages) {
            if (message.getTopic().equals("unrelated.v1")) {
                assertThat(fixture.workflow.get(message.getMessageId())).isEmpty();
                continue;
            }
            assertThat(BindingReceiptV1.decode(fixture.workflow.get(message.getMessageId()).orElseThrow()).accepted())
                    .isTrue();
        }
        assertThat(fixture.engine.operationalStatus()).containsEntry("derived", 2).containsEntry("accepted", 4);
    }

    @Test
    void rejectedCascadesConsumeBlockDerivationsButDoNotPoisonNonDerivingIngressOrNextBlock() {
        var limits = new BindingIrV1.Limits(8, 32, 2, 4096, 2, 8, 4096, 128, 16, 4096, 262144, 4194304);
        Fixture fixture = new Fixture(false, false, false, List.of(forward()), limits);
        fixture.targetKernel.rejectedValue = 42;
        var messages = List.of(message(1, "source.v1", 42), message(2, "target.v1", 11),
                message(3, "source.v1", 42), message(4, "source.v1", 42), message(5, "target.v1", 12));
        fixture.apply(1, messages);
        assertThat(fixture.source.values).isEmpty();
        assertThat(fixture.target.get(KEY)).hasValue(new byte[]{12});
        assertThat(BindingReceiptV1.decode(fixture.workflow.get(messages.get(0).getMessageId()).orElseThrow()).code())
                .isEqualTo("TARGET_REJECTED");
        assertThat(BindingReceiptV1.decode(fixture.workflow.get(messages.get(2).getMessageId()).orElseThrow()).code())
                .isEqualTo("TARGET_REJECTED");
        assertThat(BindingReceiptV1.decode(fixture.workflow.get(messages.get(3).getMessageId()).orElseThrow()).code())
                .isEqualTo("CAPACITY_EXCEEDED");
        assertThat(BindingReceiptV1.decode(fixture.workflow.get(messages.get(4).getMessageId()).orElseThrow())
                .accepted())
                .isTrue();
        assertThat(fixture.engine.operationalStatus()).containsEntry("derived", 2)
                .containsEntry("accepted", 2).containsEntry("rejected", 3);
        assertThat(fixture.workflow.values).hasSize(messages.size()); // Only authenticated receipts remain.
        assertThat(fixture.workflow.get("work".getBytes(java.nio.charset.StandardCharsets.US_ASCII))).isEmpty();
        assertThat(fixture.workflow.get("expression-work".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                .isEmpty();
        var next = message(6, "source.v1", 43);
        fixture.apply(2, List.of(next));
        assertThat(BindingReceiptV1.decode(fixture.workflow.get(next.getMessageId()).orElseThrow()).accepted())
                .isTrue();
        assertThat(fixture.source.get(KEY)).hasValue(new byte[]{43});
        assertThat(fixture.target.get(KEY)).hasValue(new byte[]{43});
        assertThat(fixture.engine.operationalStatus()).containsEntry("derived", 1).containsEntry("rejected", 0);
    }

    private static AppMessage message(int identity, String topic, int value) {
        byte[] id = new byte[32];
        id[31] = (byte) identity;
        return AppMessage.builder().messageId(id).chainId("chain").topic(topic).sender(new byte[32])
                .senderSeq(identity).expiresAt(Long.MAX_VALUE).body(new byte[]{(byte) value})
                .authScheme(0).authProof(new byte[]{1}).build();
    }

    @Test
    void effectShortfallRejectsEveryPlanBeforeEmitterAndKeepsFailedBindingLocation() {
        Fixture fixture = new Fixture(false, false, false, List.of(forward(), effect("notify")));
        fixture.apply();
        assertThat(fixture.source.values).isEmpty();
        assertThat(fixture.target.values).isEmpty();
        assertThat(fixture.emitted).isEmpty();
        var receipt = BindingReceiptV1.decode(fixture.workflow.get(MESSAGE_ID).orElseThrow());
        assertThat(receipt.code()).isEqualTo("EFFECT_CAPACITY_EXCEEDED");
        assertThat(receipt.failedStepOrdinal()).isEqualTo(2);
        assertThat(receipt.steps().stream().filter(step -> step.ordinal() == 2).findFirst().orElseThrow().bindingId())
                .isEqualTo("notify");
    }

    @Test
    void successfulEffectUsesDeterministicScopeAndSourceProvenance() {
        Fixture fixture = new Fixture(false, false, false, List.of(effect("notify")));
        fixture.effectCapacity = 1;
        fixture.apply();
        assertThat(fixture.receipt().get(3)).isEqualTo("ACCEPTED");
        assertThat(fixture.emitted).singleElement().satisfies(intent -> {
            assertThat(intent.sourceMessageId()).containsExactly(MESSAGE_ID);
            assertThat(intent.scope()).isEqualTo("binding/" + HexFormat.of().formatHex(
                    EventBindingWorkflow.derivedId(MESSAGE_ID, "notify", 1)));
        });
        fixture.apply();
        assertThat(fixture.emitted).hasSize(1);
    }

    private static BindingIrV1.Binding effect(String id) {
        return new BindingIrV1.Binding(id, "source", BindingProgram.BASELINE, List.of(),
                new BindingIrV1.EffectTarget("test", "app-final", "none", 0, BindingIrV1.Mapping.identity()));
    }

    @Test
    void expressionErrorRetainsBindingAndExactClauseLocation() {
        var division = new BindingExpressionV1.Call("div", List.of(
                new BindingExpressionV1.Literal(1L), new BindingExpressionV1.Literal(0L)));
        var expression = new BindingExpressionV1(BindingExpressionV1.Type.BOOLEAN,
                new BindingExpressionV1.Call("eq", List.of(division, new BindingExpressionV1.Literal(1L))));
        var binding = new BindingIrV1.Binding("guarded", "source", BindingProgram.BASELINE, List.of(
                new BindingIrV1.FieldClause("bodyLength", BindingIrV1.Operator.GE,
                        List.of(new BindingSourceV1.Literal(1L))), new BindingIrV1.ExpressionClause(expression)),
                new BindingIrV1.CommandTarget("target", "put", BindingIrV1.Mapping.raw("body")));
        Fixture fixture = new Fixture(false, false, false, List.of(binding));
        fixture.apply();
        var receipt = BindingReceiptV1.decode(fixture.workflow.get(MESSAGE_ID).orElseThrow());
        assertThat(receipt.code()).isEqualTo("EXPRESSION_DIVISION_BY_ZERO");
        assertThat(receipt.steps()).singleElement().satisfies(step -> {
            assertThat(step.eventsProduced()).containsExactly(BindingProgram.BASELINE);
            assertThat(step.conditions()).containsExactly(new BindingReceiptV1.Condition("guarded", 1));
        });
        assertThat(fixture.source.values).isEmpty();
    }

    @Test
    void failedLaterMappingNamesAttemptedChildAndRetainsParentConditions() {
        var invalidMapping = new BindingIrV1.Binding("bad-map", "source", BindingProgram.BASELINE, List.of(),
                new BindingIrV1.EffectTarget("test", "app-final", "none", 0, BindingIrV1.Mapping.fields(List.of(
                        new BindingIrV1.Assignment("payload", new BindingSourceV1.Function("cbor-field", List.of(
                                new BindingSourceV1.Field("body"), new BindingSourceV1.Literal("missing"))))))));
        Fixture fixture = new Fixture(false, false, false, List.of(forward(), invalidMapping));
        fixture.apply();
        var receipt = BindingReceiptV1.decode(fixture.workflow.get(MESSAGE_ID).orElseThrow());
        assertThat(receipt.code()).isEqualTo("FUNCTION_INVALID_CBOR");
        assertThat(receipt.failedStepOrdinal()).isEqualTo(2);
        assertThat(receipt.steps()).hasSize(2);
        assertThat(receipt.steps().getFirst().conditions()).containsExactly(
                new BindingReceiptV1.Condition("forward", -1), new BindingReceiptV1.Condition("bad-map", -1));
        assertThat(receipt.steps().getLast().bindingId()).isEqualTo("bad-map");
        assertThat(receipt.steps().getLast().depth()).isEqualTo(1);
        assertThat(fixture.source.values).isEmpty();
        assertThat(fixture.target.values).isEmpty();
    }

    private static BindingIrV1.Binding forward() {
        return new BindingIrV1.Binding("forward", "source", BindingProgram.BASELINE, List.of(),
                new BindingIrV1.CommandTarget("target", "put", BindingIrV1.Mapping.raw("body")));
    }

    @Test
    void rejectedCascadeRetainsSharedOwnerReservationButNotBusinessPlans() {
        Fixture fixture = new Fixture(true, false, true);
        fixture.apply();
        assertThat(fixture.source.get(KEY)).isEmpty();
        assertThat(fixture.target.values).isEmpty();
        assertThat(fixture.source.get(WORK_KEY))
                .hasValue(ByteBuffer.allocate(12).putLong(1).putInt(1).array());
        assertThat(fixture.receipt().get(5)).isEqualTo("TARGET_REJECTED");
        fixture.apply(); // An already-receipted source must not reserve work again.
        assertThat(fixture.source.writes).isEqualTo(1);
    }

    @Test
    void exhaustedSharedBudgetRejectsBeforeFactsAndDoesNotRefundEarlierWork() {
        Fixture fixture = new Fixture(false, false, true);
        fixture.source.put(WORK_KEY, ByteBuffer.allocate(12).putLong(1).putInt(1).array());
        fixture.targetKernel.corruptFacts = true; // Would throw if called before the reservation fence.
        fixture.apply();
        assertThat(fixture.receipt().get(5)).isEqualTo("CRYPTO_WORK_EXCEEDED");
        assertThat(fixture.source.get(KEY)).isEmpty();
        assertThat(fixture.source.writes).isEqualTo(1);
    }

    @Test
    void businessPlanCannotOverwriteAnOwnerAccountingKey() {
        Fixture fixture = new Fixture(false, false, true);
        fixture.sourceKernel.writeAccountingKey = true;
        fixture.apply();
        assertThat(fixture.receipt().get(5)).isEqualTo("RESERVED_ACCOUNTING_KEY");
        assertThat(fixture.source.values).isEmpty();
    }

    @Test
    void derivedRejectionDiscardsSourceAndTargetPlansButRetainsReceipt() {
        Fixture fixture = new Fixture(true);
        fixture.apply();
        assertThat(fixture.source.values).isEmpty();
        assertThat(fixture.target.values).isEmpty();
        assertThat(fixture.receipt().get(3)).isEqualTo("REJECTED");
        assertThat(fixture.receipt().get(4)).isEqualTo(1L);
        assertThat(fixture.receipt().get(5)).isEqualTo("TARGET_REJECTED");
        assertThat(fixture.claims).isZero();
    }

    @Test
    void successfulCascadeCommitsOnceAndPreservesSenderAndOriginalIndex() {
        Fixture fixture = new Fixture(false);
        fixture.apply();
        assertThat(fixture.source.get(KEY)).hasValue(BODY);
        assertThat(fixture.target.get(KEY)).hasValue(BODY);
        assertThat(fixture.receipt().get(3)).isEqualTo("ACCEPTED");
        assertThat(fixture.targetKernel.lastContext.sender()).containsExactly(new byte[32]);
        assertThat(fixture.targetKernel.lastContext.originalMessageIndex()).isZero();
        assertThat(fixture.targetKernel.lastContext.messageId())
                .containsExactly(EventBindingWorkflow.derivedId(MESSAGE_ID, "forward", 1));
        byte[] receipt = fixture.workflow.get(MESSAGE_ID).orElseThrow();

        fixture.apply();
        assertThat(fixture.claims).isEqualTo(1);
        assertThat(fixture.workflow.get(MESSAGE_ID)).hasValue(receipt);
        assertThat(fixture.source.writes).isEqualTo(1);
        assertThat(fixture.target.writes).isEqualTo(1);
    }

    @Test
    void infrastructureCommitFailurePropagatesInsteadOfProducingRejectionReceipt() {
        Fixture fixture = new Fixture(false);
        fixture.target.failWrites = true;
        assertThatThrownBy(fixture::apply).isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated storage failure");
        // The enclosing host block transaction, not this workflow, rolls back infrastructure failures.
        assertThat(fixture.workflow.get(MESSAGE_ID)).isEmpty();
    }

    @Test
    void declaredForeignFactsSeeEarlierOverlayWritesWithoutReceivingWriters() {
        Fixture fixture = new Fixture(false, true);
        fixture.apply();
        assertThat(fixture.targetKernel.foreignValue).containsExactly(BODY);
        assertThat(fixture.receipt().get(3)).isEqualTo("ACCEPTED");
    }

    @Test
    void corruptPersistedFactsAreNotMisclassifiedAsMalformedCommands() {
        Fixture fixture = new Fixture(false);
        fixture.targetKernel.corruptFacts = true;
        assertThatThrownBy(fixture::apply).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("corrupt persisted entry");
        assertThat(fixture.source.values).isEmpty();
        assertThat(fixture.target.values).isEmpty();
        assertThat(fixture.workflow.get(MESSAGE_ID)).isEmpty();
    }

    private static final class Fixture implements CompositeWorkflowContext {
        final MemoryState source = new MemoryState();
        final MemoryState target = new MemoryState();
        final MemoryState workflow = new MemoryState();
        final PutKernel targetKernel;
        final PutKernel sourceKernel;
        final EventBindingWorkflow engine;
        final List<EffectIntent> emitted = new java.util.ArrayList<>();
        int effectCapacity;
        int claims;

        Fixture(boolean rejectTarget) {
            this(rejectTarget, false);
        }

        Fixture(boolean rejectTarget, boolean readSource) {
            this(rejectTarget, readSource, false);
        }

        Fixture(boolean rejectTarget, boolean readSource, boolean accounting) {
            this(rejectTarget, readSource, accounting, List.of(forward()));
        }

        Fixture(boolean rejectTarget, boolean readSource, boolean accounting, List<BindingIrV1.Binding> bindings) {
            this(rejectTarget, readSource, accounting, bindings, BindingIrV1.Limits.DEFAULT);
        }

        Fixture(boolean rejectTarget, boolean readSource, boolean accounting, List<BindingIrV1.Binding> bindings,
                BindingIrV1.Limits limits) {
            targetKernel = new PutKernel(rejectTarget, readSource);
            sourceKernel = new PutKernel(false);
            sourceKernel.ownsBudget = accounting;
            targetKernel.requestsBudget = accounting;
            var sourceGeneration = new ComponentGeneration("source", "1", 1);
            var targetGeneration = new ComponentGeneration("target", "1", 1);
            var ir = new BindingIrV1(List.of(
                    new BindingIrV1.Component("source", "test", "source.v1", Map.of(), 0),
                    new BindingIrV1.Component("target", "test", "target.v1", Map.of(), 0)),
                    bindings,
                    limits);
            var program = new BindingProgram(ir, Map.of("source", sourceKernel, "target", targetKernel));
            var descriptor = new WorkflowDescriptor(EventBindingWorkflow.ID, "1",
                    List.of("source.v1", "target.v1"), 1, 0, List.of(sourceGeneration, targetGeneration), 0);
            engine = new EventBindingWorkflow(program, descriptor,
                    Map.of("source", sourceGeneration, "target", targetGeneration),
                            AppChainTestProfiles.enabledEffects(10));
        }

        void apply() {
            AppMessage message = AppMessage.builder().messageId(MESSAGE_ID).chainId("chain").topic("source.v1")
                    .sender(new byte[32]).senderSeq(1).expiresAt(Long.MAX_VALUE).body(BODY)
                    .authScheme(0).authProof(new byte[]{1}).build();
            apply(1, List.of(message));
        }

        void apply(long height, List<AppMessage> messages) {
            AppBlock block = new AppBlock(1, "chain", height, new byte[32], 0, new byte[0], height,
                    new byte[32], new byte[32], messages, new byte[32], FinalityCert.empty());
            engine.apply(AppBlockExecutionContext.fromValidatedBlock(block), this);
        }

        List<?> receipt() { return (List<?>) BindingCbor.decode(workflow.get(MESSAGE_ID).orElseThrow(), 65536); }
        @Override public AppStateWriter state(ComponentGeneration participant) {
            return participant.componentId().equals("source") ? source : target;
        }
        @Override public AppEffectEmitter effects(ComponentGeneration owner) {
            return new AppEffectEmitter() {
                @Override public EffectId emit(EffectIntent intent) {
                    if (emitted.size() >= effectCapacity) throw new AssertionError("preflight missed effect shortfall");
                    var id = new EffectId("chain", 1, emitted.size());
                    emitted.add(intent);
                    return id;
                }
                @Override public long pendingCount() { return emitted.size(); }
            };
        }
        @Override public AppStateWriter workflowState() { return workflow; }
        @Override public int remainingEffectCapacity() { return effectCapacity - emitted.size(); }
        @Override public ClaimResult claim(String id, byte[] hash) { claims++; return ClaimResult.CLAIMED; }
    }

    private static final class PutKernel implements TransitionKernel<byte[], Boolean> {
        private final boolean reject;
        private final boolean readSource;
        private boolean corruptFacts;
        private boolean corruptCodec;
        private RuntimeException decodeFailure;
        private boolean decodeFatal;
        private String statelessRejection;
        private boolean contextualRejection;
        private boolean contextualAccept;
        private RuntimeException admissionFailure;
        private boolean nullAdmission;
        private int statelessCalls;
        private int contextualCalls;
        private boolean ownsBudget;
        private boolean requestsBudget;
        private boolean writeAccountingKey;
        private byte[] foreignValue;
        private TransitionContext lastContext;
        private int rejectedValue = -1;
        private java.util.function.Consumer<TransitionContext> observer = ignored -> { };
        PutKernel(boolean reject) { this(reject, false); }
        PutKernel(boolean reject, boolean readSource) { this.reject = reject; this.readSource = readSource; }
        @Override public MessageCodec<byte[]> codec() {
            if (corruptCodec) throw new AssertionError("predictable admission must precede codec access");
            var delegate = new OrderedLogKernel().codec();
            return new MessageCodec<>() {
                @Override public byte[] encode(byte[] value) { return delegate.encode(value); }
                @Override public byte[] decode(byte[] body) {
                    if (decodeFatal) throw new AssertionError("fatal codec failure");
                    if (decodeFailure != null) throw decodeFailure;
                    return delegate.decode(body);
                }
                @Override public Class<byte[]> type() { return byte[].class; }
            };
        }
        @Override public List<TransitionWorkBudget> workBudgets() {
            return ownsBudget ? List.of(new TransitionWorkBudget("crypto", WORK_KEY, 1)) : List.of();
        }
        @Override public AdmissionResult admit(byte[] command) {
            statelessCalls++;
            if (admissionFailure != null) throw admissionFailure;
            if (nullAdmission) return null;
            return statelessRejection == null ? AdmissionResult.accept() : AdmissionResult.reject(statelessRejection);
        }
        @Override public AdmissionResult admit(byte[] command, TransitionContext context) {
            contextualCalls++;
            if (contextualAccept) return AdmissionResult.accept();
            return contextualRejection ? AdmissionResult.reject("CONTEXTUAL_BOUND")
                    : TransitionKernel.super.admit(command, context);
        }
        @Override public List<TransitionWorkReference> workReferences() {
            return requestsBudget ? List.of(new TransitionWorkReference("source", "crypto")) : List.of();
        }
        @Override public Optional<TransitionWorkRequest> workRequest(byte[] command, TransitionContext context) {
            return requestsBudget ? Optional.of(new TransitionWorkRequest(workReferences().getFirst(), 1))
                    : Optional.empty();
        }
        @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state) {
            if (corruptFacts) throw new IllegalArgumentException("corrupt persisted entry");
            return true;
        }
        @Override public List<String> readParticipants() { return readSource ? List.of("source") : List.of(); }
        @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state,
                                       Map<String, AppStateReader> participants) {
            assertThat(participants.keySet()).containsExactlyElementsOf(readParticipants());
            assertThat(state).isNotInstanceOf(AppStateWriter.class);
            if (readSource) {
                assertThat(participants.get("source")).isNotInstanceOf(AppStateWriter.class);
                foreignValue = participants.get("source").get(KEY).orElseThrow();
                assertThatThrownBy(() -> participants.put("other", state))
                        .isInstanceOf(UnsupportedOperationException.class);
            }
            return facts(command, context, state);
        }
        @Override public TransitionDecision decide(byte[] command, TransitionContext context, Boolean facts) {
            lastContext = context;
            observer.accept(context);
            return reject || command.length > 0 && Byte.toUnsignedInt(command[0]) == rejectedValue
                    ? TransitionDecision.reject("TARGET_REJECTED", "fixture")
                    : TransitionDecision.approve(TransitionPlan.mutations(List.of(
                            StateMutation.put(writeAccountingKey ? WORK_KEY : KEY, command))));
        }
        @Override public List<CommandDescriptor> commands() {
            return List.of(new CommandDescriptor("put", CommandDescriptor.Layout.RAW_BYTES, 0, List.of()));
        }
        @Override public List<EventDescriptor> events() { return List.of(); }
        @Override public ConfigurationDescriptor configuration() { return ConfigurationDescriptor.empty(); }
    }

    private static final class MemoryState implements AppStateWriter {
        final Map<String, byte[]> values = new HashMap<>();
        boolean failWrites;
        int writes;
        @Override public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(values.get(HexFormat.of().formatHex(key))).map(byte[]::clone);
        }
        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public void put(byte[] key, byte[] value) {
            if (failWrites) throw new IllegalStateException("simulated storage failure");
            writes++;
            values.put(HexFormat.of().formatHex(key), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(HexFormat.of().formatHex(key)); }
    }
}
