package org.yanoproject.x.composite.bindings;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine.AdmissionResult;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.FinalityCert;
import org.yanoproject.api.appchain.codec.MessageCodec;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.EventDescriptor;
import org.yanoproject.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.api.appchain.transition.StateMutation;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionPlan;
import org.yanoproject.api.appchain.transition.TransitionWorkBudget;
import org.yanoproject.api.appchain.transition.TransitionWorkReference;
import org.yanoproject.api.appchain.transition.TransitionWorkRequest;
import org.yanoproject.appchain.testkit.AppChainTestProfiles;
import org.yanoproject.x.composite.ComponentGeneration;
import org.yanoproject.x.composite.CompositeWorkflowContext;
import org.yanoproject.x.composite.WorkflowDescriptor;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;

import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-031.3 Phase 0 characterization: pins the admission, work-charging, receipt-compaction and pre-pool
 * behaviour that the policy plane changes. Phase 2 (receipt layout) and Phase 3 (rule evaluation) must update
 * these pins deliberately and explain every difference in the implementation ledger.
 */
class AdmissionCharacterizationTest {
    private static final byte[] BODY = {42};
    private static final byte[] WORK_KEY = {9};
    private static final byte[] TARGET_WORK_KEY = {10};

    @Test
    void sourceKernelAdmissionRejectsBeforeWorkReservationFactsOrDecision() {
        Fixture fixture = new Fixture();
        fixture.source.statelessRejection = "CONFIGURED_BOUND";
        AppMessage message = message(1);
        fixture.apply(List.of(message));

        byte[] stored = fixture.workflow.get(message.getMessageId()).orElseThrow();
        var receipt = BindingReceiptV1.decode(stored);
        assertThat(receipt.accepted()).isFalse();
        assertThat(receipt.code()).isEqualTo("ADMISSION");
        assertThat(receipt.failedStepOrdinal()).isZero();
        assertThat(receipt.steps()).singleElement().satisfies(step -> {
            assertThat(step.ordinal()).isZero();
            assertThat(step.bindingId()).isNull();
            assertThat(step.targetComponentId()).isEqualTo("source");
            assertThat(step.eventsProduced()).isEmpty();
            assertThat(step.conditions()).isEmpty();
            assertThat(step.status()).isEqualTo("REJECTED");
        });
        assertThat(HexFormat.of().formatHex(stored)).isEqualTo(SOURCE_ADMISSION_RECEIPT);
        assertThat(fixture.sourceState.get(WORK_KEY)).isEmpty();
        assertThat(fixture.source.factsCalls).isZero();
        assertThat(fixture.source.decideCalls).isZero();
        assertThat(fixture.sourceState.values).isEmpty();
        assertThat(fixture.targetState.values).isEmpty();
        assertThat(fixture.evaluationWork()).isEqualTo(SOURCE_ADMISSION_WORK);
    }

    @Test
    void derivedKernelAdmissionRejectsWholeCascadeAndKeepsParentTrace() {
        Fixture fixture = new Fixture();
        fixture.target.statelessRejection = "CONFIGURED_BOUND";
        AppMessage message = message(1);
        fixture.apply(List.of(message));

        byte[] stored = fixture.workflow.get(message.getMessageId()).orElseThrow();
        var receipt = BindingReceiptV1.decode(stored);
        assertThat(receipt.code()).isEqualTo("ADMISSION");
        assertThat(receipt.failedStepOrdinal()).isEqualTo(1);
        assertThat(receipt.steps()).hasSize(2);
        assertThat(receipt.steps().getFirst().status()).isEqualTo("PLANNED");
        assertThat(receipt.steps().getFirst().conditions())
                .containsExactly(new BindingReceiptV1.Condition("forward", -1));
        assertThat(receipt.steps().get(1).status()).isEqualTo("REJECTED");
        assertThat(receipt.steps().get(1).bindingId()).isEqualTo("forward");
        assertThat(HexFormat.of().formatHex(stored)).isEqualTo(DERIVED_ADMISSION_RECEIPT);
        assertThat(fixture.target.factsCalls).isZero();
        // The accepted source step's crypto reservation is accounting, not a business plan: it stays charged.
        assertThat(fixture.sourceState.values).containsOnlyKeys(HexFormat.of().formatHex(WORK_KEY));
        assertThat(fixture.targetState.values).isEmpty();
        assertThat(fixture.evaluationWork()).isEqualTo(DERIVED_ADMISSION_WORK);
    }

