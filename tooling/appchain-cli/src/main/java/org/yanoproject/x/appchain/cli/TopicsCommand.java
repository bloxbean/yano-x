package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yanoproject.x.appchain.formats.SealedApp;
import org.yanoproject.x.devtools.TopicsCli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code topics}: namespaces and writer policies (ADR-038) from an operator's node directory. Plans, signatures
 * and proofs come from the topics tool with this node's address, API key and only chain as defaults;
 * {@code approve} is this member's vote on a governance plan, cast through this node.
 */
final class TopicsCommand {
    private static final ObjectMapper JSON = new ObjectMapper();
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
        if (!json && !rest.isEmpty() && "submit".equals(rest.getFirst()) && !rest.contains("--help")) {
            return submit(rest, defaults, error);
        }
        return new TopicsCli().run(rest.toArray(String[]::new), out, error, defaults);
    }

    /**
     * {@code topics submit}: submits through the topics tool, then, on this node, waits until each owner-signed
     * change is accepted and in effect, like {@code approve} does for votes.
     */
    private int submit(List<String> arguments, TopicsCli.Defaults defaults, PrintWriter error) throws IOException {
        boolean noWait = arguments.remove("--no-wait");
        boolean heartbeat = arguments.remove("--heartbeat");
        String timeout = take(arguments, "timeout", "300");
        StringWriter buffer = new StringWriter();
        TopicsCli.Defaults quiet = new TopicsCli.Defaults(defaults.peer(), defaults.apiKey(), defaults.chain(), false);
        int exit = new TopicsCli().run(arguments.toArray(String[]::new), new PrintWriter(buffer), error, quiet);
        if (exit != 0) return exit;
        JsonNode result = JSON.readTree(buffer.toString());
        Path planFile = Path.of(planArgument(arguments));
        Map<String, Long> revisions = TopicsCli.plannedRevisions(planFile);
        Map<String, String> submitted = new LinkedHashMap<>();
        boolean votes = false;
        for (JsonNode command : result.path("commands")) {
            String target = command.path("target").asText();
            if ("MEMBER_GOVERNANCE_REQUIRED".equals(command.path("status").asText())) {
                terminal.say("  " + target + ": a member vote, not a submission");
                votes = true;
            } else {
                String id = command.path("submission").path("messageId").asText();
                terminal.ok("Submitted " + target + " (message " + id + ")");
                submitted.put(target, id);
            }
        }
        if (votes) {
            terminal.next("every operator runs  yano.sh appchain topics approve <the unsigned plan>");
            return 0;
        }
        String first = submitted.keySet().stream().findFirst().map(TopicsCommand::example).orElse("<topic>");
        if (noWait || defaults.peer() == null || submitted.isEmpty()) {
            terminal.next("it takes effect after the chain's activation lag; check with  yano.sh appchain topics "
                    + "resolve " + first);
            return 0;
        }
        NodeClient client = new NodeClient(defaults.peer(), defaults.apiKey());
        String chain = result.path("chainId").asText();
        String path = "/api/v1/app-chain/chains/" + chain;
        long deadline = System.currentTimeMillis() + 1000L * Integer.parseInt(timeout);
        boolean offer = Heartbeat.allowed(client, path);
        for (Map.Entry<String, String> change : submitted.entrySet()) {
            String target = change.getKey();
            Long revision = revisions.get(target);
            heartbeat = Await.until(terminal, client, path, chain,
                    ignored -> accepted(client, path, target, change.getValue(), revision), target, deadline,
                    heartbeat, offer, "a signed change activates after the chain's activation lag",
                    () -> "Send traffic to the chain, or run submit again with --heartbeat");
            terminal.ok(target + (revision == null ? " is revoked" : " is in effect"));
        }
        terminal.next("yano.sh appchain topics resolve " + first);
        return 0;
    }

    /** True once the command's receipt is ACCEPTED and its revision active; a refused receipt fails with why. */
    private static boolean accepted(NodeClient client, String path, String target, String messageId, Long revision) {
        JsonNode receipt = client.get(path + "/topics/receipts/" + messageId).orElse(null);
        if (receipt == null) return false;
        String code = receipt.path("code").asText();
        if (!"ACCEPTED".equals(code)) {
            throw CliException.failure(target + " was refused: " + TopicsCli.explain(code));
        }
        if (revision == null) return true;
        String name = URLEncoder.encode(target, StandardCharsets.UTF_8).replace("+", "%20");
        return client.get(path + "/topics/entries/" + name)
                .map(entry -> entry.path("active").path("revision").asLong(0) >= revision).orElse(false);
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
        if (Arrays.asList(arguments).contains("--help")) {
            terminal.say("Usage: " + APPROVE_USAGE);
            terminal.say("""
                      Casts this operator's member vote on each governance command of a plan made by
                      topics plan, through this node, after showing what it votes for (--yes skips the
                      question). It then waits until the change is in effect: enough operators must vote and
                      the chain must make its activation-lag blocks; --heartbeat sends small messages so an idle
                      chain makes them. It never votes twice: run it again at any time to see where it stands.""");
            return 0;
        }
        Args args = new Args(arguments, Set.of("node-dir", "timeout", "app"), Set.of("heartbeat", "no-wait", "yes"));
        if (args.positional(0) == null) throw CliException.usage("Name the plan. Usage: " + APPROVE_USAGE);
        NodeDir node = new NodeDir(args.path("node-dir", Path.of(NodeDir.DEFAULT)));
        Path planFile = Path.of(args.positional(0));
        List<TopicsCli.Vote> votes;
        try {
            votes = TopicsCli.votes(planFile);
        } catch (IllegalArgumentException invalid) {
            throw CliException.failure(planFile.getFileName() + " is not a valid topics plan: " + invalid.getMessage());
        } catch (IOException unreadable) {
            throw CliException.failure(planFile + " is not a plan; name the <name>.plan.json that topics plan wrote");
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
        terminal.say(planFile.getFileName() + " asks the members of " + chainId + " (" + app.genesis().name()
                + ") to vote on:");
        for (TopicsCli.Vote vote : votes) terminal.say("  " + describe(vote));
        if (!args.flag("yes")) {
            if (!terminal.interactive()) {
                throw CliException.usage("Check the change above, then pass --yes to vote as " + self);
            }
            if (!terminal.confirm("Vote yes as " + self + "?", false)) {
                terminal.say("No vote cast.");
                return CliException.FAILURE;
            }
        }
        List<TopicsCli.Vote> pending = castVotes(client, path, node.publicKey(), votes);
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
        boolean offer = Heartbeat.allowed(client, path);
        if (!offer) {
            terminal.say(chainId + " refuses unregistered topics, so heartbeats cannot make blocks there; "
                    + "the change activates with the chain's own traffic.");
        }
        boolean heartbeat = args.flag("heartbeat") && offer;
        for (TopicsCli.Vote vote : pending) {
            heartbeat = Await.until(terminal, client, path, chainId, ignored -> inEffect(client, path, vote),
                    vote.operation() + " " + vote.target(), deadline, heartbeat, offer,
                    "it activates once enough operators vote and the chain makes its activation-lag blocks",
                    () -> waitingReason(client, path, node.publicKey(), vote));
            terminal.ok(describe(vote) + " is in effect");
        }
        terminal.next(next(votes.getFirst()));
        return 0;
    }

    /**
     * Votes where this member has not voted in the current tally, and returns the votes still to take effect. A vote
     * after quorum would open a second tally that re-applies the change, so a decided change is never voted again.
     */
    List<TopicsCli.Vote> castVotes(NodeClient client, String path, String memberKey, List<TopicsCli.Vote> votes) {
        List<TopicsCli.Vote> pending = new ArrayList<>();
        for (TopicsCli.Vote vote : votes) {
            String what = describe(vote);
            switch (progress(client, path, memberKey, vote)) {
                case IN_EFFECT -> terminal.ok(what + ": already in effect");
                case DECIDED -> {
                    terminal.ok(what + ": enough operators voted; it activates after the activation lag");
                    pending.add(vote);
                }
                case VOTED -> {
                    terminal.ok(what + ": this node already voted; waiting for the other operators");
                    pending.add(vote);
                }
                case OPEN -> {
                    JsonNode submitted = client.post(path + "/admin/topics/governance",
                            Map.of("commandHex", vote.commandHex()));
                    terminal.ok(what + ": voted (message " + submitted.path("messageId").asText() + ")");
                    pending.add(vote);
                }
                case SUPERSEDED -> throw superseded(vote);
            }
        }
        return pending;
    }

    private boolean inEffect(NodeClient client, String path, TopicsCli.Vote vote) {
        TopicsCli.Progress progress = progress(client, path, null, vote);
        if (progress == TopicsCli.Progress.SUPERSEDED) throw superseded(vote);
        return progress == TopicsCli.Progress.IN_EFFECT;
    }

    /** Where a vote stands on this node: its target's entry and, for this member, the governance tally. */
    private static TopicsCli.Progress progress(NodeClient client, String path, String memberKey,
                                               TopicsCli.Vote vote) {
        String name = URLEncoder.encode(vote.target(), StandardCharsets.UTF_8).replace("+", "%20");
        JsonNode entry = client.get(path + "/topics/entries/" + name).orElse(null);
        String tally = null;
        long height = 0;
        long window = 0;
        if (memberKey != null) {
            JsonNode state = client.get(path + "/state/entry/" + TopicsCli.governanceTallyKeyHex()).orElse(null);
            tally = state == null || !"PRESENT".equals(state.path("presence").asText()) ? null
                    : state.path("valueHex").asText(null);
            height = state == null ? 0 : state.path("height").asLong(0);
            if (height == 0) {
                height = client.get(path + "/status").map(status -> status.path("stateCommitment")
                        .path("version").asLong()).orElse(0L);
            }
            window = client.get(path + "/topics/config").map(config -> config.path("config").path("settings")
                    .path("governanceWindowBlocks").asLong(0)).orElse(0L);
        }
        return TopicsCli.progress(vote, memberKey == null ? "" : memberKey, entry, tally, height, window);
    }

    /** Why a vote is not in effect yet, for the timeout message. */
    private static String waitingReason(NodeClient client, String path, String memberKey, TopicsCli.Vote vote) {
        return switch (progress(client, path, memberKey, vote)) {
            case DECIDED -> "Enough operators voted; the chain must make its activation-lag blocks. Send traffic, "
                    + "or run this command again with --heartbeat.";
            case VOTED -> "Not every needed operator has voted yet; run this command again later (it never votes "
                    + "twice).";
            default -> "Run this command again later; it never votes twice.";
        };
    }

    private static CliException superseded(TopicsCli.Vote vote) {
        return CliException.failure(vote.target() + " changed since this plan was made (another change holds "
                + "revision " + vote.revision() + "); check it with  yano.sh appchain topics resolve "
                + example(vote.target()) + "  and plan again if still needed");
    }

    private static String describe(TopicsCli.Vote vote) {
        String owners = vote.owners() == null ? "" : " with owners " + vote.owners().path("threshold").asText()
                + " of " + vote.owners().path("keys").size() + " keys";
        return vote.operation() + " " + vote.target() + owners;
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

    /** The plan named by {@code --plan} or as the first positional argument after the action. */
    private static String planArgument(List<String> arguments) {
        for (int index = 1; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (argument.equals("--plan")) return arguments.get(index + 1);
            if (argument.startsWith("--")) index++;
            else return argument;
        }
        throw CliException.usage("Name the signed plan. Usage: yano.sh appchain topics submit <signed plan.json>");
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
