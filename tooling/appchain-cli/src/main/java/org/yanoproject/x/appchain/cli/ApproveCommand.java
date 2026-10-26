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
 * raise or lower the threshold first when the change needs it, approve the change, drive activation, then
 * add or drop the peer and restart this node once. Safe to run again.
 */
final class ApproveCommand {
    static final String USAGE = "yano.sh appchain approve <name>.member | --remove <name> [<app>]"
            + " [--node-dir yano-node] [--heartbeat] [--timeout <seconds>] [--yes]";

    private final Terminal terminal;
    private final Distribution distribution;

    ApproveCommand(Terminal terminal, Distribution distribution) {
        this.terminal = terminal;
        this.distribution = distribution;
    }

    int run(String[] arguments) throws IOException {
        Args args = new Args(arguments, Set.of("remove", "node-dir", "timeout", "app"), Set.of("heartbeat", "yes"));
        NodeDir node = new NodeDir(args.path("node-dir", Path.of(NodeDir.DEFAULT)));
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
            terminal.say("Adding " + name + " (key " + Show.keyId(key) + ", " + card.node() + ") to " + genesis.name());
        } else {
            name = remove;
            key = keyOf(app, local, remove);
            terminal.say("Removing " + name + " (key " + Show.keyId(key) + ") from " + genesis.name());
        }

        NodeClient client = NodeClient.local(node);
        for (SealedApp.ChainGenesis chain : genesis.chains()) {
            if (key.equals(chain.settings().get("sequencer.proposer")) && remove != null) {
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
            if (!scheduled) {
                int threshold = threshold(client, path);
                int wanted = remove == null ? Math.max(threshold, count / 2 + 1) : Math.min(threshold, count);
                if (wanted != threshold) {
                    if (wanted > threshold) requireOnline(client, path, wanted, chain.id());
                    terminal.say(chain.id() + ": first " + (wanted > threshold ? "raising" : "lowering")
                            + " the threshold from " + threshold + " to " + wanted + " (" + count + " members)");
                    client.post(path + "/admin/threshold", Map.of("threshold", wanted));
                    heartbeat = await(client, path, chain.id(), status -> status.path("membershipEpochActive")
                                    .asBoolean(false) && status.path("membershipActiveThreshold").asInt() == wanted,
                            "threshold " + wanted, deadline, heartbeat);
                }
                client.post(path + "/admin/members/" + (remove == null ? "add" : "remove"), Map.of("publicKey", key));
            }
            heartbeat = await(client, path, chain.id(), active, (remove == null ? "adding " : "removing ") + name,
                    deadline, heartbeat);
            terminal.ok(chain.id() + ": " + name + (remove == null ? " is a member" : " is removed"));
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
        node.saveLocal(genesis.name(), new NodeDir.Local(local.fingerprint(), local.bundles(), local.peerOverrides(),
                extra, removed));
        NodeConfig.write(node, node.joined());
        terminal.say("Restarting this node to " + (card != null ? "connect to " : "drop ") + name + " …");
        new StopCommand(terminal).stop(node);
        StartCommand start = new StartCommand(terminal, distribution);
        if (!Service.installed(node)) start.launch(node, 300, false);
        else Service.systemctl("start", Service.unitName(node));
        start.waitReady(node, 300, false);
        terminal.next(card != null ? "when every operator has approved, " + name + " runs  yano.sh appchain start"
                        + "  and catches up; check with  yano.sh appchain status"
                : "yano.sh appchain status");
        return 0;
    }

    private boolean await(NodeClient client, String path, String chain, Predicate<JsonNode> done, String what,
                          long deadline, boolean heartbeat) {
        boolean told = false;
        boolean asked = heartbeat;
        while (true) {
            JsonNode status = client.get(path + "/status").orElse(null);
            if (status != null && done.test(status)) return heartbeat;
            if (System.currentTimeMillis() > deadline) {
                throw CliException.notReady(chain + ": still waiting for " + what + ". The other operators may not "
                        + "have run approve yet; run this command again later (it continues where it stopped)");
            }
            if (!told) {
                terminal.say(chain + ": waiting for " + what + " (it activates once enough operators approve and "
                        + "the chain makes 10 more blocks)");
                told = true;
            }
            String machine = status == null ? "" : status.path("stateMachine").asText();
            String effect = Heartbeat.effect(machine);
            if (effect != null && !asked) {
                heartbeat = terminal.confirm(chain + " may be idle. Send heartbeat messages to make blocks? Each one "
                        + effect, false);
                asked = true;
                if (!heartbeat) terminal.say("Then send ordinary traffic to " + chain + " so it makes blocks.");
            }
            if (heartbeat && effect != null) {
                try {
                    client.post(path + "/messages", Heartbeat.message(machine));
                } catch (CliException busy) {
                    // A refused heartbeat is retried on the next round.
                }
            }
            Devnet.sleep(2000);
        }
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

    /** Refuses a threshold no online quorum could reach. */
    private static void requireOnline(NodeClient client, String path, int wanted, String chain) {
        JsonNode status = client.get(path + "/status").orElseThrow();
        int online = 1;
        var peers = status.path("peers");
        for (var entry : (Iterable<Map.Entry<String, JsonNode>>) peers::fields) {
            if (entry.getValue().asBoolean(false)) online++;
        }
        if (online < wanted) {
            throw CliException.failure(chain + ": only " + online + " members are connected, but the new threshold "
                    + "needs " + wanted + "; bring the others online first so the chain cannot stall");
        }
    }

    private static String keyOf(SealedApp app, NodeDir.Local local, String name) {
        SealedApp.Member member = app.genesis().member(name);
        if (member != null) return member.publicKey();
        return local.extraMembers().stream().map(MemberCard::parse).filter(card -> card.name().equals(name))
                .map(MemberCard::publicKey).findFirst()
                .orElseThrow(() -> CliException.failure("No member named " + name + " in " + app.genesis().name()));
    }
}
