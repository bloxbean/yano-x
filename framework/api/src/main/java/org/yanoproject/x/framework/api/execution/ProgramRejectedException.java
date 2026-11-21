package org.yanoproject.x.framework.api.execution;

import java.util.Objects;

/**
 * A program cannot be loaded. The {@link #code()} is deterministic for a given artifact and engine
 * fingerprint; the message is diagnostic text.
 */
public class ProgramRejectedException extends Exception {

    /** Standard rejection codes. Runtimes may add codes with their own runtime-id prefix. */
    public static final String MALFORMED = "malformed";
    public static final String UNSUPPORTED_ABI = "unsupported-abi";
    public static final String UNSUPPORTED_MEDIA_TYPE = "unsupported-media-type";
    public static final String FORBIDDEN_FEATURE = "forbidden-feature";
    public static final String UNKNOWN_IMPORT = "unknown-import";
    public static final String MISSING_EXPORT = "missing-export";
    public static final String TOO_LARGE = "too-large";

    private final String code;

    public ProgramRejectedException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ProgramRejectedException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
