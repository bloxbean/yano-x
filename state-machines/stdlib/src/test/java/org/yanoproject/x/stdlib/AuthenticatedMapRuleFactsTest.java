package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppChainMembershipEpoch;
import org.yanoproject.api.appchain.AppChainMembershipView;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.FinalityCert;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.api.appchain.transition.TransitionWorkAccounting;
import org.yanoproject.x.dpp.profile.DppGenesis;
import org.yanoproject.x.dpp.profile.DppStarterProfile;
import org.yanoproject.x.dpp.profile.DppValues;
import org.yanoproject.x.roles.DeclarativeRoleProviders;
import org.yanoproject.x.roles.contracts.ActorKeyEpochV1;
import org.yanoproject.x.roles.contracts.ActorRecordV1;
import org.yanoproject.x.roles.contracts.OrganizationRecordV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.roles.contracts.RoleWorkflowKeys;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.MapActionV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * ADR-031.3 §5.5 facts of the authenticated map, from the DPP demo trust registry: every value comes from the
 * facts that produced the approval, so it was verified by the map's own direct-role authorization.
 */
class AuthenticatedMapRuleFactsTest {
    private static final AppEffectEmitter NO_EFFECTS = AppEffectEmitter.rejecting("no effects");

    @Test
    void governedMapsDeclareMembershipCollectionsCountsAndSingleActorFacts() {
        var fixture = new Fixture();
        assertThat(fixture.kernel.ruleFacts()).extracting(RuleFact::name, RuleFact::type).containsExactly(
                tuple("senderMember", RuleFact.Type.BOOLEAN), tuple("collections", RuleFact.Type.TEXT_SET),
                tuple("directActorCount", RuleFact.Type.INTEGER), tuple("approvalCount", RuleFact.Type.INTEGER),
                tuple("actorId", RuleFact.Type.TEXT), tuple("organizationId", RuleFact.Type.TEXT),
                tuple("role", RuleFact.Type.TEXT), tuple("roles", RuleFact.Type.TEXT_SET),
                tuple("policyId", RuleFact.Type.TEXT));
        var open = new AuthenticatedMapContract.CollectionDescriptor("records", AuthenticatedMapContract.AUTH_OPEN,
                "", true, 64, 1024, AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, "");
        var ungoverned = new AuthenticatedMapTransitionKernel(new AuthenticatedMapStateMachine(
                new AuthenticatedMapContract.Genesis("chain", StateCommitmentProfiles.MPF_BLAKE2B256_V1,
                        StateCommitmentProfiles.MPF.formatFingerprint(), new byte[32], new byte[32], new byte[32], 16,
                        32768, List.of(open), List.of(), List.of(), null)), "", "");
        assertThat(ungoverned.ruleFacts()).extracting(RuleFact::name).containsExactly("senderMember",
                "collections");
    }

    @Test
    void anUngovernedMapReportsMembershipAsSampledAndItsCollections() {
        var open = new AuthenticatedMapContract.CollectionDescriptor("records", AuthenticatedMapContract.AUTH_OPEN,
                "", true, 64, 1024, AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, "");
        var genesis = new AuthenticatedMapContract.Genesis("chain", StateCommitmentProfiles.MPF_BLAKE2B256_V1,
                StateCommitmentProfiles.MPF.formatFingerprint(), new byte[32], new byte[32], new byte[32], 16, 32768,
                List.of(open), List.of(), List.of(), null);
        AppChainMembershipView members = height -> new AppChainMembershipEpoch(0,
                List.of(HexFormat.of().formatHex(member())), 1);
        var kernel = new AuthenticatedMapTransitionKernel(new AuthenticatedMapStateMachine(genesis, members), "", "");
        byte[] body = TransitionScalars.encode(Map.of("action", AuthenticatedMapAuthorizationContract.encodeAction(
                new MapActionV1(false, List.of(AuthenticatedMapContract.Mutation.put("records", new byte[]{1},
                        new byte[]{2})), List.of(new AuthorizationAssignmentV1(0, AuthenticatedMapContract.AUTH_OPEN,
                        "", AuthenticatedMapAuthorizationContract.NO_EVIDENCE_HANDLE))))));
        for (byte[] sender : List.of(member(), KeyGenUtil.getPublicKeyFromPrivateKey(repeated(0x62)))) {
            var context = new TransitionContext(2, 0, 0, Fixture.MESSAGE_ID, "records.v1", sender);
            var command = kernel.codec().decode(body);
            var facts = kernel.facts(command, context, new MemoryState(), Map.of());
            assertThat(kernel.decide(command, context, facts)).isInstanceOf(TransitionDecision.Approved.class);
            assertThat(kernel.ruleFactValues(command, context, facts)).isEqualTo(Map.of(
                    "senderMember", Arrays.equals(sender, member()), "collections", List.of("records")));
        }
    }

