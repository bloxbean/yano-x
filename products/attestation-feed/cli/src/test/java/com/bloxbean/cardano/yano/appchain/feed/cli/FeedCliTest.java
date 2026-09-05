package com.bloxbean.cardano.yano.appchain.feed.cli;

import com.bloxbean.cardano.yano.appchain.feed.profile.FeedValues;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Offline behaviour of yano-feed on the goldens the cluster test wrote (ADR-052 §7). */
class FeedCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path GOLDEN = Path.of(System.getProperty("yano.feed.cli.golden.dir"));

    @Test
    void usageAndUnknownCommands() {
        Run none = run();
        assertThat(none.code()).isEqualTo(FeedCli.USAGE);
        assertThat(none.out()).contains("yano-feed", "EXPERIMENTAL");
        assertThat(run("--help").code()).isEqualTo(FeedCli.OK);
        Run unknown = run("frobnicate");
        assertThat(unknown.code()).isEqualTo(FeedCli.USAGE);
        assertThat(unknown.err()).contains("unknown command");
        assertThat(run("verify").err()).contains("--bundle is required");
        assertThat(run("round", "get", "--feed", "f").code()).isEqualTo(FeedCli.USAGE);
        // A negative value is a value, not an option.
        assertThat(run("observe", "--feed", "f", "--value", "-1825").err()).contains("--url and --chain");
    }

    @Test
    void genesisDescriptorAndActorKeysAreOffline() throws Exception {
        Run descriptor = run("descriptor", "--demo", "--chain", "c");
        assertThat(descriptor.code()).isEqualTo(FeedCli.OK);
        JsonNode node = JSON.readTree(descriptor.out());
        assertThat(node.path("actors")).hasSize(8);
        Run genesis = run("genesis", "--demo", "--members", "aa".repeat(32) + "," + "bb".repeat(32), "--threshold", "2");
        assertThat(genesis.code()).isEqualTo(FeedCli.OK);
        assertThat(genesis.out()).contains("yano.app-chain.chains[0].state.genesis-id=");
        Path seed = Files.createTempFile("feed-seed", ".hex");
        Files.writeString(seed, "11".repeat(32));
        Files.setPosixFilePermissions(seed, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Run key = run("actor-key", "--actor", "source-alpha", "--seed-file", seed.toString());
        assertThat(key.code()).isEqualTo(FeedCli.OK);
        assertThat(JSON.readTree(key.out()).path("keyId").asText()).isEqualTo("source-alpha-k1");
    }

    @Test
    void verifyReportsTrustLevelsAndDisagreements() {
        Path bundle = GOLDEN.resolve("golden-round.json");
        Path members = GOLDEN.resolve("golden-members.json");
        Run pinned = run("verify", "--bundle", bundle.toString(), "--members", members.toString());
        assertThat(pinned.err()).isEmpty();
        assertThat(pinned.code()).isEqualTo(FeedCli.PINNED);
        assertThat(pinned.out()).contains("CLOSED", "-18.25 degC", "AGREES", "consumed once", "CALLER_PINNED_ROOT",
                "source-gamma: OUTLIER");
        Run declared = run("verify", "--bundle", bundle.toString());
        assertThat(declared.code()).isEqualTo(FeedCli.UNPINNED);
        assertThat(declared.out()).contains("INTERNAL_CONSISTENCY_ONLY");
        Run json = run("verify", "--bundle", bundle.toString(), "--members", members.toString(), "--json");
        assertThat(json.code()).isEqualTo(FeedCli.PINNED);
        Run wrong = run("verify", "--bundle", GOLDEN.resolve("golden-wrong-round.json").toString(),
                "--members", members.toString());
        assertThat(wrong.code()).isEqualTo(FeedCli.INVALID);
        assertThat(wrong.out()).contains("RECORD_DISAGREES", "DISAGREES");
        Run open = run("verify", "--bundle", GOLDEN.resolve("golden-open-round.json").toString(),
                "--members", members.toString());
        assertThat(open.code()).isEqualTo(FeedCli.PINNED);
        assertThat(open.out()).contains("OPEN", "EQUIVOCATED", "FOREIGN_WRITER", "OUT_OF_RANGE");
        Run both = run("verify", "--bundle", bundle.toString(), "--members", members.toString(),
                "--anchor-datum-hex", "00");
        assertThat(both.code()).isEqualTo(FeedCli.USAGE);
    }

    @Test
    void datumAndTamperedDocuments() throws Exception {
        Run datum = run("datum", "--bundle", GOLDEN.resolve("golden-round.json").toString());
        assertThat(datum.code()).isEqualTo(FeedCli.OK);
        assertThat(datum.out()).contains("hex:    d8798a", "binds the record: true");
        assertThat(run("datum", "--bundle", GOLDEN.resolve("golden-open-round.json").toString()).code())
                .isEqualTo(FeedCli.INVALID);
        assertThat(run("datum", "--bundle", GOLDEN.resolve("golden-wrong-round.json").toString()).code())
                .isEqualTo(FeedCli.INVALID);

        String bundleJson = Files.readString(GOLDEN.resolve("golden-round.json"));
        Path tampered = Files.createTempFile("feed-tampered", ".json");
        Files.writeString(tampered, bundleJson.replace("\"round\" : 7", "\"round\" : 8"));
        Run relabelled = run("verify", "--bundle", tampered.toString(),
                "--members", GOLDEN.resolve("golden-members.json").toString());
        assertThat(relabelled.code()).isEqualTo(FeedCli.INVALID);

        String requestJson = Files.readString(GOLDEN.resolve("golden-round-request.json"));
        Path request = Files.createTempFile("feed-request", ".json");
        Files.writeString(request, requestJson.replaceFirst("\"payloadHash\" : \"[0-9a-f]{64}\"",
                "\"payloadHash\" : \"" + "00".repeat(32) + "\""));
        Run apply = run("round", "apply", "--request", request.toString(), "--url", "http://127.0.0.1:1", "--chain", "c");
        assertThat(apply.code()).isEqualTo(FeedCli.INVALID);
        assertThat(apply.err()).contains("payload hash");
    }

    @Test
    void specsSimulationAndValuesParse() throws Exception {
        FeedValues.FeedValue spec = FeedCli.specOf("""
                {"description":"d","unit":"degC","scale":2,"epochStart":"1790000000","roundSeconds":60,
                 "sources":["a","b"],"minimumSources":2,"maximumDeviationPpm":20000,"maximumDeviationAbsolute":50,
                 "minimumValue":-4000,"maximumValue":1000}
                """);
        assertThat(spec.sources()).containsExactly("a", "b");
        assertThat(spec.epochStart()).isEqualTo(1_790_000_000L);
        assertThat(FeedCli.specNode(spec).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(FeedCli.parseValue("-1825")).isEqualTo(-1_825);
        long alpha = FeedCli.simulatedValue("coldstore-7", 7, "source-alpha", -1_825, 5_000, false);
        assertThat(alpha).isBetween(-1_834L, -1_816L);
        assertThat(FeedCli.simulatedValue("coldstore-7", 7, "source-alpha", -1_825, 5_000, false)).isEqualTo(alpha);
        assertThat(FeedCli.simulatedValue("coldstore-7", 7, "source-beta", -1_825, 5_000, false)).isNotEqualTo(alpha);
        assertThat(FeedCli.simulatedValue("coldstore-7", 7, "source-gamma", -1_825, 5_000, true)).isEqualTo(-913);
        assertThat(FeedCli.simulatedValue("f", 1, "s", 0, 5_000, false)).isZero();
    }

    @Test
    void runtimeClasspathHoldsNoRuntimeOrConnector() {
        String classpath = System.getProperty("yano.feed.firewall.classpath");
        assertThat(classpath).isNotBlank();
        for (String entry : classpath.split(File.pathSeparator)) {
            String name = new File(entry).getName().toLowerCase(Locale.ROOT);
            for (String forbidden : List.of("yano-appchain-core-", "yano-node", "yano-consensus", "kafka", "aws-sdk")) {
                assertThat(name).as("runtime classpath entry").doesNotContain(forbidden);
            }
        }
    }

    private record Run(int code, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new FeedCli(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
