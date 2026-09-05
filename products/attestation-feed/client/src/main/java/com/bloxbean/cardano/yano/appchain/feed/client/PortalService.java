package com.bloxbean.cardano.yano.appchain.feed.client;

import com.bloxbean.cardano.yano.appchain.feed.profile.FeedDatum;
import com.bloxbean.cardano.yano.appchain.feed.profile.FeedStarterProfile;
import com.bloxbean.cardano.yano.appchain.feed.profile.FeedValues;
import com.bloxbean.cardano.yano.appchain.trust.client.StatusAnswer;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistryException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bloxbean.cardano.yano.appchain.feed.client.HttpSupport.JSON;
import static com.bloxbean.cardano.yano.appchain.feed.client.HttpSupport.error;
import static com.bloxbean.cardano.yano.appchain.feed.client.HttpSupport.respond;

/**
 * The public feed portal (ADR-052 §2.4): read-only, no seed, no node API key exposed.
 * {@code GET /feeds}, {@code /feeds/{feedId}}, {@code /feeds/{feedId}/rounds/{round}[?height=]}
 * (the round view), {@code .../rounds/{round}/proof} (the bundle), {@code .../rounds/{round}/datum},
 * {@code /feeds/{feedId}/latest[/proof]}, {@code /healthz}.
 */
public final class PortalService implements AutoCloseable {
    private static final Pattern FEED = Pattern.compile("/feeds/([^/]{1,32})");
    private static final Pattern ROUND = Pattern.compile("/feeds/([^/]{1,32})/rounds/([^/]{1,20})");
    private static final Pattern ROUND_PROOF = Pattern.compile("/feeds/([^/]{1,32})/rounds/([^/]{1,20})/proof");
    private static final Pattern ROUND_DATUM = Pattern.compile("/feeds/([^/]{1,32})/rounds/([^/]{1,20})/datum");
    private static final Pattern LATEST = Pattern.compile("/feeds/([^/]{1,32})/latest");
    private static final Pattern LATEST_PROOF = Pattern.compile("/feeds/([^/]{1,32})/latest/proof");
    private static final HexFormat HEX = HexFormat.of();

    private final HttpServer server;
    private final FeedClient client;

    private PortalService(HttpServer server, FeedClient client) {
        this.server = server;
        this.client = client;
    }

