package org.yanoproject.x.feed.profile;

import org.yanoproject.x.roles.contracts.ApprovalPolicyV1;
import org.yanoproject.x.roles.contracts.DirectRolePolicyV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapSchema;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The ADR-052 §2.1 attestation feed starter profile: three collections on the governed
 * authenticated map, their policies, and the genesis-bound schemas that filter malformed values
 * before finalization. Observations and round records are ordinary governed entries; the
 * aggregate is recomputed by every verifier ({@link Aggregation}), never on chain.
 */
public final class FeedStarterProfile {
    public static final String PROFILE_ID = "attestation-feed-starter-v1";
    public static final String STARTER_NOTICE =
            "Attestation feed starter: a configuration-only observation ledger on the stock "
                    + "authenticated map (ADR-052). Not the oracle pipeline of ADR app-layer/012; "
                    + "aggregates are recomputed by verifiers, nothing is published to Cardano.";

    public static final String FEEDS = "feeds";
    public static final String OBSERVATIONS = "observations";
    public static final String ROUNDS = "rounds";
    public static final List<String> COLLECTION_IDS = List.of(FEEDS, OBSERVATIONS, ROUNDS);

    public static final String FEED_ADMIN_ROLE = "feed-admin";
    public static final String SOURCE_ROLE = "source";
    public static final String OPERATOR_ROLE = "feed-operator";
    public static final String PUBLISHER_ROLE = "publisher";

    public static final String FEED_ADMIN_POLICY = "feed-admin-write";
    public static final String SOURCE_POLICY = "source-write";
    public static final String ROUND_POLICY = "round-close";
    public static final String ROUND_CLAUSE = "independent-publishers";
    public static final String AUTHORITY_ID = "feed-admins";

    public static final String FEED_SCHEMA = "feed-spec-v1";
    public static final String OBSERVATION_SCHEMA = "feed-observation-v1";
    public static final String ROUND_SCHEMA = "feed-round-record-v1";

    public static final int VALUE_VERSION = 1;
    public static final int MAX_KEY_BYTES = AuthenticatedMapContract.MAX_APPLICATION_KEY_BYTES;
    public static final int MAX_FEED_VALUE_BYTES = 2_048;
    public static final int MAX_OBSERVATION_VALUE_BYTES = 512;
    public static final int MAX_ROUND_VALUE_BYTES = 2_048;
    public static final int MAX_FEED_ID_BYTES = 32;
    public static final int MAX_SOURCE_ID_BYTES = 63;
    public static final int MAX_DESCRIPTION_BYTES = 256;
    public static final int MAX_UNIT_BYTES = 16;
    public static final int MAX_NOTE_BYTES = 256;
    public static final int MAX_SCALE = 18;
    public static final int MAX_SOURCES = 16;
    public static final long MAX_ROUND_SECONDS = 604_800;
    public static final long MAX_PPM = 1_000_000;
    public static final long MAX_EPOCH_SECONDS = 1L << 40;
    public static final long MAX_HEIGHT = 1L << 62;
    public static final long MAX_ROUND = Long.MAX_VALUE;
    /** Values are signed integers within ±(2^63 − 1) so every client computes exactly. */
    public static final long MAX_VALUE = Long.MAX_VALUE;
    public static final long MIN_VALUE = -Long.MAX_VALUE;
    public static final long DIRECT_AUTHORIZATION_LIFETIME_BLOCKS = 100;
    public static final long ROUND_LIFETIME_BLOCKS = 600;
    public static final long ADMINISTRATOR_LIFETIME_BLOCKS = 1_000;

    public static final int FEED_ACTIVE = 0;
    public static final int FEED_PAUSED = 1;
    public static final List<String> FEED_STATUS_NAMES = List.of("ACTIVE", "PAUSED");

    public static final int ROUND_CLOSED = 1;
    public static final int ROUND_NO_QUORUM = 2;

