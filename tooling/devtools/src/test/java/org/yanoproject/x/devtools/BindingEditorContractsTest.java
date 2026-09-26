package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1.Component;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-031.2 M0 tooling contracts: inputs, diagnostics, report encoding and writing. */
class BindingEditorContractsTest {
    private static final BindingDocumentCompiler.DescriptorCatalog CATALOG =
            new BindingDocumentCompiler.DescriptorCatalog() {
                @Override public ConfigurationDescriptor configuration(String machineId) {
                    return new ConfigurationDescriptor(List.of(new ConfigurationDescriptor.Setting("format",
                            TransitionScalars.Type.TEXT, "raw")));
                }
                @Override public Map<String, Type> eventFields(Component component, String eventId) {
                    return Map.of("amount", Type.INTEGER, "key", Type.BYTES, "label", Type.TEXT);
                }
            };

    @Test
    void inputDigestsCoverExactBytesAndRecordStatesInReadOrder(@TempDir Path directory) throws IOException {
        Path document = directory.resolve("doc.yml");
        byte[] bytes = "\uFEFFa: 1\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(document, bytes);
        Path oversized = directory.resolve("big.json");
        Files.write(oversized, new byte[11]);
        BindingInputs inputs = new BindingInputs();
        assertThat(inputs.read("document", document, 64)).isEqualTo("\uFEFFa: 1\r\n");
        assertThatThrownBy(() -> inputs.read("context", oversized, 10))
                .hasMessage("binding input file exceeds size limit");
        var snapshot = inputs.snapshot(List.of("document", "context", "fixture"));
        assertThat(snapshot).extracting(BindingInputs.Observed::role).containsExactly("document", "context", "fixture");
        assertThat(snapshot.get(0).sha256()).isEqualTo(BindingInputs.sha256(bytes));
        assertThat(snapshot.get(0).bytes()).isEqualTo(bytes.length);
        assertThat(snapshot.get(1).state()).isEqualTo("oversized");
        assertThat(snapshot.get(1).sha256()).isNull();
        assertThat(snapshot.get(2).state()).isEqualTo("not-read");
        Path invalid = directory.resolve("invalid.yml");
        Files.write(invalid, new byte[] {'a', (byte) 0xff});
        assertThatThrownBy(() -> new BindingInputs().read("document", invalid, 64)).isInstanceOf(IOException.class);
    }

    @Test
    void compilerFailuresCarryStableCodesExactSegmentsAndUnchangedMessages() {
        var cases = Map.of(
                "components: [{id: s, machine: m}]\nbindings: []\nextra: 1\n",
                new Object[] {"UNKNOWN_FIELD", "$.extra: unknown field", List.of("extra")},
                "components: [{id: s, machine: m, config: {a.b: 1}}]\nbindings: []\n",
                new Object[] {"COMPONENT_CONFIGURATION_INVALID", null, List.of("components", 0, "config")},
                "composite:\n  components: [{id: s, machine: m}]\n  bindings:\n    - id: b\n      from: {component: s, "
                        + "event: e}\n      to: {component: s, command: c, map: {x.y: {field: missing}}}\n",
                new Object[] {"UNKNOWN_EVENT_FIELD",
                        "$.composite.bindings[0].to.map.x.y: unknown event field: missing",
                        List.of("composite", "bindings", 0, "to", "map", "x.y")},
                "components: [{id: s, machine: m}]\nbindings:\n  - id: b\n    from: {component: s, event: e}\n"
                        + "    when: [{field: amount, exists: false}]\n"
                        + "    to: {component: s, command: c, rawBody: key}\n",
                new Object[] {"MUST_BE_TRUE", "$.bindings[0].when[0].exists: must be true; use the opposite operator",
                        List.of("bindings", 0, "when", 0, "exists")});
        cases.forEach((yaml, expected) -> {
            assertThatThrownBy(() -> BindingDocumentCompiler.compile(yaml, CATALOG))
                    .isInstanceOfSatisfying(BindingAuthoringException.class, error -> {
                        var diagnostic = error.diagnostic();
                        assertThat(diagnostic.code()).isEqualTo(expected[0]);
                        if (expected[1] != null) assertThat(error.getMessage()).isEqualTo(expected[1]);
                        assertThat(diagnostic.location().pathSegments()).isEqualTo(expected[2]);
                        assertThat(diagnostic.message()).isEqualTo(BindingDiagnostic.CODES.get(expected[0]));
                    });
        });
        assertThatThrownBy(() -> BindingDocumentCompiler.compile(
                "components: [{id: s, machine: m, config: {a.b: 1}}]\nbindings: []\n", CATALOG))
                .isInstanceOfSatisfying(BindingAuthoringException.class, error ->
                        assertThat(error.diagnostic().location().componentIndex()).isZero());
    }