    @Test
    void acceptedAndRejectedCascadesShareOneUnrefundedBlockCounter() {
        Fixture accepted = new Fixture();
        accepted.apply(List.of(message(1)));
        assertThat(accepted.evaluationWork()).isEqualTo(ACCEPTED_WORK);

        Fixture mixed = new Fixture();
        mixed.target.rejectedValue = 7;
        AppMessage rejected = message(1, new byte[]{7});
        AppMessage legitimate = message(2);
        mixed.apply(List.of(rejected, legitimate));
        assertThat(BindingReceiptV1.decode(mixed.workflow.get(rejected.getMessageId()).orElseThrow()).code())
                .isEqualTo("TARGET_REJECTED");
        assertThat(BindingReceiptV1.decode(mixed.workflow.get(legitimate.getMessageId()).orElseThrow()).accepted())
                .isTrue();
        assertThat(mixed.evaluationWork()).isEqualTo(TARGET_REJECTED_WORK + ACCEPTED_WORK);
        assertThat(mixed.targetState.values).hasSize(1);
    }

    @Test
    void prePoolValidationRejectsUnknownMalformedAndStatelessRejectedCommands() {
        Fixture fixture = new Fixture();
        AppMessage unknown = AppMessage.builder().messageId(new byte[32]).chainId("chain").topic("unknown.v1")
                .sender(new byte[32]).senderSeq(1).expiresAt(Long.MAX_VALUE).body(BODY)
                .authScheme(0).authProof(new byte[]{1}).build();
        assertThat(fixture.engine.validate(unknown).reason()).isEqualTo("UNKNOWN_BINDING_SOURCE");
        assertThat(fixture.engine.validate(message(1)).isAccepted()).isTrue();
        fixture.source.malformed = true;
        assertThat(fixture.engine.validate(message(1)).reason()).isEqualTo("MALFORMED_SOURCE_COMMAND");
        fixture.source.malformed = false;
        fixture.source.statelessRejection = "CONFIGURED_BOUND";
        assertThat(fixture.engine.validate(message(1)).reason()).isEqualTo("CONFIGURED_BOUND");
        assertThat(fixture.source.factsCalls).isZero();
        assertThat(fixture.workflow.values).isEmpty();
    }

    @Test
    void contextualAdmissionAloneRejectsAtApplyButNotAtIngress() {
        Fixture fixture = new Fixture();
        fixture.source.contextualRejection = "CONTEXT_BOUND";
        AppMessage message = message(1);
        assertThat(fixture.engine.validate(message).isAccepted()).isTrue();
        assertThat(fixture.source.contextualCalls).isZero();
        fixture.apply(List.of(message));
        var receipt = BindingReceiptV1.decode(fixture.workflow.get(message.getMessageId()).orElseThrow());
        assertThat(receipt.code()).isEqualTo("ADMISSION");
        assertThat(receipt.failedStepOrdinal()).isZero();
        assertThat(fixture.source.contextualCalls).isEqualTo(1);
        assertThat(fixture.sourceState.get(WORK_KEY)).isEmpty();
        assertThat(fixture.source.workRequests).isZero();
    }

    @Test
    void ingressNeverRequestsWorkReadsFactsOrDecides() {
        Fixture fixture = new Fixture();
        assertThat(fixture.engine.validate(message(1)).isAccepted()).isTrue();
        assertThat(fixture.source.workRequests).isZero();
        assertThat(fixture.source.factsCalls).isZero();
        assertThat(fixture.source.decideCalls).isZero();
        Fixture tight = new Fixture(new BindingIrV1.Limits(8, 32, 4096, 65536, 2, 8, 65536, 128, 16, 65536,
                64, 33554432, 4));
        AppMessage large = message(2, new byte[128]);
        assertThat(tight.engine.validate(large).reason()).isEqualTo("COMMAND_WORK_EXCEEDED");
        assertThat(tight.source.statelessCalls).isZero();
    }

    @Test
    void derivedAdmissionRejectionReservesNoTargetCryptoWork() {
        Fixture fixture = new Fixture(BindingIrV1.Limits.DEFAULT, true);
        fixture.target.statelessRejection = "CONFIGURED_BOUND";
        AppMessage message = message(1);
        fixture.apply(List.of(message));
        assertThat(BindingReceiptV1.decode(fixture.workflow.get(message.getMessageId()).orElseThrow()).code())
                .isEqualTo("ADMISSION");
        assertThat(fixture.target.workRequests).isZero();
        assertThat(fixture.targetState.get(TARGET_WORK_KEY)).isEmpty();
        // The source's own reservation happened before the derived step and stays charged.
        assertThat(fixture.sourceState.get(WORK_KEY)).isPresent();
    }

