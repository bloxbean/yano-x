package org.yanoproject.x.stdlib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.runtime.appchain.AppChainSubsystem;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;
import org.yanoproject.x.dpp.profile.DppStarterProfile;
import org.yanoproject.x.dpp.profile.DppValues;
import org.yanoproject.x.feed.profile.FeedStarterProfile;
import org.yanoproject.x.feed.profile.FeedValues;
import org.yanoproject.x.roles.contracts.ActorStatementV1;
import org.yanoproject.x.roles.contracts.ApprovalProposalV1;
import org.yanoproject.x.roles.contracts.RoleWorkflowKeys;
import org.yanoproject.x.roles.contracts.SignedActorCommandV1;
import org.yanoproject.x.roles.contracts.StagedActorCommandV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.x.stdlib.DeclarativeBindingsClusterTest.awaitReceipt;
import static org.yanoproject.x.stdlib.DeclarativeBindingsClusterTest.assertConvergence;
import static org.yanoproject.x.stdlib.DeclarativeBindingsClusterTest.verifyCertifiedProof;

/**
 * Product qualification with existing DPP/feed schemas and policies, not similarly named toy records.
 * This proves configuration-only approval-to-map execution and known-key certified proofs. It deliberately
 * makes no assertion about envelope-replay portal projections or the legacy signed AttestCertificate wire.
 */
@Timeout(120)
class BindingProductQualificationTest {
    @Test
    void dppCertificateRequiresIndependentOrganizationsAndMatchesExistingValueContract(@TempDir Path directory)
            throws Exception {
        qualify(BindingProductFixtures.fixture(true), directory);
    }

    /**
     * ADR-031.3 §6.3: with operator-for-direct-writes on the registry, the approval-referenced certificate
     * write still commits. It arrives through apply-approved with one verified approval reference and no direct
     * actor; a second rule pins exactly those facts ({@code directActorCount == 0}, {@code approvalCount == 1}).
     */
    @Test
    void dppCertificateReachesARoleGatedRegistryThroughItsApproval(@TempDir Path directory) throws Exception {
        var fixture = BindingProductFixtures.fixture(true);
        var operator = rule("operator-for-direct-writes", "ROLE_REQUIRED", new Call("or", List.of(
                new Call("eq", List.of(fact("directActorCount"), new Literal(0L))),
                new Call("in", List.of(new Field(Scope.PARAMS, "role"), fact("roles"))))),
                new BindingIrV1.Parameter("role", BindingIrV1.ParameterType.TEXT, null));
        var approvalOnly = rule("one-approval-reference", "NOT_APPROVAL_REFERENCED", new Call("and", List.of(
                new Call("eq", List.of(fact("directActorCount"), new Literal(0L))),
                new Call("eq", List.of(fact("approvalCount"), new Literal(1L))))));
        List<BindingIrV1.Component> components = new ArrayList<>();
        for (var component : fixture.ir().components()) {
            components.add(!component.id().equals("registry") ? component : new BindingIrV1.Component(
                    component.id(), component.machineId(), component.ingressTopic(), component.configuration(),
                    component.maxEffectsPerBlock(), component.fromHeight(), List.of(
                    new BindingIrV1.RuleAttachment(operator.id(), Map.of("role",
                            new BindingSourceV1.Literal(DppStarterProfile.OPERATOR_ROLE))),
                    new BindingIrV1.RuleAttachment(approvalOnly.id(), Map.of()))));
        }
        var gated = new BindingProductFixtures.Fixture(fixture.name(), fixture.genesis(),
                new BindingIrV1(components, List.of(approvalOnly, operator), fixture.ir().bindings(),
                        fixture.ir().limits(), fixture.ir().workflowFromHeight()), fixture.collection(),
                fixture.key(), fixture.value(), fixture.policy(), fixture.clause(), fixture.proposer(),
                fixture.firstVoter(), fixture.sameOrganizationVoter(), fixture.independentVoter(),
                fixture.actorSeed());
        var approval = qualify(gated, directory);
        assertThat(approval.steps().getLast().targetComponentId()).isEqualTo("registry");
        assertThat(approval.steps().getLast().rules()).isEqualTo(new BindingReceiptV1.RuleTrace(2, null));
    }

