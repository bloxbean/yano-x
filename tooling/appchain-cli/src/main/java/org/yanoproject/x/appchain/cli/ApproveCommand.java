package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.yanoproject.x.appchain.formats.MemberCard;
import org.yanoproject.x.appchain.formats.SealedApp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * {@code approve}: this operator's part of a governed membership change, on every chain of the application:
 * raise or lower the threshold first where the change needs it, approve the change on every chain, wait for all
 * of them to activate, then add or drop the peer and restart this node once. Safe to run again.
 */
final class ApproveCommand {
    static final String USAGE = "yano.sh appchain approve <name>.member | --remove <name> [<app>]"
            + " [--node-dir yano-node] [--heartbeat] [--timeout <seconds>] [--yes]";

    private final Terminal terminal;
    private final Distribution distribution;
    private NodeDir node;

    ApproveCommand(Terminal terminal, Distribution distribution) {
        this.terminal = terminal;
        this.distribution = distribution;
    }

    int run(String[] arguments) throws IOException {
        Args args = new Args(arguments, Set.of("remove", "node-dir", "timeout", "app"), Set.of("heartbeat", "yes"));
        node = new NodeDir(args.path("node-dir", NodeDir.defaultDir()));
        String remove = args.value("remove");
        String cardFile = remove == null ? args.positional(0) : null;
        String appName = args.value("app") != null ? args.value("app") : remove == null ? args.positional(1)
                : args.positional(0);
        if (remove == null && cardFile == null) throw CliException.usage("Name a member card. Usage: " + USAGE);
        SealedApp app = appName == null ? node.onlyApp() : node.app(appName);
        SealedApp.Genesis genesis = app.genesis();
        if (!"governed".equals(genesis.membership())) {
            terminal.say(genesis.name() + " uses static membership. Every operator adds or removes the key with "
                    + "POST /api/v1/app-chain/chains/<chain>/admin/members/add|remove on their node, in the same "
                    + "order (user guide §14.2).");
            return CliException.FAILURE;
        }
        if (!Processes.running(node) || !NodeClient.local(node).ready()) {
            throw CliException.failure("This node must be running; run  yano.sh appchain start  first");
        }
        long deadline = System.currentTimeMillis() + 1000L * (args.integer("timeout") == null ? 900
                : args.integer("timeout"));
        boolean heartbeat = args.flag("heartbeat");
        NodeDir.Local local = node.local(genesis.name());

        String name;
        String key;
        MemberCard card = null;
        if (remove == null) {
            card = MemberCard.parse(NodeDir.read(Path.of(cardFile)));
            if (!card.signatureValid()) throw CliException.failure(cardFile + " has a bad signature");
            if (!card.invite().equals(genesis.invite())) {
                throw CliException.failure(cardFile + " was made for another application (invite " + card.invite()
                        + "); ask " + card.name() + " to run  yano.sh appchain request " + genesis.name() + ".chain");
            }
            name = card.name();
            key = card.publicKey();
            String current = activeMembers(app, local).get(name);
            if (current != null && !current.equals(card.publicKey())) {
                throw CliException.failure(name + " is already a member with another key (" + Show.keyId(current)
                        + "). To replace a lost key, first run  yano.sh appchain approve --remove " + name
                        + "  on every node, then approve the new card");
            }
            terminal.say("Adding " + name + " (key " + Show.keyId(key) + ", " + card.node() + ") to " + genesis.name());
        } else {
            name = remove;
            key = activeMembers(app, local).get(remove);
            if (key == null) throw CliException.failure("No active member named " + remove + " in " + genesis.name());
            terminal.say("Removing " + name + " (key " + Show.keyId(key) + ") from " + genesis.name());
        }

        // Every chain's change is proposed first and then awaited together, so a large application activates in
        // one wait rather than one chain after another.
        NodeClient client = NodeClient.local(node);
        List<Change> changes = new ArrayList<>();
        for (SealedApp.ChainGenesis chain : genesis.chains()) {
            if (remove != null && "fixed".equals(chain.settings().get("sequencer.mode"))
                    && key.equals(chain.settings().get("sequencer.proposer"))) {
                throw CliException.failure(name + " is the fixed proposer of " + chain.id() + "; the runtime refuses "
                        + "to remove it. Losing the lead's key means sealing a new application.");
            }
            String path = "/api/v1/app-chain/chains/" + chain.id();
            List<String> members = members(client, path);
            boolean scheduled = members.contains(key) == (remove == null);
            int count = remove == null ? members.size() + (scheduled ? 0 : 1) : members.size() - (scheduled ? 0 : 1);
            Predicate<JsonNode> active = status -> status.path("membershipEpochActive").asBoolean(false)
                    && status.path("membershipActiveMembers").asInt() == count;
            if (scheduled && active.test(client.get(path + "/status").orElseThrow())) {
                terminal.ok(chain.id() + ": already " + (remove == null ? "a member" : "removed"));
                continue;
            }
            changes.add(new Change(chain, path, scheduled, members.size(), count, active));
        }

        List<Wait> thresholds = new ArrayList<>();
        for (Change change : changes) {
            if (change.scheduled()) continue;
            int threshold = threshold(client, change.path());
            int faults = Integer.parseInt(change.chain().settings().getOrDefault("consensus.max-byzantine-members",
                    "0"));
            int wanted = safeThreshold(threshold, change.before(), change.after(), faults);
            if (wanted == threshold) continue;
            if (wanted > threshold) requireOnline(app, local, change.chain().id(), wanted);
            terminal.say(change.chain().id() + ": first " + (wanted > threshold ? "raising" : "lowering")
                    + " the threshold from " + threshold + " to " + wanted + " (" + change.after() + " members)");
            client.post(change.path() + "/admin/threshold", Map.of("threshold", wanted));
            thresholds.add(new Wait(change.chain().id(), change.path(), status -> status.path("membershipEpochActive")
                    .asBoolean(false) && status.path("membershipActiveThreshold").asInt() == wanted,
                    "threshold " + wanted, Heartbeat.allowed(client, change.path())));
        }
        heartbeat = awaitAll(client, thresholds, deadline, heartbeat);

        List<Wait> activations = new ArrayList<>();
        for (Change change : changes) {
            if (!change.scheduled()) {
                client.post(change.path() + "/admin/members/" + (remove == null ? "add" : "remove"),
                        Map.of("publicKey", key));
            }
            activations.add(new Wait(change.chain().id(), change.path(), change.active(),
                    (remove == null ? "adding " : "removing ") + name, Heartbeat.allowed(client, change.path())));
        }
        awaitAll(client, activations, deadline, heartbeat);
        for (Change change : changes) {
            terminal.ok(change.chain().id() + ": " + name + (remove == null ? " is a member" : " is removed"));
        }

        List<String> extra = new ArrayList<>(local.extraMembers());
        List<String> removed = new ArrayList<>(local.removedMembers());
        if (card != null) {
            String json = card.toJson();
            extra.removeIf(item -> MemberCard.parse(item).name().equals(name));
            extra.add(json);
            removed.remove(name);
        } else {
            extra.removeIf(item -> MemberCard.parse(item).name().equals(name));
            if (genesis.member(name) != null && !removed.contains(name)) removed.add(name);
        }
        NodeDir.Local updated = new NodeDir.Local(local.fingerprint(), local.bundles(), local.peerOverrides(), extra,
                removed);
        if (updated.equals(local)) {
            terminal.ok("This node already knows " + name + "; nothing to restart");
            terminal.next("yano.sh appchain status");
            return 0;
        }
        node.saveLocal(genesis.name(), updated);
        NodeConfig.write(node, node.joined());
        terminal.say("Restarting this node to " + (card != null ? "connect to " : "drop ") + name + " …");
        new StopCommand(terminal).stop(node);
        StartCommand start = new StartCommand(terminal, distribution);
        if (!Service.installed(node)) start.launch(node, 300, false);
        else Service.control(node, "start");
        start.waitReady(node, 300, false);
        terminal.next(card != null ? "when every operator has approved, " + name + " runs  yano.sh appchain start"
                        + "  and catches up; check with  yano.sh appchain status"
                : "yano.sh appchain status");
        return 0;
    }

