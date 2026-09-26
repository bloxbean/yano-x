package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.appchain.AppChainConsensusProfile;
import org.yanoproject.api.appchain.AppChainMembershipEpoch;
import org.yanoproject.api.appchain.effects.EffectOutcomeCommitment;
import org.yanoproject.api.appchain.effects.FinalityGate;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.stdlib.contracts.KvRegistryContract;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-031.2 M0: editor report envelopes and authoring catalogs produced through the real manifested catalog.
 * Every command is also run without {@code --report} to prove historical output is unchanged.
 */
class BindingEditorToolingIT {
    @TempDir Path temporary;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOCUMENT = """
            composite:
              components:
                - {id: records, machine: kv-registry}
                - {id: audit, machine: doc-trail}
              bindings:
                - id: audit-record
                  from: {component: records, event: kv-registry.entry-put.v1}
                  when: [{expr: 'event.valueLength < 100'}]
                  to:
                    component: audit
                    command: append
                    map:
                      entityId: {fn: hex, args: [{field: key}]}
                      entryHash: {field: valueHash}
                      reference: {literal: published}
            """;

    @Test
    void reportsLeaveHistoricalOutputUnchangedAndBindExactInputs() throws Exception {
        Inputs inputs = inputs(DOCUMENT);
        for (String command : List.of("compile", "validate", "dry-run")) {
            List<String> extra = command.equals("dry-run") ? List.of("--fixture", inputs.fixture().toString())
                    : List.of();
            Output plain = run(command, inputs, extra);
            Path reportPath = temporary.resolve(command + "-report.json");
            List<String> withReport = new ArrayList<>(extra);
            withReport.addAll(List.of("--report", reportPath.toString()));
            Output reported = run(command, inputs, withReport);
            assertThat(reported.exit()).as(reported.err()).isZero().isEqualTo(plain.exit());
            assertThat(reported.out()).isEqualTo(plain.out());
            assertThat(reported.err()).isEqualTo(plain.err());
            JsonNode report = JSON.readTree(reportPath.toFile());
            assertThat(report.path("schema").textValue()).isEqualTo("yano-x-binding-report-v1");
            assertThat(report.path("operation").textValue()).isEqualTo(command);
            assertThat(report.path("operationOutcome").textValue()).isEqualTo("completed");
            assertThat(report.path("inputs").get(0).path("sha256").textValue())
                    .isEqualTo(BindingInputs.sha256(Files.readAllBytes(inputs.document())));
            assertThat(report.path("inputs").get(1).path("sha256").textValue())
                    .isEqualTo(BindingInputs.sha256(Files.readAllBytes(inputs.context())));
            assertThat(report.path("result").path("profile").path("digestHex").textValue()).matches("[0-9a-f]{64}");
            assertThat(report.path("result").path("profile").path("executionVersion").textValue()).isEqualTo("1.1.0");
            assertThat(report.path("catalog").path("fingerprint").textValue()).startsWith("sha256:");
            assertThat(report.path("receiptCodes").size()).isGreaterThan(30);
            if (command.equals("compile")) {
                assertThat(report.path("result").path("irHex").textValue()).isEqualTo(plain.out().strip());
            }
            if (command.equals("dry-run")) {
                JsonNode rehearsal = report.path("result").path("rehearsal");
                JsonNode message = rehearsal.path("messages").get(0);
                assertThat(message.path("disposition").textValue()).isEqualTo("executed");
                assertThat(message.path("topic").textValue()).isEqualTo("records.command.v1");
                byte[] bytes = HexFormat.of().parseHex(message.path("receiptHex").textValue());
                JsonNode view = JSON.valueToTree(BindingReport.receiptView(bytes));
                assertThat(view).isEqualTo(message.path("receipt"));
                assertThat(message.path("receipt").path("height").isTextual()).isTrue();
                assertThat(rehearsal.path("assumptions").path("height").textValue()).isEqualTo("1");
                assertThat(rehearsal.has("postState")).isFalse();
                assertThat(report.path("inputs").get(2).path("role").textValue()).isEqualTo("fixture");
            }
        }
    }

