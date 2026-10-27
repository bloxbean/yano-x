package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.yanoproject.x.appchain.formats.Draft;
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
 * it runs it, so a beginner learns the production steps. {@code --chain} and {@code --answer} take the same values
 * as {@code new} (and {@code --from} a template), so any recipe or several chains can be rehearsed. {@code --clean}
 * stops and removes the rehearsal.
 */
final class TryCommand {
    static final String USAGE = "yano.sh appchain try [--from <template.yaml>]"
            + " [--chain <id>=<recipe>|<id>=bindings:<file>]... [--answer <chain>.<name>=<value>]..."
            + " [--setting <chain>.<key>=<value>]... [--without <chain>]..."
            + " [--topics <chain>=members|anyone|registrars:<a>+<b>]... [--dir appchain-try] [--clean]";
    private static final String LOG_RECIPE = "audit-log";
    private static final String MARKER = ".appchain-try";
    private static final List<String> OPERATORS = List.of("alice", "bob", "carol");

    private final Terminal terminal;
    private final PrintStream err;

    TryCommand(Terminal terminal, PrintStream err) {
        this.terminal = terminal;
        this.err = err;
    }

    int run(String[] arguments) throws IOException {
        Args args = new Args(arguments, Set.of("dir", "from", "chain", "answer", "setting", "without", "topics"),
                Set.of("clean", "yes"));
        args.maxPositionals(0, USAGE);
        Path root = args.path("dir", Path.of("appchain-try")).toAbsolutePath().normalize();
        if (args.flag("clean")) return clean(root);
        String clean = "yano.sh appchain try --clean" + (args.value("dir") == null ? "" : " --dir " + rel(root));
        if (Files.exists(root)) {
            throw CliException.failure(root + " already exists. Look around with  yano.sh appchain status --node-dir "
                    + rel(root.resolve("alice/yano-node")) + "  or remove it with  " + clean);
        }
        Files.createDirectories(root);
        Files.writeString(root.resolve(MARKER), "rehearsal directory created by yano.sh appchain try\n");
        Path coordinator = root.resolve("coordinator");
        Files.createDirectories(coordinator);

        terminal.say("This rehearsal runs every real step on this machine: one coordinator and three operators");
        terminal.say("(alice, bob, carol), each with their own node directory under " + rel(root) + ".");
        terminal.say("In real life each operator runs their steps on their own machine and sends files to the others.");

        step("1. The coordinator creates the application (a wizard asks these questions when run by hand).");
        List<String> create = new ArrayList<>(List.of("new", "demo", "--network", "devnet", "--operators",
                String.join(",", OPERATORS)));
        if (args.value("from") != null) {
            create.addAll(List.of("--from", Path.of(args.value("from")).toAbsolutePath().toString()));
        }
        List<String> chains = args.values("chain").isEmpty() && args.value("from") == null
                ? List.of("orders=" + LOG_RECIPE) : args.values("chain");
        for (String chain : chains) create.addAll(List.of("--chain", chain));
        for (String option : List.of("answer", "setting", "without", "topics")) {
            for (String value : args.values(option)) create.addAll(List.of("--" + option, value));
        }
        create.addAll(List.of("--output", coordinator.toString(), "--yes"));
        try {
            exec(coordinator, create.toArray(String[]::new));
        } catch (CliException | IllegalArgumentException failed) {
            // Nothing runs yet, so a mistyped chain or a missing answer leaves no rehearsal behind.
            delete(root);
            throw failed;
        }
        Draft draft = Draft.parse(NodeDir.read(coordinator.resolve("demo.draft.yaml")));

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

        String log = draft.chains().stream().filter(chain -> LOG_RECIPE.equals(chain.recipe()))
                .map(Draft.Chain::id).findFirst().orElse(null);
        if (log != null) finalizeMessage(root, log);

        List<String> next = new ArrayList<>(List.of(
                "look around:  yano.sh appchain status --node-dir " + rel(root.resolve("alice/yano-node")),
                "try the optional step:  yano.sh appchain anchor --node-dir " + rel(root.resolve("alice/yano-node"))));
        if (draft.chains().stream().anyMatch(chain -> chain.topics() != null)) {
            next.add("protect a namespace:  yano.sh appchain topics init games/ --node-dir "
                    + rel(root.resolve("alice/yano-node")) + "  (then plan, and approve from each node)");
        }
        next.add("stop and remove the rehearsal:  " + clean);
        next.add("set up a real application:  yano.sh appchain new");
        terminal.next(next.toArray(String[]::new));
        return 0;
    }

    /** Step 7, for a log chain: one message submitted through bob's node is final with the same root everywhere. */
    private void finalizeMessage(Path root, String chainId) throws IOException {
        step("7. An application submits a message through bob's node; all three nodes finalize it.");
        String messages = "/api/v1/app-chain/chains/" + chainId + "/messages";
        NodeDir bob = new NodeDir(root.resolve("bob").resolve("yano-node"));
        NodeClient client = NodeClient.local(bob);
        terminal.say("$ curl -X POST " + client.base() + messages + " -H 'X-API-Key: …' "
                + "-d '{\"topic\":\"orders\",\"body\":\"order A-100 created\"}'");
        JsonNode accepted = client.post(messages, Map.of("topic", "orders", "body", "order A-100 created"));
        String id = accepted.path("messageId").asText();
        long height = 0;
        for (int attempt = 0; attempt < 60 && height == 0; attempt++) {
            Devnet.sleep(1000);
            height = client.get(messages + "/" + id)
                    .map(message -> message.path("height").asLong()).orElse(0L);
        }
        if (height == 0) throw CliException.notReady("The message was not finalized within a minute");
        for (String operator : OPERATORS) {
            NodeClient view = NodeClient.local(new NodeDir(root.resolve(operator).resolve("yano-node")));
            JsonNode block = view.get("/api/v1/app-chain/chains/" + chainId + "/blocks/" + height).orElseThrow();
            terminal.say(String.format(java.util.Locale.ROOT, "  %-6s height %d  root %s…", operator, height,
                    block.path("stateRoot").asText().substring(0, 16)));
        }
        terminal.ok("Message " + id.substring(0, 16) + "… is final at height " + height + " on every node");
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
        delete(root);
        terminal.ok("Removed the rehearsal in " + rel(root));
        return 0;
    }

    private static void delete(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static String rel(Path path) {
        Path here = Path.of("").toAbsolutePath();
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(here) ? here.relativize(absolute).toString() : absolute.toString();
    }
}
