package com.bloxbean.cardano.yano.appchain.trust.client;

import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.trust.profile.TrustRegistryProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The registry operator gateway (ADR-053 §2.2). It runs on the operator's machine with the actor
 * seeds, signs what the console asks for, and answers with the receipt. Loopback by default, a
 * bearer token on every operator request, and no route that returns a seed.
 *
 * <p>A gateway signs for every actor it was started with, so whoever holds its token can write as
 * any of them. That is the trust boundary the console names as GATEWAY mode; browser-held keys
 * (ADR-053 §2.1) narrow it to one actor and one tab.
 */
public final class RegistryGatewayService implements AutoCloseable {
    public static final String TOKEN_HEADER = "X-Gateway-Token";
    public static final int MAX_PATH_CHARS = 512;
    public static final int MAX_BODY_BYTES = 1 << 20;
    public static final int MAX_SCHEMA_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpServer server;
    private final RegistryWriter writer;
    private final Map<String, byte[]> seeds;
    private final String token;
    private final String genesisIdHexOverride;

    private RegistryGatewayService(HttpServer server, RegistryWriter writer,
                                   Map<String, byte[]> seeds, String token,
                                   String genesisIdHexOverride) {
        this.server = server;
        this.writer = writer;
        this.seeds = seeds;
        this.token = token;
        this.genesisIdHexOverride = genesisIdHexOverride;
    }

    /**
     * @param seeds actor id to 32-byte seed; copied in, and zeroed by {@link #close()}
     * @param token the bearer token, or null to generate one
     * @param genesisIdHexOverride the generated {@code state.genesis-id}, needed only before the
     *                             chain's first block
     */
    public static RegistryGatewayService start(RegistryWriter writer, Map<String, byte[]> seeds,
                                               String token, String genesisIdHexOverride,
                                               InetSocketAddress bind, boolean allowRemote)
            throws IOException {
        Objects.requireNonNull(writer, "writer");
        if (!allowRemote && !bind.getAddress().isLoopbackAddress()) {
            throw new IllegalArgumentException("the gateway binds to loopback unless --allow-remote is given");
        }
        Map<String, byte[]> copy = new LinkedHashMap<>();
        seeds.forEach((actor, seed) -> {
            if (seed.length != 32) throw new IllegalArgumentException("seed of " + actor + " must be 32 bytes");
            copy.put(actor, seed.clone());
        });
        if (copy.isEmpty()) throw new IllegalArgumentException("the gateway needs at least one seed");
        String bearer = token != null ? token : randomToken();
        HttpServer server = HttpServer.create(bind, 16);
        RegistryGatewayService service = new RegistryGatewayService(server, writer, copy, bearer,
                genesisIdHexOverride);
        server.createContext("/", service::handle);
        server.start();
        return service;
    }