    @Test
    void aSingleDirectActorExposesItsVerifiedRecords() {
        var fixture = new Fixture();
        var decision = fixture.decide(fixture.command(List.of(fixture.claim("claim-1", "issuer-a"))));
        assertThat(decision.decision()).isInstanceOf(TransitionDecision.Approved.class);
        assertThat(decision.values()).isEqualTo(Map.of("senderMember", true, "collections", List.of("claims"),
                "directActorCount", 1L, "approvalCount", 0L, "actorId", "issuer-a", "organizationId", "green-labs",
                "role", DppStarterProfile.CLAIM_ISSUER_ROLE, "roles", List.of(DppStarterProfile.CLAIM_ISSUER_ROLE),
                "policyId", DppStarterProfile.CLAIM_ISSUER_POLICY));
        // maker-a holds two roles; the set is sorted by unsigned UTF-8 bytes.
        var maker = fixture.decide(fixture.command(List.of(fixture.event("event-1", "maker-a"))));
        assertThat(maker.values()).containsEntry("roles", List.of(DppStarterProfile.MANUFACTURER_ROLE,
                DppStarterProfile.OPERATOR_ROLE)).containsEntry("role", DppStarterProfile.OPERATOR_ROLE);
    }

    @Test
    void aMultiActorBatchExposesOnlyCountsAndItsCollections() {
        var fixture = new Fixture();
        var decision = fixture.decide(fixture.command(List.of(fixture.event("event-1", "logistics-a"),
                fixture.claim("claim-1", "issuer-a"))));
        assertThat(decision.decision()).isInstanceOf(TransitionDecision.Approved.class);
        assertThat(decision.values()).isEqualTo(Map.of("senderMember", true, "collections",
                List.of("claims", "events"), "directActorCount", 2L, "approvalCount", 0L));
        // Two writes to one collection name it once, as a text set must.
        var twice = fixture.decide(fixture.command(List.of(fixture.claim("claim-1", "issuer-a"),
                fixture.claim("claim-2", "issuer-a"))));
        assertThat(twice.values()).containsEntry("collections", List.of("claims"))
                .containsEntry("directActorCount", 2L);
    }

    @Test
    void unverifiableActorsAreRejectedBeforeAnyFactExists() {
        var fixture = new Fixture();
        var write = fixture.claim("claim-1", "issuer-a");
        var forged = fixture.forged(write);
        assertThat(fixture.decide(fixture.command(List.of(forged))).decision())
                .isInstanceOfSatisfying(TransitionDecision.Rejected.class, rejected ->
                        assertThat(rejected.rejection().code()).isEqualTo("MAP_" + AuthenticatedMapContract
                                .ERROR_ACTOR_SIGNATURE));
        Map<String, Consumer<Fixture>> tampered = new LinkedHashMap<>();
        tampered.put("revoked key", each -> each.rewriteActor("issuer-a", RecordStatus.ACTIVE, RecordStatus.REVOKED,
                "green-labs"));
        tampered.put("inactive actor", each -> each.rewriteActor("issuer-a", RecordStatus.SUSPENDED,
                RecordStatus.ACTIVE, "green-labs"));
        tampered.put("unknown organization", each -> each.rewriteActor("issuer-a", RecordStatus.ACTIVE,
                RecordStatus.ACTIVE, "no-such-org"));
        tampered.put("inactive organization", each -> each.actors.put(RoleWorkflowKeys.organizationRevision(
                "green-labs", 1), new OrganizationRecordV1("green-labs", 1, RecordStatus.SUSPENDED,
                new byte[0]).encode()));
        tampered.forEach((name, tamper) -> {
            var each = new Fixture();
            tamper.accept(each);
            assertThat(each.decide(each.command(List.of(each.claim("claim-1", "issuer-a")))).decision()).as(name)
                    .isInstanceOfSatisfying(TransitionDecision.Rejected.class, rejected ->
                            assertThat(rejected.rejection().code()).isEqualTo("MAP_"
                                    + AuthenticatedMapContract.ERROR_ACTOR_INELIGIBLE));
        });
    }

