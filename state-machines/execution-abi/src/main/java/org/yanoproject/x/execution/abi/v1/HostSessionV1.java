package org.yanoproject.x.execution.abi.v1;

import org.yanoproject.x.execution.abi.cbor.CborException;
import org.yanoproject.x.framework.api.execution.HostAbort;
import org.yanoproject.x.framework.api.execution.HostCalls;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Host functions of ABI v1 for one invocation. It charges host fuel, enforces limits and the
 * entry point's permissions, and stages writes and effects. Nothing reaches the block until the
 * caller has seen the invocation complete and takes {@link #writes()} and {@link #effects()}.
 *
 * <p>The same class serves the adapter and the TCK, so every runtime is tested against the host
 * behaviour it will meet in a node.
 */
public final class HostSessionV1 implements HostCalls {

    /** What the current entry point may do. */
    public enum Mode {
        /** {@code apply_message}, {@code on_effect_result}: reads, writes and effects. */
        TRANSITION,
        /** {@code validate}, {@code query}: reads only. */
        READ_ONLY
    }

    /** Receives guest log lines. Logging never affects results. */
    @FunctionalInterface
    public interface LogSink {
        LogSink DISCARD = (level, message) -> { };

        void log(int level, String message);
    }

    // ABI violation codes.
    public static final String RESERVED_KEY = "reserved-key";
    public static final String INVALID_KEY = "invalid-key";
    public static final String VALUE_TOO_LARGE = "value-too-large";
    public static final String WRITE_LIMIT = "write-limit";
    public static final String READ_ONLY = "read-only";
    public static final String INVALID_EFFECT = "invalid-effect";
    public static final String EFFECTS_DISABLED = "effects-disabled";
    public static final String EFFECT_LIMIT = "effect-limit";
    public static final String INPUT_TOO_LARGE = "input-too-large";
    public static final String INVALID_LOG = "invalid-log";
    public static final String HOST_FUEL = "host-fuel";

    private final Mode mode;
    private final StagedState state;
    private final long hostFuelBudget;
    private final EffectLimits effectLimits;
    private final LogSink logSink;
    private final List<EffectIntentArg> effects = new ArrayList<>();
    private long hostFuelUsed;
    private long calls;

    public HostSessionV1(Mode mode, StateView base, long hostFuelBudget, EffectLimits effectLimits, LogSink logSink) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.state = new StagedState(Objects.requireNonNull(base, "base"));
        this.hostFuelBudget = hostFuelBudget;
        this.effectLimits = Objects.requireNonNull(effectLimits, "effectLimits");
        this.logSink = Objects.requireNonNull(logSink, "logSink");
    }

    @Override
    public byte[] call(int index, byte[][] args) {
        int expected = HostTableV1.FUNCTIONS.get(index).arity();
        if (args.length != expected) {
            // The runtime built the binding from the table; a wrong arity is a runtime bug.
            throw new IllegalArgumentException("host function " + index + " called with " + args.length + " args");
        }
        calls++;
        long inputBytes = 0;
        for (byte[] arg : args) {
            inputBytes += Objects.requireNonNull(arg, "host call argument").length;
        }
        charge(HostCostsV1.CALL + inputBytes * HostCostsV1.PER_INPUT_BYTE);
        return switch (index) {
            case HostTableV1.STATE_GET -> {
                byte[] key = key(args[0]);
                byte[] value = state.get(key).orElse(null);
                if (value != null) {
                    charge(value.length * HostCostsV1.PER_OUTPUT_BYTE);
                }
                yield value;
            }
            case HostTableV1.STATE_PUT -> {
                writable();
                byte[] key = key(args[0]);
                if (args[1].length > AbiV1.MAX_VALUE_BYTES) {
                    throw violation(VALUE_TOO_LARGE, "value of " + args[1].length + " bytes");
                }
                charge(HostCostsV1.STATE_WRITE + args[1].length * HostCostsV1.PER_VALUE_BYTE_WRITTEN);
                checkWriteLimit(key);
                state.put(key, args[1]);
                yield null;
            }
            case HostTableV1.STATE_DELETE -> {
                writable();
                byte[] key = key(args[0]);
                charge(HostCostsV1.STATE_WRITE);
                checkWriteLimit(key);
                state.delete(key);
                yield null;
            }
            case HostTableV1.EFFECT_EMIT -> {
                writable();
                charge(HostCostsV1.EFFECT_EMIT);
                yield emit(args[0]);
            }
            case HostTableV1.BLAKE2B_256, HostTableV1.SHA2_256 -> {
                if (args[0].length > AbiV1.MAX_HASH_INPUT_BYTES) {
                    throw violation(INPUT_TOO_LARGE, "hash input of " + args[0].length + " bytes");
                }
                charge(HostCostsV1.HASH + 32 * HostCostsV1.PER_OUTPUT_BYTE);
                yield index == HostTableV1.BLAKE2B_256 ? Hashes.blake2b256(args[0]) : Hashes.sha256(args[0]);
            }
            case HostTableV1.LOG -> {
                charge(HostCostsV1.LOG);
                if (args[0].length != 1 || args[0][0] < 0 || args[0][0] > 3 || args[1].length > AbiV1.MAX_LOG_BYTES) {
                    throw violation(INVALID_LOG, "log level must be one byte 0..3 and the message at most "
                            + AbiV1.MAX_LOG_BYTES + " bytes");
                }
                logSink.log(args[0][0], utf8(args[1]));
                yield null;
            }
            default -> throw new IllegalArgumentException("unknown host function " + index);
        };
    }

    private byte[] emit(byte[] encoded) {
        EffectIntentArg intent;
        try {
            intent = EffectIntentArg.decode(encoded);
        } catch (CborException | IllegalArgumentException e) {
            throw violation(INVALID_EFFECT, e.getMessage());
        }
        if (!effectLimits.enabled()) {
            throw violation(EFFECTS_DISABLED, "effects are not enabled on this chain");
        }
        if (effects.size() >= AbiV1.MAX_EFFECTS_PER_INVOCATION || effects.size() >= effectLimits.remainingInBlock()) {
            throw violation(EFFECT_LIMIT, "too many effects");
        }
        if (intent.payload().length > effectLimits.maxPayloadBytes()) {
            throw violation(EFFECT_LIMIT, "effect payload over effects.max-payload-bytes");
        }
        if (intent.expiryBlocks() > effectLimits.maxExpiryBlocks()
                || (intent.resultPolicy() == 1 && intent.expiryBlocks() > effectLimits.resultWindowBlocks())) {
            throw violation(EFFECT_LIMIT, "effect expiry over the chain limit");
        }
        effects.add(intent);
        return null;
    }

    private byte[] key(byte[] key) {
        if (key.length == 0 || key.length > AbiV1.MAX_KEY_BYTES) {
            throw violation(INVALID_KEY, "key must be 1.." + AbiV1.MAX_KEY_BYTES + " bytes");
        }
        if (key[0] == AbiV1.RESERVED_KEY_PREFIX) {
            throw violation(RESERVED_KEY, "keys starting with '~' are reserved");
        }
        return key;
    }

    private void writable() {
        if (mode != Mode.TRANSITION) {
            throw violation(READ_ONLY, "this entry point cannot write state or emit effects");
        }
    }

    private void checkWriteLimit(byte[] key) {
        if (!state.touches(key) && state.size() >= AbiV1.MAX_WRITES_PER_INVOCATION) {
            throw violation(WRITE_LIMIT, "more than " + AbiV1.MAX_WRITES_PER_INVOCATION + " keys written");
        }
    }

    private void charge(long units) {
        hostFuelUsed += units;
        if (hostFuelUsed > hostFuelBudget) {
            throw new HostAbort(HostAbort.Kind.HOST_FUEL_EXHAUSTED, HOST_FUEL,
                    "host fuel budget " + hostFuelBudget + " exhausted");
        }
    }

    private static HostAbort violation(String code, String message) {
        return new HostAbort(HostAbort.Kind.ABI_VIOLATION, code, message);
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw violation(INVALID_LOG, "log message is not valid UTF-8");
        }
    }

    /** Staged writes, final value per key in first-write order. */
    public List<Write> writes() {
        return state.writes();
    }

    /** Emitted effects in emission order. */
    public List<EffectIntentArg> effects() {
        return List.copyOf(effects);
    }

    public long hostFuelUsed() {
        return Math.min(hostFuelUsed, hostFuelBudget);
    }

    public long calls() {
        return calls;
    }
}
