package org.yanoproject.x.execution;

import org.yanoproject.x.execution.abi.v1.AbiV1;
import org.yanoproject.x.execution.abi.v1.EffectIntentArg;
import org.yanoproject.x.execution.abi.v1.EntryInputs;
import org.yanoproject.x.execution.abi.v1.EntryOutputs;
import org.yanoproject.x.execution.abi.v1.ExecutionDescriptor;
import org.yanoproject.x.execution.abi.v1.Hashes;
import org.yanoproject.x.execution.tck.reference.GuestEnv;
import org.yanoproject.x.execution.tck.reference.GuestError;
import org.yanoproject.x.execution.tck.reference.ReferenceGuest;
import org.yanoproject.x.execution.tck.reference.ReferenceRuntimeFactory;
import org.yanoproject.x.framework.api.execution.ExecutionRuntimeResolver;
import org.yanoproject.x.framework.api.execution.ExecutionRuntimeFactory;
import org.yanoproject.x.framework.api.execution.ExecutionRuntime;
import org.yanoproject.x.framework.api.execution.RuntimeOptions;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Reference guests and settings helpers for adapter tests. */
final class TestGuests {

    static final Set<String> IMPORTS = Set.of("state_get", "state_put", "state_delete", "effect_emit", "log");

    /** Per-key counter, the same semantics as the TCK COUNTER: {@code "c/" ++ body -> be64(n + 1)}. */
    static final Supplier<ReferenceGuest> COUNTER = () -> new ReferenceGuest() {
        @Override public Set<String> exports() { return Set.of(AbiV1.APPLY_MESSAGE, AbiV1.VALIDATE, AbiV1.QUERY); }
        @Override public Set<String> imports() { return IMPORTS; }
        @Override public byte[] invoke(String export, byte[] input, GuestEnv env) {
            return switch (export) {
                case AbiV1.APPLY_MESSAGE -> {
                    byte[] key = concat(b("c/"), EntryInputs.ApplyMessage.decode(input).message().body());
                    env.host("state_put", key, be64(be64(env.host("state_get", key)) + 1));
                    yield new EntryOutputs.Transition.Ok().encode();
                }
                case AbiV1.VALIDATE -> EntryInputs.Validate.decode(input).message().body().length == 0
                        ? new EntryOutputs.Verdict.Reject("empty-body").encode() : new EntryOutputs.Verdict.Accept().encode();
                default -> {
                    EntryInputs.Query q = EntryInputs.Query.decode(input);
                    yield q.path().equals("count")
                            ? new EntryOutputs.QueryOutcome.Payload(be64(be64(env.host("state_get", concat(b("c/"), q.params()))))).encode()
                            : q.path().equals("bad") ? new EntryOutputs.QueryOutcome.Error("invalid-request").encode()
                            : new EntryOutputs.QueryOutcome.Error("unsupported").encode();
                }
            };
        }
    };

    /**
     * The first body byte selects a behaviour; every behaviour writes {@code "w/" ++ body} first.
     * {@code r} reject, {@code t} trap, {@code s} spin, {@code f} flood host calls, {@code o} bad
     * output, {@code e} emit an effect, {@code ~} write a reserved key; anything else: Ok.
     */
    static final Supplier<ReferenceGuest> MIXED = () -> new ReferenceGuest() {
        @Override public Set<String> exports() { return Set.of(AbiV1.APPLY_MESSAGE, AbiV1.ON_EFFECT_RESULT); }
        @Override public Set<String> imports() { return IMPORTS; }
        @Override public byte[] invoke(String export, byte[] input, GuestEnv env) {
            if (export.equals(AbiV1.ON_EFFECT_RESULT)) {
                var result = EntryInputs.OnEffectResult.decode(input).result();
                if (result.scope().startsWith("trap")) {
                    throw new GuestError("trap in on_effect_result");
                }
                env.host("state_put", b("fx/" + result.scope()), new byte[] {(byte) result.outcome()});
                return new EntryOutputs.Transition.Ok().encode();
            }
            var message = EntryInputs.ApplyMessage.decode(input).message();
            byte[] body = message.body();
            env.host("state_put", concat(b("w/"), body), b("1"));
            switch (body.length == 0 ? 'k' : (char) body[0]) {
                case 'r' -> {
                    return new EntryOutputs.Transition.Rejected("no-thanks").encode();
                }
                case 't' -> throw new GuestError("boom");
                case 's' -> {
                    while (true) {
                        env.tick(1);
                    }
                }
                case 'f' -> {
                    while (true) {
                        env.host("state_get", b("k"));
                    }
                }
                case 'o' -> {
                    return new byte[] {(byte) 0xA1, 0x00, 0x18, 0x00};
                }
                case 'e' -> env.host("effect_emit", new EffectIntentArg("demo.pay", body, "s-" + new String(body,
                        StandardCharsets.UTF_8), -1, 1, 5).encode());
                case '~' -> env.host("state_put", b("~fx/evil"), b("1"));
                default -> { }
            }
            return new EntryOutputs.Transition.Ok().encode();
        }
    };

