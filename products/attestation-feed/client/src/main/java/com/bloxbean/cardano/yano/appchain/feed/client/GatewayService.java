package com.bloxbean.cardano.yano.appchain.feed.client;

import com.bloxbean.cardano.yano.appchain.feed.profile.Aggregation;
import com.bloxbean.cardano.yano.appchain.feed.profile.FeedStarterProfile;
import com.bloxbean.cardano.yano.appchain.feed.profile.FeedValues;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static com.bloxbean.cardano.yano.appchain.feed.client.HttpSupport.JSON;
import static com.bloxbean.cardano.yano.appchain.feed.client.HttpSupport.error;
import static com.bloxbean.cardano.yano.appchain.feed.client.HttpSupport.respond;

/**
 * The signing gateway (ADR-052 §2.4): it signs with the seeds of the actors it was started
 * with, on the operator's machine, for the console. Loopback by default, a random bearer token
 * on every request, JSON in and out, every write answered with its receipt. It never returns a
 * seed, and it refuses to approve a round it cannot recompute.
 */
public final class GatewayService implements AutoCloseable {
    public static final String TOKEN_HEADER = "X-Gateway-Token";
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpServer server;
    private final FeedClient client;
    private final Map<String, byte[]> seeds;
    private final String token;
    private final String genesisIdHexOverride;

    private GatewayService(HttpServer server, FeedClient client, Map<String, byte[]> seeds,
                           String token, String genesisIdHexOverride) {
        this.server = server;
        this.client = client;
        this.seeds = seeds;
        this.token = token;
        this.genesisIdHexOverride = genesisIdHexOverride;
    }

    public static GatewayService start(FeedClient client, Map<String, byte[]> seeds, String token,
                                       String genesisIdHexOverride, InetSocketAddress bind,
                                       boolean allowRemote) throws IOException {
        Objects.requireNonNull(client, "client");
        if (!allowRemote && !bind.getAddress().isLoopbackAddress()) {
            throw new IllegalArgumentException("the gateway binds to loopback unless --allow-remote is given");
        }
        Map<String, byte[]> copy = new LinkedHashMap<>();
        seeds.forEach((actor, seed) -> {
            if (seed.length != 32) throw new IllegalArgumentException("seed of " + actor + " must be 32 bytes");
            copy.put(actor, seed.clone());
        });
        String bearer = token != null ? token : randomToken();
        HttpServer server = HttpServer.create(bind, 16);
        GatewayService service = new GatewayService(server, client, copy, bearer, genesisIdHexOverride);
        server.createContext("/", service::handle);
        server.start();
        return service;
    }