    @Test
    void clauseAndMappingLocationsAreStructuredAndCelPositionsUseUtf16Columns() {
        String yaml = """
                components: [{id: s, machine: m}]
                bindings:
                  - id: b
                    from: {component: s, event: e}
                    when: [{field: amount, gt: 0}, {expr: '"😀" == event.nope'}]
                    to: {component: s, command: c, rawBody: key}
                """;
        assertThatThrownBy(() -> BindingDocumentCompiler.compile(yaml, CATALOG))
                .isInstanceOfSatisfying(BindingAuthoringException.class, error -> {
                    assertThat(error.getMessage()).startsWith("$.bindings[0].when[1]: invalid binding expression:");
                    var location = error.diagnostic().location();
                    assertThat(error.code()).isEqualTo("EXPRESSION_INVALID");
                    assertThat(location.bindingIndex()).isZero();
                    assertThat(location.clauseIndex()).isEqualTo(1);
                    assertThat(location.pathSegments()).isEqualTo(List.of("bindings", 0, "when", 1, "expr"));
                    assertThat(location.expressionLine()).isEqualTo(1);
                    // CEL counts code points; '"', the two-unit emoji, '"', ' ', '=', '=', ' ' puts "event" at
                    // UTF-16 column 9 (it would be 8 in code points).
                    assertThat(location.expressionColumn()).isEqualTo(9);
                    assertThat(error.diagnostic().detailMayContainInput()).isTrue();
                });
    }

    @Test
    void celPositionsWithoutAstralCharactersAreUnchanged() {
        String yaml = """
                components: [{id: s, machine: m}]
                bindings:
                  - id: b
                    from: {component: s, event: e}
                    when: [{expr: '1 == event.nope'}]
                    to: {component: s, command: c, rawBody: key}
                """;
        assertThatThrownBy(() -> BindingDocumentCompiler.compile(yaml, CATALOG))
                .isInstanceOfSatisfying(BindingAuthoringException.class, error ->
                        assertThat(error.diagnostic().location().expressionColumn()).isEqualTo(6));
    }

    @Test
    void yamlFailurePositionsAreOneBasedUtf16AndDuplicateKeysHaveTheirOwnCode() {
        assertThatThrownBy(() -> BindingDocumentCompiler.parseDocument("a: \"😀\"\nb: 1\nb: 2\n"))
                .isInstanceOfSatisfying(BindingAuthoringException.class, error -> {
                    assertThat(error.code()).isEqualTo("YAML_DUPLICATE_KEY");
                    assertThat(error.line()).isEqualTo(3);
                });
        assertThatThrownBy(() -> BindingDocumentCompiler.parseDocument("x: \"😀\" &a\n"))
                .isInstanceOfSatisfying(BindingAuthoringException.class, error ->
                        assertThat(error.code()).isIn("YAML_SYNTAX", "YAML_FORBIDDEN_CONSTRUCT"));
        assertThatThrownBy(() -> BindingDocumentCompiler.parseDocument("x: &a 1\ny: *a\n"))
                .isInstanceOfSatisfying(BindingAuthoringException.class, error -> {
                    assertThat(error.code()).isEqualTo("YAML_FORBIDDEN_CONSTRUCT");
                    assertThat(error.getMessage())
                            .isEqualTo("$: YAML aliases, anchors, and explicit tags are not supported");
                });
        assertThatThrownBy(() -> BindingDocumentCompiler.parseDocument("a: 1\n---\nb: 2\n"))
                .isInstanceOfSatisfying(BindingAuthoringException.class, error ->
                        assertThat(error.code()).isEqualTo("YAML_MULTIPLE_DOCUMENTS"));
        assertThat(BindingExpressionCompiler.utf16Column("😀x\nab", 1, 1)).isEqualTo(3);
        assertThat(BindingExpressionCompiler.utf16Column("😀x\nab", 2, 1)).isEqualTo(2);
        assertThat(BindingExpressionCompiler.utf16Column("ab", 3, 0)).isNull();
    }

