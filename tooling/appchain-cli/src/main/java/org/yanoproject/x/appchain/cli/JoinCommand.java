package org.yanoproject.x.appchain.cli;

import org.yanoproject.x.appchain.formats.SealedApp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code join}: checks a sealed file and this node's place in it, records the operator's consent to its
 * fingerprint, and writes the node configuration. Safe to repeat.
 */
final class JoinCommand {
    static final String USAGE = "yano.sh appchain join <app>.chain [--expect-fingerprint <fp>] [--yes]"
            + " [--node-dir yano-node] [--listen <port>] [--peer <name>=<host:port>]... [--replace]"
            + " [--accept-bundles]";

    private final Terminal terminal;
    private final Distribution distribution;

    JoinCommand(Terminal terminal, Distribution distribution) {
        this.terminal = terminal;
        this.distribution = distribution;
    }

    int run(String[] arguments) {
        Args args = new Args(arguments, Set.of("expect-fingerprint", "node-dir", "listen", "peer"),
                Set.of("yes", "replace", "accept-bundles"));
        args.maxPositionals(1, USAGE);
        if (args.positional(0) == null) throw CliException.usage("Name the sealed file. Usage: " + USAGE);
        Path file = Path.of(args.positional(0));
        SealedApp app = SealedApp.parse(NodeDir.read(file));
        SealedApp.Genesis genesis = app.genesis();
        NodeDir node = new NodeDir(args.path("node-dir", Path.of(NodeDir.DEFAULT)));
        NodeDir.Identity identity = node.requireIdentity();
        String key = node.publicKey();

        SealedApp.Member founding = genesis.memberByKey(key);
        boolean later = founding == null;
        if (later) {
            if (genesis.member(identity.name()) != null) {
                throw CliException.failure("The sealed roster lists " + identity.name() + " with key "
                        + Show.keyId(genesis.member(identity.name()).publicKey()) + ", but this node's key is "
                        + Show.keyId(key) + ". Someone else's card was sealed under your name: stop and tell the "
                        + "coordinator.");
            }
            Path record = requestRecord(node, genesis);
            org.yanoproject.x.appchain.formats.MemberCard requested = Files.isRegularFile(record)
                    ? org.yanoproject.x.appchain.formats.MemberCard.parse(NodeDir.read(record)) : null;
            if (requested != null && (!requested.invite().equals(genesis.invite())
                    || !requested.publicKey().equals(key))) {
                throw CliException.failure("This node's request was for another application or key; run "
                        + "`yano.sh appchain request " + file.getFileName() + "` again");
            }
            if (requested == null) {
                throw CliException.failure("This node's key (" + Show.keyId(key) + ") is not a member of "
                        + genesis.name() + ". If you are a founding operator, the coordinator sealed someone "
                        + "else's card under your name: stop and tell them. To join later, run "
                        + "`yano.sh appchain request " + file.getFileName() + "` first.");
            }
            if (!"governed".equals(genesis.membership())) {
                throw CliException.failure(genesis.name() + " uses static membership; a later member is added "
                        + "by the operators' static runbook, not by join");
            }
        } else if (!founding.name().equals(identity.name())) {
            throw CliException.failure("This node's key is sealed under the name '" + founding.name()
                    + "', but this node is '" + identity.name() + "'; tell the coordinator");
        }

        List<SealedApp> others = node.apps().stream()
                .filter(other -> !other.genesis().name().equals(genesis.name())).toList();
        checkCompatible(genesis, others);

        SealedApp existing = Files.isRegularFile(node.appFile(genesis.name())) ? node.app(genesis.name()) : null;
        boolean same = existing != null && existing.fingerprint().equals(app.fingerprint());
        if (existing != null && !same) {
            if (node.hasData()) {
                throw CliException.failure("This node already runs " + genesis.name() + " ("
                        + existing.displayFingerprint() + ") and has its data; a different sealed file means a "
                        + "new application: ask the coordinator to seal it under a new name");
            }
            terminal.warn("This replaces the earlier " + genesis.name() + " (" + existing.displayFingerprint()
                    + "), which this node never started.");
            if (!args.flag("replace") && !terminal.confirm("Replace it?", false)) {
                throw CliException.failure("Not replaced; pass --replace to replace it");
            }
        }

        Show.sealed(terminal, app);
        if (later) terminal.say("  you join later as " + identity.name() + " (key " + Show.keyId(key) + ")");
        String expected = args.value("expect-fingerprint");
        if (expected != null) {
            if (!app.matches(expected)) {
                throw CliException.failure("The fingerprint is " + app.displayFingerprint() + ", not " + expected
                        + ". Do not start: the file differs from the one the coordinator sealed.");
            }
        } else if (!same) {
            if (!terminal.interactive()) {
                throw CliException.usage("Without a terminal, pass --expect-fingerprint " + app.displayFingerprint()
                        + " after comparing it with the coordinator");
            }
            if (!terminal.confirm("Does the chain fingerprint and every key id match what you compared with "
                    + "the others?", false)) {
                throw CliException.failure("Not joined. Compare the fingerprint again with the coordinator.");
            }
        }

        Map<String, String> installed = distribution.bundleDigests();
        NodeDir.Local local = node.local(genesis.name());
        Map<String, String> accepted = local != null && same ? local.bundles() : genesis.bundles();
        List<String> changed = Distribution.bundleChanges(accepted, installed);
        if (!changed.isEmpty()) {
            if (!args.flag("accept-bundles")) {
                throw CliException.failure("This distribution's plugin bundles differ from the sealed ones: "
                        + String.join(", ", changed) + ". Install Yano X " + genesis.yanoXVersion()
                        + ", or, if the operators agreed to the upgrade, re-run with --accept-bundles");
            }
            terminal.warn("Accepting changed bundles " + String.join(", ", changed)
                    + " for " + genesis.name());
            accepted = new TreeMap<>(installed);
        }
        if (!genesis.yanoXVersion().equals(distribution.yanoXVersion())) {
            terminal.warn("Sealed with Yano X " + genesis.yanoXVersion() + "; this is " + distribution.yanoXVersion());
        }

        Map<String, String> overrides = new TreeMap<>(local == null ? Map.of() : local.peerOverrides());
        for (String peer : args.values("peer")) {
            int equals = peer.indexOf('=');
            if (equals <= 0) throw CliException.usage("--peer must look like bob=node-b.example:13337");
            overrides.put(peer.substring(0, equals),
                    org.yanoproject.x.appchain.formats.Names.nodeAddress(peer.substring(equals + 1)));
        }
        Integer listen = args.integer("listen");
        if (listen != null && listen != identity.listenPort()) {
            terminal.warn("Listening on " + listen + " while other members reach this node at " + identity.node());
            identity = new NodeDir.Identity(identity.name(), identity.publicKey(), identity.node(), identity.http(),
                    listen, identity.httpPort());
            node.saveIdentity(identity);
        }

        NodeDir.write(node.path("run", "join.pending"), app.fingerprint() + "\n");
        NodeDir.write(node.appFile(genesis.name()), app.toJson());
        node.saveLocal(genesis.name(), new NodeDir.Local(app.fingerprint(), accepted, overrides,
                local == null ? List.of() : local.extraMembers(),
                local == null ? List.of() : local.removedMembers()));
        node.apiKey();
        NodeConfig.write(node, node.joined());
        NodeConfig.ensureLocalFile(node);
        Service.writeUnit(node, distribution);
        try {
            Files.deleteIfExists(node.path("run", "join.pending"));
        } catch (java.io.IOException error) {
            throw CliException.failure("Cannot finish join: " + error.getMessage());
        }

        terminal.ok("Joined " + genesis.name() + " (" + app.displayFingerprint() + ") in " + node.root());
        if (Service.unitWritten(node)) {
            terminal.say("  To run it as a service: " + Service.installHint(node));
        }
        boolean running = Processes.running(node);
        terminal.next(running ? "restart this node to load the new configuration:  yano.sh appchain stop && "
                        + "yano.sh appchain start" : "yano.sh appchain start",
                later ? "then the existing operators run  yano.sh appchain approve " + identity.name() + ".member"
                        : "then  yano.sh appchain status " + genesis.name());
        return 0;
    }

