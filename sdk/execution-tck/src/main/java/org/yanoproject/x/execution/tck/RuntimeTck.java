package org.yanoproject.x.execution.tck;

import org.junit.jupiter.api.DynamicTest;
import org.yanoproject.x.execution.abi.cbor.CborException;
import org.yanoproject.x.execution.abi.v1.AbiV1;
import org.yanoproject.x.execution.abi.v1.EffectIntentArg;
import org.yanoproject.x.execution.abi.v1.EffectLimits;
import org.yanoproject.x.execution.abi.v1.EffectResultInput;
import org.yanoproject.x.execution.abi.v1.EntryOutputs;
import org.yanoproject.x.execution.abi.v1.HostCostsV1;
import org.yanoproject.x.execution.abi.v1.HostSessionV1;
import org.yanoproject.x.execution.abi.v1.HostTableV1;
import org.yanoproject.x.execution.abi.v1.StagedState;
import org.yanoproject.x.execution.abi.v1.StateView;
import org.yanoproject.x.execution.abi.v1.Write;
import org.yanoproject.x.framework.api.execution.ExecutionEngineException;
import org.yanoproject.x.framework.api.execution.ExecutionProfile;
import org.yanoproject.x.framework.api.execution.ExecutionResult;
import org.yanoproject.x.framework.api.execution.ExecutionRuntime;
import org.yanoproject.x.framework.api.execution.ExecutionRuntimeFactory;
import org.yanoproject.x.framework.api.execution.HostAbort;
import org.yanoproject.x.framework.api.execution.InstanceConfig;
import org.yanoproject.x.framework.api.execution.LoadedProgram;
import org.yanoproject.x.framework.api.execution.MemoryModel;
import org.yanoproject.x.framework.api.execution.ProgramArtifact;
import org.yanoproject.x.framework.api.execution.ProgramInstance;
import org.yanoproject.x.framework.api.execution.ProgramRejectedException;
import org.yanoproject.x.framework.api.execution.Readiness;
import org.yanoproject.x.framework.api.execution.RuntimeDescriptor;
import org.yanoproject.x.framework.api.execution.RuntimeOptions;
import org.yanoproject.x.framework.api.execution.TrapCode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.yanoproject.x.execution.tck.TckInputs.applyMessage;
import static org.yanoproject.x.execution.tck.TckInputs.be64;
import static org.yanoproject.x.execution.tck.TckInputs.utf8;

/**
 * Conformance kit for {@link ExecutionRuntimeFactory} implementations, against guest ABI v1 and the
 * host behaviour of {@link HostSessionV1}. Each check is independent; {@link #run()} returns every
 * result, and {@link #dynamicTests()} turns them into JUnit tests.
 */
public final class RuntimeTck {

    /** Generous per-invocation budgets, for checks that must not run out. */
    public static final long DEFAULT_COMPUTE = 50_000_000L;
    public static final long DEFAULT_HOST = 10_000_000L;
    /** Budgets for the exhaustion checks. */
    public static final long SPIN_COMPUTE = 1_000_000L;
    public static final long FLOOD_HOST = 100_000L;
    public static final long MEMORY_LIMIT = 16L * 1024 * 1024;
    /** Thread stack sizes the recursion check runs on. Results must not depend on them. */
    public static final long[] STACK_SIZES = {512L * 1024, 4L * 1024 * 1024, 64L * 1024 * 1024};

    static final EffectLimits EFFECT_LIMITS = new EffectLimits(true, AbiV1.MAX_EFFECT_PAYLOAD_BYTES, 100_000, 100_000, 256);

    private final ExecutionRuntimeFactory factory;
    private final TckPrograms programs;
    private final RuntimeOptions options;
    private final long compute;

    private RuntimeTck(Builder builder) {
        this.factory = builder.factory;
        this.programs = builder.programs;
        this.options = builder.options;
        this.compute = builder.compute;
    }

    public static Builder builder(ExecutionRuntimeFactory factory, TckPrograms programs) {
        return new Builder(factory, programs);
    }

    public static final class Builder {
        private final ExecutionRuntimeFactory factory;
        private final TckPrograms programs;
        private RuntimeOptions options = RuntimeOptions.none();
        private long compute = DEFAULT_COMPUTE;

        private Builder(ExecutionRuntimeFactory factory, TckPrograms programs) {
            this.factory = Objects.requireNonNull(factory, "factory");
            this.programs = Objects.requireNonNull(programs, "programs");
        }

        public Builder options(RuntimeOptions value) {
            this.options = value;
            return this;
        }

        /** Compute budget for checks that must complete, in the runtime's own unit. */
        public Builder computeBudget(long value) {
            this.compute = value;
            return this;
        }