    @Test
    void replayedAndDuplicatedMessagesAreNeverPresentedAsThisBlocksExecution() throws Exception {
        Inputs inputs = inputs(DOCUMENT);
        Output first = run("dry-run", inputs, List.of("--fixture", inputs.fixture().toString(),
                "--report", temporary.resolve("first.json").toString()));
        assertThat(first.exit()).as(first.err()).isZero();
        Path prior = temporary.resolve("prior.json");
        Files.writeString(prior, first.out());
        var fixture = JSON.readValue(inputs.fixture().toFile(), BindingDryRun.Fixture.class);
        var repeated = fixture.messages().getFirst();
        var fresh = new BindingDryRun.Message("33".repeat(32), repeated.senderHex(), 2, Long.MAX_VALUE,
                repeated.topic(), repeated.bodyHex(), "00");
        JSON.writeValue(inputs.fixture().toFile(), new BindingDryRun.Fixture(2, Long.MAX_VALUE, "00".repeat(32),
                Long.MAX_VALUE, List.of(), List.of(repeated, fresh, fresh)));
        Path secondReport = temporary.resolve("second.json");
        Output second = run("dry-run", inputs, List.of("--fixture", inputs.fixture().toString(),
                "--prior-result", prior.toString(), "--report", secondReport.toString()));
        assertThat(second.exit()).as(second.err()).isZero();
        JsonNode firstRehearsal = JSON.readTree(temporary.resolve("first.json").toFile()).path("result")
                .path("rehearsal");
        JsonNode rehearsal = JSON.readTree(secondReport.toFile()).path("result").path("rehearsal");
        JsonNode messages = rehearsal.path("messages");
        assertThat(messages.get(0).path("disposition").textValue()).isEqualTo("replay-existing-receipt");
        assertThat(messages.get(0).path("receipt").path("height").textValue()).isEqualTo("1");
        assertThat(messages.get(1).path("disposition").textValue()).isEqualTo("executed");
        assertThat(messages.get(1).path("receipt").path("height").textValue()).isEqualTo("2");
        assertThat(messages.get(2).path("disposition").textValue()).isEqualTo("duplicate-in-fixture");
        assertThat(rehearsal.path("priorPostStateSha256").textValue())
                .isEqualTo(firstRehearsal.path("postStateSha256").textValue());
        assertThat(rehearsal.path("fromPriorResult").booleanValue()).isTrue();
        assertThat(rehearsal.path("assumptions").path("timestamp").textValue()).isEqualTo("9223372036854775807");
        assertThat(rehearsal.path("assumptions").path("pendingEffects").textValue()).isEqualTo("9223372036854775807");
        assertThat(JSON.readTree(secondReport.toFile()).path("inputs").get(3).path("role").textValue())
                .isEqualTo("priorResult");
    }

