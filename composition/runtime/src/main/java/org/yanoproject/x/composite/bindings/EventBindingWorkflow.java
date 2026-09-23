package org.yanoproject.x.composite.bindings;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppChainConsensusProfile;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.AppStateMachine.AdmissionResult;
import org.yanoproject.api.appchain.effects.EffectIntent;
import org.yanoproject.api.appchain.effects.FinalityGate;
import org.yanoproject.api.appchain.effects.ResultPolicy;
import org.yanoproject.api.appchain.transition.StateMutation;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionEvent;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionPlan;
import org.yanoproject.api.appchain.transition.TransitionPlans;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.api.appchain.transition.TransitionWorkAccounting;
import org.yanoproject.x.composite.ComponentGeneration;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.CompositeWorkflow;
import org.yanoproject.x.composite.CompositeWorkflowContext;
import org.yanoproject.x.composite.WorkflowDescriptor;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;

/**
 * Executes declarative cascades in breadth-first order with one business-failure boundary per source message.
 *
 * <p>Each command reads a component-scoped overlay containing earlier approved writes in the same cascade.
 * Plans, derived effects, and the bounded receipt are checked before component state is committed. A rejected
 * cascade retains its receipt and work accounting, but does not commit its component plans. Work already spent
 * is deliberately not refunded: repeatedly failing commands must not bypass the per-block work limits.
 *
 * <p>The enclosing composite/host transaction supplies block-level atomicity. Infrastructure failures during
 * commit propagate to that transaction; this class must not turn a partially committed infrastructure failure
 * into an ordinary rejected-message receipt. Instances retain only non-consensus diagnostic snapshots
 * between blocks; all execution decisions use the supplied block context and authenticated state.
 */
public final class EventBindingWorkflow implements CompositeWorkflow {
    public static final String ID = "event-bindings";
    private static final byte[] WORK_KEY = "work".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXPRESSION_KEY = "expression-work".getBytes(StandardCharsets.US_ASCII);
    private final BindingProgram program;
    private final WorkflowDescriptor descriptor;
    private final Map<String, ComponentGeneration> generations;
    private final Map<String, String> ingress;
    private final AppChainConsensusProfile consensus;
    private volatile Map<String, Object> diagnostics = Map.of();

    public EventBindingWorkflow(BindingProgram program, WorkflowDescriptor descriptor,
                                Map<String, ComponentGeneration> generations, AppChainConsensusProfile consensus) {
        this.program = program;
        this.descriptor = descriptor;
        this.generations = Map.copyOf(generations);
        this.consensus = consensus;
        Map<String, String> routes = new LinkedHashMap<>();
        program.ir().components().forEach(component -> routes.put(component.ingressTopic(), component.id()));
        this.ingress = Map.copyOf(routes);
    }
    @Override public WorkflowDescriptor descriptor() { return descriptor; }
    /** Returns the last locally completed apply's counters, never read by execution or persisted as authority. */
    @Override public Map<String, Object> operationalStatus() { return diagnostics; }

    /**
     * Rejects malformed commands and statically impossible subscribed-baseline size/work before pooling.
     * Uses full committed allowances, never a node-local remaining-block counter; stateful authorization,
     * native event output and dynamic fan-out remain authoritative apply-time checks.
     */
    @Override public AdmissionResult validate(AppMessage source) {
        String component = ingress.get(source.getTopic());
        if (component == null) return AdmissionResult.reject("UNKNOWN_BINDING_SOURCE");
        try {
            var limits = program.ir().limits();
            var baseline = baselineEstimate(component, source.getTopic(), source.getSender(), source.getMessageId(),
                    source.getBody());
            long mandatory = mandatoryWork(source.getBody(), baseline);
            if (mandatory > limits.maxExpressionWorkPerCascade()
                    || baseline != null && baseline.decodingWork() > limits.maxExpressionWorkPerBlock()) {
                return AdmissionResult.reject("COMMAND_WORK_EXCEEDED");
            }
        } catch (BindingFailure predictable) {
            return AdmissionResult.reject(predictable.code());
        }
        return validateKernel(program.kernel(component), source.getBody());
    }

