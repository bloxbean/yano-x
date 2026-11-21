package org.yanoproject.x.framework.host;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.plugin.spi.PluginCallbackScope;
import org.yanoproject.x.framework.api.execution.ExecutionEngineException;
import org.yanoproject.x.framework.api.execution.ExecutionProfile;
import org.yanoproject.x.framework.api.execution.ExecutionResult;
import org.yanoproject.x.framework.api.execution.ExecutionRuntime;
import org.yanoproject.x.framework.api.execution.ExecutionRuntimeFactory;
import org.yanoproject.x.framework.api.execution.HostCalls;
import org.yanoproject.x.framework.api.execution.InstanceConfig;
import org.yanoproject.x.framework.api.execution.LoadedProgram;
import org.yanoproject.x.framework.api.execution.MemoryModel;
import org.yanoproject.x.framework.api.execution.MeteringModel;
import org.yanoproject.x.framework.api.execution.ProgramArtifact;
import org.yanoproject.x.framework.api.execution.ProgramInstance;
import org.yanoproject.x.framework.api.execution.ProgramRejectedException;
import org.yanoproject.x.framework.api.execution.Readiness;
import org.yanoproject.x.framework.api.execution.RuntimeDescriptor;
import org.yanoproject.x.framework.api.execution.RuntimeOptions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionRuntimeBindingTest {

    /** Records which callbacks ran inside the scope; the plugin side asserts it is inside one. */
    private static final class RecordingScope implements PluginCallbackScope {
        final List<String> events = new ArrayList<>();
        int depth;

        @Override public String bundleId() { return "com.example.runtime"; }
        @Override public String selector() { return "fake"; }
        @Override public ClassLoader loader() { return getClass().getClassLoader(); }

        @Override
        public <T, X extends Throwable> T call(Callback<T, X> callback) throws X {
            depth++;
            try {
                return callback.get();
            } finally {
                depth--;
            }
        }

        @Override
        public <X extends Throwable> void run(Action<X> action) throws X {
            call(() -> {
                action.run();
                return null;
            });
        }

        @Override
        public <X extends Throwable> void runCleanup(Action<X> action) throws X {
            events.add("cleanup");
            run(action);
        }

        @Override
        public <T> T activation(String action, Supplier<T> callback) {
            events.add("activation:" + action);
            return callback.get();
        }

        @Override
        public <T> T product(T value, UnaryOperator<T> facade) {
            events.add("product:" + value.getClass().getSimpleName());
            return facade.apply(value);
        }

        @Override
        public <T> List<T> products(List<T> values, UnaryOperator<T> facade) {
            return values.stream().map(facade).toList();
        }
    }

    private final RecordingScope scope = new RecordingScope();
    private final Set<String> closed = new HashSet<>();
    private byte[] seenInput;

    private void inScope() {
        assertThat(scope.depth).as("plugin code runs inside the callback scope").isPositive();
    }

    private final ExecutionRuntimeFactory plugin = new ExecutionRuntimeFactory() {
        @Override public String runtimeId() { inScope(); return "fake"; }

        @Override
        public ExecutionRuntime open(RuntimeOptions options) {
            inScope();
            if (options.values().containsKey("fail")) {
                throw new ExecutionEngineException("engine cannot start");
            }
            return new FakeRuntime();
        }
    };

    private final class FakeRuntime implements ExecutionRuntime {
        @Override
        public RuntimeDescriptor descriptor() {
            inScope();
            TreeMap<String, String> components = new TreeMap<>();
            components.put("runtime.id", "fake");
            components.put("metering.unit", "step");
            return new RuntimeDescriptor("fake", "Fake", "1", new MeteringModel("step", true, true),
                    MemoryModel.values()[0], Set.of(1), Set.of("application/x-fake"), components);
        }

        @Override public Readiness readiness(ExecutionProfile profile) { inScope(); return Readiness.ready(); }

        @Override
        public LoadedProgram load(ProgramArtifact artifact, InstanceConfig config) throws ProgramRejectedException {
            inScope();
            if (artifact.bytes().length == 0) {
                throw new ProgramRejectedException("empty", "empty program");
            }
            return new LoadedProgram() {
                @Override public Set<String> exports() { inScope(); return new HashSet<>(Set.of("apply_message")); }

                @Override
                public ProgramInstance instantiate(HostCalls host, long computeBudget) {
                    inScope();
                    return new ProgramInstance() {
                        @Override
                        public ExecutionResult invoke(String export, byte[] input) {
                            inScope();
                            seenInput = input;
                            input[0] = 42; // a misbehaving runtime scribbles on its input
                            return new ExecutionResult.Completed(host.call(0, new byte[][] {input}),
                                    OptionalLong.of(1));
                        }

                        @Override public long computeBudget() { inScope(); return computeBudget; }
                        @Override public boolean usable() { inScope(); return true; }
                        @Override public void close() { inScope(); closed.add("instance"); }
                    };
                }

                @Override public void close() { inScope(); closed.add("program"); }
            };
        }

        @Override public void close() { inScope(); closed.add("runtime"); }
    }

    @Test
    void everyRuntimeCallbackRunsInTheBundleScopeAndClosesThroughCleanup() throws Exception {
        AppLedgerKindBindings.ExecutionRuntimes binding = new AppLedgerKindBindings.ExecutionRuntimes();
        assertThat(binding.manifestKey()).isEqualTo("execution-runtime");
        assertThat(scope.call(() -> binding.selector(plugin))).isEqualTo("fake");
        ExecutionRuntimeFactory factory = binding.facade(plugin, scope);

        assertThat(factory.runtimeId()).isEqualTo("fake");
        try (ExecutionRuntime runtime = factory.open(RuntimeOptions.none())) {
            assertThat(runtime.descriptor().runtimeId()).isEqualTo("fake");
            assertThat(runtime.readiness(ExecutionProfile.CONSENSUS).status()).isEqualTo(Readiness.Status.READY);
            try (LoadedProgram program = runtime.load(new ProgramArtifact("application/x-fake", 1, new byte[] {1}),
                    new InstanceConfig(Set.of(), List.of(), 0))) {
                assertThat(program.exports()).containsExactly("apply_message");
                assertThatThrownBy(() -> program.exports().add("x")).isInstanceOf(UnsupportedOperationException.class);
                byte[] input = {7};
                try (ProgramInstance instance = program.instantiate((index, args) -> args[0].clone(), 5)) {
                    ExecutionResult result = instance.invoke("apply_message", input);
                    assertThat(input).containsExactly(7);
                    assertThat(seenInput).isNotSameAs(input);
                    assertThat(((ExecutionResult.Completed) result).output()).containsExactly(42);
                    assertThat(instance.computeBudget()).isEqualTo(5);
                    assertThat(instance.usable()).isTrue();
                }
            }
        }
        assertThat(closed).containsExactlyInAnyOrder("instance", "program", "runtime");
        assertThat(scope.events).containsSubsequence("activation:read execution-runtime identity",
                "product:FakeRuntime", "cleanup", "cleanup", "cleanup");
    }

    @Test
    void engineFailuresAndRejectionsPassThroughUnchanged() {
        ExecutionRuntimeFactory factory = new AppLedgerKindBindings.ExecutionRuntimes().facade(plugin, scope);
        assertThatThrownBy(() -> factory.open(RuntimeOptions.of(java.util.Map.of("fail", "1"))))
                .isExactlyInstanceOf(ExecutionEngineException.class)
                .hasMessage("engine cannot start");
        ExecutionRuntime runtime = factory.open(RuntimeOptions.none());
        assertThatThrownBy(() -> runtime.load(new ProgramArtifact("application/x-fake", 1, new byte[0]),
                new InstanceConfig(Set.of(), List.of(), 0)))
                .isExactlyInstanceOf(ProgramRejectedException.class)
                .hasMessage("empty program");
    }
}
