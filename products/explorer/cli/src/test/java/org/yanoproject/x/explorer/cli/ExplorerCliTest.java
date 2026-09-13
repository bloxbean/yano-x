package org.yanoproject.x.explorer.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-050 §2.5 exit codes on the goldens the cluster test wrote, plus the classpath firewall. */
class ExplorerCliTest {
    private static final Path GOLDEN = Path.of(System.getProperty("yano.explorer.cli.golden.dir"));

    private record Run(int exit, String out, String err) { }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = ExplorerCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void usageAndUnknownCommandsExitTwo() {
        assertThat(run().exit()).isEqualTo(2);
        assertThat(run("--help").exit()).isZero();
        assertThat(run("--help").out()).contains("yano-explorer").contains("verify --bundle");
        assertThat(run("bogus").exit()).isEqualTo(2);
        assertThat(run("verify").exit()).isEqualTo(2);
        assertThat(run("trail").exit()).isEqualTo(2);
    }

    @Test
    void verifiesTheGoldenRowBundleUnderEveryTrustInput() throws Exception {
        Path bundle = GOLDEN.resolve("golden-row-bundle.json");
        Path members = GOLDEN.resolve("golden-members.json");
        Run declared = run("verify", "--bundle", bundle.toString());
        assertThat(declared.err()).isEmpty();
        assertThat(declared.exit()).as(declared.out()).isEqualTo(6);
        assertThat(declared.out()).contains("INTERNAL_CONSISTENCY_ONLY").contains("block record");
        Run pinned = run("verify", "--bundle", bundle.toString(), "--members", members.toString());
        assertThat(pinned.exit()).as(pinned.out()).isEqualTo(5);
        assertThat(pinned.out()).contains("CALLER_PINNED_ROOT");
        Run state = run("verify", "--bundle", GOLDEN.resolve("golden-state-bundle.json").toString(),
                "--members", members.toString());
        assertThat(state.exit()).as(state.out()).isEqualTo(5);
        assertThat(state.out()).contains("decoded fact equals");
    }

    @Test
    void tamperedBundlesExitFour() throws Exception {
        String json = Files.readString(GOLDEN.resolve("golden-row-bundle.json"), StandardCharsets.UTF_8);
        Path tampered = Files.createTempFile("explorer-tampered-", ".json");
        Files.writeString(tampered, json.replaceFirst("\"stateRoot\" : \"[0-9a-f]{64}\"",
                "\"stateRoot\" : \"" + "00".repeat(32) + "\""), StandardCharsets.UTF_8);
        Run run = run("verify", "--bundle", tampered.toString());
        assertThat(run.exit()).as(run.out()).isEqualTo(4);
        assertThat(run.out()).contains("FAIL");
        Path notABundle = Files.createTempFile("explorer-plain-", ".json");
        Files.writeString(notABundle, "{\"schema\":\"other\"}", StandardCharsets.UTF_8);
        assertThat(run("verify", "--bundle", notABundle.toString()).exit()).isEqualTo(4);
    }

    @Test
    void runtimeClasspathHoldsNoLinkedDataProcessor() {
        String classpath = System.getProperty("yano.explorer.firewall.classpath");
        assertThat(classpath).isNotBlank();
        for (String entry : classpath.split(File.pathSeparator)) {
            String name = new File(entry).getName().toLowerCase(Locale.ROOT);
            for (String forbidden : List.of("jsonld", "json-ld", "rdf4j", "jena", "titanium")) {
                assertThat(name).doesNotContain(forbidden);
            }
        }
    }
}
