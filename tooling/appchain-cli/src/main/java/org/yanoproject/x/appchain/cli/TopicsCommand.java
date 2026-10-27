package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.yanoproject.x.appchain.formats.SealedApp;
import org.yanoproject.x.devtools.TopicsCli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code topics}: namespaces and writer policies (ADR-038) from an operator's node directory. Plans, signatures
 * and proofs come from the topics tool with this node's address, API key and only chain as defaults;
 * {@code approve} is this member's vote on a governance plan, cast through this node.
 */
final class TopicsCommand {
    static final String APPROVE_USAGE = "yano.sh appchain topics approve <plan.json> [<app>] [--node-dir yano-node]"
            + " [--heartbeat] [--timeout <seconds>] [--no-wait] [--yes]";

    private final Terminal terminal;
    private final PrintStream err;

    TopicsCommand(Terminal terminal, PrintStream err) {
        this.terminal = terminal;
        this.err = err;
    }

    int run(String[] arguments) throws IOException {
        if (arguments.length > 0 && "approve".equals(arguments[0])) {
            return approve(Arrays.copyOfRange(arguments, 1, arguments.length));
        }
        List<String> rest = new ArrayList<>(Arrays.asList(arguments));
        Path nodeDir = Path.of(take(rest, "node-dir", NodeDir.DEFAULT));
        String app = take(rest, "app", null);
        boolean json = rest.remove("--json");
        TopicsCli.Defaults defaults = defaults(new NodeDir(nodeDir), app, !json);
        PrintWriter out = new PrintWriter(terminal.out(), true, StandardCharsets.UTF_8);
        PrintWriter error = new PrintWriter(err, true, StandardCharsets.UTF_8);
        return new TopicsCli().run(rest.toArray(String[]::new), out, error, defaults);
    }

    /** This node's API, its key and the application's only chain, when this is a node directory. */
    static TopicsCli.Defaults defaults(NodeDir node, String appName, boolean guided) {
        NodeDir.Identity identity = node.identity();
        if (identity == null) return new TopicsCli.Defaults(null, null, null, guided);
        String peer = "http://127.0.0.1:" + identity.httpPort();
        String chain = null;
        List<SealedApp> apps = node.apps();
        SealedApp app = appName != null ? node.app(appName) : apps.size() == 1 ? apps.getFirst() : null;
        if (app != null && app.genesis().chains().size() == 1) chain = app.genesis().chains().getFirst().id();
        return new TopicsCli.Defaults(peer, node.apiKey(), chain, guided);
    }

