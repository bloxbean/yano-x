package org.yanoproject.x.appchain.cli;

import java.nio.file.Files;
import java.util.Optional;

/** The node process this tool started, tracked by {@code run/node.pid}. */
final class Processes {
    private Processes() {
    }

    static Optional<ProcessHandle> node(NodeDir node) {
        var file = node.path("run", "node.pid");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            long pid = Long.parseLong(NodeDir.read(file).strip());
            // The launcher execs the JVM, so the recorded pid is the node for its whole life.
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    static boolean running(NodeDir node) {
        return node(node).isPresent() || Service.active(node);
    }
}
