package org.yanoproject.x.execution.abi.v1;

import org.yanoproject.x.execution.abi.cbor.CanonicalCbor;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** Inputs of the four ABI v1 entry points. Every input map starts with key 0 = ABI version. */
public final class EntryInputs {

    private EntryInputs() {
    }

    private static void version(Fields f, String type) {
        if (f.uint(0) != AbiV1.VERSION) {
            throw Fields.schema(type, "abi version must be " + AbiV1.VERSION);
        }
    }

    /** Input of {@code apply_message}. */
    public record ApplyMessage(BlockInfo block, MessageInput message, byte[] config) {
        public ApplyMessage {
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(message, "message");
            config = config.clone();
        }

        public byte[] encode() {
            return CanonicalCbor.encode(Map.of(0L, (long) AbiV1.VERSION, 1L, block.toCbor(),
                    2L, message.toCbor(), 3L, config.clone()));
        }

        public static ApplyMessage decode(byte[] bytes) {
            Fields f = Fields.decode("apply-message-input", bytes, Fields.keys(0, 1, 2, 3));
            version(f, "apply-message-input");
            return new ApplyMessage(BlockInfo.fromCbor(f.raw(1)), MessageInput.fromCbor(f.raw(2)), f.bytes(3));
        }

        @Override public byte[] config() { return config.clone(); }

        @Override public boolean equals(Object o) {
            return o instanceof ApplyMessage a && block.equals(a.block) && message.equals(a.message)
                    && Arrays.equals(config, a.config);
        }

        @Override public int hashCode() { return Objects.hash(block, message); }
    }

    /** Input of {@code on_effect_result}. */
    public record OnEffectResult(BlockInfo block, EffectResultInput result, byte[] config) {
        public OnEffectResult {
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(result, "result");
            config = config.clone();
        }

        public byte[] encode() {
            return CanonicalCbor.encode(Map.of(0L, (long) AbiV1.VERSION, 1L, block.toCbor(),
                    2L, result.toCbor(), 3L, config.clone()));
        }

        public static OnEffectResult decode(byte[] bytes) {
            Fields f = Fields.decode("on-effect-result-input", bytes, Fields.keys(0, 1, 2, 3));
            version(f, "on-effect-result-input");
            return new OnEffectResult(BlockInfo.fromCbor(f.raw(1)), EffectResultInput.fromCbor(f.raw(2)), f.bytes(3));
        }

        @Override public byte[] config() { return config.clone(); }

        @Override public boolean equals(Object o) {
            return o instanceof OnEffectResult a && block.equals(a.block) && result.equals(a.result)
                    && Arrays.equals(config, a.config);
        }

        @Override public int hashCode() { return Objects.hash(block, result); }
    }

    /** Input of {@code validate}: advisory admission against committed state at {@code candidateHeight - 1}. */
    public record Validate(String chainId, long candidateHeight, MessageInput message, byte[] config) {
        public Validate {
            Objects.requireNonNull(chainId, "chainId");
            Objects.requireNonNull(message, "message");
            config = config.clone();
            if (candidateHeight < 1) {
                throw new IllegalArgumentException("candidateHeight must be >= 1");
            }
        }

        public byte[] encode() {
            return CanonicalCbor.encode(Map.of(0L, (long) AbiV1.VERSION, 1L, chainId, 2L, candidateHeight,
                    3L, message.toCbor(), 4L, config.clone()));
        }

        public static Validate decode(byte[] bytes) {
            Fields f = Fields.decode("validate-input", bytes, Fields.keys(0, 1, 2, 3, 4));
            version(f, "validate-input");
            return new Validate(f.text(1), f.uint(2), MessageInput.fromCbor(f.raw(3)), f.bytes(4));
        }

        @Override public byte[] config() { return config.clone(); }

        @Override public boolean equals(Object o) {
            return o instanceof Validate v && chainId.equals(v.chainId) && candidateHeight == v.candidateHeight
                    && message.equals(v.message) && Arrays.equals(config, v.config);
        }

        @Override public int hashCode() { return Objects.hash(chainId, candidateHeight, message); }
    }

    /** Input of {@code query}: a read of committed state at {@code committedHeight}. */
    public record Query(String chainId, long committedHeight, String path, byte[] params, byte[] config) {
        public Query {
            Objects.requireNonNull(chainId, "chainId");
            Objects.requireNonNull(path, "path");
            params = params.clone();
            config = config.clone();
            if (committedHeight < 0) {
                throw new IllegalArgumentException("committedHeight must be >= 0");
            }
        }

        public byte[] encode() {
            return CanonicalCbor.encode(Map.of(0L, (long) AbiV1.VERSION, 1L, chainId, 2L, committedHeight,
                    3L, path, 4L, params.clone(), 5L, config.clone()));
        }

        public static Query decode(byte[] bytes) {
            Fields f = Fields.decode("query-input", bytes, Fields.keys(0, 1, 2, 3, 4, 5));
            version(f, "query-input");
            return new Query(f.text(1), f.uint(2), f.text(3), f.bytes(4), f.bytes(5));
        }

        @Override public byte[] params() { return params.clone(); }
        @Override public byte[] config() { return config.clone(); }

        @Override public boolean equals(Object o) {
            return o instanceof Query q && chainId.equals(q.chainId) && committedHeight == q.committedHeight
                    && path.equals(q.path) && Arrays.equals(params, q.params) && Arrays.equals(config, q.config);
        }

        @Override public int hashCode() { return Objects.hash(chainId, committedHeight, path); }
    }
}
