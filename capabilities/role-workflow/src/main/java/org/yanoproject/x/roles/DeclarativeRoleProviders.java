package org.yanoproject.x.roles;

import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppCapabilityManifest;
import org.yanoproject.api.appchain.AppChainInfo;
import org.yanoproject.api.appchain.AppQueryContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.codec.MessageCodec;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.proof.ProofSubjectProvider;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.EventDescriptor;
import org.yanoproject.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.api.appchain.transition.StateMutation;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionEvent;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionPlan;
import org.yanoproject.api.appchain.transition.TransitionPlans;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.api.appchain.transition.TransitionWorkBudget;
import org.yanoproject.api.appchain.transition.TransitionWorkReference;
import org.yanoproject.api.appchain.transition.TransitionWorkRequest;
import org.yanoproject.x.composite.ComponentDescriptor;
import org.yanoproject.x.roles.contracts.ActorStatementV1;
import org.yanoproject.x.roles.contracts.ApprovalProposalV1;
import org.yanoproject.x.roles.contracts.GovernedGenesisV1;
import org.yanoproject.x.roles.contracts.RoleWorkflowResultCode;
import org.yanoproject.x.roles.contracts.StagedActorCommandV1;
import org.yanoproject.x.roles.internal.ActorApprovalProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catalog contributions for independently configured role participants inside a declarative composite.
 *
 * <p>These are explicit leaves, not unwrapped instances of the standalone role preset. Genesis is committed
 * component configuration and the actor instance reference is explicit. The actor leaf currently supplies
 * genesis authority and the shared work budget; it rejects command derivations rather than implying that
 * bindings authorize registry governance. Approval commands use the same pure lifecycle as standalone roles.
 */
public final class DeclarativeRoleProviders {
    public static final String ACTORS_ID = "domain-actors-component";
    public static final String APPROVALS_ID = "governed-role-approvals";
    public static final String APPROVED_EVENT = "role-approvals.proposal-approved.v1";
    private static final String VERSION = "1.0.0";

    private DeclarativeRoleProviders() { }

    /** Catalog-selected actor genesis and cryptographic-work owner. */
    public static final class Actors implements AppStateMachineProvider {
        @Override public String id() { return ACTORS_ID; }
        @Override public AppStateMachine create() { throw new IllegalArgumentException("actor genesis required"); }
        @Override public AppStateMachine create(AppStateMachineContext context) {
            GovernedGenesisV1 genesis = genesis(context, ACTORS_ID);
            var descriptor = descriptor(DomainActorRegistryComponent.COMPONENT_ID,
                    List.of(DomainActorRegistryComponent.TOPIC), List.of("actor", "actor-current", "organization",
                            "organization-current", "administrator-authority", "administrator-authority-current"));
            var delegate = DomainActorRegistryComponent.genesisBound(descriptor, context.chainId(), genesis,
                    context.stateCommitmentIdentity().orElseThrow().genesisId());
            return new Leaf(ACTORS_ID, delegate, new ActorKernel(genesis), null);
        }
    }

    /** Catalog-selected actor-signed approval lifecycle with versioned action staging. */
    public static final class Approvals implements AppStateMachineProvider {
        @Override public String id() { return APPROVALS_ID; }
        @Override public AppStateMachine create() { throw new IllegalArgumentException("approval genesis required"); }
        @Override public AppStateMachine create(AppStateMachineContext context) {
            GovernedGenesisV1 genesis = genesis(context, APPROVALS_ID);
            String actors = context.settings().getOrDefault("machines." + APPROVALS_ID + ".actor-component", "actors");
            var processor = new ActorApprovalProcessor(context.chainId(), genesis.limits());
            var delegate = new RoleAwareApprovalsComponent(descriptor(RoleAwareApprovalsComponent.COMPONENT_ID,
                    List.of(), List.of("policy", "policy-current", "proposal", "stats", "pending-approvals")), genesis);
            return new Leaf(APPROVALS_ID, delegate, new ApprovalKernel(processor, actors), processor);
        }
    }

    private static GovernedGenesisV1 genesis(AppStateMachineContext context, String machine) {
        String encoded = context.settings().get("machines." + machine + ".genesis-cbor-hex");
        if (encoded == null || encoded.length() > 131_072) {
            throw new IllegalArgumentException("missing or oversized committed role genesis");
        }
        GovernedGenesisV1 genesis = GovernedGenesisV1.decode(HexFormat.of().parseHex(encoded));
        if (!genesis.chainId().equals(context.chainId()))
                throw new IllegalArgumentException("role genesis chain mismatch");
        return genesis;
    }

    private static ComponentDescriptor descriptor(String id, List<String> topics, List<String> queries) {
        return new ComponentDescriptor(id, VERSION, "declarative-role-genesis-v1", id + "-state-v1",
                1, 0, topics, queries, 0);
    }

