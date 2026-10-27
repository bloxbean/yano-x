package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.yanoproject.x.appchain.formats.Ed25519;
import org.yanoproject.x.appchain.formats.SealedApp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code anchor}: makes this node the anchor leader of one chain (ADR-056 D10) — wallet, funding, confirmation,
 * restart, and a one-time script bootstrap only when no member reports an anchor thread. {@code --stop} hands
 * the role back.
 */
final class AnchorCommand {
    static final String USAGE = "yano.sh appchain anchor [<app>] [--chain <id>] [--node-dir yano-node] [--stop]"
            + " [--take-over] [--key <wallet-seed-file>] [--ada <devnet-funding>] [--timeout <seconds>] [--yes]";

    private final Terminal terminal;
    private final Distribution distribution;

    AnchorCommand(Terminal terminal, Distribution distribution) {
        this.terminal = terminal;
        this.distribution = distribution;
    }

    int run(String[] arguments) throws IOException {
        Args args = new Args(arguments, Set.of("chain", "node-dir", "ada", "timeout", "key"),
                Set.of("stop", "take-over", "yes"));
        args.maxPositionals(1, USAGE);
        NodeDir node = new NodeDir(args.path("node-dir", Path.of(NodeDir.DEFAULT)));
        SealedApp app = args.positional(0) == null ? node.onlyApp() : node.app(args.positional(0));
        SealedApp.Genesis genesis = app.genesis();
        List<String> ids = genesis.chains().stream().map(SealedApp.ChainGenesis::id).toList();
        String chain = args.value("chain") != null ? genesis.chain(args.value("chain")).id()
                : ids.size() == 1 ? ids.getFirst()
                : terminal.choose("Which chain should this node anchor?", ids, null, null, "--chain");
        if (!Processes.running(node)) throw CliException.failure("Start this node first:  yano.sh appchain start");
        String path = "/api/v1/app-chain/chains/" + chain;
        NodeClient local = NodeClient.local(node);
        long deadline = System.currentTimeMillis() + 1000L * (args.integer("timeout") == null ? 600
                : args.integer("timeout"));

        if (args.flag("stop")) {
            if (Anchoring.lead(node, genesis.name(), chain) == null) {
                terminal.say("This node does not lead anchoring for " + chain);
                return 0;
            }
            Anchoring.remove(node, genesis.name(), chain);
            restart(node);
            terminal.ok("This node no longer anchors " + chain);
            terminal.next("another operator can take over with  yano.sh appchain anchor " + genesis.name()
                    + " --chain " + chain);
            return 0;
        }

        String self = node.requireIdentity().name();
        if ("devnet".equals(genesis.network()) && !self.equals(genesis.lead())) {
            throw CliException.failure("On a devnet only the lead's node keeps the Cardano wallet view anchoring "
                    + "needs; ask " + genesis.lead() + " to run  yano.sh appchain anchor " + genesis.name()
                    + " --chain " + chain);
        }
        StatusCommand.AppView view = new StatusCommand(terminal).view(node, app);
        String otherLeader = null;
        String thread = null;
        List<String> offline = new java.util.ArrayList<>();
        for (StatusCommand.MemberView member : view.members()) {
            if (!member.online()) {
                if (!member.name().equals(self)) offline.add(member.name());
                continue;
            }
            JsonNode anchor = member.chains().getOrDefault(chain, missing()).path("anchor");
            if (!anchor.path("threadPolicyId").asText("").isEmpty()) thread = anchor.path("threadPolicyId").asText();
            if (!member.name().equals(self) && StatusCommand.leads(anchor)) {
                otherLeader = member.name();
            }
        }
        if (otherLeader != null) {
            throw CliException.failure(otherLeader + " already anchors " + chain + ". To move the role, "
                    + otherLeader + " runs  yano.sh appchain anchor --stop --chain " + chain + "  first");
        }
        // An unreachable member may still be leading; only an explicit take-over proceeds.
        if (!offline.isEmpty() && !args.flag("take-over")) {
            throw CliException.failure(String.join(", ", offline) + " cannot be reached and may be leading anchoring "
                    + "for " + chain + ". Bring them online, or if they are gone for good, run again with --take-over");
        }
        String mine = local.get(path + "/status").map(status -> status.path("anchor").path("threadPolicyId")
                .asText("")).orElse("");
        if (thread != null && !thread.equals(mine)) {
            throw CliException.failure(chain + " already has an anchor thread on Cardano that this node has not "
                    + "confirmed yet (a node learns it by co-signing anchors). Lead anchoring from a member that "
                    + "has co-signed, or wait until this node has.");
        }

        String mode = genesis.chain(chain).settings().getOrDefault("anchor.mode", "metadata");
        String every = genesis.chain(chain).settings().getOrDefault("anchor.every-blocks", "10");
        terminal.say("Anchoring writes " + chain + "'s height and state root to Cardano (" + genesis.network()
                + ") every " + every + " blocks, in " + mode + " mode ("
                + ("script".equals(mode) ? "about 0.35 ADA per anchor; members co-sign" : "about 0.2 ADA per anchor")
                + ").");
        terminal.say("The anchor wallet lives only on this node. Fund it only with what you are willing to spend on "
                + "fees: its balance is the spending limit.");
        if (!args.flag("yes") && !terminal.confirm("Make this node the anchor leader of " + chain + "?", false)) {
            return CliException.FAILURE;
        }
        Path wallet = node.path("secrets", "anchor.key");
        if (args.value("key") != null) {
            // An existing funded wallet: its seed is copied into this node and never printed.
            String seed = NodeDir.readSeedFile(Path.of(args.value("key")));
            Ed25519.publicKey(seed);
            if (Anchoring.hasWallet(node) && !NodeDir.read(wallet).strip().equals(seed)) {
                throw CliException.failure("This node already has a different anchor wallet in " + wallet);
            }
            if (!Anchoring.hasWallet(node)) {
                NodeDir.writeSecret(wallet, seed + "\n");
                terminal.ok("Imported the anchor wallet key into " + wallet);
            }
        } else if (!Anchoring.hasWallet(node)) {
            NodeDir.writeSecret(wallet, Ed25519.newSeed() + "\n");
            terminal.ok("Created the anchor wallet key in " + wallet);
        }
        Anchoring.Lead previous = Anchoring.lead(node, genesis.name(), chain);
        boolean leadingNow = local.get(path + "/status").map(status -> StatusCommand.leads(status.path("anchor")))
                .orElse(false);
        if (previous != null && leadingNow) {
            // Already configured and running as leader: restarting would drop a pending bootstrap held in memory.
            terminal.say("This node already leads anchoring for " + chain + "; continuing where it stopped.");
        } else {
            Anchoring.put(node, new Anchoring.Lead(genesis.name(), chain,
                    previous == null ? null : previous.submittedBootstrap()));
            restart(node);
        }

        String address = null;
        while (address == null) {
            address = local.get(path + "/status").map(status -> status.path("anchor"))
                    .map(anchor -> anchor.path("walletAddress").asText(anchor.path("address").asText(null)))
                    .orElse(null);
            if (address == null) {
                if (System.currentTimeMillis() > deadline) {
                    throw CliException.notReady("The node did not report its anchor wallet; see its log");
                }
                Devnet.sleep(2000);
            }
        }
        terminal.say("Anchor wallet: " + address);
        if ("devnet".equals(genesis.network())) {
            int ada = args.integer("ada") == null ? 100 : args.integer("ada");
            String lead = genesis.lead();
            NodeClient faucet = new NodeClient(lead.equals(self) ? local.base() : app.peers().get(lead).http(), null);
            faucet.post("/api/v1/devnet/fund", Map.of("address", address, "ada", ada));
            terminal.ok("Funded it with " + ada + " test ADA from the devnet (" + lead + "'s node)");
        } else {
            terminal.say("Send ADA to that address from any wallet. This command continues once it arrives.");
        }

        if ("script".equals(mode) && thread == null && !offline.isEmpty()) {
            throw CliException.failure("No reachable member reports an anchor thread, but " + String.join(", ", offline)
                    + " cannot be reached and may hold one; a second bootstrap would fork the thread. Bring them "
                    + "online first");
        }
        if ("script".equals(mode) && thread == null) {
            Anchoring.Lead lead = Anchoring.lead(node, genesis.name(), chain);
            if (lead.submittedBootstrap() != null && !bootstrapped(local, path)) {
                terminal.say("A bootstrap was already submitted (" + lead.submittedBootstrap() + "); waiting for "
                        + "Cardano to confirm it instead of submitting another.");
            } else {
                String submitted = bootstrap(local, path, deadline);
                Anchoring.put(node, new Anchoring.Lead(genesis.name(), chain, submitted));
            }
        }
        while (!ready(local, path, mode)) {
            if (System.currentTimeMillis() > deadline) {
                throw CliException.notReady("Anchoring is configured but not confirmed yet. If this node restarted "
                        + "after submitting the bootstrap, check that transaction on Cardano before running anchor "
                        + "again; otherwise run  yano.sh appchain status  later.");
            }
            Devnet.sleep(3000);
        }
        terminal.ok("This node anchors " + chain + ("script".equals(mode) ? " (anchor thread confirmed)" : ""));
        terminal.next("the first anchor is written after " + every + " more blocks; see it with  yano.sh appchain "
                + "status " + genesis.name());
        return 0;
    }

