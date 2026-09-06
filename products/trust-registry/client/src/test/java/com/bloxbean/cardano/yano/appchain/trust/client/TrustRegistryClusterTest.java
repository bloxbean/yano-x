package com.bloxbean.cardano.yano.appchain.trust.client;

import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.appchain.attest.client.AttestTrust;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorStatementV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.trust.profile.StatusBitstring;
import com.bloxbean.cardano.yano.appchain.trust.profile.StatusProjection;
import com.bloxbean.cardano.yano.appchain.trust.profile.TrustRegistryGenesis;
import com.bloxbean.cardano.yano.appchain.trust.profile.TrustRegistryProfile;
import com.bloxbean.cardano.yano.appchain.trust.profile.TrustRegistryValues;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end registry flow on a real three-member governed authenticated-map cluster:
 * governed-role writes, list publication, proof-bound answers in every trust mode, the
 * standards service, approval-routed issuer onboarding, and the committed goldens.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrustRegistryClusterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final String API_KEY = "trust-test-key";
    private static final String LIST = "list-1";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private RegistryTestCluster cluster;
    private GatewayHttpBridge bridge;
    private TrustRegistryClient client;
    private RegistryService service;
    private AttestTrust.CallerPinned pinned;
    private String genesisIdHex;
    private byte[] genesisId;
    private long revokedAtHeight;
    private long publishedHeight;
    private StatusAnswer statusAnswer;

    @BeforeAll
    void startCluster() throws Exception {
        cluster = RegistryTestCluster.start("trust-registry-golden", 3, 2);
        bridge = GatewayHttpBridge.start(cluster.node(2), API_KEY);
        client = TrustRegistryClient.builder(bridge.baseUrl(), cluster.chainId())
                .apiKey(API_KEY).build();
        genesisId = AuthenticatedMapContract.genesisId(cluster.genesis());
        genesisIdHex = HEX.formatHex(genesisId);
        TrustRegistryClient.Identity fresh = client.identity();
        assertThat(fresh.genesis()).as("the map genesis is not queryable before the first block")
                .isNull();
        assertThat(fresh.stateGenesisIdHex()).hasSize(64);
        assertThat(client.tipHeight()).isZero();
        pinned = new AttestTrust.CallerPinned(cluster.chainId(),
                Set.copyOf(cluster.memberKeysHex()), cluster.threshold(), null, null);

        // issuer-a sets two indexes, then revokes one of them; registrar-a registers a subject.
        // The first write is signed from the genesis descriptor: no block exists yet.
        write("issuer-a", TrustRegistryProfile.ISSUER_POLICY, AuthenticatedMapContract.Command.batch(
                List.of(AuthenticatedMapContract.Mutation.put(TrustRegistryProfile.STATUS,
                                TrustRegistryProfile.statusKey(LIST, 5),
                                new TrustRegistryValues.StatusValue(1, 3).encode()),
                        AuthenticatedMapContract.Mutation.put(TrustRegistryProfile.STATUS,
                                TrustRegistryProfile.statusKey(LIST, 8),
                                new TrustRegistryValues.StatusValue(0, 0).encode()))));
        cluster.awaitHeight(1);
        TrustRegistryClient.Identity identity = client.identity();
        assertThat(identity.registry()).isTrue();
        assertThat(identity.mapGenesisIdHex()).isEqualTo(genesisIdHex);
        assertThat(identity.stateGenesisIdHex())
                .as("state identity is application-profile-bound on a composite runtime")
                .isNotEqualTo(genesisIdHex);
        AuthenticatedMapContract.Receipt revoke = write("issuer-a",
                TrustRegistryProfile.ISSUER_POLICY, AuthenticatedMapContract.Command.single(
                        AuthenticatedMapContract.Mutation.revoke(TrustRegistryProfile.STATUS,
                                TrustRegistryProfile.statusKey(LIST, 8), 1, null)));
        revokedAtHeight = revoke.height();
        write("registrar-a", TrustRegistryProfile.REGISTRAR_POLICY,
                AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.put(
                        TrustRegistryProfile.SUBJECTS,
                        TrustRegistryProfile.subjectKey("did:example:subject-1"),
                        new TrustRegistryValues.SubjectValue(
                                "registry-operator", "product", new byte[32]).encode())));

        // publish-list: replay, hash, write the list entry.
        StatusProjection projection = new StatusProjection();
        publishedHeight = client.replay(projection, client.tipHeight());
        StatusBitstring bits = projection.bitstring(LIST, TrustRegistryProfile.MIN_BIT_LENGTH);
        assertThat(bits.get(5)).isTrue();
        assertThat(bits.get(8)).as("revoked index is terminal").isTrue();
        assertThat(bits.setCount()).isEqualTo(2);
        write("issuer-a", TrustRegistryProfile.ISSUER_POLICY, AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(TrustRegistryProfile.STATUS_LISTS,
                        TrustRegistryProfile.listKey(LIST),
                        new TrustRegistryValues.StatusListValue("revocation",
                                TrustRegistryProfile.MIN_BIT_LENGTH, bits.sha256(),
                                publishedHeight).encode())));
        statusAnswer = client.answer(TrustRegistryProfile.STATUS,
                TrustRegistryProfile.statusKey(LIST, 5), null);
        service = RegistryService.start(client, new InetSocketAddress("127.0.0.1", 0));
    }

    @AfterAll
    void stopCluster() {
        if (service != null) service.close();
        if (bridge != null) bridge.close();
        if (cluster != null) cluster.close();
    }

    @Test
    void governedWriteAnswersWithDirectRoleProvenance() {
        assertThat(statusAnswer.presence()).isEqualTo(StatusAnswer.Presence.ACTIVE);
        assertThat(statusAnswer.provenance().kind()).isEqualTo(StatusAnswer.ProvenanceKind.DIRECT_ROLE);
        assertThat(statusAnswer.provenance().actorId()).isEqualTo("issuer-a");
        assertThat(statusAnswer.provenance().organizationId()).isEqualTo("issuer-org-a");
        assertThat(statusAnswer.provenance().policyId()).isEqualTo(TrustRegistryProfile.ISSUER_POLICY);
        assertThat(statusAnswer.provenance().keyId()).isEqualTo("issuer-a-k1");
        assertThat(statusAnswer.provenance().role()).isEqualTo("issuer");
        assertThat(statusAnswer.facts()).extracting(StatusAnswer.Fact::name).containsExactly(
                "entry", "receipt", "direct-consumption", "direct-policy", "direct-policy-current",
                "actor", "actor-current", "organization", "genesis-marker");
        assertThat(statusAnswer.genesisIdHex()).isEqualTo(client.identity().stateGenesisIdHex());
        assertThat(HEX.formatHex(statusAnswer.fact("genesis-marker").expectedValue()))
                .isEqualTo(genesisIdHex);
        assertThat(TrustRegistryValues.StatusValue.decode(statusAnswer.entry().value()).bit())
                .isEqualTo(1);

        TrustRegistryVerifier.Verification verification =
                TrustRegistryVerifier.verify(statusAnswer, pinned);
        assertThat(verification.failures()).isEmpty();
        assertThat(verification.consistent()).isTrue();
        assertThat(verification.trustLevel()).isEqualTo(ProofLabVocabulary.TrustLevel.CALLER_PINNED_ROOT);

        TrustRegistryVerifier.Verification declared =
                TrustRegistryVerifier.verify(statusAnswer, AttestTrust.bundleDeclared());
        assertThat(declared.consistent()).isTrue();
        assertThat(declared.trustLevel())
                .isEqualTo(ProofLabVocabulary.TrustLevel.INTERNAL_CONSISTENCY_ONLY);

        StatusAnswer decoded = AnswerCodec.fromJson(AnswerCodec.toJson(statusAnswer));
        assertThat(TrustRegistryVerifier.verify(decoded, pinned).consistent()).isTrue();
    }

    @Test
    void wrongMembersAndTamperedEntriesFail() {
        AttestTrust.CallerPinned strangers = new AttestTrust.CallerPinned(cluster.chainId(),
                Set.of("aa".repeat(32), "bb".repeat(32)), 1, null, null);
        TrustRegistryVerifier.Verification wrongMembers =
                TrustRegistryVerifier.verify(statusAnswer, strangers);
        assertThat(wrongMembers.consistent()).isFalse();
        assertThat(wrongMembers.failures()).isNotEmpty();

        JsonNode node = AnswerCodec.toJsonNode(statusAnswer);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("provenance"))
                .put("actorId", "registrar-a");
        StatusAnswer relabelled = AnswerCodec.fromJsonNode(node);
        TrustRegistryVerifier.Verification relabelledResult =
                TrustRegistryVerifier.verify(relabelled, pinned);
        assertThat(relabelledResult.consistent())
                .as("provenance labels must match the proven consumption")
                .isFalse();
        assertThat(relabelledResult.failures()).anyMatch(failure -> failure.contains("labels"));

        byte[] forged = new TrustRegistryValues.StatusValue(0, 3).encode();
        StatusAnswer.Entry original = statusAnswer.entry();
        StatusAnswer.Entry tampered = new StatusAnswer.Entry(original.status(), original.revision(),
                original.controller(), forged, AuthenticatedMapContract.logicalValueHash(forged),
                original.createdHeight(), original.lastMutationHeight());
        StatusAnswer forgedAnswer = new StatusAnswer(statusAnswer.chainId(), statusAnswer.profile(),
                statusAnswer.genesisIdHex(), statusAnswer.height(), statusAnswer.stateRootHex(),
                statusAnswer.blockHashHex(), statusAnswer.collection(), statusAnswer.key(),
                statusAnswer.presence(), tampered, statusAnswer.provenance(),
                statusAnswer.actionCommitment(), statusAnswer.authorizationEvidence(),
                statusAnswer.facts().stream().map(fact -> fact.name().equals("entry")
                        ? new StatusAnswer.Fact("entry", fact.expectedKey(), tampered.encode(),
                        fact.proofJson()) : fact).toList(), statusAnswer.evidenceJson());
        assertThat(TrustRegistryVerifier.verify(forgedAnswer, pinned).consistent()).isFalse();
    }

    @Test
    void tombstoneExclusionGenesisAndHistoricalAnswers() {
        StatusAnswer revoked = client.answer(TrustRegistryProfile.STATUS,
                TrustRegistryProfile.statusKey(LIST, 8), null);
        assertThat(revoked.presence()).isEqualTo(StatusAnswer.Presence.REVOKED);
        assertThat(revoked.entry().revision()).isEqualTo(2);
        assertThat(revoked.provenance().kind()).isEqualTo(StatusAnswer.ProvenanceKind.DIRECT_ROLE);
        assertThat(TrustRegistryVerifier.verify(revoked, pinned).consistent()).isTrue();

        StatusAnswer before = client.answer(TrustRegistryProfile.STATUS,
                TrustRegistryProfile.statusKey(LIST, 8), revokedAtHeight - 1);
        assertThat(before.height()).isEqualTo(revokedAtHeight - 1);
        assertThat(before.presence()).isEqualTo(StatusAnswer.Presence.ACTIVE);
        assertThat(before.entry().revision()).isEqualTo(1);
        assertThat(TrustRegistryVerifier.verify(before, pinned).consistent()).isTrue();

        StatusAnswer absent = client.answer(TrustRegistryProfile.SUBJECTS,
                TrustRegistryProfile.subjectKey("did:example:nobody"), null);
        assertThat(absent.presence()).isEqualTo(StatusAnswer.Presence.ABSENT);
        assertThat(absent.provenance().kind()).isEqualTo(StatusAnswer.ProvenanceKind.NONE);
        assertThat(absent.facts()).hasSize(1);
        assertThat(TrustRegistryVerifier.verify(absent, pinned).consistent()).isTrue();

        StatusAnswer issuer = client.answer(TrustRegistryProfile.ISSUERS,
                TrustRegistryProfile.issuerKey("issuer-a"), null);
        assertThat(issuer.presence()).isEqualTo(StatusAnswer.Presence.ACTIVE);
        assertThat(issuer.provenance().kind()).isEqualTo(StatusAnswer.ProvenanceKind.GENESIS);
        assertThat(TrustRegistryValues.IssuerValue.decode(issuer.entry().value()).framework())
                .isEqualTo(TrustRegistryGenesis.DEMO_FRAMEWORK);
        assertThat(TrustRegistryVerifier.verify(issuer, pinned).consistent()).isTrue();

        StatusAnswer subject = client.answer(TrustRegistryProfile.SUBJECTS,
                TrustRegistryProfile.subjectKey("did:example:subject-1"), null);
        assertThat(subject.provenance().actorId()).isEqualTo("registrar-a");
        assertThat(subject.provenance().policyId()).isEqualTo(TrustRegistryProfile.REGISTRAR_POLICY);
        assertThat(TrustRegistryVerifier.verify(subject, pinned).consistent()).isTrue();
    }

    @Test
    void serviceServesTheListAndTrqpAnswers() throws Exception {
        JsonNode health = get("/healthz");
        assertThat(health.path("chainId").asText()).isEqualTo(cluster.chainId());

        String listJson = getText("/status-lists/" + LIST);
        JsonNode list = JSON.readTree(listJson);
        assertThat(list.path("credentialSubject").path("statusPurpose").asText()).isEqualTo("revocation");
        assertThat(list.path("credentialSubject").path("statusSize").asInt()).isEqualTo(1);
        assertThat(list.path("x-yano").path("matchesChain").asBoolean()).isTrue();
        assertThat(list.path("x-yano").path("replayedHeight").asLong()).isEqualTo(publishedHeight);
        StatusListDocument.Served served = StatusListDocument.parse(listJson);
        assertThat(served.bitstring().get(5)).isTrue();
        assertThat(served.bitstring().get(8)).isTrue();
        assertThat(served.bitstring().get(9)).isFalse();
        assertThat(served.bitstring().sha256Hex()).isEqualTo(served.chainListSha256Hex());
        StatusAnswer listEntry = AnswerCodec.fromJsonNode(list.path("x-yano").path("listEntry"));
        assertThat(TrustRegistryVerifier.verify(listEntry, pinned).consistent()).isTrue();

        JsonNode earlier = get("/status-lists/" + LIST + "?height=" + (revokedAtHeight - 1));
        assertThat(earlier.path("x-yano").path("setCount").asLong()).isEqualTo(1);
        assertThat(earlier.path("x-yano").path("matchesChain").asBoolean()).isFalse();

        JsonNode trqp = get("/trqp/entities/issuer-a/authorizations/issue:credential?framework="
                + TrustRegistryGenesis.DEMO_FRAMEWORK);
        assertThat(trqp.path("authorized").asBoolean()).isTrue();
        assertThat(trqp.path("answer").path("provenance").path("kind").asText()).isEqualTo("GENESIS");
        JsonNode denied = get("/trqp/entities/issuer-a/authorizations/mint:money?framework="
                + TrustRegistryGenesis.DEMO_FRAMEWORK);
        assertThat(denied.path("authorized").asBoolean()).isFalse();
        JsonNode unknown = get("/trqp/entities/nobody/authorizations/issue:credential?framework=x");
        assertThat(unknown.path("authorized").asBoolean()).isFalse();
        assertThat(unknown.path("reason").asText()).contains("not registered");

        JsonNode entry = get("/entries/status/" + HEX.formatHex(TrustRegistryProfile.statusKey(LIST, 5)));
        assertThat(entry.path("presence").asText()).isEqualTo("ACTIVE");
        assertThat(status("/status-lists/missing-list")).isEqualTo(404);
        assertThat(status("/nope")).isEqualTo(404);
    }

    @Test
    void issuerOnboardingThroughTheApprovalRouteAnswersWithReceiptProvenance() throws Exception {
        AuthenticatedMapContract.Command command = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(TrustRegistryProfile.ISSUERS,
                        TrustRegistryProfile.issuerKey("issuer-b"),
                        new TrustRegistryValues.IssuerValue(TrustRegistryGenesis.DEMO_FRAMEWORK,
                                List.of("issue:credential"), 1, 0).encode()));
        AuthenticatedMapAuthorizationContract.MapActionV1 action =
                TrustRegistrySigner.approvalAction(command, TrustRegistryProfile.ONBOARDING_POLICY);
        byte[] payloadHash = TrustRegistrySigner.approvalPayloadHash(genesisId, action);
        long tip = client.tipHeight();
        long deadline = tip + 100;
        TrustRegistrySigner.ActorContext registrarA = actor("registrar-a", tip);
        TrustRegistrySigner.ActorContext registrarB = actor("registrar-b", tip);
        String proposalId = "onboard-issuer-b";
        String propose = client.submitStatement(TrustRegistrySigner.signedStatement(
                ActorStatementV1.Action.PROPOSE, cluster.chainId(), proposalId,
                TrustRegistryProfile.ONBOARDING_POLICY, 1, payloadHash, deadline, registrarA, ""));
        client.awaitFinalized(propose, TIMEOUT);
        for (TrustRegistrySigner.ActorContext registrar : List.of(registrarA, registrarB)) {
            String approve = client.submitStatement(TrustRegistrySigner.signedStatement(
                    ActorStatementV1.Action.APPROVE, cluster.chainId(), proposalId,
                    TrustRegistryProfile.ONBOARDING_POLICY, 1, payloadHash, deadline, registrar,
                    TrustRegistryProfile.ONBOARDING_CLAUSE));
            client.awaitFinalized(approve, TIMEOUT);
        }
        String messageId = client.submit(TrustRegistrySigner.approvalCommand(action, proposalId,
                TrustRegistryProfile.ONBOARDING_POLICY, 1));
        AuthenticatedMapContract.Receipt receipt = client.awaitReceipt(messageId, TIMEOUT);
        assertThat(receipt.status()).as("error code " + receipt.errorCode())
                .isEqualTo(AuthenticatedMapContract.RECEIPT_APPLIED);

        StatusAnswer onboarded = client.answer(TrustRegistryProfile.ISSUERS,
                TrustRegistryProfile.issuerKey("issuer-b"), null);
        assertThat(onboarded.presence()).isEqualTo(StatusAnswer.Presence.ACTIVE);
        assertThat(onboarded.provenance().kind()).isEqualTo(StatusAnswer.ProvenanceKind.RECEIPT);
        assertThat(onboarded.provenance().messageIdHex()).isEqualTo(messageId);
        assertThat(TrustRegistryVerifier.verify(onboarded, pinned).consistent()).isTrue();
        JsonNode trqp = get("/trqp/entities/issuer-b/authorizations/issue:credential?framework="
                + TrustRegistryGenesis.DEMO_FRAMEWORK);
        assertThat(trqp.path("authorized").asBoolean()).isTrue();
    }

    @Test
    void goldenFixturesStayVerifiable() throws Exception {
        String membersJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                JSON.createObjectNode()
                        .put("chainId", cluster.chainId())
                        .put("threshold", cluster.threshold())
                        .set("memberKeysHex", JSON.valueToTree(cluster.memberKeysHex())));
        String answerJson = AnswerCodec.toJson(statusAnswer);
        String listJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                JSON.readTree(getText("/status-lists/" + LIST)));
        String descriptorJson = TrustRegistryGenesis.toJson(cluster.descriptor());
        if (Boolean.getBoolean("yano.trust.golden.write")) {
            for (String property : List.of("yano.trust.golden.dir", "yano.trust.cli.golden.dir",
                    "yano.trust.ui.golden.dir")) {
                Path dir = Path.of(System.getProperty(property));
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("golden-answer.json"), answerJson + System.lineSeparator());
                Files.writeString(dir.resolve("golden-members.json"), membersJson + System.lineSeparator());
                Files.writeString(dir.resolve("golden-status-list.json"), listJson + System.lineSeparator());
                Files.writeString(dir.resolve("golden-descriptor.json"), descriptorJson + System.lineSeparator());
            }
        }
        Path golden = Path.of(System.getProperty("yano.trust.golden.dir"));
        if (!Files.exists(golden.resolve("golden-answer.json"))) {
            return;
        }
        StatusAnswer committed = AnswerCodec.fromJson(Files.readString(golden.resolve("golden-answer.json")));
        AttestTrust.CallerPinned committedMembers = AttestTrust.CallerPinned.fromJson(
                Files.readString(golden.resolve("golden-members.json")));
        TrustRegistryVerifier.Verification result = TrustRegistryVerifier.verify(committed, committedMembers);
        assertThat(result.failures()).isEmpty();
        assertThat(result.consistent()).isTrue();
        assertThat(result.trustLevel()).isEqualTo(ProofLabVocabulary.TrustLevel.CALLER_PINNED_ROOT);
        StatusListDocument.Served served = StatusListDocument.parse(
                Files.readString(golden.resolve("golden-status-list.json")));
        assertThat(served.bitstring().sha256Hex()).isEqualTo(served.chainListSha256Hex());
        TrustRegistryGenesis.Descriptor descriptor = TrustRegistryGenesis.parse(
                Files.readString(golden.resolve("golden-descriptor.json")));
        assertThat(descriptor.actors()).extracting(TrustRegistryGenesis.Actor::id)
                .containsExactlyElementsOf(TrustRegistryGenesis.DEMO_ACTOR_IDS);
    }

    @Test
    void gatewayPerformsEveryWriteForTheConsole() throws Exception {
        Map<String, byte[]> seeds = new LinkedHashMap<>();
        for (String actorId : TrustRegistryGenesis.DEMO_ACTOR_IDS) {
            seeds.put(actorId, TrustRegistryGenesis.demoActorSeed(actorId));
        }
        try (RegistryGatewayService gateway = RegistryGatewayService.start(new RegistryWriter(client),
                seeds, null, null, new InetSocketAddress("127.0.0.1", 0), false)) {
            HttpClient http = HttpClient.newHttpClient();
            String base = gateway.baseUrl();

            // The token is required on every operator route, and healthz never asks for it.
            assertThat(http.send(HttpRequest.newBuilder(URI.create(base + "/healthz")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(http.send(HttpRequest.newBuilder(URI.create(base + "/operator/actors")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);

            JsonNode actors = gatewayGet(gateway, "/operator/actors");
            assertThat(actors.path("chainId").asText()).isEqualTo(cluster.chainId());
            assertThat(actors.path("actors")).hasSize(TrustRegistryGenesis.DEMO_ACTOR_IDS.size());
            JsonNode issuer = actors.path("actors").get(
                    TrustRegistryGenesis.DEMO_ACTOR_IDS.indexOf("issuer-a"));
            assertThat(issuer.path("organizationId").asText()).isEqualTo("issuer-org-a");
            assertThat(issuer.path("roles").get(0).asText()).isEqualTo(TrustRegistryProfile.ISSUER_ROLE);

            // Every write route, each answered with the receipt the CLI prints.
            ObjectNode status = JSON.createObjectNode().put("actorId", "issuer-a")
                    .put("listId", "list-2").put("index", 12).put("bit", 1).put("reasonCode", 3);
            JsonNode wrote = gatewayPost(gateway, "/operator/status", status, 200);
            assertThat(wrote.path("status").asText()).isEqualTo("APPLIED");
            assertThat(wrote.path("results").get(0).path("key").asText()).isEqualTo("list-2/12");
            assertThat(wrote.path("results").get(0).path("revision").asLong()).isEqualTo(1);

            ObjectNode subject = JSON.createObjectNode().put("actorId", "registrar-a")
                    .put("subjectId", "did:example:gateway-1").put("controllerOrganizationId", "registry-operator")
                    .put("kind", "product").put("metadataHashHex", "11".repeat(32));
            assertThat(gatewayPost(gateway, "/operator/subjects", subject, 200).path("status").asText())
                    .isEqualTo("APPLIED");

            ObjectNode schema = JSON.createObjectNode().put("actorId", "registrar-a")
                    .put("schemaId", "schema-gw-1")
                    .put("valueBase64", Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)));
            assertThat(gatewayPost(gateway, "/operator/schemas", schema, 200).path("status").asText())
                    .isEqualTo("APPLIED");

            ObjectNode publish = JSON.createObjectNode().put("actorId", "issuer-a").put("listId", "list-2");
            JsonNode published = gatewayPost(gateway, "/operator/lists/publish", publish, 200);
            assertThat(published.path("status").asText()).isEqualTo("APPLIED");
            assertThat(published.path("setCount").asLong()).isEqualTo(1);
            assertThat(published.path("listSha256").asText()).hasSize(64);

            ObjectNode revoke = JSON.createObjectNode().put("actorId", "issuer-a")
                    .put("listId", "list-2").put("index", 12);
            assertThat(gatewayPost(gateway, "/operator/status/revoke", revoke, 200)
                    .path("results").get(0).path("status").asText()).isEqualTo("REVOKED");
            assertThat(gatewayPost(gateway, "/operator/status/revoke", revoke, 409).path("error").asText())
                    .as("revocation is terminal").contains("REVOKED");

            // The chain, not the gateway, decides what a role may write: a registrar writing a
            // status entry is signed, submitted, and refused by the map.
            ObjectNode wrongRole = JSON.createObjectNode().put("actorId", "registrar-a")
                    .put("listId", "list-2").put("index", 13).put("bit", 1);
            JsonNode refused = gatewayPost(gateway, "/operator/status", wrongRole, 200);
            assertThat(refused.path("status").asText()).isEqualTo("REJECTED");
            assertThat(refused.path("errorCode").asInt()).isPositive();

            // Bad requests are answered before anything is signed.
            ObjectNode unknownActor = JSON.createObjectNode().put("actorId", "nobody")
                    .put("listId", "list-2").put("index", 1).put("bit", 1);
            assertThat(gatewayPost(gateway, "/operator/status", unknownActor, 400).path("error").asText())
                    .contains("holds no seed");
            ObjectNode missingField = JSON.createObjectNode().put("actorId", "issuer-a").put("listId", "list-2");
            assertThat(gatewayPost(gateway, "/operator/status", missingField, 400).path("error").asText())
                    .contains("required");
            assertThat(gatewayPost(gateway, "/operator/unknown", missingField, 404).path("error").asText())
                    .contains("unknown path");

            // The gateway never hands back a seed.
            assertThat(actors.toString()).doesNotContain(HEX.formatHex(
                    TrustRegistryGenesis.demoActorSeed("issuer-a")));
        }
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode gatewayGet(RegistryGatewayService gateway, String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(gateway.baseUrl() + path))
                        .header(RegistryGatewayService.TOKEN_HEADER, gateway.token()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private JsonNode gatewayPost(RegistryGatewayService gateway, String path, JsonNode body,
                                 int expectedStatus) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(gateway.baseUrl() + path))
                        .header(RegistryGatewayService.TOKEN_HEADER, gateway.token())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(expectedStatus);
        return JSON.readTree(response.body());
    }

    private AuthenticatedMapContract.Receipt write(String actorId, String policyId,
                                                   AuthenticatedMapContract.Command command) {
        long tip = client.tipHeight();
        long policyRevision = tip < 1 ? 1 : client.directPolicy(policyId).revision();
        long lifetime = tip < 1 ? TrustRegistryProfile.DIRECT_AUTHORIZATION_LIFETIME_BLOCKS
                : Math.min(client.directPolicy(policyId).maximumAuthorizationLifetimeBlocks(),
                TrustRegistryProfile.DIRECT_AUTHORIZATION_LIFETIME_BLOCKS);
        TrustRegistrySigner.ActorContext actor = actor(actorId, tip);
        long issued = Math.max(tip, 1);
        byte[] bytes = TrustRegistrySigner.governedCommand(command, policyId, policyRevision,
                actor, cluster.chainId(), genesisId, issued, issued + lifetime,
                TrustRegistrySigner.randomAuthorizationId());
        String messageId = client.submit(bytes);
        AuthenticatedMapContract.Receipt receipt = client.awaitReceipt(messageId, TIMEOUT);
        assertThat(receipt.status()).as("receipt error " + receipt.errorCode())
                .isEqualTo(AuthenticatedMapContract.RECEIPT_APPLIED);
        return receipt;
    }

    private TrustRegistrySigner.ActorContext actor(String actorId, long height) {
        byte[] seed = TrustRegistryGenesis.demoActorSeed(actorId);
        if (height < 1) {
            return TrustRegistrySigner.genesisActorContext(cluster.descriptor(), actorId, seed);
        }
        return TrustRegistrySigner.actorContext(client.actor(actorId), seed, height);
    }

    private JsonNode get(String path) throws Exception {
        return JSON.readTree(getText(path));
    }

    private String getText(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(service.baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    private int status(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(service.baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