    @Test
    void compactedFailedStepSizeIsPinned() {
        String longName = "a".repeat(127);
        var failed = new BindingReceiptV1.Step(17, 3, longName, "target", new byte[32],
                Collections.nCopies(257, longName),
                Collections.nCopies(256, new BindingReceiptV1.Condition(longName, 7)),
                "REJECTED", "EXPRESSION_DIVISION_BY_ZERO", true);
        var trace = new java.util.ArrayList<>(List.of(failed));
        EventBindingWorkflow.compactFailureTrace(trace, 17);
        byte[] encoded = new BindingReceiptV1(new byte[32], 1, false, 17, "RECEIPT_CAPACITY_EXCEEDED", trace).encode();
        assertThat(encoded).hasSize(COMPACTED_FAILURE_BYTES);
        assertThat(encoded.length).isLessThan(BindingReceiptV1.MAX_BYTES);
    }

    // Pinned in Phase 0 against the pre-ADR-031.3 implementation. Differences since then, explained in the ledger:
    //  - Phase 2: every receipt step gains the eleventh element, the empty rule trace [0, null] (3 bytes: 82 00 f6),
    //    so step arrays grow from 10 (8a) to 11 (8b) elements; the compacted failure step grows by 3 bytes.
    private static final String SOURCE_ADMISSION_RECEIPT =
            "870158200000000000000000000000000000000000000000000000000000000000000001016852454a454354"
            + "4544006941444d495353494f4e818b0000f666736f7572636558200000000000000000000000000000000000"
            + "00000000000000000000000000000180808200f66852454a45435445446941444d495353494f4ef4";
    private static final String DERIVED_ADMISSION_RECEIPT =
            "870158200000000000000000000000000000000000000000000000000000000000000001016852454a454354"
            + "4544016941444d495353494f4e828b0000f666736f7572636558200000000000000000000000000000000000"
            + "00000000000000000000000000000181781d636f6d706f736974652e636f6d6d616e642d6163636570746564"
            + "2e7631818267666f7277617264208200f667504c414e4e454460f48b010167666f7277617264667461726765"
            + "7458206ca55de6a1625ef6f09f62b55a8207f170a1251b472e710ea1d66987ad1c3e8e80808200f66852454a"
            + "45435445446941444d495353494f4ef5";
    // Source preparation charges only the cascade counter, so a source-step rejection leaves block work at zero.
    private static final long SOURCE_ADMISSION_WORK = 0;
    private static final long DERIVED_ADMISSION_WORK = 170;
    private static final long ACCEPTED_WORK = 170;
    private static final long TARGET_REJECTED_WORK = 170;
    private static final int COMPACTED_FAILURE_BYTES = 33830;

    private static AppMessage message(int identity) {
        return message(identity, BODY);
    }

    private static AppMessage message(int identity, byte[] body) {
        byte[] id = new byte[32];
        id[31] = (byte) identity;
        return AppMessage.builder().messageId(id).chainId("chain").topic("source.v1").sender(new byte[32])
                .senderSeq(identity).expiresAt(Long.MAX_VALUE).body(body).authScheme(0).authProof(new byte[]{1})
                .build();
    }

    private static final class Fixture implements CompositeWorkflowContext {
        final MemoryState sourceState = new MemoryState();
        final MemoryState targetState = new MemoryState();
        final MemoryState workflow = new MemoryState();
        final RecordingKernel source;
        final RecordingKernel target;
        final EventBindingWorkflow engine;

        Fixture() {
            this(BindingIrV1.Limits.DEFAULT, false);
        }

        Fixture(BindingIrV1.Limits limits) {
            this(limits, false);
        }

        Fixture(BindingIrV1.Limits limits, boolean targetCharges) {
            source = new RecordingKernel("source", WORK_KEY);
            target = targetCharges ? new RecordingKernel("target", TARGET_WORK_KEY) : new RecordingKernel(null, null);
            var sourceGeneration = new ComponentGeneration("source", "1", 1);
            var targetGeneration = new ComponentGeneration("target", "1", 1);
            var ir = new BindingIrV1(List.of(
                    new BindingIrV1.Component("source", "test", "source.v1", Map.of(), 0),
                    new BindingIrV1.Component("target", "test", "target.v1", Map.of(), 0)),
                    List.of(new BindingIrV1.Binding("forward", "source", BindingProgram.BASELINE, List.of(),
                            new BindingIrV1.CommandTarget("target", "put", BindingIrV1.Mapping.raw("body")))),
                    limits);
            var program = new BindingProgram(ir, Map.of("source", source, "target", target));
            var descriptor = new WorkflowDescriptor(EventBindingWorkflow.ID, "1",
                    List.of("source.v1", "target.v1"), 1, 0, List.of(sourceGeneration, targetGeneration), 0);
            engine = new EventBindingWorkflow(program, descriptor,
                    Map.of("source", sourceGeneration, "target", targetGeneration),
                    AppChainTestProfiles.enabledEffects(10));
        }