    private void restart(NodeDir node) throws IOException {
        NodeConfig.write(node, node.joined());
        terminal.say("Restarting this node with the new anchor settings …");
        new StopCommand(terminal).stop(node);
        StartCommand start = new StartCommand(terminal, distribution);
        if (Service.installed(node)) {
            Service.control(node, "start");
        } else {
            start.launch(node, 300, false);
        }
        start.waitReady(node, 300, false);
    }

    private static String bootstrap(NodeClient local, String path, long deadline) {
        while (true) {
            try {
                JsonNode result = local.post(path + "/admin/anchor/bootstrap", Map.of());
                return result.path("txHash").asText(result.path("tx").asText("submitted"));
            } catch (CliException notYet) {
                if (System.currentTimeMillis() > deadline) throw notYet;
                Devnet.sleep(5000);
            }
        }
    }

    private static boolean bootstrapped(NodeClient local, String path) {
        return local.get(path + "/status").map(status -> status.path("anchor").path("bootstrapped").asBoolean(false))
                .orElse(false);
    }

    private static boolean ready(NodeClient local, String path, String mode) {
        JsonNode anchor = local.get(path + "/status").map(status -> status.path("anchor")).orElse(missing());
        if (!StatusCommand.leads(anchor)) return false;
        return !"script".equals(mode) || anchor.path("bootstrapped").asBoolean(false);
    }

    private static JsonNode missing() {
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
