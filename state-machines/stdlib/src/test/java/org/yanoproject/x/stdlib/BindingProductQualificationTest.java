package org.yanoproject.x.stdlib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.runtime.appchain.AppChainSubsystem;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
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
import java.util.LinkedHashSet;
import java.util.List;

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

    private static void qualify(BindingProductFixtures.Fixture fixture, Path directory) throws Exception {
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