    /** Decoding failures are bad input; failures in the stateless admission implementation are not. */
    private static <C> AdmissionResult validateKernel(TransitionKernel<C, ?> kernel, byte[] body) {
        var codec = kernel.codec();
        C command;
        try {
            command = codec.decode(body);
        } catch (RuntimeException malformed) {
            return AdmissionResult.reject("MALFORMED_SOURCE_COMMAND");
        }
        return Objects.requireNonNull(kernel.admit(command), "kernel returned null stateless admission");
    }

    /**
     * Processes routed messages in their original block order, sharing one work budget across all cascades.
     * Transient authenticated work counters are removed before the successfully applied block is finalized.
     *
     * @param execution routed messages together with their original block positions
     * @param context participant-scoped writers, effect emitters, claims, and workflow receipt storage
     */
    @Override public void apply(AppBlockExecutionContext execution, CompositeWorkflowContext context) {
        Work work = new Work(program.ir().limits().maxExpressionWorkPerBlock());
        int visible = 0;
        for (AppMessage source : execution.messages()) {
            int index = execution.originalMessageIndex(visible++);
            cascade(source, TransitionContext.of(execution.block(), index, source), context, work);
        }
        context.workflowState().delete(WORK_KEY);
        context.workflowState().delete(EXPRESSION_KEY);
        diagnostics = Map.of("height", execution.block().height(), "accepted", work.accepted,
                "rejected", work.rejected, "replayed", work.replayed, "derived", work.derived,
                "evaluationWork", work.expressions.used());
    }

    /** Only subscribed baseline events need a scalar envelope; native-only commands retain host body capacity. */
    private BindingPayload.Estimate baselineEstimate(String component, String topic, byte[] sender,
                                                     byte[] messageId, byte[] body) {
        return program.bindings(component, BindingProgram.BASELINE).isEmpty() ? null
                : BindingPayload.estimate(topic, sender, messageId, body, program.ir().limits().maxEventPayloadBytes());
    }

    private static long mandatoryWork(byte[] body, BindingPayload.Estimate baseline) {
        return 1L + body.length + (baseline == null ? 0 : baseline.preparationWork() + baseline.decodingWork());
    }

    /**
     * Host-bounded source preparation spends only cascade work. Binding-selected processing and all derived
     * dispatches spend shared block work as well, preventing derived amplification from escaping that budget.
     */
    private static void chargeStep(long units, boolean shared, BindingExpressionEvaluator.Budget cascade,
                                   BindingExpressionEvaluator.Budget block) {
        if (shared) BindingWork.charge(units, cascade, block);
        else cascade.charge(units);
    }

