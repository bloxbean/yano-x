package org.yanoproject.x.execution.conformance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.config.PluginsOptions;
import org.yanoproject.runtime.plugins.PluginLoaderHandle;
import org.yanoproject.runtime.plugins.PluginRuntimeEnvironment;
import org.yanoproject.x.execution.ExecutionStateMachineProvider;
import org.yanoproject.x.execution.abi.v1.ExecutionDescriptor;
import org.yanoproject.x.execution.abi.v1.Hashes;
import org.yanoproject.x.execution.tck.RuntimeTck;
import org.yanoproject.x.execution.tck.TckProgram;
import org.yanoproject.x.execution.tck.TckResult;
import org.yanoproject.x.execution.tck.reference.ReferenceRuntimeFactory;
import org.yanoproject.x.execution.tck.reference.ReferenceTckPrograms;
import org.yanoproject.x.framework.api.execution.ExecutionRuntime;
import org.yanoproject.x.framework.api.execution.ExecutionRuntimeFactory;
import org.yanoproject.x.framework.api.execution.ExecutionRuntimeResolver;
import org.yanoproject.x.framework.api.execution.RuntimeOptions;
import org.yanoproject.x.framework.runtime.appchain.StateMachineConformance;
import org.yanoproject.x.framework.runtime.plugins.CatalogExecutionRuntimeResolver;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4 exit gate of ADR-058: a runtime loaded through the host's real directory catalog, as an
 * {@code execution-runtime} contribution of a manifested bundle, passes the TCK and runs the execution
 * state machine with the same roots as the runtime used directly.
 */
class CatalogExecutionRuntimeTest {

    private static final String RUNTIME = ReferenceRuntimeFactory.RUNTIME_ID;

    private static PluginRuntimeEnvironment open(Path plugins, Set<String> allowList) throws IOException {
        Path bundle = Path.of(System.getProperty("yano.execution.fixture.bundle"));
        Files.copy(bundle, plugins.resolve(bundle.getFileName()));
        PluginsOptions options = new PluginsOptions(true, false, allowList, Set.of(), Map.of());
        return PluginRuntimeEnvironment.open(options,
                PluginLoaderHandle.directory(plugins, isolatedParent(CatalogExecutionRuntimeTest.class.getClassLoader())));
    }

    @Test
    void catalogLoadedRuntimePassesTheTck(@TempDir Path plugins) throws IOException {
        try (PluginRuntimeEnvironment environment = open(plugins, Set.of(ConformanceReferenceRuntimeFactory.BUNDLE_ID))) {
            ExecutionRuntimeFactory factory = new CatalogExecutionRuntimeResolver(environment.providers()).resolve(RUNTIME);

            // The host hands out its facade, never the bundle's own object, down to every instance.
            assertThat(factory.getClass().getName()).startsWith("org.yanoproject.x.framework.host.AppLedgerKindBindings$");

            List<TckResult> results = RuntimeTck.builder(factory, new ReferenceTckPrograms()).build().run();
            assertThat(results).filteredOn(r -> r.status() == TckResult.Status.FAIL).isEmpty();
            assertThat(results).filteredOn(r -> r.status() == TckResult.Status.WARN)
                    .extracting(TckResult::id).containsExactly("memory.bounded");
            assertThat(results).hasSizeGreaterThanOrEqualTo(25);
        }
    }

    @Test
    void runtimeOfABundleThatIsNotAllowListedIsRefused(@TempDir Path plugins) throws IOException {
        try (PluginRuntimeEnvironment environment = open(plugins, Set.of())) {
            assertThat(environment.providers().names(ExecutionRuntimeFactory.class)).contains(RUNTIME);
            ExecutionRuntimeResolver resolver = new CatalogExecutionRuntimeResolver(environment.providers());
            assertThatThrownBy(() -> resolver.resolve(RUNTIME))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("explicitly allow-listed");
            assertThatThrownBy(() -> resolver.resolve("graaljs"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not installed");
        }
    }

    @Test
    void executionStateMachineOnTheCatalogRuntimeGivesTheSameRoots(@TempDir Path plugins) throws IOException {
        ReferenceRuntimeFactory direct = ReferenceTckPrograms.factory();
        Map<String, String> settings = counterSettings(direct);
        StateMachineConformance.Result expected = harness(new ExecutionStateMachineProvider(id -> direct), settings).run();
        assertThat(expected.deterministic()).as(expected::describeDivergence).isTrue();

        try (PluginRuntimeEnvironment environment = open(plugins, Set.of(ConformanceReferenceRuntimeFactory.BUNDLE_ID))) {
            ExecutionRuntimeResolver catalog = new CatalogExecutionRuntimeResolver(environment.providers());
            StateMachineConformance.Result actual = harness(new ExecutionStateMachineProvider(catalog), settings).run();
            assertThat(actual.deterministic()).as(actual::describeDivergence).isTrue();
            assertThat(actual.outcomesPerRun().getFirst()).isEqualTo(expected.outcomesPerRun().getFirst());
        }
    }

    private static Map<String, String> counterSettings(ReferenceRuntimeFactory factory) {
        byte[] program = TckProgram.COUNTER.name().getBytes(StandardCharsets.UTF_8);
        byte[] fingerprint;
        try (ExecutionRuntime runtime = factory.open(RuntimeOptions.none())) {
            fingerprint = ExecutionDescriptor.fingerprint(runtime.descriptor());
        }
        ExecutionDescriptor descriptor = new ExecutionDescriptor(RUNTIME, fingerprint, ReferenceRuntimeFactory.MEDIA_TYPE,
                Hashes.blake2b256(program), 1,
                new ExecutionDescriptor.Budgets(100_000, 50_000, 10_000, 10_000, 10_000, 10_000), 0, new byte[0], true);
        return Map.of(
                "machines.execution.descriptor-cbor-hex", HexFormat.of().formatHex(descriptor.encode()),
                "machines.execution.program-hex", HexFormat.of().formatHex(program),
                "effects.enabled", "false");
    }

    private static StateMachineConformance.Builder harness(ExecutionStateMachineProvider provider,
                                                           Map<String, String> settings) {
        return StateMachineConformance.builder(provider)
                .settings(settings)
                .blocks(20)
                .messagesPerBlock(6)
                .seed(7)
                .runs(2)
                .bodyGenerator((height, index, random) -> ("key-" + random.nextInt(4)).getBytes(StandardCharsets.UTF_8))
                .stateProbe("key-0", "c/key-0".getBytes(StandardCharsets.UTF_8));
    }


    /** Hides the fixture and the reference runtime from the plugin's parent, so the bundle's copies load. */
    private static ClassLoader isolatedParent(ClassLoader delegate) {
        return new ClassLoader(delegate) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("org.yanoproject.x.execution.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }

            @Override
            public URL getResource(String name) {
                return hidden(name) ? null : super.getResource(name);
            }

            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return hidden(name) ? Collections.emptyEnumeration() : super.getResources(name);
            }

            private boolean hidden(String name) {
                return name.startsWith("META-INF/yano/plugins/") || name.startsWith("META-INF/services/org.yanoproject.x.")
                        || name.startsWith("org/yanoproject/x/execution/");
            }
        };
    }
}