    public static PortalService start(FeedClient client, InetSocketAddress bind) throws IOException {
        Objects.requireNonNull(client, "client");
        HttpServer server = HttpServer.create(bind, 16);
        PortalService service = new PortalService(server, client);
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
                node.put("starter", FeedStarterProfile.STARTER_NOTICE);
                respond(exchange, 200, node);
            } else if (path.equals("/feeds")) {
                respond(exchange, 200, feeds());
            } else if ((matcher = ROUND_PROOF.matcher(path)).matches()) {
                RoundBundle bundle = client.round(feedId(matcher.group(1)), round(matcher.group(2)), height);
                respond(exchange, 200, bundle.toJsonNode());
            } else if ((matcher = ROUND_DATUM.matcher(path)).matches()) {
                datum(exchange, client.round(feedId(matcher.group(1)), round(matcher.group(2)), height));
            } else if ((matcher = ROUND.matcher(path)).matches()) {
                RoundBundle bundle = client.round(feedId(matcher.group(1)), round(matcher.group(2)), height);
                respond(exchange, 200, RoundView.of(bundle));
            } else if ((matcher = LATEST_PROOF.matcher(path)).matches()) {
                RoundBundle bundle = client.latestRound(feedId(matcher.group(1)));
                respond(exchange, bundle == null ? 404 : 200,
                        bundle == null ? error("no closed round in the probe window") : bundle.toJsonNode());
            } else if ((matcher = LATEST.matcher(path)).matches()) {
                RoundBundle bundle = client.latestRound(feedId(matcher.group(1)));
                respond(exchange, bundle == null ? 404 : 200,
                        bundle == null ? error("no closed round in the probe window") : RoundView.of(bundle));
            } else if ((matcher = FEED.matcher(path)).matches()) {
                feed(exchange, feedId(matcher.group(1)));
            } else {
                respond(exchange, 404, error("unknown path"));
            }
        } catch (IllegalArgumentException invalid) {
            respond(exchange, 400, error(invalid.getMessage()));
        } catch (FeedException failure) {
            respond(exchange, failure.error() == FeedException.Error.INVALID ? 404 : 503,
                    error(failure.getMessage()));
        } catch (TrustRegistryException failure) {
            respond(exchange, failure.error() == TrustRegistryException.Error.INVALID ? 404 : 503,
                    error(failure.getMessage()));
        } catch (RuntimeException failure) {
            respond(exchange, 500, error("portal failure: " + failure.getMessage()));
        }
    }

    private static String feedId(String segment) {
        return FeedStarterProfile.requireFeedId(HttpSupport.decode(segment));
    }

    private static long round(String segment) {
        return FeedStarterProfile.parseRound(HttpSupport.decode(segment));
    }

    private ObjectNode feeds() {
        ObjectNode root = JSON.createObjectNode();
        root.put("chainId", client.chainId());
        ArrayNode feeds = root.putArray("feeds");
        List<String> ids;
        try {
            ids = client.feeds();
            root.put("replayedHeight", client.projection().replayedHeight());
        } catch (FeedException bound) {
            root.put("note", bound.getMessage());
            ids = client.projection().feedIds();
        }
        ids.forEach(feeds::add);
        root.put("starter", FeedStarterProfile.STARTER_NOTICE);
        return root;
    }

    private void feed(HttpExchange exchange, String feedId) throws IOException {
        StatusAnswer answer = client.feedAnswer(feedId, null);
        if (answer.presence() == StatusAnswer.Presence.ABSENT) {
            respond(exchange, 404, error("unknown feed " + feedId));
            return;
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("feedId", feedId);
        root.put("chainId", client.chainId());
        root.put("height", answer.height());
        root.put("presence", answer.presence().name());
        root.put("starter", FeedStarterProfile.STARTER_NOTICE);
        if (answer.presence() == StatusAnswer.Presence.ACTIVE) {
            FeedValues.FeedValue feed;
            try {
                feed = FeedValues.FeedValue.decode(answer.entry().value());
            } catch (RuntimeException malformed) {
                respond(exchange, 500, error("feed entry is malformed"));
                return;
            }
            ObjectNode spec = root.putObject("feed");
            spec.put("description", feed.description());
            spec.put("unit", feed.unit());
            spec.put("scale", feed.scale());
            spec.put("epochStart", feed.epochStart());
            spec.put("roundSeconds", feed.roundSeconds());
            ArrayNode sources = spec.putArray("sources");
            feed.sources().forEach(sources::add);
            spec.put("minimumSources", feed.minimumSources());
            spec.put("maximumDeviationPpm", feed.maximumDeviationPpm());
            spec.put("maximumDeviationAbsolute", feed.maximumDeviationAbsolute());
            spec.put("minimumValue", feed.minimumValue());
            spec.put("maximumValue", feed.maximumValue());
            spec.put("status", feed.statusName());
            spec.put("revision", answer.entry().revision());
            spec.put("lastMutationHeight", answer.entry().lastMutationHeight());
            if (answer.provenance().actorId() != null) {
                spec.put("writtenBy", answer.provenance().actorId());
            }
            // The calendar's current round by the portal's clock: a hint, never a proof.
            root.put("currentRoundByPortalClock", client.currentRound(feed));
        }
        ArrayNode rounds = root.putArray("roundsWithRecords");
        try {
            client.feeds();
            client.projection().rounds(feedId).forEach(rounds::add);
            root.put("replayedHeight", client.projection().replayedHeight());
        } catch (FeedException bound) {
            root.put("note", bound.getMessage());
        }
        respond(exchange, 200, root);
    }

    private void datum(HttpExchange exchange, RoundBundle bundle) throws IOException {
        FeedValues.RoundValue record = bundle.recordValue();
        var result = bundle.recompute();
        var feed = bundle.feedValue();
        if (record == null || !record.closed() || result == null || feed == null || !result.closed()
                || record.closedAtHeight() != bundle.observationHeight()) {
            respond(exchange, 404, error("round " + bundle.round() + " of " + bundle.feedId() + " is "
                    + bundle.status() + "; a datum exists for CLOSED rounds only"));
            return;
        }
        byte[] datum = FeedDatum.encode(FeedClient.datumFields(bundle.chainId(), bundle.feedId(), bundle.round(),
                feed, result, bundle.observationHeight(), bundle.stateRootHex()));
        ObjectNode node = JSON.createObjectNode();
        node.put("feedId", bundle.feedId());
        node.put("round", bundle.round());
        node.put("type", FeedDatum.DATUM_ID);
        node.put("hex", HEX.formatHex(datum));
        node.put("sha256", HEX.formatHex(FeedValues.sha256(datum)));
        node.put("recordDatumSha256", HEX.formatHex(record.datumSha256()));
        node.put("bindsRecord", java.util.Arrays.equals(FeedValues.sha256(datum), record.datumSha256()));
        node.put("starter", FeedStarterProfile.STARTER_NOTICE);
        node.put("note", "a candidate for the deferred Cardano publication executor (ADR-052 §8); nothing is published");
        respond(exchange, 200, node);
    }
}
