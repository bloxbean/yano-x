package org.yanoproject.x.execution.tck.reference;

import org.yanoproject.x.execution.abi.v1.AbiV1;
import org.yanoproject.x.execution.abi.v1.EffectIntentArg;
import org.yanoproject.x.execution.abi.v1.EntryInputs;
import org.yanoproject.x.execution.abi.v1.EntryOutputs;
import org.yanoproject.x.execution.tck.TckProgram;
import org.yanoproject.x.execution.tck.TckPrograms;
import org.yanoproject.x.framework.api.execution.ProgramArtifact;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The TCK programs written as reference-runtime Java guests: the reference implementation that the
 * TCK's own tests, the execution adapter's tests and the catalog conformance fixture run.
 */
public final class ReferenceTckPrograms implements TckPrograms {

    @FunctionalInterface
    interface Body {
        byte[] invoke(String export, byte[] input, GuestEnv env);
    }

    private static final Set<String> STANDARD_IMPORTS =
            Set.of("state_get", "state_put", "state_delete", "effect_emit", "blake2b_256", "sha2_256", "log");

    private static Supplier<ReferenceGuest> guest(Set<String> exports, Set<String> imports, Body body) {
        return () -> new ReferenceGuest() {
            @Override public Set<String> exports() { return exports; }
            @Override public Set<String> imports() { return imports; }
            @Override public byte[] invoke(String export, byte[] input, GuestEnv env) { return body.invoke(export, input, env); }
        };
    }

    private static Supplier<ReferenceGuest> applyOnly(Body body) {
        return guest(Set.of(AbiV1.APPLY_MESSAGE), STANDARD_IMPORTS, body);
    }

    private static final Map<TckProgram, Supplier<ReferenceGuest>> GUESTS = new EnumMap<>(TckProgram.class);

