package com.bloxbean.cardano.yano.appchain.dpp.client;

import com.bloxbean.cardano.yano.appchain.dpp.profile.DppStarterProfile;
import com.bloxbean.cardano.yano.appchain.trust.client.StatusAnswer;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistryException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bloxbean.cardano.yano.appchain.dpp.client.HttpSupport.JSON;
import static com.bloxbean.cardano.yano.appchain.dpp.client.HttpSupport.error;
import static com.bloxbean.cardano.yano.appchain.dpp.client.HttpSupport.respond;

/**
 * The public passport portal (ADR-051 §2.4): read-only, no seed, no node API key exposed.
 * {@code GET /passports/{productId}[?height=]}, {@code /passports/{productId}/proof},
 * {@code /01/{gtin}[/21/{serial}]}, {@code /documents/{sha256}}, {@code /healthz}.
 */
public final class PortalService implements AutoCloseable {
    private static final Pattern PASSPORT = Pattern.compile("/passports/([^/]{1,128})");
    private static final Pattern PROOF = Pattern.compile("/passports/([^/]{1,128})/proof");
    private static final Pattern DIGITAL_LINK = Pattern.compile("/01/([0-9]{8,14})(?:/21/([^/]{1,20}))?");
    private static final Pattern DOCUMENT = Pattern.compile("/documents/([0-9a-f]{64})");

    private final HttpServer server;
    private final DppClient client;
    private final DocumentStore documents;

    private PortalService(HttpServer server, DppClient client, DocumentStore documents) {
        this.server = server;
        this.client = client;
        this.documents = documents;
    }

    public static PortalService start(DppClient client, DocumentStore documents, InetSocketAddress bind)
            throws IOException {
        Objects.requireNonNull(client, "client");
        HttpServer server = HttpServer.create(bind, 16);
        PortalService service = new PortalService(server, client, documents);
        server.createContext("/", service::handle);
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
        HttpSupport.cors(exchange, "Content-Type");
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"GET".equals(method)) {
                respond(exchange, 405, error("only GET is served"));
                return;
            }
            if (path == null || path.length() > HttpSupport.MAX_PATH_CHARS) {
                respond(exchange, 404, error("unknown path"));
                return;
            }
            Map<String, String> query = HttpSupport.query(exchange.getRequestURI().getRawQuery());
            Long height = HttpSupport.height(query);
            Matcher matcher;
            if (path.equals("/healthz")) {
                ObjectNode node = JSON.createObjectNode();
                node.put("chainId", client.chainId());
                node.put("tipHeight", client.tipHeight());
                node.put("replayedHeight", client.projection().replayedHeight());
                node.put("prototype", DppStarterProfile.PROTOTYPE_NOTICE);
                respond(exchange, 200, node);
            } else if ((matcher = PROOF.matcher(path)).matches()) {
                PassportBundle bundle = passport(HttpSupport.decode(matcher.group(1)), height);
                respond(exchange, bundle == null ? 404 : 200,
                        bundle == null ? error("unknown product") : bundle.toJsonNode());
            } else if ((matcher = PASSPORT.matcher(path)).matches()) {
                view(exchange, HttpSupport.decode(matcher.group(1)), height);
            } else if ((matcher = DIGITAL_LINK.matcher(path)).matches()) {
                view(exchange, DppStarterProfile.gs1ProductId(matcher.group(1), matcher.group(2)), height);
            } else if ((matcher = DOCUMENT.matcher(path)).matches()) {
                document(exchange, matcher.group(1));
            } else {
                respond(exchange, 404, error("unknown path"));
            }
        } catch (IllegalArgumentException invalid) {
            respond(exchange, 400, error(invalid.getMessage()));
        } catch (DppException failure) {
            respond(exchange, failure.error() == DppException.Error.INVALID ? 404 : 503,
                    error(failure.getMessage()));
        } catch (TrustRegistryException failure) {
            respond(exchange, failure.error() == TrustRegistryException.Error.INVALID ? 404 : 503,
                    error(failure.getMessage()));
        } catch (RuntimeException failure) {
            respond(exchange, 500, error("portal failure: " + failure.getMessage()));
        }
    }

    private void view(HttpExchange exchange, String productId, Long height) throws IOException {
        PassportBundle bundle = passport(productId, height);
        if (bundle == null) {
            respond(exchange, 404, error("unknown product " + productId));
            return;
        }
        respond(exchange, 200, PassportView.of(bundle, this::documentAvailable));
    }

    /** Null when the product was never written: no entry, no tombstone, nothing in the timeline. */
    private PassportBundle passport(String productId, Long height) {
        PassportBundle bundle = client.passport(productId, height);
        if (bundle.product().presence() == StatusAnswer.Presence.ABSENT && bundle.timeline().isEmpty()) {
            return null;
        }
        return bundle;
    }

    private boolean documentAvailable(String sha256) {
        return documents != null && documents.has(sha256);
    }

    private void document(HttpExchange exchange, String sha256) throws IOException {
        Optional<byte[]> body = documents == null ? Optional.empty() : documents.get(sha256);
        if (body.isEmpty()) {
            respond(exchange, 404, error("no document " + sha256));
            return;
        }
        exchange.getResponseHeaders().add("X-Content-Sha256", sha256);
        HttpSupport.respondBytes(exchange, 200, body.get(), "application/octet-stream");
    }
}
