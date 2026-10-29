package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class AppChainPackagedCliTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path temporary;

    @Test
    void installedAndStandaloneZipLaunchersContainRunnableVersionedMetadata() throws Exception {
        Path install = configuredPath("yano.test.appchain-cli-install-dir");
        Path distribution = configuredPath("yano.test.appchain-cli-dist-zip");
        Path launcher = launcher(install);
        Path config = temporary.resolve("application-appchain.yml");
        Files.writeString(config, """
                yano:
                  app-chain:
                    chain-id: packaged-test
                """, StandardCharsets.UTF_8);

        Result validate = run(launcher, "config", "validate", "--mode", "template",
                config.toString());
        Result explain = run(launcher, "config", "explain", "block.max-bytes");

        String signingKey = "b".repeat(64);
        Path resolvedConfig = temporary.resolve("resolved-appchain.yml");
        Files.writeString(resolvedConfig, """
                yano:
                  app-chain:
                    enabled: true
                    chain-id: packaged-resolved-test
                    signing-key: %s
                    members: %s
                    threshold: 1
                    state:
                      commitment-profile: mpf-blake2b256-v1
                      format-fingerprint: 91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b
                      genesis-id: %s
                """.formatted(signingKey, "a".repeat(64), "11".repeat(32)),
                StandardCharsets.UTF_8);
        Result resolved = run(launcher, "config", "validate", "--mode", "resolved",
                "--format", "json", "--config", resolvedConfig.toString());
        Result effective = run(launcher, "config", "effective", "--mode", "resolved",
                "--format", "json", "--show-sources", "--config", resolvedConfig.toString());
        Path project = temporary.resolve("packaged-project");
        Result created = run(launcher, "new", "packaged", "--network", "devnet", "--operators", "alice",
                "--chain", "orders=audit-log", "--output", project.toString(), "--yes");
        Path draft = project.resolve("packaged.draft.yaml");
        Result requested = run(launcher, "request", draft.toString(), "--name", "alice", "--host", "127.0.0.1",
                "--node-dir", project.resolve("alice-node").toString(), "--output", project.toString(), "--yes");
        Result inspected = run(launcher, "inspect", project.resolve("alice.member").toString(), "--json");
        Result capabilities = run(launcher, "capabilities", "--format", "json");
        Result eutxoDemo = run(launcher, "eutxo", "demo", "scenarios", "--format", "json");
        Path actorSeed = temporary.resolve("packaged-actor.seed");
        Files.writeString(actorSeed, "11".repeat(32), StandardCharsets.US_ASCII);
        Result actorPublicKey = run(launcher, "appchain", "role", "public-key",
                "--seed-file", actorSeed.toString());

        assertThat(validate.exitCode()).isZero();
        assertThat(validate.output()).contains("VALID_TEMPLATE");
        assertThat(validate.error()).isEmpty();
        assertThat(explain.exitCode()).isZero();
        assertThat(explain.output()).contains("PROPERTY\tyano.app-chain.block.max-bytes");
        assertThat(resolved.exitCode()).isZero();
        assertThat(resolved.output()).contains("\"status\":\"VALID_RESOLVED\"")
                .doesNotContain(signingKey);
        assertThat(resolved.error()).isEmpty();
        assertThat(effective.exitCode()).isZero();
        assertThat(effective.output()).contains("<redacted>", "resolved-appchain.yml")
                .doesNotContain(signingKey)
                .doesNotContain(temporary.toString());
        assertThat(effective.error()).isEmpty();
        assertThat(created.exitCode()).as(created.error()).isZero();
        assertThat(created.output()).contains("invite code", "Next:");
        assertThat(draft).isRegularFile();
        assertThat(requested.exitCode()).as(requested.error()).isZero();
        assertThat(requested.output()).contains("alice.member");
        assertThat(inspected.exitCode()).as(inspected.error()).isZero();
        assertThat(inspected.output()).contains("\"signatureValid\" : true");
        assertThat(project.resolve("alice-node/secrets/member.key")).isRegularFile();
        assertThat(inspected.output()).doesNotContain(Files.readString(
                project.resolve("alice-node/secrets/member.key")).strip());
        assertThat(capabilities.exitCode()).isZero();
        assertThat(capabilities.output()).contains(
                "state:role-approvals", "state:role-evidence", "state:eutxo-ledger",
                "state:custom-plugin");
        assertThat(eutxoDemo.exitCode()).isZero();
        assertThat(eutxoDemo.output())
                .contains("\"status\":\"EUTXO_DEMO_SCENARIOS\"")
                .contains("\"id\":\"ledger\"", "\"id\":\"bridge\"");
        assertThat(eutxoDemo.error()).isEmpty();
        assertThat(actorPublicKey.exitCode()).isZero();
        assertThat(actorPublicKey.output().trim()).matches("[0-9a-f]{64}")
                .doesNotContain("11".repeat(32));
        assertThat(actorPublicKey.error()).isEmpty();

        try (ZipFile archive = new ZipFile(distribution.toFile())) {
            List<String> entries = archive.stream().map(entry -> entry.getName()).toList();
            assertThat(entries).anyMatch(name -> name.endsWith("/bin/yano-appchain"));
            assertThat(entries).anyMatch(name ->
                    name.endsWith("/metadata/appchain-dx/v1/appchain-runtime.schema.json"));
            assertThat(entries).anyMatch(name ->
                    name.endsWith("/metadata/appchain-dx/v1/appchain-property-catalog.json"));
            assertThat(entries).anyMatch(name ->
                    name.endsWith("/metadata/appchain-dx/v1alpha1/appchain-capability-catalog.json"));
            assertThat(entries).anyMatch(name ->
                    name.endsWith("/metadata/appchain-dx/v1alpha1/"
                            + "appchain-release-capability-index.json"));
            assertThat(entries).anyMatch(name ->
                    name.endsWith("/metadata/appchain-dx/v1alpha1/"
                            + "appchain-first-party-metadata.json"));
            assertThat(entries).anyMatch(name ->
                    name.endsWith("/metadata/appchain-dx/v1alpha1/"
                            + "appchain-metadata-trust.schema.json"));
            assertThat(entries).anyMatch(name ->
                    name.endsWith("/skills/configure-yano-appchain/SKILL.md"));
            assertThat(entries).anyMatch(name ->
                    name.endsWith("/skills/configure-yano-appchain/agents/openai.yaml"));
        }
    }

    private static Path configuredPath(String property) {
        String value = System.getProperty(property);
        assertThat(value).as(property).isNotBlank();
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static Path launcher(Path install) {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
        Path path = install.resolve("bin").resolve(windows ? "yano-appchain.bat" : "yano-appchain");
        assertThat(path).isRegularFile();
        return path;
    }

    private Result run(Path launcher, String... arguments) throws Exception {
        boolean windows = launcher.getFileName().toString().endsWith(".bat");
        List<String> command = new ArrayList<>();
        if (windows) {
            command.addAll(List.of("cmd.exe", "/d", "/c"));
        }
        command.add(launcher.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(temporary.toFile()).start();
        if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            fail("packaged app-chain CLI exceeded " + TIMEOUT);
        }
        return new Result(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String output, String error) {
    }
}