    @Test
    void aReceiptKeyReplayApprovesWithoutFactsSoFactRulesFailClosed() {
        var fixture = new Fixture();
        byte[] body = fixture.command(List.of(fixture.claim("claim-1", "issuer-a")));
        fixture.map.put(AuthenticatedMapContract.receiptKey(Fixture.MESSAGE_ID), new byte[]{1});
        var replay = fixture.decide(body);
        assertThat(replay.decision()).isInstanceOfSatisfying(TransitionDecision.Approved.class, approved ->
                assertThat(approved.plan().mutations()).isEmpty());
        assertThat(replay.values()).isEmpty();
    }

    /** DPP demo registry with the actor and approval components initialized from its governed genesis. */
    private static final class Fixture {
        static final byte[] MESSAGE_ID = repeated(0x44);
        final AuthenticatedMapContract.Genesis genesis = DppGenesis.genesis(DppGenesis.demo("rule-facts"),
                List.of(HexFormat.of().formatHex(member())), 1);
        final MemoryState actors = new MemoryState();
        final MemoryState approvals = new MemoryState();
        final MemoryState map = new MemoryState();
        final AppStateMachine actorMachine;
        final AuthenticatedMapTransitionKernel kernel;
        private int authorizations;

        Fixture() {
            String roleGenesis = HexFormat.of().formatHex(genesis.governedGenesis().encode());
            actorMachine = new DeclarativeRoleProviders.Actors().create(context(roleGenesis));
            actorMachine.apply(block(1), actors, NO_EFFECTS);
            new DeclarativeRoleProviders.Approvals().create(context(roleGenesis)).apply(block(1), approvals,
                    NO_EFFECTS);
            AppChainMembershipView members = height -> new AppChainMembershipEpoch(0,
                    List.of(HexFormat.of().formatHex(member())), 1);
            kernel = new AuthenticatedMapTransitionKernel(new AuthenticatedMapStateMachine(genesis, members),
                    "actors", "reviews");
        }

        /** One mutation with the direct authorization that covers it. */
        record Write(AuthenticatedMapContract.Mutation mutation, String policy, String actor) { }

        Write claim(String id, String actor) {
            byte[] value = new DppValues.ClaimValue(DppStarterProfile.VISIBILITY_PUBLIC,
                    "recycled".getBytes(StandardCharsets.UTF_8), "green-labs", 0, 0, new byte[0]).encode();
            return new Write(AuthenticatedMapContract.Mutation.put(DppStarterProfile.CLAIMS,
                    DppStarterProfile.claimKey("product-1", "material", id), value),
                    DppStarterProfile.CLAIM_ISSUER_POLICY, actor);
        }

        Write event(String id, String actor) {
            byte[] value = new DppValues.EventValue("shipped", "swift-logistics", 1_700_000_000L, "",
                    new byte[0], "").encode();
            return new Write(AuthenticatedMapContract.Mutation.put(DppStarterProfile.EVENTS,
                    DppStarterProfile.eventKey("product-1", id), value), DppStarterProfile.OPERATOR_POLICY, actor);
        }

        /** The same write whose authorization is signed by another actor's key. */
        Write forged(Write write) { return new Write(write.mutation(), write.policy(), "!" + write.actor()); }

