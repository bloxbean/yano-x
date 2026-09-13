package org.yanoproject.x.dpp.client;

import org.yanoproject.x.dpp.profile.DppStarterProfile;
import org.yanoproject.x.dpp.profile.DppValues;
import org.yanoproject.x.roles.contracts.ActorRecordV1;
import org.yanoproject.x.trust.client.TrustRegistryException;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.yanoproject.x.dpp.client.HttpSupport.JSON;
import static org.yanoproject.x.dpp.client.HttpSupport.error;
import static org.yanoproject.x.dpp.client.HttpSupport.respond;

/**
 * The operator gateway (ADR-051 §2.4): ADR-026 §4.2's "DPP gateway" in miniature. It signs
 * with the seeds of the actors it was started with, on the operator's machine, for the console.
 * Loopback by default, a random bearer token on every request, JSON in and out, and every
 * write answered with its receipt. It never returns a seed.
 */
public final class GatewayService implements AutoCloseable {
    public static final String TOKEN_HEADER = "X-Gateway-Token";
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpServer server;
    private final DppClient client;
    private final DocumentStore documents;
    private final Map<String, byte[]> seeds;
    private final String token;
    private final String genesisIdHexOverride;

    private GatewayService(HttpServer server, DppClient client, DocumentStore documents,
                           Map<String, byte[]> seeds, String token, String genesisIdHexOverride) {
        this.server = server;
        this.client = client;
        this.documents = documents;
        this.seeds = seeds;
        this.token = token;
        this.genesisIdHexOverride = genesisIdHexOverride;
    }