        public RuntimeTck build() {
            return new RuntimeTck(this);
        }
    }

    @FunctionalInterface
    private interface Check {
        /** Returns a WARN detail, or {@code null} for PASS; throws {@link TckFailure} for FAIL. */
        String run(ExecutionRuntime runtime) throws Exception;
    }

    private Map<String, Check> checks() {
        Map<String, Check> checks = new LinkedHashMap<>();
        checks.put("descriptor.valid", this::descriptorValid);
        checks.put("descriptor.stable", this::descriptorStable);
        checks.put("readiness.consensus", this::readinessConsensus);
        checks.put("load.exports", this::loadExports);
        checks.put("load.missing-export", this::loadMissingExport);
        checks.put("load.unknown-import", this::loadUnknownImport);
        checks.put("bytes.transparent", this::bytesTransparent);
        checks.put("counter.apply", this::counterApply);
        checks.put("counter.validate", this::counterValidate);
        checks.put("counter.query", this::counterQuery);
        checks.put("effects.emit-and-result", this::effects);
        checks.put("transition.reject", this::reject);
        checks.put("trap.guest-error", this::trapGuestError);
        checks.put("compute.exhaustion", this::computeExhaustion);
        checks.put("compute.per-invocation", this::computePerInvocation);
        checks.put("host.fuel-exhaustion", this::hostFuelExhaustion);
        checks.put("host.abort-uncatchable", this::hostAbortUncatchable);
        checks.put("host.value-limit", this::hostValueLimit);
        checks.put("host.reserved-key", this::hostReservedKey);
        checks.put("host.read-only", this::hostReadOnly);
        checks.put("recursion.deterministic", this::recursion);
        checks.put("memory.bounded", this::memoryBounded);
        checks.put("isolation.instances", this::isolation);
        checks.put("environment.stable", this::environmentStable);
        checks.put("determinism.corpus", this::determinismCorpus);
        checks.put("concurrency.instances", this::concurrency);
        return checks;
    }

    /** Runs every check. */
    public List<TckResult> run() {
        List<TckResult> results = new ArrayList<>();
        try (ExecutionRuntime runtime = factory.open(options)) {
            for (Map.Entry<String, Check> check : checks().entrySet()) {
                results.add(runOne(check.getKey(), check.getValue(), runtime));
            }
        }
        return List.copyOf(results);
    }

    private TckResult runOne(String id, Check check, ExecutionRuntime runtime) {
        try {
            String warning = check.run(runtime);
            return warning == null ? new TckResult(id, TckResult.Status.PASS, "")
                    : new TckResult(id, TckResult.Status.WARN, warning);
        } catch (TckFailure e) {
            return new TckResult(id, TckResult.Status.FAIL, e.getMessage());
        } catch (Throwable e) {
            return new TckResult(id, TckResult.Status.FAIL, "unexpected " + e);
        }
    }

    /** One JUnit test per check; WARN results pass and print their detail. */
    public List<DynamicTest> dynamicTests() {
        return run().stream().map(result -> DynamicTest.dynamicTest(result.id(), () -> {
            if (result.status() == TckResult.Status.FAIL) {
                throw new AssertionError(result.id() + ": " + result.detail());
            }
            if (result.status() == TckResult.Status.WARN) {
                System.out.println("[TCK WARN] " + result.id() + ": " + result.detail());
            }
        })).toList();
    }

    // ------------------------------------------------------------------ helpers

    private static InstanceConfig instanceConfig() {
        return new InstanceConfig(AbiV1.REQUIRED_EXPORTS, HostTableV1.FUNCTIONS, MEMORY_LIMIT);
    }

    private ProgramArtifact artifact(TckProgram program) {
        ProgramArtifact artifact = programs.artifact(program);
        if (artifact == null) {
            throw new TckFailure("runtime does not supply TCK program " + program);
        }
        return artifact;
    }

    private LoadedProgram load(ExecutionRuntime runtime, TckProgram program) {
        try {
            return runtime.load(artifact(program), instanceConfig());
        } catch (ProgramRejectedException e) {
            throw new TckFailure(program + " was rejected at load: " + e.code() + " " + e.getMessage(), e);
        }
    }

    record Invocation(ExecutionResult result, HostSessionV1 session) {
        List<Write> writes() {
            return session.writes();
        }
    }

    private Invocation invoke(ProgramInstance instance, HostSessionV1 session, String export, byte[] input) {
        return new Invocation(instance.invoke(export, input), session);
    }

    private static HostSessionV1 session(HostSessionV1.Mode mode, StateView base, long hostBudget) {
        return new HostSessionV1(mode, base, hostBudget, EFFECT_LIMITS, HostSessionV1.LogSink.DISCARD);
    }

    private static final StateView EMPTY = key -> Optional.empty();

