package org.yanoproject.x.trust.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class TrustCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path GOLDEN = Path.of(System.getProperty("yano.trust.cli.golden.dir"));
    private static final String MEMBER_A = "aa".repeat(32);
    private static final String MEMBER_B = "bb".repeat(32);

    @TempDir
    Path temp;

    @Test
    void usageAndUnknownCommands() {
        Run none = run();
        assertThat(none.code()).isEqualTo(TrustCli.USAGE);
        assertThat(none.out()).contains("yano-trust");
        assertThat(run("--help").code()).isEqualTo(TrustCli.OK);
        Run unknown = run("frobnicate");
        assertThat(unknown.code()).isEqualTo(TrustCli.USAGE);
        assertThat(unknown.err()).contains("unknown command");
        assertThat(run("status", "--url").code()).isEqualTo(TrustCli.USAGE);
        assertThat(run("status", "--url", "http://x", "--chain", "c").code())
                .as("names no entry").isEqualTo(TrustCli.USAGE);
    }

    @Test
    void genesisIsDeterministicAndPrintsTheFourProperties() throws Exception {
        Run first = run("genesis", "--demo", "--members", MEMBER_A + "," + MEMBER_B,
                "--threshold", "2", "--chain-index", "13");
        assertThat(first.code()).isEqualTo(TrustCli.OK);
        List<String> lines = first.out().lines().toList();
        assertThat(lines).hasSize(4).allMatch(line -> line.startsWith("yano.app-chain.chains[13]."));
        assertThat(first.out()).contains("state.commitment-profile=mpf-blake2b256-v1");
        Run second = run("genesis", "--demo", "--members", MEMBER_A + "," + MEMBER_B,
                "--threshold", "2", "--chain-index", "13");
        assertThat(second.out()).isEqualTo(first.out());

        Path descriptor = temp.resolve("descriptor.json");
        assertThat(run("descriptor", "--demo", "--output", descriptor.toString()).code())
                .isEqualTo(TrustCli.OK);
        Run fromFile = run("genesis", "--descriptor", descriptor.toString(), "--members",
                MEMBER_A + "," + MEMBER_B, "--threshold", "2", "--chain-index", "13");
        assertThat(fromFile.out()).isEqualTo(first.out());
        assertThat(run("genesis", "--demo", "--members", "nope").code()).isEqualTo(TrustCli.INVALID);
    }

    @Test
    void actorKeyDerivesThePublicKeyAndProofFromAnOwnerOnlySeed() throws Exception {
        Path seed = temp.resolve("issuer.seed");
        Files.writeString(seed, "11".repeat(32));
        try {
            Files.setPosixFilePermissions(seed, PosixFilePermissions.fromString("rw-r--r--"));
            Run open = run("actor-key", "--actor", "issuer-x", "--seed-file", seed.toString());
            assertThat(open.code()).isEqualTo(TrustCli.INVALID);
            assertThat(open.err()).contains("chmod 600");
            Files.setPosixFilePermissions(seed, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException notPosix) {
            // permission bits are not enforced on this file system
        }
        Run key = run("actor-key", "--actor", "issuer-x", "--seed-file", seed.toString(),
                "--chain", "registry");
        assertThat(key.code()).isEqualTo(TrustCli.OK);
        JsonNode node = JSON.readTree(key.out());
        assertThat(node.path("keyId").asText()).isEqualTo("issuer-x-k1");
        assertThat(node.path("publicKeyHex").asText()).hasSize(64);
        assertThat(node.path("keyProofHex").asText()).isNotEmpty();
    }

    @Test
    void verifyGoldenAnswerInEveryTrustMode() throws Exception {
        Path answer = GOLDEN.resolve("golden-answer.json");
        Path members = GOLDEN.resolve("golden-members.json");
        if (!Files.exists(answer)) {
            return;
        }
        Run pinned = run("verify", "--answer", answer.toString(), "--members", members.toString());
        assertThat(pinned.err()).isEmpty();
        assertThat(pinned.code()).isEqualTo(TrustCli.PINNED);
        assertThat(pinned.out()).contains("CONSISTENT").contains("CALLER_PINNED_ROOT")
                .contains("DIRECT_ROLE").contains("issuer-a");

        Run declared = run("verify", "--answer", answer.toString(), "--json");
        assertThat(declared.code()).isEqualTo(TrustCli.UNPINNED);
        JsonNode report = JSON.readTree(declared.out());
        assertThat(report.path("consistent").asBoolean()).isTrue();
        assertThat(report.path("trustLevel").asText()).isEqualTo("INTERNAL_CONSISTENCY_ONLY");
        assertThat(report.path("decoded").path("bit").asInt()).isEqualTo(1);

        ObjectNode strangers = (ObjectNode) JSON.readTree(Files.readString(members));
        strangers.set("memberKeysHex", JSON.valueToTree(List.of(MEMBER_A, MEMBER_B)));
        strangers.put("threshold", 1);
        Path strangersFile = temp.resolve("strangers.json");
        Files.writeString(strangersFile, strangers.toString());
        Run wrong = run("verify", "--answer", answer.toString(), "--members", strangersFile.toString());
        assertThat(wrong.code()).isEqualTo(TrustCli.INVALID);
        assertThat(wrong.out()).contains("FAILED");

        ObjectNode tampered = (ObjectNode) JSON.readTree(Files.readString(answer));
        ((ObjectNode) tampered.get("entry")).put("revision", 99);
        Path tamperedFile = temp.resolve("tampered.json");
        Files.writeString(tamperedFile, tampered.toString());
        assertThat(run("verify", "--answer", tamperedFile.toString(), "--members",
                members.toString()).code()).isEqualTo(TrustCli.INVALID);

        Run both = run("verify", "--answer", answer.toString(), "--members", members.toString(),
                "--anchor-datum-hex", "00");
        assertThat(both.code()).isEqualTo(TrustCli.USAGE);
    }

    @Test
    void runtimeClasspathHoldsNoLinkedDataProcessor() {
        String classpath = System.getProperty("yano.trust.firewall.classpath");
        assertThat(classpath).isNotBlank();
        for (String entry : classpath.split(File.pathSeparator)) {
            String name = new File(entry).getName().toLowerCase(Locale.ROOT);
            for (String forbidden : List.of("jsonld", "json-ld", "rdf4j", "jena", "titanium")) {
                assertThat(name).doesNotContain(forbidden);
            }
        }
    }

    private record Run(int code, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new TrustCli(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
