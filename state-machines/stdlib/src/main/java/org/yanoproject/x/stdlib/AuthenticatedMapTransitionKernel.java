package org.yanoproject.x.stdlib;

import org.yanoproject.api.appchain.AppStateMachine.AdmissionResult;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.codec.MessageCodec;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.EventDescriptor;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.api.appchain.transition.RuleValueView;
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
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapSchema;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.SimpleValueType;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 *
 * <p>For declarative admission rules (ADR-031.4 §5.4) each collection is a value view of its entries, and a
 * schema-typed collection also exposes its values' top-level scalar members. The kernel's write view describes each
 * mutation of a command, and its coverage names who verifiably authorized each write, taken only from the facts of
 * an approved decision.
 */
final class AuthenticatedMapTransitionKernel implements
        TransitionKernel<AuthenticatedMapTransitionKernel.Command, AuthenticatedMapTransitionKernel.Facts> {
    private final AuthenticatedMapStateMachine map;
    private final String actors;
    private final String approvals;
    private final AuthenticatedMapDirectAuthorizer authorizer;
    /** Exposed value members per collection, in schema member order (ADR-031.4 §5.4). */
    private final Map<String, List<RuleFact>> valueFields;
    /** Write value fields: members every collection that declares them types alike. */
    private final Map<String, RuleFact.Type> writeValueTypes;

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
        Map<String, List<RuleFact>> exposed = new LinkedHashMap<>();
        Map<String, RuleFact.Type> union = new LinkedHashMap<>();
        Set<String> conflicting = new HashSet<>();
        for (var collection : map.genesis().collections()) {
            boolean canonical = collection.valueEncoding() == AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR;
            List<RuleFact> members = canonical ? exposedMembers(map.schemaOf(collection.id())) : List.of();
            exposed.put(collection.id(), members);
            for (RuleFact member : members) {
                var previous = union.putIfAbsent(member.name(), member.type());
                if (previous != null && previous != member.type()) conflicting.add(member.name());
            }
        }
        conflicting.forEach(union::remove);
        valueFields = Map.copyOf(exposed);
        writeValueTypes = Map.copyOf(union);
    }

    /**
     * The value members a schema exposes: for an exact text-keyed map root, the first
     * {@link RuleValueView#MAX_FIELDS} members in schema order whose key is a CEL identifier and whose type is one
     * scalar kind. Other roots, keys and types expose nothing.
     */
    private static List<RuleFact> exposedMembers(AuthenticatedMapSchema.Schema schema) {
        if (schema == null || !(schema.root() instanceof AuthenticatedMapSchema.MapNode root)) return List.of();
        List<RuleFact> members = new ArrayList<>();
        for (var field : root.fields()) {
            if (members.size() == RuleValueView.MAX_FIELDS) break;
            if (!field.key().matches("[a-zA-Z][a-zA-Z0-9_]{0,62}")
                    || RuleFact.RESERVED_NAMES.contains(field.key())) {
                continue;
            }
            RuleFact.Type type = switch (field.value()) {
                case AuthenticatedMapSchema.IntegerNode _ -> RuleFact.Type.INTEGER;
                case AuthenticatedMapSchema.TextNode _ -> RuleFact.Type.TEXT;
                case AuthenticatedMapSchema.BytesNode _ -> RuleFact.Type.BYTES;
                case AuthenticatedMapSchema.BooleanNode _ -> RuleFact.Type.BOOLEAN;
                default -> null;
            };
            if (type != null) members.add(new RuleFact(field.key(), type));
        }
        return List.copyOf(members);
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

    /** Checks configured command bounds and canonical values without fabricating a block execution context. */
    @Override public AdmissionResult admit(Command command) {
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
        var authorization = AuthenticatedMapDirectAuthorizer.AuthorizationResult.accepted(Set.of(), List.of(),
                List.of(), 0);
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

    /**
     * Facts for admission rules (ADR-031.3 §5.5). Every map declares the sender's membership and the collections
     * a batch writes; a governed map also declares its verified evidence counts and, for exactly one direct
     * actor, that actor's verified identity, organization, roles and policy.
     */
    @Override public List<RuleFact> ruleFacts() {
        List<RuleFact> facts = new ArrayList<>(List.of(new RuleFact("senderMember", RuleFact.Type.BOOLEAN),
                new RuleFact("collections", RuleFact.Type.TEXT_SET)));
        if (authorizer != null) {
            facts.addAll(List.of(new RuleFact("directActorCount", RuleFact.Type.INTEGER),
                    new RuleFact("approvalCount", RuleFact.Type.INTEGER),
                    new RuleFact("actorId", RuleFact.Type.TEXT),
                    new RuleFact("organizationId", RuleFact.Type.TEXT),
                    new RuleFact("role", RuleFact.Type.TEXT),
                    new RuleFact("roles", RuleFact.Type.TEXT_SET),
                    new RuleFact("policyId", RuleFact.Type.TEXT)));
        }
        return List.copyOf(facts);
    }

    /**
     * Values read only from the facts that produced the approval, so every value was verified by {@link #facts}.
     * A replay recognized by its receipt key approves an empty plan without facts; a fact rule then fails closed.
     */
    @Override public Map<String, Object> ruleFactValues(Command command, TransitionContext context, Facts facts) {
        if (facts.replay() || facts.mapFacts() == null) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("senderMember", facts.mapFacts().senderMember());
        values.put("collections", sortedText(command.action().mutations().stream()
                .map(AuthenticatedMapContract.Mutation::collectionId).toList()));
        if (authorizer != null) {
            var authorization = facts.authorization();
            values.put("directActorCount", (long) authorization.directFacts().size());
            values.put("approvalCount", (long) authorization.approvalCount());
            if (authorization.directFacts().size() == 1) {
                var direct = authorization.directFacts().getFirst();
                values.put("actorId", direct.actor().actorId());
                values.put("organizationId", direct.organization().organizationId());
                values.put("role", direct.policy().requiredRole());
                values.put("roles", sortedText(direct.actor().roles()));
                values.put("policyId", direct.policy().policyId());
            }
        }
        return values;
    }

    private static final List<RuleFact> ENTRY_FIELDS = List.of(new RuleFact("status", RuleFact.Type.TEXT),
            new RuleFact("revision", RuleFact.Type.INTEGER), new RuleFact("createdHeight", RuleFact.Type.INTEGER),
            new RuleFact("lastMutationHeight", RuleFact.Type.INTEGER),
            new RuleFact("valueLength", RuleFact.Type.INTEGER), new RuleFact("controller", RuleFact.Type.BYTES));

    /** ADR-031.4 §5.4: one view per collection, its entries' fields and the schema's value members. */
    @Override public List<RuleValueView> ruleValueViews() {
        return map.genesis().collections().stream().map(collection -> new RuleValueView(collection.id(),
                ENTRY_FIELDS, valueFields.get(collection.id()))).toList();
    }

    /** A read of {@code key} in a collection is the entry at the collection's canonical key. */
    @Override public byte[] ruleValueKey(String namespace, byte[] key) {
        var descriptor = map.genesis().collections().stream().filter(item -> item.id().equals(namespace))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown map collection"));
        if (key.length == 0 || key.length > descriptor.maxKeyBytes()) {
            throw new IllegalArgumentException("map application key outside the collection bound");
        }
        return AuthenticatedMapContract.canonicalKey(namespace, key);
    }

    @Override public Map<String, Object> ruleValueFields(String namespace, byte[] key, byte[] stored) {
        AuthenticatedMapContract.Entry entry;
        try {
            entry = AuthenticatedMapContract.decodeEntry(stored);
        } catch (RuntimeException undecodable) {
            return Map.of();
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", entry.status() == AuthenticatedMapContract.STATUS_ACTIVE ? "ACTIVE" : "REVOKED");
        fields.put("revision", entry.revision());
        fields.put("createdHeight", entry.createdHeight());
        fields.put("lastMutationHeight", entry.lastMutationHeight());
        fields.put("valueLength", (long) entry.value().length);
        fields.put("controller", entry.controller());
        if (entry.status() == AuthenticatedMapContract.STATUS_ACTIVE) {
            memberValues(entry.value(), valueFields.getOrDefault(namespace, List.of()))
                    .forEach((name, value) -> fields.put(RuleValueView.VALUE_PREFIX + name, value));
        }
        return fields;
    }

    /**
     * The exposed members of one canonical-CBOR map value: scalars of the member's kind; text and bytes over
     * {@link RuleFact#MAX_VALUE_BYTES} are absent, and an integer outside int64 is reported exactly, which the engine
     * refuses. A value that is not such a map exposes nothing.
     */
    private static Map<String, Object> memberValues(byte[] value, List<RuleFact> members) {
        if (members.isEmpty() || value.length == 0) return Map.of();
        DataItem decoded;
        try {
            decoded = CborSerializationUtil.deserializeOne(value);
        } catch (RuntimeException undecodable) {
            return Map.of();
        }
        if (!(decoded instanceof co.nstant.in.cbor.model.Map entries)) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        for (RuleFact member : members) {
            DataItem item = entries.get(new UnicodeString(member.name()));
            Object scalar = switch (item) {
                case UnsignedInteger number when member.type() == RuleFact.Type.INTEGER -> exact(number.getValue());
                case NegativeInteger number when member.type() == RuleFact.Type.INTEGER -> exact(number.getValue());
                case UnicodeString text when member.type() == RuleFact.Type.TEXT
                        && text.getString().getBytes(StandardCharsets.UTF_8).length <= RuleFact.MAX_VALUE_BYTES ->
                        text.getString();
                case ByteString bytes when member.type() == RuleFact.Type.BYTES
                        && bytes.getBytes().length <= RuleFact.MAX_VALUE_BYTES -> bytes.getBytes();
                case SimpleValue simple when member.type() == RuleFact.Type.BOOLEAN
                        && (simple.getSimpleValueType() == SimpleValueType.TRUE
                        || simple.getSimpleValueType() == SimpleValueType.FALSE) ->
                        simple.getSimpleValueType() == SimpleValueType.TRUE;
                case null, default -> null;
            };
            if (scalar != null) values.put(member.name(), scalar);
        }
        return values;
    }

    private static Object exact(BigInteger value) {
        return value.bitLength() < 64 ? (Object) value.longValue() : value;
    }

    private static final List<String> OPERATIONS = List.of("PUT", "PUT_IF_ABSENT", "COMPARE_AND_SET",
            "TRANSFER_CONTROLLER", "REVOKE", "RESTORE");

    /** ADR-031.4 §5.2: one element per mutation of a command, in command order. */
    @Override public List<RuleFact> ruleWriteFields() {
        return List.of(new RuleFact("collection", RuleFact.Type.TEXT), new RuleFact("key", RuleFact.Type.BYTES),
                new RuleFact("keyText", RuleFact.Type.TEXT), new RuleFact("op", RuleFact.Type.TEXT),
                new RuleFact("hasValue", RuleFact.Type.BOOLEAN), new RuleFact("valueLength", RuleFact.Type.INTEGER),
                new RuleFact("expectedRevision", RuleFact.Type.INTEGER));
    }

    @Override public List<RuleFact> ruleWriteCoverageFields() {
        return List.of(new RuleFact("coverage", RuleFact.Type.TEXT), new RuleFact("actorId", RuleFact.Type.TEXT),
                new RuleFact("actorOrganizationId", RuleFact.Type.TEXT),
                new RuleFact("actorRoles", RuleFact.Type.TEXT_SET));
    }

    @Override public List<Map<String, Object>> ruleWrites(Command command) {
        List<Map<String, Object>> writes = new ArrayList<>();
        for (var mutation : command.action().mutations()) {
            Map<String, Object> write = new LinkedHashMap<>();
            write.put("collection", mutation.collectionId());
            write.put("key", mutation.applicationKey());
            utf8(mutation.applicationKey()).ifPresent(text -> write.put("keyText", text));
            write.put("op", OPERATIONS.get(mutation.operation()));
            // Exactly the operations the map validates a value for, so no rule guard can drift from the kernel.
            boolean hasValue = AuthenticatedMapStateMachine.valueBearing(mutation.operation());
            write.put("hasValue", hasValue);
            write.put("valueLength", (long) mutation.value().length);
            write.put("expectedRevision", mutation.expectedRevision());
            if (hasValue) {
                memberValues(mutation.value(), valueFields.getOrDefault(mutation.collectionId(), List.of()))
                        .forEach((name, value) -> {
                            // Only members every collection types alike are declared for writes.
                            if (writeValueTypes.containsKey(name)) write.put(RuleValueView.VALUE_PREFIX + name, value);
                        });
            }
            writes.add(write);
        }
        return writes;
    }

    /**
     * Coverage of each write, from the facts that produced the approval only. After approval each governed write is
     * covered by exactly one evidence item of its collection's policy: {@code direct} with that actor's verified
     * identity, organization and roles, or {@code approval}; a write in an open, owner or member collection reports
     * {@code none}. A replay recognized by its receipt key approves without facts and establishes no coverage, and
     * facts whose authorization was rejected establish none either.
     */
    @Override public List<Map<String, Object>> ruleWriteCoverage(Command command, TransitionContext context,
                                                                 Facts facts) {
        int writes = command.action().mutations().size();
        List<Map<String, Object>> coverage = new ArrayList<>();
        if (facts.replay() || facts.mapFacts() == null || !facts.authorization().accepted()) {
            for (int index = 0; index < writes; index++) coverage.add(Map.of());
            return coverage;
        }
        var authorization = facts.authorization();
        for (int index = 0; index < writes; index++) {
            final int position = index;
            var direct = authorization.directCoverage().stream()
                    .filter(item -> item.indexes().contains(position)).findFirst();
            if (direct.isPresent()) {
                var verified = direct.get().facts();
                coverage.add(Map.of("coverage", "direct", "actorId", verified.actor().actorId(),
                        "actorOrganizationId", verified.organization().organizationId(),
                        "actorRoles", sortedText(verified.actor().roles())));
            } else {
                coverage.add(Map.of("coverage", authorization.approvalIndexes().contains(position) ? "approval"
                        : "none"));
            }
        }
        return coverage;
    }

    private static Optional<String> utf8(byte[] bytes) {
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException malformed) {
            return Optional.empty();
        }
    }

    /** Distinct text in unsigned UTF-8 byte order, the order a {@code TEXT_SET} fact requires. */
    private static List<String> sortedText(List<String> values) {
        return values.stream().distinct().sorted((left, right) -> Arrays.compareUnsigned(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8))).toList();
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
