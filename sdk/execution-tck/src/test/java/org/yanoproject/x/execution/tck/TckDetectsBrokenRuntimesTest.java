package org.yanoproject.x.execution.tck;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.execution.abi.v1.AbiV1;
import org.yanoproject.x.execution.abi.v1.EntryOutputs;
import org.yanoproject.x.execution.tck.reference.GuestEnv;
import org.yanoproject.x.execution.tck.reference.ReferenceGuest;
import org.yanoproject.x.execution.tck.reference.ReferenceRuntimeFactory;
import org.yanoproject.x.execution.tck.reference.ReferenceTckPrograms;
import org.yanoproject.x.framework.api.execution.ExecutionProfile;
import org.yanoproject.x.framework.api.execution.ExecutionResult;
import org.yanoproject.x.framework.api.execution.ExecutionRuntime;
import org.yanoproject.x.framework.api.execution.ExecutionRuntimeFactory;
import org.yanoproject.x.framework.api.execution.HostCalls;
import org.yanoproject.x.framework.api.execution.InstanceConfig;
import org.yanoproject.x.framework.api.execution.LoadedProgram;
import org.yanoproject.x.framework.api.execution.ProgramArtifact;
import org.yanoproject.x.framework.api.execution.ProgramInstance;
import org.yanoproject.x.framework.api.execution.ProgramRejectedException;
import org.yanoproject.x.framework.api.execution.Readiness;
import org.yanoproject.x.framework.api.execution.RuntimeDescriptor;
import org.yanoproject.x.framework.api.execution.RuntimeOptions;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The TCK must fail runtimes that break the contract. Each case swaps one reference guest for a
 * misbehaving one that a broken engine would produce, and expects exactly that check to fail.
 */
class TckDetectsBrokenRuntimesTest {

    private static List<String> failuresWith(TckProgram program, Supplier<ReferenceGuest> replacement) {
        Map<TckProgram, Supplier<ReferenceGuest>> guests = ReferenceTckPrograms.guests();
        guests.put(program, replacement);
        Map<String, Supplier<ReferenceGuest>> byName = new HashMap<>();
        guests.forEach((p, g) -> byName.put(p.name(), g));
        return RuntimeTck.builder(new ReferenceRuntimeFactory(byName), new ReferenceTckPrograms()).build().run().stream()
                .filter(r -> r.status() == TckResult.Status.FAIL).map(TckResult::id).toList();
    }

    private static Supplier<ReferenceGuest> apply(java.util.function.BiFunction<byte[], GuestEnv, byte[]> body) {
        return () -> new ReferenceGuest() {
            @Override public Set<String> exports() { return Set.of(AbiV1.APPLY_MESSAGE); }
            @Override public Set<String> imports() { return Set.of("state_get", "state_put"); }
            @Override public byte[] invoke(String export, byte[] input, GuestEnv env) { return body.apply(input, env); }
        };
    }

    @Test
    void leakedClockIsDetected() {
        assertEquals(List.of("environment.stable"), failuresWith(TckProgram.ENVIRONMENT_PROBE, apply((input, env) -> {
            env.host("state_put", "env".getBytes(), Long.toString(System.nanoTime()).getBytes());
            return new EntryOutputs.Transition.Ok().encode();
        })));
    }

    @Test
    void stackDependentRecursionIsDetected() {
        // A runtime without its own depth limit: the JVM stack decides.
        assertEquals(List.of("recursion.deterministic"), failuresWith(TckProgram.DEEP_RECURSION, apply((input, env) -> {
            recurse(0);
            return new EntryOutputs.Transition.Ok().encode();
        })));
    }

    private static int recurse(int depth) {
        return recurse(depth + 1) + 1;
    }

    @Test
    void nonCanonicalOutputIsDetected() {
        // {0: 0} with a two-byte integer head: same value, not canonical.
        assertEquals(List.of("transition.reject"), failuresWith(TckProgram.REJECT,
                apply((input, env) -> new byte[] {(byte) 0xA1, 0x00, 0x18, 0x00})));
    }

    @Test
    void meterThatDoesNotRestartIsDetected() {
        // Usage accumulates across the invocations of one instance instead of restarting at zero.
        ExecutionRuntimeFactory reference = ReferenceTckPrograms.factory();
        ExecutionRuntimeFactory broken = new ExecutionRuntimeFactory() {
            @Override public String runtimeId() { return reference.runtimeId(); }
            @Override public ExecutionRuntime open(RuntimeOptions options) {
                ExecutionRuntime inner = reference.open(options);
                return new ExecutionRuntime() {
                    @Override public RuntimeDescriptor descriptor() { return inner.descriptor(); }
                    @Override public Readiness readiness(ExecutionProfile profile) { return inner.readiness(profile); }
                    @Override public void close() { inner.close(); }
                    @Override public LoadedProgram load(ProgramArtifact artifact, InstanceConfig config) throws ProgramRejectedException {
                        LoadedProgram program = inner.load(artifact, config);
                        return new LoadedProgram() {
                            @Override public Set<String> exports() { return program.exports(); }
                            @Override public void close() { program.close(); }
                            @Override public ProgramInstance instantiate(HostCalls host, long budget) {
                                ProgramInstance instance = program.instantiate(host, budget);
                                return new ProgramInstance() {
                                    private long spent;
                                    private boolean poisoned;
                                    @Override public long computeBudget() { return budget; }
                                    @Override public boolean usable() { return !poisoned && instance.usable(); }
                                    @Override public void close() { instance.close(); }
                                    @Override public ExecutionResult invoke(String export, byte[] input) {
                                        if (poisoned) {
                                            throw new IllegalStateException("poisoned");
                                        }
                                        ExecutionResult result = instance.invoke(export, input);
                                        spent += result.computeUsed().orElse(0);
                                        if (spent > budget) {
                                            poisoned = true;
                                            return new ExecutionResult.OutOfCompute(budget, OptionalLong.of(budget));
                                        }
                                        return result;
                                    }
                                };
                            }
                        };
                    }
                };
            }
        };
        List<String> failures = RuntimeTck.builder(broken, new ReferenceTckPrograms()).build().run().stream()
                .filter(r -> r.status() == TckResult.Status.FAIL).map(TckResult::id).toList();
        assertEquals(List.of("compute.per-invocation"), failures);
    }
}
