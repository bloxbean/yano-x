package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.yanoproject.x.appchain.formats.SealedApp;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code try}: the whole ceremony on one machine with three simulated operators, printing each real command as
 * it runs it, so a beginner learns the production steps. {@code --clean} stops and removes the rehearsal.
 */
final class TryCommand {
    static final String USAGE = "yano.sh appchain try [--dir appchain-try] [--clean]";
    private static final String MARKER = ".appchain-try";
    private static final List<String> OPERATORS = List.of("alice", "bob", "carol");

    private final Terminal terminal;
    private final PrintStream err;

    TryCommand(Terminal terminal, PrintStream err) {
        this.terminal = terminal;
        this.err = err;
    }

    int run(String[] arguments) throws IOException {
        Args args = new Args(arguments, Set.of("dir"), Set.of("clean", "yes"));
        args.maxPositionals(0, USAGE);
        Path root = args.path("dir", Path.of("appchain-try")).toAbsolutePath().normalize();
        if (args.flag("clean")) return clean(root);
        if (Files.exists(root)) {
            throw CliException.failure(root + " already exists. Look around with  yano.sh appchain status --node-dir "
                    + rel(root.resolve("alice/yano-node")) + "  or remove it with  yano.sh appchain try --clean");
        }
        Files.createDirectories(root);
        Files.writeString(root.resolve(MARKER), "rehearsal directory created by yano.sh appchain try\n");
        Path coordinator = root.resolve("coordinator");
        Files.createDirectories(coordinator);

        terminal.say("This rehearsal runs every real step on this machine: one coordinator and three operators");
        terminal.say("(alice, bob, carol), each with their own node directory under " + rel(root) + ".");
        terminal.say("In real life each operator runs their steps on their own machine and sends files to the others.");

        step("1. The coordinator creates the application (a wizard asks these questions when run by hand).");
        exec(coordinator, "new", "demo", "--network", "devnet", "--operators", String.join(",", OPERATORS),
                "--chain", "orders=audit-log", "--output", coordinator.toString(), "--yes");

        step("2. Each operator makes their node key and a member card, and sends the card to the coordinator.");
        Set<Integer> used = new HashSet<>();
        for (String operator : OPERATORS) {
            int node = Ports.firstFree(13337, used);
            used.add(node);
            int http = Ports.firstFree(8080, used);
            used.add(http);
            exec(root.resolve(operator), "request", coordinator.resolve("demo.draft.yaml").toString(),
                    "--name", operator, "--host", "127.0.0.1", "--node-port", Integer.toString(node),
                    "--http-port", Integer.toString(http), "--output", coordinator.toString(),
                    "--node-dir", root.resolve(operator).resolve("yano-node").toString(), "--yes");
        }

        step("3. The coordinator seals the draft with the cards. Everyone compares the fingerprint on a call.");
        exec(coordinator, "seal", coordinator.resolve("demo.draft.yaml").toString());
        SealedApp sealed = SealedApp.parse(NodeDir.read(coordinator.resolve("demo.chain")));

        step("4. Each operator joins, confirming the fingerprint they compared.");
        for (String operator : OPERATORS) {
            exec(root.resolve(operator), "join", coordinator.resolve("demo.chain").toString(),
                    "--expect-fingerprint", sealed.displayFingerprint(),
                    "--node-dir", root.resolve(operator).resolve("yano-node").toString());
        }

        step("5. Each operator starts their node. The lead (alice) starts the devnet; the others follow it.");
        for (String operator : OPERATORS) {
            exec(root.resolve(operator), "start", "--node-dir", root.resolve(operator).resolve("yano-node").toString());
        }

        step("6. Anyone checks that every member is online and agrees.");
        exec(root.resolve("bob"), "status", "--wait", "live", "--timeout", "180",
                "--node-dir", root.resolve("bob").resolve("yano-node").toString());

        step("7. An application submits a message through bob's node; all three nodes finalize it.");
        NodeDir bob = new NodeDir(root.resolve("bob").resolve("yano-node"));
        NodeClient client = NodeClient.local(bob);
        terminal.say("$ curl -X POST " + client.base() + "/api/v1/app-chain/chains/orders/messages -H 'X-API-Key: …' "
                + "-d '{\"topic\":\"orders\",\"body\":\"order A-100 created\"}'");
        JsonNode accepted = client.post("/api/v1/app-chain/chains/orders/messages",
                Map.of("topic", "orders", "body", "order A-100 created"));
        String id = accepted.path("messageId").asText();
        long height = 0;
        for (int attempt = 0; attempt < 60 && height == 0; attempt++) {
            Devnet.sleep(1000);
            height = client.get("/api/v1/app-chain/chains/orders/messages/" + id)
                    .map(message -> message.path("height").asLong()).orElse(0L);
        }
        if (height == 0) throw CliException.notReady("The message was not finalized within a minute");
        for (String operator : OPERATORS) {
            NodeClient view = NodeClient.local(new NodeDir(root.resolve(operator).resolve("yano-node")));
            JsonNode block = view.get("/api/v1/app-chain/chains/orders/blocks/" + height).orElseThrow();
            terminal.say(String.format(java.util.Locale.ROOT, "  %-6s height %d  root %s…", operator, height,
                    block.path("stateRoot").asText().substring(0, 16)));
        }
        terminal.ok("Message " + id.substring(0, 16) + "… is final at height " + height + " on every node");

        terminal.next("look around:  yano.sh appchain status --node-dir " + rel(root.resolve("alice/yano-node")),
                "try the optional step:  yano.sh appchain anchor --node-dir " + rel(root.resolve("alice/yano-node")),
                "stop and remove the rehearsal:  yano.sh appchain try --clean",
                "set up a real application:  yano.sh appchain new");
        return 0;
    }

    private void step(String text) {
        terminal.blank();
        terminal.say("── " + text);
    }

    /** Prints the command an operator would type, then runs it in this process. */
    private void exec(Path where, String... arguments) {
        List<String> shown = new ArrayList<>();
        for (String argument : arguments) shown.add(argument.startsWith("/") ? rel(Path.of(argument)) : argument);
        terminal.say("$ yano.sh appchain " + String.join(" ", shown));
        int exit = AppChainCli.run(arguments, new Terminal(null, terminal.out(), false, false), err);
        if (exit != 0) throw CliException.failure("The rehearsal stopped at that step (exit " + exit + ")");
    }

    private int clean(Path root) throws IOException {
        if (!Files.exists(root)) {
            terminal.say("Nothing to clean: " + rel(root) + " does not exist");
            return 0;
        }
        if (!Files.isRegularFile(root.resolve(MARKER))) {
            throw CliException.failure(root + " was not created by try; not removing it");
        }
        for (String operator : OPERATORS) {
            Path node = root.resolve(operator).resolve("yano-node");
            if (Files.isRegularFile(node.resolve("node.json"))) new StopCommand(terminal).stop(new NodeDir(node));
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        terminal.ok("Removed the rehearsal in " + rel(root));
        return 0;
    }

    private static String rel(Path path) {
        Path here = Path.of("").toAbsolutePath();
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(here) ? here.relativize(absolute).toString() : absolute.toString();
    }
}
