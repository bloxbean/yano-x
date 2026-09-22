package org.yanoproject.x.devtools;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.x.composite.contracts.BindingIrV1;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BindingCliTest {
    @TempDir Path temporary;

    @Test
    void routesThroughMainCliAndPrintsHelpfulUsage() {
        Output output = run("bindings", "--help");
        assertThat(output.exit()).isZero();
        assertThat(output.out()).contains("compile|validate|graph|dry-run", "not verify signatures");
        assertThat(run("bindings", "compile", "file.yml").exit()).isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
        assertThat(run("bindings", "unknown").exit()).isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
        assertThat(run("bindings", "graph", "file.hex", "--ir", "--ir").exit())
                .isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
        assertThat(run("bindings", "graph", "file.hex", "--ir", "--fixture", "fixture.json").exit())
                .isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
    }

    @Test
    void graphFromCommittedIrNeedsNoPluginActivationOrContext() throws Exception {
        BindingIrV1 ir = new BindingIrV1(List.of(new BindingIrV1.Component("records", "custom-machine",
                "records.command.v1", Map.of(), 0)), List.of(), BindingIrV1.Limits.DEFAULT);
        Path document = temporary.resolve("bindings.hex");
        Files.writeString(document, HexFormat.of().formatHex(ir.encode()));
        Output output = run("bindings", "graph", document.toString(), "--ir");
        assertThat(output.exit()).isZero();
        assertThat(output.out()).isEqualTo(BindingGraph.dot(ir));
        Files.writeString(document, "0001");
        assertThat(run("bindings", "graph", document.toString(), "--ir").exit())
                .isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
    }

    @Test
    void declarativeInitRequiresBothExplicitInputsAndRejectsThemForOtherRecipes() {
        List<String> base = List.of("init", "--non-interactive", "--network", "devnet", "--members", "3",
                "--output", temporary.resolve("project").toString(), "--recipe");
        for (List<String> suffix : List.of(List.of("declarative-composite"),
                List.of("declarative-composite", "--bindings", "missing.yaml"),
                List.of("declarative-composite", "--plugins-directory", "missing"),
                List.of("audit-log", "--bindings", "missing.yaml", "--plugins-directory", "missing"))) {
            var args = new java.util.ArrayList<>(base);
            args.addAll(suffix);
            Output result = run(args.toArray(String[]::new));
            assertThat(result.exit()).as(result.err()).isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
            assertThat(result.err()).contains("--bindings", "--plugins-directory");
            assertThat(temporary.resolve("project")).doesNotExist();
        }
    }

    @Test
    void rejectsMalformedUtf8AndMissingFilesWithoutCreatingOutputFiles() throws Exception {
        Path bad = temporary.resolve("bad.hex");
        Files.write(bad, new byte[]{(byte) 0xc3, 0x28});
        assertThat(run("bindings", "graph", bad.toString(), "--ir").exit()).isEqualTo(AppChainDevtoolsCli.EXIT_IO);
        assertThat(run("bindings", "graph", temporary.resolve("missing").toString(), "--ir").exit())
                .isEqualTo(AppChainDevtoolsCli.EXIT_IO);
        try (var entries = Files.list(temporary)) {
            assertThat(entries.map(Path::getFileName).toList()).containsExactly(Path.of("bad.hex"));
        }
    }

    @Test
    void snapshotProjectionHidesThirdPartyHelpersAndDiscoveryButSharesHostSpi() throws Exception {
        Path bundle = temporary.resolve("fixture.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(bundle))) {
            for (String entry : List.of("META-INF/yano/plugins/fixture.json", "dev/cel/compiler/CelCompiler.class",
                    "org/yanoproject/api/appchain/AppStateMachine.class",
                    "com/bloxbean/cardano/yaci/core/protocol/appmsg/model/AppMessage.class")) {
                jar.putNextEntry(new JarEntry(entry));
                jar.write(new byte[]{0});
                jar.closeEntry();
            }
        }
        BindingPluginEnvironment.SnapshotParent parent =
                new BindingPluginEnvironment.SnapshotParent(getClass().getClassLoader());
        parent.initialize(List.of(bundle));
        assertThatThrownBy(() -> parent.loadClass("dev.cel.compiler.CelCompiler"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThat(parent.loadClass(AppStateMachine.class.getName())).isSameAs(AppStateMachine.class);
        assertThat(parent.loadClass(AppMessage.class.getName())).isSameAs(AppMessage.class);
        assertThat(parent.getResource("dev/cel/compiler/CelCompiler.class")).isNull();
        assertThat(parent.getResources("META-INF/services/org.yanoproject.api.appchain.AppStateMachineProvider")
                .hasMoreElements()).isFalse();
        assertThatThrownBy(() -> parent.initialize(List.of(bundle))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unmanifestedAndEmptyPluginDirectoriesFailClosed() throws Exception {
        Path plugins = Files.createDirectory(temporary.resolve("plugins"));
        assertThatThrownBy(() -> BindingPluginEnvironment.open(plugins))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(plugins.resolve("plain.jar")))) {
            // An ordinary library is not an executable runtime bundle.
        }
        assertThatThrownBy(() -> BindingPluginEnvironment.open(plugins))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("manifested");
    }

    private static Output run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = new AppChainDevtoolsCli().run(args, new PrintWriter(out), new PrintWriter(err));
        return new Output(exit, out.toString(), err.toString());
    }

    private record Output(int exit, String out, String err) { }
}
