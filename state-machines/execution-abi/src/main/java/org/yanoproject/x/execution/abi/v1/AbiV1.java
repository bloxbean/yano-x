package org.yanoproject.x.execution.abi.v1;

import java.util.Set;

/**
 * Constants of guest ABI v1. The normative text is {@code docs/guest-abi-v1.md}; the CDDL is
 * {@code spec/guest-abi-v1.cddl}. Changing any value here is an ABI change.
 */
public final class AbiV1 {

    public static final int VERSION = 1;

    // Entry points.
    public static final String APPLY_MESSAGE = "apply_message";
    public static final String ON_EFFECT_RESULT = "on_effect_result";
    public static final String VALIDATE = "validate";
    public static final String QUERY = "query";
    public static final Set<String> REQUIRED_EXPORTS = Set.of(APPLY_MESSAGE);
    public static final Set<String> OPTIONAL_EXPORTS = Set.of(ON_EFFECT_RESULT, VALIDATE, QUERY);

    // Program media types.
    public static final String MEDIA_JAVASCRIPT = "text/javascript";
    public static final String MEDIA_WASM = "application/wasm";

    // Limits (bytes unless stated).
    public static final int MAX_KEY_BYTES = 1024;
    public static final int MAX_VALUE_BYTES = 65_536;
    /** Distinct keys one invocation may write or delete. */
    public static final int MAX_WRITES_PER_INVOCATION = 4096;
    public static final int MAX_EFFECTS_PER_INVOCATION = 64;
    public static final int MAX_EFFECT_PAYLOAD_BYTES = 65_536;
    public static final int MAX_EFFECT_TYPE_CHARS = 128;
    public static final int MAX_SCOPE_BYTES = 256;
    public static final int MAX_TRANSITION_OUTPUT_BYTES = 4096;
    public static final int MAX_QUERY_OUTPUT_BYTES = 1_048_576;
    public static final int MAX_HASH_INPUT_BYTES = 1_048_576;
    public static final int MAX_LOG_BYTES = 1024;
    public static final int MAX_CODE_CHARS = 64;
    /** Keys starting with this byte ({@code '~'}) belong to the framework and the adapter. */
    public static final byte RESERVED_KEY_PREFIX = 0x7E;

    private AbiV1() {
    }

    /** Rejection and error code grammar: {@code [a-z0-9][a-z0-9._-]{0,63}}. */
    public static boolean isCode(String code) {
        return code != null && code.matches("[a-z0-9][a-z0-9._-]{0," + (MAX_CODE_CHARS - 1) + "}");
    }

    /** Effect type grammar, the same rule Yano applies to executor declarations. */
    public static boolean isEffectType(String type) {
        if (type == null || type.isEmpty() || type.length() > MAX_EFFECT_TYPE_CHARS || !asciiLetter(type.charAt(0))) {
            return false;
        }
        for (int i = 1; i < type.length(); i++) {
            char c = type.charAt(i);
            if (!asciiLetter(c) && !(c >= '0' && c <= '9') && c != '.' && c != '_' && c != '-' && c != ':' && c != '+') {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiLetter(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
    }
}