    private record Change(SealedApp.ChainGenesis chain, String path, boolean scheduled, int before, int after,
                          Predicate<JsonNode> active) {
    }

    private record Wait(String chain, String path, Predicate<JsonNode> done, String what, boolean heartbeats) {
    }

    /** Waits until every chain's change is active, sending heartbeats to the idle ones if the operator agrees. */
    private boolean awaitAll(NodeClient client, List<Wait> waits, long deadline, boolean heartbeat) {
        List<Wait> pending = new ArrayList<>(waits);
        boolean told = false;
        boolean asked = heartbeat;
        while (!pending.isEmpty()) {
            List<String> machines = new ArrayList<>();
            for (java.util.Iterator<Wait> iterator = pending.iterator(); iterator.hasNext(); ) {
                Wait wait = iterator.next();
                JsonNode status = client.get(wait.path() + "/status").orElse(null);
                if (status != null && wait.done().test(status)) {
                    iterator.remove();
                } else {
                    machines.add(status == null ? "" : status.path("stateMachine").asText());
                }
            }
            if (pending.isEmpty()) break;
            if (System.currentTimeMillis() > deadline) {
                throw CliException.notReady(String.join(", ", pending.stream().map(Wait::chain).toList())
                        + ": still waiting for " + pending.getFirst().what() + ". The other operators may not have "
                        + "run approve yet; run this command again later (it continues where it stopped)");
            }
            if (!told) {
                terminal.say(String.join(", ", pending.stream().map(Wait::chain).toList()) + ": waiting for "
                        + pending.getFirst().what() + " (it activates once enough operators approve and each chain "
                        + "makes 10 more blocks)");
                told = true;
            }
            for (int index = 0; index < pending.size(); index++) {
                Wait wait = pending.get(index);
                // Heartbeats use an unregistered topic, which a chain refusing unregistered topics never accepts.
                String effect = wait.heartbeats() ? Heartbeat.effect(machines.get(index)) : null;
                if (effect != null && !asked) {
                    asked = true;
                    if (terminal.interactive()) {
                        heartbeat = terminal.confirm(wait.chain() + " may be idle. Send heartbeat messages to make "
                                + "blocks? Each one " + effect, false);
                        if (!heartbeat) terminal.say("Then send ordinary traffic to idle chains so they make blocks.");
                    } else {
                        terminal.say("Chains make blocks only from messages. If one is idle, send traffic, or run "
                                + "again with --heartbeat.");
                    }
                }
                if (heartbeat && effect != null) {
                    try {
                        client.post(wait.path() + "/messages", Heartbeat.message(machines.get(index)));
                    } catch (CliException busy) {
                        // A refused heartbeat is retried on the next round.
                    }
                }
            }
            Devnet.sleep(2000);
        }
        return heartbeat;
    }

