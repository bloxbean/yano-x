package org.yanoproject.x.attest.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the CLI against the committed golden certificate produced by the cluster test. */
class AttestCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path GOLDEN = Path.of(System.getProperty("yano.attest.cli.golden.dir"));

    private record Run(int code, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new AttestCli(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helpAndUsage() {
        assertThat(run("--help").code()).isEqualTo(AttestCli.OK);
        assertThat(run("--help").out()).contains("yano-attest <command>");
        assertThat(run().code()).isEqualTo(AttestCli.USAGE);
        assertThat(run("bogus").code()).isEqualTo(AttestCli.USAGE);
        assertThat(run("verify").code()).isEqualTo(AttestCli.USAGE);
        assertThat(run("verify", "--certificate").code()).isEqualTo(AttestCli.USAGE);
        assertThat(run("attest", "--file", "x").code()).isEqualTo(AttestCli.USAGE);
    }

    @Test
    void verifiesGoldenWithPinnedMembers() throws Exception {
        Run run = run("verify", "--certificate", GOLDEN.resolve("golden-certificate.json").toString(),
                "--file", GOLDEN.resolve("golden-document.txt").toString(),
                "--members", GOLDEN.resolve("golden-members.json").toString(), "--json");
        assertThat(run.err()).isEmpty();
        assertThat(run.code()).isEqualTo(AttestCli.PINNED);
        JsonNode report = JSON.readTree(run.out());
        assertThat(report.get("accepted").asBoolean()).isTrue();
        assertThat(report.get("trustLevel").asText()).isEqualTo("CALLER_PINNED_ROOT");
        assertThat(report.get("checks").get("digest").asText()).isEqualTo("MATCH");
        assertThat(report.get("checks").get("finality").asText()).isEqualTo("VALID");
        assertThat(report.get("checks").get("trailHead").asText()).isEqualTo("VERIFIED");
        assertThat(report.get("failures")).isEmpty();
    }

    @Test
    void humanReadableReportListsEveryCheck() {
        Run run = run("verify", "--certificate", GOLDEN.resolve("golden-certificate.json").toString(),
                "--members", GOLDEN.resolve("golden-members.json").toString());
        assertThat(run.code()).isEqualTo(AttestCli.PINNED);
        assertThat(run.out()).contains("C1 digest", "NOT_SUPPLIED", "C4 finality", "VALID", "ACCEPTED");
    }

    @Test
    void unpinnedVerificationIsConsistentOnly() throws Exception {
        Run run = run("verify", "--certificate", GOLDEN.resolve("golden-certificate.json").toString(), "--json");
        assertThat(run.code()).isEqualTo(AttestCli.UNPINNED);
        JsonNode report = JSON.readTree(run.out());
        assertThat(report.get("accepted").asBoolean()).isFalse();
        assertThat(report.get("consistent").asBoolean()).isTrue();
        assertThat(report.get("trustLevel").asText()).isEqualTo("INTERNAL_CONSISTENCY_ONLY");
    }

    @Test
    void mismatchedFileIsInvalid(@TempDir Path temp) throws Exception {
        Path other = temp.resolve("other.txt");
        Files.writeString(other, "not the golden document");
        Run run = run("verify", "--certificate", GOLDEN.resolve("golden-certificate.json").toString(),
                "--file", other.toString(), "--members", GOLDEN.resolve("golden-members.json").toString(), "--json");
        assertThat(run.code()).isEqualTo(AttestCli.INVALID);
        JsonNode report = JSON.readTree(run.out());
        assertThat(report.get("checks").get("digest").asText()).isEqualTo("MISMATCH");
    }

    @Test
    void wrongMembersAreInvalid(@TempDir Path temp) throws Exception {
        ObjectNode members = (ObjectNode) JSON.readTree(Files.readString(GOLDEN.resolve("golden-members.json")));
        ((com.fasterxml.jackson.databind.node.ArrayNode) members.get("memberKeysHex")).set(0, "99".repeat(32));
        Path wrong = temp.resolve("members.json");
        Files.writeString(wrong, members.toString());
        Run run = run("verify", "--certificate", GOLDEN.resolve("golden-certificate.json").toString(),
                "--members", wrong.toString(), "--json");
        assertThat(run.code()).isEqualTo(AttestCli.INVALID);
        assertThat(JSON.readTree(run.out()).get("checks").get("finality").asText()).isEqualTo("INVALID");
    }

    @Test
    void anchorDatumWithoutAnchoredSegmentIsInvalid() {
        // A syntactically invalid datum is rejected as invalid input, never as unavailable.
        Run run = run("verify", "--certificate", GOLDEN.resolve("golden-certificate.json").toString(),
                "--anchor-datum-hex", "d8799f00ff", "--json");
        assertThat(run.code()).isEqualTo(AttestCli.INVALID);
    }

    @Test
    void malformedCertificateIsInvalid(@TempDir Path temp) throws Exception {
        Path broken = temp.resolve("broken.json");
        Files.writeString(broken, "{\"schema\":\"nope\"}");
        Run run = run("verify", "--certificate", broken.toString());
        assertThat(run.code()).isEqualTo(AttestCli.INVALID);
        assertThat(run.err()).contains("Invalid attest certificate");
    }

    @Test
    void missingCertificateIsUnavailable() {
        Run run = run("verify", "--certificate", GOLDEN.resolve("does-not-exist.json").toString());
        assertThat(run.code()).isEqualTo(AttestCli.UNAVAILABLE);
    }

    @Test
    void unreachableNodeIsUnavailable() {
        Run run = run("status", "--url", "http://127.0.0.1:9/api/v1", "--chain", "x");
        assertThat(run.code()).isEqualTo(AttestCli.UNAVAILABLE);
    }
}
