package org.yanoproject.x.appchain.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.yanoproject.x.appchain.formats.Draft;
import org.yanoproject.x.appchain.formats.FormatException;
import org.yanoproject.x.appchain.formats.MemberCard;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * {@code invite --serve} (ADR-056 D6): a temporary listener on the coordinator's machine that carries the Phase A
 * files automatically. Each expected operator gets one link; the link fetches the draft, posts that operator's card,
 * and later fetches the sealed file. Everything it carries is public and checked by invite code or signature, so the
 * link only keeps strangers from posting cards; the fingerprint comparison stays the trust step.
 */
final class InviteCommand {
    static final String USAGE = "yano.sh appchain invite <app>.draft.yaml --serve [--host <public-host>]"
            + " [--port 7099] [--expires-hours 24] [--seal]";
    static final String SCHEME = "yano://";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_CARD_BYTES = 16 * 1024;

    private final Terminal terminal;
    private final Distribution distribution;
    private final java.io.PrintStream err;

    InviteCommand(Terminal terminal, Distribution distribution, java.io.PrintStream err) {
        this.terminal = terminal;
        this.distribution = distribution;
        this.err = err;
    }

    int run(String[] arguments) throws IOException {
        Args args = new Args(arguments, Set.of("host", "port", "expires-hours"), Set.of("serve", "seal", "yes"));
        args.maxPositionals(1, USAGE);
        if (args.positional(0) == null || !args.flag("serve")) throw CliException.usage("Usage: " + USAGE);
        Path draftFile = Path.of(args.positional(0)).toAbsolutePath().normalize();
        Draft draft = Draft.parse(NodeDir.read(draftFile));
        Path directory = draftFile.getParent();
        Path sealed = directory.resolve(draft.name() + ".chain");
        int port = args.integer("port") == null ? 7099 : args.integer("port");
        String host = args.value("host") != null ? args.value("host")
                : terminal.ask("Address operators reach this machine at (host name or IP)",
                "devnet".equals(draft.network()) ? "127.0.0.1" : null, "--host");
        Instant expires = Instant.now().plus(Duration.ofHours(
                args.integer("expires-hours") == null ? 24 : args.integer("expires-hours")));

        Map<String, String> tokens = new LinkedHashMap<>();
        for (String operator : draft.operators()) {
            byte[] token = new byte[12];
            RANDOM.nextBytes(token);
            tokens.put(HexFormat.of().formatHex(token), operator);
        }
        Set<String> delivered = ConcurrentHashMap.newKeySet();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException busy) {
            throw CliException.failure("Port " + port + " is in use; pass --port with a free one");
        }
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/v1/draft", exchange -> handle(exchange, tokens, expires, "GET", operator -> {
            byte[] body = Files.readAllBytes(draftFile);
            exchange.getResponseHeaders().add("X-Operator-Name", operator);
            send(exchange, 200, "application/yaml", body);
        }));
        server.createContext("/v1/card", exchange -> handle(exchange, tokens, expires, "POST", operator -> {
            byte[] body = readBounded(exchange.getRequestBody());
            MemberCard card;
            try {
                card = MemberCard.parse(new String(body, StandardCharsets.UTF_8))
                        .verifiedFor(Draft.parse(NodeDir.read(draftFile)).invite());
            } catch (FormatException invalid) {
                send(exchange, 400, "text/plain", invalid.getMessage().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!card.name().equals(operator)) {
                send(exchange, 403, "text/plain", ("This link is for " + operator + ", not " + card.name())
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            Path file = directory.resolve(operator + ".member");
            if (Files.exists(sealed)) {
                send(exchange, 409, "text/plain", "The draft is already sealed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            NodeDir.write(file, card.toJson());
            synchronized (terminal) {
                terminal.ok("Card from " + operator + " (key " + Show.keyId(card.publicKey()) + ", " + card.node()
                        + ")");
            }
            send(exchange, 200, "application/json", "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8));
        }));
        server.createContext("/v1/chain", exchange -> handle(exchange, tokens, expires, "GET", operator -> {
            if (!Files.isRegularFile(sealed)) {
                send(exchange, 202, "application/json", ("{\"sealed\":false,\"missing\":"
                        + names(missing(draft, directory)) + "}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 200, "application/json", Files.readAllBytes(sealed));
            delivered.add(operator);
            synchronized (terminal) {
                terminal.ok(operator + " received " + sealed.getFileName());
            }
        }));
        server.start();
        try {
            terminal.say("Serving " + draftFile.getFileName() + " on port " + port + " until "
                    + expires.toString().substring(0, 16).replace('T', ' ') + " UTC. Send each operator their link:");
            tokens.forEach((token, operator) -> terminal.say(String.format(java.util.Locale.ROOT,
                    "  %-12s yano.sh appchain request %s%s:%d/%s#%s", operator, SCHEME, host, port, token,
                    draft.invite())));
            terminal.say("Links are single-use per operator and carry only public data. Still compare the chain "
                    + "fingerprint and key ids on a call before anyone starts.");
            boolean askedToSeal = false;
            while (Instant.now().isBefore(expires)) {
                if (delivered.containsAll(tokens.values())) {
                    terminal.ok("Every operator has the sealed file; the listener stops.");
                    terminal.next("on a call, read out the chain fingerprint and each key id; then each operator "
                            + "runs  yano.sh appchain start");
                    return 0;
                }
                if (!askedToSeal && !Files.exists(sealed) && missing(draft, directory).isEmpty()) {
                    askedToSeal = true;
                    if (args.flag("seal") || terminal.confirm("All " + draft.operators().size()
                            + " cards are in. Seal now?", true)) {
                        // The listener hands the file out, so seal's own "send it" advice does not apply here.
                        Terminal quiet = new Terminal(null, terminal.out(), false, false);
                        int exit = new SealCommand(quiet, distribution).run(new String[]{draftFile.toString()});
                        if (exit != 0) return exit;
                    } else {
                        terminal.say("Seal when ready with  yano.sh appchain seal " + draftFile.getFileName()
                                + "  in another terminal; this listener then hands it out.");
                    }
                }
                Devnet.sleep(1000);
            }
            throw CliException.notReady("The links expired before every operator received the sealed file; run "
                    + "invite --serve again (a new run prints new links)");
        } finally {
            server.stop(0);
        }
    }

    private static java.util.List<String> missing(Draft draft, Path directory) {
        return draft.operators().stream().filter(name -> !Files.isRegularFile(directory.resolve(name + ".member")))
                .toList();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(String operator) throws IOException;
    }

    private void handle(HttpExchange exchange, Map<String, String> tokens, Instant expires, String method,
                        Handler handler) throws IOException {
        try (exchange) {
            if (!method.equals(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain", new byte[0]);
                return;
            }
            if (Instant.now().isAfter(expires)) {
                send(exchange, 410, "text/plain", "This invitation expired".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String operator = tokens.get(String.valueOf(exchange.getRequestHeaders().getFirst("X-Invite-Token")));
            if (operator == null) {
                send(exchange, 403, "text/plain", "Unknown invitation link".getBytes(StandardCharsets.UTF_8));
                return;
            }
            handler.handle(operator);
        } catch (IOException | RuntimeException failure) {
            err.println("invite: " + failure.getMessage());
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        byte[] body = input.readNBytes(MAX_CARD_BYTES + 1);
        if (body.length > MAX_CARD_BYTES) throw new IOException("member card too large");
        return body;
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
    }

    private static String names(java.util.List<String> names) {
        return "[" + String.join(",", names.stream().map(name -> "\"" + name + "\"").toList()) + "]";
    }
}
