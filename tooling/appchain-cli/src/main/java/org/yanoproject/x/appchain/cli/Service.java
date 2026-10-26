package org.yanoproject.x.appchain.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The systemd unit {@code join} writes on Linux. The tool never installs it; once the operator has, start,
 * stop and restarts go through {@code systemctl} so one node never has two managers.
 */
final class Service {
    private Service() {
    }

    static boolean linux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    static String unitName(NodeDir node) {
        return "yano-appchain-" + node.requireIdentity().name() + ".service";
    }

    static Path unitFile(NodeDir node) {
        return node.path(unitName(node));
    }

    static void writeUnit(NodeDir node, Distribution distribution) {
        if (!linux()) return;
        String user = System.getProperty("user.name");
        String launcher = distribution.home().resolve("tools/yano-appchain/bin/yano-appchain").toString();
        NodeDir.write(unitFile(node), """
                [Unit]
                Description=Yano app-chain node %s
                After=network-online.target
                Wants=network-online.target

                [Service]
                User=%s
                Environment=YANO_HOME=%s
                ExecStart=%s start --foreground --node-dir %s
                Restart=on-failure
                RestartSec=5
                TimeoutStopSec=60
                LimitNOFILE=65536

                [Install]
                WantedBy=multi-user.target
                """.formatted(node.requireIdentity().name(), user, distribution.home(), launcher, node.root()));
    }

    static boolean unitWritten(NodeDir node) {
        return Files.isRegularFile(unitFile(node));
    }

    static String installHint(NodeDir node) {
        return "sudo cp " + unitFile(node) + " /etc/systemd/system/ && sudo systemctl enable --now "
                + unitName(node);
    }

    /** True when the operator installed and enabled this node's unit. */
    static boolean installed(NodeDir node) {
        if (!linux() || node.identity() == null) return false;
        return Files.isRegularFile(Path.of("/etc/systemd/system", unitName(node)))
                && systemctl("is-enabled", unitName(node)) == 0;
    }

    static boolean active(NodeDir node) {
        return installed(node) && systemctl("is-active", unitName(node)) == 0;
    }

    static int systemctl(String... arguments) {
        String[] command = new String[arguments.length + 1];
        command[0] = "systemctl";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(30, TimeUnit.SECONDS) ? process.exitValue() : 1;
        } catch (IOException error) {
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }
}