    static {
        GUESTS.put(TckProgram.COUNTER, guest(Set.of(AbiV1.APPLY_MESSAGE, AbiV1.VALIDATE, AbiV1.QUERY), STANDARD_IMPORTS,
                (export, input, env) -> switch (export) {
                    case AbiV1.APPLY_MESSAGE -> {
                        byte[] body = EntryInputs.ApplyMessage.decode(input).message().body();
                        byte[] key = concat(ascii("c/"), body);
                        long n = be64(env.host("state_get", key));
                        env.tick(10);
                        env.host("state_put", key, be64(n + 1));
                        yield ok();
                    }
                    case AbiV1.VALIDATE -> EntryInputs.Validate.decode(input).message().body().length == 0
                            ? new EntryOutputs.Verdict.Reject("empty-body").encode()
                            : new EntryOutputs.Verdict.Accept().encode();
                    case AbiV1.QUERY -> {
                        EntryInputs.Query query = EntryInputs.Query.decode(input);
                        yield query.path().equals("count")
                                ? new EntryOutputs.QueryOutcome.Payload(be64(be64(
                                        env.host("state_get", concat(ascii("c/"), query.params()))))).encode()
                                : new EntryOutputs.QueryOutcome.Error("unsupported").encode();
                    }
                    default -> throw new GuestError("unexpected export " + export);
                }));
        GUESTS.put(TckProgram.EFFECTS, guest(Set.of(AbiV1.APPLY_MESSAGE, AbiV1.ON_EFFECT_RESULT), STANDARD_IMPORTS,
                (export, input, env) -> {
                    if (export.equals(AbiV1.APPLY_MESSAGE)) {
                        var message = EntryInputs.ApplyMessage.decode(input).message();
                        String scope = HexFormat.of().formatHex(message.messageId());
                        env.host("effect_emit", new EffectIntentArg("tck.echo", message.body(), scope, -1, 1, 10).encode());
                    } else {
                        var result = EntryInputs.OnEffectResult.decode(input).result();
                        env.host("state_put", ascii("r/" + result.scope()), new byte[] {(byte) result.outcome()});
                    }
                    return ok();
                }));
        GUESTS.put(TckProgram.ECHO, applyOnly((export, input, env) -> EntryInputs.ApplyMessage.decode(input).message().body()));
        GUESTS.put(TckProgram.REJECT, applyOnly((export, input, env) -> {
            byte[] body = EntryInputs.ApplyMessage.decode(input).message().body();
            env.host("state_put", ascii("x"), body);
            return body.length > 0 && body[0] == 0 ? new EntryOutputs.Transition.Rejected("tck.reject").encode() : ok();
        }));
        GUESTS.put(TckProgram.TRAP_AFTER_WRITE, applyOnly((export, input, env) -> {
            env.host("state_put", ascii("t"), EntryInputs.ApplyMessage.decode(input).message().body());
            throw new GuestError("trap after write");
        }));
        GUESTS.put(TckProgram.SPIN, applyOnly((export, input, env) -> {
            while (true) {
                env.tick(1);
            }
        }));
        GUESTS.put(TckProgram.HOST_FLOOD, applyOnly((export, input, env) -> {
            while (true) {
                env.host("state_get", ascii("k"));
            }
        }));
        GUESTS.put(TckProgram.CATCH_HOST_ABORT, applyOnly((export, input, env) -> {
            for (int i = 0; i < 100_000; i++) {
                try {
                    env.host("state_put", ascii("k"), new byte[1024]);
                } catch (RuntimeException swallowed) {
                    // A hostile guest ignores every host error.
                }
            }
            return ok();
        }));
        GUESTS.put(TckProgram.OVERSIZE_VALUE, applyOnly((export, input, env) -> {
            env.host("state_put", ascii("k"), new byte[AbiV1.MAX_VALUE_BYTES + 1]);
            return ok();
        }));
        GUESTS.put(TckProgram.RESERVED_KEY, applyOnly((export, input, env) -> {
            env.host("state_put", ascii("~fx/x"), ascii("v"));
            return ok();
        }));
        GUESTS.put(TckProgram.WRITE_IN_VALIDATE, guest(Set.of(AbiV1.APPLY_MESSAGE, AbiV1.VALIDATE), STANDARD_IMPORTS,
                (export, input, env) -> {
                    if (export.equals(AbiV1.VALIDATE)) {
                        env.host("state_put", ascii("k"), ascii("v"));
                        return new EntryOutputs.Verdict.Accept().encode();
                    }
                    return ok();
                }));
        GUESTS.put(TckProgram.DEEP_RECURSION, applyOnly((export, input, env) -> {
            recurse(env);
            return ok();
        }));
        GUESTS.put(TckProgram.MISSING_EXPORT, guest(Set.of(AbiV1.VALIDATE), STANDARD_IMPORTS,
                (export, input, env) -> new EntryOutputs.Verdict.Accept().encode()));
        GUESTS.put(TckProgram.UNKNOWN_IMPORT, guest(Set.of(AbiV1.APPLY_MESSAGE), Set.of("net_fetch"),
                (export, input, env) -> {
                    env.host("net_fetch", ascii("https://example.org"));
                    return ok();
                }));
        GUESTS.put(TckProgram.STATIC_STATE, () -> {
            long[] counter = {0};
            return guest(Set.of(AbiV1.APPLY_MESSAGE), STANDARD_IMPORTS, (export, input, env) -> {
                counter[0]++;
                byte[] body = EntryInputs.ApplyMessage.decode(input).message().body();
                if (body.length > 0 && body[0] == 0) {
                    return new EntryOutputs.Transition.Rejected("tck.static-reject").encode();
                }
                env.host("state_put", ascii("g"), be64(counter[0]));
                return ok();
            }).get();
        });
        // Reference guests are trusted Java code that promises not to touch ambient state.
        GUESTS.put(TckProgram.ENVIRONMENT_PROBE, applyOnly((export, input, env) -> {
            env.host("state_put", ascii("env"), ascii("clock=unavailable;random=unavailable;env=unavailable"));
            return ok();
        }));
    }

    private static void recurse(GuestEnv env) {
        env.enter();
        env.tick(1);
        recurse(env);
        env.exit();
    }

    /** Every TCK program's reference guest; a copy that callers may change. */
    public static Map<TckProgram, Supplier<ReferenceGuest>> guests() {
        return new EnumMap<>(GUESTS);
    }

    /** A reference runtime factory that knows every TCK program by its name. */
    public static ReferenceRuntimeFactory factory() {
        Map<String, Supplier<ReferenceGuest>> byName = new java.util.HashMap<>();
        GUESTS.forEach((program, guest) -> byName.put(program.name(), guest));
        return new ReferenceRuntimeFactory(byName);
    }

    @Override
    public ProgramArtifact artifact(TckProgram program) {
        return new ProgramArtifact(ReferenceRuntimeFactory.MEDIA_TYPE, 1, program.name().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] ok() {
        return new EntryOutputs.Transition.Ok().encode();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static long be64(byte[] value) {
        return value == null ? 0 : ByteBuffer.wrap(value).getLong();
    }

    private static byte[] be64(long n) {
        return ByteBuffer.allocate(8).putLong(n).array();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
