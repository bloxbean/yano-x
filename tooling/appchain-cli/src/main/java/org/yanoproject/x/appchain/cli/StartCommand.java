package org.yanoproject.x.appchain.cli;

import org.yanoproject.x.appchain.formats.SealedApp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code start}: checks this node's setup, prepares the devnet if needed, starts the node and waits. */
final class StartCommand {
    static final String USAGE = "yano.sh appchain start [--node-dir yano-node] [--timeout <seconds>] [--foreground]";

    private final Terminal terminal;
    private final Distribution distribution;

    StartCommand(Terminal terminal, Distribution distribution) {
        this.terminal = terminal;
        this.distribution = distribution;
    }

    int run(String[] arguments) throws IOException {
        Args args = new Args(arguments, Set.of("node-dir", "timeout"), Set.of("foreground", "yes"));
        args.maxPositionals(0, USAGE);
        NodeDir node = new NodeDir(args.path("node-dir", Path.of(NodeDir.DEFAULT)));
        int timeout = args.integer("timeout") == null ? 300 : args.integer("timeout");
        boolean foreground = args.flag("foreground");
        if (!foreground && Service.installed(node)) {
            if (Service.systemctl("start", Service.unitName(node)) != 0) {
                throw CliException.failure("systemctl start " + Service.unitName(node) + " failed; see "
                        + "journalctl -u " + Service.unitName(node));
            }
            return waitReady(node, timeout, true);
        }
        Process process = launch(node, timeout, foreground);
        if (foreground) {
            try {
                return process.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroy();
                return 1;
            }
        }
        return waitReady(node, timeout, true);
    }

    /** Starts the node process after every check; also used by restarts in approve and anchor. */
    Process launch(NodeDir node, int timeoutSeconds, boolean foreground) throws IOException {
        distribution.requireLauncher();
        NodeDir.Identity identity = node.requireIdentity();
        if (Files.exists(node.path("run", "join.pending"))) {
            throw CliException.failure("A join did not finish; run  yano.sh appchain join <app>.chain  again");
        }
        if (Processes.running(node)) {
            throw CliException.failure("This node is already running; use  yano.sh appchain status");
        }
        List<NodeConfig.Joined> joined = node.joined();
        if (joined.isEmpty() || !NodeConfig.written(node)) {
            throw CliException.failure("This node has not joined an application; run  yano.sh appchain join "
                    + "<app>.chain  first");
        }
        for (int port : new int[]{identity.httpPort(), identity.listenPort()}) {
            if (!Ports.free(port)) {
                throw CliException.failure("Port " + port + " is already in use by another program. Stop it, or "
                        + "set up this node with other ports (a new --node-dir and request --http-port/--node-port)");
            }
        }
        Map<String, String> installed = distribution.bundleDigests();
        for (NodeConfig.Joined entry : joined) {
            String name = entry.app().genesis().name();
            NodeDir.Local local = node.local(name);
            List<String> changed = local.bundles().entrySet().stream()
                    .filter(bundle -> !bundle.getValue().equals(installed.get(bundle.getKey())))
                    .map(Map.Entry::getKey).toList();
            if (!changed.isEmpty()) {
                throw CliException.failure("Plugin bundles changed since this node joined " + name + ": "
                        + String.join(", ", changed) + ". Reinstall the release it joined with, or accept the "
                        + "upgrade with  yano.sh appchain join " + node.appFile(name) + " --accept-bundles");
            }
        }
        SealedApp first = joined.getFirst().app();
        String network = first.genesis().network();
        Map<String, String> environment = new HashMap<>();
        environment.put("YANO_APPCHAIN_SIGNING_KEY", node.seed());
        environment.put("YANO_APPCHAIN_API_KEYS", node.apiKey());
        environment.put("QUARKUS_CONFIG_LOCATIONS", NodeConfig.consensusFile(node) + ","
                + node.path("config", "node.yaml"));
        if (Anchoring.hasWallet(node)) {
            environment.put("YANO_APPCHAIN_ANCHOR_KEY", NodeDir.read(node.path("secrets", "anchor.key")).strip());
        }
        if ("devnet".equals(network)) {
            Devnet.prepare(node, first, distribution, terminal, timeoutSeconds * 1000L);
            environment.put("YANO_APPCHAIN_GENESIS_TIMESTAMP", Long.toString(Devnet.systemStartMillis(node)));
            environment.put("YANO_APPCHAIN_L1_NETWORK_GENESIS_ID", Distribution.sha256(Devnet.genesis(node)));
        }
        Files.createDirectories(node.path("logs"));
        Files.deleteIfExists(node.path("logs", "node.log.previous"));
        if (Files.exists(node.path("logs", "node.log"))) {
            Files.move(node.path("logs", "node.log"), node.path("logs", "node.log.previous"));
        }
        Files.createDirectories(node.path("data"));
        ProcessBuilder builder = new ProcessBuilder(distribution.launcher().toString(), "start:" + network);
        builder.environment().putAll(environment);
        builder.directory(distribution.home().toFile());
        if (foreground) {
            builder.inheritIO();
        } else {
            Path log = node.path("logs", "node.log");
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        }
        Process process = builder.start();
        NodeDir.write(node.path("run", "node.pid"), process.pid() + "\n");
        terminal.say("Starting " + identity.name() + " (" + String.join(", ",
                joined.stream().map(entry -> entry.app().genesis().name()).toList()) + ") on " + network
                + ", HTTP " + identity.httpPort() + ", node port " + identity.listenPort());
        return process;
    }

    int waitReady(NodeDir node, int timeoutSeconds, boolean showNext) {
        NodeClient client = NodeClient.local(node);
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (client.ready()) {
                terminal.ok("Node " + node.requireIdentity().name() + " is up at " + client.base());
                if (showNext) terminal.next("yano.sh appchain status   (shows whether every member is online)");
                return 0;
            }
            String failure = startupFailure(node);
            if (failure != null) {
                new StopCommand(terminal).stop(node);
                throw CliException.failure("The node failed to start: " + failure + "; see "
                        + node.path("logs", "node.log"));
            }
            if (!Processes.running(node)) {
                throw CliException.failure("The node stopped during startup" + (failure == null ? "" : ": " + failure)
                        + "; see " + node.path("logs", "node.log"));
            }
            Devnet.sleep(2000);
        }
        throw CliException.notReady("The node is still starting after " + timeoutSeconds + "s; check  yano.sh "
                + "appchain status  or " + node.path("logs", "node.log"));
    }

    private static String startupFailure(NodeDir node) {
        Path log = node.path("logs", "node.log");
        if (!Files.isRegularFile(log)) return null;
        try (var lines = Files.lines(log)) {
            return lines.filter(line -> line.contains("Failed to initialize or auto-start"))
                    .reduce((first, second) -> second).map(line -> line.replaceFirst("^.*?(YANO_STARTUP|Failed)", "$1"))
                    .orElse(null);
        } catch (IOException | java.io.UncheckedIOException error) {
            return null;
        }
    }
}
