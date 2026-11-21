package org.yanoproject.x.execution.abi.cbor;

import java.util.Objects;

/** Input is not canonical CBOR in the ABI subset. {@link #code()} is a stable reason code. */
public final class CborException extends RuntimeException {

    public static final String TRUNCATED = "truncated";
    public static final String TRAILING_BYTES = "trailing-bytes";
    public static final String NON_CANONICAL_INT = "non-canonical-int";
    public static final String INDEFINITE_LENGTH = "indefinite-length";
    public static final String UNSUPPORTED_TYPE = "unsupported-type";
    public static final String INT_OUT_OF_RANGE = "int-out-of-range";
    public static final String INVALID_UTF8 = "invalid-utf8";
    public static final String MAP_KEY_ORDER = "map-key-order";
    public static final String DUPLICATE_KEY = "duplicate-key";
    public static final String DEPTH_LIMIT = "depth-limit";
    public static final String SIZE_LIMIT = "size-limit";
    public static final String SCHEMA = "schema";

    private final String code;

    public CborException(String code, String message) {
        super(message, null, false, false);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