    @Test
    void failuresProduceStructuredDiagnosticsWithoutGuessingOrLeakingInputs() throws Exception {
        Inputs inputs = inputs(DOCUMENT.replace("{field: valueHash}", "{field: missingField}"));
        Path report = temporary.resolve("authoring.json");
        Output authoring = run("validate", inputs, List.of("--report", report.toString()));
        assertThat(authoring.exit()).isEqualTo(2);
        assertThat(authoring.out()).isEmpty();
        JsonNode diagnostic = JSON.readTree(report.toFile()).path("diagnostics").get(0);
        assertThat(diagnostic.path("code").textValue()).isEqualTo("UNKNOWN_EVENT_FIELD");
        assertThat(diagnostic.path("location").path("pathSegments").toString())
                .isEqualTo("[\"composite\",\"bindings\",0,\"to\",\"map\",\"entryHash\"]");
        assertThat(JSON.readTree(report.toFile()).path("operationOutcome").textValue()).isEqualTo("failed");
        assertThat(JSON.readTree(report.toFile()).path("result").isNull()).isTrue();

        Files.writeString(inputs.document(), DOCUMENT.replace("{fn: hex, args: [{field: key}]}", "{field: key}"));
        Output profile = run("validate", inputs, List.of("--report", report.toString()));
        assertThat(profile.exit()).isEqualTo(2);
        assertThat(profile.err()).contains("$.composite.bindings[0]", "target field 'entityId': binding type mismatch");
        JsonNode located = JSON.readTree(report.toFile()).path("diagnostics").get(0);
        assertThat(located.path("code").textValue()).isEqualTo("BINDING_TYPE_MISMATCH");
        assertThat(located.path("location").path("bindingId").textValue()).isEqualTo("audit-record");
        assertThat(located.path("location").path("targetField").textValue()).isEqualTo("entityId");
        assertThat(located.path("location").path("pathSegments").toString())
                .isEqualTo("[\"composite\",\"bindings\",0,\"to\",\"map\",\"entityId\"]");

        Files.writeString(inputs.document(), DOCUMENT);
        Files.writeString(inputs.context(), "{\"chainId\":\"secret-value\",\"chainId\":\"x\"}");
        Output json = run("validate", inputs, List.of("--report", report.toString()));
        assertThat(json.exit()).isEqualTo(2);
        JsonNode jsonDiagnostic = JSON.readTree(report.toFile()).path("diagnostics").get(0);
        assertThat(jsonDiagnostic.path("code").textValue()).isEqualTo("JSON_INVALID");
        assertThat(jsonDiagnostic.path("location").path("input").textValue()).isEqualTo("context");
        assertThat(jsonDiagnostic.has("detail")).isFalse();
        assertThat(Files.readString(report)).doesNotContain("secret-value");

        JSON.writeValue(inputs.context().toFile(), new BindingCatalogSession.ContextInput("offline-test",
                context().settings(), context().consensusProfile(), context().membership()));
        Files.writeString(inputs.context(), Files.readString(inputs.context()).replace("\"offline-test\"", "\" \""));
        Output context = run("validate", inputs, List.of("--report", report.toString()));
        assertThat(context.exit()).isEqualTo(2);
        assertThat(JSON.readTree(report.toFile()).path("diagnostics").get(0).path("code").textValue())
                .isEqualTo("CONTEXT_INVALID");

        JSON.writeValue(inputs.context().toFile(), context());
        Files.writeString(inputs.document(), "x".repeat(1_048_577));
        Output oversized = run("compile", inputs, List.of("--report", report.toString()));
        assertThat(oversized.exit()).isEqualTo(2);
        JsonNode oversizedReport = JSON.readTree(report.toFile());
        assertThat(oversizedReport.path("diagnostics").get(0).path("code").textValue()).isEqualTo("INPUT_TOO_LARGE");
        assertThat(oversizedReport.path("inputs").get(0).path("state").textValue()).isEqualTo("oversized");
        assertThat(oversizedReport.path("inputs").get(0).path("sha256").isNull()).isTrue();
        assertThat(oversizedReport.path("inputs").get(1).path("state").textValue()).isEqualTo("not-read");
    }

    @Test
    void targetCommandAndFieldFailuresPointAtTheAuthoredTarget() throws Exception {
        Inputs inputs = inputs(DOCUMENT.replace("command: append", "command: appendx"));
        Path report = temporary.resolve("target.json");
        Output command = run("validate", inputs, List.of("--report", report.toString()));
        assertThat(command.exit()).isEqualTo(2);
        JsonNode located = JSON.readTree(report.toFile()).path("diagnostics").get(0);
        assertThat(located.path("code").textValue()).isEqualTo("UNKNOWN_TARGET_COMMAND");
        assertThat(located.path("location").path("part").textValue()).isEqualTo("target-command");
        assertThat(located.path("location").path("pathSegments").toString())
                .isEqualTo("[\"composite\",\"bindings\",0,\"to\",\"command\"]");

        Files.writeString(inputs.document(), DOCUMENT.replace("          entryHash: {field: valueHash}\n", ""));
        Output missing = run("validate", inputs, List.of("--report", report.toString()));
        assertThat(missing.exit()).isEqualTo(2);
        located = JSON.readTree(report.toFile()).path("diagnostics").get(0);
        assertThat(located.path("code").textValue()).isEqualTo("MISSING_TARGET_FIELD");
        assertThat(located.path("location").path("part").textValue()).isEqualTo("target-field");
        assertThat(located.path("location").path("targetField").textValue()).isEqualTo("entryHash");
        assertThat(located.path("location").path("pathSegments").toString())
                .isEqualTo("[\"composite\",\"bindings\",0,\"to\",\"map\"]");
        assertThat(located.path("detailMayContainInput").booleanValue()).isTrue();

        // Plain invocations never run the report-only explanation and keep the exact historical text.
        Output plain = run("validate", inputs, List.of());
        assertThat(plain.exit()).isEqualTo(missing.exit());
        assertThat(plain.err()).isEqualTo(missing.err());
    }