    private void cascade(AppMessage source, TransitionContext origin, CompositeWorkflowContext context, Work work) {
        String component = ingress.get(source.getTopic());
        if (component == null) throw new IllegalStateException("source route is not declared");
        var limits = program.ir().limits();
        var expressionBudget = new BindingExpressionEvaluator.Budget(limits.maxExpressionWorkPerCascade());
        Map<String, Overlay> overlays = new LinkedHashMap<>();
        generations.forEach((id, generation) -> overlays.put(id, new Overlay(context.state(generation))));
        List<Planned> plans = new ArrayList<>();
        List<BindingReceiptV1.Step> trace = new ArrayList<>();
        ArrayDeque<Step> queue = new ArrayDeque<>();
        queue.add(new Step(component, source.getBody(), origin, null, 0, 0, false));
        Set<String> consumed = new HashSet<>();
        Set<String> written = new HashSet<>();
        int produced = 0;
        Step current = queue.getFirst();
        String failure = null;
        List<String> currentEvents = List.of();
        List<BindingReceiptV1.Condition> conditions = new ArrayList<>();
        try {
            if (context.workflowState().get(source.getMessageId()).isPresent()) {
                work.replayed++;
                return;
            }
            if (work.derived >= limits.maxDerivedPerBlock()
                    && program.ir().bindings().stream()
                            .anyMatch(binding -> binding.sourceComponent().equals(component))) {
                throw new BindingFailure("CAPACITY_EXCEEDED");
            }
            while (!queue.isEmpty()) {
                current = queue.removeFirst();
                currentEvents = List.of();
                conditions = new ArrayList<>();
                if (current.depth > limits.maxCascadeDepth()) throw new BindingFailure("LIMIT_DEPTH");
                var baselineEstimate = baselineEstimate(current.component, current.context.topic(), origin.sender(),
                        current.context.messageId(), current.body);
                try {
                    long mandatory = mandatoryWork(current.body, baselineEstimate);
                    if (mandatory > limits.maxExpressionWorkPerCascade()) {
                        chargeStep(mandatory, current.depth > 0, expressionBudget, work.expressions);
                        throw new BindingFailure("COMMAND_WORK_EXCEEDED");
                    }
                    if (baselineEstimate != null
                            && baselineEstimate.decodingWork() > limits.maxExpressionWorkPerBlock()) {
                        BindingWork.charge(baselineEstimate.decodingWork(), expressionBudget, work.expressions);
                        throw new BindingFailure("COMMAND_WORK_EXCEEDED");
                    }
                } catch (BindingFailure exhausted) {
                    throw new BindingFailure("COMMAND_WORK_EXCEEDED");
                }
                chargeStep(1L + current.body.length
                                + (baselineEstimate == null ? 0 : baselineEstimate.preparationWork()),
                        current.depth > 0, expressionBudget, work.expressions);
                Map<String, AppStateReader> participants = new LinkedHashMap<>();
                for (String id : program.readParticipants(current.component)) participants.put(id, overlays.get(id));
                TransitionDecision decision = decide(program.kernel(current.component), current,
                        overlays.get(current.component), Map.copyOf(participants), context);
                if (decision instanceof TransitionDecision.Rejected rejected)
                        throw new BindingFailure(rejected.rejection().code());
                TransitionPlan plan = ((TransitionDecision.Approved) decision).plan();
                preflightMutations(current.component, plan, consumed, written);
                plans.add(new Planned(current, plan));
                overlays.get(current.component).apply(plan);
                List<TransitionEvent> events = new ArrayList<>(plan.events());
                if (events.stream().anyMatch(event -> BindingProgram.BASELINE.equals(event.eventId()))) {
                    throw new BindingFailure("RESERVED_EVENT_ID");
                }
                if (baselineEstimate != null) {
                    byte[] body = current.body;
                    byte[] baseline = TransitionScalars.encode(Map.of("topic", current.context.topic(),
                            "sender", origin.sender(), "messageId", current.context.messageId(), "body", body,
                            "bodyHash", Blake2bUtil.blake2bHash256(body), "bodyLength", (long) body.length));
                    events.add(new TransitionEvent(BindingProgram.BASELINE, baseline));
                }
                // The semantic event exists even when no subscriber requires its expensive scalar payload.
                currentEvents = new ArrayList<>(plan.events().stream().map(TransitionEvent::eventId).toList());
                currentEvents.add(BindingProgram.BASELINE);
                for (TransitionEvent event : events) {
                    if (event.payload().length > limits.maxEventPayloadBytes())
                            throw new BindingFailure("EVENT_PAYLOAD_TOO_LARGE");
                    var selected = program.bindings(current.component, event.eventId());
                    chargeStep(1L + event.payload().length, current.depth > 0 || !selected.isEmpty(),
                            expressionBudget, work.expressions);
                    Map<String, Object> values = TransitionScalars.decode(event.payload());
                    validateEvent(current.component, event.eventId(), values);
                    for (var binding : selected) {
                        if (conditions.size() == BindingReceiptV1.MAX_CONDITION_RECORDS) {
                            throw new BindingFailure("RECEIPT_CAPACITY_EXCEEDED");
                        }
                        int failed = program.condition(binding, values, overlays::get, expressionBudget,
                                work.expressions);
                        conditions.add(new BindingReceiptV1.Condition(binding.id(), failed));
                        if (failed >= 0) continue;
                        if (produced == limits.maxDerivedPerSourceMessage()) throw new BindingFailure("LIMIT_FANOUT");
                        if (work.derived == limits.maxDerivedPerBlock()) throw new BindingFailure("CAPACITY_EXCEEDED");
                        produced++;
                        work.derived++;
                        byte[] id = derivedId(source.getMessageId(), binding.id(), produced);
                        byte[] payload;
                        try {
                            payload = program.payload(binding, values, event.payload(), expressionBudget,
                                    work.expressions);
                        } catch (BindingFailure mappingFailure) {
                            // Preserve the parent's visited outcomes, then name the attempted child whose
                            // payload could not be built. No unvisited event/condition is manufactured.
                            trace.add(new BindingReceiptV1.Step(current.ordinal, current.depth, current.binding,
                                    current.component, current.context.messageId(), currentEvents, conditions,
                                    "PLANNED", "", current.raw));
                            String targetComponent = binding.target() instanceof BindingIrV1.CommandTarget command
                                    ? command.component() : current.component;
                            String targetTopic = program.ir().components().stream()
                                    .filter(componentSpec -> componentSpec.id().equals(targetComponent))
                                    .findFirst().orElseThrow().ingressTopic();
                            var derived = new TransitionContext(origin.height(), origin.timestamp(),
                                    origin.originalMessageIndex(), id, targetTopic, origin.sender());
                            current = new Step(targetComponent, new byte[0], derived, binding.id(), produced,
                                    current.depth + 1,
                                    binding.target().mapping().kind() == BindingIrV1.MappingKind.RAW_BODY);
                            currentEvents = List.of();
                            conditions = new ArrayList<>();
                            throw mappingFailure;
                        }
                        if (binding.target() instanceof BindingIrV1.CommandTarget target) {
                            String topic = program.ir().components().stream()
                                    .filter(c -> c.id().equals(target.component()))
                                    .findFirst().orElseThrow().ingressTopic();
                            TransitionContext derived = new TransitionContext(origin.height(), origin.timestamp(),
                                    origin.originalMessageIndex(), id, topic, origin.sender());
                            queue.addLast(new Step(target.component(), payload, derived, binding.id(), produced,
                                    current.depth + 1, target.mapping().kind() == BindingIrV1.MappingKind.RAW_BODY));
                        } else {
                            var target = (BindingIrV1.EffectTarget) binding.target();
                            EffectIntent effect = new EffectIntent(target.effectType(), payload,
                                    "binding/" + HexFormat.of().formatHex(id),
                                    target.gate().equals("app-final")
                                            ? FinalityGate.APP_FINAL : FinalityGate.L1_ANCHORED,
                                    target.resultPolicy().equals("none") ? ResultPolicy.NONE : ResultPolicy.CHAIN,
                                    target.expiryBlocks(), source.getMessageId());
                            TransitionContext effectContext = new TransitionContext(origin.height(), origin.timestamp(),
                                    origin.originalMessageIndex(), id, current.context.topic(), origin.sender());
                            plans.add(new Planned(new Step(current.component, payload, effectContext, binding.id(),
                                    produced, current.depth + 1, false),
                                    new TransitionPlan(List.of(), List.of(effect), List.of(), List.of())));
                            trace.add(new BindingReceiptV1.Step(produced, current.depth + 1, binding.id(),
                                    current.component, id, List.of(), List.of(), "EFFECT_PLANNED", "", false));
                        }
                    }
                }
                trace.add(new BindingReceiptV1.Step(current.ordinal, current.depth, current.binding,
                        current.component, current.context.messageId(), currentEvents,
                        conditions, "PLANNED", "", current.raw));
                // Fail before any commit if the trace no longer fits its authenticated-state contract.
                try { receipt(source, origin, true, null, "", trace).encode(); }
                catch (IllegalArgumentException capacity) { throw new BindingFailure("RECEIPT_CAPACITY_EXCEEDED"); }
            }
            EffectFailure effectFailure = preflightEffects(plans, origin.height(), context.remainingEffectCapacity());
            if (effectFailure != null) {
                current = effectFailure.step();
                throw new BindingFailure(effectFailure.code());
            }
            byte[] commandHash = Blake2bUtil.blake2bHash256(source.getBody());
            if (context.claim(HexFormat.of().formatHex(source.getMessageId()), commandHash)
                    != CompositeWorkflowContext.ClaimResult.CLAIMED) throw new BindingFailure("REPLAY_OR_CONFLICT");
        } catch (BindingFailure rejected) {
            failure = rejected.code();
            if (rejected.bindingId() != null && conditions.size() < BindingReceiptV1.MAX_CONDITION_RECORDS) {
                conditions.add(new BindingReceiptV1.Condition(rejected.bindingId(), rejected.clause()));
            }
        }
        if (failure != null) {
            int failedOrdinal = current.ordinal;
            var prior = trace.stream().filter(step -> step.ordinal() == failedOrdinal).findFirst();
            if (prior.isPresent()) {
                currentEvents = prior.get().eventsProduced();
                conditions = prior.get().conditions();
            }
            trace.removeIf(step -> step.ordinal() == failedOrdinal);
            trace.add(new BindingReceiptV1.Step(current.ordinal, current.depth, current.binding,
                    current.component, current.context.messageId(), currentEvents, conditions,
                    "REJECTED", failure, current.raw));
        }
        trace.sort(Comparator.comparingInt(BindingReceiptV1.Step::ordinal));
        BindingReceiptV1 receipt = receipt(source, origin, failure == null,
                failure == null ? null : current.ordinal, failure == null ? "" : failure, trace);
        byte[] encoded;
        while (true) {
            try { encoded = receipt.encode(); break; }
            catch (IllegalArgumentException tooLarge) {
                failure = "RECEIPT_CAPACITY_EXCEEDED";
                compactFailureTrace(trace, current.ordinal);
                receipt = receipt(source, origin, false, current.ordinal, "RECEIPT_CAPACITY_EXCEEDED", trace);
            }
        }
        // Receipt encoding is part of approval, never a fallible operation after component commits.
        // No broad catch surrounds commit: remaining failures are infrastructure/invariant failures.
        if (failure == null) {
            for (Planned plan : plans) {
                ComponentGeneration generation = generations.get(plan.step.component);
                TransitionPlans.commit(plan.plan, context.state(generation), context.effects(generation));
            }
        }
        context.workflowState().put(WORK_KEY, ByteBuffer.allocate(4).putInt(work.derived).array());
        context.workflowState().put(EXPRESSION_KEY, ByteBuffer.allocate(8).putLong(work.expressions.used()).array());
        context.workflowState().put(source.getMessageId(), encoded);
        if (failure == null) work.accepted++; else work.rejected++;
    }