    private static BindingIrV1.AdmissionRule rule(String id, String deny, BindingExpressionV1.Node condition,
                                                  BindingIrV1.Parameter... parameters) {
        return new BindingIrV1.AdmissionRule(id, deny, null, List.of(parameters), List.of(
                new BindingIrV1.ExpressionClause(new BindingExpressionV1(BindingExpressionV1.Type.BOOLEAN,
                        condition))));
    }

    private static Field fact(String name) { return new Field(Scope.FACTS, name); }

    @Test
    void feedRoundRequiresIndependentOrganizationsAndMatchesExistingValueContract(@TempDir Path directory)
            throws Exception {
        qualify(BindingProductFixtures.fixture(false), directory);
    }

    @Test
    void checkedInProductRecipesContainExactReproducibleGenesisWithoutPlaceholders() throws Exception {
        for (boolean dpp : new boolean[]{true, false}) {
            var fixture = BindingProductFixtures.fixture(dpp);
            Path example = Path.of("..", "..", "examples", "bindings", fixture.name() + "-approval.yaml");
            assertThat(Files.readString(example).stripTrailing())
                    .isEqualTo(BindingProductFixtures.yaml(fixture).stripTrailing());
            assertThat(fixture.ir().encode()).hasSizeLessThanOrEqualTo(65536);
            assertThat(dpp ? DppStarterProfile.matches(fixture.genesis())
                    : FeedStarterProfile.matches(fixture.genesis())).isTrue();
        }
    }