        void apply(List<AppMessage> messages) {
            AppBlock block = new AppBlock(1, "chain", 1, new byte[32], 0, new byte[0], 1,
                    new byte[32], new byte[32], messages, new byte[32], FinalityCert.empty());
            engine.apply(AppBlockExecutionContext.fromValidatedBlock(block), this);
        }

        long evaluationWork() { return (Long) engine.operationalStatus().get("evaluationWork"); }

        @Override public AppStateWriter state(ComponentGeneration participant) {
            return participant.componentId().equals("source") ? sourceState : targetState;
        }
        @Override public AppEffectEmitter effects(ComponentGeneration owner) {
            return AppEffectEmitter.rejecting("characterization fixture emits no effects");
        }
        @Override public AppStateWriter workflowState() { return workflow; }
        @Override public int remainingEffectCapacity() { return 0; }
        @Override public ClaimResult claim(String id, byte[] hash) { return ClaimResult.CLAIMED; }
    }

    /** Raw-bytes kernel that records which callbacks ran; a charging kernel owns and requests a crypto budget. */
    private static final class RecordingKernel implements TransitionKernel<byte[], Boolean> {
        private final String owner;
        private final byte[] budgetKey;
        private String statelessRejection;
        private String contextualRejection;
        private boolean malformed;
        private int rejectedValue = -1;
        private int statelessCalls;
        private int contextualCalls;
        private int workRequests;
        private int factsCalls;
        private int decideCalls;

        /** A kernel that owns and charges {@code budgetKey} as component {@code owner}, or a non-charging one. */
        RecordingKernel(String owner, byte[] budgetKey) {
            this.owner = owner;
            this.budgetKey = budgetKey;
        }

        @Override public MessageCodec<byte[]> codec() {
            var delegate = new OrderedLogKernel().codec();
            return new MessageCodec<>() {
                @Override public byte[] encode(byte[] value) { return delegate.encode(value); }
                @Override public byte[] decode(byte[] body) {
                    if (malformed) throw new IllegalArgumentException("malformed fixture body");
                    return delegate.decode(body);
                }
                @Override public Class<byte[]> type() { return byte[].class; }
            };
        }
        @Override public AdmissionResult admit(byte[] command) {
            statelessCalls++;
            return statelessRejection == null ? AdmissionResult.accept() : AdmissionResult.reject(statelessRejection);
        }
        @Override public AdmissionResult admit(byte[] command, TransitionContext context) {
            contextualCalls++;
            return contextualRejection == null ? TransitionKernel.super.admit(command, context)
                    : AdmissionResult.reject(contextualRejection);
        }
        @Override public List<TransitionWorkBudget> workBudgets() {
            return owner != null ? List.of(new TransitionWorkBudget("crypto", budgetKey, 4)) : List.of();
        }
        @Override public List<TransitionWorkReference> workReferences() {
            return owner != null ? List.of(new TransitionWorkReference(owner, "crypto")) : List.of();
        }
        @Override public Optional<TransitionWorkRequest> workRequest(byte[] command, TransitionContext context) {
            workRequests++;
            return owner != null ? Optional.of(new TransitionWorkRequest(workReferences().getFirst(), 1))
                    : Optional.empty();
        }
        @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state) {
            factsCalls++;
            return true;
        }
        @Override public TransitionDecision decide(byte[] command, TransitionContext context, Boolean facts) {
            decideCalls++;
            return command.length > 0 && Byte.toUnsignedInt(command[0]) == rejectedValue
                    ? TransitionDecision.reject("TARGET_REJECTED", "fixture")
                    : TransitionDecision.approve(TransitionPlan.mutations(List.of(
                            StateMutation.put(context.messageId(), command))));
        }
        @Override public List<CommandDescriptor> commands() {
            return List.of(new CommandDescriptor("put", CommandDescriptor.Layout.RAW_BYTES, 0, List.of()));
        }
        @Override public List<EventDescriptor> events() { return List.of(); }
        @Override public ConfigurationDescriptor configuration() { return ConfigurationDescriptor.empty(); }
    }

    private static final class MemoryState implements AppStateWriter {
        final Map<String, byte[]> values = new HashMap<>();
        @Override public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(values.get(HexFormat.of().formatHex(key))).map(byte[]::clone);
        }
        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(HexFormat.of().formatHex(key)); }
    }
}
