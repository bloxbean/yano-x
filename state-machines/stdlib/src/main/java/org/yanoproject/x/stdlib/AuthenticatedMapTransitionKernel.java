package org.yanoproject.x.stdlib;

import org.yanoproject.api.appchain.AppStateMachine.AdmissionResult;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.codec.MessageCodec;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.EventDescriptor;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionEvent;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionPlan;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.api.appchain.transition.TransitionWorkReference;
import org.yanoproject.api.appchain.transition.TransitionWorkRequest;
import org.yanoproject.x.roles.contracts.ApprovalProposalV1;
import org.yanoproject.x.roles.contracts.RoleWorkflowKeys;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.MapActionV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.MapApprovalReferenceV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure authenticated-map adapter with a closed scalar-map command bridge.
 *
 * <p>The v1 leaf command is canonical CBOR {@code {action: bstr, approvalReference?: tstr}}.
 * Action bytes are the existing canonical {@code MapActionV1}; the optional proposal id is evidence,
 * never a signature or an authorization grant. The adapter reconstructs the complete approval reference
 * from action assignments and the immutable proposal revision, then calls the unchanged role authorizer.
 * Thus the compact declarative mapping cannot change the signed action or bypass one-use consumption.
 * Alternatively {@code {command: bstr}} carries an unchanged canonical final-v1 action/evidence envelope;
 * its entire field is evidence, so bindings must copy it directly from an event. Signed evidence in that
 * envelope requests non-refundable work from the actor-owned budget before authorization facts are read.
 */
