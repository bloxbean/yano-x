package org.yanoproject.x.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The `appchain` dispatch that the integrated distribution adds to Yano's launcher (ADR-064 D8). */
class IntegratedLauncherTest {

    private static final Path LAUNCHER = Path.of(System.getProperty("yano.x.test.launcher"));

    @TempDir
    Path root;

    @Test
    void dispatchesEveryCommandToTheBundledToolingFromTheCallerDirectory() throws Exception {
        tool(root.resolve("tools/yano-appchain/bin/yano-appchain"));
        Path caller = Files.createDirectories(root.resolve("caller"));

        Result result = run(caller, Map.of(), "appchain", "cluster", "status", "--json");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("cwd=" + caller.toRealPath() + "\nargs=cluster status --json\n");
    }

    @Test
    void ignoresLegacyDevtoolsInstallations() throws Exception {
        tool(root.resolve("yano-devtools/bin/yano-appchain"));
        tool(root.resolve("yano-devtools-1.0/bin/yano-appchain"));

        Result result = run(root, Map.of(), "appchain", "try");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("requires version-matched app-chain tooling");
    }

    @Test
    void helpWithoutToolingExplainsWhereTheCommandsComeFrom() throws Exception {
        Result result = run(root, Map.of(), "appchain");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("Usage: ./yano.sh appchain <command>");
    }

    @Test
    void anExplicitCliOverrideResolvesAgainstTheCallerDirectory() throws Exception {
        Path caller = Files.createDirectories(root.resolve("caller"));
        tool(caller.resolve("custom-cli"));

        Result result = run(caller, Map.of("YANO_APPCHAIN_CLI", "custom-cli"), "appchain", "new");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).endsWith("args=new\n");
    }

    private static void tool(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "#!/usr/bin/env bash\necho \"cwd=$PWD\"\necho \"args=$*\"\n");
        assertThat(path.toFile().setExecutable(true)).isTrue();
    }

    private Result run(Path directory, Map<String, String> environment, String... arguments) throws Exception {
        Path launcher = root.resolve("yano.sh");
        if (!Files.exists(launcher)) {
            Files.copy(LAUNCHER, launcher);
        }
        List<String> command = new ArrayList<>(List.of("bash", launcher.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().remove("YANO_APPCHAIN_CLI");
        builder.environment().putAll(environment);
        Path stdout = root.resolve("stdout.txt");
        Path stderr = root.resolve("stderr.txt");
        Process process = builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