    private static ConfigurationDescriptor configuration(boolean approvals) {
        var settings = new ArrayList<ConfigurationDescriptor.Setting>();
        settings.add(new ConfigurationDescriptor.Setting("genesis-cbor-hex", TransitionScalars.Type.TEXT, null));
        if (approvals) settings.add(new ConfigurationDescriptor.Setting("actor-component",
                TransitionScalars.Type.TEXT, "actors"));
        return new ConfigurationDescriptor(settings);
    }

    /** Delegates namespace-local initialization/query/proof behavior without exporting the preset's routes. */
    private record Leaf(String id, AppStateMachine delegate, TransitionKernel<?, ?> kernel,
                        ActorApprovalProcessor maintenance) implements AppStateMachine {
        @Override public Optional<TransitionKernel<?, ?>> transitionKernel() { return Optional.of(kernel); }
        @Override public void init(AppStateReader state, AppChainInfo info) { delegate.init(state, info); }
        @Override public AppCapabilityManifest capabilityManifest() { return delegate.capabilityManifest(); }
        @Override public List<ProofSubjectProvider> proofSubjectProviders() { return delegate.proofSubjectProviders(); }
        @Override public byte[] query(String path, byte[] params, AppQueryContext context) {
            return delegate.query(path, params, context);
        }
        @Override public void apply(AppBlockExecutionContext execution, AppStateWriter state,
                AppEffectEmitter effects) {
            if (!execution.messages().isEmpty()) {
                throw new IllegalStateException("declarative role commands must pass through the binding workflow");
            }
            delegate.apply(execution, state, effects);
            if (maintenance == null) return;
            var plan = maintenance.prepareHeightPlan(execution.block().height(), state);
            var mutations = new ArrayList<>(plan.mutations());
            // Maintenance emits explicit proposal mutations, so cleanup needs no state scan or writer adapter.
            for (StateMutation mutation : plan.mutations()) {
                if (mutation.kind() == StateMutation.Kind.PUT && isProposalKey(mutation.key())) {
                    var proposal = ApprovalProposalV1.decode(mutation.value());
                    if (proposal.status() == ApprovalProposalV1.ProposalStatus.EXPIRED) {
                        mutations.add(StateMutation.delete(StagedActorCommandV1.stateKey(proposal.proposalId())));
                    }
                }
            }
            TransitionPlans.commit(TransitionPlan.mutations(mutations), state, effects);
        }
        private static boolean isProposalKey(byte[] key) {
            return key.length > 2 && key[0] == 'q' && key[1] == '/';
        }
    }

