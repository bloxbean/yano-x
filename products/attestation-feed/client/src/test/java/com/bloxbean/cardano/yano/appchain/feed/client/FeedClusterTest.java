package com.bloxbean.cardano.yano.appchain.feed.client;

import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.appchain.attest.client.AttestTrust;
import com.bloxbean.cardano.yano.appchain.feed.profile.Aggregation;
import com.bloxbean.cardano.yano.appchain.feed.profile.FeedGenesis;
import com.bloxbean.cardano.yano.appchain.feed.profile.FeedStarterProfile;
import com.bloxbean.cardano.yano.appchain.feed.profile.FeedValues;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.trust.client.StatusAnswer;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistryClient;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-052 §7: the whole journey on a real three-member governed map with the demo genesis, then
 * bundles verified under every trust input, tampering, the portal and gateway routes, and the
 * goldens the CLI and console tests pin.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedClusterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final String API_KEY = "feed-test-key";
    private static final String FEED = "coldstore-7";
    private static final String SECOND_FEED = "humidity-1";
    private static final long EPOCH = 1_790_000_000L;
    private static final FeedValues.FeedValue SPEC = FeedGenesis.demoFeed(EPOCH);

    private FeedTestCluster cluster;
    private GatewayHttpBridge bridge;
    private FeedClient client;
    private PortalService portal;
    private GatewayService gateway;
    private AttestTrust.CallerPinned pinned;
    private String genesisIdHex;
    private RoundBundle round7;
    private RoundBundle round8;
    private RoundBundle round8Later;
    private RoundBundle round9;
    private RoundBundle round10;
    private RoundBundle round11;
    private RoundBundle round12;
    private RoundBundle round15;
    private RoundRequest request7;
    private RoundRequest goldenRequest;
    private FeedClient.WriteResult sameOrganizationApply;
    private long h0Of8;

    @BeforeAll
    void journey() throws Exception {
        cluster = FeedTestCluster.start("attestation-feed-golden", 3, 2);
        bridge = GatewayHttpBridge.start(cluster.node(2), API_KEY);
        // The client's clock sits in round 13 of the demo calendar so `latest` probes backwards from there.
        Clock clock = Clock.fixed(Instant.ofEpochSecond(SPEC.roundStart(13) + 5), ZoneOffset.UTC);
        client = new FeedClient(TrustRegistryClient.builder(bridge.baseUrl(), cluster.chainId())
                .apiKey(API_KEY).build(), Duration.ofSeconds(60), clock);
        genesisIdHex = HEX.formatHex(AuthenticatedMapContract.genesisId(cluster.genesis()));
        pinned = new AttestTrust.CallerPinned(cluster.chainId(),
                Set.copyOf(cluster.memberKeysHex()), cluster.threshold(), null, null);
        assertThat(client.identity().starter()).as("genesis unreadable before the first block").isFalse();

        // The first write on a fresh chain signs from the generated genesis id.
        FeedClient.Signer adminFirst = new FeedClient.Signer("feed-admin-a", seed("feed-admin-a"), genesisIdHex, null);
        applied(client.createFeed(adminFirst, FEED, SPEC));
        cluster.awaitHeight(1);
        assertThat(client.identity().starter()).isTrue();
        assertThat(client.identity().mapGenesisIdHex()).isEqualTo(genesisIdHex);

        // Round 7: three observations, one outlier; a same-organization second approval is not enough.
        observe("source-alpha", 7, -1_825, 10);
        observe("source-beta", 7, -1_810, 12);
        observe("source-gamma", 7, -900, 15);
        request7 = client.proposeRound(signer("ops-a"), FEED, 7, null);
        assertThat(request7.consistent()).isTrue();
        RoundRequest travelled = RoundRequest.fromJson(request7.toJson());
        assertThat(travelled.recordValue().acceptedSources()).containsExactly("source-alpha", "source-beta");
        assertThat(travelled.recordValue().aggregate()).isEqualTo(-1_825);
        client.approveRound(signer("publisher-a"), travelled);
        client.approveRound(signer("publisher-c"), travelled);
        sameOrganizationApply = client.applyRound(travelled);
        assertThat(sameOrganizationApply.applied()).isFalse();
        client.approveRound(signer("publisher-b"), travelled);
        applied(client.applyRound(travelled));
        round7 = client.round(FEED, 7, null);

        // Round 8: closed on two observations at h0; the third lands later and cannot change the record.
        observe("source-alpha", 8, -1_830, 20);
        observe("source-beta", 8, -1_820, 21);
        RoundRequest request8 = client.proposeRound(signer("ops-a"), FEED, 8, null);
        h0Of8 = request8.recordValue().closedAtHeight();
        observe("source-gamma", 8, -1_825, 22);
        client.approveRound(signer("publisher-a"), request8);
        client.approveRound(signer("publisher-b"), request8);
        applied(client.applyRound(request8));
        round8 = client.round(FEED, 8, null);
        round8Later = client.round(FEED, 8, client.tipHeight());

        // Round 9: one observation, recorded as NO_QUORUM.
        observe("source-alpha", 9, -1_800, 30);
        RoundRequest request9 = client.proposeRound(signer("ops-a"), FEED, 9, null);
        client.approveRound(signer("publisher-a"), request9);
        client.approveRound(signer("publisher-b"), request9);
        applied(client.applyRound(request9));
        round9 = client.round(FEED, 9, null);

        // Round 10 (open): an equivocation, a foreign write, and an out-of-range value.
        observe("source-alpha", 10, -1_800, 40);
        applied(client.put(signer("source-alpha"), FeedStarterProfile.OBSERVATIONS,
                FeedStarterProfile.observationKey(FEED, 10, "source-alpha"),
                new FeedValues.ObservationValue(-1_790, SPEC.roundStart(10) + 41, null, "rewritten").encode()));
        applied(client.put(signer("source-alpha"), FeedStarterProfile.OBSERVATIONS,
                FeedStarterProfile.observationKey(FEED, 10, "source-beta"),
                new FeedValues.ObservationValue(-1_800, SPEC.roundStart(10) + 42, null, "").encode()));
        observe("source-gamma", 10, -4_001, 43);
        round10 = client.round(FEED, 10, null);
        // Round 11 (open): an observation whose signed time belongs to round 10.
        applied(client.observe(signer("source-gamma"), FEED, 11,
                new FeedValues.ObservationValue(-1_800, SPEC.roundStart(10) + 50, null, "")));
        round11 = client.round(FEED, 11, null);

        // Round 12: a wrong record pushed past the approvers' check; the verifier must catch it.
        observe("source-alpha", 12, -1_800, 50);
        observe("source-beta", 12, -1_800, 51);
        FeedClient.Computation honest = client.compute(FEED, 12, client.tipHeight());
        FeedValues.RoundValue wrong = new FeedValues.RoundValue(FeedStarterProfile.ROUND_CLOSED,
                honest.feed().height(), -1_799, 2, honest.result().acceptedSources(),
                honest.policySha256(), honest.datumSha256());
        RoundRequest bogus = client.propose(signer("ops-a"), FEED, 12, wrong);
        assertThatThrownBy(() -> client.approveRound(signer("publisher-a"), bogus))
                .isInstanceOf(FeedException.class).hasMessageContaining("disagrees");
        client.decide(signer("publisher-a"), bogus, true);
        client.decide(signer("publisher-b"), bogus, true);
        applied(client.applyRound(bogus));
        round12 = client.round(FEED, 12, null);

        // Round 15: the honest result with a zeroed datum hash, pushed the same way; the verifier
        // reaches the datum check only when the result agrees.
        observe("source-alpha", 15, -1_810, 20);
        observe("source-beta", 15, -1_812, 21);
        FeedClient.Computation honest15 = client.compute(FEED, 15, client.tipHeight());
        RoundRequest wrongDatum = client.propose(signer("ops-a"), FEED, 15, new FeedValues.RoundValue(
                FeedStarterProfile.ROUND_CLOSED, honest15.feed().height(), honest15.result().aggregate(), 2,
                honest15.result().acceptedSources(), honest15.policySha256(), new byte[32]));
        assertThatThrownBy(() -> client.approveRound(signer("publisher-b"), wrongDatum))
                .isInstanceOf(FeedException.class).hasMessageContaining("disagrees");
        client.decide(signer("publisher-a"), wrongDatum, true);
        client.decide(signer("publisher-b"), wrongDatum, true);
        applied(client.applyRound(wrongDatum));
        round15 = client.round(FEED, 15, null);

        // A proposal that is never applied, for the golden request; then the feed is paused.
        observe("source-alpha", 14, -1_805, 52);
        observe("source-beta", 14, -1_806, 53);
        goldenRequest = client.proposeRound(signer("ops-a"), FEED, 14, null);

        portal = PortalService.start(client, new InetSocketAddress("127.0.0.1", 0));
        Map<String, byte[]> seeds = new LinkedHashMap<>();
        for (String actorId : FeedGenesis.DEMO_ACTOR_IDS) {
            seeds.put(actorId, seed(actorId));
        }
        gateway = GatewayService.start(client, seeds, null, null, new InetSocketAddress("127.0.0.1", 0), false);
    }

    @AfterAll
    void stop() {
        if (gateway != null) gateway.close();
        if (portal != null) portal.close();
        if (bridge != null) bridge.close();
        if (cluster != null) cluster.close();
    }

    @Test
    void closedRoundVerifiesUnderEveryTrustInput() {
        assertThat(round7.status()).isEqualTo("CLOSED");
        assertThat(round7.observationHeight()).isEqualTo(request7.recordValue().closedAtHeight());
        assertThat(round7.recordHeight()).isGreaterThan(round7.observationHeight());
        assertThat(round7.observations()).hasSize(3);
        assertThat(sameOrganizationApply.errorName()).isEqualTo("APPROVAL_NOT_APPROVED");

        FeedVerifier.Verification pinnedResult = FeedVerifier.verify(round7, pinned);
        assertThat(pinnedResult.failures()).isEmpty();
        assertThat(pinnedResult.consistent()).isTrue();
        assertThat(pinnedResult.flags()).isEmpty();
        assertThat(pinnedResult.trustLevel()).isEqualTo(ProofLabVocabulary.TrustLevel.CALLER_PINNED_ROOT);
        assertThat(pinnedResult.certSignatures()).isGreaterThanOrEqualTo(cluster.threshold());
        assertThat(pinnedResult.checks()).anyMatch(check -> check.contains("consumed once"));
        assertThat(pinnedResult.checks()).anyMatch(check -> check.contains("agrees with the recomputation"));
        assertThat(pinnedResult.result().aggregate()).isEqualTo(-1_825);
        assertThat(pinnedResult.result().acceptedSources()).containsExactly("source-alpha", "source-beta");
        assertThat(pinnedResult.result().source("source-gamma").disposition()).isEqualTo(Aggregation.Disposition.OUTLIER);
        assertThat(pinnedResult.record().datumSha256()).hasSize(32);
        assertThat(pinnedResult.answers()).hasSize(5);

        FeedVerifier.Verification declared = FeedVerifier.verify(round7, AttestTrust.bundleDeclared());
        assertThat(declared.consistent()).isTrue();
        assertThat(declared.trustLevel()).isEqualTo(ProofLabVocabulary.TrustLevel.INTERNAL_CONSISTENCY_ONLY);

        RoundBundle decoded = RoundBundle.fromJson(round7.toJson());
        assertThat(decoded.toJson()).isEqualTo(round7.toJson());
        assertThat(FeedVerifier.verify(decoded, pinned).consistent()).isTrue();
        // The canonical bundle of a closed round does not change as the chain grows.
        assertThat(client.round(FEED, 7, null).toJson()).isEqualTo(round7.toJson());

        ObjectNode view = RoundView.of(round7);
        assertThat(view.path("status").asText()).isEqualTo("CLOSED");
        assertThat(view.path("recomputed").path("decimal").asText()).isEqualTo("-18.25");
        assertThat(view.path("record").path("agreesWithRecomputation").asBoolean()).isTrue();
        assertThat(view.path("record").path("approvalConsumption").asBoolean()).isTrue();
        assertThat(view.path("record").path("provenance").path("kind").asText()).isEqualTo("RECEIPT");
        assertThat(view.path("sources")).extracting(node -> node.path("disposition").asText())
                .containsExactly("ACCEPTED", "ACCEPTED", "OUTLIER");
        assertThat(view.path("sources").get(0).path("provenance").path("actorId").asText()).isEqualTo("source-alpha");
        assertThat(view.path("datum").path("bindsRecord").asBoolean()).isTrue();
        assertThat(view.path("datum").path("hex").asText()).startsWith("d8798a");
    }

    @Test
    void lateObservationsDoNotChangeTheRecordButShowUpWhenReAnswered() {
        FeedVerifier.Verification canonical = FeedVerifier.verify(round8, pinned);
        assertThat(canonical.failures()).isEmpty();
        assertThat(canonical.result().source("source-gamma").disposition()).isEqualTo(Aggregation.Disposition.ABSENT);
        assertThat(canonical.record().aggregate()).isEqualTo(-1_830);
        assertThat(round8.observationHeight()).isEqualTo(h0Of8);

        FeedVerifier.Verification later = FeedVerifier.verify(round8Later, pinned);
        assertThat(later.failures()).isEmpty();
        assertThat(later.flags()).containsExactly(FeedVerifier.FLAG_LATER_HEIGHT);
        assertThat(later.result().source("source-gamma").disposition()).isEqualTo(Aggregation.Disposition.ACCEPTED);
        assertThat(later.result().aggregate()).isEqualTo(-1_825);
        assertThat(later.checks()).anyMatch(check -> check.contains("DIFFERS"));
        assertThat(RoundView.of(round8Later).path("record").path("sameHeight").asBoolean()).isFalse();

        FeedVerifier.Verification noQuorum = FeedVerifier.verify(round9, pinned);
        assertThat(noQuorum.failures()).isEmpty();
        assertThat(noQuorum.status()).isEqualTo("NO_QUORUM");
        assertThat(noQuorum.record().datumSha256()).isEmpty();
        assertThat(RoundView.of(round9).path("datum").isNull()).isTrue();
    }

    @Test
    void openRoundsExposeEveryDispositionAndWrongRecordsAreCaught() {
        FeedVerifier.Verification open = FeedVerifier.verify(round10, pinned);
        assertThat(open.failures()).isEmpty();
        assertThat(open.status()).isEqualTo("OPEN");
        assertThat(open.result().statusName()).isEqualTo("NO_QUORUM");
        assertThat(open.result().source("source-alpha").disposition()).isEqualTo(Aggregation.Disposition.EQUIVOCATED);
        assertThat(open.result().source("source-beta").disposition()).isEqualTo(Aggregation.Disposition.FOREIGN_WRITER);
        assertThat(open.result().source("source-gamma").disposition()).isEqualTo(Aggregation.Disposition.OUT_OF_RANGE);
        assertThat(round10.recordHeight()).isEqualTo(round10.observationHeight());
        assertThat(FeedVerifier.verify(round11, pinned).result().source("source-gamma").disposition())
                .isEqualTo(Aggregation.Disposition.WRONG_ROUND);

        FeedVerifier.Verification wrong = FeedVerifier.verify(round12, pinned);
        assertThat(wrong.consistent()).isFalse();
        assertThat(wrong.flags()).contains(FeedVerifier.FLAG_RECORD_DISAGREES);
        assertThat(wrong.failures()).anyMatch(failure -> failure.contains("disagrees with the recomputation"));
        assertThat(wrong.checks()).anyMatch(check -> check.contains("consumed once"));
        assertThat(RoundView.of(round12).path("record").path("agreesWithRecomputation").asBoolean()).isFalse();
        assertThat(RoundView.of(round12).path("datum").isNull()).isTrue();

        FeedVerifier.Verification wrongDatum = FeedVerifier.verify(round15, pinned);
        assertThat(wrongDatum.consistent()).isFalse();
        assertThat(wrongDatum.flags()).containsExactly(FeedVerifier.FLAG_DATUM_MISMATCH);
        assertThat(wrongDatum.failures()).anyMatch(failure -> failure.contains("datum hash"));
        assertThat(wrongDatum.result().aggregate()).isEqualTo(-1_812);
        assertThat(RoundView.of(round15).path("record").path("agreesWithRecomputation").asBoolean()).isTrue();
        assertThat(RoundView.of(round15).path("datum").path("bindsRecord").asBoolean()).isFalse();

        // A paused feed closes no rounds, and the feed update is a compare-and-set on the read revision.
        applied(client.updateFeed(signer("feed-admin-a"), FEED, SPEC.withStatus(FeedStarterProfile.FEED_PAUSED)));
        assertThatThrownBy(() -> client.proposeRound(signer("ops-a"), FEED, 13, null))
                .isInstanceOf(FeedException.class).hasMessageContaining("paused");
        assertThat(FeedVerifier.verify(round7, pinned).consistent()).as("history unaffected").isTrue();
        applied(client.updateFeed(signer("feed-admin-a"), FEED, SPEC));
        // The latest round with a record, probed backwards from the clock's round 13, is the wrong round 12.
        RoundBundle latest = client.latestRound(FEED);
        assertThat(latest).isNotNull();
        assertThat(latest.round()).isEqualTo(12);
    }

    @Test
    void wrongMembersTamperingAndForeignBundlesFail() {
        AttestTrust.CallerPinned strangers = new AttestTrust.CallerPinned(cluster.chainId(),
                Set.of("aa".repeat(32), "bb".repeat(32)), 1, null, null);
        assertThat(FeedVerifier.verify(round7, strangers).consistent()).isFalse();

        // A changed observation value breaks its proof.
        StatusAnswer observation = round7.observations().getFirst();
        byte[] forged = new FeedValues.ObservationValue(-1_700, SPEC.roundStart(7) + 10, null, "").encode();
        StatusAnswer.Entry original = observation.entry();
        StatusAnswer.Entry tampered = new StatusAnswer.Entry(original.status(), original.revision(),
                original.controller(), forged, AuthenticatedMapContract.logicalValueHash(forged),
                original.createdHeight(), original.lastMutationHeight());
        StatusAnswer forgedAnswer = new StatusAnswer(observation.chainId(), observation.profile(),
                observation.genesisIdHex(), observation.height(), observation.stateRootHex(),
                observation.blockHashHex(), observation.collection(), observation.key(), observation.presence(),
                tampered, observation.provenance(), observation.actionCommitment(),
                observation.authorizationEvidence(),
                observation.facts().stream().map(fact -> fact.name().equals("entry")
                        ? new StatusAnswer.Fact("entry", fact.expectedKey(), tampered.encode(), fact.proofJson())
                        : fact).toList(), observation.evidenceJson());
        RoundBundle forgedBundle = new RoundBundle(round7.chainId(), round7.profile(), round7.genesisIdHex(),
                round7.feedId(), round7.round(), round7.observationHeight(), round7.feed(),
                List.of(forgedAnswer, round7.observations().get(1), round7.observations().get(2)), round7.record());
        FeedVerifier.Verification forgedResult = FeedVerifier.verify(forgedBundle, pinned);
        assertThat(forgedResult.consistent()).isFalse();
        assertThat(forgedResult.failures()).isNotEmpty();

        // The record stripped of its approval consumption fact is refused.
        StatusAnswer record = round7.record();
        StatusAnswer stripped = new StatusAnswer(record.chainId(), record.profile(), record.genesisIdHex(),
                record.height(), record.stateRootHex(), record.blockHashHex(), record.collection(), record.key(),
                record.presence(), record.entry(), record.provenance(), record.actionCommitment(),
                record.authorizationEvidence(),
                record.facts().stream().filter(fact -> !fact.name().equals(FeedClient.APPROVAL_CONSUMPTION_FACT)).toList(),
                record.evidenceJson());
        RoundBundle strippedBundle = new RoundBundle(round7.chainId(), round7.profile(), round7.genesisIdHex(),
                round7.feedId(), round7.round(), round7.observationHeight(), round7.feed(), round7.observations(), stripped);
        FeedVerifier.Verification strippedResult = FeedVerifier.verify(strippedBundle, pinned);
        assertThat(strippedResult.consistent()).isFalse();
        assertThat(strippedResult.flags()).contains(FeedVerifier.FLAG_UNPROVEN_APPROVAL);

        // Answers of another feed or round cannot be relabelled.
        ObjectNode relabelled = round7.toJsonNode();
        relabelled.put("round", 8);
        FeedVerifier.Verification relabelledResult = FeedVerifier.verify(RoundBundle.fromJsonNode(relabelled), pinned);
        assertThat(relabelledResult.consistent()).isFalse();
        assertThat(relabelledResult.failures()).anyMatch(failure -> failure.contains("is not"));
        ObjectNode otherFeed = round7.toJsonNode();
        otherFeed.put("feedId", SECOND_FEED);
        assertThat(FeedVerifier.verify(RoundBundle.fromJsonNode(otherFeed), pinned).consistent()).isFalse();
        // An observation answer swapped for round 8's is a different key.
        RoundBundle swapped = new RoundBundle(round7.chainId(), round7.profile(), round7.genesisIdHex(),
                round7.feedId(), round7.round(), round7.observationHeight(), round7.feed(),
                List.of(round8.observations().getFirst(), round7.observations().get(1), round7.observations().get(2)),
                round7.record());
        assertThat(FeedVerifier.verify(swapped, pinned).consistent()).isFalse();
    }

    @Test
    void portalServesFeedsRoundsProofsAndDatums() throws Exception {
        JsonNode health = get(portal.baseUrl() + "/healthz");
        assertThat(health.path("chainId").asText()).isEqualTo(cluster.chainId());
        JsonNode feeds = get(portal.baseUrl() + "/feeds");
        assertThat(feeds.path("feeds")).extracting(JsonNode::asText).contains(FEED);
        JsonNode feed = get(portal.baseUrl() + "/feeds/" + FEED);
        assertThat(feed.path("feed").path("unit").asText()).isEqualTo("degC");
        assertThat(feed.path("feed").path("sources")).hasSize(3);
        assertThat(feed.path("roundsWithRecords")).extracting(JsonNode::asLong).contains(7L, 8L, 9L, 12L);
        assertThat(feed.path("currentRoundByPortalClock").asLong()).isEqualTo(13);

        JsonNode view = get(portal.baseUrl() + "/feeds/" + FEED + "/rounds/7");
        assertThat(view.path("status").asText()).isEqualTo("CLOSED");
        assertThat(view.path("record").path("agreesWithRecomputation").asBoolean()).isTrue();
        JsonNode proof = get(portal.baseUrl() + "/feeds/" + FEED + "/rounds/7/proof");
        RoundBundle served = RoundBundle.fromJsonNode(proof);
        assertThat(served.toJson()).isEqualTo(round7.toJson());
        assertThat(FeedVerifier.verify(served, pinned).consistent()).isTrue();
        JsonNode datum = get(portal.baseUrl() + "/feeds/" + FEED + "/rounds/7/datum");
        assertThat(datum.path("bindsRecord").asBoolean()).isTrue();
        assertThat(datum.path("sha256").asText()).isEqualTo(HEX.formatHex(round7.recordValue().datumSha256()));
        assertThat(status(portal.baseUrl() + "/feeds/" + FEED + "/rounds/9/datum")).isEqualTo(404);
        JsonNode open = get(portal.baseUrl() + "/feeds/" + FEED + "/rounds/10");
        assertThat(open.path("status").asText()).isEqualTo("OPEN");
        assertThat(open.path("recomputed").path("status").asText()).isEqualTo("NO_QUORUM");
        JsonNode later = get(portal.baseUrl() + "/feeds/" + FEED + "/rounds/8?height=" + round8Later.observationHeight());
        assertThat(later.path("record").path("sameHeight").asBoolean()).isFalse();
        JsonNode latest = get(portal.baseUrl() + "/feeds/" + FEED + "/latest");
        assertThat(latest.path("round").asLong()).isEqualTo(12);
        assertThat(status(portal.baseUrl() + "/feeds/" + FEED + "/rounds/999")).isEqualTo(200);
        assertThat(status(portal.baseUrl() + "/feeds/nope")).isEqualTo(404);
        assertThat(status(portal.baseUrl() + "/feeds/nope/rounds/1")).isEqualTo(404);
        assertThat(status(portal.baseUrl() + "/feeds/Bad")).isEqualTo(400);
        assertThat(status(portal.baseUrl() + "/feeds/" + FEED + "/rounds/x")).isEqualTo(400);
        assertThat(status(portal.baseUrl() + "/nope")).isEqualTo(404);
        HttpResponse<String> post = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(portal.baseUrl() + "/feeds/" + FEED))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(405);
    }

    @Test
    void gatewayPerformsEveryWriteForTheConsole() throws Exception {
        HttpResponse<String> unauthenticated = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(gateway.baseUrl() + "/operator/actors")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        JsonNode actors = gatewayGet("/operator/actors");
        assertThat(actors.path("actors")).hasSize(FeedGenesis.DEMO_ACTOR_IDS.size());
        assertThat(actors.path("actors").get(1).path("roles")).extracting(JsonNode::asText).contains("source");

        ObjectNode create = JSON.createObjectNode();
        create.put("actorId", "feed-admin-a");
        create.put("feedId", SECOND_FEED);
        ObjectNode spec = create.putObject("feed");
        spec.put("description", "Cold store 7 relative humidity");
        spec.put("unit", "%RH");
        spec.put("scale", 1);
        spec.put("epochStart", EPOCH);
        spec.put("roundSeconds", 60);
        spec.putArray("sources").add("source-alpha").add("source-beta");
        spec.put("minimumSources", 2);
        spec.put("maximumDeviationPpm", 50_000);
        spec.put("maximumDeviationAbsolute", 5);
        spec.put("minimumValue", "0");
        spec.put("maximumValue", 1_000);
        assertThat(gatewayPost("/operator/feeds", create, 200).path("status").asText()).isEqualTo("APPLIED");

        for (String[] observation : List.of(new String[]{"source-alpha", "550"}, new String[]{"source-beta", "560"})) {
            ObjectNode observe = JSON.createObjectNode();
            observe.put("actorId", observation[0]);
            observe.put("feedId", SECOND_FEED);
            observe.put("value", observation[1]);
            observe.put("observedAt", EPOCH + 5 * 60 + 7);
            observe.put("note", "from the console");
            JsonNode observed = gatewayPost("/source/observe", observe, 200);
            assertThat(observed.path("status").asText()).isEqualTo("APPLIED");
            assertThat(observed.path("round").asLong()).isEqualTo(5);
        }

        ObjectNode propose = JSON.createObjectNode();
        propose.put("actorId", "ops-a");
        propose.put("feedId", SECOND_FEED);
        propose.put("round", 5);
        JsonNode proposed = gatewayPost("/operator/rounds/propose", propose, 200);
        JsonNode request = proposed.path("request");
        assertThat(request.path("type").asText()).isEqualTo(RoundRequest.TYPE);
        assertThat(request.path("record").path("decimal").asText()).isEqualTo("55.0");
        assertThat(proposed.path("dispositions").path("source-beta").asText()).isEqualTo("ACCEPTED");
        for (String publisher : List.of("publisher-a", "publisher-b")) {
            ObjectNode approve = JSON.createObjectNode();
            approve.put("actorId", publisher);
            approve.set("request", request);
            assertThat(gatewayPost("/operator/rounds/approve", approve, 200).path("decision").asText())
                    .isEqualTo("APPROVE");
        }
        ObjectNode apply = JSON.createObjectNode();
        apply.set("request", request);
        assertThat(gatewayPost("/operator/rounds/apply", apply, 200).path("status").asText()).isEqualTo("APPLIED");

        ObjectNode unknownActor = JSON.createObjectNode();
        unknownActor.put("actorId", "nobody");
        unknownActor.put("feedId", SECOND_FEED);
        unknownActor.put("value", 1);
        assertThat(gatewayPost("/source/observe", unknownActor, 400).path("error").asText()).contains("no seed");
        ObjectNode missing = JSON.createObjectNode();
        missing.put("actorId", "source-alpha");
        assertThat(gatewayPost("/source/observe", missing, 400).path("error").asText()).contains("required");
        ObjectNode unauthorizedRole = JSON.createObjectNode();
        unauthorizedRole.put("actorId", "ops-a");
        unauthorizedRole.put("feedId", SECOND_FEED);
        unauthorizedRole.put("value", 1);
        unauthorizedRole.put("round", 6);
        assertThat(gatewayPost("/source/observe", unauthorizedRole, 200).path("status").asText()).isEqualTo("REJECTED");

        RoundBundle second = client.round(SECOND_FEED, 5, null);
        assertThat(second.status()).isEqualTo("CLOSED");
        assertThat(second.recordValue().aggregate()).isEqualTo(550);
        assertThat(FeedVerifier.verify(second, pinned).consistent()).isTrue();
        assertThat(get(portal.baseUrl() + "/feeds").path("feeds")).extracting(JsonNode::asText).contains(SECOND_FEED);
    }

    @Test
    void goldenFixturesStayVerifiable() throws Exception {
        String membersJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                JSON.createObjectNode()
                        .put("chainId", cluster.chainId())
                        .put("threshold", cluster.threshold())
                        .set("memberKeysHex", JSON.valueToTree(cluster.memberKeysHex())));
        if (Boolean.getBoolean("yano.feed.golden.write")) {
            for (String property : List.of("yano.feed.golden.dir", "yano.feed.cli.golden.dir", "yano.feed.ui.golden.dir")) {
                Path dir = Path.of(System.getProperty(property));
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("golden-round.json"), round7.toJson() + System.lineSeparator());
                Files.writeString(dir.resolve("golden-open-round.json"), round10.toJson() + System.lineSeparator());
                Files.writeString(dir.resolve("golden-wrong-round.json"), round12.toJson() + System.lineSeparator());
                Files.writeString(dir.resolve("golden-members.json"), membersJson + System.lineSeparator());
                Files.writeString(dir.resolve("golden-round-request.json"), goldenRequest.toJson() + System.lineSeparator());
            }
        }
        Path golden = Path.of(System.getProperty("yano.feed.golden.dir"));
        if (!Files.exists(golden.resolve("golden-round.json"))) {
            return;
        }
        RoundBundle committed = RoundBundle.fromJson(Files.readString(golden.resolve("golden-round.json")));
        AttestTrust.CallerPinned committedMembers = AttestTrust.CallerPinned.fromJson(
                Files.readString(golden.resolve("golden-members.json")));
        FeedVerifier.Verification result = FeedVerifier.verify(committed, committedMembers);
        assertThat(result.failures()).isEmpty();
        assertThat(result.consistent()).isTrue();
        assertThat(result.trustLevel()).isEqualTo(ProofLabVocabulary.TrustLevel.CALLER_PINNED_ROOT);
        assertThat(result.result().aggregate()).isEqualTo(-1_825);
        FeedVerifier.Verification wrong = FeedVerifier.verify(
                RoundBundle.fromJson(Files.readString(golden.resolve("golden-wrong-round.json"))), committedMembers);
        assertThat(wrong.flags()).contains(FeedVerifier.FLAG_RECORD_DISAGREES);
        assertThat(FeedVerifier.verify(RoundBundle.fromJson(
                Files.readString(golden.resolve("golden-open-round.json"))), committedMembers).consistent()).isTrue();
        assertThat(RoundRequest.fromJson(Files.readString(golden.resolve("golden-round-request.json"))).consistent()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private void observe(String source, long round, long value, long offsetSeconds) {
        applied(client.observe(signer(source), FEED, round,
                new FeedValues.ObservationValue(value, SPEC.roundStart(round) + offsetSeconds, null, "")));
    }

    private static byte[] seed(String actorId) {
        return FeedGenesis.demoActorSeed(actorId);
    }

    private static FeedClient.Signer signer(String actorId) {
        return FeedClient.Signer.of(actorId, seed(actorId));
    }

    private static FeedClient.WriteResult applied(FeedClient.WriteResult result) {
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