    /**
     * The TCK's STATIC_STATE, plus a trap and {@code on_effect_result}: every transition increments a
     * counter in guest memory. A body starting with 0 is rejected, one starting with 1 traps; any
     * other transition writes {@code g = be64(counter)}.
     */
    static final Supplier<ReferenceGuest> STATIC_STATE = () -> new ReferenceGuest() {
        private long counter;

        @Override public Set<String> exports() { return Set.of(AbiV1.APPLY_MESSAGE, AbiV1.ON_EFFECT_RESULT); }
        @Override public Set<String> imports() { return IMPORTS; }
        @Override public byte[] invoke(String export, byte[] input, GuestEnv env) {
            counter++;
            if (export.equals(AbiV1.APPLY_MESSAGE)) {
                byte[] body = EntryInputs.ApplyMessage.decode(input).message().body();
                if (body.length > 0 && body[0] == 0) {
                    return new EntryOutputs.Transition.Rejected("tck.static-reject").encode();
                }
                if (body.length > 0 && body[0] == 1) {
                    throw new GuestError("trap");
                }
            }
            env.host("state_put", b("g"), ByteBuffer.allocate(8).putLong(counter).array());
            return new EntryOutputs.Transition.Ok().encode();
        }
    };

    static ReferenceRuntimeFactory factory() {
        Map<String, Supplier<ReferenceGuest>> programs = new HashMap<>();
        programs.put("counter", COUNTER);
        programs.put("mixed", MIXED);
        programs.put("static-state", STATIC_STATE);
        return new ReferenceRuntimeFactory(programs);
    }

    static final ExecutionDescriptor.Budgets BUDGETS = new ExecutionDescriptor.Budgets(100_000, 50_000, 10_000, 10_000, 10_000, 10_000);

    static byte[] fingerprint(ReferenceRuntimeFactory factory) {
        try (ExecutionRuntime runtime = factory.open(RuntimeOptions.none())) {
            return ExecutionDescriptor.fingerprint(runtime.descriptor());
        }
    }

    static ExecutionDescriptor descriptor(String program, byte[] fingerprint, boolean receipts) {
        return new ExecutionDescriptor(ReferenceRuntimeFactory.RUNTIME_ID, fingerprint, ReferenceRuntimeFactory.MEDIA_TYPE,
                Hashes.blake2b256(b(program)), 1, BUDGETS, 0, new byte[0], receipts);
    }

    static Map<String, String> settings(ExecutionDescriptor descriptor, String program, boolean effects) {
        Map<String, String> settings = new HashMap<>();
        settings.put(ExecutionSettings.PREFIX + "descriptor-cbor-hex", HexFormat.of().formatHex(descriptor.encode()));
        settings.put(ExecutionSettings.PREFIX + "program-hex", HexFormat.of().formatHex(b(program)));
        settings.put("effects.enabled", Boolean.toString(effects));
        return settings;
    }

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static long be64(byte[] v) {
        return v == null ? 0 : ByteBuffer.wrap(v).getLong();
    }

    static byte[] be64(long n) {
        return ByteBuffer.allocate(8).putLong(n).array();
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private TestGuests() {
    }

    /** A resolver that knows only {@code factory}, failing like the host's catalog resolver otherwise. */
    static ExecutionRuntimeResolver resolver(ExecutionRuntimeFactory factory) {
        return id -> {
            if (!id.equals(factory.runtimeId())) {
                throw new IllegalStateException("Execution runtime '" + id + "' is not installed on this node");
            }
            return factory;
        };
    }
}