    @Test
    void reportOnlyDataNeverChangesTheOutcome() throws Exception {
        Inputs inputs = inputs(DOCUMENT);
        Output first = run("dry-run", inputs, List.of("--fixture", inputs.fixture().toString()));
        assertThat(first.exit()).as(first.err()).isZero();
        JsonNode prior = JSON.readTree(first.out());
        ((com.fasterxml.jackson.databind.node.ObjectNode) prior.path("postState").get(0)).put("valueHex", "zz");
        Path malformed = temporary.resolve("malformed-prior.json");
        JSON.writeValue(malformed.toFile(), prior);
        var fixture = JSON.readValue(inputs.fixture().toFile(), BindingDryRun.Fixture.class);
        JSON.writeValue(inputs.fixture().toFile(), new BindingDryRun.Fixture(2, fixture.timestamp(),
                fixture.stateRootHex(), fixture.pendingEffects(), List.of(), fixture.messages()));
        List<String> args = List.of("--fixture", inputs.fixture().toString(), "--prior-result", malformed.toString());
        Output plain = run("dry-run", inputs, args);
        List<String> reported = new ArrayList<>(args);
        reported.addAll(List.of("--report", temporary.resolve("malformed.json").toString()));
        Output withReport = run("dry-run", inputs, reported);
        assertThat(plain.exit()).isEqualTo(2);
        assertThat(withReport.exit()).isEqualTo(plain.exit());
        assertThat(withReport.err()).isEqualTo(plain.err());
        assertThat(withReport.out()).isEqualTo(plain.out());
    }

    @Test
    void usageUnreadableAndUnsafeReportTargetsWriteNoReport() throws Exception {
        Inputs inputs = inputs(DOCUMENT);
        Path report = temporary.resolve("never.json");
        assertThat(run("graph", inputs, List.of("--report", report.toString())).exit()).isEqualTo(64);
        assertThat(run("validate", inputs, List.of("--report", inputs.document().toString())).exit()).isEqualTo(64);
        assertThat(Files.readString(inputs.document())).isEqualTo(DOCUMENT);
        assertThat(run("validate", inputs, List.of("--report", inputs.plugins().resolve("r.json").toString())).exit())
                .isEqualTo(64);
        Files.delete(inputs.context());
        assertThat(run("validate", inputs, List.of("--report", report.toString())).exit()).isEqualTo(74);
        assertThat(report).doesNotExist();
        assertThat(inputs.plugins().resolve("r.json")).doesNotExist();
    }