    /**
     * Reduces an oversized rejection trace without removing the diagnostic that explains its failure.
     * Higher-ordinal non-failed history is omitted first. If the failed step alone exceeds the byte cap,
     * its event names are omitted, while every visited condition (including the exact failing clause),
     * binding, target, message id, original code, and raw-body marker is retained.
     *
     * <p>The remaining failed step necessarily fits: at most 256 conditions with 127-character ASCII
     * binding names consume less than 35 KiB, plus bounded metadata. A missing failed step or an oversized
     * event-free failed step is therefore an infrastructure invariant violation, not another truncation.
     *
     * @param trace mutable trace sorted by derivation ordinal
     * @param failedOrdinal ordinal that must remain represented by its diagnostic step
     */
    static void compactFailureTrace(List<BindingReceiptV1.Step> trace, int failedOrdinal) {
        if (trace.stream().noneMatch(step -> step.ordinal() == failedOrdinal)) {
            throw new IllegalStateException("rejected trace has no failed step");
        }
        for (int index = trace.size() - 1; index >= 0; index--) {
            if (trace.get(index).ordinal() != failedOrdinal) {
                trace.remove(index);
                return;
            }
        }
        var failed = trace.getFirst();
        if (failed.eventsProduced().isEmpty()) {
            throw new IllegalStateException("event-free failure diagnostic exceeds receipt limit");
        }
        trace.set(0, new BindingReceiptV1.Step(failed.ordinal(), failed.depth(), failed.bindingId(),
                failed.targetComponentId(), failed.messageId(), List.of(), failed.conditions(),
                failed.status(), failed.code(), failed.rawBody()));
    }