    @Test
    void diagnosticsUseControlledMessagesAndRedactPaths() {
        var diagnostic = BindingDiagnostic.error("PLUGIN_CATALOG_INVALID",
                "bundle /Users/example/private/plugins/a.jar and C:\\Users\\x\\b.jar failed\nsecond line", true,
                BindingDiagnostic.Location.input("plugins"));
        assertThat(diagnostic.detail()).isEqualTo("bundle <path> and <path> failed");
        assertThat(diagnostic.message()).isEqualTo(BindingDiagnostic.CODES.get("PLUGIN_CATALOG_INVALID"));
        assertThat(BindingDiagnostic.sanitize("records/kv-registry.entry-put.v1 kept")).contains("records/kv-registry");
        assertThat(BindingDiagnostic.sanitize("x".repeat(600))).hasSize(512);
        assertThatThrownBy(() -> new BindingDiagnostic("NOT_A_CODE", "error", "x", null, false,
                BindingDiagnostic.Location.input("document"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BindingDiagnostic("UNCLASSIFIED", "error", "free text", null, false,
                BindingDiagnostic.Location.input("document"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pathsUnderKnownPrefixesAreRedactedEvenWithSpacesAndUncPathsByShape() {
        String home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", "/Users/Jane Doe");
            assertThat(BindingDiagnostic.sanitize(
                    "cannot open /Users/Jane Doe/Library/Application Support/yano/x.jar: denied"))
                    .isEqualTo("cannot open <path>: denied").doesNotContain("Support", "Doe");
        } finally {
            System.setProperty("user.home", home);
        }
        assertThat(BindingDiagnostic.sanitize("share \\\\server\\share\\x.jar failed"))
                .isEqualTo("share <path> failed");
    }

    @Test
    void locationsAreDerivedByPositionInTheDocumentShape() {
        var mappingNamedConfig = BindingDiagnostic.Location.fromSegments("document", BindingDocumentPath.ROOT
                .field("composite").field("bindings").index(0).field("to").field("map").field("config")
                .field("args").index(1), null, null);
        assertThat(mappingNamedConfig.part()).isEqualTo("mapping");
        assertThat(mappingNamedConfig.targetField()).isEqualTo("config");
        assertThat(mappingNamedConfig.setting()).isNull();
        assertThat(mappingNamedConfig.argumentPath()).containsExactly(1);
        var setting = BindingDiagnostic.Location.fromSegments("document", BindingDocumentPath.ROOT
                .field("components").index(1).field("config").field("threshold"), null, null);
        assertThat(setting.part()).isEqualTo("component-config");
        assertThat(setting.componentIndex()).isEqualTo(1);
        assertThat(setting.setting()).isEqualTo("threshold");
        var lookup = BindingDiagnostic.Location.fromSegments("document", BindingDocumentPath.ROOT
                .field("bindings").index(3).field("when").index(2).field("lookup").field("key").field("args")
                .index(0), null, null);
        assertThat(lookup.part()).isEqualTo("lookup-key");
        assertThat(lookup.bindingIndex()).isEqualTo(3);
        assertThat(lookup.clauseIndex()).isEqualTo(2);
        assertThat(lookup.argumentPath()).containsExactly(0);
        var command = BindingDiagnostic.Location.fromSegments("document", BindingDocumentPath.ROOT
                .field("bindings").index(0).field("to").field("command"), null, null);
        assertThat(command.part()).isEqualTo("target-command");
        var effect = BindingDiagnostic.Location.fromSegments("document", BindingDocumentPath.ROOT
                .field("bindings").index(0).field("to").field("effect").field("gate"), null, null);
        assertThat(effect.part()).isEqualTo("effect");
        assertThat(BindingDiagnostic.PARTS).contains("mapping", "target-field", "target-command", "raw-body");
        assertThat(BindingDiagnostic.Location.part("not-a-part")).isNull();
    }

    @Test
    void positionsAreOmittedWhenSourcesUseLineBreaksOtherThanLineFeed() {
        assertThatThrownBy(() -> BindingDocumentCompiler.parseDocument("components: []\r\nbindings: [\r\n"))
                .isInstanceOfSatisfying(BindingAuthoringException.class, error -> {
                    assertThat(error.code()).isEqualTo("YAML_SYNTAX");
                    assertThat(error.line()).isNull();
                    assertThat(error.column()).isNull();
                });
        assertThat(BindingExpressionCompiler.utf16Column("a\u2028b", 1, 1)).isNull();
    }

    @Test
    void studioDiagnosticTableIsGeneratedFromTheJavaCatalog() throws IOException {
        assertThat(Files.readString(Path.of(StudioDiagnosticCodes.STUDIO_MODULE)))
                .as("regenerate with ./gradlew :tooling:devtools:generateStudioDiagnosticCodes")
                .isEqualTo(StudioDiagnosticCodes.module());
    }

    @Test
    void receiptViewsMatchGoldenBytesWithDecimalHeights() throws IOException {
        Properties vectors = new Properties();
        try (var input = BindingReceiptV1.class.getResourceAsStream(
                "/cddl/declarative-bindings-v1-golden-vectors.properties")) {
            vectors.load(input);
        }
        var rejected = BindingReport.receiptView(HexFormat.of().parseHex(vectors.getProperty("receipt.rejected")));
        assertThat(rejected.get("status")).isEqualTo("REJECTED");
        assertThat(rejected.get("height")).isEqualTo("1");
        assertThat(rejected.get("failedStepOrdinal")).isEqualTo(1);
        var extreme = new BindingReceiptV1(new byte[32], Long.MAX_VALUE, true, null, "", List.of()).encode();
        assertThat(BindingReport.receiptView(extreme).get("height")).isEqualTo("9223372036854775807");
        byte[] trailing = java.util.Arrays.copyOf(extreme, extreme.length + 1);
        assertThatThrownBy(() -> BindingReport.receiptView(trailing)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postStateDigestIsOrderIndependentAndDomainSeparated() {
        var a = new BindingDryRun.Entry("01", "aa");
        var b = new BindingDryRun.Entry("0102", "");
        String digest = BindingReport.postStateDigest(List.of(a, b));
        assertThat(BindingReport.postStateDigest(List.of(b, a))).isEqualTo(digest);
        assertThat(BindingReport.postStateDigest(List.of(new BindingDryRun.Entry("01", "aa01")))).isNotEqualTo(
                BindingReport.postStateDigest(List.of(new BindingDryRun.Entry("01aa", "01"))));
        assertThat(BindingReport.postStateDigest(List.of())).hasSize(64);
    }

    @Test
    void reportsAreWrittenAtomicallyWithOwnerOnlyPermissionsAndNeverOverInputs(@TempDir Path directory)
            throws IOException {
        Path input = directory.resolve("bindings.yml");
        Files.writeString(input, "x");
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        assertThatThrownBy(() -> BindingReport.requireSafeTarget(input, List.of(input), plugins))
                .hasMessageContaining("input");
        assertThatThrownBy(() -> BindingReport.requireSafeTarget(directory.resolve("./bindings.yml"),
                List.of(input), plugins)).hasMessageContaining("input");
        assertThatThrownBy(() -> BindingReport.requireSafeTarget(plugins.resolve("r.json"), List.of(input), plugins))
                .hasMessageContaining("plugin");
        Path report = directory.resolve("report.json");
        BindingReport.requireSafeTarget(report, java.util.Arrays.asList(input, null), plugins);
        Path linkTarget = directory.resolve("elsewhere.txt");
        Files.writeString(linkTarget, "keep");
        Files.createSymbolicLink(report, linkTarget);
        BindingReport.write(report, "{}\n".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.isSymbolicLink(report)).isFalse();
        assertThat(Files.readString(report)).isEqualTo("{}\n");
        assertThat(Files.readString(linkTarget)).isEqualTo("keep");
        if (report.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(report)).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
        try (var listing = Files.list(directory)) {
            assertThat(listing.map(path -> path.getFileName().toString())).noneMatch(name -> name.endsWith(".tmp"));
        }
        assertThatThrownBy(() -> BindingReport.encode(Map.of("x", "y".repeat(BindingReport.MAX_BYTES))))
                .isInstanceOf(IOException.class);
    }

    @Test
    void toolIdentityRecordsBuildVersionsAndAuthoringEnvironment() {
        var identity = BindingToolIdentity.toolIdentity();
        assertThat(identity).containsKeys("producer", "host", "authoringEnvironment");
        assertThat(identity.get("authoringEnvironment")).isEqualTo("yano-x-binding-authoring-environment-v1");
        @SuppressWarnings("unchecked")
        var host = (Map<String, Object>) identity.get("host");
        assertThat((String) host.get("version")).isNotBlank().doesNotContain("${");
    }
}