    /** Runs the recipe on three members and returns the independent approval's receipt. */
    private static BindingReceiptV1 qualify(BindingProductFixtures.Fixture fixture, Path directory)
            throws Exception {
        var ports = DeclarativeBindingsClusterTest.ports(3);
        var members = new LinkedHashSet<>(BindingProductFixtures.members());
        var action = new AuthenticatedMapAuthorizationContract.MapActionV1(false,
                List.of(AuthenticatedMapContract.Mutation.put(fixture.collection(), fixture.key(), fixture.value())),
                List.of(new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                        0, AuthenticatedMapContract.AUTH_APPROVAL, fixture.policy(), 1)));
        byte[] actionBytes = AuthenticatedMapAuthorizationContract.encodeAction(action);
        byte[] payloadHash = AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                AuthenticatedMapContract.genesisId(fixture.genesis()),
                AuthenticatedMapAuthorizationContract.actionCommitment(action));
        byte[] mapKey = CompositeStateKeys.componentKey("registry",
                AuthenticatedMapContract.canonicalKey(fixture.collection(), fixture.key()));
        byte[] consumptionKey = CompositeStateKeys.componentKey("registry",
                AuthenticatedMapContract.approvalConsumptionKey("qualification"));
        try (var cluster = new DeclarativeBindingsClusterTest.Cluster(
                BindingProductFixtures.configurations(fixture, ports), ports, directory)) {
            var ingress = cluster.node(0);
            String propose = ingress.submit("reviews.v1", command(fixture, fixture.proposer(),
                    ActorStatementV1.Action.PROPOSE, payloadHash, actionBytes));
            awaitReceipt(cluster, propose);
            assertThat(receipt(ingress, propose).accepted()).isTrue();

            String first = ingress.submit("reviews.v1", command(fixture, fixture.firstVoter(),
                    ActorStatementV1.Action.APPROVE, payloadHash, new byte[0]));
            awaitReceipt(cluster, first);
            assertThat(receipt(ingress, first).accepted()).isTrue();
            assertPendingWithoutMap(cluster, mapKey, consumptionKey, 1);

            String same = ingress.submit("reviews.v1", command(fixture, fixture.sameOrganizationVoter(),
                    ActorStatementV1.Action.APPROVE, payloadHash, new byte[0]));
            awaitReceipt(cluster, same);
            assertThat(receipt(ingress, same).accepted()).isFalse();
            assertThat(receipt(ingress, same).code()).isEqualTo("DISTINCTNESS_DUPLICATE");
            assertPendingWithoutMap(cluster, mapKey, consumptionKey, 1);

            String independent = ingress.submit("reviews.v1", command(fixture, fixture.independentVoter(),
                    ActorStatementV1.Action.APPROVE, payloadHash, new byte[0]));
            awaitReceipt(cluster, independent);
            for (var node : cluster.liveNodes()) {
                assertThat(receipt(node, independent).accepted()).isTrue();
                var proposal = proposal(node);
                assertThat(proposal.status()).isEqualTo(ApprovalProposalV1.ProposalStatus.APPROVED);
                assertThat(proposal.decisions()).hasSize(2);
                assertThat(proposal.decisions()).extracting(decision -> decision.organizationId())
                        .doesNotHaveDuplicates();
                var entry = node.stateValue(mapKey).map(AuthenticatedMapContract::decodeEntry).orElseThrow();
                assertThat(entry.value()).isEqualTo(fixture.value());
                assertThat(entry.revision()).isEqualTo(1);
                if (fixture.name().equals("dpp")) {
                    assertThat(DppValues.CertificateValue.decode(entry.value()).productId()).isEqualTo("product-1");
                } else {
                    var round = FeedValues.RoundValue.decode(entry.value());
                    assertThat(round.status()).isEqualTo(FeedStarterProfile.ROUND_NO_QUORUM);
                    assertThat(round.acceptedSources()).isEmpty();
                }
                assertThat(node.stateValue(consumptionKey)).isPresent();
                assertThat(node.stateValue(CompositeStateKeys.componentKey("reviews",
                        StagedActorCommandV1.stateKey("qualification")))).isEmpty();
                verifyCertifiedProof(node, mapKey, members, fixture.chain());
                verifyCertifiedProof(node, consumptionKey, members, fixture.chain());
            }
            assertConvergence(cluster, members);
            return receipt(ingress, independent);
        }
    }

    private static void assertPendingWithoutMap(DeclarativeBindingsClusterTest.Cluster cluster,
                                                byte[] mapKey, byte[] consumptionKey, int decisions) {
        for (var node : cluster.liveNodes()) {
            assertThat(proposal(node).status()).isEqualTo(ApprovalProposalV1.ProposalStatus.PENDING);
            assertThat(proposal(node).decisions()).hasSize(decisions);
            assertThat(node.stateValue(mapKey)).isEmpty();
            assertThat(node.stateValue(consumptionKey)).isEmpty();
            assertThat(node.stateValue(CompositeStateKeys.componentKey("reviews",
                    StagedActorCommandV1.stateKey("qualification")))).isPresent();
        }
    }

    private static byte[] command(BindingProductFixtures.Fixture fixture, String actorId,
                                   ActorStatementV1.Action action, byte[] hash, byte[] staged) {
        var statement = new ActorStatementV1(action, fixture.chain(), "qualification", fixture.policy(), 1,
                AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN, hash, 500, actorId, 1,
                actorId + "-k1", action == ActorStatementV1.Action.APPROVE ? fixture.clause() : "");
        return new StagedActorCommandV1(SignedActorCommandV1.sign(statement, fixture.actorSeed().apply(actorId)),
                staged).encode();
    }

    private static ApprovalProposalV1 proposal(AppChainSubsystem node) {
        return node.stateValue(CompositeStateKeys.componentKey("reviews", RoleWorkflowKeys.proposal("qualification")))
                .map(ApprovalProposalV1::decode).orElseThrow();
    }

    private static BindingReceiptV1 receipt(AppChainSubsystem node, String id) {
        return BindingReceiptV1.decode(node.query("composite/binding-receipt-v1/" + id, new byte[0]).payload());
    }
}
