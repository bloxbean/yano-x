package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.yanoproject.x.appchain.formats.Draft;
import org.yanoproject.x.appchain.formats.MemberCard;
import org.yanoproject.x.appchain.formats.SealedApp;
import org.yanoproject.x.devtools.TopicsCli;
import org.yanoproject.x.devtools.AppChainSealResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * {@code ui} (ADR-056 D7): a localhost-only page over the same commands. Every action runs the exact CLI command
 * in-process, and the page shows that command, so files written from the browser are the CLI's own. The API needs a
 * one-time token in a custom header, which another web page cannot send.
 */
final class UiCommand {
    static final String USAGE = "yano.sh appchain ui [--dir <work-dir>] [--node-dir yano-node] [--port <port>]"
            + " [--no-open]";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> VERBS = Set.of("new", "request", "seal", "inspect", "join", "start", "stop",
            "status", "approve", "anchor", "topics");
    /** The page may only vote on a topics plan; planning and signing stay with their owners' keys. */
    private static final Set<String> TOPIC_ACTIONS = Set.of("approve");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,120}");
    private static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8",
            "mjs", "text/javascript; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "json", "application/json", "yaml", "application/yaml",
            "svg", "image/svg+xml");

    private final Terminal terminal;
    private final Distribution distribution;
    private final PrintStream err;

    UiCommand(Terminal terminal, Distribution distribution, PrintStream err) {
        this.terminal = terminal;
        this.distribution = distribution;
        this.err = err;
    }

    int run(String[] arguments) throws IOException {
        Args args = new Args(arguments, Set.of("dir", "node-dir", "port"), Set.of("no-open", "yes"));
        args.maxPositionals(0, USAGE);
        Path work = args.path("dir", Path.of(".")).toAbsolutePath().normalize();
        Path nodeDir = args.path("node-dir", work.resolve(NodeDir.DEFAULT)).toAbsolutePath().normalize();
        Files.createDirectories(work);
        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        String token = HexFormat.of().formatHex(secret);
        HttpServer server = start(work, nodeDir, args.integer("port") == null ? 0 : args.integer("port"), token);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/?t=" + token;
        terminal.ok("App-chain setup UI for " + work);
        terminal.say("  Open " + url);
        terminal.say("  It listens on this computer only. Press Ctrl-C to stop it.");
        if (!args.flag("no-open")) open(url);
        try {
            Thread.currentThread().join();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            server.stop(0);
        }
        return 0;
    }

    /** Starts the listener on 127.0.0.1 only. */
    HttpServer start(Path work, Path nodeDir, int port, String token) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> serve(exchange, token, work, nodeDir));
        server.start();
        return server;
    }

    private void serve(HttpExchange exchange, String token, Path work, Path nodeDir) {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            if (path.startsWith("/api/")) {
                byte[] presented = String.valueOf(exchange.getRequestHeaders().getFirst("X-UI-Token"))
                        .getBytes(StandardCharsets.UTF_8);
                if (!MessageDigest.isEqual(presented, token.getBytes(StandardCharsets.UTF_8))) {
                    send(exchange, 403, "application/json", "{\"error\":\"missing or wrong UI token\"}");
                    return;
                }
                api(exchange, path.substring("/api/".length()), work, nodeDir);
                return;
            }
            staticFile(exchange, path);
        } catch (IOException | RuntimeException failure) {
            err.println("ui: " + failure.getMessage());
        }
    }

    private void api(HttpExchange exchange, String route, Path work, Path nodeDir) throws IOException {
        switch (route) {
            case "state" -> sendJson(exchange, 200, state(work, nodeDir));
            case "recipes" -> sendJson(exchange, 200, recipes());
            case "run" -> {
                JsonNode body = JSON.readTree(read(exchange.getRequestBody(), 64 * 1024));
                List<String> arguments = new ArrayList<>();
                body.path("args").forEach(item -> arguments.add(item.asText()));
                if (arguments.isEmpty() || !VERBS.contains(arguments.getFirst()) || "topics".equals(arguments.getFirst())
                        && (arguments.size() < 2 || !TOPIC_ACTIONS.contains(arguments.get(1)))) {
                    sendJson(exchange, 400, JSON.createObjectNode().put("error", "not an allowed command"));
                    return;
                }
                sendJson(exchange, 200, runCommand(arguments, work, nodeDir));
            }
            case "upload" -> {
                JsonNode body = JSON.readTree(read(exchange.getRequestBody(), 1024 * 1024));
                String name = body.path("name").asText("");
                if (!FILE_NAME.matcher(name).matches() || !(name.endsWith(".member") || name.endsWith(".chain")
                        || name.endsWith(".yaml") || name.endsWith(".plan.json"))) {
                    sendJson(exchange, 400, JSON.createObjectNode().put("error",
                            "only .member, .chain, .yaml and .plan.json files can be added"));
                    return;
                }
                String content = body.path("content").asText("");
                if (name.endsWith(".member")) MemberCard.parse(content);
                if (name.endsWith(".chain")) SealedApp.parse(content);
                if (name.endsWith(".plan.json")
                        && !"TopicsPlan".equals(JSON.readTree(content).path("kind").asText())) {
                    sendJson(exchange, 400, JSON.createObjectNode().put("error", name + " is not a topics plan"));
                    return;
                }
                NodeDir.write(work.resolve(name), content);
                sendJson(exchange, 200, JSON.createObjectNode().put("saved", name));
            }
            case "file" -> {
                String name = exchange.getRequestURI().getQuery() == null ? ""
                        : exchange.getRequestURI().getQuery().replaceFirst("^name=", "");
                Path file = work.resolve(name);
                if (!FILE_NAME.matcher(name).matches() || !Files.isRegularFile(file) || name.endsWith(".key")) {
                    send(exchange, 404, "text/plain", "no such file");
                    return;
                }
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
                send(exchange, 200, "application/octet-stream", Files.readString(file));
            }
            default -> sendJson(exchange, 404, JSON.createObjectNode().put("error", "unknown route"));
        }
    }

    /** Runs one CLI command with the work directory and node directory filled in, capturing what it prints. */
    ObjectNode runCommand(List<String> arguments, Path work, Path nodeDir) {
        List<String> resolved = new ArrayList<>();
        for (String argument : arguments) {
            boolean file = FILE_NAME.matcher(argument).matches() && (argument.endsWith(".yaml")
                    || argument.endsWith(".member") || argument.endsWith(".chain") || argument.endsWith(".plan.json"));
            resolved.add(file ? work.resolve(argument).toString() : argument);
        }
        String verb = arguments.getFirst();
        if (Set.of("request", "join", "start", "stop", "status", "approve", "anchor", "topics").contains(verb)
                && !arguments.contains("--node-dir")) {
            resolved.addAll(List.of("--node-dir", nodeDir.toString()));
        }
        if (Set.of("new", "request").contains(verb) && !arguments.contains("--output")) {
            resolved.addAll(List.of("--output", work.toString()));
        }
        if (Set.of("new", "request", "anchor").contains(verb) && !arguments.contains("--yes")) resolved.add("--yes");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exit;
        synchronized (this) {
            exit = AppChainCli.run(resolved.toArray(String[]::new),
                    new Terminal(null, new PrintStream(out, true, StandardCharsets.UTF_8), false),
                    new PrintStream(error, true, StandardCharsets.UTF_8), distribution);
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("command", "yano.sh appchain " + String.join(" ", arguments));
        result.put("exit", exit);
        result.put("out", out.toString(StandardCharsets.UTF_8));
        result.put("err", error.toString(StandardCharsets.UTF_8));
        return result;
    }

    /** Drafts, cards and sealed files in the work directory, and this node's applications. */
    ObjectNode state(Path work, Path nodeDir) throws IOException {
        ObjectNode state = JSON.createObjectNode();
        state.put("work", work.toString());
        state.put("nodeDir", nodeDir.toString());
        ArrayNode drafts = state.putArray("drafts");
        ArrayNode sealed = state.putArray("sealed");
        ArrayNode cards = state.putArray("cards");
        ArrayNode plans = state.putArray("plans");
        try (var files = Files.list(work)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                try {
                    if (name.endsWith(".draft.yaml")) {
                        Draft draft = Draft.parse(Files.readString(file));
                        ObjectNode entry = drafts.addObject().put("file", name).put("name", draft.name())
                                .put("invite", draft.invite()).put("network", draft.network())
                                .put("threshold", draft.threshold()).put("sealed",
                                        Files.isRegularFile(work.resolve(draft.name() + ".chain")));
                        draft.operators().forEach(entry.putArray("operators")::add);
                        draft.chains().forEach(chain -> entry.withArray("chains").addObject().put("id", chain.id())
                                .put("recipe", chain.recipe())
                                .put("topics", chain.topics() == null ? null : chain.topics().summary()));
                    } else if (name.endsWith(".chain")) {
                        SealedApp app = SealedApp.parse(Files.readString(file));
                        ObjectNode entry = sealed.addObject().put("file", name).put("name", app.genesis().name())
                                .put("fingerprint", app.displayFingerprint()).put("network", app.genesis().network())
                                .put("threshold", app.genesis().threshold()).put("lead", app.genesis().lead());
                        app.genesis().members().forEach(member -> entry.withArray("members").addObject()
                                .put("name", member.name()).put("keyId", Show.keyId(member.publicKey())));
                        app.genesis().chains().forEach(chain -> entry.withArray("chains").addObject()
                                .put("id", chain.id()).put("recipe", chain.recipe())
                                .put("topics", Show.topics(app.genesis(), chain.settings())));
                    } else if (name.endsWith(".plan.json")) {
                        ObjectNode entry = plans.addObject().put("file", name);
                        try {
                            List<TopicsCli.Vote> votes = TopicsCli.votes(file);
                            entry.put("chain", votes.getFirst().chainId());
                            NodeDir voter = new NodeDir(nodeDir);
                            boolean live = voter.identity() != null && Processes.running(voter);
                            for (TopicsCli.Vote vote : votes) {
                                ObjectNode item = entry.withArray("votes").addObject()
                                        .put("operation", vote.operation()).put("target", vote.target());
                                // Where this member's vote stands, as topics approve would report it.
                                if (live) {
                                    try {
                                        item.put("progress", TopicsCommand.progress(NodeClient.local(voter),
                                                "/api/v1/app-chain/chains/" + vote.chainId(), voter.publicKey(),
                                                vote).name());
                                    } catch (CliException unreadable) {
                                        item.put("progress", "UNKNOWN"); // the page offers the vote; approve checks
                                    }
                                }
                            }
                        } catch (IOException | IllegalArgumentException notVotes) {
                            entry.put("signed", true); // owner-signed plans are submitted with the CLI
                        }
                    } else if (name.endsWith(".member")) {
                        MemberCard card = MemberCard.parse(Files.readString(file));
                        cards.addObject().put("file", name).put("name", card.name()).put("invite", card.invite())
                                .put("keyId", Show.keyId(card.publicKey())).put("valid", card.signatureValid());
                    }
                } catch (RuntimeException unreadable) {
                    state.withArray("problems").add(name + ": " + unreadable.getMessage());
                }
            }
        }
        NodeDir node = new NodeDir(nodeDir);
        ObjectNode nodeState = state.putObject("node");
        NodeDir.Identity identity = node.identity();
        if (identity != null) {
            nodeState.put("name", identity.name()).put("keyId", Show.keyId(identity.publicKey()))
                    .put("node", identity.node()).put("http", identity.http())
                    .put("running", Processes.running(node));
            ArrayNode apps = nodeState.putArray("apps");
            for (SealedApp app : node.apps()) {
                ObjectNode entry = apps.addObject().put("name", app.genesis().name());
                if (Processes.running(node)) {
                    entry.set("status", JSON.valueToTree(new StatusCommand(terminal).view(node, app)));
                }
            }
        }
        return state;
    }

    private static ObjectNode recipes() throws IOException {
        ObjectNode result = JSON.createObjectNode();
        for (Map.Entry<String, String> recipe : AppChainSealResolver.recipes().entrySet()) {
            ObjectNode entry = result.putObject(recipe.getKey()).put("summary", recipe.getValue());
            AppChainSealResolver.recipeAnswers(recipe.getKey()).forEach(entry.putArray("answers")::add);
        }
        return result;
    }

    /** The page and its modules from this tool, and the binding editor from the release's Studio. */
    private void staticFile(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..")) {
            send(exchange, 404, "text/plain", "not found");
            return;
        }
        byte[] body = null;
        if (path.startsWith("/studio/")) {
            Path file = distribution.home().resolve("studio").resolve(path.substring("/studio/".length())).normalize();
            if (file.startsWith(distribution.home().resolve("studio")) && Files.isRegularFile(file)) {
                body = Files.readAllBytes(file);
            }
        } else {
            try (InputStream resource = UiCommand.class.getResourceAsStream("/appchain-ui" + path)) {
                if (resource != null) body = resource.readAllBytes();
            }
        }
        if (body == null) {
            send(exchange, 404, "text/plain", "not found");
            return;
        }
        String extension = path.substring(path.lastIndexOf('.') + 1);
        exchange.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(extension, "application/octet-stream"));
        if (path.endsWith(".html")) {
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; "
                    + "style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; "
                    + "frame-ancestors 'none'");
        }
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void open(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String[] command = os.contains("mac") ? new String[]{"open", url}
                : os.contains("win") ? new String[]{"rundll32", "url.dll,FileProtocolHandler", url}
                : new String[]{"xdg-open", url};
        try {
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException noBrowser) {
            // The printed address still works.
        }
    }

    private static byte[] read(InputStream input, int limit) throws IOException {
        byte[] body = input.readNBytes(limit + 1);
        if (body.length > limit) throw new IOException("request too large");
        return body;
    }

    private static void sendJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        send(exchange, status, "application/json", JSON.writeValueAsString(body));
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
    }
}