final class AuthenticatedMapTransitionKernel implements
        TransitionKernel<AuthenticatedMapTransitionKernel.Command, AuthenticatedMapTransitionKernel.Facts> {
    private final AuthenticatedMapStateMachine map;
    private final String actors;
    private final String approvals;
    private final AuthenticatedMapDirectAuthorizer authorizer;

    AuthenticatedMapTransitionKernel(AuthenticatedMapStateMachine map, String actors, String approvals) {
        this.map = map;
        this.actors = actors;
        this.approvals = approvals;
        boolean governed = map.genesis().governedGenesis() != null;
        if (governed && (actors.isBlank() || approvals.isBlank() || actors.equals(approvals))) {
            throw new IllegalArgumentException("governed map requires distinct actor and approval participants");
        }
        if (!governed && (!actors.isEmpty() || !approvals.isEmpty())) {
            throw new IllegalArgumentException("ungoverned map does not accept governance participants");
        }
        authorizer = governed ? new AuthenticatedMapDirectAuthorizer(map.genesis().chainId(), map.genesisId(),
                map.genesis().governedGenesis().limits()) : null;
    }

    /** Decoded leaf input; the referenced proposal is resolved only through declared participant facts. */
    record Command(MapActionV1 action, String approvalReference, AuthenticatedMapCommandV1 authorized) {
        Command(MapActionV1 action, String approvalReference) { this(action, approvalReference, null); }
    }

    /** Snapshot of domain facts plus an authorization result; no reader or writer escapes facts collection. */
    record Facts(AuthenticatedMapStateMachine.MapFacts mapFacts,
                 AuthenticatedMapDirectAuthorizer.AuthorizationResult authorization, boolean replay) { }

    @Override public MessageCodec<Command> codec() {
        return new MessageCodec<>() {
            @Override public Class<Command> type() { return Command.class; }
            @Override public byte[] encode(Command command) {
                if (command.authorized() != null) {
                    return TransitionScalars.encode(Map.of("command",
                            AuthenticatedMapAuthorizationContract.encodeCommand(command.authorized())));
                }
                byte[] action = AuthenticatedMapAuthorizationContract.encodeAction(command.action());
                return TransitionScalars.encode(command.approvalReference().isEmpty()
                        ? Map.of("action", action)
                        : Map.of("action", action, "approvalReference", command.approvalReference()));
            }
            @Override public Command decode(byte[] body) {
                Map<String, Object> values = TransitionScalars.decode(body);
                if (values.keySet().equals(Set.of("command")) && values.get("command") instanceof byte[] encoded) {
                    var authorized = AuthenticatedMapAuthorizationContract.decodeCommand(encoded);
                    return new Command(authorized.action(), "", authorized);
                }
                if (!Set.of("action", "approvalReference").containsAll(values.keySet())
                        || !(values.get("action") instanceof byte[] action)
                        || values.containsKey("approvalReference")
                        && !(values.get("approvalReference") instanceof String)) {
                    throw new IllegalArgumentException("invalid authenticated-map leaf command");
                }
                String reference = (String) values.getOrDefault("approvalReference", "");
                if (values.containsKey("approvalReference") && reference.isBlank()) {
                    throw new IllegalArgumentException("approval reference must not be empty");
                }
                return new Command(AuthenticatedMapAuthorizationContract.decodeAction(action), reference);
            }
        };
    }

    @Override public AdmissionResult admit(Command command, TransitionContext context) {
        try {
            var legacy = new AuthenticatedMapContract.Command(command.action().batch(), command.action().mutations());
            map.validateCommandBounds(legacy);
            map.validateCommandValues(legacy);
            if (codec().encode(command).length > map.genesis().maxBatchBytes()) {
                return AdmissionResult.reject("MAP_COMMAND_BYTES");
            }
            return AdmissionResult.accept();
        } catch (IllegalArgumentException malformed) {
            return AdmissionResult.reject("MAP_COMMAND_INVALID");
        }
    }

    @Override public List<String> readParticipants() {
        return authorizer == null ? List.of() : List.of(actors, approvals);
    }

    /**
     * Resolves canonical {@code [collectionId, applicationKey]} coordinates through the map's established
     * key derivation helper. Logical keys cannot address receipts, genesis markers, or authorization counters.
     */
    @Override public byte[] lookupKey(byte[] logicalKey) {
        if (logicalKey == null || logicalKey.length > 512) {
            throw new IllegalArgumentException("map logical lookup key exceeds its bound");
        }
        Object decoded = BindingCbor.decode(logicalKey, 512);
        if (!(decoded instanceof List<?> fields) || fields.size() != 2
                || !(fields.get(0) instanceof String collection) || !(fields.get(1) instanceof byte[] key)) {
            throw new IllegalArgumentException("map lookup requires collection and application key");
        }
        var descriptor = map.genesis().collections().stream().filter(item -> item.id().equals(collection))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown map lookup collection"));
        if (key.length > descriptor.maxKeyBytes()) {
            throw new IllegalArgumentException("map lookup application key exceeds collection bound");
        }
        return AuthenticatedMapContract.canonicalKey(collection, key);
    }

    /** Declares the actor-owned shared counter; the map never receives a writer to actor state. */
    @Override public List<TransitionWorkReference> workReferences() {
        return authorizer == null ? List.of() : List.of(new TransitionWorkReference(actors, "governed-crypto-v1"));
    }

    /** Charges original signed evidence before any signature verification, including subsequently rejected actions. */
    @Override public Optional<TransitionWorkRequest> workRequest(Command command, TransitionContext context) {
        int units = command.authorized() == null ? 0 : command.authorized().cryptoWorkUnits();
        return authorizer == null || units == 0 ? Optional.empty()
                : Optional.of(new TransitionWorkRequest(workReferences().getFirst(), units));
    }

    @Override public Facts facts(Command command, TransitionContext context, AppStateReader state) {
        if (authorizer != null) throw new IllegalArgumentException("governed map requires participant readers");
        return facts(command, context, state, Map.of());
    }

    @Override public Facts facts(Command command, TransitionContext context, AppStateReader state,
                                 Map<String, AppStateReader> participants) {
        if (!participants.keySet().equals(Set.copyOf(readParticipants()))) {
            throw new IllegalArgumentException("incorrect map authorization participants");
        }
        var authorization = AuthenticatedMapDirectAuthorizer.AuthorizationResult.accepted(Set.of(), List.of());
        if (state.get(AuthenticatedMapContract.receiptKey(context.messageId())).isPresent()) {
            return new Facts(null, authorization, true);
        }
        var legacy = new AuthenticatedMapContract.Command(command.action().batch(), command.action().mutations());
        var evidence = new ArrayList<AuthenticatedMapAuthorizationContract.AuthorizationEvidenceV1>();
        if (!command.approvalReference().isEmpty()) {
            if (authorizer == null) return rejectedFacts(AuthenticatedMapContract.ERROR_APPROVAL_MISMATCH);
            ApprovalProposalV1 proposal = participants.get(approvals)
                    .get(RoleWorkflowKeys.proposal(command.approvalReference()))
                    .map(ApprovalProposalV1::decode).orElse(null);
            if (proposal == null) return rejectedFacts(AuthenticatedMapContract.ERROR_APPROVAL_NOT_APPROVED);
            var assignments = command.action().authorizations().stream()
                    .filter(item -> item.authorizationKind() == AuthenticatedMapContract.AUTH_APPROVAL).toList();
            if (assignments.isEmpty() || assignments.stream().anyMatch(item -> item.evidenceHandle() != 1
                    || !item.policyId().equals(proposal.policyId()))) {
                return rejectedFacts(AuthenticatedMapContract.ERROR_AUTHORIZATION_ASSIGNMENT);
            }
            evidence.add(new MapApprovalReferenceV1(command.approvalReference(),
                    AuthenticatedMapAuthorizationContract.actionCommitment(command.action()),
                    assignments.stream().map(item -> item.mutationIndex()).toList(),
                    proposal.policyId(), proposal.policyRevision()));
        }
        AuthenticatedMapCommandV1 resolved;
        try {
            resolved = command.authorized() == null
                    ? new AuthenticatedMapCommandV1(command.action(), evidence) : command.authorized();
            map.validateAuthorizationAssignments(resolved);
        } catch (IllegalArgumentException malformed) {
            return rejectedFacts(AuthenticatedMapContract.ERROR_AUTHORIZATION_ASSIGNMENT);
        }
        if (authorizer != null) {
            // The workflow has reserved workRequest before entering facts. Approval references have no
            // signature work; explicit signed envelopes consume the shared actor-owned work budget.
            authorization = authorizer.authorizeReserved(resolved, context.height(), context.messageId(),
                    participants.get(actors), participants.get(approvals), state);
        }
        return new Facts(map.commandFacts(context.height(), context.sender(), legacy,
                authorization.consumptions(), state), authorization, false);
    }

    private static Facts rejectedFacts(int error) {
        return new Facts(null, AuthenticatedMapDirectAuthorizer.AuthorizationResult.rejected(error), false);
    }

    @Override public TransitionDecision decide(Command command, TransitionContext context, Facts facts) {
        if (!facts.authorization().accepted()) {
            return TransitionDecision.reject("MAP_" + facts.authorization().errorCode(), "map authorization rejected");
        }
        if (facts.replay()) return TransitionDecision.approve(TransitionPlan.empty());
        var legacy = new AuthenticatedMapContract.Command(command.action().batch(), command.action().mutations());
        var result = map.decideCommand(context.height(), context.sender(), context.messageId(), legacy,
                AuthenticatedMapAuthorizationContract.actionCommitment(command.action()),
                facts.authorization().governedMutationIndexes(), facts.authorization().consumptions(),
                facts.mapFacts());
        if (result.receipt().status() != AuthenticatedMapContract.RECEIPT_APPLIED) {
            return TransitionDecision.reject("MAP_" + result.receipt().errorCode(),
                    "map transition rejected");
        }
        List<TransitionEvent> events = new ArrayList<>();
        if (result.receipt().results().size() + 1 > TransitionPlan.MAX_EVENTS_PER_PLAN) {
            return TransitionDecision.reject("MAP_EVENT_LIMIT", "map batch exceeds composition event capacity");
        }
        for (int index = 0; index < result.receipt().results().size(); index++) {
            var mutation = result.receipt().results().get(index);
            events.add(new TransitionEvent("authenticated-map.entry-updated.v1", TransitionScalars.encode(Map.of(
                    "collectionId", mutation.collectionId(), "applicationKey", mutation.applicationKey(),
                    "operation", (long) command.action().mutations().get(index).operation(),
                    "revision", mutation.revision(), "status", (long) mutation.status(),
                    "logicalValueHash", mutation.logicalValueHash(), "sender", context.sender()))));
        }
        var receipt = result.receipt();
        events.add(new TransitionEvent("authenticated-map.batch-applied.v1", TransitionScalars.encode(Map.of(
                "messageId", receipt.messageId(), "batchCommitment", receipt.batchCommitment(),
                "resultCommitment", receipt.resultCommitment(), "resultCount", (long) receipt.results().size(),
                "status", (long) receipt.status(), "errorCode", (long) receipt.errorCode()))));
        var plan = result.plan();
        return TransitionDecision.approve(new TransitionPlan(plan.mutations(), plan.effects(), plan.consumptions(),
                plan.receipts(), events));
    }

    @Override public List<CommandDescriptor> commands() {
        return List.of(new CommandDescriptor("apply-action", CommandDescriptor.Layout.MAP, 0, List.of(
                field("action", TransitionScalars.Type.BYTES),
                new CommandDescriptor.Field("approvalReference", TransitionScalars.Type.TEXT, true,
                        CommandDescriptor.Role.EVIDENCE))),
                new CommandDescriptor("apply-basic-action", CommandDescriptor.Layout.MAP, 0,
                        List.of(field("action", TransitionScalars.Type.BYTES))),
                new CommandDescriptor("apply-authorized", CommandDescriptor.Layout.MAP, 0, List.of(
                        new CommandDescriptor.Field("command", TransitionScalars.Type.BYTES, true,
                                CommandDescriptor.Role.EVIDENCE))));
    }

    @Override public List<EventDescriptor> events() {
        return List.of(new EventDescriptor("authenticated-map.entry-updated.v1", List.of(
                field("collectionId", TransitionScalars.Type.TEXT),
                field("applicationKey", TransitionScalars.Type.BYTES),
                field("operation", TransitionScalars.Type.INTEGER),
                field("revision", TransitionScalars.Type.INTEGER), field("status", TransitionScalars.Type.INTEGER),
                field("logicalValueHash", TransitionScalars.Type.BYTES),
                field("sender", TransitionScalars.Type.BYTES))),
                new EventDescriptor("authenticated-map.batch-applied.v1", List.of(
                        field("messageId", TransitionScalars.Type.BYTES),
                        field("batchCommitment", TransitionScalars.Type.BYTES),
                        field("resultCommitment", TransitionScalars.Type.BYTES),
                        field("resultCount", TransitionScalars.Type.INTEGER),
                        field("status", TransitionScalars.Type.INTEGER),
                        field("errorCode", TransitionScalars.Type.INTEGER))));
    }

    @Override public ConfigurationDescriptor configuration() {
        return new ConfigurationDescriptor(List.of(
                new ConfigurationDescriptor.Setting("genesis-cbor-hex", TransitionScalars.Type.TEXT, null),
                new ConfigurationDescriptor.Setting("actors", TransitionScalars.Type.TEXT, ""),
                new ConfigurationDescriptor.Setting("approvals", TransitionScalars.Type.TEXT, "")));
    }

    private static CommandDescriptor.Field field(String name, TransitionScalars.Type type) {
        return new CommandDescriptor.Field(name, type, true, CommandDescriptor.Role.DATA);
    }
}