    static Path requestRecord(NodeDir node, SealedApp.Genesis genesis) {
        return node.path("apps", genesis.name() + ".request");
    }

    private static void checkCompatible(SealedApp.Genesis genesis, List<SealedApp> others) {
        Set<String> chainIds = new HashSet<>();
        for (SealedApp other : others) {
            if (!other.genesis().network().equals(genesis.network())) {
                throw CliException.failure("This node runs " + other.genesis().name() + " on "
                        + other.genesis().network() + "; one node runs applications of one Cardano network only");
            }
            if ("devnet".equals(genesis.network()) && !other.genesis().lead().equals(genesis.lead())) {
                throw CliException.failure("Devnet applications on one node must share the lead that produces "
                        + "the devnet (" + other.genesis().lead() + ")");
            }
            other.genesis().settings().forEach((key, value) -> {
                String mine = genesis.settings().get(key);
                if (mine != null && !mine.equals(value)) {
                    throw CliException.failure("Node-wide setting " + key + " differs from "
                            + other.genesis().name() + "; run these applications on separate nodes");
                }
            });
            other.genesis().chains().forEach(chain -> chainIds.add(chain.id()));
        }
        for (SealedApp.ChainGenesis chain : genesis.chains()) {
            if (chainIds.contains(chain.id())) {
                throw CliException.failure("This node already runs a chain named '" + chain.id() + "'");
            }
        }
    }

}