    private <C, F> TransitionDecision decide(TransitionKernel<C, F> kernel, Step step, AppStateReader state,
                                            Map<String, AppStateReader> participants,
                                            CompositeWorkflowContext context) {
        C command;
        var codec = kernel.codec();
        try { command = codec.decode(step.body); }
        catch (RuntimeException malformed) {
            throw new BindingFailure(step.depth == 0 ? "MALFORMED_SOURCE_COMMAND" : "MALFORMED_DERIVED_COMMAND");
        }
        // Both hooks are pure/repeatable: the default contextual hook delegates to stateless admission,
        // but an override must not bypass configured command bounds for derived messages.
        if (!kernel.admit(command).isAccepted() || !kernel.admit(command, step.context).isAccepted()) {
            throw new BindingFailure("ADMISSION");
        }
        kernel.workRequest(command, step.context).ifPresent(request -> {
            var budget = program.workBudget(step.component, request.reference());
            var owner = generations.get(request.reference().participantId());
            if (!TransitionWorkAccounting.reserve(context.state(owner), budget, step.context.height(),
                    request.units())) {
                throw new BindingFailure("CRYPTO_WORK_EXCEEDED");
            }
        });
        return kernel.decide(command, step.context, kernel.facts(command, step.context, state, participants));
    }
    private void validateEvent(String component, String event, Map<String, Object> values) {
        var schema = program.schema(component, event);
        values.forEach((field, value) -> {
            if (schema.get(field) != BindingExpressionEvaluator.type(value))
                    throw new BindingFailure("EVENT_TYPE_ERROR");
        });
        if (!BindingProgram.BASELINE.equals(event)) {
            var descriptor = program.kernel(component).events().stream()
                    .filter(candidate -> candidate.eventId().equals(event)).findFirst().orElseThrow();
            if (descriptor.fields().stream().anyMatch(field -> field.required() && !values.containsKey(field.name()))) {
                throw new BindingFailure("EVENT_MISSING_FIELD");
            }
        }
    }
    /** Ordinary writes may overlap across steps; one-use consumption keys may not be overwritten or reused. */
    private void preflightMutations(String component, TransitionPlan plan,
                                            Set<String> consumed, Set<String> written) {
        for (StateMutation mutation : allWrites(plan)) {
            if (program.isAccountingKey(component, mutation.key())) {
                throw new BindingFailure("RESERVED_ACCOUNTING_KEY");
            }
            try { CompositeStateKeys.componentKey(component, mutation.key()); }
            catch (IllegalArgumentException tooLarge) { throw new BindingFailure("STATE_KEY_LIMIT"); }
            String key = component + "/" + HexFormat.of().formatHex(mutation.key());
            if (consumed.contains(key)) throw new BindingFailure("CONSUMPTION_CONFLICT");
        }
        for (StateMutation mutation : plan.consumptions()) {
            String key = component + "/" + HexFormat.of().formatHex(mutation.key());
            if (written.contains(key) || !consumed.add(key)) throw new BindingFailure("CONSUMPTION_CONFLICT");
        }
        for (StateMutation mutation : allWrites(plan)) {
            written.add(component + "/" + HexFormat.of().formatHex(mutation.key()));
        }
    }
    /** Returns the exact failing step so effect-only derivations are diagnosable in rejected receipts. */
    private EffectFailure preflightEffects(List<Planned> plans, long height, int remaining) {
        int count = 0;
        for (Planned plan : plans) for (EffectIntent intent : plan.plan.effects()) {
            if (plan.step.depth > program.ir().limits().maxCascadeDepth()) {
                return new EffectFailure(plan.step, "LIMIT_DEPTH");
            }
            if (++count > remaining) return new EffectFailure(plan.step, "EFFECT_CAPACITY_EXCEEDED");
            if (!consensus.effectsEnabled() || intent.payload().length > consensus.effectsMaxPayloadBytes()) {
                return new EffectFailure(plan.step, "EFFECT_PAYLOAD_LIMIT");
            }
            long expiry = intent.expiryBlocks() == 0
                    ? Math.min(consensus.effectsMaxExpiryBlocks(),
                            consensus.effectsResultWindowBlocks()) : intent.expiryBlocks();
            if (intent.result() == ResultPolicy.CHAIN && (expiry > consensus.effectsMaxExpiryBlocks()
                    || expiry > consensus.effectsResultWindowBlocks() || expiry > Long.MAX_VALUE - height)) {
                return new EffectFailure(plan.step, "EFFECT_EXPIRY_LIMIT");
            }
        }
        return null;
    }
    private static List<StateMutation> allWrites(TransitionPlan plan) {
        List<StateMutation> all = new ArrayList<>(plan.mutations());
        all.addAll(plan.consumptions());
        all.addAll(plan.receipts());
        return all;
    }
    private static BindingReceiptV1 receipt(AppMessage source, TransitionContext origin, boolean accepted,
                                            Integer failed, String code, List<BindingReceiptV1.Step> trace) {
        return new BindingReceiptV1(source.getMessageId(), origin.height(), accepted, failed, code, trace);
    }
    /**
     * Derives a replay-stable identifier using domain-separated Blake2b-256.
     * The preimage is the ASCII domain (including its NUL terminator), source id, ASCII binding id,
     * and the four-byte big-endian derivation ordinal. Changing this layout changes consensus behavior.
     *
     * @param source original source-message id, expected to contain 32 bytes
     * @param binding validated binding identifier
     * @param ordinal one-based derivation ordinal within the source cascade
     * @return a fresh 32-byte identifier
     */
    public static byte[] derivedId(byte[] source, String binding, int ordinal) {
        byte[] domain = "yano-x-derived-command-v1\0".getBytes(StandardCharsets.US_ASCII);
        byte[] name = binding.getBytes(StandardCharsets.US_ASCII);
        return Blake2bUtil.blake2bHash256(ByteBuffer.allocate(domain.length + source.length + name.length + 4)
                .put(domain).put(source).put(name).putInt(ordinal).array());
    }
    private record Step(String component, byte[] body, TransitionContext context,
                        String binding, int ordinal, int depth, boolean raw) { }
    private record Planned(Step step, TransitionPlan plan) { }
    private record EffectFailure(Step step, String code) { }
    private static final class Work {
        private int derived;
        private int accepted;
        private int rejected;
        private int replayed;
        private final BindingExpressionEvaluator.Budget expressions;
        Work(long maximum) { expressions = new BindingExpressionEvaluator.Budget(maximum); }
    }
    private static final class Overlay implements AppStateReader {
        private final AppStateReader parent;
        private final Map<String, Optional<byte[]>> changes = new LinkedHashMap<>();
        Overlay(AppStateReader parent) { this.parent = parent; }
        void apply(TransitionPlan plan) {
            for (StateMutation mutation : allWrites(plan)) {
                changes.put(HexFormat.of().formatHex(mutation.key()),
                        mutation.kind() == StateMutation.Kind.PUT ? Optional.of(mutation.value()) : Optional.empty());
            }
        }
        @Override public Optional<byte[]> get(byte[] key) {
            String id = HexFormat.of().formatHex(key);
            return (changes.containsKey(id) ? changes.get(id) : parent.get(key)).map(byte[]::clone);
        }
        @Override public byte[] stateRoot() { return parent.stateRoot().clone(); }
        @Override public long committedHeight() { return parent.committedHeight(); }
    }
}
