package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.FinalityCert;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionPlans;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.x.roles.contracts.ActorKeyEpochV1;
import org.yanoproject.x.roles.contracts.ActorKeyProofV1;
import org.yanoproject.x.roles.contracts.ActorRecordV1;
import org.yanoproject.x.roles.contracts.AdministratorAuthorityV1;
import org.yanoproject.x.roles.contracts.ApprovalPolicyV1;
import org.yanoproject.x.roles.contracts.ApprovalProposalV1;
import org.yanoproject.x.roles.contracts.GenesisActorV1;
import org.yanoproject.x.roles.contracts.GovernedAuthorizationLimitsV1;
import org.yanoproject.x.roles.contracts.GovernedGenesisV1;
import org.yanoproject.x.roles.contracts.OrganizationRecordV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.roles.contracts.RoleWorkflowKeys;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.MapActionV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedMapTransitionKernelTest {
    private static final String CHAIN = "map-kernel-test";
    private static final byte[] SENDER = new byte[32];
    private static final TransitionContext CONTEXT = new TransitionContext(2, 1000, 0,
            new byte[32], "registry", SENDER);

    @Test
    void maximumBatchProduces128EntryEventsAndOneBatchEventWithExactStandaloneStateParity() {
        var map = map(AuthenticatedMapContract.AUTH_OWNER, null, 128);
        var kernel = new AuthenticatedMapTransitionKernel(map, "", "");
        var mutations = IntStream.range(0, 128).mapToObj(index -> AuthenticatedMapContract.Mutation.put(
                "records", bytes("key-" + index), bytes("value-" + index))).toList();
        var legacy = AuthenticatedMapContract.Command.batch(mutations);
        var action = MapActionV1.basic(legacy, Collections.nCopies(128, AuthenticatedMapContract.AUTH_OWNER));
        var command = new AuthenticatedMapTransitionKernel.Command(action, "");
        var plannedState = new State();
        var standaloneState = new State();
        map.apply(block(1, List.of()), plannedState, AppEffectEmitter.rejecting("no effects"));
        map.apply(block(1, List.of()), standaloneState, AppEffectEmitter.rejecting("no effects"));

        assertThat(kernel.codec().decode(kernel.codec().encode(command)).action().mutations()).hasSize(128);
        assertThat(kernel.admit(command, CONTEXT).isAccepted()).isTrue();
        var decision = (TransitionDecision.Approved) kernel.decide(command, CONTEXT,
                kernel.facts(command, CONTEXT, plannedState));
        assertThat(plannedState.values).hasSize(1); // Planning did not publish any entry or command receipt.
        assertThat(decision.plan().events()).hasSize(129);
        assertThat(decision.plan().events().subList(0, 128)).allSatisfy(event ->
                assertThat(event.eventId()).isEqualTo("authenticated-map.entry-updated.v1"));
        assertThat(decision.plan().events().getLast().eventId()).isEqualTo("authenticated-map.batch-applied.v1");
        TransitionPlans.commit(decision.plan(), plannedState, AppEffectEmitter.rejecting("no effects"));
        var message = AppMessage.builder().chainId(CHAIN).messageId(CONTEXT.messageId()).sender(SENDER)
                .senderSeq(1).expiresAt(0).topic(AuthenticatedMapContract.DEFAULT_TOPIC)
                .body(AuthenticatedMapAuthorizationContract.encodeCommand(
                        new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(action, List.of())))
                .authScheme(0).authProof(new byte[0]).build();
        // Compare the same final-v1 action contract. Its receipt commits the authorization assignments too;
        // legacy mutation-only commands intentionally have a different batch commitment domain.
        map.applyFinal(block(2, List.of(message)), standaloneState);
        assertThat(plannedState.values.keySet()).containsExactlyInAnyOrderElementsOf(standaloneState.values.keySet());
        standaloneState.values.forEach((key, value) -> assertThat(plannedState.values.get(key)).containsExactly(value));
        assertThat(plannedState.values).hasSize(130); // Genesis marker, 128 entries, unchanged final-v1 receipt.

        // Independently prove the frozen legacy plan/apply path at the same maximum batch size, including
        // its original mutation-only commitment and receipt bytes. Do not normalize or omit either receipt.
        var legacyPlanState = new State();
        var legacyStandaloneState = new State();
        map.apply(block(1, List.of()), legacyPlanState, AppEffectEmitter.rejecting("no effects"));
        map.apply(block(1, List.of()), legacyStandaloneState, AppEffectEmitter.rejecting("no effects"));
        var legacyFacts = map.commandFacts(2, SENDER, legacy, List.of(), legacyPlanState);
        var legacyDecision = map.decideCommand(2, SENDER, CONTEXT.messageId(), legacy,
                AuthenticatedMapContract.batchCommitment(legacy), Set.of(), List.of(), legacyFacts);
        assertThat(legacyPlanState.values).hasSize(1);
        TransitionPlans.commit(legacyDecision.plan(), legacyPlanState, AppEffectEmitter.rejecting("no effects"));
        var legacyMessage = AppMessage.builder().chainId(CHAIN).messageId(CONTEXT.messageId()).sender(SENDER)
                .senderSeq(1).expiresAt(0).topic(AuthenticatedMapContract.DEFAULT_TOPIC)
                .body(AuthenticatedMapContract.encodeCommand(legacy)).authScheme(0).authProof(new byte[0]).build();
        map.apply(block(2, List.of(legacyMessage)), legacyStandaloneState, AppEffectEmitter.rejecting("no effects"));
        assertThat(legacyPlanState.values).hasSize(130);
        assertThat(legacyPlanState.values.keySet())
                .containsExactlyInAnyOrderElementsOf(legacyStandaloneState.values.keySet());
        legacyStandaloneState.values.forEach((key, value) ->
                assertThat(legacyPlanState.values.get(key)).containsExactly(value));
    }

    private static AppBlockExecutionContext block(long height, List<AppMessage> messages) {
        return AppBlockExecutionContext.fromValidatedBlock(new AppBlock(AppBlock.BLOCK_VERSION, CHAIN, height,
                new byte[32], 0, new byte[0], 1000, new byte[32], new byte[32], messages,
                SENDER, FinalityCert.empty()));
    }

    @Test
    void scalarBridgePreservesOwnerChecksAndProducesExistingEntryBytes() {
        var map = map(AuthenticatedMapContract.AUTH_OWNER, null);
        var kernel = new AuthenticatedMapTransitionKernel(map, "", "");
        var action = MapActionV1.basic(AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put("records", bytes("key"), bytes("value"))),
                List.of(AuthenticatedMapContract.AUTH_OWNER));
        var command = new AuthenticatedMapTransitionKernel.Command(action, "");
        byte[] encoded = kernel.codec().encode(command);
        assertThat(kernel.codec().encode(kernel.codec().decode(encoded))).isEqualTo(encoded);
        assertThat(kernel.admit(command, CONTEXT).isAccepted()).isTrue();
        var state = new State();
        var result = (TransitionDecision.Approved) kernel.decide(command, CONTEXT,
                kernel.facts(command, CONTEXT, state));
        assertThat(state.values).isEmpty();
        assertThat(result.plan().events()).extracting(event -> event.eventId())
                .containsExactly("authenticated-map.entry-updated.v1", "authenticated-map.batch-applied.v1");
        TransitionPlans.commit(result.plan(), state, AppEffectEmitter.rejecting("no effects"));
        assertThat(state.get(AuthenticatedMapContract.canonicalKey("records", bytes("key"))).orElseThrow())
                .isEqualTo(AuthenticatedMapContract.encodeEntry(AuthenticatedMapContract.Entry.active(
                        1, SENDER, bytes("value"), 2, 2)));
        byte[] otherSender = new byte[32];
        otherSender[0] = 1;
        byte[] otherMessage = new byte[32];
        otherMessage[0] = 1;
        var unauthorized = new TransitionContext(3, 1001, 0, otherMessage, "registry", otherSender);
        assertThat(kernel.decide(command, unauthorized, kernel.facts(command, unauthorized, state)))
                .isInstanceOf(TransitionDecision.Rejected.class);
    }

    @Test
    void malformedBridgeAndUnmappedActorEvidenceAreRejected() {
        var kernel = new AuthenticatedMapTransitionKernel(map(AuthenticatedMapContract.AUTH_OWNER, null), "", "");
        assertThatThrownBy(() -> kernel.codec().decode(TransitionScalars.encode(Map.of(
                "action", new byte[]{1}, "unexpected", true))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kernel.codec().decode(TransitionScalars.encode(Map.of(
                "action", new byte[]{1}, "approvalReference", 1L))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(kernel.commands().getFirst().fields().get(1).role())
                .isEqualTo(CommandDescriptor.Role.EVIDENCE);
    }

    @Test
    void logicalLookupUsesCanonicalCollectionKeyAndCannotReachOtherStateNamespaces() {
        var kernel = new AuthenticatedMapTransitionKernel(map(AuthenticatedMapContract.AUTH_OWNER, null), "", "");
        assertThat(kernel.lookupKey(BindingCbor.encode(List.of("records", bytes("key")))))
                .isEqualTo(AuthenticatedMapContract.canonicalKey("records", bytes("key")));
        assertThatThrownBy(() -> kernel.lookupKey(BindingCbor.encode(List.of("unknown", bytes("key")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kernel.lookupKey(AuthenticatedMapContract.genesisMarkerKey()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kernel.lookupKey(new byte[513])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvedProposalProducesOneUseMapOwnedConsumptionAndRejectsDifferentAction() {
        Fixture fixture = governed();
        var command = new AuthenticatedMapTransitionKernel.Command(fixture.action, "proposal-1");
        var states = Map.<String, AppStateReader>of(
                "actors", new State(), "reviews", fixture.approvals);
        var mapState = new State();
        var facts = fixture.kernel.facts(command, CONTEXT, mapState, states);
        var decision = (TransitionDecision.Approved) fixture.kernel.decide(command, CONTEXT, facts);
        assertThat(decision.plan().consumptions()).hasSize(1);
        assertThat(decision.plan().consumptions().getFirst().key())
                .isEqualTo(AuthenticatedMapContract.approvalConsumptionKey("proposal-1"));
        assertThat(mapState.values).isEmpty();
        TransitionPlans.commit(decision.plan(), mapState, AppEffectEmitter.rejecting("no effects"));
        byte[] secondId = new byte[32];
        secondId[0] = 2;
        var second = new TransitionContext(2, 1000, 1, secondId, "registry", SENDER);
        assertThat(fixture.kernel.decide(command, second,
                fixture.kernel.facts(command, second, mapState, states)))
                .isInstanceOf(TransitionDecision.Rejected.class);

        var changed = new MapActionV1(false, List.of(AuthenticatedMapContract.Mutation.put(
                "records", bytes("key"), bytes("different"))), fixture.action.authorizations());
        var altered = new AuthenticatedMapTransitionKernel.Command(changed, "proposal-1");
        assertThat(fixture.kernel.decide(altered, CONTEXT,
                fixture.kernel.facts(altered, CONTEXT, new State(), states)))
                .isInstanceOf(TransitionDecision.Rejected.class);
    }

    @Test
    void corruptApprovalStatePropagatesInsteadOfBecomingABusinessRejection() {
        Fixture fixture = governed();
        fixture.approvals.put(RoleWorkflowKeys.proposal("proposal-1"), new byte[]{1});
        var command = new AuthenticatedMapTransitionKernel.Command(fixture.action, "proposal-1");
        assertThatThrownBy(() -> fixture.kernel.facts(command, CONTEXT, new State(), Map.of(
                "actors", new State(), "reviews", fixture.approvals)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void opaqueFinalEnvelopeRequestsActorOwnedSignatureWorkAndPreservesBytes() {
        Fixture fixture = governed();
        var action = new MapActionV1(false, fixture.action.mutations(), List.of(new AuthorizationAssignmentV1(
                0, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, "review", 1)));
        var authorization = new AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1(
                new byte[32], CHAIN, new byte[32],
                AuthenticatedMapAuthorizationContract.actionCommitment(action), List.of(0), "review", 1,
                "actor", 1, "actor-key", new byte[32], 1, 100,
                AuthenticatedMapAuthorizationContract.SIGNATURE_ED25519, new byte[64]);
        byte[] original = AuthenticatedMapAuthorizationContract.encodeCommand(
                new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(action, List.of(authorization)));
        byte[] mapped = TransitionScalars.encode(Map.of("command", original));
        var decoded = fixture.kernel.codec().decode(mapped);
        assertThat(fixture.kernel.codec().encode(decoded)).isEqualTo(mapped);
        var request = fixture.kernel.workRequest(decoded, CONTEXT).orElseThrow();
        assertThat(request.units()).isEqualTo(1);
        assertThat(request.reference().participantId()).isEqualTo("actors");
        assertThat(request.reference().budgetId()).isEqualTo("governed-crypto-v1");
        assertThat(fixture.kernel.workRequest(
                new AuthenticatedMapTransitionKernel.Command(fixture.action, "proposal-1"), CONTEXT)).isEmpty();
    }

    private static Fixture governed() {
        byte[] seed = new byte[32];
        seed[0] = 42;
        var key = new ActorKeyEpochV1("actor-key", KeyGenUtil.getPublicKeyFromPrivateKey(seed),
                1, 0, RecordStatus.ACTIVE);
        var actor = new ActorRecordV1("actor", "org", 1, RecordStatus.ACTIVE,
                List.of("reviewer"), List.of(key), new byte[0]);
        var policy = new ApprovalPolicyV1("review", 1, RecordStatus.ACTIVE, List.of("reviewer"),
                List.of(new ApprovalPolicyV1.RequiredClause("review", "reviewer", 1,
                        ApprovalPolicyV1.DistinctBy.ACTOR)), ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 100);
        var genesis = new GovernedGenesisV1(CHAIN,
                new AdministratorAuthorityV1("admin", 1, List.of("actor"), 1, 100),
                List.of(new OrganizationRecordV1("org", 1, RecordStatus.ACTIVE, new byte[0])),
                List.of(new GenesisActorV1(actor, List.of(ActorKeyProofV1.sign(CHAIN, "actor", 1, key, seed)))),
                List.of(), List.of(policy), GovernedAuthorizationLimitsV1.defaults());
        var map = map(AuthenticatedMapContract.AUTH_APPROVAL, genesis);
        var action = new MapActionV1(false, List.of(AuthenticatedMapContract.Mutation.put(
                "records", bytes("key"), bytes("value"))), List.of(new AuthorizationAssignmentV1(
                0, AuthenticatedMapContract.AUTH_APPROVAL, "review", 1)));
        var proposal = new ApprovalProposalV1("proposal-1", "review", 1, policy.digest(),
                AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN,
                AuthenticatedMapAuthorizationContract.approvalPayloadHash(map.genesisId(),
                        AuthenticatedMapAuthorizationContract.actionCommitment(action)), 100,
                ApprovalProposalV1.ProposalStatus.APPROVED, "actor", "org", 1, "reviewer", 1,
                "actor-key", 1, List.of());
        var approvals = new State();
        approvals.put(RoleWorkflowKeys.proposal("proposal-1"), proposal.encode());
        approvals.put(RoleWorkflowKeys.policyRevision("review", 1), policy.encode());
        return new Fixture(new AuthenticatedMapTransitionKernel(map, "actors", "reviews"), action, approvals);
    }

    private record Fixture(AuthenticatedMapTransitionKernel kernel, MapActionV1 action, State approvals) { }

    private static AuthenticatedMapStateMachine map(int authorization, GovernedGenesisV1 governance) {
        return map(authorization, governance, 16);
    }

    private static AuthenticatedMapStateMachine map(int authorization, GovernedGenesisV1 governance, int batchItems) {
        var collection = new AuthenticatedMapContract.CollectionDescriptor("records", authorization,
                governance == null ? "" : "review", true, 64, 1024,
                AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, "");
        var genesis = new AuthenticatedMapContract.Genesis(CHAIN, StateCommitmentProfiles.MPF_BLAKE2B256_V1,
                StateCommitmentProfiles.MPF.formatFingerprint(), new byte[32], new byte[32], new byte[32],
                batchItems, 32768, List.of(collection), List.of(), List.of(), governance);
        return new AuthenticatedMapStateMachine(genesis);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static final class State implements AppStateWriter {
        private final Map<String, byte[]> values = new HashMap<>();
        @Override public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(values.get(HexFormat.of().formatHex(key))).map(byte[]::clone);
        }
        @Override public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(HexFormat.of().formatHex(key)); }
        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public long committedHeight() { return 1; }
    }
}