    public static final Pattern FEED_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,31}");
    public static final Pattern SOURCE_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern ROUND = Pattern.compile("0|[1-9][0-9]{0,18}");

    private FeedStarterProfile() {
    }

    /** Collections sorted by id, exactly as the map genesis retains them. */
    public static List<AuthenticatedMapContract.CollectionDescriptor> collections() {
        return List.of(
                new AuthenticatedMapContract.CollectionDescriptor(
                        FEEDS, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, FEED_ADMIN_POLICY,
                        false, MAX_KEY_BYTES, MAX_FEED_VALUE_BYTES,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, FEED_SCHEMA),
                new AuthenticatedMapContract.CollectionDescriptor(
                        OBSERVATIONS, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, SOURCE_POLICY,
                        false, MAX_KEY_BYTES, MAX_OBSERVATION_VALUE_BYTES,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, OBSERVATION_SCHEMA),
                new AuthenticatedMapContract.CollectionDescriptor(
                        ROUNDS, AuthenticatedMapContract.AUTH_APPROVAL, ROUND_POLICY,
                        false, MAX_KEY_BYTES, MAX_ROUND_VALUE_BYTES,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, ROUND_SCHEMA));
    }

    public static List<AuthenticatedMapContract.ValidatorDescriptor> validators() {
        return List.of(
                AuthenticatedMapContract.ValidatorDescriptor.schema(OBSERVATION_SCHEMA, observationSchema()),
                AuthenticatedMapContract.ValidatorDescriptor.schema(ROUND_SCHEMA, roundSchema()),
                AuthenticatedMapContract.ValidatorDescriptor.schema(FEED_SCHEMA, feedSchema()));
    }

    public static List<DirectRolePolicyV1> directPolicies() {
        return List.of(
                new DirectRolePolicyV1(FEED_ADMIN_POLICY, 1, RecordStatus.ACTIVE, FEED_ADMIN_ROLE,
                        DIRECT_AUTHORIZATION_LIFETIME_BLOCKS),
                new DirectRolePolicyV1(SOURCE_POLICY, 1, RecordStatus.ACTIVE, SOURCE_ROLE,
                        DIRECT_AUTHORIZATION_LIFETIME_BLOCKS));
    }

    /** A round close: a feed operator proposes, two publishers from distinct organizations approve. */
    public static ApprovalPolicyV1 roundPolicy() {
        return new ApprovalPolicyV1(
                ROUND_POLICY, 1, RecordStatus.ACTIVE, List.of(OPERATOR_ROLE),
                List.of(new ApprovalPolicyV1.RequiredClause(
                        ROUND_CLAUSE, PUBLISHER_ROLE, 2,
                        ApprovalPolicyV1.DistinctBy.ORGANIZATION)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, ROUND_LIFETIME_BLOCKS);
    }

    public static String policyOf(String collectionId) {
        return switch (collectionId) {
            case FEEDS -> FEED_ADMIN_POLICY;
            case OBSERVATIONS -> SOURCE_POLICY;
            case ROUNDS -> ROUND_POLICY;
            default -> throw new IllegalArgumentException("unknown feed collection " + collectionId);
        };
    }

    public static String roleOf(String collectionId) {
        return switch (policyOf(collectionId)) {
            case FEED_ADMIN_POLICY -> FEED_ADMIN_ROLE;
            case SOURCE_POLICY -> SOURCE_ROLE;
            default -> throw new IllegalArgumentException(
                    collectionId + " is written through the approval route, not a role");
        };
    }

    // ------------------------------------------------------------------ keys

    public static String requireFeedId(String feedId) {
        return require(feedId, FEED_ID, "feed id");
    }

    public static String requireSourceId(String sourceId) {
        return require(sourceId, SOURCE_ID, "source id");
    }

    public static long requireRound(long round) {
        if (round < 0) {
            throw new IllegalArgumentException("round must be a non-negative integer");
        }
        return round;
    }

    public static long parseRound(String text) {
        if (text == null || !ROUND.matcher(text).matches()) {
            throw new IllegalArgumentException("round must be a canonical non-negative decimal");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("round exceeds 2^63 - 1");
        }
    }

    public static int requireFeedStatus(int status) {
        if (status != FEED_ACTIVE && status != FEED_PAUSED) {
            throw new IllegalArgumentException("feed status must be 0 (ACTIVE) or 1 (PAUSED)");
        }
        return status;
    }

    public static int feedStatusCode(String name) {
        int index = FEED_STATUS_NAMES.indexOf(Objects.requireNonNull(name, "status").toUpperCase());
        if (index < 0) {
            throw new IllegalArgumentException("feed status must be one of " + FEED_STATUS_NAMES);
        }
        return index;
    }

    public static String feedStatusName(int status) {
        return FEED_STATUS_NAMES.get(requireFeedStatus(status));
    }

    public static int requireRoundStatus(int status) {
        if (status != ROUND_CLOSED && status != ROUND_NO_QUORUM) {
            throw new IllegalArgumentException("round status must be 1 (CLOSED) or 2 (NO_QUORUM)");
        }
        return status;
    }

    public static String roundStatusName(int status) {
        return requireRoundStatus(status) == ROUND_CLOSED ? "CLOSED" : "NO_QUORUM";
    }

    public static byte[] feedKey(String feedId) {
        return ascii(requireFeedId(feedId));
    }

    public static byte[] observationKey(String feedId, long round, String sourceId) {
        return ascii(requireFeedId(feedId) + "/" + requireRound(round) + "/" + requireSourceId(sourceId));
    }

    public static byte[] roundKey(String feedId, long round) {
        return ascii(requireFeedId(feedId) + "/" + requireRound(round));
    }

    public record ObservationKey(String feedId, long round, String sourceId) {
    }

    public record RoundKey(String feedId, long round) {
    }

    public static ObservationKey parseObservationKey(byte[] key) {
        String[] parts = split(key, 3, "observation key must be <feedId>/<round>/<sourceId>");
        return new ObservationKey(requireFeedId(parts[0]), parseRound(parts[1]), requireSourceId(parts[2]));
    }

    public static RoundKey parseRoundKey(byte[] key) {
        String[] parts = split(key, 2, "round key must be <feedId>/<round>");
        return new RoundKey(requireFeedId(parts[0]), parseRound(parts[1]));
    }

    /** The feed a key of {@code collection} belongs to, or null when the key is malformed. */
    public static String feedIdOf(String collection, byte[] key) {
        try {
            return switch (collection) {
                case FEEDS -> requireFeedId(text(key));
                case OBSERVATIONS -> parseObservationKey(key).feedId();
                case ROUNDS -> parseRoundKey(key).feedId();
                default -> null;
            };
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    public static String text(byte[] key) {
        return new String(Objects.requireNonNull(key, "key"), StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------ calendar

    /** {@code floor((observedAt - epochStart) / roundSeconds)}; observations before the epoch have no round. */
    public static long roundOf(long epochStart, long roundSeconds, long observedAt) {
        if (observedAt < epochStart) {
            throw new IllegalArgumentException("observedAt precedes the feed's epoch start");
        }
        return (observedAt - epochStart) / roundSeconds;
    }

    public static long roundStart(long epochStart, long roundSeconds, long round) {
        return Math.addExact(epochStart, Math.multiplyExact(requireRound(round), roundSeconds));
    }

    /** The last second of the round, {@code start(round + 1) - 1}. */
    public static long roundEnd(long epochStart, long roundSeconds, long round) {
        return Math.addExact(roundStart(epochStart, roundSeconds, round), roundSeconds - 1);
    }

    /** Whether a map genesis declares this profile: the three collections under its policies. */
    public static boolean matches(AuthenticatedMapContract.Genesis genesis) {
        if (genesis == null || genesis.governedGenesis() == null) return false;
        Map<String, AuthenticatedMapContract.CollectionDescriptor> declared =
                genesis.collections().stream().collect(Collectors.toMap(
                        AuthenticatedMapContract.CollectionDescriptor::id, descriptor -> descriptor,
                        (left, right) -> left));
        for (AuthenticatedMapContract.CollectionDescriptor expected : collections()) {
            AuthenticatedMapContract.CollectionDescriptor actual = declared.get(expected.id());
            if (actual == null
                    || actual.authorization() != expected.authorization()
                    || !actual.authorizationPolicyId().equals(expected.authorizationPolicyId())
                    || actual.restoreAllowed()
                    || actual.valueEncoding() != expected.valueEncoding()) {
                return false;
            }
        }
        return genesis.governedGenesis().approvalPolicy(ROUND_POLICY) != null
                && genesis.governedGenesis().directPolicy(FEED_ADMIN_POLICY) != null
                && genesis.governedGenesis().directPolicy(SOURCE_POLICY) != null;
    }

    // ------------------------------------------------------------------ schemas

    /**
     * {@code [1, description, unit, scale, epochStart, roundSeconds, sources[], minimumSources,
     * maximumDeviationPpm, maximumDeviationAbsolute, minimumValue, maximumValue, status]}.
     */
    static byte[] feedSchema() {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.ArrayNode(List.of(
                required(versionNode()),
                required(text(0, MAX_DESCRIPTION_BYTES)),
                required(text(1, MAX_UNIT_BYTES)),
                required(unsigned(0, MAX_SCALE)),
                required(unsigned(0, MAX_EPOCH_SECONDS)),
                required(unsigned(1, MAX_ROUND_SECONDS)),
                required(sourceList(1)),
                required(unsigned(1, MAX_SOURCES)),
                required(unsigned(0, MAX_PPM)),
                required(unsigned(0, MAX_VALUE)),
                required(signed()),
                required(signed()),
                required(unsigned(FEED_ACTIVE, FEED_PAUSED))))).definition();
    }

    /** {@code [1, value, observedAt, evidenceSha256, note]}. */
    static byte[] observationSchema() {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.ArrayNode(List.of(
                required(versionNode()),
                required(signed()),
                required(unsigned(0, MAX_EPOCH_SECONDS)),
                required(optionalDigest()),
                required(text(0, MAX_NOTE_BYTES))))).definition();
    }

    /** {@code [1, status, closedAtHeight, aggregate, scale, acceptedSources[], policySha256, datumSha256]}. */
    static byte[] roundSchema() {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.ArrayNode(List.of(
                required(versionNode()),
                required(unsigned(ROUND_CLOSED, ROUND_NO_QUORUM)),
                required(unsigned(1, MAX_HEIGHT)),
                required(signed()),
                required(unsigned(0, MAX_SCALE)),
                required(sourceList(0)),
                required(bytes(32, 32)),
                required(optionalDigest())))).definition();
    }

    private static AuthenticatedMapSchema.Node sourceList(int minimum) {
        return new AuthenticatedMapSchema.ArrayNode(List.of(new AuthenticatedMapSchema.Occurrence(
                minimum, MAX_SOURCES, text(1, MAX_SOURCE_ID_BYTES))));
    }

    private static AuthenticatedMapSchema.Occurrence required(AuthenticatedMapSchema.Node node) {
        return AuthenticatedMapSchema.Occurrence.required(node);
    }

    private static AuthenticatedMapSchema.Node versionNode() {
        return unsigned(VALUE_VERSION, VALUE_VERSION);
    }

    private static AuthenticatedMapSchema.Node text(int minimum, int maximum) {
        return new AuthenticatedMapSchema.TextNode(minimum, maximum, null);
    }

    private static AuthenticatedMapSchema.Node bytes(int minimum, int maximum) {
        return new AuthenticatedMapSchema.BytesNode(minimum, maximum, null);
    }

    private static AuthenticatedMapSchema.Node optionalDigest() {
        return new AuthenticatedMapSchema.ChoiceNode(List.of(bytes(0, 0), bytes(32, 32)));
    }

    private static AuthenticatedMapSchema.Node unsigned(long minimum, long maximum) {
        return new AuthenticatedMapSchema.IntegerNode(AuthenticatedMapSchema.INTEGER_UINT,
                BigInteger.valueOf(minimum), BigInteger.valueOf(maximum));
    }

    private static AuthenticatedMapSchema.Node signed() {
        return new AuthenticatedMapSchema.IntegerNode(AuthenticatedMapSchema.INTEGER_ANY,
                BigInteger.valueOf(MIN_VALUE), BigInteger.valueOf(MAX_VALUE));
    }

    private static String require(String value, Pattern pattern, String name) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + pattern.pattern());
        }
        return value;
    }

    private static byte[] ascii(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("key exceeds " + MAX_KEY_BYTES + " bytes");
        }
        return bytes;
    }

    private static String[] split(byte[] key, int parts, String message) {
        String[] split = text(key).split("/", -1);
        if (split.length != parts) {
            throw new IllegalArgumentException(message);
        }
        return split;
    }
}
