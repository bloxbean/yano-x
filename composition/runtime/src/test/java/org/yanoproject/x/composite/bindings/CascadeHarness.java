package org.yanoproject.x.composite.bindings;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine.AdmissionResult;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.FinalityCert;
import org.yanoproject.api.appchain.codec.MessageCodec;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.effects.EffectId;
import org.yanoproject.api.appchain.effects.EffectIntent;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.EventDescriptor;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.api.appchain.transition.StateMutation;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionEvent;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionPlan;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.api.appchain.transition.TransitionWorkBudget;
import org.yanoproject.api.appchain.transition.TransitionWorkReference;
import org.yanoproject.api.appchain.transition.TransitionWorkRequest;
import org.yanoproject.appchain.testkit.AppChainTestProfiles;
import org.yanoproject.x.composite.ComponentGeneration;
import org.yanoproject.x.composite.CompositeWorkflowContext;
import org.yanoproject.x.composite.WorkflowDescriptor;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * In-memory cascade harness for engine tests: one {@link RecordKernel} per component, a workflow state, and helpers
 * to submit source messages and read receipts. Nothing here is consensus code.
 */
final class CascadeHarness implements CompositeWorkflowContext {
    final Map<String, MemoryState> states = new LinkedHashMap<>();
    final Map<String, RecordKernel> kernels = new LinkedHashMap<>();
    final MemoryState workflow = new MemoryState();
    final List<EffectIntent> emitted = new ArrayList<>();
    final BindingProgram program;
    final EventBindingWorkflow engine;
    int effectCapacity = 16;

    CascadeHarness(BindingIrV1 ir) {
        this(ir, component -> new RecordKernel());
    }

    CascadeHarness(BindingIrV1 ir, Function<String, RecordKernel> kernelFactory) {
        Map<String, ComponentGeneration> generations = new LinkedHashMap<>();
        for (var component : ir.components()) {
            RecordKernel kernel = kernelFactory.apply(component.id());
            kernel.selfId = component.id();
            kernels.put(component.id(), kernel);
            states.put(component.id(), new MemoryState());
            generations.put(component.id(), new ComponentGeneration(component.id(), "1", 1));
        }
        program = new BindingProgram(ir, Map.copyOf(kernels));
        var descriptor = new WorkflowDescriptor(EventBindingWorkflow.ID, "1",
                ir.components().stream().map(BindingIrV1.Component::ingressTopic).toList(), 1, 0,
                List.copyOf(generations.values()), 16);
        engine = new EventBindingWorkflow(program, descriptor, generations, AppChainTestProfiles.enabledEffects(16));
    }

    static AppMessage message(int identity, String topic, byte[] body) {
        byte[] id = new byte[32];
        id[31] = (byte) identity;
        id[30] = (byte) (identity >>> 8);
        return AppMessage.builder().messageId(id).chainId("chain").topic(topic).sender(sender())
                .senderSeq(identity).expiresAt(Long.MAX_VALUE).body(body).authScheme(0).authProof(new byte[]{1})
                .build();
    }

    static byte[] sender() {
        byte[] sender = new byte[32];
        Arrays.fill(sender, (byte) 7);
        return sender;
    }

    void apply(long height, List<AppMessage> messages) {
        AppBlock block = new AppBlock(1, "chain", height, new byte[32], 0, new byte[0], height,
                new byte[32], new byte[32], messages, new byte[32], FinalityCert.empty());
        engine.apply(AppBlockExecutionContext.fromValidatedBlock(block), this);
    }

    BindingReceiptV1 receipt(AppMessage message) {
        return BindingReceiptV1.decode(workflow.get(message.getMessageId()).orElseThrow());
    }

    long evaluationWork() { return (Long) engine.operationalStatus().get("evaluationWork"); }

    @Override public AppStateWriter state(ComponentGeneration participant) {
        return states.get(participant.componentId());
    }
    @Override public AppEffectEmitter effects(ComponentGeneration owner) {
        return new AppEffectEmitter() {
            @Override public EffectId emit(EffectIntent intent) {
                emitted.add(intent);
                return new EffectId("chain", 1, emitted.size() - 1);
            }
            @Override public long pendingCount() { return emitted.size(); }
        };
    }
    @Override public AppStateWriter workflowState() { return workflow; }
    @Override public int remainingEffectCapacity() { return effectCapacity - emitted.size(); }
    @Override public ClaimResult claim(String id, byte[] hash) { return ClaimResult.CLAIMED; }

    /** Encodes a {@code record} command: {@code [1, value, note / null, proof / null]}. */
    static byte[] record(byte[] value, String note) {
        return BindingCbor.encode(Arrays.asList(1L, value, note, null));
    }

    /** Encodes a {@code transfer} command: {@code [2, amount, memo]}. */
    static byte[] transfer(long amount, String memo) {
        return BindingCbor.encode(Arrays.asList(2L, amount, memo));
    }

    /**
     * Test kernel with two opcode commands, {@code record} ({@code [1, value, note?, proof?]}, proof is evidence) and
     * {@code transfer} ({@code [2, amount, memo]}). It writes the command under the message id and emits
     * {@code recorded.v1} with the value, note, and amount. Facts and rule facts are configurable.
     */
    static final class RecordKernel implements TransitionKernel<List<Object>, RecordKernel.Facts> {
        record Facts(long sequence, boolean verified) { }
        List<RuleFact> declared = List.of();
        Function<List<Object>, Map<String, Object>> factValues = command -> Map.of();
        String rejectCode;
        String admissionRejection;
        boolean lenientCodec;
        boolean requestsWork;
        final List<TransitionContext> decided = new ArrayList<>();
        final List<Facts> factsProduced = new ArrayList<>();
        final List<Facts> factsQueried = new ArrayList<>();
        int factsCalls;
        int ruleFactCalls;

