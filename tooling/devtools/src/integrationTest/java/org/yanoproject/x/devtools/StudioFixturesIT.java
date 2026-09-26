package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.x.stdlib.contracts.ApprovalsContract;
import org.yanoproject.x.stdlib.contracts.KvRegistryContract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generated Studio inputs (ADR-031.2 C1/C2): the shipped first-party authoring catalog and the report/catalog
 * fixtures used by Studio's Node tests. Everything is produced by the real CLI from the real first-party bundles;
 * nothing is handwritten. {@link #main} regenerates the checked-in files; the test fails when they drift.
 * Build-specific identity values (JAR digests and the plugin fingerprint) are compared as placeholders because
 * they change with every local build; they remain in the files as the record of what generated them.
 */
class StudioFixturesIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> VOLATILE = Set.of("jarSha256", "linkedCompositeJarSha256", "coreApiJarSha256",
            "runtimeJarSha256", "fingerprint", "digest");
    @TempDir Path temporary;

    /** Regenerates the checked-in outputs. Arguments: repository root. */
    public static void main(String[] args) throws Exception {
        Path repository = Path.of(args[0]);
        Path work = Files.createTempDirectory("studio-fixtures");
        for (var entry : generate(repository, work).entrySet()) {
            Path target = repository.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void checkedInStudioCatalogAndFixturesMatchRegeneratedOutput() throws Exception {
        Path repository = Path.of(System.getProperty("yano.test.repo-root"));
        var generated = generate(repository, temporary);
        List<String> drift = new ArrayList<>();
        for (var entry : generated.entrySet()) {
            Path file = repository.resolve(entry.getKey());
            if (!Files.exists(file)) { drift.add(entry.getKey() + " is missing"); continue; }
            String current = Files.readString(file);
            boolean same = entry.getKey().endsWith(".json")
                    ? normalize(JSON.readTree(current)).equals(normalize(JSON.readTree(entry.getValue())))
                    : current.equals(entry.getValue());
            if (!same) drift.add(entry.getKey() + " differs");
        }
        assertThat(drift).as("run ./gradlew :tooling:devtools:regenerateStudioFixtures").isEmpty();
    }

    @Test
    void shippedContextIsTheTutorialContextByteForByte() throws IOException {
        Path repository = Path.of(System.getProperty("yano.test.repo-root"));
        assertThat(Files.readString(repository.resolve("tooling/studio/src/main/web/binding-authoring-context.json")))
                .isEqualTo(tutorialBlocks(repository).get(1));
    }

    static Map<String, String> generate(Path repository, Path work) throws Exception {
        Path plugins = Files.createDirectories(work.resolve("plugins"));
        for (String bundle : System.getProperty("yano.test.binding-bundles").split(java.io.File.pathSeparator)) {
            Path source = Path.of(bundle);
            Files.copy(source, plugins.resolve(source.getFileName()));
        }
        List<String> blocks = tutorialBlocks(repository);
        Path context = repository.resolve("tooling/studio/src/main/web/binding-authoring-context.json");
        Path document = work.resolve("bindings.yaml");
        Files.writeString(document, blocks.get(0));
        Path fixture = work.resolve("fixture.json");
        Files.writeString(fixture, blocks.get(2));
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("tooling/studio/src/main/web/binding-authoring-catalog.json",
                cli(List.of("bindings", "catalog", "--plugins-directory", plugins.toString(), "--context",
                        context.toString(), "--all"), 0).out());
        String fixtures = "tooling/studio/src/test/fixtures/";
        outputs.put(fixtures + "tutorial-bindings.yaml", blocks.get(0));
        outputs.put(fixtures + "tutorial-fixture.json", blocks.get(2));
        outputs.put(fixtures + "catalog-tutorial.json", cli(List.of("bindings", "catalog", document.toString(),
                "--plugins-directory", plugins.toString(), "--context", context.toString()), 0).out());
        outputs.put(fixtures + "report-validate.json",
                report(work, "validate", document, plugins, context, List.of(), 0));
        outputs.put(fixtures + "report-dry-run.json", report(work, "dry-run", document, plugins, context,
                List.of("--fixture", fixture.toString()), 0));
        Path invalid = work.resolve("invalid.yaml");
        Files.writeString(invalid, blocks.get(0).replace("{field: valueHash}", "{fn: hex, args: [{field: key}]}"));
        outputs.put(fixtures + "report-validate-failed.json", report(work, "validate", invalid, plugins, context,
                List.of(), 2));
        // A second block replays the first message and repeats a fresh one: dispositions must say so.
        Path prior = work.resolve("prior.json");
        Files.writeString(prior, cli(List.of("bindings", "dry-run", document.toString(), "--plugins-directory",
                plugins.toString(), "--context", context.toString(), "--fixture", fixture.toString()), 0).out());
        JsonNode first = JSON.readTree(blocks.get(2));
        ObjectNode next = (ObjectNode) first.deepCopy();
        next.put("height", 2);
        next.put("timestamp", 456);
        ObjectNode fresh = (ObjectNode) first.path("messages").get(0).deepCopy();
        fresh.put("messageIdHex", "33".repeat(32));
        fresh.put("senderSeq", 2);
        ArrayNode messages = JSON.createArrayNode().add(first.path("messages").get(0)).add(fresh).add(fresh);
        next.set("messages", messages);
        Path second = work.resolve("fixture-2.json");
        Files.writeString(second, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(next));
        outputs.put(fixtures + "tutorial-fixture-2.json", Files.readString(second));
        outputs.put(fixtures + "report-dry-run-replay.json", report(work, "dry-run", document, plugins, context,
                List.of("--fixture", second.toString(), "--prior-result", prior.toString()), 0));
        scenarios(repository, work, plugins, context, outputs, fixtures + "scenarios/");
        return outputs;
    }

    /**
     * Engine outcome scenarios for Studio's receipt explanation (ADR-031.2 M2), each a real dry-run through the
     * packaged CLI. Command bodies come from the stdlib contract encoders, never from browser code.
     */
    private static void scenarios(Path repository, Path work, Path plugins, Path context, Map<String, String> outputs,
                                  String directory) throws Exception {
        String registry = Files.readString(repository.resolve("examples/bindings/registry-to-audit.yaml"));
        // A short value cascades into the audit trail; a 100-byte value fails the clause and is skipped.
        scenario(work, plugins, context, outputs, directory, "cascade", "registry-to-audit", registry,
                List.of(fixture(1, List.of(
                        message(0x11, 0x22, 1, "records.command.v1",
                                KvRegistryContract.put(new byte[]{1}, new byte[]{3, 4})),
                        message(0x12, 0x22, 2, "records.command.v1",
                                KvRegistryContract.put(new byte[]{2}, new byte[100]))))));
        // A first write has an empty previousValueHash, so the derived append is malformed and the whole cascade,
        // source write included, rolls back. The second write to the same key therefore fails the same way.
        scenario(work, plugins, context, outputs, directory, "rollback", null,
                registry.replace("entryHash: {field: valueHash}", "entryHash: {field: previousValueHash}"),
                List.of(fixture(1, List.of(
                        message(0x21, 0x22, 1, "records.command.v1",
                                KvRegistryContract.put(new byte[]{1}, new byte[]{5})),
                        message(0x22, 0x22, 2, "records.command.v1",
                                KvRegistryContract.put(new byte[]{1}, new byte[]{6}))))));
        // A committed per-cascade work limit below the mandatory decoding work exhausts the budget at the source.
        scenario(work, plugins, context, outputs, directory, "budget", null, registry.replace("  bindings:",
                "  limits: {maxExpressionWorkPerCascade: 16}\n  bindings:"), List.of(fixture(1, List.of(
                        message(0x31, 0x22, 1, "records.command.v1",
                                KvRegistryContract.put(new byte[]{1}, new byte[]{3, 4}))))));
        // A dry-run that fails at fixture admission: the document compiled, the rehearsal input was rejected.
        Path failingDocument = work.resolve("fixture-failure.yaml");
        Files.writeString(failingDocument, registry);
        Path failingFixture = work.resolve("fixture-failure-fixture.json");
        String failingText = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(fixture(1, List.of(
                message(0x61, 0x22, 1, "records.command.v1", new byte[]{(byte) 0xff})))) + "\n";
        Files.writeString(failingFixture, failingText);
        outputs.put(directory + "fixture-failure-fixture.json", failingText);
        outputs.put(directory + "fixture-failure-report.json", report(work, "dry-run", failingDocument, plugins,
                context, List.of("--fixture", failingFixture.toString()), 2));
        // A proposal, a first vote, an explicit empty block and a second vote from a different member.
        String approval = Files.readString(repository.resolve("examples/bindings/approval-to-audit.yaml"));
        scenario(work, plugins, context, outputs, directory, "approval", "approval-to-audit", approval, List.of(
                fixture(1, List.of(message(0x41, 0x51, 1, "reviews.command.v1",
                        ApprovalsContract.propose("item-1", new byte[]{9, 9}, 2, Long.MAX_VALUE)))),
                fixture(2, List.of(message(0x42, 0x52, 1, "reviews.command.v1", ApprovalsContract.approve("item-1")))),
                fixture(3, List.of()),
                fixture(4, List.of(message(0x43, 0x53, 1, "reviews.command.v1",
                        ApprovalsContract.approve("item-1"))))));
    }

    /**
     * Runs one rehearsal sequence. When {@code example} names a starter, its fixtures are the public example
     * inputs shipped with Studio under {@code examples/bindings/fixtures/<example>/fixture-N.json}, the fixed names
     * the editor's CLI handoff uses; otherwise they stay with the test fixtures.
     */
    private static void scenario(Path work, Path plugins, Path context, Map<String, String> outputs, String directory,
                                 String name, String example, String document, List<ObjectNode> blocks)
            throws Exception {
        Path documentPath = work.resolve(name + ".yaml");
        Files.writeString(documentPath, document);
        outputs.put(directory + name + ".yaml", document);
        Path prior = null;
        for (int block = 1; block <= blocks.size(); block++) {
            String suffix = blocks.size() == 1 ? "" : "-" + block;
            String fixtureText = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(blocks.get(block - 1)) + "\n";
            Path fixturePath = work.resolve(name + "-fixture" + suffix + ".json");
            Files.writeString(fixturePath, fixtureText);
            outputs.put(example == null ? directory + name + "-fixture" + suffix + ".json"
                    : "examples/bindings/fixtures/" + example + "/fixture-" + block + ".json", fixtureText);
            Path reportPath = work.resolve(name + "-report" + suffix + ".json");
            List<String> args = new ArrayList<>(List.of("bindings", "dry-run", documentPath.toString(),
                    "--plugins-directory", plugins.toString(), "--context", context.toString(),
                    "--fixture", fixturePath.toString(), "--report", reportPath.toString()));
            if (prior != null) args.addAll(List.of("--prior-result", prior.toString()));
            String result = cli(args, 0).out();
            outputs.put(directory + name + "-report" + suffix + ".json", Files.readString(reportPath));
            prior = work.resolve(name + "-result" + suffix + ".json");
            Files.writeString(prior, result);
        }
    }

    private static ObjectNode fixture(long height, List<ObjectNode> messages) {
        ObjectNode fixture = JSON.createObjectNode();
        fixture.put("height", height);
        fixture.put("timestamp", 1_000 * height);
        fixture.put("stateRootHex", "00".repeat(32));
        fixture.put("pendingEffects", 0);
        fixture.set("state", JSON.createArrayNode());
        fixture.set("messages", JSON.createArrayNode().addAll(messages));
        return fixture;
    }

    private static ObjectNode message(int id, int sender, long sequence, String topic, byte[] body) {
        ObjectNode message = JSON.createObjectNode();
        message.put("messageIdHex", String.format("%02x", id).repeat(32));
        message.put("senderHex", String.format("%02x", sender).repeat(32));
        message.put("senderSeq", sequence);
        message.put("expiresAt", Long.MAX_VALUE);
        message.put("topic", topic);
        message.put("bodyHex", java.util.HexFormat.of().formatHex(body));
        message.put("authProofHex", "00");
        return message;
    }

    private static String report(Path work, String command, Path document, Path plugins, Path context,
                                 List<String> extra, int exit) throws IOException {
        Path report = work.resolve("report-" + command + "-" + System.nanoTime() + ".json");
        List<String> args = new ArrayList<>(List.of("bindings", command, document.toString(), "--plugins-directory",
                plugins.toString(), "--context", context.toString()));
        args.addAll(extra);
        args.addAll(List.of("--report", report.toString()));
        cli(args, exit);
        return Files.readString(report);
    }

    /** Runs the packaged launcher, so recorded identities are those of the real installed tool JARs. */
    private static Output cli(List<String> args, int expected) throws IOException {
        String launcher = System.getProperty("yano.test.appchain-cli");
        if (launcher == null) throw new IllegalStateException("yano.test.appchain-cli is required");
        List<String> command = new ArrayList<>();
        command.add(launcher);
        command.addAll(args);
        Path out = Files.createTempFile("studio-fixture", ".out");
        Path err = Files.createTempFile("studio-fixture", ".err");
        try {
            Process process = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile())
                    .start();
            int exit = process.waitFor();
            if (exit != expected) {
                throw new IllegalStateException(args + " exited " + exit + ": " + Files.readString(err));
            }
            return new Output(Files.readString(out), Files.readString(err));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", interrupted);
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    static List<String> tutorialBlocks(Path repository) throws IOException {
        Matcher matcher = Pattern.compile("```(?:yaml|json)\\n(.*?)```", Pattern.DOTALL)
                .matcher(Files.readString(repository.resolve("docs/appchain/bindings/01-first-workflow.md")));
        List<String> blocks = new ArrayList<>();
        while (matcher.find()) blocks.add(matcher.group(1));
        return blocks;
    }

    private static JsonNode normalize(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode copy = JSON.createObjectNode();
            object.properties().forEach(entry -> copy.set(entry.getKey(), VOLATILE.contains(entry.getKey())
                    && !entry.getValue().isNull() ? TextNode.valueOf("<build-specific>")
                    : normalize(entry.getValue())));
            return copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = JSON.createArrayNode();
            array.forEach(item -> copy.add(normalize(item)));
            return copy;
        }
        return node;
    }

    private record Output(String out, String err) { }
}
