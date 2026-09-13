package org.yanoproject.x.explorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The verifiable read API of ADR-050 §2.4: GET-only JSON over the index, row and state bundles
 * assembled from captured material, and archived content by hash. The node API key stays in the
 * explorer; the service never signs or writes.
 */
public final class ExplorerService implements AutoCloseable {
    public static final int MAX_PATH_CHARS = 512;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CHAIN = Pattern.compile("/chains/([A-Za-z0-9._:-]{1,128})(/.*)?");
    private static final Pattern BLOCK = Pattern.compile("/blocks/([0-9]{1,18})");
    private static final Pattern MESSAGE = Pattern.compile("/messages/([0-9a-f]{64})(/proof)?");
    private static final Pattern SUBJECT = Pattern.compile("/subjects/([a-z-]{1,32})/([^/]{1,512})(/proof)?");
    private static final Pattern TRAIL = Pattern.compile("/trails/([^/]{1,512})(/proof)?");
    private static final Pattern CONTENT = Pattern.compile("/content/([0-9a-f]{64})");

    private final HttpServer server;
    private final Explorer explorer;
    private final List<String> chains;
    private final ScheduledExecutorService poller;

    private ExplorerService(HttpServer server, Explorer explorer, List<String> chains, long pollMillis) {
        this.server = server;
        this.explorer = explorer;
        this.chains = List.copyOf(chains);
        this.poller = pollMillis > 0 ? Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "explorer-follow");
            thread.setDaemon(true);
            return thread;
        }) : null;
        if (poller != null) {
            poller.scheduleWithFixedDelay(this::poll, 0, pollMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Starts the service; with {@code pollMillis > 0} a background loop keeps the listed chains
     * caught up.
     */
    public static ExplorerService start(Explorer explorer, List<String> chains, InetSocketAddress bind,
                                        long pollMillis) throws IOException {
        HttpServer server = HttpServer.create(bind, 16);
        ExplorerService service = new ExplorerService(server, explorer, chains, pollMillis);
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

    private void poll() {
        for (String chainId : chains) {
            try {
                explorer.catchUp(chainId, Explorer.DEFAULT_BATCH);
            } catch (RuntimeException failure) {
                // recorded by the explorer as the chain's diagnostic; the next tick retries
            }
        }
    }

    @Override
    public void close() {
        if (poller != null) poller.shutdownNow();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, error("only GET is served"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.length() > MAX_PATH_CHARS) {
                respond(exchange, 404, error("unknown path"));
                return;
            }
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            if (path.equals("/healthz")) {
                ObjectNode node = JSON.createObjectNode();
                ArrayNode list = node.putArray("chains");
                for (String chainId : explorer.chains()) {
                    ObjectNode entry = list.addObject();
                    entry.put("chainId", chainId);
                    IndexStore.Checkpoint checkpoint = explorer.store().checkpoint(chainId);
                    entry.put("checkpointHeight", checkpoint.height());
                }
                respond(exchange, 200, node);
                return;
            }
            if (path.equals("/chains")) {
                ArrayNode list = JSON.createArrayNode();
                for (String chainId : explorer.chains()) {
                    try {
                        list.add(chainNode(explorer.chain(chainId)));
                    } catch (ExplorerException unavailable) {
                        ObjectNode entry = list.addObject();
                        entry.put("chainId", chainId);
                        entry.put("diagnostic", unavailable.getMessage());
                    }
                }
                respond(exchange, 200, list);
                return;
            }
            Matcher chain = CHAIN.matcher(path);
            if (!chain.matches()) {
                respond(exchange, 404, error("unknown path"));
                return;
            }
            String chainId = chain.group(1);
            if (!chains.contains(chainId) && !explorer.store().chains().contains(chainId)) {
                respond(exchange, 404, error("chain " + chainId + " is not indexed here"));
                return;
            }
            String rest = chain.group(2) == null ? "" : chain.group(2);
            Matcher matcher;
            if (rest.isEmpty() || rest.equals("/")) {
                respond(exchange, 200, chainNode(explorer.chain(chainId)));
            } else if (rest.equals("/blocks")) {
                long from = parseLong(query.getOrDefault("from", "0"));
                int limit = (int) parseLong(query.getOrDefault("limit", "20"));
                ObjectNode node = JSON.createObjectNode();
                node.put("chainId", chainId);
                node.put("checkpointHeight", explorer.store().checkpoint(chainId).height());
                ArrayNode list = node.putArray("blocks");
                for (IndexedBlock block : explorer.store().blocks(chainId, from, limit)) list.add(blockNode(block, false));
                respond(exchange, 200, node);
            } else if ((matcher = BLOCK.matcher(rest)).matches()) {
                long height = parseLong(matcher.group(1));
                Optional<IndexedBlock> block = explorer.store().block(chainId, height);
                if (block.isEmpty()) {
                    respond(exchange, 404, error("block " + height + " is not indexed"));
                } else {
                    ObjectNode node = blockNode(block.get(), true);
                    ArrayNode list = node.putArray("messages");
                    for (IndexedMessage message : explorer.store().messages(chainId, height)) {
                        list.add(messageNode(chainId, message, block.get().level()));
                    }
                    respond(exchange, 200, node);
                }
            } else if ((matcher = MESSAGE.matcher(rest)).matches()) {
                String messageId = matcher.group(1);
                if (matcher.group(2) != null) {
                    respond(exchange, 200, Bundles.toNode(explorer.rowBundle(chainId, messageId)));
                    return;
                }
                Optional<IndexedMessage> message = explorer.store().message(chainId, messageId);
                if (message.isEmpty()) {
                    respond(exchange, 404, error("message " + messageId + " is not indexed"));
                } else {
                    IndexedBlock block = explorer.store().block(chainId, message.get().height()).orElseThrow();
                    ObjectNode node = messageNode(chainId, message.get(), block.level());
                    node.put("blockHash", block.blockHashHex());
                    node.put("stateRoot", block.stateRootHex());
                    node.put("timestamp", block.timestamp());
                    node.put("provable", !block.canonicalHex().isEmpty());
                    respond(exchange, 200, node);
                }
            } else if (rest.equals("/search")) {
                ArrayNode list = JSON.createArrayNode();
                for (IndexStore.SearchHit hit : explorer.store().search(chainId, query.getOrDefault("q", ""), 50)) {
                    list.add(JSON.valueToTree(hit));
                }
                respond(exchange, 200, list);
            } else if (rest.equals("/topics")) {
                respond(exchange, 200, JSON.valueToTree(explorer.store().topics(chainId)));
            } else if (rest.equals("/subjects")) {
                ArrayNode list = JSON.createArrayNode();
                for (IndexStore.SubjectSummary summary : explorer.store().subjects(chainId,
                        query.getOrDefault("module", ""), query.getOrDefault("prefix", ""),
                        (int) parseLong(query.getOrDefault("limit", "50")))) {
                    list.add(JSON.valueToTree(summary));
                }
                respond(exchange, 200, list);
            } else if ((matcher = SUBJECT.matcher(rest)).matches()) {
                subject(exchange, chainId, matcher.group(1), decode(matcher.group(2)), matcher.group(3) != null, query);
            } else if ((matcher = TRAIL.matcher(rest)).matches()) {
                subject(exchange, chainId, StockModules.DOC_TRAIL, decode(matcher.group(1)), matcher.group(2) != null, query);
            } else if ((matcher = CONTENT.matcher(rest)).matches()) {
                content(exchange, matcher.group(1), query.containsKey("meta"));
            } else {
                respond(exchange, 404, error("unknown path"));
            }
        } catch (ExplorerException failure) {
            int status = switch (failure.error()) {
                case USAGE -> 400;
                case INVALID -> 404;
                case UNAVAILABLE, MALFORMED_RESPONSE -> 503;
                case IDENTITY_MISMATCH -> 409;
            };
            respond(exchange, status, error(failure.getMessage()));
        } catch (IllegalArgumentException invalid) {
            respond(exchange, 400, error(invalid.getMessage()));
        } catch (RuntimeException failure) {
            respond(exchange, 500, error("service failure: " + failure.getMessage()));
        }
    }

    private void subject(HttpExchange exchange, String chainId, String module, String subject, boolean proof,
                         Map<String, String> query) throws IOException {
        if (proof) {
            Long height = query.containsKey("height") ? parseLong(query.get("height")) : null;
            respond(exchange, 200, Bundles.toNode(explorer.stateBundle(chainId, module, subject, height)));
            return;
        }
        boolean check = !"false".equals(query.getOrDefault("check", "true"));
        Explorer.SubjectView view = explorer.subject(chainId, module, subject, check);
        ObjectNode node = JSON.createObjectNode();
        node.put("chainId", chainId);
        node.put("module", view.module());
        node.put("kind", view.kind());
        node.put("subject", view.subject());
        ArrayNode rows = node.putArray("rows");
        for (IndexStore.RowRecord row : view.rows()) rows.add(rowNode(row));
        node.set("derived", JSON.valueToTree(view.derived()));
        node.set("stateCheck", JSON.valueToTree(view.stateCheck()));
        respond(exchange, 200, node);
    }

    private void content(HttpExchange exchange, String sha256, boolean meta) throws IOException {
        Optional<IndexStore.ContentRecord> record = explorer.store().content(sha256);
        if (record.isEmpty()) {
            respond(exchange, 404, error("no archived content " + sha256));
            return;
        }
        if (meta) {
            ObjectNode node = JSON.valueToTree(record.get());
            node.put("available", explorer.archiver().read(sha256).isPresent());
            respond(exchange, 200, node);
            return;
        }
        Optional<byte[]> body = explorer.archiver().read(sha256);
        if (body.isEmpty()) {
            respond(exchange, 404, error("content " + sha256 + " is recorded but not stored (" + record.get().status() + ")"));
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("X-Content-Sha256", sha256);
        exchange.sendResponseHeaders(200, body.get().length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body.get());
        }
    }

    // --- views ---

    static ObjectNode chainNode(Explorer.ChainView view) {
        ObjectNode node = JSON.createObjectNode();
        node.put("chainId", view.identity().chainId());
        node.put("applicationId", view.identity().applicationId());
        node.put("profile", view.identity().profile());
        node.put("stateGenesisId", view.identity().stateGenesisIdHex());
        node.put("identityDigest", view.identity().digest());
        node.put("checkpointHeight", view.checkpoint().height());
        node.put("checkpointBlockHash", view.checkpoint().blockHashHex());
        node.put("tipHeight", view.tipHeight());
        node.put("lagBlocks", view.lagBlocks());
        node.set("levels", JSON.valueToTree(view.levels()));
        node.set("topics", JSON.valueToTree(view.topics()));
        node.put("diagnostic", view.diagnostic());
        return node;
    }

    static ObjectNode blockNode(IndexedBlock block, boolean detail) {
        ObjectNode node = JSON.createObjectNode();
        node.put("height", block.height());
        node.put("blockHash", block.blockHashHex());
        node.put("prevHash", block.prevHashHex());
        node.put("timestamp", block.timestamp());
        node.put("messagesRoot", block.messagesRootHex());
        node.put("stateRoot", block.stateRootHex());
        node.put("proposer", block.proposerHex());
        node.put("messageCount", block.messageCount());
        node.put("certSignatures", block.certSignatures());
        node.put("level", block.level().name());
        node.put("provable", !block.canonicalHex().isEmpty());
        node.put("blockRecordCaptured", !block.blockRecordProofJson().isEmpty());
        if (detail) {
            node.set("memberKeysHex", JSON.valueToTree(block.memberKeysHex()));
            node.put("threshold", block.threshold());
            node.put("anchor", block.anchorJson());
            node.put("diagnostic", block.diagnostic());
        }
        return node;
    }

    ObjectNode messageNode(String chainId, IndexedMessage message, VerificationLevel level) {
        ObjectNode node = Bundles.messageNode(message);
        node.put("level", level.name());
        ArrayNode rows = node.putArray("rows");
        for (IndexStore.RowRecord row : explorer.store().rowsOfMessage(chainId, message.height(), message.index())) {
            rows.add(rowNode(row));
        }
        ObjectNode evidence = node.putObject("evidence");
        evidence.put("messageId", message.messageIdHex());
        evidence.put("height", message.height());
        evidence.put("index", message.index());
        return node;
    }

    static ObjectNode rowNode(IndexStore.RowRecord row) {
        ObjectNode node = JSON.createObjectNode();
        node.put("height", row.height());
        node.put("index", row.index());
        node.put("ordinal", row.ordinal());
        node.put("messageId", row.messageIdHex());
        node.put("module", row.module());
        node.put("kind", row.kind());
        node.put("subject", row.subject());
        node.put("op", row.op());
        node.set("fields", JSON.valueToTree(row.fields()));
        node.put("level", row.level().name());
        return node;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("numbers must be integers");
        }
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return values;
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static ObjectNode error(String message) {
        return JSON.createObjectNode().put("error", Objects.requireNonNullElse(message, "failure"));
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Comma-separated values, trimmed, blanks dropped. */
    public static List<String> csv(String value) {
        List<String> items = new ArrayList<>();
        if (value == null) return items;
        for (String item : value.split(",")) if (!item.isBlank()) items.add(item.trim());
        return items;
    }
}