        @Override public MessageCodec<List<Object>> codec() {
            return new MessageCodec<>() {
                @Override public byte[] encode(List<Object> value) { return BindingCbor.encode(value); }
                @Override public List<Object> decode(byte[] body) {
                    byte[] input = body;
                    if (lenientCodec && body.length > 1 && body[body.length - 1] == 0) {
                        // Tolerates one trailing zero byte: a body the codec accepts but no canonical view has.
                        input = Arrays.copyOf(body, body.length - 1);
                    }
                    Object decoded = BindingCbor.decode(input, Math.max(1, input.length));
                    if (!(decoded instanceof List<?> list) || list.isEmpty() || !(list.getFirst() instanceof Long op)
                            || op < 1 || op > 2) {
                        throw new IllegalArgumentException("record command");
                    }
                    return new ArrayList<>(list);
                }
                @SuppressWarnings("unchecked")
                @Override public Class<List<Object>> type() { return (Class<List<Object>>) (Class<?>) List.class; }
            };
        }
        @Override public AdmissionResult admit(List<Object> command) {
            return admissionRejection == null ? AdmissionResult.accept() : AdmissionResult.reject(admissionRejection);
        }
        @Override public List<TransitionWorkBudget> workBudgets() {
            return List.of(new TransitionWorkBudget("work", new byte[]{9}, 1_000));
        }
        @Override public List<TransitionWorkReference> workReferences() {
            return requestsWork ? List.of(new TransitionWorkReference(selfId, "work")) : List.of();
        }
        String selfId = "";
        @Override public Optional<TransitionWorkRequest> workRequest(List<Object> command, TransitionContext context) {
            return requestsWork ? Optional.of(new TransitionWorkRequest(workReferences().getFirst(), 1))
                    : Optional.empty();
        }
        @Override public Facts facts(List<Object> command, TransitionContext context, AppStateReader state) {
            factsCalls++;
            Facts facts = new Facts(factsCalls, true);
            factsProduced.add(facts);
            return facts;
        }
        @Override public TransitionDecision decide(List<Object> command, TransitionContext context, Facts facts) {
            decided.add(context);
            if (rejectCode != null) return TransitionDecision.reject(rejectCode, "fixture");
            Map<String, Object> event = new LinkedHashMap<>();
            if (command.get(1) instanceof byte[] value) event.put("value", value);
            if (command.size() > 2 && command.get(2) instanceof String note) event.put("note", note);
            if (command.get(1) instanceof Long amount) event.put("amount", amount);
            return TransitionDecision.approve(new TransitionPlan(List.of(StateMutation.put(context.messageId(),
                    BindingCbor.encode(command))), List.of(), List.of(), List.of(),
                    List.of(new TransitionEvent("recorded.v1", TransitionScalars.encode(event)))));
        }
        @Override public List<RuleFact> ruleFacts() { return declared; }
        @Override public Map<String, Object> ruleFactValues(List<Object> command, TransitionContext context,
                                                            Facts facts) {
            ruleFactCalls++;
            factsQueried.add(facts);
            return factValues.apply(command);
        }
        @Override public List<CommandDescriptor> commands() {
            return List.of(new CommandDescriptor("record", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 1, List.of(
                            new CommandDescriptor.Field("value", TransitionScalars.Type.BYTES, true,
                                    CommandDescriptor.Role.DATA),
                            new CommandDescriptor.Field("note", TransitionScalars.Type.TEXT, false,
                                    CommandDescriptor.Role.DATA),
                            new CommandDescriptor.Field("proof", TransitionScalars.Type.BYTES, false,
                                    CommandDescriptor.Role.EVIDENCE))),
                    new CommandDescriptor("transfer", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 2, List.of(
                            new CommandDescriptor.Field("amount", TransitionScalars.Type.INTEGER, true,
                                    CommandDescriptor.Role.DATA),
                            new CommandDescriptor.Field("memo", TransitionScalars.Type.TEXT, true,
                                    CommandDescriptor.Role.DATA))));
        }
        @Override public List<EventDescriptor> events() {
            return List.of(new EventDescriptor("recorded.v1", List.of(
                    new CommandDescriptor.Field("value", TransitionScalars.Type.BYTES, false,
                            CommandDescriptor.Role.DATA),
                    new CommandDescriptor.Field("note", TransitionScalars.Type.TEXT, false,
                            CommandDescriptor.Role.DATA),
                    new CommandDescriptor.Field("amount", TransitionScalars.Type.INTEGER, false,
                            CommandDescriptor.Role.DATA))));
        }
        @Override public ConfigurationDescriptor configuration() {
            return new ConfigurationDescriptor(List.of(
                    new ConfigurationDescriptor.Setting("tier", TransitionScalars.Type.TEXT, "standard")));
        }
    }

    static final class MemoryState implements AppStateWriter {
        final Map<String, byte[]> values = new HashMap<>();
        @Override public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(values.get(HexFormat.of().formatHex(key))).map(byte[]::clone);
        }
        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(HexFormat.of().formatHex(key)); }
        /** Business keys only: the harness kernel's work counter key is {@code 09}. */
        List<String> businessKeys() { return values.keySet().stream().filter(key -> !key.equals("09")).toList(); }
    }
}
