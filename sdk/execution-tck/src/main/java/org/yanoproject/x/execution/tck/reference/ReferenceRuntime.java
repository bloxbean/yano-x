package org.yanoproject.x.execution.tck.reference;

import org.yanoproject.x.framework.api.execution.ExecutionEngineException;
import org.yanoproject.x.framework.api.execution.ExecutionProfile;
import org.yanoproject.x.framework.api.execution.ExecutionResult;
import org.yanoproject.x.framework.api.execution.ExecutionRuntime;
import org.yanoproject.x.framework.api.execution.HostAbort;
import org.yanoproject.x.framework.api.execution.HostCalls;
import org.yanoproject.x.framework.api.execution.HostFunction;
import org.yanoproject.x.framework.api.execution.InstanceConfig;
import org.yanoproject.x.framework.api.execution.LoadedProgram;
import org.yanoproject.x.framework.api.execution.MemoryModel;
import org.yanoproject.x.framework.api.execution.MeteringModel;
import org.yanoproject.x.framework.api.execution.ProgramArtifact;
import org.yanoproject.x.framework.api.execution.ProgramInstance;
import org.yanoproject.x.framework.api.execution.ProgramRejectedException;
import org.yanoproject.x.framework.api.execution.Readiness;
import org.yanoproject.x.framework.api.execution.RuntimeDescriptor;
import org.yanoproject.x.framework.api.execution.TrapCode;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

final class ReferenceRuntime implements ExecutionRuntime {

    static final int MAX_CALL_DEPTH = 1_000;
    private static final MeteringModel METERING = new MeteringModel("reference.tick", true, true);

    private final Map<String, Supplier<ReferenceGuest>> programs;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final RuntimeDescriptor descriptor;

    ReferenceRuntime(Map<String, Supplier<ReferenceGuest>> programs) {
        this.programs = programs;
        TreeMap<String, String> fingerprint = new TreeMap<>();
        fingerprint.put("runtime.id", ReferenceRuntimeFactory.RUNTIME_ID);
        fingerprint.put("engine.version", "1");
        fingerprint.put("metering.unit", METERING.unit());
        fingerprint.put("limits.call-depth", Integer.toString(MAX_CALL_DEPTH));
        this.descriptor = new RuntimeDescriptor(ReferenceRuntimeFactory.RUNTIME_ID, "Yano reference runtime", "1",
                METERING, MemoryModel.NOT_ENFORCED, Set.of(1), Set.of(ReferenceRuntimeFactory.MEDIA_TYPE), fingerprint);
    }

    @Override
    public RuntimeDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Readiness readiness(ExecutionProfile profile) {
        if (closed.get()) {
            return Readiness.unavailable("closed");
        }
        return profile == ExecutionProfile.CONSENSUS ? Readiness.ready() : Readiness.unavailable("profile not built");
    }

    @Override
    public LoadedProgram load(ProgramArtifact artifact, InstanceConfig config) throws ProgramRejectedException {
        if (closed.get()) {
            throw new ExecutionEngineException("runtime closed");
        }
        if (!ReferenceRuntimeFactory.MEDIA_TYPE.equals(artifact.mediaType())) {
            throw new ProgramRejectedException(ProgramRejectedException.UNSUPPORTED_MEDIA_TYPE, artifact.mediaType());
        }
        if (artifact.abiVersion() != 1) {
            throw new ProgramRejectedException(ProgramRejectedException.UNSUPPORTED_ABI, "abi " + artifact.abiVersion());
        }
        String name = new String(artifact.bytes(), StandardCharsets.UTF_8);
        Supplier<ReferenceGuest> supplier = programs.get(name);
        if (supplier == null) {
            throw new ProgramRejectedException(ProgramRejectedException.MALFORMED, "no reference guest named " + name);
        }
        ReferenceGuest probe = supplier.get();
        for (String required : config.requiredExports()) {
            if (!probe.exports().contains(required)) {
                throw new ProgramRejectedException(ProgramRejectedException.MISSING_EXPORT, required);
            }
        }
        for (String imported : probe.imports()) {
            if (config.hostFunction(imported) == null) {
                throw new ProgramRejectedException(ProgramRejectedException.UNKNOWN_IMPORT, imported);
            }
        }
        Set<String> exports = Set.copyOf(probe.exports());
        return new LoadedProgram() {
            @Override public Set<String> exports() { return exports; }
            @Override public ProgramInstance instantiate(HostCalls host, long computeBudget) {
                if (computeBudget < 1) {
                    throw new IllegalArgumentException("compute budget must be at least 1");
                }
                return new Instance(supplier.get(), exports, config, host, computeBudget);
            }
            @Override public void close() { }
        };
    }

