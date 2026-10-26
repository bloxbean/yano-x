package org.yanoproject.x.appchain.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** {@code stop}: stops this node and keeps all its data. */
final class StopCommand {
    static final String USAGE = "yano.sh appchain stop [--node-dir yano-node]";

    private final Terminal terminal;

    StopCommand(Terminal terminal) {
        this.terminal = terminal;
    }

    int run(String[] arguments) {
        Args args = new Args(arguments, Set.of("node-dir"), Set.of("yes"));
        args.maxPositionals(0, USAGE);
        NodeDir node = new NodeDir(args.path("node-dir", Path.of(NodeDir.DEFAULT)));
        stop(node);
        return 0;
    }

    void stop(NodeDir node) {
        if (Service.installed(node)) {
            if (Service.systemctl("stop", Service.unitName(node)) != 0) {
                throw CliException.failure("systemctl stop " + Service.unitName(node) + " failed");
            }
            terminal.ok("Stopped " + Service.unitName(node) + "; its data is kept");
            return;
        }
        var handle = Processes.node(node);
        if (handle.isEmpty()) {
            terminal.say("This node is not running");
            return;
        }
        handle.get().destroy();
        try {
            handle.get().onExit().get(60, TimeUnit.SECONDS);
        } catch (Exception slow) {
            handle.get().destroyForcibly();
        }
        try {
            Files.deleteIfExists(node.path("run", "node.pid"));
        } catch (java.io.IOException ignored) {
            // A stale record is ignored by the next start.
        }
        terminal.ok("Stopped " + node.requireIdentity().name() + "; its data is kept");
    }
}
