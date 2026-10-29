package org.yanoproject.x.appchain.cli;

import java.nio.file.Files;
import java.util.Optional;

/** The node process this tool started, tracked by {@code run/node.pid}. */
final class Processes {
    private Processes() {
    }

    /**
     * The recorded node process, when it is still the same process: the file holds the pid and its start time, so
     * a pid reused after a crash or reboot is never mistaken for the node.
     */
    static Optional<ProcessHandle> node(NodeDir node) {
        var file = node.path("run", "node.pid");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            String[] parts = NodeDir.read(file).strip().split("\\s+");
            // The launcher execs the JVM, so the recorded pid is the node for its whole life.
            Optional<ProcessHandle> handle = ProcessHandle.of(Long.parseLong(parts[0])).filter(ProcessHandle::isAlive);
            if (parts.length < 2) return handle;
            long started = Long.parseLong(parts[1]);
            return handle.filter(process -> process.info().startInstant()
                    .map(instant -> Math.abs(instant.toEpochMilli() - started) < 2000).orElse(true));
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    static void record(NodeDir node, Process process) {
        long started = process.toHandle().info().startInstant().map(java.time.Instant::toEpochMilli)
                .orElse(System.currentTimeMillis());
        NodeDir.write(node.path("run", "node.pid"), process.pid() + " " + started + "\n");
    }

    static boolean running(NodeDir node) {
        return node(node).isPresent() || Service.active(node);
    }
}