    /** Fresh instance, one invocation. */
    private Invocation once(LoadedProgram program, String export, byte[] input, HostSessionV1.Mode mode,
                            StateView base, long computeBudget, long hostBudget) {
        HostSessionV1 session = session(mode, base, hostBudget);
        try (ProgramInstance instance = program.instantiate(session, computeBudget)) {
            return invoke(instance, session, export, input);
        }
    }

    private Invocation applyOnce(LoadedProgram program, byte[] body, StateView base) {
        return once(program, AbiV1.APPLY_MESSAGE, applyMessage(1, 0, body), HostSessionV1.Mode.TRANSITION,
                base, compute, DEFAULT_HOST);
    }

    private static byte[] completed(Invocation invocation, String what) {
        if (!(invocation.result() instanceof ExecutionResult.Completed completed)) {
            throw new TckFailure(what + ": expected Completed, got " + describe(invocation.result()));
        }
        return completed.output();
    }

    private static EntryOutputs.Transition transition(Invocation invocation, String what) {
        byte[] output = completed(invocation, what);
        try {
            return EntryOutputs.decodeTransition(output);
        } catch (CborException e) {
            throw new TckFailure(what + ": output is not a canonical transition outcome (" + e.code() + "): "
                    + HexFormat.of().formatHex(output));
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new TckFailure(message);
        }
    }

    private static HostAbort aborted(ExecutionResult result, HostAbort.Kind kind, String code, String what) {
        if (!(result instanceof ExecutionResult.HostAborted aborted)) {
            throw new TckFailure(what + ": expected HostAborted(" + kind + "), got " + describe(result));
        }
        expect(aborted.abort().kind() == kind, what + ": expected abort kind " + kind + ", got " + aborted.abort().kind());
        if (code != null) {
            expect(aborted.abort().code().equals(code), what + ": expected abort code " + code + ", got "
                    + aborted.abort().code());
        }
        return aborted.abort();
    }

    static String describe(ExecutionResult result) {
        return switch (result) {
            case ExecutionResult.Completed c -> "Completed(" + c.output().length + " bytes)";
            case ExecutionResult.Trapped t -> "Trapped(" + t.code() + ")";
            case ExecutionResult.OutOfCompute o -> "OutOfCompute(" + o.budget() + ")";
            case ExecutionResult.HostAborted h -> "HostAborted(" + h.abort().kind() + "/" + h.abort().code() + ")";
        };
    }

