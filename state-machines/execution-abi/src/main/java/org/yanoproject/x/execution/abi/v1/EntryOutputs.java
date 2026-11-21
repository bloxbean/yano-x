package org.yanoproject.x.execution.abi.v1;

import org.yanoproject.x.execution.abi.cbor.CanonicalCbor;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Outputs of the ABI v1 entry points. Each is a map whose key 0 is a status: 0 for success, 1 for
 * a guest-chosen refusal with a code. State changes and effects are not in the output; they are
 * made through host functions during the call.
 */
public final class EntryOutputs {

    private EntryOutputs() {
    }

    /** Output of {@code apply_message} and {@code on_effect_result}. */
    public sealed interface Transition permits Transition.Ok, Transition.Rejected {
        record Ok() implements Transition {
        }

        /** The guest refuses the message. The host discards its writes and effects. */
        record Rejected(String code) implements Transition {
            public Rejected {
                Fields.code("transition-outcome", code);
            }
        }

        default byte[] encode() {
            return switch (this) {
                case Ok ok -> CanonicalCbor.encode(Map.of(0L, 0L));
                case Rejected r -> CanonicalCbor.encode(Map.of(0L, 1L, 1L, r.code()));
            };
        }

        static Transition decode(byte[] bytes) {
            Fields f = Fields.decode("transition-outcome", bytes, Fields.keys(0, 1));
            long status = f.uint(0);
            if (status == 0 && !f.has(1)) {
                return new Ok();
            }
            if (status == 1) {
                return new Rejected(Fields.code("transition-outcome", f.text(1)));
            }
            throw Fields.schema("transition-outcome", "invalid status " + status);
        }
    }

    /** Output of {@code validate}. */
    public sealed interface Verdict permits Verdict.Accept, Verdict.Reject {
        record Accept() implements Verdict {
        }

        record Reject(String code) implements Verdict {
            public Reject {
                Fields.code("verdict", code);
            }
        }

        default byte[] encode() {
            return switch (this) {
                case Accept a -> CanonicalCbor.encode(Map.of(0L, 0L));
                case Reject r -> CanonicalCbor.encode(Map.of(0L, 1L, 1L, r.code()));
            };
        }

        static Verdict decode(byte[] bytes) {
            Fields f = Fields.decode("verdict", bytes, Fields.keys(0, 1));
            long status = f.uint(0);
            if (status == 0 && !f.has(1)) {
                return new Accept();
            }
            if (status == 1) {
                return new Reject(Fields.code("verdict", f.text(1)));
            }
            throw Fields.schema("verdict", "invalid status " + status);
        }
    }

    /** Output of {@code query}. */
    public sealed interface QueryOutcome permits QueryOutcome.Payload, QueryOutcome.Error {
        String UNSUPPORTED = "unsupported";
        String INVALID_REQUEST = "invalid-request";

        record Payload(byte[] bytes) implements QueryOutcome {
            public Payload {
                bytes = bytes.clone();
            }

            @Override public byte[] bytes() { return bytes.clone(); }
            @Override public boolean equals(Object o) { return o instanceof Payload p && Arrays.equals(bytes, p.bytes); }
            @Override public int hashCode() { return Arrays.hashCode(bytes); }
        }

        /** Only {@link #UNSUPPORTED} and {@link #INVALID_REQUEST} are allowed, matching Yano's query contract. */
        record Error(String code) implements QueryOutcome {
            public Error {
                Objects.requireNonNull(code, "code");
                if (!code.equals(UNSUPPORTED) && !code.equals(INVALID_REQUEST)) {
                    throw Fields.schema("query-outcome", "error code must be unsupported or invalid-request");
                }
            }
        }

        default byte[] encode() {
            return switch (this) {
                case Payload p -> CanonicalCbor.encode(Map.of(0L, 0L, 1L, p.bytes()));
                case Error e -> CanonicalCbor.encode(Map.of(0L, 1L, 1L, e.code()));
            };
        }

        static QueryOutcome decode(byte[] bytes) {
            Fields f = Fields.decode("query-outcome", bytes, Fields.keys(0, 1));
            long status = f.uint(0);
            if (status == 0) {
                return new Payload(f.bytes(1));
            }
            if (status == 1) {
                return new Error(f.text(1));
            }
            throw Fields.schema("query-outcome", "invalid status " + status);
        }
    }

    public static Transition decodeTransition(byte[] bytes) {
        return Transition.decode(bytes);
    }

    public static Verdict decodeVerdict(byte[] bytes) {
        return Verdict.decode(bytes);
    }

    public static QueryOutcome decodeQuery(byte[] bytes) {
        return QueryOutcome.decode(bytes);
    }
}