    /**
     * @param seeds actor id to 32-byte seed; the map is copied and the caller's arrays are not
     *              zeroed here
     * @param token the bearer token, or null to generate one
     */
    public static GatewayService start(DppClient client, DocumentStore documents,
                                       Map<String, byte[]> seeds, String token,
                                       String genesisIdHexOverride, InetSocketAddress bind,
                                       boolean allowRemote) throws IOException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(documents, "documents");
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
        GatewayService service = new GatewayService(server, client, documents, copy, bearer,
                genesisIdHexOverride);
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
                node.put("prototype", DppStarterProfile.PROTOTYPE_NOTICE);
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
            JsonNode body = HttpSupport.readJson(exchange);
            switch (path) {
                case "/operator/products" -> respond(exchange, 200, HttpSupport.receiptNode(
                        client.registerProduct(signer(body), text(body, "productId"),
                                new DppValues.ProductValue(text(body, "manufacturerOrganizationId"),
                                        body.has("status") ? DppStarterProfile.statusCode(text(body, "status"))
                                                : DppStarterProfile.STATUS_DRAFT,
                                        0, "", text(body, "passportProfileId")))));
                case "/operator/versions" -> version(exchange, body);
                case "/operator/status" -> respond(exchange, 200, HttpSupport.receiptNode(
                        client.setStatus(signer(body), text(body, "productId"),
                                DppStarterProfile.statusCode(text(body, "status")),
                                body.path("successorProductId").asText(""))));
                case "/operator/revoke" -> respond(exchange, 200, HttpSupport.receiptNode(
                        client.revokeProduct(signer(body), text(body, "productId"))));
                case "/operator/claims" -> claim(exchange, body);
                case "/operator/events" -> respond(exchange, 200, HttpSupport.receiptNode(
                        client.appendEvent(signer(body), text(body, "productId"),
                                body.path("eventId").asText("").isBlank()
                                        ? eventId(body.path("observedAt").asLong(0)) : text(body, "eventId"),
                                new DppValues.EventValue(text(body, "eventType"),
                                        text(body, "actorOrganizationId"), body.path("observedAt").asLong(0),
                                        body.path("location").asText(""), digest(body, "evidenceSha256"),
                                        body.path("note").asText("")))));
                case "/operator/certifications/propose" -> propose(exchange, body);
                case "/operator/certifications/approve", "/operator/certifications/reject" -> {
                    CertificationRequest request = CertificationRequest.fromJsonNode(body.path("request"));
                    String messageId = client.decideCertification(signer(body), request,
                            path.endsWith("/approve"));
                    ObjectNode node = JSON.createObjectNode();
                    node.put("messageId", messageId);
                    node.put("decision", path.endsWith("/approve") ? "APPROVE" : "REJECT");
                    node.put("proposalId", request.proposalId());
                    respond(exchange, 200, node);
                }
                case "/operator/certifications/apply" -> respond(exchange, 200, HttpSupport.receiptNode(
                        client.applyCertification(CertificationRequest.fromJsonNode(body.path("request")))));
                default -> respond(exchange, 404, error("unknown path"));
            }
        } catch (IllegalArgumentException invalid) {
            respond(exchange, 400, error(invalid.getMessage()));
        } catch (DppException failure) {
            respond(exchange, failure.error() == DppException.Error.USAGE ? 400
                    : failure.error() == DppException.Error.INVALID ? 409 : 503, error(failure.getMessage()));
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

    private void version(HttpExchange exchange, JsonNode body) throws IOException {
        String documentBase64 = text(body, "documentBase64");
        byte[] document;
        try {
            document = Base64.getDecoder().decode(documentBase64);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("documentBase64 is not base64");
        }
        String sha256 = documents.put(document);
        DppValues.VersionValue value = new DppValues.VersionValue(HEX.parseHex(sha256),
                body.path("mediaType").asText("application/octet-stream"),
                body.path("reference").asText(""), document.length);
        ObjectNode node = HttpSupport.receiptNode(client.publishVersion(signer(body),
                text(body, "productId"), body.path("version").asLong(0), value));
        node.put("documentSha256", sha256);
        respond(exchange, 200, node);
    }

    private void claim(HttpExchange exchange, JsonNode body) throws IOException {
        String productId = text(body, "productId");
        String claimType = text(body, "claimType");
        String claimId = text(body, "claimId");
        String claimText = text(body, "text");
        boolean committed = body.path("committed").asBoolean(false);
        Disclosure disclosure = null;
        byte[] value;
        if (committed) {
            disclosure = Disclosure.create(client.chainId(), productId, claimType, claimId, claimText);
            value = disclosure.commitment();
        } else {
            value = claimText.getBytes(StandardCharsets.UTF_8);
        }
        DppValues.ClaimValue claim = new DppValues.ClaimValue(
                committed ? DppStarterProfile.VISIBILITY_COMMITTED : DppStarterProfile.VISIBILITY_PUBLIC,
                value, text(body, "issuerOrganizationId"), body.path("validFromHeight").asLong(0),
                body.path("validUntilHeight").asLong(0), digest(body, "evidenceSha256"));
        ObjectNode node = HttpSupport.receiptNode(client.putClaim(signer(body), productId, claimType,
                claimId, claim));
        if (disclosure != null) {
            // Returned once, to the operator who asked; the gateway keeps no copy.
            node.set("disclosure", disclosure.toJsonNode());
        }
        respond(exchange, 200, node);
    }

    private void propose(HttpExchange exchange, JsonNode body) throws IOException {
        CertificationRequest request;
        if (body.path("revoke").asBoolean(false)) {
            request = client.proposeRevocation(signer(body), text(body, "certificateId"));
        } else {
            request = client.proposeCertificate(signer(body), text(body, "certificateId"),
                    new DppValues.CertificateValue(text(body, "productId"), text(body, "certificateType"),
                            text(body, "issuerOrganizationId"), requireDigest(body, "evidenceSha256"),
                            body.path("validFromHeight").asLong(0), body.path("validUntilHeight").asLong(0)));
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("proposalId", request.proposalId());
        node.put("messageId", request.proposeMessageIdHex());
        node.set("request", request.toJsonNode());
        respond(exchange, 200, node);
    }

    private DppClient.Signer signer(JsonNode body) {
        String actorId = text(body, "actorId");
        byte[] seed = seeds.get(actorId);
        if (seed == null) {
            throw DppException.usage("the gateway holds no seed for actor " + actorId
                    + " (it signs for " + seeds.keySet() + ")");
        }
        return new DppClient.Signer(actorId, seed, genesisIdHexOverride,
                body.path("keyId").asText("").isBlank() ? null : body.path("keyId").asText());
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (!node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException("field " + field + " is required");
        }
        return node.textValue();
    }

    private static byte[] digest(JsonNode body, String field) {
        String hex = body.path(field).asText("");
        if (hex.isBlank()) return new byte[0];
        if (!hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("field " + field + " must be 32 bytes of lowercase hex");
        }
        return HEX.parseHex(hex);
    }

    private static byte[] requireDigest(JsonNode body, String field) {
        byte[] digest = digest(body, field);
        if (digest.length == 0) {
            throw new IllegalArgumentException("field " + field + " is required");
        }
        return digest;
    }

    static String eventId(long observedAt) {
        byte[] random = new byte[4];
        RANDOM.nextBytes(random);
        return "e-" + Math.max(observedAt, 0) + "-" + HEX.formatHex(random);
    }
}
