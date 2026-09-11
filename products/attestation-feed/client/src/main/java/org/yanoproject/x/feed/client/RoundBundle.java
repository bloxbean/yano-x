package org.yanoproject.x.feed.client;

import org.yanoproject.x.feed.profile.Aggregation;
import org.yanoproject.x.feed.profile.FeedStarterProfile;
import org.yanoproject.x.feed.profile.FeedValues;
import org.yanoproject.x.trust.client.AnswerCodec;
import org.yanoproject.x.trust.client.StatusAnswer;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The {@code feed-round-v1} document (ADR-052 §2.2): the feed record and one observation answer
 * per configured source at the observation height {@code h0}, and the round record answer at
 * the record height {@code H} (absent when the round is open, in which case {@code H = h0}).
 * Offline, {@link FeedVerifier} verifies the answers, recomputes the round, and compares.
 */
public record RoundBundle(
        String chainId,
        String profile,
        String genesisIdHex,
        String feedId,
        long round,
        long observationHeight,
        StatusAnswer feed,
        List<StatusAnswer> observations,
        StatusAnswer record
) {
    public static final String TYPE = "feed-round-v1";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    public static final int MAX_ANSWERS = 2 + FeedStarterProfile.MAX_SOURCES;
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_MALFORMED = "MALFORMED";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public RoundBundle {
        Objects.requireNonNull(chainId, "chainId");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(genesisIdHex, "genesisIdHex");
        FeedStarterProfile.requireFeedId(feedId);
        FeedStarterProfile.requireRound(round);
        Objects.requireNonNull(feed, "feed");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        Objects.requireNonNull(record, "record");
        if (observations.size() > FeedStarterProfile.MAX_SOURCES) {
            throw new IllegalArgumentException("a round carries at most "
                    + FeedStarterProfile.MAX_SOURCES + " observations");
        }
        if (observationHeight < 1) {
            throw new IllegalArgumentException("observation height must be positive");
        }
    }

    public long recordHeight() {
        return record.height();
    }

    /** The state root the observations were answered under (the feed answer's). */
    public String stateRootHex() {
        return feed.stateRootHex();
    }

    /** Every answer: the feed, the observations in feed order, then the record. */
    public List<StatusAnswer> answers() {
        List<StatusAnswer> all = new ArrayList<>();
        all.add(feed);
        all.addAll(observations);
        all.add(record);
        return List.copyOf(all);
    }

    /** The decoded feed record, or null when the feed answer carries no active entry. */
    public FeedValues.FeedValue feedValue() {
        if (feed.presence() != StatusAnswer.Presence.ACTIVE || feed.entry() == null) return null;
        try {
            return FeedValues.FeedValue.decode(feed.entry().value());
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** The decoded round record, or null when the round is open, revoked, or malformed. */
    public FeedValues.RoundValue recordValue() {
        if (record.presence() != StatusAnswer.Presence.ACTIVE || record.entry() == null) return null;
        try {
            return FeedValues.RoundValue.decode(record.entry().value());
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** {@code OPEN}, {@code CLOSED}, {@code NO_QUORUM}, {@code REVOKED}, or {@code MALFORMED}. */
    public String status() {
        return switch (record.presence()) {
            case ABSENT -> STATUS_OPEN;
            case REVOKED -> STATUS_REVOKED;
            case ACTIVE -> {
                FeedValues.RoundValue value = recordValue();
                yield value == null ? STATUS_MALFORMED : value.statusName();
            }
        };
    }

    /**
     * The aggregation input one observation answer represents for {@code sourceId}: the decoded
     * value, the tombstone flag, the writer the answer's provenance names, and the revision.
     */
    public static Aggregation.Input input(String sourceId, StatusAnswer answer) {
        if (answer.presence() == StatusAnswer.Presence.REVOKED) {
            return new Aggregation.Input(sourceId, null, true, null, 0);
        }
        if (answer.presence() != StatusAnswer.Presence.ACTIVE || answer.entry() == null) {
            return Aggregation.Input.absent(sourceId);
        }
        FeedValues.ObservationValue value;
        try {
            value = FeedValues.ObservationValue.decode(answer.entry().value());
        } catch (RuntimeException malformed) {
            return Aggregation.Input.absent(sourceId);
        }
        return new Aggregation.Input(sourceId, value, false, answer.provenance().actorId(),
                answer.entry().revision());
    }

    /** The recomputation from the bundle's own answers, or null when the feed is not decodable. */
    public Aggregation.Result recompute() {
        FeedValues.FeedValue value = feedValue();
        if (value == null || value.sources().size() != observations.size()) return null;
        List<Aggregation.Input> inputs = new ArrayList<>();
        for (int index = 0; index < observations.size(); index++) {
            inputs.add(input(value.sources().get(index), observations.get(index)));
        }
        try {
            return Aggregation.aggregate(value, round, inputs);
        } catch (RuntimeException mismatch) {
            return null;
        }
    }

    // ------------------------------------------------------------------ JSON

    public ObjectNode toJsonNode() {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("type", TYPE);
        root.put("chainId", chainId);
        root.put("profile", profile);
        root.put("genesisId", genesisIdHex);
        root.put("feedId", feedId);
        root.put("round", round);
        root.put("observationHeight", observationHeight);
        root.put("recordHeight", recordHeight());
        root.put("stateRoot", stateRootHex());
        root.put("status", status());
        root.put("starter", FeedStarterProfile.STARTER_NOTICE);
        ArrayNode answers = root.putArray("answers");
        for (StatusAnswer answer : answers()) {
            answers.add(AnswerCodec.toJsonNode(answer));
        }
        return root;
    }

    public String toJson() {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(toJsonNode());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static RoundBundle fromJson(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("round document exceeds " + MAX_JSON_BYTES + " bytes");
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("round document is not well-formed JSON", malformed);
        }
        return fromJsonNode(root);
    }

    public static RoundBundle fromJsonNode(JsonNode root) {
        if (root == null || !root.isObject()
                || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION
                || !TYPE.equals(root.path("type").asText())) {
            throw new IllegalArgumentException("round document has an unsupported identity");
        }
        JsonNode answersNode = root.path("answers");
        if (!answersNode.isArray() || answersNode.size() < 2 || answersNode.size() > MAX_ANSWERS) {
            throw new IllegalArgumentException("round document carries 2-" + MAX_ANSWERS + " answers");
        }
        StatusAnswer feed = null;
        StatusAnswer record = null;
        List<StatusAnswer> observations = new ArrayList<>();
        for (JsonNode node : answersNode) {
            StatusAnswer answer = AnswerCodec.fromJsonNode(node);
            switch (answer.collection()) {
                case FeedStarterProfile.FEEDS -> {
                    if (feed != null) throw new IllegalArgumentException("round document carries two feed answers");
                    feed = answer;
                }
                case FeedStarterProfile.OBSERVATIONS -> observations.add(answer);
                case FeedStarterProfile.ROUNDS -> {
                    if (record != null) throw new IllegalArgumentException("round document carries two record answers");
                    record = answer;
                }
                default -> throw new IllegalArgumentException(
                        "round document answers an unknown collection " + answer.collection());
            }
        }
        if (feed == null || record == null) {
            throw new IllegalArgumentException("round document carries no feed or no record answer");
        }
        return new RoundBundle(root.path("chainId").asText(), root.path("profile").asText(),
                root.path("genesisId").asText(), root.path("feedId").asText(), root.path("round").asLong(-1),
                root.path("observationHeight").asLong(), feed, observations, record);
    }
}
