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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        String named = take(rest, "node-dir", null);
        Path nodeDir = named == null ? NodeDir.defaultDir() : Path.of(named);
        String app = take(rest, "app", null);
        boolean json = rest.remove("--json");
        TopicsCli.Defaults defaults = defaults(new NodeDir(nodeDir), app, !json);
        PrintWriter out = new PrintWriter(terminal.out(), true, StandardCharsets.UTF_8);
        PrintWriter error = new PrintWriter(err, true, StandardCharsets.UTF_8);
        if (!json && !rest.isEmpty() && "submit".equals(rest.getFirst()) && !rest.contains("--help")) {
            return submit(rest, defaults, error, new NodeDir(nodeDir).path("topic-submissions"));
        }
        return new TopicsCli().run(rest.toArray(String[]::new), out, error, defaults);
    }

    /**
     * {@code topics submit}: submits through the topics tool, then, on this node, waits until each owner-signed
     * change is accepted and in effect, like {@code approve} does for votes.
     */
    private int submit(List<String> arguments, TopicsCli.Defaults defaults, PrintWriter error, Path records)
            throws IOException {
        boolean noWait = arguments.remove("--no-wait");
        boolean heartbeat = arguments.remove("--heartbeat");
        String timeout = take(arguments, "timeout", "300");
        StringWriter buffer = new StringWriter();
        StringWriter refused = new StringWriter();
        TopicsCli.Defaults quiet = new TopicsCli.Defaults(defaults.peer(), defaults.apiKey(), defaults.chain(), false);
        int exit = new TopicsCli().run(arguments.toArray(String[]::new), new PrintWriter(buffer),
                new PrintWriter(refused), quiet);
        Path planFile = Path.of(planArgument(arguments));
        if (exit != 0) {
            // Submitting the same signed plan again is refused as pending or stale; on this node, that is the
            // earlier submission still on its way, so wait for it rather than send the operator in circles.
            if (!noWait && defaults.peer() != null && !arguments.contains("--peer")
                    && refused.toString().matches("(?s).*TOPIC_COMMAND_(PENDING|STALE).*")
                    && alreadySubmitted(planFile, defaults)) {
                Map<String, String> earlier = submissions(planFile, records);
                if (earlier != null) {
                    return awaitResubmitted(planFile, earlier, defaults, heartbeat, Integer.parseInt(timeout));
                }
                error.print(refused);
                error.println("It was submitted before, but not from this node directory, so how it ended is not "
                        + "known here; check it with  yano.sh appchain topics resolve <target>");
                error.flush();
                return exit;
            }
            error.print(refused);
            error.flush();
            return exit;
        }
        JsonNode result = JSON.readTree(buffer.toString());
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
                if (defaults.peer() != null && !arguments.contains("--peer")) {
                    recordSubmission(planFile, records, target, id);
                }
            }
        }
        String voteLine = "every operator runs  yano.sh appchain topics approve <the unsigned plan>  for the votes";
        String first = submitted.keySet().stream().findFirst().map(TopicsCommand::example).orElse("<topic>");
        // Waiting reads the node that took the submission: this node, never a peer named on the command line.
        if (noWait || defaults.peer() == null || arguments.contains("--peer") || submitted.isEmpty()) {
            List<String> next = new ArrayList<>();
            if (!submitted.isEmpty()) {
                next.add("it takes effect after the chain's activation lag; check with  yano.sh appchain topics "
                        + "resolve " + first);
            }
            if (votes) next.add(voteLine);
            terminal.next(next.toArray(String[]::new));
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
                    ignored -> accepted(client, path, target, change.getValue(), revision),
                    TopicsCli.safeTarget(target), deadline,
                    heartbeat, offer, "a signed change activates after the chain's activation lag",
                    () -> "Send traffic to the chain, or run submit again with --heartbeat");
            terminal.ok(TopicsCli.safeTarget(target) + (revision == null ? " is revoked" : " is in effect"));
        }
        terminal.next(votes ? new String[] {voteLine, "then  yano.sh appchain topics resolve " + first}
                : new String[] {"yano.sh appchain topics resolve " + first});
        return 0;
    }

    /**
     * True once the command's receipt is ACCEPTED and its revision active, read together at one height; a refused
     * receipt, superseded included, fails with the reason.
     */
    private static boolean accepted(NodeClient client, String path, String target, String messageId, Long revision) {
        TopicsCli.Submission submission;
        try {
            long height = client.require(path + "/status").path("stateCommitment").path("version").asLong();
            submission = TopicsCli.submitted(pinned(client, path, height), messageId, target, revision);
        } catch (IOException | CliException unreadable) {
            return false; // tried again on the next round
        }
        if (submission.refusal() != null) {
            throw CliException.failure(TopicsCli.safeTarget(target) + " was refused: "
                    + TopicsCli.explain(submission.refusal()));
        }
        return submission.done();
    }

    /** Whether this node's chain already used every signed statement of the plan. */
    private static boolean alreadySubmitted(Path planFile, TopicsCli.Defaults defaults) {
        try {
            String chain = JSON.readTree(planFile.toFile()).path("snapshot").path("chainId").asText();
            NodeClient client = new NodeClient(defaults.peer(), defaults.apiKey());
            String path = "/api/v1/app-chain/chains/" + chain;
            long height = client.require(path + "/status").path("stateCommitment").path("version").asLong();
            TopicsCli.StateReader state = pinned(client, path, height);
            Map<String, String> hashes = TopicsCli.statementHashes(planFile);
            for (String hash : hashes.values()) if (!TopicsCli.used(state, hash)) return false;
            return !hashes.isEmpty();
        } catch (IOException | CliException unreadable) {
            return false;
        }
    }

    /** Remembers the message that carried a signed statement, so a re-run can follow its receipt. */
    private void recordSubmission(Path planFile, Path records, String target, String messageId) {
        try {
            String hash = TopicsCli.statementHashes(planFile).get(target);
            if (hash == null || !hash.matches("[0-9a-f]{64}")) return;
            Files.createDirectories(records);
            NodeDir.write(records.resolve(hash + ".json"), JSON.writeValueAsString(
                    Map.of("target", target, "messageId", messageId, "at", System.currentTimeMillis())) + "\n");
        } catch (IOException unwritable) {
            terminal.warn("Could not record the submission in " + records + ": " + unwritable.getMessage());
        }
    }

    /** The messages this node directory used for every signed statement of the plan, or null if any is unknown. */
    private static Map<String, String> submissions(Path planFile, Path records) {
        Map<String, String> messages = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, String> statement : TopicsCli.statementHashes(planFile).entrySet()) {
                Path file = records.resolve(statement.getValue() + ".json");
                if (!statement.getValue().matches("[0-9a-f]{64}") || !Files.isRegularFile(file)) return null;
                String id = JSON.readTree(file.toFile()).path("messageId").asText("");
                if (!id.matches("[0-9a-f]{64}")) return null;
                messages.put(statement.getKey(), id);
            }
        } catch (IOException unreadable) {
            return null;
        }
        return messages.isEmpty() ? null : messages;
    }

    /** Waits for this node directory's earlier submission of the same plan, by its receipts. */
    private int awaitResubmitted(Path planFile, Map<String, String> messages, TopicsCli.Defaults defaults,
            boolean heartbeat, int timeout) throws IOException {
        String chain = JSON.readTree(planFile.toFile()).path("snapshot").path("chainId").asText();
        NodeClient client = new NodeClient(defaults.peer(), defaults.apiKey());
        String path = "/api/v1/app-chain/chains/" + chain;
        terminal.ok(TopicsCli.shown(planFile.toString()) + " was already submitted; waiting for it to take effect");
        long deadline = System.currentTimeMillis() + 1000L * timeout;
        boolean offer = Heartbeat.allowed(client, path);
        Map<String, Long> revisions = TopicsCli.plannedRevisions(planFile);
        for (Map.Entry<String, String> change : messages.entrySet()) {
            String target = change.getKey();
            Long revision = revisions.get(target);
            // The receipt decides: a submission cancelled or superseded meanwhile is reported as refused.
            heartbeat = Await.until(terminal, client, path, chain,
                    ignored -> accepted(client, path, target, change.getValue(), revision),
                    TopicsCli.safeTarget(target), deadline, heartbeat, offer,
                    "a signed change activates after the chain's activation lag",
                    () -> "Send traffic to the chain, or run submit again with --heartbeat");
            terminal.ok(TopicsCli.safeTarget(target) + (revision == null ? " is revoked" : " is in effect"));
        }
        return 0;
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
                      topics plan, through this node, after showing exactly what it votes for (--yes skips
                      the question once you have checked it; --plan-sha256 refuses a plan file that changed).
                      It then waits until the change is in effect: enough operators must vote and the chain
                      must make its activation-lag blocks; --heartbeat sends small messages so an idle chain
                      makes them, and --no-wait returns once the vote is included.
                      This node records every vote it casts and never casts the same vote twice: run it again
                      at any time to see where the vote stands. --again votes again after an earlier vote
                      expired or was refused.""");
            return 0;
        }
        Args args = new Args(arguments, Set.of("node-dir", "timeout", "app", "plan-sha256"),
                Set.of("heartbeat", "no-wait", "yes", "again"));
        if (args.positional(0) == null) throw CliException.usage("Name the plan. Usage: " + APPROVE_USAGE);
        NodeDir node = new NodeDir(args.path("node-dir", NodeDir.defaultDir()));
        Path planFile = Path.of(args.positional(0));
        String shown = TopicsCli.shown(planFile.toString());
        List<TopicsCli.Vote> votes;
        try {
            // One read: the digest checked and the votes cast come from the same bytes.
            byte[] plan = TopicsCli.planBytes(planFile);
            if (args.value("plan-sha256") != null && !sha256(plan).equals(args.value("plan-sha256"))) {
                throw CliException.failure(shown + " is not the plan that was shown (its contents changed); "
                        + "look at it again before voting");
            }
            votes = TopicsCli.votes(plan);
        } catch (IllegalArgumentException invalid) {
            throw CliException.failure(shown + " is not a valid topics plan: " + invalid.getMessage());
        } catch (IOException unreadable) {
            throw CliException.failure(shown + " is not a plan; name the <name>.plan.json that topics plan wrote");
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
        JsonNode status = client.require(path + "/status");
        if (!votes.getFirst().generationId().equals(status.path("stateCommitment").path("generationId").asText())) {
            throw CliException.failure(shown + " was made for another generation of " + chainId
                    + "; plan it again with  yano.sh appchain topics plan <topics.yaml>");
        }

        String self = node.requireIdentity().name();
        Ballot ballot = new Ballot(client, path, node.publicKey(), node.path("topic-votes"), messageLifetimeMs(node));
        Map<TopicsCli.Vote, Standing> standings = new LinkedHashMap<>();
        for (TopicsCli.Vote vote : votes) standings.put(vote, ballot.standing(vote));
        boolean toVote = standings.values().stream().anyMatch(standing -> standing == Standing.OPEN)
                || args.flag("again") && standings.values().contains(Standing.LAPSED);
        if (toVote) {
            terminal.say(shown + " asks the members of " + chainId + " (" + app.genesis().name()
                    + ") to vote yes on:");
            votes.forEach(vote -> TopicsCli.describe(vote).forEach(line -> terminal.say("  " + line)));
            if (!args.flag("yes")) {
                if (!terminal.interactive()) {
                    throw CliException.usage("Check the change above, then pass --yes to vote as " + self);
                }
                if (!terminal.confirm("Vote yes as " + self + "?", false)) {
                    terminal.say("No vote cast.");
                    return CliException.FAILURE;
                }
            }
        }
        List<TopicsCli.Vote> pending = ballot.cast(votes, args.flag("again"));
        if (pending.isEmpty()) {
            terminal.next("yano.sh appchain topics resolve "
                    + example(TopicsCli.safeTarget(votes.getFirst().target())));
            return 0;
        }
        if (args.flag("no-wait")) {
            terminal.next("when enough operators have voted, the change activates; check with",
                    "  yano.sh appchain topics approve " + shown + "   (it never votes twice)");
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
                    vote.operation() + " " + TopicsCli.safeTarget(vote.target()), deadline, heartbeat, offer,
                    "it activates once enough operators vote and the chain makes its activation-lag blocks",
                    () -> waitingReason(client, path, node.publicKey(), vote));
            terminal.ok(vote.operation() + " " + TopicsCli.safeTarget(vote.target()) + " is in effect");
        }
        terminal.next(next(votes.getFirst()));
        return 0;
    }

    /** Where this member's vote stands, combining the chain's state with the votes this node has cast. */
    enum Standing {
        IN_EFFECT, DECIDED, VOTED, IN_FLIGHT, OPEN, LAPSED, REFUSED, SUPERSEDED
    }

    /**
     * This member's votes through this node. Every vote cast is recorded under {@code topic-votes/} by its command
     * hash, so it is never cast twice: a vote not yet included, a vote whose tally a decision consumed, and a vote
     * the chain refused all stay recorded. Only {@code --again} votes again, after an earlier vote lapsed.
     */
    final class Ballot {
        /** Allowance for the node stamping a message slightly after the record, and for clock steps. */
        private static final long MARGIN_MS = 2 * 60_000;
        private final NodeClient client;
        private final String path;
        private final String memberKey;
        private final Path records;
        private final long lifetimeMs;
        private final Map<TopicsCli.Vote, String> refusals = new LinkedHashMap<>();

        /** {@code lifetimeMs} is how long this node keeps a vote message valid before it expires unincluded. */
        Ballot(NodeClient client, String path, String memberKey, Path records, long lifetimeMs) {
            this.client = client;
            this.path = path;
            this.memberKey = memberKey;
            this.records = records;
            this.lifetimeMs = lifetimeMs;
        }

        Standing standing(TopicsCli.Vote vote) {
            TopicsCli.Progress progress = progress(client, path, memberKey, vote);
            if (progress != TopicsCli.Progress.OPEN) return Standing.valueOf(progress.name());
            JsonNode record = record(vote);
            if (record == null) return Standing.OPEN;
            // A reservation without a message id is a vote whose sending was interrupted: it may still be in
            // the pool, so it counts as cast until it would have expired.
            String refusal = refusal(record.path("messageId").asText(""));
            if (refusal != null) {
                refusals.put(vote, refusal);
                return Standing.REFUSED;
            }
            return System.currentTimeMillis() - record.path("at").asLong() < lifetimeMs + MARGIN_MS
                    ? Standing.IN_FLIGHT : Standing.LAPSED;
        }

        /**
         * Casts the open votes (and lapsed ones with {@code again}); returns the votes still to take effect. The
         * standings shown before a confirmation are read again here, and each vote once more just before it is
         * sent, so a quorum or a change that landed meanwhile is never voted over.
         */
        List<TopicsCli.Vote> cast(Collection<TopicsCli.Vote> votes, boolean again) {
            Map<TopicsCli.Vote, Standing> standings = new LinkedHashMap<>();
            for (TopicsCli.Vote vote : votes) standings.put(vote, standing(vote));
            // Every vote is checked before any is cast, so an out-of-date plan casts nothing.
            standings.forEach((vote, standing) -> {
                if (standing == Standing.SUPERSEDED) throw superseded(vote);
            });
            List<TopicsCli.Vote> pending = new ArrayList<>();
            for (Map.Entry<TopicsCli.Vote, Standing> entry : standings.entrySet()) {
                TopicsCli.Vote vote = entry.getKey();
                String what = vote.operation() + " " + TopicsCli.safeTarget(vote.target());
                Standing standing = entry.getValue();
                if (standing == Standing.OPEN || standing == Standing.LAPSED && again) {
                    standing = standing(vote);
                    if (standing == Standing.SUPERSEDED) throw superseded(vote);
                }
                switch (standing) {
                    case IN_EFFECT -> terminal.ok(what + ": already in effect");
                    case DECIDED -> {
                        terminal.ok(what + ": enough operators voted; it activates after the activation lag");
                        pending.add(vote);
                    }
                    case VOTED -> {
                        terminal.ok(what + ": this node already voted; waiting for the other operators");
                        pending.add(vote);
                    }
                    case IN_FLIGHT -> {
                        terminal.ok(what + ": this node's vote is waiting to be included in a block");
                        pending.add(vote);
                    }
                    case REFUSED -> terminal.warn(what + ": this node's vote was refused: "
                            + TopicsCli.explain(refusals.get(vote)) + ". Plan the change again if it is still needed");
                    case LAPSED -> {
                        if (!again) {
                            terminal.warn(what + ": this node voted before, but that vote no longer counts (it "
                                    + "expired, or the decision it joined was refused). Run with --again to vote "
                                    + "again");
                        } else {
                            vote(vote, what);
                            pending.add(vote);
                        }
                    }
                    case OPEN -> {
                        vote(vote, what);
                        pending.add(vote);
                    }
                    case SUPERSEDED -> throw superseded(vote);
                }
            }
            return pending;
        }

        private void vote(TopicsCli.Vote vote, String what) {
            // The record is reserved before the vote is sent, exclusively: a crash or a lost reply leaves it in
            // place, and a second approve running at the same time finds it and sends nothing.
            if (!reserve(vote)) {
                terminal.ok(what + ": another approve on this node is casting this vote");
                return;
            }
            JsonNode submitted;
            try {
                submitted = client.post(path + "/admin/topics/governance", Map.of("commandHex", vote.commandHex()));
            } catch (CliException failed) {
                if (failed.getMessage().startsWith("The node refused")) release(vote); // answered: nothing queued
                throw failed;
            }
            String messageId = submitted.path("messageId").asText();
            remember(vote, messageId);
            terminal.ok(what + ": voted (message " + messageId + ")");
            // A vote counts once a block includes it; until then the chain's state cannot show it.
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                Standing standing;
                try {
                    standing = standing(vote);
                } catch (CliException unreadable) {
                    standing = Standing.IN_FLIGHT;
                }
                if (standing == Standing.REFUSED) {
                    throw CliException.failure(what + ": the chain refused this vote: "
                            + TopicsCli.explain(refusals.get(vote)));
                }
                if (standing != Standing.IN_FLIGHT && standing != Standing.OPEN) return;
                Devnet.sleep(1000);
            }
            terminal.warn(what + ": the vote is not in a block yet; it counts once the chain makes one");
        }

        private JsonNode record(TopicsCli.Vote vote) {
            Path file = records.resolve(TopicsCli.commandHash(vote) + ".json");
            if (!Files.isRegularFile(file)) return null;
            try {
                return JSON.readTree(file.toFile());
            } catch (IOException unreadable) {
                throw CliException.failure("Cannot read the vote record " + file + ": " + unreadable.getMessage());
            }
        }

        /** Creates the vote's record before it is sent; false when another process already holds it. */
        private boolean reserve(TopicsCli.Vote vote) {
            Path file = records.resolve(TopicsCli.commandHash(vote) + ".json");
            try {
                Files.createDirectories(records);
                // A lapsed vote is cast again under a fresh record; renaming the old one is atomic, so of two
                // runs voting again at once only one goes on.
                if (record(vote) != null && standing(vote) == Standing.LAPSED) {
                    Files.move(file, records.resolve(TopicsCli.commandHash(vote) + ".lapsed-"
                            + System.currentTimeMillis() + ".json"), StandardCopyOption.ATOMIC_MOVE);
                }
                Files.writeString(file, JSON.writeValueAsString(Map.of("chain", vote.chainId(), "target",
                        vote.target(), "operation", vote.operation(), "at", System.currentTimeMillis())) + "\n",
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return true;
            } catch (FileAlreadyExistsException | NoSuchFileException held) {
                return false;
            } catch (IOException unwritable) {
                throw CliException.failure("Cannot record the vote in " + records + " (" + unwritable.getMessage()
                        + "); no vote was cast");
            }
        }

        private void release(TopicsCli.Vote vote) {
            try {
                Files.deleteIfExists(records.resolve(TopicsCli.commandHash(vote) + ".json"));
            } catch (IOException ignored) {
                // a leftover reservation only delays a later vote until it would have expired
            }
        }

        private void remember(TopicsCli.Vote vote, String messageId) {
            try {
                NodeDir.write(records.resolve(TopicsCli.commandHash(vote) + ".json"), JSON.writeValueAsString(
                        Map.of("chain", vote.chainId(), "target", vote.target(), "operation", vote.operation(),
                                "messageId", messageId, "at", System.currentTimeMillis())) + "\n");
            } catch (IOException unwritable) {
                throw CliException.failure("The vote was cast (message " + messageId + ") but could not be recorded "
                        + "in " + records + ": " + unwritable.getMessage() + ". Do not vote again by hand");
            }
        }

        /** The refusal code of this node's vote message, if the chain refused it. */
        private String refusal(String messageId) {
            if (!messageId.matches("[0-9a-f]{64}")) return null;
            try {
                long height = client.require(path + "/status").path("stateCommitment").path("version").asLong();
                TopicsCli.Submission receipt = TopicsCli.submitted(pinned(client, path, height), messageId,
                        null, null);
                return receipt.refusal();
            } catch (IOException | CliException unreadable) {
                return null;
            }
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * How long this node keeps a submitted message valid: the host's 600-second default, or a longer
     * {@code default-ttl-seconds} set in the node's configuration, so a vote is never taken for expired early.
     */
    static long messageLifetimeMs(NodeDir node) {
        long seconds = 600;
        Pattern setting = Pattern.compile("default-ttl-seconds\\s*[:=]\\s*['\"]?(\\d{1,9})");
        try (var files = Files.list(node.path("config"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Matcher found = setting.matcher(Files.readString(file));
                while (found.find()) seconds = Math.max(seconds, Long.parseLong(found.group(1)));
            }
        } catch (IOException unreadable) {
            // no configuration yet: the host default applies
        }
        return seconds * 1000;
    }

    private boolean inEffect(NodeClient client, String path, TopicsCli.Vote vote) {
        TopicsCli.Progress progress;
        try {
            progress = progress(client, path, null, vote);
        } catch (CliException unreadable) {
            return false; // a read the node could not answer is tried again on the next round
        }
        if (progress == TopicsCli.Progress.SUPERSEDED) throw superseded(vote);
        return progress == TopicsCli.Progress.IN_EFFECT;
    }

    /** Where a vote stands on this node: its target's entry and, for this member, the governance tally. */
    static TopicsCli.Progress progress(NodeClient client, String path, String memberKey, TopicsCli.Vote vote) {
        // Every read is pinned to one committed height, so a quorum landing between reads cannot look like an
        // open vote; a read the node cannot answer stops the command instead of counting as absence.
        try {
            long height = client.require(path + "/status").path("stateCommitment").path("version").asLong();
            long window = client.require(path + "/topics/config").path("config").path("settings")
                    .path("governanceWindowBlocks").asLong(0);
            return TopicsCli.progress(vote, memberKey == null ? "" : memberKey, pinned(client, path, height), height,
                    window);
        } catch (IOException | CliException unreadable) {
            throw CliException.failure("Cannot read where the vote stands from this node (" + unreadable.getMessage()
                    + "); no vote was cast. Try again");
        }
    }

    /** State values at one committed height; a read the node cannot answer fails rather than reading as absent. */
    private static TopicsCli.StateReader pinned(NodeClient client, String path, long height) {
        return key -> {
            JsonNode value = client.require(path + "/state/entry/" + HexFormat.of().formatHex(key) + "?height="
                    + height);
            return switch (value.path("presence").asText()) {
                case "PRESENT" -> Optional.of(HexFormat.of().parseHex(value.path("valueHex").asText()));
                case "ABSENT", "TOMBSTONED" -> Optional.empty();
                default -> throw new IOException("the node returned no state presence");
            };
        };
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
        return CliException.failure(TopicsCli.safeTarget(vote.target()) + " changed since this plan was made, so "
                + "this vote would act on a different state; check it with  yano.sh appchain topics resolve "
                + example(TopicsCli.safeTarget(vote.target())) + "  and plan again if the change is still needed");
    }

    private static String next(TopicsCli.Vote vote) {
        return switch (vote.operation()) {
            case "create", "recover" -> "the owners of " + TopicsCli.safeTarget(vote.target()) + " change it with  "
                    + "yano.sh appchain topics init " + TopicsCli.safeTarget(vote.target()) + "  (an update by them), "
                    + "then plan, sign and submit";
            default -> "yano.sh appchain topics resolve " + example(TopicsCli.safeTarget(vote.target()));
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
