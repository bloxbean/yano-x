package org.yanoproject.x.dpp.client;

import org.yanoproject.api.appchain.proof.ProofLabVocabulary;
import org.yanoproject.x.attest.client.AttestTrust;
import org.yanoproject.x.dpp.profile.DppGenesis;
import org.yanoproject.x.dpp.profile.DppStarterProfile;
import org.yanoproject.x.dpp.profile.DppValues;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.trust.client.AnswerCodec;
import org.yanoproject.x.trust.client.StatusAnswer;
import org.yanoproject.x.trust.client.TrustRegistryClient;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-051 §7: the starter's whole journey on a real three-member governed map cluster with the
 * demo genesis: registration, versions, public and committed claims, lifecycle events, the
 * certification round with two auditors, a raw rewrite, a stale compare-and-set, certificate
 * revocation, product revocation, passports verified under every trust input, tampering, the
 * disclosure check, and the portal and gateway routes. Writes the goldens the CLI and console pin.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DppClusterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final String API_KEY = "dpp-test-key";
    private static final String PRODUCT = "gtin:09506000134352";
    private static final String SECOND_PRODUCT = "gtin:09506000134369";
    private static final byte[] DOCUMENT_V1 = "{\"passport\":\"battery\",\"version\":1}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DOCUMENT_V2 = "{\"passport\":\"battery\",\"version\":2}".getBytes(StandardCharsets.UTF_8);

    private DppTestCluster cluster;
    private GatewayHttpBridge bridge;
    private DppClient client;
    private DocumentStore documents;
    private PortalService portal;
    private GatewayService gateway;
    private AttestTrust.CallerPinned pinned;
    private String genesisIdHex;
    private Disclosure disclosure;
    private String documentV1Sha;
    private PassportBundle beforeRevoke;
    private PassportBundle atTip;
    private DppClient.WriteResult prematureApply;
    private DppClient.WriteResult staleCompareAndSet;

    @BeforeAll
    void journey() throws Exception {
        Path temp = Files.createTempDirectory("dpp-test-");
        cluster = DppTestCluster.start("dpp-starter-golden", 3, 2);
        bridge = GatewayHttpBridge.start(cluster.node(2), API_KEY);
        client = new DppClient(TrustRegistryClient.builder(bridge.baseUrl(), cluster.chainId())
                .apiKey(API_KEY).build());
        genesisIdHex = HEX.formatHex(AuthenticatedMapContract.genesisId(cluster.genesis()));
        pinned = new AttestTrust.CallerPinned(cluster.chainId(),
                Set.copyOf(cluster.memberKeysHex()), cluster.threshold(), null, null);
        documents = new DocumentStore(temp.resolve("documents"));
        assertThat(client.identity().starter()).as("genesis unreadable before the first block").isFalse();

        // The first write on a fresh chain signs from the generated genesis id.
        DppClient.Signer makerFirst = new DppClient.Signer("maker-a", seed("maker-a"), genesisIdHex, null);
        applied(client.registerProduct(makerFirst, PRODUCT, new DppValues.ProductValue(
                "acme-manufacturing", DppStarterProfile.STATUS_DRAFT, 0, "", "battery-passport-demo-v1")));
        cluster.awaitHeight(1);
        assertThat(client.identity().starter()).isTrue();
        assertThat(client.identity().mapGenesisIdHex()).isEqualTo(genesisIdHex);

        documentV1Sha = documents.put(DOCUMENT_V1);
        DppClient.WriteResult published = applied(client.publishVersion(signer("maker-a"), PRODUCT, 1,
                new DppValues.VersionValue(HEX.parseHex(documentV1Sha), "application/json",
                        "https://acme.example/passports/v1.json", DOCUMENT_V1.length)));
        assertThat(published.receipt().results()).hasSize(2);
        applied(client.setStatus(signer("maker-a"), PRODUCT, DppStarterProfile.STATUS_ACTIVE, ""));

        applied(client.putClaim(signer("issuer-a"), PRODUCT, "recycled-content", "rc-1",
                new DppValues.ClaimValue(DppStarterProfile.VISIBILITY_PUBLIC,
                        "42 %".getBytes(StandardCharsets.UTF_8), "green-labs", 0, 0, null)));
        disclosure = Disclosure.create(cluster.chainId(), PRODUCT, "carbon-footprint", "cf-1", "12.5 kgCO2e");
        applied(client.putClaim(signer("issuer-a"), PRODUCT, "carbon-footprint", "cf-1",
                new DppValues.ClaimValue(DppStarterProfile.VISIBILITY_COMMITTED, disclosure.commitment(),
                        "green-labs", 0, 0, HEX.parseHex(documentV1Sha))));

        applied(client.appendEvent(signer("maker-a"), PRODUCT, "e-1", new DppValues.EventValue(
                "MANUFACTURED", "acme-manufacturing", 1_788_000_000L, "Plant 7", null, "")));
        applied(client.appendEvent(signer("logistics-a"), PRODUCT, "e-2", new DppValues.EventValue(
                "SHIPPED", "swift-logistics", 1_788_100_000L, "Rotterdam", null, "container MSKU1")));
        applied(client.appendEvent(signer("logistics-a"), PRODUCT, "e-3", new DppValues.EventValue(
                "RECEIVED", "swift-logistics", 1_788_200_000L, "Hamburg", null, "")));

        // Certification: propose, apply too early (rejected), two auditors approve, apply.
        CertificationRequest request = client.proposeCertificate(signer("certifier-a"), "cert-eco-1",
                new DppValues.CertificateValue(PRODUCT, "EU-Ecodesign", "cert-body-a",
                        HEX.parseHex(documentV1Sha), 0, 0));
        assertThat(request.consistent()).isTrue();
        CertificationRequest travelled = CertificationRequest.fromJson(request.toJson());
        prematureApply = client.applyCertification(travelled);
        assertThat(prematureApply.applied()).isFalse();
        client.decideCertification(signer("auditor-a"), travelled, true);
        client.decideCertification(signer("auditor-b"), travelled, true);
        applied(client.applyCertification(travelled));

        // A raw rewrite of version 1, a stale compare-and-set, and version 2.
        applied(client.put(signer("maker-a"), DppStarterProfile.VERSIONS,
                DppStarterProfile.versionKey(PRODUCT, 1), new DppValues.VersionValue(
                        HEX.parseHex(documentV1Sha), "application/json", "rewritten", DOCUMENT_V1.length).encode()));
        AuthenticatedMapContract.Entry product = client.entry(DppStarterProfile.PRODUCTS,
                DppStarterProfile.productKey(PRODUCT)).orElseThrow();
        staleCompareAndSet = client.submitGoverned(signer("maker-a"), DppStarterProfile.MANUFACTURER_POLICY,
                AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.compareAndSet(
                        DppStarterProfile.PRODUCTS, DppStarterProfile.productKey(PRODUCT),
                        DppValues.ProductValue.decode(product.value()).withStatus(
                                DppStarterProfile.STATUS_INACTIVE, "").encode(),
                        product.revision() - 1, null)));
        assertThat(staleCompareAndSet.applied()).isFalse();
        String documentV2Sha = documents.put(DOCUMENT_V2);
        applied(client.publishVersion(signer("maker-a"), PRODUCT, 2, new DppValues.VersionValue(
                HEX.parseHex(documentV2Sha), "application/json", "https://acme.example/passports/v2.json",
                DOCUMENT_V2.length)));

        // Certificate revocation through a second round.
        CertificationRequest revocation = client.proposeRevocation(signer("certifier-a"), "cert-eco-1");
        assertThat(revocation.operation()).isEqualTo("REVOKE");
        client.decideCertification(signer("auditor-a"), revocation, true);
        client.decideCertification(signer("auditor-b"), revocation, true);
        applied(client.applyCertification(revocation));

        beforeRevoke = client.passport(PRODUCT, null);
        applied(client.revokeProduct(signer("maker-a"), PRODUCT));
        atTip = client.passport(PRODUCT, null);

        portal = PortalService.start(client, documents, new InetSocketAddress("127.0.0.1", 0));
        Map<String, byte[]> seeds = new LinkedHashMap<>();
        for (String actorId : DppGenesis.DEMO_ACTOR_IDS) {
            seeds.put(actorId, seed(actorId));
        }
        gateway = GatewayService.start(client, documents, seeds, null, null,
                new InetSocketAddress("127.0.0.1", 0), false);
    }

    @AfterAll
    void stop() {
        if (gateway != null) gateway.close();
        if (portal != null) portal.close();
        if (bridge != null) bridge.close();
        if (cluster != null) cluster.close();
    }

    @Test
    void passportVerifiesUnderEveryTrustInputAndExposesTheFlags() {
        ObjectNode view = PassportView.of(beforeRevoke, documents::has);
        assertThat(view.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(view.path("product").path("currentVersion").asLong()).isEqualTo(2);
        assertThat(view.path("product").path("provenance").path("actorId").asText()).isEqualTo("maker-a");
        assertThat(view.path("versions")).hasSize(2);
        JsonNode versionOne = view.path("versions").get(0);
        assertThat(versionOne.path("version").asLong()).isEqualTo(1);
        assertThat(versionOne.path("revision").asLong()).isEqualTo(2);
        assertThat(versionOne.path("flags")).extracting(JsonNode::asText).contains(PassportView.FLAG_REWRITTEN);
        assertThat(versionOne.path("availability").asText()).isEqualTo("CONTENT_VERIFIED");
        JsonNode versionTwo = view.path("versions").get(1);
        assertThat(versionTwo.path("current").asBoolean()).isTrue();
        assertThat(versionTwo.path("flags")).isEmpty();
        assertThat(view.path("flags")).isEmpty();
        assertThat(view.path("claims")).hasSize(2);
        assertThat(view.path("claims").get(0).path("text").asText()).isEqualTo("42 %");
        assertThat(view.path("claims").get(1).path("visibility").asText()).isEqualTo("COMMITTED");
        assertThat(view.path("claims").get(1).path("commitment").asText())
                .isEqualTo(HEX.formatHex(disclosure.commitment()));
        assertThat(view.path("events")).extracting(node -> node.path("eventType").asText())
                .containsExactly("MANUFACTURED", "SHIPPED", "RECEIVED");
        assertThat(view.path("events").get(1).path("provenance").path("organizationId").asText())
                .isEqualTo("swift-logistics");
        assertThat(view.path("certificates")).hasSize(1);
        JsonNode certificate = view.path("certificates").get(0);
        assertThat(certificate.path("presence").asText()).isEqualTo("REVOKED");
        assertThat(certificate.path("approvalConsumption").asBoolean()).isTrue();
        assertThat(certificate.path("provenance").path("kind").asText()).isEqualTo("RECEIPT");
        assertThat(view.path("timeline").size()).isGreaterThanOrEqualTo(12);

        PassportVerifier.Verification pinnedResult = PassportVerifier.verify(beforeRevoke, pinned);
        assertThat(pinnedResult.failures()).isEmpty();
        assertThat(pinnedResult.consistent()).isTrue();
        assertThat(pinnedResult.trustLevel()).isEqualTo(ProofLabVocabulary.TrustLevel.CALLER_PINNED_ROOT);
        assertThat(pinnedResult.certSignatures()).isGreaterThanOrEqualTo(cluster.threshold());
        assertThat(pinnedResult.checks()).anyMatch(check -> check.contains("consumed once"));
        assertThat(pinnedResult.answers()).hasSize(beforeRevoke.answers().size());

        PassportVerifier.Verification declared = PassportVerifier.verify(beforeRevoke, AttestTrust.bundleDeclared());
        assertThat(declared.consistent()).isTrue();
        assertThat(declared.trustLevel()).isEqualTo(ProofLabVocabulary.TrustLevel.INTERNAL_CONSISTENCY_ONLY);

        PassportBundle decoded = PassportBundle.fromJson(beforeRevoke.toJson());
        assertThat(decoded.answers()).hasSize(beforeRevoke.answers().size());
        assertThat(PassportVerifier.verify(decoded, pinned).consistent()).isTrue();

        assertThat(prematureApply.errorName()).isEqualTo("APPROVAL_NOT_APPROVED");
        assertThat(staleCompareAndSet.errorName()).isIn("WRONG_REVISION", "PRECONDITION");
    }

    @Test
    void wrongMembersTamperingAndForeignKeysFail() {
        AttestTrust.CallerPinned strangers = new AttestTrust.CallerPinned(cluster.chainId(),
                Set.of("aa".repeat(32), "bb".repeat(32)), 1, null, null);
        assertThat(PassportVerifier.verify(beforeRevoke, strangers).consistent()).isFalse();

        StatusAnswer version = beforeRevoke.versions().getFirst();
        byte[] forged = new DppValues.VersionValue(new byte[32], "application/json", "forged", 1).encode();
        StatusAnswer.Entry original = version.entry();
        StatusAnswer.Entry tampered = new StatusAnswer.Entry(original.status(), original.revision(),
                original.controller(), forged, AuthenticatedMapContract.logicalValueHash(forged),
                original.createdHeight(), original.lastMutationHeight());
        StatusAnswer forgedAnswer = new StatusAnswer(version.chainId(), version.profile(),
                version.genesisIdHex(), version.height(), version.stateRootHex(), version.blockHashHex(),
                version.collection(), version.key(), version.presence(), tampered, version.provenance(),
                version.actionCommitment(), version.authorizationEvidence(),
                version.facts().stream().map(fact -> fact.name().equals("entry")
                        ? new StatusAnswer.Fact("entry", fact.expectedKey(), tampered.encode(), fact.proofJson())
                        : fact).toList(), version.evidenceJson());
        PassportBundle forgedBundle = new PassportBundle(beforeRevoke.chainId(), beforeRevoke.profile(),
                beforeRevoke.genesisIdHex(), beforeRevoke.height(), beforeRevoke.stateRootHex(),
                beforeRevoke.blockHashHex(), beforeRevoke.productId(), beforeRevoke.product(),
                List.of(forgedAnswer, beforeRevoke.versions().get(1)), beforeRevoke.claims(),
                beforeRevoke.events(), beforeRevoke.certificates(), beforeRevoke.timeline());
        PassportVerifier.Verification forgedResult = PassportVerifier.verify(forgedBundle, pinned);
        assertThat(forgedResult.consistent()).isFalse();
        assertThat(forgedResult.failures()).isNotEmpty();

        // Answers of another product cannot be smuggled into this passport.
        ObjectNode relabelled = beforeRevoke.toJsonNode();
        relabelled.put("productId", SECOND_PRODUCT);
        PassportBundle foreign = PassportBundle.fromJsonNode(relabelled);
        PassportVerifier.Verification foreignResult = PassportVerifier.verify(foreign, pinned);
        assertThat(foreignResult.consistent()).isFalse();
        assertThat(foreignResult.failures()).anyMatch(failure -> failure.contains("does not belong"));

        // A certificate answer stripped of its approval consumption fact is refused.
        StatusAnswer certificate = beforeRevoke.certificates().getFirst();
        StatusAnswer stripped = new StatusAnswer(certificate.chainId(), certificate.profile(),
                certificate.genesisIdHex(), certificate.height(), certificate.stateRootHex(),
                certificate.blockHashHex(), certificate.collection(), certificate.key(),
                certificate.presence(), certificate.entry(), certificate.provenance(),
                certificate.actionCommitment(), certificate.authorizationEvidence(),
                certificate.facts().stream().filter(fact ->
                        !fact.name().equals(DppClient.APPROVAL_CONSUMPTION_FACT)).toList(),
                certificate.evidenceJson());
        PassportBundle strippedBundle = new PassportBundle(beforeRevoke.chainId(), beforeRevoke.profile(),
                beforeRevoke.genesisIdHex(), beforeRevoke.height(), beforeRevoke.stateRootHex(),
                beforeRevoke.blockHashHex(), beforeRevoke.productId(), beforeRevoke.product(),
                beforeRevoke.versions(), beforeRevoke.claims(), beforeRevoke.events(),
                List.of(stripped), beforeRevoke.timeline());
        assertThat(PassportVerifier.verify(strippedBundle, pinned).failures())
                .anyMatch(failure -> failure.contains("approval consumption"));
    }

    @Test
    void tombstonesHistoryAndDisclosures() {
        assertThat(atTip.product().presence()).isEqualTo(StatusAnswer.Presence.REVOKED);
        assertThat(atTip.height()).isGreaterThan(beforeRevoke.height());
        ObjectNode view = PassportView.of(atTip, documents::has);
        assertThat(view.path("status").asText()).isEqualTo("REVOKED");
        assertThat(view.path("versions")).hasSize(2);
        assertThat(PassportVerifier.verify(atTip, pinned).consistent()).isTrue();

        PassportBundle historical = client.passport(PRODUCT, beforeRevoke.height());
        assertThat(historical.height()).isEqualTo(beforeRevoke.height());
        assertThat(historical.product().presence()).isEqualTo(StatusAnswer.Presence.ACTIVE);
        assertThat(historical.toJson()).isEqualTo(beforeRevoke.toJson());

        PassportBundle unknown = client.passport("gtin:00000000000000", null);
        assertThat(unknown.product().presence()).isEqualTo(StatusAnswer.Presence.ABSENT);
        assertThat(unknown.timeline()).isEmpty();
        assertThat(PassportVerifier.verify(unknown, pinned).consistent()).isTrue();

        assertThat(disclosure.check(beforeRevoke).outcome()).isEqualTo(Disclosure.Outcome.MATCH);
        Disclosure wrongSalt = new Disclosure(disclosure.chainId(), disclosure.productId(),
                disclosure.claimType(), disclosure.claimId(), "00".repeat(32), disclosure.text());
        assertThat(wrongSalt.check(beforeRevoke).outcome()).isEqualTo(Disclosure.Outcome.MISMATCH);
        Disclosure wrongText = new Disclosure(disclosure.chainId(), disclosure.productId(),
                disclosure.claimType(), disclosure.claimId(), disclosure.saltHex(), "12.6 kgCO2e");
        assertThat(wrongText.check(beforeRevoke).outcome()).isEqualTo(Disclosure.Outcome.MISMATCH);
        Disclosure publicClaim = new Disclosure(disclosure.chainId(), PRODUCT, "recycled-content", "rc-1",
                disclosure.saltHex(), "42 %");
        assertThat(publicClaim.check(beforeRevoke).outcome()).isEqualTo(Disclosure.Outcome.NOT_COMMITTED);
        Disclosure absent = new Disclosure(disclosure.chainId(), PRODUCT, "carbon-footprint", "cf-9",
                disclosure.saltHex(), "x");
        assertThat(absent.check(beforeRevoke).outcome()).isEqualTo(Disclosure.Outcome.ABSENT);
        Disclosure wrongProduct = new Disclosure(disclosure.chainId(), SECOND_PRODUCT, "carbon-footprint",
                "cf-1", disclosure.saltHex(), "x");
        assertThat(wrongProduct.check(beforeRevoke).outcome()).isEqualTo(Disclosure.Outcome.WRONG_PRODUCT);
    }

    @Test
    void portalServesPassportsProofsDigitalLinksAndDocuments() throws Exception {
        JsonNode health = get(portal.baseUrl() + "/healthz");
        assertThat(health.path("chainId").asText()).isEqualTo(cluster.chainId());
        JsonNode view = get(portal.baseUrl() + "/passports/" + PRODUCT);
        assertThat(view.path("status").asText()).isEqualTo("REVOKED");
        assertThat(view.path("versions").get(0).path("availability").asText()).isEqualTo("CONTENT_VERIFIED");
        JsonNode historical = get(portal.baseUrl() + "/passports/" + PRODUCT + "?height=" + beforeRevoke.height());
        assertThat(historical.path("status").asText()).isEqualTo("ACTIVE");
        JsonNode link = get(portal.baseUrl() + "/01/9506000134352");
        assertThat(link.path("productId").asText()).isEqualTo(PRODUCT);
        JsonNode proof = get(portal.baseUrl() + "/passports/" + PRODUCT + "/proof?height=" + beforeRevoke.height());
        PassportBundle served = PassportBundle.fromJsonNode(proof);
        assertThat(served.toJson()).isEqualTo(beforeRevoke.toJson());
        assertThat(PassportVerifier.verify(served, pinned).consistent()).isTrue();

        HttpResponse<byte[]> document = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(portal.baseUrl() + "/documents/" + documentV1Sha)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(document.statusCode()).isEqualTo(200);
        assertThat(document.body()).isEqualTo(DOCUMENT_V1);
        assertThat(document.headers().firstValue("X-Content-Sha256")).contains(documentV1Sha);
        assertThat(status(portal.baseUrl() + "/documents/" + "00".repeat(32))).isEqualTo(404);
        assertThat(status(portal.baseUrl() + "/passports/gtin:00000000000000")).isEqualTo(404);
        assertThat(status(portal.baseUrl() + "/passports/bad%20id")).isEqualTo(400);
        assertThat(status(portal.baseUrl() + "/nope")).isEqualTo(404);
        HttpResponse<String> post = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(portal.baseUrl() + "/passports/" + PRODUCT))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(405);
    }

    @Test
    void gatewayPerformsEveryWriteForTheConsole() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> unauthenticated = http.send(HttpRequest.newBuilder(
                URI.create(gateway.baseUrl() + "/operator/actors")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        JsonNode actors = gatewayGet("/operator/actors");
        assertThat(actors.path("actors")).hasSize(DppGenesis.DEMO_ACTOR_IDS.size());
        assertThat(actors.path("actors").get(1).path("roles")).extracting(JsonNode::asText)
                .contains("manufacturer", "operator");

        ObjectNode register = JSON.createObjectNode();
        register.put("actorId", "maker-a");
        register.put("productId", SECOND_PRODUCT);
        register.put("manufacturerOrganizationId", "acme-manufacturing");
        register.put("passportProfileId", "battery-passport-demo-v1");
        register.put("status", "ACTIVE");
        JsonNode registered = gatewayPost("/operator/products", register, 200);
        assertThat(registered.path("status").asText()).isEqualTo("APPLIED");

        ObjectNode version = JSON.createObjectNode();
        version.put("actorId", "maker-a");
        version.put("productId", SECOND_PRODUCT);
        version.put("version", 1);
        version.put("mediaType", "application/json");
        version.put("reference", "https://acme.example/passports/second.json");
        version.put("documentBase64", Base64.getEncoder().encodeToString(DOCUMENT_V2));
        JsonNode published = gatewayPost("/operator/versions", version, 200);
        assertThat(published.path("status").asText()).isEqualTo("APPLIED");
        assertThat(documents.has(published.path("documentSha256").asText())).isTrue();

        ObjectNode claim = JSON.createObjectNode();
        claim.put("actorId", "issuer-a");
        claim.put("productId", SECOND_PRODUCT);
        claim.put("claimType", "carbon-footprint");
        claim.put("claimId", "cf-1");
        claim.put("text", "9.1 kgCO2e");
        claim.put("committed", true);
        claim.put("issuerOrganizationId", "green-labs");
        JsonNode claimed = gatewayPost("/operator/claims", claim, 200);
        assertThat(claimed.path("status").asText()).isEqualTo("APPLIED");
        Disclosure fromGateway = Disclosure.fromJson(claimed.path("disclosure").toString());

        ObjectNode event = JSON.createObjectNode();
        event.put("actorId", "logistics-a");
        event.put("productId", SECOND_PRODUCT);
        event.put("eventType", "SHIPPED");
        event.put("actorOrganizationId", "swift-logistics");
        event.put("observedAt", 1_788_300_000L);
        event.put("location", "Antwerp");
        assertThat(gatewayPost("/operator/events", event, 200).path("status").asText()).isEqualTo("APPLIED");

        ObjectNode propose = JSON.createObjectNode();
        propose.put("actorId", "certifier-a");
        propose.put("productId", SECOND_PRODUCT);
        propose.put("certificateId", "cert-eco-2");
        propose.put("certificateType", "EU-Ecodesign");
        propose.put("issuerOrganizationId", "cert-body-a");
        propose.put("evidenceSha256", documentV1Sha);
        JsonNode proposed = gatewayPost("/operator/certifications/propose", propose, 200);
        JsonNode request = proposed.path("request");
        assertThat(request.path("type").asText()).isEqualTo(CertificationRequest.TYPE);
        for (String auditor : List.of("auditor-a", "auditor-b")) {
            ObjectNode approve = JSON.createObjectNode();
            approve.put("actorId", auditor);
            approve.set("request", request);
            assertThat(gatewayPost("/operator/certifications/approve", approve, 200)
                    .path("decision").asText()).isEqualTo("APPROVE");
        }
        ObjectNode apply = JSON.createObjectNode();
        apply.set("request", request);
        assertThat(gatewayPost("/operator/certifications/apply", apply, 200).path("status").asText())
                .isEqualTo("APPLIED");

        ObjectNode unknownActor = JSON.createObjectNode();
        unknownActor.put("actorId", "nobody");
        unknownActor.put("productId", SECOND_PRODUCT);
        assertThat(gatewayPost("/operator/revoke", unknownActor, 400).path("error").asText())
                .contains("no seed");
        ObjectNode missing = JSON.createObjectNode();
        missing.put("actorId", "maker-a");
        assertThat(gatewayPost("/operator/status", missing, 400).path("error").asText()).contains("required");

        PassportBundle second = client.passport(SECOND_PRODUCT, null);
        ObjectNode view = PassportView.of(second, documents::has);
        assertThat(view.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(view.path("versions")).hasSize(1);
        assertThat(view.path("claims")).hasSize(1);
        assertThat(view.path("events")).hasSize(1);
        assertThat(view.path("certificates").get(0).path("presence").asText()).isEqualTo("ACTIVE");
        assertThat(view.path("certificates").get(0).path("validity").asText()).isEqualTo("VALID");
        assertThat(fromGateway.check(second).outcome()).isEqualTo(Disclosure.Outcome.MATCH);
        assertThat(PassportVerifier.verify(second, pinned).consistent()).isTrue();
    }

    @Test
    void goldenFixturesStayVerifiable() throws Exception {
        String membersJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                JSON.createObjectNode()
                        .put("chainId", cluster.chainId())
                        .put("threshold", cluster.threshold())
                        .set("memberKeysHex", JSON.valueToTree(cluster.memberKeysHex())));
        String passportJson = beforeRevoke.toJson();
        String disclosureJson = disclosure.toJson();
        String requestJson = client.proposeCertificate(signer("certifier-a"), "cert-golden",
                new DppValues.CertificateValue(PRODUCT, "golden", "cert-body-a", new byte[32], 0, 0)).toJson();
        if (Boolean.getBoolean("yano.dpp.golden.write")) {
            for (String property : List.of("yano.dpp.golden.dir", "yano.dpp.cli.golden.dir",
                    "yano.dpp.ui.golden.dir")) {
                Path dir = Path.of(System.getProperty(property));
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("golden-passport.json"), passportJson + System.lineSeparator());
                Files.writeString(dir.resolve("golden-members.json"), membersJson + System.lineSeparator());
                Files.writeString(dir.resolve("golden-disclosure.json"), disclosureJson + System.lineSeparator());
                Files.writeString(dir.resolve("golden-certification-request.json"), requestJson + System.lineSeparator());
            }
        }
        Path golden = Path.of(System.getProperty("yano.dpp.golden.dir"));
        if (!Files.exists(golden.resolve("golden-passport.json"))) {
            return;
        }
        PassportBundle committed = PassportBundle.fromJson(Files.readString(golden.resolve("golden-passport.json")));
        AttestTrust.CallerPinned committedMembers = AttestTrust.CallerPinned.fromJson(
                Files.readString(golden.resolve("golden-members.json")));
        PassportVerifier.Verification result = PassportVerifier.verify(committed, committedMembers);
        assertThat(result.failures()).isEmpty();
        assertThat(result.consistent()).isTrue();
        assertThat(result.trustLevel()).isEqualTo(ProofLabVocabulary.TrustLevel.CALLER_PINNED_ROOT);
        Disclosure committedDisclosure = Disclosure.fromJson(
                Files.readString(golden.resolve("golden-disclosure.json")));
        assertThat(committedDisclosure.check(committed).outcome()).isEqualTo(Disclosure.Outcome.MATCH);
        assertThat(CertificationRequest.fromJson(
                Files.readString(golden.resolve("golden-certification-request.json"))).consistent()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] seed(String actorId) {
        return DppGenesis.demoActorSeed(actorId);
    }

    private static DppClient.Signer signer(String actorId) {
        return DppClient.Signer.of(actorId, seed(actorId));
    }

    private static DppClient.WriteResult applied(DppClient.WriteResult result) {
        assertThat(result.applied()).as("receipt error " + result.errorName()).isTrue();
        return result;
    }

    private JsonNode gatewayGet(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(gateway.baseUrl() + path)).header(GatewayService.TOKEN_HEADER, gateway.token())
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private JsonNode gatewayPost(String path, JsonNode body, int expectedStatus) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(gateway.baseUrl() + path)).header(GatewayService.TOKEN_HEADER, gateway.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return JSON.readTree(response.body());
    }

    private static JsonNode get(String url) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static int status(String url) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