    @Test
    void catalogDescribesExactlyProbedInstancesWithTheReportIdentity() throws Exception {
        String document = """
                components:
                  - {id: first, machine: kv-registry}
                  - {id: second, machine: kv-registry, config: {}}
                  - {id: explicit, machine: kv-registry, config: {value-format: raw}}
                  - {id: trail, machine: doc-trail}
                bindings: []
                """;
        Inputs inputs = inputs(document);
        Output invalidSelector = catalog(inputs, List.of("--machine", "Acme.Ledger"));
        assertThat(invalidSelector.exit()).isEqualTo(64);
        assertThat(invalidSelector.out()).isEmpty();
        Output output = catalog(inputs, List.of(inputs.document().toString(), "--machine", "approvals",
                "--machine", "declarative-composite"));
        assertThat(output.exit()).as(output.err()).isZero();
        JsonNode catalog = JSON.readTree(output.out());
        assertThat(catalog.path("schema").textValue()).isEqualTo("yano-x-binding-authoring-catalog-v1");
        assertThat(catalog.path("context").path("sha256").textValue())
                .isEqualTo(BindingInputs.sha256(Files.readAllBytes(inputs.context())));
        assertThat(catalog.path("document").path("sha256").textValue())
                .isEqualTo(BindingInputs.sha256(Files.readAllBytes(inputs.document())));
        JsonNode instances = catalog.path("instances");
        assertThat(instances.size()).isEqualTo(5);
        assertThat(instances.get(0).path("componentIds").toString()).isEqualTo("[\"first\",\"second\"]");
        assertThat(instances.get(0).path("authoredConfiguration").size()).isZero();
        assertThat(instances.get(0).path("normalizedConfiguration").path("value-format").path("value").textValue())
                .isEqualTo("raw");
        assertThat(instances.get(1).path("componentIds").toString()).isEqualTo("[\"explicit\"]");
        assertThat(instances.get(1).path("authoredConfiguration").path("value-format").path("type").textValue())
                .isEqualTo("text");
        assertThat(instances.get(2).path("machineId").textValue()).isEqualTo("doc-trail");
        assertThat(instances.get(2).path("commands").get(0).path("layout").textValue()).isEqualTo("ARRAY");
        assertThat(instances.get(3).path("machineId").textValue()).isEqualTo("approvals");
        assertThat(instances.get(3).path("basis").textValue()).isEqualTo("explicit");
        assertThat(instances.get(4).path("status").textValue()).isEqualTo("not-composable");
        assertThat(instances.get(0).path("commands").get(0).path("opCode").textValue()).isEqualTo("0");
        Output validated = run("validate", inputs(DOCUMENT, "second"), List.of("--report",
                temporary.resolve("second-report.json").toString()));
        assertThat(validated.exit()).as(validated.err()).isZero();
        assertThat(JSON.readTree(temporary.resolve("second-report.json").toFile()).path("catalog").path("fingerprint"))
                .isEqualTo(catalog.path("catalog").path("fingerprint"));

        Output all = catalog(inputs, List.of("--all"));
        assertThat(all.exit()).as(all.err()).isZero();
        JsonNode everything = JSON.readTree(all.out());
        assertThat(everything.path("instances").size()).isEqualTo(everything.path("selectors").size());
        for (JsonNode instance : everything.path("instances")) {
            assertThat(instance.path("status").textValue()).isIn("available", "construction-failed", "not-composable",
                    "requires-configuration");
            if (!instance.path("status").textValue().equals("available")) {
                assertThat(instance.path("diagnostic").path("code").textValue()).isNotBlank();
                assertThat(instance.has("events")).isFalse();
            }
        }
    }

    @Test
    void replacedBundleBytesChangeTheRecordedCatalogIdentityOrFailClosed() throws Exception {
        Inputs inputs = inputs(DOCUMENT);
        JsonNode original = JSON.readTree(catalog(inputs, List.of("--machine", "kv-registry")).out());
        Path bundle;
        try (var files = Files.list(inputs.plugins())) {
            bundle = files.filter(path -> path.getFileName().toString().contains("stdlib")).findFirst().orElseThrow();
        }
        Path rewritten = temporary.resolve("rewritten.jar");
        try (JarFile source = new JarFile(bundle.toFile());
             JarOutputStream target = new JarOutputStream(Files.newOutputStream(rewritten))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                target.putNextEntry(new JarEntry(entry.getName()));
                try (var input = source.getInputStream(entry)) { input.transferTo(target); }
                target.closeEntry();
            }
            target.putNextEntry(new JarEntry("META-INF/yano-x-replaced-bundle-marker.txt"));
            target.write("replaced".getBytes(StandardCharsets.UTF_8));
            target.closeEntry();
        }
        Files.move(rewritten, bundle, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Output replaced = catalog(inputs, List.of("--machine", "kv-registry"));
        if (replaced.exit() == 0) {
            assertThat(JSON.readTree(replaced.out()).path("catalog").path("fingerprint"))
                    .isNotEqualTo(original.path("catalog").path("fingerprint"));
        } else {
            assertThat(replaced.exit()).isEqualTo(2);
        }
    }

