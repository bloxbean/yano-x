package org.yanoproject.x.appchain.cli;

import org.yanoproject.x.appchain.formats.Draft;
import org.yanoproject.x.appchain.formats.MemberCard;
import org.yanoproject.x.appchain.formats.Names;
import org.yanoproject.x.appchain.formats.SealedApp;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * {@code request}: an operator's step. Creates the member key in the node directory if needed, fixes the
 * addresses other members reach this node at, and writes {@code <name>.member} for the coordinator.
 */
final class RequestCommand {
    static final String USAGE = "yano.sh appchain request <app>.draft.yaml|<app>.chain [--name <operator>]"
            + " [--host <public-host>] [--node-port 13337] [--http-port 8080] [--node-dir yano-node]"
            + " [--key <seed-file>] [--output <dir>] [--yes]";

    private final Terminal terminal;

    RequestCommand(Terminal terminal) {
        this.terminal = terminal;
    }

    int run(String[] arguments) {
        Args args = new Args(arguments, Set.of("name", "host", "node-port", "http-port", "node-dir", "key", "output"),
                Set.of("yes"));
        args.maxPositionals(1, USAGE);
        if (args.positional(0) == null) throw CliException.usage("Name the draft or chain file. Usage: " + USAGE);
        Path source = Path.of(args.positional(0));
        String text = NodeDir.read(source);
        String invite;
        String network;
        List<String> expected;
        boolean late = source.toString().endsWith(".chain");
        if (late) {
            SealedApp app = SealedApp.parse(text);
            invite = app.genesis().invite();
            network = app.genesis().network();
            expected = List.of();
        } else {
            Draft draft = Draft.parse(text);
            invite = draft.invite();
            network = draft.network();
            expected = draft.operators();
        }

        NodeDir node = new NodeDir(args.path("node-dir", Path.of(NodeDir.DEFAULT)));
        NodeDir.Identity identity = node.identity();
        if (identity == null) {
            String name = args.value("name") != null ? args.value("name")
                    : expected.isEmpty() ? terminal.ask("Your operator name", null, "--name")
                    : terminal.choose("Which operator are you?", expected, null, null, "--name");
            Names.name(name, "Operator name");
            String host = args.value("host") != null ? args.value("host")
                    : terminal.ask("Address other members reach this node at (host name or IP)",
                    "devnet".equals(network) ? "127.0.0.1" : null, "--host");
            int nodePort = port(args, "node-port", "Node-to-node port", 13337);
            int httpPort = port(args, "http-port", "HTTP port", 8080);
            for (int port : new int[]{nodePort, httpPort}) {
                if (!Ports.free(port)) {
                    terminal.warn("Port " + port + " is in use on this machine right now; the node needs it free "
                            + "when it starts");
                }
            }
            String seed = node.ensureKey(args.value("key") == null ? null : Path.of(args.value("key")));
            identity = new NodeDir.Identity(name, org.yanoproject.x.appchain.formats.Ed25519.publicKey(seed),
                    Names.nodeAddress(host + ":" + nodePort), Names.httpAddress("http://" + host + ":" + httpPort),
                    nodePort, httpPort);
            node.saveIdentity(identity);
            node.apiKey();
            terminal.ok("Created node directory " + node.root() + " with a new member key (keep it safe; "
                    + "it never leaves this directory)");
        } else {
            requireSame(args.value("name"), identity.name(), "operator name");
            if (args.value("host") != null) requireSame(args.value("host"), Names.host(identity.node()), "host");
            if (args.integer("node-port") != null) {
                requireSame(args.value("node-port"), Integer.toString(Names.port(identity.node())), "node port");
            }
            if (args.integer("http-port") != null) {
                requireSame(args.value("http-port"), Integer.toString(Names.port(identity.http())), "HTTP port");
            }
            if (!identity.publicKey().equals(node.publicKey())) {
                throw CliException.failure("node.json does not match the member key in " + node.root());
            }
        }
        if (!expected.isEmpty() && !expected.contains(identity.name())) {
            terminal.warn("The draft does not list operator '" + identity.name() + "'; the coordinator expects "
                    + String.join(", ", expected));
        }

        MemberCard card = MemberCard.create(node.seed(), invite, identity.name(), identity.node(), identity.http());
        if (late) {
            NodeDir.write(node.path("apps", SealedApp.parse(text).genesis().name() + ".request"), card.toJson());
        }
        Path output = args.path("output", Path.of(".")).resolve(identity.name() + ".member");
        NodeDir.write(output, card.toJson());
        terminal.ok("Wrote " + output + "  (key id " + Show.keyId(card.publicKey()) + ", reachable at "
                + card.node() + ")");
        if (late) {
            terminal.next("send " + output.getFileName() + " to the existing operators; each runs",
                    "  yano.sh appchain approve " + output.getFileName(),
                    "then run  yano.sh appchain join " + source.getFileName() + "  and  yano.sh appchain start");
        } else {
            terminal.next("send " + output.getFileName() + " to the coordinator, and tell them your key id "
                    + Show.keyId(card.publicKey()) + " on the call where you compare fingerprints.");
        }
        return 0;
    }

    private int port(Args args, String flag, String question, int fallback) {
        Integer value = args.integer(flag);
        if (value == null) value = Integer.parseInt(terminal.ask(question, Integer.toString(fallback), "--" + flag));
        if (value < 1 || value > 65535) throw CliException.usage("--" + flag + " must be a port number");
        return value;
    }

    private static void requireSame(String requested, String existing, String what) {
        if (requested != null && !requested.equals(existing)) {
            throw CliException.failure("This node directory already uses " + what + " '" + existing
                    + "'; use another --node-dir for a different node");
        }
    }
}
