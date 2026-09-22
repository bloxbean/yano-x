package org.yanoproject.x.composite.bindings;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
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
import org.yanoproject.appchain.testkit.AppChainTestProfiles;
import org.yanoproject.x.composite.ComponentGeneration;
import org.yanoproject.x.composite.CompositeWorkflowContext;
import org.yanoproject.x.composite.WorkflowDescriptor;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Runtime lookup truth table and real cascade read-your-writes evidence, independent of authoring validation. */
class BindingLookupTest {
    private static final byte[] KEY = {1};
    private static final byte[] VALUE = {42};

    @Test
    void everyLookupExpectationDistinguishesAbsentEqualDifferentAndEmptyAuthenticatedBytes() {
        for (var expectation : BindingIrV1.Expectation.values()) {
            var binding = lookupBinding(expectation);
            var program = new BindingProgram(document(List.of(binding)), kernels());
            Memory state = new Memory();
            assertThat(matches(program, binding, state)).as("absent %s", expectation)
                    .isEqualTo(expectation == BindingIrV1.Expectation.ABSENT);
            state.put(KEY, VALUE);
            assertThat(matches(program, binding, state)).as("equal %s", expectation)
                    .isEqualTo(expectation != BindingIrV1.Expectation.ABSENT);
            state.put(KEY, new byte[]{43});
            assertThat(matches(program, binding, state)).as("different %s", expectation)
                    .isEqualTo(expectation == BindingIrV1.Expectation.EXISTS);
            state.put(KEY, new byte[0]);
            assertThat(matches(program, binding, state)).as("empty but present %s", expectation)
                    .isEqualTo(expectation == BindingIrV1.Expectation.EXISTS);
        }
    }

    @Test
    void lookupConditionSeesEarlierDerivedPlanBeforeAnyComponentCommit() {
        var first = new BindingIrV1.Binding("copy", "source", BindingProgram.BASELINE, List.of(),
                new BindingIrV1.CommandTarget("copy", "put", BindingIrV1.Mapping.raw("body")));
        var second = lookupBinding(BindingIrV1.Expectation.EQUAL_EVENT);
        var program = new BindingProgram(document(List.of(first, second)), kernels());
        var generations = Map.of("source", new ComponentGeneration("source", "1", 1),
                "copy", new ComponentGeneration("copy", "1", 1), "audit", new ComponentGeneration("audit", "1", 1));
        var workflow = new EventBindingWorkflow(program, new WorkflowDescriptor(EventBindingWorkflow.ID, "1",
                List.of("source.v1", "copy.v1", "audit.v1"), 1, 0,
                List.of(generations.get("source"), generations.get("copy"), generations.get("audit")), 0),
                generations, AppChainTestProfiles.enabledEffects(1));
        Map<String, Memory> states = Map.of("source", new Memory(), "copy", new Memory(), "audit", new Memory());
        Memory receipts = new Memory();
        var context = new CompositeWorkflowContext() {
            @Override public AppStateWriter state(ComponentGeneration participant) {
                return states.get(participant.componentId());
            }
            @Override public AppEffectEmitter effects(ComponentGeneration participant) {
                return AppEffectEmitter.rejecting("no effects in lookup fixture");
            }
            @Override public AppStateWriter workflowState() { return receipts; }
            @Override public int remainingEffectCapacity() { return 0; }
            @Override public ClaimResult claim(String id, byte[] hash) { return ClaimResult.CLAIMED; }
        };
        var message = AppMessage.builder().chainId("chain").topic("source.v1").messageId(new byte[32])
                .sender(new byte[32]).senderSeq(1).expiresAt(Long.MAX_VALUE).body(VALUE)
                .authScheme(0).authProof(new byte[]{1}).build();
        var block = new AppBlock(1, "chain", 1, new byte[32], 0, new byte[0], 1, new byte[32], new byte[32],
                List.of(message), new byte[32], FinalityCert.empty());
        assertThat(states.get("copy").get(KEY)).isEmpty();
        workflow.apply(AppBlockExecutionContext.fromValidatedBlock(block), context);
        var receipt = BindingReceiptV1.decode(receipts.get(message.getMessageId()).orElseThrow());
        assertThat(receipt.accepted()).isTrue();
        assertThat(receipt.steps()).hasSize(3);
        assertThat(receipt.steps().get(1).conditions())
                .containsExactly(new BindingReceiptV1.Condition("audit-copy", -1));
        assertThat(states.get("audit").get(KEY)).hasValue(VALUE);
        byte[] retainedReceipt = receipts.get(message.getMessageId()).orElseThrow();
        workflow.apply(AppBlockExecutionContext.fromValidatedBlock(block), context);
        assertThat(receipts.get(message.getMessageId())).hasValue(retainedReceipt);
        assertThat(states.values()).allSatisfy(state -> assertThat(state.writes).isEqualTo(1));
        assertThat(workflow.operationalStatus()).containsEntry("replayed", 1).containsEntry("derived", 0);
    }

    private static boolean matches(BindingProgram program, BindingIrV1.Binding binding, Memory state) {
        return program.condition(binding, Map.of("body", VALUE), ignored -> state,
                new BindingExpressionEvaluator.Budget(1000), new BindingExpressionEvaluator.Budget(1000)) == -1;
    }

    private static BindingIrV1.Binding lookupBinding(BindingIrV1.Expectation expectation) {
        BindingSourceV1 operand = switch (expectation) {
            case EXISTS, ABSENT -> null;
            case EQUAL_LITERAL -> new BindingSourceV1.Literal(VALUE);
            case EQUAL_EVENT -> new BindingSourceV1.Field("body");
        };
        return new BindingIrV1.Binding("audit-copy", "copy", BindingProgram.BASELINE,
                List.of(new BindingIrV1.LookupClause("copy", new BindingSourceV1.Literal(KEY), expectation, operand)),
                new BindingIrV1.CommandTarget("audit", "put", BindingIrV1.Mapping.raw("body")));
    }

    private static BindingIrV1 document(List<BindingIrV1.Binding> bindings) {
        return new BindingIrV1(List.of("source", "copy", "audit").stream()
                .map(id -> new BindingIrV1.Component(id, "fixture", id + ".v1", Map.of(), 0)).toList(),
                bindings, BindingIrV1.Limits.DEFAULT);
    }

    private static Map<String, TransitionKernel<?, ?>> kernels() {
        return Map.of("source", new Put(), "copy", new Put(), "audit", new Put());
    }

    private static final class Put implements TransitionKernel<byte[], Boolean> {
        @Override public MessageCodec<byte[]> codec() { return new OrderedLogKernel().codec(); }
        @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state) { return true; }
        @Override public TransitionDecision decide(byte[] command, TransitionContext context, Boolean facts) {
            return TransitionDecision.approve(TransitionPlan.mutations(List.of(StateMutation.put(KEY, command))));
        }
        @Override public List<CommandDescriptor> commands() {
            return List.of(new CommandDescriptor("put", CommandDescriptor.Layout.RAW_BYTES, 0, List.of()));
        }
        @Override public List<EventDescriptor> events() { return List.of(); }
        @Override public ConfigurationDescriptor configuration() { return ConfigurationDescriptor.empty(); }
    }

    private static final class Memory implements AppStateWriter {
        private final Map<String, byte[]> values = new LinkedHashMap<>();
        private int writes;
        @Override public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(values.get(HexFormat.of().formatHex(key))).map(byte[]::clone);
        }
        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public void put(byte[] key, byte[] value) {
            writes++;
            values.put(HexFormat.of().formatHex(key), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(HexFormat.of().formatHex(key)); }
    }
}