    private static Optional<byte[]> written(List<Write> writes, byte[] key) {
        for (Write write : writes) {
            if (Arrays.equals(write.key(), key)) {
                return Optional.ofNullable(write.value());
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ checks

    private String descriptorValid(ExecutionRuntime runtime) {
        RuntimeDescriptor d = runtime.descriptor();
        expect(d.runtimeId().equals(factory.runtimeId()), "descriptor runtime id " + d.runtimeId()
                + " differs from factory id " + factory.runtimeId());
        expect(d.abiVersions().contains(AbiV1.VERSION), "runtime does not declare ABI v1");
        expect(d.mediaTypes().contains(artifact(TckProgram.COUNTER).mediaType()),
                "runtime does not declare the media type of its own TCK programs");
        expect(d.fingerprintComponents().containsKey("engine.version"), "fingerprint must include engine.version");
        if (!d.metering().exactExhaustion()) {
            throw new TckFailure("metering must stop at exactly the budget for the CONSENSUS profile");
        }
        return null;
    }

    private String descriptorStable(ExecutionRuntime runtime) {
        try (ExecutionRuntime second = factory.open(options)) {
            expect(second.descriptor().fingerprintComponents().equals(runtime.descriptor().fingerprintComponents()),
                    "two opens with the same options report different fingerprint components");
        }
        return null;
    }

    private String readinessConsensus(ExecutionRuntime runtime) {
        Readiness readiness = runtime.readiness(ExecutionProfile.CONSENSUS);
        return switch (readiness.status()) {
            case READY -> null;
            case DEGRADED -> "runtime is degraded for CONSENSUS: " + readiness.reason();
            case UNAVAILABLE -> throw new TckFailure("runtime is unavailable for CONSENSUS: " + readiness.reason());
        };
    }

    private String loadExports(ExecutionRuntime runtime) {
        try (LoadedProgram program = load(runtime, TckProgram.COUNTER)) {
            expect(program.exports().containsAll(List.of(AbiV1.APPLY_MESSAGE, AbiV1.VALIDATE, AbiV1.QUERY)),
                    "COUNTER exports " + program.exports());
        }
        try (LoadedProgram program = load(runtime, TckProgram.ECHO)) {
            expect(!program.exports().contains(AbiV1.QUERY), "ECHO must not report query as exported");
            HostSessionV1 session = session(HostSessionV1.Mode.TRANSITION, EMPTY, DEFAULT_HOST);
            try (ProgramInstance instance = program.instantiate(session, compute)) {
                try {
                    instance.invoke(AbiV1.QUERY, new byte[0]);
                    throw new TckFailure("invoking a missing export must throw IllegalArgumentException");
                } catch (IllegalArgumentException expected) {
                    // required
                }
            }
        }
        return null;
    }

    private String loadMissingExport(ExecutionRuntime runtime) {
        try {
            runtime.load(artifact(TckProgram.MISSING_EXPORT), instanceConfig()).close();
            throw new TckFailure("a program without apply_message must be rejected at load");
        } catch (ProgramRejectedException e) {
            expect(e.code().equals(ProgramRejectedException.MISSING_EXPORT), "rejected with " + e.code()
                    + ", expected " + ProgramRejectedException.MISSING_EXPORT);
        }
        return null;
    }

    private String loadUnknownImport(ExecutionRuntime runtime) {
        LoadedProgram program;
        try {
            program = runtime.load(artifact(TckProgram.UNKNOWN_IMPORT), instanceConfig());
        } catch (ProgramRejectedException e) {
            expect(e.code().equals(ProgramRejectedException.UNKNOWN_IMPORT), "rejected with " + e.code()
                    + ", expected " + ProgramRejectedException.UNKNOWN_IMPORT);
            return null;
        }
        // Dynamic languages cannot see imports at load time; the call must then fail inside the guest.
        try (program) {
            Invocation invocation = applyOnce(program, utf8("x"), EMPTY);
            expect(invocation.result() instanceof ExecutionResult.Trapped t && t.code() == TrapCode.GUEST_ERROR,
                    "calling an unknown host function must trap with GUEST_ERROR, got " + describe(invocation.result()));
            expect(invocation.session().calls() == 0, "an unknown host function must never reach the host");
            return "unknown imports are detected only at call time";
        }
    }

    private String bytesTransparent(ExecutionRuntime runtime) {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        byte[] large = new byte[AbiV1.MAX_VALUE_BYTES];
        new Random(7).nextBytes(large);
        try (LoadedProgram program = load(runtime, TckProgram.ECHO)) {
            for (byte[] body : List.of(new byte[0], new byte[] {0}, all, large)) {
                byte[] output = completed(applyOnce(program, body, EMPTY), "ECHO");
                expect(Arrays.equals(output, body), "ECHO changed a " + body.length + "-byte body");
            }
        }
        return null;
    }

    private String counterApply(ExecutionRuntime runtime) {
        try (LoadedProgram program = load(runtime, TckProgram.COUNTER)) {
            StagedState state = new StagedState(EMPTY);
            for (int i = 0; i < 3; i++) {
                Invocation invocation = applyOnce(program, utf8("a"), state);
                expect(transition(invocation, "COUNTER") instanceof EntryOutputs.Transition.Ok, "COUNTER must return Ok");
                state.apply(invocation.writes());
            }
            List<Write> writes = state.writes();
            expect(writes.equals(List.of(new Write(utf8("c/a"), be64(3)))), "COUNTER state after 3 messages: " + writes);
        }
        return null;
    }

    private String counterValidate(ExecutionRuntime runtime) {
        try (LoadedProgram program = load(runtime, TckProgram.COUNTER)) {
            Invocation empty = once(program, AbiV1.VALIDATE, TckInputs.validate(5, new byte[0]),
                    HostSessionV1.Mode.READ_ONLY, EMPTY, compute, DEFAULT_HOST);
            expect(EntryOutputs.decodeVerdict(completed(empty, "validate"))
                    .equals(new EntryOutputs.Verdict.Reject("empty-body")), "empty body must be rejected with empty-body");
            Invocation ok = once(program, AbiV1.VALIDATE, TckInputs.validate(5, utf8("a")),
                    HostSessionV1.Mode.READ_ONLY, EMPTY, compute, DEFAULT_HOST);
            expect(EntryOutputs.decodeVerdict(completed(ok, "validate")) instanceof EntryOutputs.Verdict.Accept,
                    "non-empty body must be accepted");
        }
        return null;
    }

    private String counterQuery(ExecutionRuntime runtime) {
        StateView state = key -> Arrays.equals(key, utf8("c/a")) ? Optional.of(be64(42)) : Optional.empty();
        try (LoadedProgram program = load(runtime, TckProgram.COUNTER)) {
            Invocation found = once(program, AbiV1.QUERY, TckInputs.query(9, "count", utf8("a")),
                    HostSessionV1.Mode.READ_ONLY, state, compute, DEFAULT_HOST);
            expect(EntryOutputs.decodeQuery(completed(found, "query"))
                    .equals(new EntryOutputs.QueryOutcome.Payload(be64(42))), "query count for a must be 42");
            Invocation absent = once(program, AbiV1.QUERY, TckInputs.query(9, "count", utf8("zz")),
                    HostSessionV1.Mode.READ_ONLY, state, compute, DEFAULT_HOST);
            expect(EntryOutputs.decodeQuery(completed(absent, "query"))
                    .equals(new EntryOutputs.QueryOutcome.Payload(be64(0))), "query count for an absent key must be 0");
            Invocation unknown = once(program, AbiV1.QUERY, TckInputs.query(9, "nope", new byte[0]),
                    HostSessionV1.Mode.READ_ONLY, state, compute, DEFAULT_HOST);
            expect(EntryOutputs.decodeQuery(completed(unknown, "query"))
                    .equals(new EntryOutputs.QueryOutcome.Error("unsupported")), "unknown query path must be unsupported");
        }
        return null;
    }

    private String effects(ExecutionRuntime runtime) {
        try (LoadedProgram program = load(runtime, TckProgram.EFFECTS)) {
            byte[] body = utf8("pay-1");
            Invocation emit = applyOnce(program, body, EMPTY);
            expect(transition(emit, "EFFECTS") instanceof EntryOutputs.Transition.Ok, "EFFECTS must return Ok");
            String scope = HexFormat.of().formatHex(TckInputs.message(1, 0, body).messageId());
            expect(emit.session().effects().equals(List.of(new EffectIntentArg("tck.echo", body, scope, -1, 1, 10))),
                    "EFFECTS emitted " + emit.session().effects());
            EffectResultInput result = new EffectResultInput(TckInputs.CHAIN_ID, 1, 0, "tck.echo", scope, 1,
                    utf8("tx-ref"), null, 2);
            Invocation onResult = once(program, AbiV1.ON_EFFECT_RESULT, TckInputs.onEffectResult(2, result),
                    HostSessionV1.Mode.TRANSITION, EMPTY, compute, DEFAULT_HOST);
            expect(transition(onResult, "on_effect_result") instanceof EntryOutputs.Transition.Ok,
                    "on_effect_result must return Ok");
            expect(onResult.writes().equals(List.of(new Write(utf8("r/" + scope), new byte[] {1}))),
                    "on_effect_result wrote " + onResult.writes());
        }
        return null;
    }

    private String reject(ExecutionRuntime runtime) {
        try (LoadedProgram program = load(runtime, TckProgram.REJECT)) {
            Invocation rejected = applyOnce(program, new byte[] {0, 1}, EMPTY);
            expect(transition(rejected, "REJECT").equals(new EntryOutputs.Transition.Rejected("tck.reject")),
                    "body starting with 0 must be rejected with tck.reject");
            Invocation accepted = applyOnce(program, new byte[] {1}, EMPTY);
            expect(transition(accepted, "REJECT") instanceof EntryOutputs.Transition.Ok, "other bodies must be Ok");
        }
        return null;
    }

    private String trapGuestError(ExecutionRuntime runtime) {
        try (LoadedProgram program = load(runtime, TckProgram.TRAP_AFTER_WRITE)) {
            HostSessionV1 session = session(HostSessionV1.Mode.TRANSITION, EMPTY, DEFAULT_HOST);
            try (ProgramInstance instance = program.instantiate(session, compute)) {
                ExecutionResult result = instance.invoke(AbiV1.APPLY_MESSAGE, applyMessage(1, 0, utf8("b")));
                expect(result instanceof ExecutionResult.Trapped t && t.code() == TrapCode.GUEST_ERROR,
                        "expected Trapped(GUEST_ERROR), got " + describe(result));
                expectPoisoned(instance);
            }
        }
        return null;
    }

    private static void expectPoisoned(ProgramInstance instance) {
        expect(!instance.usable(), "instance must be unusable after a failed invocation");
        try {
            instance.invoke(AbiV1.APPLY_MESSAGE, applyMessage(1, 0, new byte[0]));
            throw new TckFailure("invoking a poisoned instance must throw IllegalStateException");
        } catch (IllegalStateException expected) {
            // required
        }
    }

    /**
     * The budget is fixed per instance and every invocation gets all of it: with the smallest budget
     * that lets one COUNTER apply complete, five applies on one instance all complete.
     */
    private String computePerInvocation(ExecutionRuntime runtime) {
        try (LoadedProgram program = load(runtime, TckProgram.COUNTER)) {
            long low = 1;
            long high = compute;
            expect(fitsIn(program, high), "COUNTER must complete within the default budget");
            while (low < high) {
                long mid = low + (high - low) / 2;
                if (fitsIn(program, mid)) {
                    high = mid;
                } else {
                    low = mid + 1;
                }
            }
            long smallest = low;
            HostSessionV1[] current = new HostSessionV1[1];
            try (ProgramInstance instance = program.instantiate((index, args) -> current[0].call(index, args), smallest)) {
                expect(instance.computeBudget() == smallest, "computeBudget() must report the instance budget");
                for (int i = 0; i < 5; i++) {
                    current[0] = session(HostSessionV1.Mode.TRANSITION, EMPTY, DEFAULT_HOST);
                    ExecutionResult result = instance.invoke(AbiV1.APPLY_MESSAGE, applyMessage(1, i, utf8("a")));
                    expect(result instanceof ExecutionResult.Completed,
                            "invocation " + i + " with budget " + smallest + " must complete: the meter restarts at zero, got "
                                    + describe(result));
                }
            }
        }
        return null;
    }

    private boolean fitsIn(LoadedProgram program, long budget) {
        Invocation invocation = once(program, AbiV1.APPLY_MESSAGE, applyMessage(1, 0, utf8("a")),
                HostSessionV1.Mode.TRANSITION, EMPTY, budget, DEFAULT_HOST);
        if (invocation.result() instanceof ExecutionResult.Completed) {
            return true;
        }
        expect(invocation.result() instanceof ExecutionResult.OutOfCompute,
                "a too-small budget must give OutOfCompute, got " + describe(invocation.result()));
        return false;
    }

    private String computeExhaustion(ExecutionRuntime runtime) {
        boolean exact = runtime.descriptor().metering().exactExhaustion() && runtime.descriptor().metering().reportsUsage();
        try (LoadedProgram program = load(runtime, TckProgram.SPIN)) {
            for (int run = 0; run < 3; run++) {
                HostSessionV1 session = session(HostSessionV1.Mode.TRANSITION, EMPTY, DEFAULT_HOST);
                try (ProgramInstance instance = program.instantiate(session, SPIN_COMPUTE)) {
                    ExecutionResult result = instance.invoke(AbiV1.APPLY_MESSAGE, applyMessage(1, 0, new byte[0]));
                    expect(result instanceof ExecutionResult.OutOfCompute o && o.budget() == SPIN_COMPUTE,
                            "SPIN must run out of compute at the budget, got " + describe(result));
                    if (exact) {
                        expect(result.computeUsed().equals(java.util.OptionalLong.of(SPIN_COMPUTE)),
                                "exact metering must report usage equal to the budget, got " + result.computeUsed());
                    }
                    expectPoisoned(instance);
                }
            }
        }
        return null;
    }

    private String hostFuelExhaustion(ExecutionRuntime runtime) {
        // state_get("k") costs CALL + 1 input byte and returns nothing: the abort comes on a fixed call.
        long perCall = HostCostsV1.CALL + HostCostsV1.PER_INPUT_BYTE;
        long expectedCalls = FLOOD_HOST / perCall + 1;
        try (LoadedProgram program = load(runtime, TckProgram.HOST_FLOOD)) {
            for (int run = 0; run < 3; run++) {
                Invocation invocation = once(program, AbiV1.APPLY_MESSAGE, applyMessage(1, 0, new byte[0]),
                        HostSessionV1.Mode.TRANSITION, EMPTY, compute, FLOOD_HOST);
                aborted(invocation.result(), HostAbort.Kind.HOST_FUEL_EXHAUSTED, HostSessionV1.HOST_FUEL, "HOST_FLOOD");
                expect(invocation.session().calls() == expectedCalls, "host fuel ran out after "
                        + invocation.session().calls() + " calls, expected " + expectedCalls);
            }
        }
        return null;
    }

    private String hostAbortUncatchable(ExecutionRuntime runtime) {
        try (LoadedProgram program = load(runtime, TckProgram.CATCH_HOST_ABORT)) {
            Invocation invocation = once(program, AbiV1.APPLY_MESSAGE, applyMessage(1, 0, new byte[0]),
                    HostSessionV1.Mode.TRANSITION, EMPTY, compute, FLOOD_HOST);
            aborted(invocation.result(), HostAbort.Kind.HOST_FUEL_EXHAUSTED, HostSessionV1.HOST_FUEL, "CATCH_HOST_ABORT");
        }
        return null;
    }

    private String hostValueLimit(ExecutionRuntime runtime) {
        try (LoadedProgram program = load(runtime, TckProgram.OVERSIZE_VALUE)) {
            aborted(applyOnce(program, new byte[0], EMPTY).result(), HostAbort.Kind.ABI_VIOLATION,
                    HostSessionV1.VALUE_TOO_LARGE, "OVERSIZE_VALUE");
        }
        return null;
    }

    private String hostReservedKey(ExecutionRuntime runtime) {
        try (LoadedProgram program = load(runtime, TckProgram.RESERVED_KEY)) {
            aborted(applyOnce(program, new byte[0], EMPTY).result(), HostAbort.Kind.ABI_VIOLATION,
                    HostSessionV1.RESERVED_KEY, "RESERVED_KEY");
        }
        return null;
    }

    private String hostReadOnly(ExecutionRuntime runtime) {
        try (LoadedProgram program = load(runtime, TckProgram.WRITE_IN_VALIDATE)) {
            Invocation invocation = once(program, AbiV1.VALIDATE, TckInputs.validate(3, utf8("a")),
                    HostSessionV1.Mode.READ_ONLY, EMPTY, compute, DEFAULT_HOST);
            aborted(invocation.result(), HostAbort.Kind.ABI_VIOLATION, HostSessionV1.READ_ONLY, "WRITE_IN_VALIDATE");
        }
        return null;
    }

    private String recursion(ExecutionRuntime runtime) throws Exception {
        try (LoadedProgram program = load(runtime, TckProgram.DEEP_RECURSION)) {
            List<String> outcomes = new ArrayList<>();
            for (long stackSize : STACK_SIZES) {
                AtomicReference<String> outcome = new AtomicReference<>();
                Thread thread = new Thread(null, () -> {
                    try {
                        Invocation invocation = once(program, AbiV1.APPLY_MESSAGE, applyMessage(1, 0, new byte[0]),
                                HostSessionV1.Mode.TRANSITION, EMPTY, compute, DEFAULT_HOST);
                        outcome.set(describe(invocation.result()));
                    } catch (ExecutionEngineException e) {
                        outcome.set("ExecutionEngineException");
                    } catch (Throwable e) {
                        outcome.set("unexpected " + e.getClass().getSimpleName());
                    }
                }, "tck-recursion-" + stackSize, stackSize);
                thread.start();
                thread.join();
                outcomes.add(outcome.get());
            }
            expect(outcomes.stream().distinct().count() == 1,
                    "recursion outcome depends on the thread stack size: " + outcomes);
            String outcome = outcomes.getFirst();
            expect(!outcome.startsWith("Completed"), "unbounded recursion completed");
            expect(!outcome.equals("ExecutionEngineException"), "unbounded recursion is an engine failure on every "
                    + "stack size; one message could stop every node. Enforce a deterministic call-depth limit.");
            expect(!outcome.startsWith("unexpected"), "recursion threw " + outcome);
        }
        return null;
    }

    private String memoryBounded(ExecutionRuntime runtime) {
        if (runtime.descriptor().memory() == MemoryModel.NOT_ENFORCED) {
            return "runtime does not bound guest memory deterministically; a guest can exhaust the node heap "
                    + "(compute metering bounds allocation only indirectly)";
        }
        // DETERMINISTIC_LIMIT is a claim: a guest that keeps growing memory must stop at exactly the
        // limit, the same way in every instance.
        long limitPages = MEMORY_LIMIT / (64 * 1024);
        try (LoadedProgram program = load(runtime, TckProgram.MEMORY_HOG)) {
            List<String> outcomes = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Invocation invocation = applyOnce(program, new byte[0], EMPTY);
                ExecutionResult result = invocation.result();
                if (result instanceof ExecutionResult.Trapped t && t.code() == TrapCode.MEMORY_LIMIT) {
                    outcomes.add("memory-limit trap");
                    continue;
                }
                completed(invocation, "MEMORY_HOG");
                byte[] pages = written(invocation.writes(), utf8("m"))
                        .orElseThrow(() -> new TckFailure("MEMORY_HOG did not write m"));
                expect(Arrays.equals(pages, be64(limitPages)), "memory stopped growing at "
                        + HexFormat.of().formatHex(pages) + " pages, the limit of " + MEMORY_LIMIT + " bytes is "
                        + limitPages + " pages");
                outcomes.add("stopped at the limit");
            }
            expect(outcomes.stream().distinct().count() == 1, "memory exhaustion differs between instances: " + outcomes);
        }
        return null;
    }

    private String isolation(ExecutionRuntime runtime) {
        try (LoadedProgram program = load(runtime, TckProgram.STATIC_STATE)) {
            for (int i = 0; i < 2; i++) {
                Invocation fresh = applyOnce(program, new byte[0], EMPTY);
                expect(written(fresh.writes(), utf8("g")).map(v -> Arrays.equals(v, be64(1))).orElse(false),
                        "a new instance must start with fresh guest memory");
            }
            HostSessionV1 first = session(HostSessionV1.Mode.TRANSITION, EMPTY, DEFAULT_HOST);
            try (ProgramInstance instance = program.instantiate(first, compute)) {
                completed(invoke(instance, first, AbiV1.APPLY_MESSAGE, applyMessage(1, 0, new byte[0])), "STATIC_STATE");
                Invocation second = invoke(instance, first, AbiV1.APPLY_MESSAGE, applyMessage(1, 1, new byte[0]));
                completed(second, "STATIC_STATE");
                expect(written(second.writes(), utf8("g")).map(v -> Arrays.equals(v, be64(2))).orElse(false),
                        "guest memory must persist between invocations of one instance");
            }
        }
        return null;
    }

    private String environmentStable(ExecutionRuntime runtime) throws Exception {
        List<String> seen = new ArrayList<>();
        try (LoadedProgram program = load(runtime, TckProgram.ENVIRONMENT_PROBE)) {
            for (int i = 0; i < 2; i++) {
                seen.add(probe(program));
                Thread.sleep(60);
            }
        }
        // A second runtime under another JVM default locale and time zone: nodes differ in both.
        java.util.Locale locale = java.util.Locale.getDefault();
        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
            try (ExecutionRuntime second = factory.open(options); LoadedProgram program = load(second, TckProgram.ENVIRONMENT_PROBE)) {
                seen.add(probe(program));
            }
        } finally {
            java.util.Locale.setDefault(locale);
            java.util.TimeZone.setDefault(zone);
        }
        expect(seen.stream().distinct().count() == 1, "ambient environment differs between instances: " + seen);
        return null;
    }

    private String probe(LoadedProgram program) {
        Invocation invocation = applyOnce(program, new byte[0], EMPTY);
        completed(invocation, "ENVIRONMENT_PROBE");
        return written(invocation.writes(), utf8("env")).map(HexFormat.of()::formatHex)
                .orElseThrow(() -> new TckFailure("ENVIRONMENT_PROBE did not write env"));
    }

    private static List<byte[]> corpus() {
        List<byte[]> bodies = new ArrayList<>();
        Random random = new Random(42);
        for (int i = 0; i < 120; i++) {
            bodies.add(utf8("key-" + random.nextInt(6)));
        }
        return bodies;
    }

    private List<Write> runCorpus(LoadedProgram program, boolean freshInstances) {
        StagedState state = new StagedState(EMPTY);
        List<byte[]> bodies = corpus();
        HostSessionV1[] holder = new HostSessionV1[1];
        // One shared instance needs one HostCalls for its lifetime, so route through a holder.
        ProgramInstance shared = freshInstances ? null
                : program.instantiate((index, args) -> holder[0].call(index, args), compute);
        try {
            for (int i = 0; i < bodies.size(); i++) {
                byte[] input = applyMessage(1 + i / 10, i % 10, bodies.get(i));
                HostSessionV1 session = session(HostSessionV1.Mode.TRANSITION, state, DEFAULT_HOST);
                Invocation invocation;
                if (freshInstances) {
                    try (ProgramInstance instance = program.instantiate(session, compute)) {
                        invocation = invoke(instance, session, AbiV1.APPLY_MESSAGE, input);
                    }
                } else {
                    holder[0] = session;
                    invocation = invoke(shared, session, AbiV1.APPLY_MESSAGE, input);
                }
                transition(invocation, "COUNTER corpus");
                state.apply(invocation.writes());
            }
        } finally {
            if (shared != null) {
                shared.close();
            }
        }
        return state.writes();
    }

    private String determinismCorpus(ExecutionRuntime runtime) {
        List<Write> fresh;
        List<Write> shared;
        try (LoadedProgram program = load(runtime, TckProgram.COUNTER)) {
            fresh = runCorpus(program, true);
            shared = runCorpus(program, false);
        }
        List<Write> reopened;
        try (ExecutionRuntime second = factory.open(options); LoadedProgram program = load(second, TckProgram.COUNTER)) {
            reopened = runCorpus(program, true);
        }
        expect(fresh.size() == 6, "corpus should touch 6 keys, touched " + fresh.size());
        expect(fresh.equals(shared), "fresh-instance and shared-instance runs differ");
        expect(fresh.equals(reopened), "runs on two runtime opens differ");
        return null;
    }

    private String concurrency(ExecutionRuntime runtime) throws Exception {
        try (LoadedProgram program = load(runtime, TckProgram.COUNTER)) {
            List<Write> expected = runCorpus(program, true);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<List<Write>>> tasks = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    boolean freshInstances = i % 2 == 0;
                    tasks.add(() -> runCorpus(program, freshInstances));
                }
                for (Future<List<Write>> future : pool.invokeAll(tasks)) {
                    expect(future.get().equals(expected), "concurrent run differs from the sequential run");
                }
            } finally {
                pool.shutdownNow();
            }
        }
        return null;
    }
}