    /** Reads {@code <actorId>.seed} files (hex) from an owner-only directory. */
    public static Map<String, byte[]> loadSeeds(Path directory) throws IOException {
        Map<String, byte[]> seeds = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("seed directory " + directory + " does not exist");
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".seed") || !Files.isRegularFile(file)) continue;
                String hex = Files.readString(file).trim().toLowerCase(Locale.ROOT);
                if (!hex.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("seed file " + file + " must hold 32 bytes of hex");
                }
                seeds.put(name.substring(0, name.length() - ".seed".length()), HEX.parseHex(hex));
            }
        }
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException("no <actor>.seed files in " + directory);
        }
        return seeds;
    }

    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    public String token() {
        return token;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + port();
    }

    public List<String> actorIds() {
        return List.copyOf(seeds.keySet());
    }

    @Override
    public void close() {
        server.stop(0);
        seeds.values().forEach(seed -> java.util.Arrays.fill(seed, (byte) 0));
    }

    private void handle(HttpExchange exchange) throws IOException {
        HttpSupport.cors(exchange, "Content-Type, " + TOKEN_HEADER);
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (path == null || path.length() > HttpSupport.MAX_PATH_CHARS) {
                respond(exchange, 404, error("unknown path"));
                return;
            }
            if (path.equals("/healthz") && "GET".equals(method)) {
                ObjectNode node = JSON.createObjectNode();
                node.put("chainId", client.chainId());
                node.put("actors", seeds.size());
                node.put("starter", FeedStarterProfile.STARTER_NOTICE);
                respond(exchange, 200, node);
                return;
            }
            if (!authorized(exchange)) {
                respond(exchange, 401, error("missing or wrong " + TOKEN_HEADER));
                return;
            }
            if ("GET".equals(method) && path.equals("/operator/actors")) {
                respond(exchange, 200, actors());
                return;
            }
            if (!"POST".equals(method)) {
                respond(exchange, 405, error("POST the source and operator routes; GET /operator/actors"));
                return;
            }
            JsonNode body = HttpSupport.readJson(exchange);
            switch (path) {
                case "/source/observe" -> observe(exchange, body);
                case "/operator/feeds" -> feed(exchange, body);
                case "/operator/rounds/propose" -> propose(exchange, body);
                case "/operator/rounds/approve", "/operator/rounds/reject" -> {
                    RoundRequest request = RoundRequest.fromJsonNode(body.path("request"));
                    boolean approve = path.endsWith("/approve");
                    String messageId = approve ? client.approveRound(signer(body), request)
                            : client.rejectRound(signer(body), request);
                    ObjectNode node = JSON.createObjectNode();
                    node.put("messageId", messageId);
                    node.put("decision", approve ? "APPROVE" : "REJECT");
                    node.put("proposalId", request.proposalId());
                    respond(exchange, 200, node);
                }
                case "/operator/rounds/apply" -> respond(exchange, 200, HttpSupport.receiptNode(
                        client.applyRound(RoundRequest.fromJsonNode(body.path("request")))));
                default -> respond(exchange, 404, error("unknown path"));
            }
        } catch (IllegalArgumentException invalid) {
            respond(exchange, 400, error(invalid.getMessage()));
        } catch (FeedException failure) {
            respond(exchange, failure.error() == FeedException.Error.USAGE ? 400
                    : failure.error() == FeedException.Error.INVALID ? 409 : 503, error(failure.getMessage()));
        } catch (TrustRegistryException failure) {
            respond(exchange, failure.error() == TrustRegistryException.Error.INVALID ? 409 : 503,
                    error(failure.getMessage()));
        } catch (RuntimeException failure) {
            respond(exchange, 500, error("gateway failure: " + failure.getMessage()));
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        return supplied != null && MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private ObjectNode actors() {
        ObjectNode root = JSON.createObjectNode();
        root.put("chainId", client.chainId());
        ArrayNode actors = root.putArray("actors");
        boolean readable = client.tipHeight() >= 1;
        for (String actorId : seeds.keySet()) {
            ObjectNode node = actors.addObject();
            node.put("actorId", actorId);
            if (readable) {
                try {
                    ActorRecordV1 record = client.chain().actor(actorId);
                    node.put("organizationId", record.organizationId());
                    ArrayNode roles = node.putArray("roles");
                    record.roles().forEach(roles::add);
                } catch (RuntimeException unknown) {
                    node.put("note", "not readable on the chain: " + unknown.getMessage());
                }
            }
        }
        return root;
    }

    /**
     * {@code {actorId, feedId, value, observedAt?, round?, evidenceSha256?, note?}}: the round is
     * derived from {@code observedAt} and the feed's calendar unless given.
     */
    private void observe(HttpExchange exchange, JsonNode body) throws IOException {
        FeedClient.Signer signer = signer(body);
        String feedId = FeedStarterProfile.requireFeedId(text(body, "feedId"));
        long value = integer(body, "value");
        long observedAt = body.has("observedAt") ? integer(body, "observedAt")
                : client.clock().instant().getEpochSecond();
        long round;
        if (body.has("round")) {
            round = integer(body, "round");
        } else {
            FeedValues.FeedValue feed = FeedValues.FeedValue.decode(
                    client.entry(FeedStarterProfile.FEEDS, FeedStarterProfile.feedKey(feedId))
                            .orElseThrow(() -> FeedException.invalid("feed " + feedId + " is not defined")).value());
            round = feed.roundOf(observedAt);
        }
        FeedValues.ObservationValue observation = new FeedValues.ObservationValue(value, observedAt,
                digest(body, "evidenceSha256"), body.path("note").asText(""));
        ObjectNode node = HttpSupport.receiptNode(client.observe(signer, feedId, round, observation));
        node.put("feedId", feedId);
        node.put("round", round);
        node.put("sourceId", signer.actorId());
        respond(exchange, 200, node);
    }

    /** {@code {actorId, feedId, feed: {...}, update?}}: create with PUT_IF_ABSENT or update with compare-and-set. */
    private void feed(HttpExchange exchange, JsonNode body) throws IOException {
        String feedId = FeedStarterProfile.requireFeedId(text(body, "feedId"));
        JsonNode spec = body.path("feed");
        if (!spec.isObject()) {
            throw new IllegalArgumentException("field feed is required");
        }
        List<String> sources = new ArrayList<>();
        for (JsonNode source : spec.path("sources")) {
            sources.add(source.asText());
        }
        FeedValues.FeedValue value = new FeedValues.FeedValue(spec.path("description").asText(""),
                text(spec, "unit"), (int) integer(spec, "scale"), integer(spec, "epochStart"),
                integer(spec, "roundSeconds"), sources, (int) integer(spec, "minimumSources"),
                spec.path("maximumDeviationPpm").asLong(0), spec.path("maximumDeviationAbsolute").asLong(0),
                integer(spec, "minimumValue"), integer(spec, "maximumValue"),
                spec.has("status") ? FeedStarterProfile.feedStatusCode(spec.path("status").asText())
                        : FeedStarterProfile.FEED_ACTIVE);
        FeedClient.WriteResult result = body.path("update").asBoolean(false)
                ? client.updateFeed(signer(body), feedId, value)
                : client.createFeed(signer(body), feedId, value);
        respond(exchange, 200, HttpSupport.receiptNode(result));
    }

    /** {@code {actorId, feedId, round, height?}} → the request document and the result it proposes. */
    private void propose(HttpExchange exchange, JsonNode body) throws IOException {
        String feedId = FeedStarterProfile.requireFeedId(text(body, "feedId"));
        long round = integer(body, "round");
        Long height = body.has("height") ? integer(body, "height") : null;
        RoundRequest request = client.proposeRound(signer(body), feedId, round, height);
        ObjectNode node = JSON.createObjectNode();
        node.put("proposalId", request.proposalId());
        node.put("messageId", request.proposeMessageIdHex());
        node.set("request", request.toJsonNode());
        FeedValues.RoundValue record = request.recordValue();
        Aggregation.Result result = client.compute(feedId, round, record.closedAtHeight()).result();
        ObjectNode sources = node.putObject("dispositions");
        for (Aggregation.SourceResult source : result.sources()) {
            sources.put(source.sourceId(), source.disposition().name());
        }
        respond(exchange, 200, node);
    }

    private FeedClient.Signer signer(JsonNode body) {
        String actorId = text(body, "actorId");
        byte[] seed = seeds.get(actorId);
        if (seed == null) {
            throw FeedException.usage("the gateway holds no seed for actor " + actorId
                    + " (it signs for " + seeds.keySet() + ")");
        }
        return new FeedClient.Signer(actorId, seed, genesisIdHexOverride,
                body.path("keyId").asText("").isBlank() ? null : body.path("keyId").asText());
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (!node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException("field " + field + " is required");
        }
        return node.textValue();
    }

    /** An integer field given as a JSON number or a decimal string (browsers lose precision above 2^53). */
    private static long integer(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isTextual() && node.textValue().matches("-?[0-9]{1,19}")) {
            try {
                return Long.parseLong(node.textValue());
            } catch (NumberFormatException overflow) {
                throw new IllegalArgumentException("field " + field + " exceeds 64 bits");
            }
        }
        throw new IllegalArgumentException("field " + field + " must be an integer");
    }

    private static byte[] digest(JsonNode body, String field) {
        String hex = body.path(field).asText("");
        if (hex.isBlank()) return new byte[0];
        if (!hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("field " + field + " must be 32 bytes of lowercase hex");
        }
        return HEX.parseHex(hex);
    }
}