    private int approve(String[] arguments) throws IOException {
        Args args = new Args(arguments, Set.of("node-dir", "timeout", "app"), Set.of("heartbeat", "no-wait", "yes"));
        if (args.positional(0) == null) throw CliException.usage("Name the plan. Usage: " + APPROVE_USAGE);
        NodeDir node = new NodeDir(args.path("node-dir", Path.of(NodeDir.DEFAULT)));
        Path planFile = Path.of(args.positional(0));
        List<TopicsCli.Vote> votes;
        try {
            votes = TopicsCli.votes(planFile);
        } catch (IllegalArgumentException invalid) {
            throw CliException.failure(planFile.getFileName() + " is not a valid topics plan: " + invalid.getMessage());
        } catch (java.io.IOException unreadable) {
            throw CliException.failure("Cannot read " + planFile + "; name the plan file that topics plan wrote");
        }
        String chainId = votes.getFirst().chainId();
        String appName = args.value("app") != null ? args.value("app") : args.positional(1);
        SealedApp app = appName != null ? node.app(appName) : node.apps().stream()
                .filter(candidate -> candidate.genesis().chains().stream().anyMatch(c -> c.id().equals(chainId)))
                .findFirst().orElseThrow(() -> CliException.failure("This node does not run chain " + chainId
                        + "; run approve in the node directory of an operator of that chain (--node-dir)"));
        if (app.genesis().chains().stream().noneMatch(chain -> chain.id().equals(chainId))) {
            throw CliException.failure(app.genesis().name() + " has no chain " + chainId);
        }
        if (!Processes.running(node) || !NodeClient.local(node).ready()) {
            throw CliException.failure("This node must be running; run  yano.sh appchain start  first");
        }
        NodeClient client = NodeClient.local(node);
        String path = "/api/v1/app-chain/chains/" + chainId;
        JsonNode status = client.get(path + "/status")
                .orElseThrow(() -> CliException.failure("Cannot read the status of " + chainId + " from this node"));
        if (!votes.getFirst().generationId().equals(status.path("stateCommitment").path("generationId").asText())) {
            throw CliException.failure(planFile.getFileName() + " was made for another generation of " + chainId
                    + "; plan it again with  yano.sh appchain topics plan <topics.yaml>");
        }

        String self = node.requireIdentity().name();
        terminal.say("Voting as " + self + " on " + chainId + " (" + app.genesis().name() + ")");
        List<TopicsCli.Vote> pending = new ArrayList<>();
        for (TopicsCli.Vote vote : votes) {
            String what = vote.operation() + " " + vote.target();
            if (activated(client, path, vote)) {
                terminal.ok(what + ": already in effect");
                continue;
            }
            try {
                JsonNode submitted = client.post(path + "/admin/topics/governance",
                        Map.of("commandHex", vote.commandHex()));
                terminal.ok(what + ": voted (message " + submitted.path("messageId").asText() + ")");
            } catch (CliException refused) {
                // A vote this node already cast is refused as a replay; the tally keeps the first one.
                if (!refused.getMessage().contains("REPLAY") && !refused.getMessage().contains("USED")) throw refused;
                terminal.ok(what + ": this node already voted");
            }
            pending.add(vote);
        }
        if (pending.isEmpty()) {
            terminal.next("yano.sh appchain topics resolve " + example(votes.getFirst().target()));
            return 0;
        }
        if (args.flag("no-wait")) {
            terminal.next("when enough operators have voted, the change activates; check with",
                    "  yano.sh appchain topics approve " + planFile.getFileName() + "   (safe to run again)");
            return 0;
        }
        long deadline = System.currentTimeMillis() + 1000L * (args.integer("timeout") == null ? 900
                : args.integer("timeout"));
        boolean heartbeat = args.flag("heartbeat") && heartbeats(app, chainId);
        if (!heartbeats(app, chainId)) {
            terminal.say(chainId + " refuses unregistered topics, so heartbeats cannot make blocks there; "
                    + "the change activates with the chain's own traffic.");
        }
        for (TopicsCli.Vote vote : pending) {
            heartbeat = Await.until(terminal, client, path, chainId, ignored -> activated(client, path, vote),
                    vote.operation() + " " + vote.target(), deadline, heartbeat && heartbeats(app, chainId),
                    "it activates once enough operators vote and the chain makes its activation-lag blocks",
                    "The other operators may not have voted yet; run this command again later (it is safe to "
                            + "repeat)");
            terminal.ok(vote.operation() + " " + vote.target() + " is in effect");
        }
        terminal.next(next(votes.getFirst()));
        return 0;
    }

    /** True once the plan's revision of the target is the active one. */
    private static boolean activated(NodeClient client, String path, TopicsCli.Vote vote) {
        JsonNode entry = client.get(path + "/topics/entries/" + vote.target()).orElse(null);
        if (entry == null) return false;
        JsonNode active = entry.path("active");
        return !active.isMissingNode() && !active.isNull() && active.path("revision").asLong() >= vote.revision();
    }

    /** Heartbeats use an unregistered topic, which a chain that refuses unregistered topics never accepts. */
    private static boolean heartbeats(SealedApp app, String chainId) {
        return !"reject".equals(app.genesis().chain(chainId).settings().get("topics.unregistered-topics"));
    }

    private static String next(TopicsCli.Vote vote) {
        return switch (vote.operation()) {
            case "create", "recover" -> "the owners of " + vote.target() + " manage it with  yano.sh appchain topics "
                    + "plan <topics.yaml>  (authority " + vote.target() + "), then sign and submit";
            default -> "yano.sh appchain topics resolve " + example(vote.target());
        };
    }

    private static String example(String target) {
        return target.endsWith("/") ? target + "<topic>" : target;
    }

    /** Removes {@code --name value} from the arguments and returns the value. */
    private static String take(List<String> arguments, String name, String fallback) {
        int index = arguments.indexOf("--" + name);
        if (index < 0) return fallback;
        if (index + 1 >= arguments.size()) throw CliException.usage("--" + name + " needs a value");
        String value = arguments.get(index + 1);
        arguments.subList(index, index + 2).clear();
        return value;
    }
}