    @Override
    public void close() {
        closed.set(true);
    }

    /** Signals compute exhaustion through guest code; guests are expected not to catch Errors. */
    private static final class OutOfComputeSignal extends Error {
        OutOfComputeSignal() {
            super(null, null, false, false);
        }
    }

    private static final class CallDepthSignal extends Error {
        CallDepthSignal() {
            super(null, null, false, false);
        }
    }

    private static final class Instance implements ProgramInstance, GuestEnv {
        private final ReferenceGuest guest;
        private final Set<String> exports;
        private final InstanceConfig config;
        private final HostCalls host;
        private boolean usable = true;
        private final long budget;
        private long used;
        private int depth;
        private HostAbort abort;

        Instance(ReferenceGuest guest, Set<String> exports, InstanceConfig config, HostCalls host, long budget) {
            this.guest = guest;
            this.exports = exports;
            this.config = config;
            this.host = host;
            this.budget = budget;
        }

        @Override
        public long computeBudget() {
            return budget;
        }

        @Override
        public ExecutionResult invoke(String export, byte[] input) {
            if (!usable) {
                throw new IllegalStateException("instance is poisoned or closed");
            }
            if (!exports.contains(export)) {
                throw new IllegalArgumentException("not exported: " + export);
            }
            used = 0;
            depth = 0;
            abort = null;
            ExecutionResult result = run(export, input);
            if (!(result instanceof ExecutionResult.Completed)) {
                usable = false;
            }
            return result;
        }

        private ExecutionResult run(String export, byte[] input) {
            byte[] output;
            try {
                output = guest.invoke(export, input.clone(), this);
            } catch (OutOfComputeSignal e) {
                return abort != null ? aborted() : new ExecutionResult.OutOfCompute(budget, OptionalLong.of(used));
            } catch (CallDepthSignal e) {
                return abort != null ? aborted()
                        : new ExecutionResult.Trapped(TrapCode.CALL_DEPTH, "depth " + MAX_CALL_DEPTH, OptionalLong.of(used));
            } catch (StackOverflowError | OutOfMemoryError e) {
                // Depends on the JVM, not on the program: never a result.
                throw new ExecutionEngineException("guest exhausted a JVM resource", e);
            } catch (ExecutionEngineException e) {
                throw e;
            } catch (RuntimeException e) {
                if (abort != null) {
                    return aborted();
                }
                return new ExecutionResult.Trapped(TrapCode.GUEST_ERROR, String.valueOf(e.getMessage()), OptionalLong.of(used));
            }
            // A guest that caught the abort and returned normally is still aborted.
            if (abort != null) {
                return aborted();
            }
            if (output == null) {
                return new ExecutionResult.Trapped(TrapCode.BAD_RETURN, "no byte string returned", OptionalLong.of(used));
            }
            return new ExecutionResult.Completed(output, OptionalLong.of(used));
        }

        private ExecutionResult aborted() {
            return new ExecutionResult.HostAborted(abort, OptionalLong.of(used));
        }

        @Override
        public void tick(long units) {
            if (abort != null) {
                throw abort; // the guest swallowed an abort and kept going
            }
            if (units > budget - used) {
                used = budget;
                throw new OutOfComputeSignal();
            }
            used += units;
        }

        @Override
        public void enter() {
            if (++depth > MAX_CALL_DEPTH) {
                throw new CallDepthSignal();
            }
        }

        @Override
        public void exit() {
            depth--;
        }

        @Override
        public byte[] host(String name, byte[]... args) {
            if (abort != null) {
                throw abort;
            }
            HostFunction function = config.hostFunction(name);
            if (function == null || !guest.imports().contains(name)) {
                throw new GuestError("host function not imported: " + name);
            }
            tick(1);
            try {
                byte[][] copies = new byte[args.length][];
                for (int i = 0; i < args.length; i++) {
                    copies[i] = args[i].clone();
                }
                byte[] result = host.call(function.index(), copies);
                return result == null ? null : result.clone();
            } catch (HostAbort e) {
                abort = e;
                throw e;
            } catch (RuntimeException e) {
                throw new ExecutionEngineException("host function " + name + " failed", e);
            }
        }

        @Override
        public boolean usable() {
            return usable;
        }

        @Override
        public void close() {
            usable = false;
        }
    }
}