    @Test
    void int64ExtremesAreDecimalTextThroughout() throws Exception {
        Inputs inputs = inputs(DOCUMENT);
        var fixture = JSON.readValue(inputs.fixture().toFile(), BindingDryRun.Fixture.class);
        JSON.writeValue(inputs.fixture().toFile(), new BindingDryRun.Fixture(1, Long.MAX_VALUE, "00".repeat(32),
                Long.MAX_VALUE, List.of(), fixture.messages()));
        Path report = temporary.resolve("extremes.json");
        Output output = run("dry-run", inputs, List.of("--fixture", inputs.fixture().toString(), "--report",
                report.toString()));
        assertThat(output.exit()).as(output.err()).isZero();
        String text = Files.readString(report);
        assertThat(text).contains("\"timestamp\" : \"9223372036854775807\"")
                .contains("\"pendingEffects\" : \"9223372036854775807\"");
        assertThat(text).doesNotContainPattern(": -?[0-9]{17,}");
        var receipt = BindingReceiptV1.decode(HexFormat.of().parseHex(JSON.readTree(report.toFile()).path("result")
                .path("rehearsal").path("messages").get(0).path("receiptHex").textValue()));
        assertThat(receipt.accepted()).isTrue();
    }

    private Inputs inputs(String document) throws Exception {
        return inputs(document, "first");
    }

    private Inputs inputs(String document, String name) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name));
        Path plugins = Files.createDirectory(root.resolve("plugins"));
        for (String bundle : System.getProperty("yano.test.binding-bundles").split(java.io.File.pathSeparator)) {
            Path source = Path.of(bundle);
            Files.copy(source, plugins.resolve(source.getFileName()));
        }
        Path authored = root.resolve("bindings.yml");
        Files.writeString(authored, document);
        Path context = root.resolve("context.json");
        JSON.writeValue(context.toFile(), context());
        Path fixture = root.resolve("fixture.json");
        byte[] command = KvRegistryContract.put(new byte[]{1, 2}, new byte[]{3, 4});
        JSON.writeValue(fixture.toFile(), new BindingDryRun.Fixture(1, 123, "00".repeat(32), 0, List.of(),
                List.of(new BindingDryRun.Message("11".repeat(32), "22".repeat(32), 1, Long.MAX_VALUE,
                        "records.command.v1", HexFormat.of().formatHex(command), "00"))));
        return new Inputs(authored, context, fixture, plugins);
    }

    private static BindingCatalogSession.ContextInput context() {
        var profile = new AppChainConsensusProfile(2, 65536, 100, 1_048_576, 0, 0, false, false,
                0, 0, 0, 0, FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT, true, List.of());
        var identity = StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF, new byte[32]);
        return new BindingCatalogSession.ContextInput("offline-test", identity.settings(), profile,
                new AppChainMembershipEpoch(0, List.of("22".repeat(32)), 1));
    }

    private static Output run(String command, Inputs inputs, List<String> extra) {
        List<String> args = new ArrayList<>(List.of("bindings", command, inputs.document().toString(),
                "--plugins-directory", inputs.plugins().toString(), "--context", inputs.context().toString()));
        args.addAll(extra);
        return execute(args);
    }

    private static Output catalog(Inputs inputs, List<String> extra) {
        List<String> args = new ArrayList<>(List.of("bindings", "catalog", "--plugins-directory",
                inputs.plugins().toString(), "--context", inputs.context().toString()));
        args.addAll(extra);
        return execute(args);
    }

    private static Output execute(List<String> args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = new AppChainDevtoolsCli().run(args.toArray(String[]::new), new PrintWriter(out),
                new PrintWriter(err));
        return new Output(exit, out.toString(), err.toString());
    }

    private record Inputs(Path document, Path context, Path fixture, Path plugins) { }
    private record Output(int exit, String out, String err) { }
}