    private static List<String> members(NodeClient client, String path) {
        JsonNode body = client.get(path + "/admin/members")
                .orElseThrow(() -> CliException.failure("Cannot read members from this node"));
        List<String> keys = new ArrayList<>();
        body.path("members").forEach(item -> keys.add(item.asText()));
        return keys;
    }

    private static int threshold(NodeClient client, String path) {
        return client.get(path + "/status").map(status -> status.path("membershipActiveThreshold").asInt())
                .orElseThrow(() -> CliException.failure("Cannot read the chain status from this node"));
    }

    /** Valid for the host's quorum rule: {@code 2t - n > f} and {@code t <= n - f}. */
    static boolean valid(int threshold, int members, int faults) {
        return 2 * threshold - members > faults && threshold <= members - faults;
    }

    /**
     * The threshold to run while membership changes from {@code now} to {@code next} members: the current one when
     * it suits both, otherwise the nearest that does, so the chain never passes through an invalid quorum.
     */
    static int safeThreshold(int current, int now, int next, int faults) {
        if (valid(current, now, faults) && valid(current, next, faults)) return current;
        for (int distance = 1; distance <= Math.max(now, next); distance++) {
            int up = current + distance;
            if (next > now && valid(up, now, faults) && valid(up, next, faults)) return up;
            int down = current - distance;
            if (next < now && down > 0 && valid(down, now, faults) && valid(down, next, faults)) return down;
        }
        throw CliException.failure("No threshold suits both " + now + " and " + next + " members with " + faults
                + " tolerated faults; change membership in smaller steps");
    }

    /**
     * Refuses a threshold that the members whose own nodes confirm their active key could not reach; a transport
     * connection alone is not a member.
     */
    private void requireOnline(SealedApp app, NodeDir.Local local, String chain, int wanted) {
        Map<String, String> active = activeMembers(app, local);
        StatusCommand.AppView view = new StatusCommand(terminal).view(node, app);
        long confirmed = view.members().stream().filter(StatusCommand.MemberView::online)
                .filter(member -> {
                    JsonNode status = member.chains().get(chain);
                    return status != null && status.path("memberKey").asText("").equals(active.get(member.name()))
                            && status.path("memberActiveForNextBlock").asBoolean(false);
                }).count();
        if (confirmed < wanted) {
            throw CliException.failure(chain + ": only " + confirmed + " members are online with their active key, but "
                    + "the new threshold needs " + wanted + "; bring the others online first so the chain cannot stall");
        }
    }

    /** Name to key of the current members as this node knows them: the roster, then later changes in order. */
    private static Map<String, String> activeMembers(SealedApp app, NodeDir.Local local) {
        Map<String, String> members = new java.util.LinkedHashMap<>();
        app.genesis().members().forEach(member -> members.put(member.name(), member.publicKey()));
        local.removedMembers().forEach(members::remove);
        local.extraMembers().stream().map(MemberCard::parse).forEach(card -> members.put(card.name(), card.publicKey()));
        return members;
    }
}