    /** Genesis-only authority participant: commands fail closed; declared counters remain engine-owned. */
    private record ActorKernel(GovernedGenesisV1 genesis) implements TransitionKernel<byte[], Boolean> {
        @Override public MessageCodec<byte[]> codec() { return new OrderedLogKernel().codec(); }
        @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state) { return true; }
        @Override public TransitionDecision decide(byte[] command, TransitionContext context, Boolean facts) {
            return TransitionDecision.reject("ACTOR_GOVERNANCE_ROUTE_REQUIRED", "actor leaf is a genesis authority");
        }
        @Override public List<CommandDescriptor> commands() { return List.of(); }
        @Override public List<EventDescriptor> events() { return List.of(); }
        @Override public ConfigurationDescriptor configuration() {
            return DeclarativeRoleProviders.configuration(false);
        }
        @Override public List<TransitionWorkBudget> workBudgets() {
            return List.of(GovernedCryptoWork.budget(genesis.limits().maximumCryptoWorkUnitsPerBlock()));
        }
    }

    /** Immutable approval facts plus the exact staged bytes; no reader or writer escapes the facts boundary. */
    private record ApprovalFacts(ActorApprovalProcessor.Facts lifecycle, byte[] action) {
        private ApprovalFacts { action = action == null ? null : action.clone(); }
        @Override public byte[] action() { return action == null ? null : action.clone(); }
    }

    private record ApprovalKernel(ActorApprovalProcessor processor, String actors)
            implements TransitionKernel<StagedActorCommandV1, ApprovalFacts> {
        @Override public MessageCodec<StagedActorCommandV1> codec() {
            return new MessageCodec<>() {
                @Override public byte[] encode(StagedActorCommandV1 value) { return value.encode(); }
                @Override public StagedActorCommandV1 decode(byte[] body) { return StagedActorCommandV1.decode(body); }
                @Override public Class<StagedActorCommandV1> type() { return StagedActorCommandV1.class; }
            };
        }
        @Override public List<String> readParticipants() { return List.of(actors); }
        @Override public ApprovalFacts facts(StagedActorCommandV1 command, TransitionContext context,
                                             AppStateReader state) {
            throw new IllegalArgumentException("actor participant is required");
        }
        @Override public ApprovalFacts facts(StagedActorCommandV1 command, TransitionContext context,
                                             AppStateReader state, Map<String, AppStateReader> participants) {
            return new ApprovalFacts(processor.facts(command.command(), context.height(),
                    participants.get(actors), state),
                    state.get(StagedActorCommandV1.stateKey(command.command().statement().proposalId())).orElse(null));
        }
        @Override public List<TransitionWorkReference> workReferences() {
            return List.of(new TransitionWorkReference(actors, GovernedCryptoWork.BUDGET_ID));
        }
        @Override public Optional<TransitionWorkRequest> workRequest(StagedActorCommandV1 command,
                                                                     TransitionContext context) {
            return processor.requiresCryptoWork(command.command(), context.height())
                    ? Optional.of(new TransitionWorkRequest(workReferences().getFirst(), 1)) : Optional.empty();
        }
        @Override public TransitionDecision decide(StagedActorCommandV1 command, TransitionContext context,
                                                    ApprovalFacts facts) {
            var result = processor.decide(command.command(), context.height(), facts.lifecycle());
            if (result.code() != RoleWorkflowResultCode.ACCEPTED
                    && result.code() != RoleWorkflowResultCode.EXACT_REPLAY) {
                return TransitionDecision.reject(result.code().name(), "actor approval rejected");
            }
            var statement = command.command().statement();
            if (result.code() == RoleWorkflowResultCode.EXACT_REPLAY) {
                if (statement.action() == ActorStatementV1.Action.PROPOSE
                        && !Arrays.equals(facts.action(), command.action())) {
                    return TransitionDecision.reject("CONFLICT", "replayed proposal changes staged action");
                }
                return TransitionDecision.approve(TransitionPlan.empty());
            }
            var mutations = new ArrayList<>(result.plan().mutations());
            var events = new ArrayList<TransitionEvent>();
            if (statement.action() == ActorStatementV1.Action.PROPOSE) {
                mutations.add(StateMutation.put(StagedActorCommandV1.stateKey(statement.proposalId()),
                        command.action()));
            }
            var proposal = result.proposal();
            if (result.changed() && proposal != null
                    && proposal.status() != ApprovalProposalV1.ProposalStatus.PENDING) {
                if (proposal.status() == ApprovalProposalV1.ProposalStatus.APPROVED) {
                    if (facts.action() == null) {
                        return TransitionDecision.reject("APPROVAL_PAYLOAD_UNAVAILABLE", "staged action is absent");
                    }
                    byte[] payload;
                    try {
                        payload = TransitionScalars.encode(Map.of("proposalId", proposal.proposalId(),
                                "policyId", proposal.policyId(), "policyRevision", proposal.policyRevision(),
                                "payloadDomain", proposal.payloadDomain(), "payloadHash", proposal.payloadHash(),
                                "deadlineHeight", proposal.deadlineHeight(), "action", facts.action(),
                                "decisionCount", (long) proposal.decisions().size()));
                    } catch (IllegalArgumentException tooLarge) {
                        return TransitionDecision.reject("EVENT_PAYLOAD_TOO_LARGE", "approval event exceeds bound");
                    }
                    events.add(new TransitionEvent(APPROVED_EVENT, payload));
                }
                mutations.add(StateMutation.delete(StagedActorCommandV1.stateKey(proposal.proposalId())));
            }
            return TransitionDecision.approve(new TransitionPlan(mutations, result.plan().effects(),
                    result.plan().consumptions(), result.plan().receipts(), events));
        }
        @Override public List<CommandDescriptor> commands() {
            // Positional wrapper preserves actor evidence byte-for-byte; the codec enforces action/op rules.
            return List.of(new CommandDescriptor("actor-command", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 1,
                    List.of(field("signedCommand", TransitionScalars.Type.BYTES, CommandDescriptor.Role.EVIDENCE),
                            field("action", TransitionScalars.Type.BYTES, CommandDescriptor.Role.DATA))));
        }
        @Override public List<EventDescriptor> events() {
            return List.of(new EventDescriptor(APPROVED_EVENT, List.of(
                    field("proposalId", TransitionScalars.Type.TEXT, CommandDescriptor.Role.DATA),
                    field("policyId", TransitionScalars.Type.TEXT, CommandDescriptor.Role.DATA),
                    field("policyRevision", TransitionScalars.Type.INTEGER, CommandDescriptor.Role.DATA),
                    field("payloadDomain", TransitionScalars.Type.TEXT, CommandDescriptor.Role.DATA),
                    field("payloadHash", TransitionScalars.Type.BYTES, CommandDescriptor.Role.DATA),
                    field("deadlineHeight", TransitionScalars.Type.INTEGER, CommandDescriptor.Role.DATA),
                    field("action", TransitionScalars.Type.BYTES, CommandDescriptor.Role.DATA),
                    field("decisionCount", TransitionScalars.Type.INTEGER, CommandDescriptor.Role.DATA))));
        }
        @Override public ConfigurationDescriptor configuration() {
            return DeclarativeRoleProviders.configuration(true);
        }
        private static CommandDescriptor.Field field(String name, TransitionScalars.Type type,
                CommandDescriptor.Role role) {
            return new CommandDescriptor.Field(name, type, true, role);
        }
    }
}
