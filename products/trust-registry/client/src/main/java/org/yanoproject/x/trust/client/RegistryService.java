package org.yanoproject.x.trust.client;

import org.yanoproject.x.trust.profile.StatusProjection;
import org.yanoproject.x.trust.profile.TrqpEvaluator;
import org.yanoproject.x.trust.profile.TrustRegistryProfile;
import org.yanoproject.x.trust.profile.TrustRegistryValues;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The read-only standards service (ADR-049 §2.3): Bitstring Status Lists and TRQP-shaped
 * answers rendered from a replayed projection and the chain's proof-bound entries. It never
 * signs, never writes, and never touches JSON-LD.
 */
public final class RegistryService implements AutoCloseable {
    public static final int MAX_PATH_CHARS = 512;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern STATUS_LIST = Pattern.compile("/status-lists/([^/]{1,64})");
    private static final Pattern TRQP = Pattern.compile(
            "/trqp/entities/([^/]{1,128})/authorizations/([^/]{1,128})");
    private static final Pattern ENTRY = Pattern.compile("/entries/([a-z-]{1,64})/([0-9a-f]{2,256})");
    private static final int PROJECTION_CACHE = 8;

    private final HttpServer server;
    private final TrustRegistryClient client;
    private final StatusProjection tip = new StatusProjection();
    private final Map<Long, StatusProjection> historical = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, StatusProjection> eldest) {
            return size() > PROJECTION_CACHE;
        }
    };

    private RegistryService(HttpServer server, TrustRegistryClient client) {
        this.server = server;
        this.client = client;
    }

    public static RegistryService start(TrustRegistryClient client, InetSocketAddress bind)
            throws IOException {
        HttpServer server = HttpServer.create(bind, 16);
        RegistryService service = new RegistryService(server, client);
        server.createContext("/", service::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return service;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + port();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, error("only GET is served"));
                return;
            }
            if (path == null || path.length() > MAX_PATH_CHARS) {
                respond(exchange, 404, error("unknown path"));
                return;
            }
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            Long height = height(query);
            Matcher matcher;
            if (path.equals("/healthz")) {
                ObjectNode node = JSON.createObjectNode();
                node.put("chainId", client.chainId());
                node.put("tipHeight", client.tipHeight());
                synchronized (tip) {
                    node.put("replayedHeight", tip.replayedHeight());
                }
                respond(exchange, 200, node);
            } else if ((matcher = STATUS_LIST.matcher(path)).matches()) {
                statusList(exchange, decode(matcher.group(1)), height);
            } else if ((matcher = TRQP.matcher(path)).matches()) {
                trqp(exchange, decode(matcher.group(1)), decode(matcher.group(2)),
                        query.getOrDefault("framework", ""), height);
            } else if ((matcher = ENTRY.matcher(path)).matches()) {
                StatusAnswer answer = client.answer(matcher.group(1),
                        HEX.parseHex(matcher.group(2)), height);
                respond(exchange, 200, AnswerCodec.toJsonNode(answer));
            } else {
                respond(exchange, 404, error("unknown path"));
            }
        } catch (IllegalStateException incomplete) {
            respond(exchange, 503, error("projection incomplete: " + incomplete.getMessage()));
        } catch (IllegalArgumentException invalid) {
            respond(exchange, 400, error(invalid.getMessage()));
        } catch (TrustRegistryException failure) {
            respond(exchange, failure.error() == TrustRegistryException.Error.INVALID ? 404 : 503,
                    error(failure.getMessage()));
        } catch (RuntimeException failure) {
            respond(exchange, 500, error("service failure: " + failure.getMessage()));
        }
    }

    private void statusList(HttpExchange exchange, String listId, Long height) throws IOException {
        TrustRegistryProfile.requireListId(listId);
        StatusAnswer listEntry = client.answer(TrustRegistryProfile.STATUS_LISTS,
                TrustRegistryProfile.listKey(listId), height);
        StatusAnswer identityEntry = listEntry;
        if (listEntry.presence() != StatusAnswer.Presence.ACTIVE && height != null) {
            // Point in time before publication: the list's purpose and size come from the
            // chain's current entry; the served hash then differs from it by construction.
            identityEntry = client.answer(TrustRegistryProfile.STATUS_LISTS,
                    TrustRegistryProfile.listKey(listId), null);
        }
        if (identityEntry.presence() != StatusAnswer.Presence.ACTIVE) {
            respond(exchange, 404, error("status list " + listId + " is not published at height "
                    + identityEntry.height() + " (" + identityEntry.presence() + ")"));
            return;
        }
        TrustRegistryValues.StatusListValue list =
                TrustRegistryValues.StatusListValue.decode(identityEntry.entry().value());
        long replayTo = height != null ? height : list.publishedHeight();
        StatusProjection projection = projectionAt(replayTo);
        StatusListDocument.Rendered rendered = StatusListDocument.render(
                listId, projection, replayTo, list, listEntry, client.chainId());
        respond(exchange, 200, rendered.document());
    }

    private void trqp(HttpExchange exchange, String entityId, String authorization,
                      String framework, Long height) throws IOException {
        if (framework.isBlank()) {
            respond(exchange, 400, error("framework query parameter is required"));
            return;
        }
        StatusAnswer answer = client.answer(TrustRegistryProfile.ISSUERS,
                TrustRegistryProfile.issuerKey(entityId), height);
        TrustRegistryValues.IssuerValue issuer = answer.presence() == StatusAnswer.Presence.ACTIVE
                ? TrustRegistryValues.IssuerValue.decode(answer.entry().value()) : null;
        TrqpEvaluator.Answer evaluated = TrqpEvaluator.evaluate(answer.presence().code(), issuer,
                framework, authorization, answer.height());
        ObjectNode node = JSON.createObjectNode();
        node.put("entityId", entityId);
        node.put("authorizationId", authorization);
        node.put("framework", framework);
        node.put("height", answer.height());
        node.put("authorized", evaluated.authorized());
        node.put("reason", evaluated.reason());
        if (issuer != null) {
            node.put("validFromHeight", issuer.validFromHeight());
            node.put("validUntilHeight", issuer.validUntilHeight());
        }
        node.set("answer", AnswerCodec.toJsonNode(answer));
        respond(exchange, 200, node);
    }

    /** The projection replayed exactly to {@code height}; the tip projection grows incrementally. */
    StatusProjection projectionAt(long height) {
        synchronized (tip) {
            if (height >= tip.replayedHeight()) {
                client.replay(tip, height);
                if (tip.replayedHeight() == height) return tip;
            }
            StatusProjection cached = historical.get(height);
            if (cached != null) return cached;
            StatusProjection fresh = new StatusProjection();
            client.replay(fresh, height);
            historical.put(height, fresh);
            return fresh;
        }
    }

    private static Long height(Map<String, String> query) {
        String value = query.get("height");
        if (value == null || value.isBlank()) return null;
        try {
            long height = Long.parseLong(value);
            if (height < 1) throw new IllegalArgumentException("height must be positive");
            return height;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("height must be an integer");
        }
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return values;
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                values.put(decode(parts[0]), decode(parts[1]));
            }
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static ObjectNode error(String message) {
        return JSON.createObjectNode().put("error", message);
    }

    private static void respond(HttpExchange exchange, int status, ObjectNode body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        // Read-only public data: status lists are fetched by verifiers from any origin.
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
