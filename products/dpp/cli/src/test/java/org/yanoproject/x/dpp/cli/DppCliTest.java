package org.yanoproject.x.dpp.cli;

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

class DppCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path GOLDEN = Path.of(System.getProperty("yano.dpp.cli.golden.dir"));
    private static final String MEMBER_A = "aa".repeat(32);
    private static final String MEMBER_B = "bb".repeat(32);

    @TempDir
    Path temp;

    @Test
    void usageAndUnknownCommands() {
        Run none = run();
        assertThat(none.code()).isEqualTo(DppCli.USAGE);
        assertThat(none.out()).contains("yano-dpp").contains("PROTOTYPE");
        assertThat(run("--help").code()).isEqualTo(DppCli.OK);
        Run unknown = run("frobnicate");
        assertThat(unknown.code()).isEqualTo(DppCli.USAGE);
        assertThat(unknown.err()).contains("unknown command");
        assertThat(run("passport", "--product", "p").code()).as("no node named").isEqualTo(DppCli.USAGE);
        assertThat(run("certify", "frob", "--url", "http://x", "--chain", "c").code()).isEqualTo(DppCli.USAGE);
        assertThat(run("verify", "--members").code()).isEqualTo(DppCli.USAGE);
    }

    @Test
    void genesisIsDeterministicAndPrintsTheFourProperties() throws Exception {
        Run first = run("genesis", "--demo", "--members", MEMBER_A + "," + MEMBER_B,
                "--threshold", "2", "--chain-index", "7");
        assertThat(first.code()).isEqualTo(DppCli.OK);
        List<String> lines = first.out().lines().toList();
        assertThat(lines).hasSize(4).allMatch(line -> line.startsWith("yano.app-chain.chains[7]."));
        assertThat(first.out()).contains("state.commitment-profile=mpf-blake2b256-v1");
        Run second = run("genesis", "--demo", "--members", MEMBER_A + "," + MEMBER_B,
                "--threshold", "2", "--chain-index", "7");
        assertThat(second.out()).isEqualTo(first.out());

        Path descriptor = temp.resolve("descriptor.json");
        assertThat(run("descriptor", "--demo", "--output", descriptor.toString()).code()).isEqualTo(DppCli.OK);
        JsonNode parsed = JSON.readTree(Files.readString(descriptor));
        assertThat(parsed.path("actors")).hasSize(7);
        Run fromFile = run("genesis", "--descriptor", descriptor.toString(), "--members",
                MEMBER_A + "," + MEMBER_B, "--threshold", "2", "--chain-index", "7");
        assertThat(fromFile.out()).isEqualTo(first.out());
        assertThat(run("genesis", "--demo", "--members", "nope").code()).isEqualTo(DppCli.INVALID);
    }

    @Test
    void actorKeyDerivesThePublicKeyAndProofFromAnOwnerOnlySeed() throws Exception {
        Path seed = temp.resolve("maker.seed");
        Files.writeString(seed, "11".repeat(32));
        try {
            Files.setPosixFilePermissions(seed, PosixFilePermissions.fromString("rw-r--r--"));
            Run open = run("actor-key", "--actor", "maker-x", "--seed-file", seed.toString());
            assertThat(open.code()).isEqualTo(DppCli.INVALID);
            assertThat(open.err()).contains("chmod 600");
            Files.setPosixFilePermissions(seed, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException notPosix) {
            // permission bits are not enforced on this file system
        }
        Run key = run("actor-key", "--actor", "maker-x", "--seed-file", seed.toString(), "--chain", "dpp");
        assertThat(key.code()).isEqualTo(DppCli.OK);
        JsonNode node = JSON.readTree(key.out());
        assertThat(node.path("keyId").asText()).isEqualTo("maker-x-k1");
        assertThat(node.path("publicKeyHex").asText()).hasSize(64);
        assertThat(node.path("keyProofHex").asText()).isNotEmpty();
    }

    @Test
    void goldenPassportVerifiesDisclosesAndFailsWhenTampered() throws Exception {
        Path passport = GOLDEN.resolve("golden-passport.json");
        if (!Files.exists(passport)) {
            return;
        }
        Path members = GOLDEN.resolve("golden-members.json");
        Path disclosure = GOLDEN.resolve("golden-disclosure.json");

        Run declared = run("verify", "--passport", passport.toString());
        assertThat(declared.code()).as(declared.err()).isEqualTo(DppCli.UNPINNED);
        assertThat(declared.out()).contains("CONSISTENT").contains("INTERNAL_CONSISTENCY_ONLY")
                .contains("gtin:09506000134352").contains("REWRITTEN").contains("consumed once");

        Run pinned = run("verify", "--passport", passport.toString(), "--members", members.toString(), "--json");
        assertThat(pinned.code()).as(pinned.err()).isEqualTo(DppCli.PINNED);
        JsonNode report = JSON.readTree(pinned.out());
        assertThat(report.path("verification").path("trustLevel").asText()).isEqualTo("CALLER_PINNED_ROOT");
        assertThat(report.path("passport").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(report.path("passport").path("certificates").get(0).path("approvalConsumption").asBoolean()).isTrue();

        Run disclosed = run("disclose", "--passport", passport.toString(), "--disclosure", disclosure.toString(),
                "--members", members.toString());
        assertThat(disclosed.code()).as(disclosed.err()).isEqualTo(DppCli.PINNED);
        assertThat(disclosed.out()).contains("MATCH").contains("12.5 kgCO2e");

        ObjectNode wrongSalt = (ObjectNode) JSON.readTree(Files.readString(disclosure));
        wrongSalt.put("saltHex", "00".repeat(32));
        Path wrongSaltFile = temp.resolve("wrong-salt.json");
        Files.writeString(wrongSaltFile, wrongSalt.toString());
        Run mismatch = run("disclose", "--passport", passport.toString(), "--disclosure", wrongSaltFile.toString());
        assertThat(mismatch.code()).isEqualTo(DppCli.INVALID);
        assertThat(mismatch.out()).contains("MISMATCH");

        ObjectNode tampered = (ObjectNode) JSON.readTree(Files.readString(passport));
        tampered.put("stateRoot", "00".repeat(32));
        Path tamperedFile = temp.resolve("tampered.json");
        Files.writeString(tamperedFile, tampered.toString());
        Run failed = run("verify", "--passport", tamperedFile.toString(), "--members", members.toString());
        assertThat(failed.code()).isEqualTo(DppCli.INVALID);
        assertThat(failed.out()).contains("FAILED");

        ObjectNode strangers = JSON.createObjectNode();
        strangers.put("chainId", JSON.readTree(Files.readString(members)).path("chainId").asText());
        strangers.put("threshold", 1);
        strangers.putArray("memberKeysHex").add(MEMBER_A).add(MEMBER_B);
        Path strangersFile = temp.resolve("strangers.json");
        Files.writeString(strangersFile, strangers.toString());
        assertThat(run("verify", "--passport", passport.toString(), "--members", strangersFile.toString()).code())
                .isEqualTo(DppCli.INVALID);
    }

    @Test
    void certificationRequestsAreCheckedBeforeSigning() throws Exception {
        Path request = GOLDEN.resolve("golden-certification-request.json");
        if (!Files.exists(request)) {
            return;
        }
        ObjectNode tampered = (ObjectNode) JSON.readTree(Files.readString(request));
        tampered.put("payloadHash", "22".repeat(32));
        Path tamperedFile = temp.resolve("request.json");
        Files.writeString(tamperedFile, tampered.toString());
        Path seed = temp.resolve("auditor.seed");
        Files.writeString(seed, "11".repeat(32));
        try {
            Files.setPosixFilePermissions(seed, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException notPosix) {
            // permission bits are not enforced on this file system
        }
        Run approve = run("certify", "approve", "--url", "http://127.0.0.1:9", "--chain", "c",
                "--request", tamperedFile.toString(), "--actor", "auditor-a", "--seed-file", seed.toString());
        assertThat(approve.code()).isEqualTo(DppCli.INVALID);
        assertThat(approve.err()).contains("payload hash");
    }

    @Test
    void runtimeClasspathHoldsNoLinkedDataProcessor() {
        String classpath = System.getProperty("yano.dpp.firewall.classpath");
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
        int code = new DppCli(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