    /** Reads {@code <actorId>.seed} files (hex) from a directory the operator owns. */
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
        if (seeds.isEmpty()) throw new IllegalArgumentException("no <actor>.seed files in " + directory);
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
        seeds.values().forEach(seed -> Arrays.fill(seed, (byte) 0));
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, " + TOKEN_HEADER);
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (path == null || path.length() > MAX_PATH_CHARS) {
                respond(exchange, 404, error("unknown path"));
                return;
            }
            if (path.equals("/healthz") && "GET".equals(method)) {
                ObjectNode node = JSON.createObjectNode();
                node.put("chainId", writer.chain().chainId());
                node.put("actors", seeds.size());
                node.put("tipHeight", writer.chain().tipHeight());
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
                respond(exchange, 405, error("POST the operator routes; GET /operator/actors"));
                return;
            }
            JsonNode body = readJson(exchange);
            switch (path) {
                case "/operator/status" -> respond(exchange, 200, receipt(writer.putStatus(
                        signer(body), text(body, "listId"), longValue(body, "index"),
                        (int) longValue(body, "bit"), (int) body.path("reasonCode").asLong(0))));
                case "/operator/status/revoke" -> respond(exchange, 200, receipt(writer.revokeStatus(
                        signer(body), text(body, "listId"), longValue(body, "index"))));
                case "/operator/subjects" -> respond(exchange, 200, receipt(writer.putSubject(
                        signer(body), text(body, "subjectId"), text(body, "controllerOrganizationId"),
                        text(body, "kind"), digest(body, "metadataHashHex"))));
                case "/operator/subjects/revoke" -> respond(exchange, 200, receipt(writer.revokeSubject(
                        signer(body), text(body, "subjectId"))));
                case "/operator/schemas" -> respond(exchange, 200, receipt(writer.putSchema(
                        signer(body), text(body, "schemaId"), schemaValue(body))));
                case "/operator/lists/publish" -> publish(exchange, body);
                default -> respond(exchange, 404, error("unknown path"));
            }
        } catch (IllegalArgumentException invalid) {
            respond(exchange, 400, error(invalid.getMessage()));
        } catch (TrustRegistryException failure) {
            int status = switch (failure.error()) {
                case USAGE, MALFORMED_RESPONSE -> 400;
                case INVALID -> 409;
                default -> 503;
            };
            respond(exchange, status, error(failure.getMessage()));
        } catch (RuntimeException failure) {
            respond(exchange, 500, error("gateway failure: " + failure.getMessage()));
        }
    }

    private void publish(HttpExchange exchange, JsonNode body) throws IOException {
        RegistryWriter.PublishedList published = writer.publishList(signer(body),
                text(body, "listId"), body.path("purpose").asText("revocation"),
                body.has("bitLength") ? longValue(body, "bitLength") : TrustRegistryProfile.MIN_BIT_LENGTH);
        ObjectNode node = receipt(published.write());
        node.put("replayedHeight", published.replayedHeight());
        node.put("setCount", published.setCount());
        node.put("listSha256", published.listSha256Hex());
        node.put("mutationCount", published.mutationCount());
        respond(exchange, 200, node);
    }

    private boolean authorized(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        return supplied != null && MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    /** The actors this gateway holds seeds for, with organization and roles read from the chain. */
    private ObjectNode actors() {
        ObjectNode root = JSON.createObjectNode();
        root.put("chainId", writer.chain().chainId());
        ArrayNode actors = root.putArray("actors");
        boolean readable = writer.chain().tipHeight() >= 1;
        for (String actorId : seeds.keySet()) {
            ObjectNode node = actors.addObject();
            node.put("actorId", actorId);
            if (readable) {
                try {
                    ActorRecordV1 record = writer.chain().actor(actorId);
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

    private RegistryWriter.Signer signer(JsonNode body) {
        String actorId = text(body, "actorId");
        byte[] seed = seeds.get(actorId);
        if (seed == null) {
            throw new IllegalArgumentException("this gateway holds no seed for " + actorId);
        }
        return new RegistryWriter.Signer(actorId, seed, genesisIdHexOverride,
                body.path("keyId").asText("").isBlank() ? null : body.path("keyId").asText());
    }

    private static byte[] schemaValue(JsonNode body) {
        String base64 = text(body, "valueBase64");
        byte[] value;
        try {
            value = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("valueBase64 is not base64");
        }
        if (value.length == 0 || value.length > MAX_SCHEMA_BYTES) {
            throw new IllegalArgumentException("a schema is 1 to " + MAX_SCHEMA_BYTES + " bytes");
        }
        return value;
    }

    private static ObjectNode receipt(RegistryWriter.WriteResult result) {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageId", result.messageIdHex());
        node.put("height", result.receipt().height());
        node.put("status", result.applied() ? "APPLIED" : "REJECTED");
        node.put("errorCode", result.receipt().errorCode());
        ArrayNode results = node.putArray("results");
        for (AuthenticatedMapContract.MutationResult mutation : result.receipt().results()) {
            ObjectNode row = results.addObject();
            row.put("collection", mutation.collectionId());
            row.put("key", new String(mutation.applicationKey(), StandardCharsets.US_ASCII));
            row.put("revision", mutation.revision());
            row.put("status", mutation.status() == AuthenticatedMapContract.STATUS_ACTIVE
                    ? "ACTIVE" : "REVOKED");
        }
        return node;
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return node.asText().trim();
    }

    private static long longValue(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (node.isMissingNode() || node.isNull()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException malformed) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
        }
        if (!node.isIntegralNumber()) throw new IllegalArgumentException(field + " must be an integer");
        return node.asLong();
    }

    private static byte[] digest(JsonNode body, String field) {
        String hex = text(body, field).toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be 32 bytes of hex");
        }
        return HEX.parseHex(hex);
    }

    private static JsonNode readJson(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("request body is larger than " + MAX_BODY_BYTES + " bytes");
            }
            if (bytes.length == 0) throw new IllegalArgumentException("a JSON body is required");
            return JSON.readTree(bytes);
        } catch (com.fasterxml.jackson.core.JacksonException malformed) {
            throw new IllegalArgumentException("the request body is not JSON");
        }
    }

    private static ObjectNode error(String message) {
        return JSON.createObjectNode().put("error", message == null ? "request refused" : message);
    }

    private static void respond(HttpExchange exchange, int status, ObjectNode body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