        /** A signed {@code apply-authorized} body: one direct authorization per write, covering its index. */
        byte[] command(List<Write> writes) {
            List<AuthorizationAssignmentV1> assignments = new ArrayList<>();
            for (int index = 0; index < writes.size(); index++) {
                assignments.add(new AuthorizationAssignmentV1(index, AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                        writes.get(index).policy(), index + 1));
            }
            var action = new MapActionV1(writes.size() > 1, writes.stream().map(Write::mutation).toList(),
                    assignments);
            byte[] commitment = AuthenticatedMapAuthorizationContract.actionCommitment(action);
            List<AuthenticatedMapAuthorizationContract.AuthorizationEvidenceV1> evidence = new ArrayList<>();
            for (int index = 0; index < writes.size(); index++) {
                String actor = writes.get(index).actor();
                boolean forged = actor.startsWith("!");
                String id = forged ? actor.substring(1) : actor;
                byte[] seed = DppGenesis.demoActorSeed(forged ? "auditor-b" : id);
                evidence.add(MapActorAuthorizationV1.sign(repeated(++authorizations), genesis.chainId(),
                        AuthenticatedMapContract.genesisId(genesis), commitment, List.of(index),
                        writes.get(index).policy(), 1, id, 1, id + "-k1",
                        KeyGenUtil.getPublicKeyFromPrivateKey(DppGenesis.demoActorSeed(id)), 1, 20, seed));
            }
            return TransitionScalars.encode(Map.of("command", AuthenticatedMapAuthorizationContract.encodeCommand(
                    new AuthenticatedMapCommandV1(action, evidence))));
        }

        record Outcome(TransitionDecision decision, Map<String, Object> values) { }

        /** Decides at height 2 from a member sender; facts are read only after an approval, as the engine does. */
        Outcome decide(byte[] body) {
            return decide(kernel, body);
        }

        private <C, F> Outcome decide(TransitionKernel<C, F> transitions, byte[] body) {
            var context = new TransitionContext(2, 0, 0, MESSAGE_ID, "registry.v1", member());
            C command = transitions.codec().decode(body);
            transitions.workRequest(command, context).ifPresent(request -> {
                var budget = actorMachine.transitionKernel().orElseThrow().workBudgets().getFirst();
                assertThat(TransitionWorkAccounting.reserve(actors, budget, 2, request.units())).isTrue();
            });
            F facts = transitions.facts(command, context, map, Map.of("actors", actors, "reviews", approvals));
            TransitionDecision decision = transitions.decide(command, context, facts);
            return new Outcome(decision, decision instanceof TransitionDecision.Approved
                    ? transitions.ruleFactValues(command, context, facts) : null);
        }

        /** Replaces an actor's genesis revision, keeping its key material. */
        void rewriteActor(String actor, RecordStatus status, RecordStatus keyStatus, String organization) {
            var current = ActorRecordV1.decode(actors.get(RoleWorkflowKeys.actorRevision(actor, 1)).orElseThrow());
            var key = current.keys().getFirst();
            actors.put(RoleWorkflowKeys.actorRevision(actor, 1), new ActorRecordV1(actor, organization, 1, status,
                    current.roles(), List.of(new ActorKeyEpochV1(key.keyId(), key.publicKey(),
                    key.validFromHeight(), key.validUntilHeight(), keyStatus)), new byte[0]).encode());
        }

        private AppStateMachineContext context(String roleGenesis) {
            return new AppStateMachineContext() {
                @Override public String chainId() { return genesis.chainId(); }
                @Override public Map<String, String> settings() {
                    return Map.of("machines.domain-actors-component.genesis-cbor-hex", roleGenesis,
                            "machines.governed-role-approvals.genesis-cbor-hex", roleGenesis);
                }
                @Override public Optional<StateCommitmentIdentity> stateCommitmentIdentity() {
                    return Optional.of(StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF, repeated(9)));
                }
            };
        }

        private AppBlockExecutionContext block(long height) {
            return AppBlockExecutionContext.fromValidatedBlock(new AppBlock(1, genesis.chainId(), height,
                    new byte[32], 0, new byte[0], height, new byte[32], new byte[32], List.of(),
                    new byte[32], FinalityCert.empty()));
        }
    }

    private static byte[] member() { return KeyGenUtil.getPublicKeyFromPrivateKey(repeated(0x61)); }

    private static byte[] repeated(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
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
