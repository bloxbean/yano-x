package org.yanoproject.x.roles;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.FinalityCert;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionPlan;
import org.yanoproject.api.appchain.transition.TransitionPlans;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.api.appchain.transition.TransitionWorkAccounting;
import org.yanoproject.x.roles.contracts.ActorKeyEpochV1;
import org.yanoproject.x.roles.contracts.ActorKeyProofV1;
import org.yanoproject.x.roles.contracts.ActorRecordV1;
import org.yanoproject.x.roles.contracts.ActorStatementV1;
import org.yanoproject.x.roles.contracts.AdministratorAuthorityV1;
import org.yanoproject.x.roles.contracts.ApprovalPolicyV1;
import org.yanoproject.x.roles.contracts.ApprovalProposalV1;
import org.yanoproject.x.roles.contracts.GenesisActorV1;
import org.yanoproject.x.roles.contracts.GovernedAuthorizationLimitsV1;
import org.yanoproject.x.roles.contracts.GovernedGenesisV1;
import org.yanoproject.x.roles.contracts.OrganizationRecordV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.roles.contracts.RoleWorkflowKeys;
import org.yanoproject.x.roles.contracts.SignedActorCommandV1;
import org.yanoproject.x.roles.contracts.StagedActorCommandV1;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DeclarativeRoleProvidersTest {
    private static final String CHAIN = "declarative-roles";
    private static final byte[] ISSUER_SEED = repeated(1);
    private static final byte[] AUDITOR_SEED = repeated(2);
    private static final byte[] ACTION = {1, 2, 3};
    private static final AppEffectEmitter NO_EFFECTS = AppEffectEmitter.rejecting("no role effects");

    @Test
    void stagesExactProposalActionThenEmitsApprovalOnceAndDeletesStaging() {
        Fixture fixture = new Fixture();
        var propose = command(ActorStatementV1.Action.PROPOSE, "release", 20, ACTION);
        TransitionPlan proposed = fixture.apply(propose, 2);
        assertThat(proposed.events()).isEmpty();
        assertThat(fixture.approvals.get(StagedActorCommandV1.stateKey("release"))).contains(ACTION);
        assertThat(fixture.apply(propose, 3).mutations()).isEmpty();
        TransitionPlan approved = fixture.apply(command(ActorStatementV1.Action.APPROVE,
                "release", 20, new byte[0]), 4);
        assertThat(approved.events()).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(DeclarativeRoleProviders.APPROVED_EVENT);
            var payload = TransitionScalars.decode(event.payload());
            assertThat(payload).containsEntry("proposalId", "release").containsEntry("decisionCount", 1L);
            assertThat((byte[]) payload.get("action")).isEqualTo(ACTION);
        });
        assertThat(fixture.approvals.get(StagedActorCommandV1.stateKey("release"))).isEmpty();
        assertThat(fixture.proposal("release").status()).isEqualTo(ApprovalProposalV1.ProposalStatus.APPROVED);
        // The terminal proposal retains its signed hash, not the deleted transport action; it cannot restage.
        assertThat(fixture.decide(propose, 5)).isInstanceOfSatisfying(TransitionDecision.Rejected.class,
                rejected -> assertThat(rejected.rejection().code()).isEqualTo("CONFLICT"));
        assertThat(fixture.decide(command(ActorStatementV1.Action.APPROVE,
                "release", 20, new byte[0]), 5)).isInstanceOfSatisfying(TransitionDecision.Rejected.class,
                rejected -> assertThat(rejected.rejection().code()).isEqualTo("TERMINAL"));
    }

    @Test
    void changedActionReplayRejectsWithoutReplacingAuthorizedProposalBytes() {
        Fixture fixture = new Fixture();
        fixture.apply(command(ActorStatementV1.Action.PROPOSE, "release", 20, ACTION), 2);
        byte[] proposalBefore = fixture.approvals.get(RoleWorkflowKeys.proposal("release")).orElseThrow();
        assertThat(fixture.decide(command(ActorStatementV1.Action.PROPOSE,
                "release", 20, new byte[]{9}), 3)).isInstanceOfSatisfying(TransitionDecision.Rejected.class,
                rejected -> assertThat(rejected.rejection().code()).isEqualTo("CONFLICT"));
        assertThat(fixture.approvals.get(RoleWorkflowKeys.proposal("release"))).contains(proposalBefore);
        assertThat(fixture.approvals.get(StagedActorCommandV1.stateKey("release"))).contains(ACTION);
    }

    @Test
    void rejectionCancellationAndEmptyBlockExpiryReclaimStagedAction() {
        for (var terminal : List.of(ActorStatementV1.Action.REJECT, ActorStatementV1.Action.CANCEL)) {
            Fixture fixture = new Fixture();
            fixture.apply(command(ActorStatementV1.Action.PROPOSE, "terminal", 20, ACTION), 2);
            assertThat(fixture.apply(command(terminal, "terminal", 20, new byte[0]), 3).events()).isEmpty();
            assertThat(fixture.approvals.get(StagedActorCommandV1.stateKey("terminal"))).isEmpty();
        }
        Fixture expired = new Fixture();
        expired.apply(command(ActorStatementV1.Action.PROPOSE, "expired", 4, ACTION), 2);
        expired.approvalMachine.apply(block(5), expired.approvals, NO_EFFECTS);
        assertThat(expired.approvals.get(StagedActorCommandV1.stateKey("expired"))).isEmpty();
        assertThat(expired.proposal("expired").status()).isEqualTo(ApprovalProposalV1.ProposalStatus.EXPIRED);
    }

    @Test
    void actorLeafOwnsSharedBudgetAndFreshSignatureEvidenceRemainsExplicit() {
        Fixture fixture = new Fixture();
        var actorKernel = fixture.actorMachine.transitionKernel().orElseThrow();
        assertThat(actorKernel.workBudgets()).singleElement().satisfies(budget -> {
            assertThat(budget.id()).isEqualTo(GovernedCryptoWork.BUDGET_ID);
            assertThat(budget.key()).isEqualTo(RoleWorkflowKeys.cryptoWork());
        });
        assertThat(actorKernel.commands()).isEmpty();
        assertThat(actorDecision(actorKernel, fixture.actors))
                .isInstanceOfSatisfying(TransitionDecision.Rejected.class,
                        rejected -> assertThat(rejected.rejection().code())
                                .isEqualTo("ACTOR_GOVERNANCE_ROUTE_REQUIRED"));
        assertThat(fixture.approvalMachine.transitionKernel().orElseThrow().commands().getFirst().fields())
                .anySatisfy(field -> {
                    assertThat(field.name()).isEqualTo("signedCommand");
                    assertThat(field.role()).isEqualTo(CommandDescriptor.Role.EVIDENCE);
                });
        var invalid = command(ActorStatementV1.Action.PROPOSE, "invalid", 20, ACTION);
        invalid = new StagedActorCommandV1(
                SignedActorCommandV1.sign(invalid.command().statement(), AUDITOR_SEED), ACTION);
        assertThat(fixture.decide(invalid, 2)).isInstanceOfSatisfying(TransitionDecision.Rejected.class,
                rejected -> assertThat(rejected.rejection().code()).isEqualTo("INVALID_SIGNATURE"));
        assertThat(fixture.actors.get(RoleWorkflowKeys.cryptoWork())).isPresent();
        assertThat(fixture.approvals.get(RoleWorkflowKeys.proposal("invalid"))).isEmpty();
    }

    private static StagedActorCommandV1 command(ActorStatementV1.Action action, String id,
                                               long deadline, byte[] bytes) {
        boolean issuer = action == ActorStatementV1.Action.PROPOSE || action == ActorStatementV1.Action.CANCEL;
        String actor = issuer ? "issuer" : "auditor";
        var statement = new ActorStatementV1(action, CHAIN, id, "release-policy", 1,
                "test.action.v1", repeated(7), deadline, actor, 1, actor + "-key", issuer ? "" : "audit");
        return new StagedActorCommandV1(
                SignedActorCommandV1.sign(statement, issuer ? ISSUER_SEED : AUDITOR_SEED), bytes);
    }

    private static <C, F> TransitionDecision actorDecision(TransitionKernel<C, F> kernel, MemoryState state) {
        C command = kernel.codec().decode(new byte[]{1});
        var context = new TransitionContext(2, 0, 0, new byte[32], "actors", new byte[32]);
        return kernel.decide(command, context, kernel.facts(command, context, state));
    }

    private static GovernedGenesisV1 genesis() {
        var policy = new ApprovalPolicyV1("release-policy", 1, RecordStatus.ACTIVE, List.of("issuer"),
                List.of(new ApprovalPolicyV1.RequiredClause("audit", "auditor", 1,
                        ApprovalPolicyV1.DistinctBy.ACTOR)), ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 100);
        return new GovernedGenesisV1(CHAIN,
                new AdministratorAuthorityV1("admins", 1, List.of("issuer"), 1, 100),
                List.of(new OrganizationRecordV1("org-issuer", 1, RecordStatus.ACTIVE, new byte[0]),
                        new OrganizationRecordV1("org-auditor", 1, RecordStatus.ACTIVE, new byte[0])),
                List.of(actor("issuer", ISSUER_SEED), actor("auditor", AUDITOR_SEED)),
                List.of(), List.of(policy), GovernedAuthorizationLimitsV1.defaults());
    }

    private static GenesisActorV1 actor(String id, byte[] seed) {
        var key = new ActorKeyEpochV1(id + "-key", KeyGenUtil.getPublicKeyFromPrivateKey(seed),
                1, 0, RecordStatus.ACTIVE);
        var actor = new ActorRecordV1(id, "org-" + id, 1, RecordStatus.ACTIVE,
                List.of(id, "registry-admin"), List.of(key), new byte[0]);
        return new GenesisActorV1(actor, List.of(ActorKeyProofV1.sign(CHAIN, id, 1, key, seed)));
    }

    private static AppBlockExecutionContext block(long height) {
        return AppBlockExecutionContext.fromValidatedBlock(new AppBlock(1, CHAIN, height,
                new byte[32], 0, new byte[0], height, new byte[32], new byte[32], List.of(),
                new byte[32], FinalityCert.empty()));
    }

    private static byte[] repeated(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final class Fixture {
        final MemoryState actors = new MemoryState();
        final MemoryState approvals = new MemoryState();
        final AppStateMachine actorMachine = new DeclarativeRoleProviders.Actors().create(context());
        final AppStateMachine approvalMachine = new DeclarativeRoleProviders.Approvals().create(context());

        Fixture() {
            actorMachine.apply(block(1), actors, NO_EFFECTS);
            approvalMachine.apply(block(1), approvals, NO_EFFECTS);
        }

        TransitionDecision decide(StagedActorCommandV1 command, long height) {
            return evaluate(approvalMachine.transitionKernel().orElseThrow(), command.encode(), height);
        }

        private <C, F> TransitionDecision evaluate(TransitionKernel<C, F> kernel, byte[] bytes, long height) {
            var context = new TransitionContext(height, 0, 0, new byte[32], "reviews", new byte[32]);
            C command = kernel.codec().decode(bytes);
            kernel.workRequest(command, context).ifPresent(request -> {
                var budget = actorMachine.transitionKernel().orElseThrow().workBudgets().getFirst();
                assertThat(TransitionWorkAccounting.reserve(actors, budget, height, request.units())).isTrue();
            });
            return kernel.decide(command, context, kernel.facts(command, context, approvals, Map.of("actors", actors)));
        }

        TransitionPlan apply(StagedActorCommandV1 command, long height) {
            TransitionDecision decision = decide(command, height);
            assertThat(decision).isInstanceOf(TransitionDecision.Approved.class);
            TransitionPlan plan = ((TransitionDecision.Approved) decision).plan();
            TransitionPlans.commit(plan, approvals, NO_EFFECTS);
            return plan;
        }

        ApprovalProposalV1 proposal(String id) {
            return ApprovalProposalV1.decode(approvals.get(RoleWorkflowKeys.proposal(id)).orElseThrow());
        }

        private static AppStateMachineContext context() {
            String genesis = HexFormat.of().formatHex(genesis().encode());
            return new AppStateMachineContext() {
                @Override public String chainId() { return CHAIN; }
                @Override public Map<String, String> settings() {
                    return Map.of("machines.domain-actors-component.genesis-cbor-hex", genesis,
                            "machines.governed-role-approvals.genesis-cbor-hex", genesis);
                }
                @Override public Optional<StateCommitmentIdentity> stateCommitmentIdentity() {
                    return Optional.of(StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF, repeated(9)));
                }
            };
        }
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
