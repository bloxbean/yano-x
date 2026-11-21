package org.yanoproject.x.execution.tck;

/**
 * Guest programs a runtime must supply to run the TCK, written in the runtime's guest language
 * against guest ABI v1. The behaviour of each is normative; {@code docs/tck.md} has the same text.
 *
 * <p>Notation: {@code be64(n)} is an 8-byte big-endian unsigned integer; {@code "x" ++ y} is byte
 * concatenation of the ASCII string and {@code y}; {@code body} is the message body of the input.
 */
public enum TckProgram {

    /**
     * Exports {@code apply_message}, {@code validate}, {@code query}.
     * <ul>
     *   <li>{@code apply_message}: {@code k = "c/" ++ body}; {@code n = be64(state_get(k))}, 0 if
     *       absent; {@code state_put(k, be64(n + 1))}; return Ok.</li>
     *   <li>{@code validate}: Reject {@code "empty-body"} if body is empty, otherwise Accept.</li>
     *   <li>{@code query}: path {@code "count"}: Payload {@code be64(n)} for {@code k = "c/" ++ params}
     *       (0 if absent); any other path: Error {@code "unsupported"}.</li>
     * </ul>
     */
    COUNTER,

    /**
     * Exports {@code apply_message}, {@code on_effect_result}.
     * <ul>
     *   <li>{@code apply_message}: {@code effect_emit} of type {@code "tck.echo"}, payload = body,
     *       scope = lowercase hex of the message id, result policy 1, expiry 10; return Ok.</li>
     *   <li>{@code on_effect_result}: {@code state_put("r/" ++ utf8(scope), [outcome])}; return Ok.</li>
     * </ul>
     */
    EFFECTS,

    /** {@code apply_message} returns the message body bytes unchanged as its output. */
    ECHO,

    /**
     * {@code apply_message}: {@code state_put("x", body)}; then if body is non-empty and its first
     * byte is 0, return Rejected {@code "tck.reject"}, otherwise Ok.
     */
    REJECT,

    /**
     * {@code apply_message}: {@code state_put("t", body)}, then stop with an unhandled guest error
     * (a JS {@code throw}, a WASM {@code unreachable}).
     */
    TRAP_AFTER_WRITE,

    /** {@code apply_message}: loop forever, with no host calls. */
    SPIN,

    /** {@code apply_message}: loop forever calling {@code state_get("k")}. */
    HOST_FLOOD,

    /**
     * {@code apply_message}: repeat 100,000 times {@code state_put("k", 1024 zero bytes)}, and in
     * languages that can catch host errors, catch and ignore every error; then return Ok.
     */
    CATCH_HOST_ABORT,

    /** {@code apply_message}: {@code state_put("k", 65,537 zero bytes)}; return Ok. */
    OVERSIZE_VALUE,

    /** {@code apply_message}: {@code state_put("~fx/x", "v")}; return Ok. */
    RESERVED_KEY,

    /** Exports {@code apply_message} (returns Ok) and {@code validate}: {@code state_put("k", "v")}, then Accept. */
    WRITE_IN_VALIDATE,

    /** {@code apply_message}: unbounded non-tail recursion with no host calls. */
    DEEP_RECURSION,

    /** Exports only {@code validate} (Accept). It has no {@code apply_message}. */
    MISSING_EXPORT,

    /**
     * {@code apply_message}: calls a host function named {@code net_fetch} with one argument. In
     * languages with static imports the program imports {@code yano.net_fetch}.
     */
    UNKNOWN_IMPORT,

    /**
     * {@code apply_message}: increments a counter held in guest memory (a global variable). Then,
     * if the body is non-empty and its first byte is 0, returns Rejected {@code "tck.static-reject"};
     * otherwise {@code state_put("g", be64(counter))} and returns Ok. The counter starts at 0 in a
     * new instance.
     */
    STATIC_STATE,

    /**
     * {@code apply_message}: reads every ambient source of nondeterminism the guest language offers
     * (clock, randomness, environment, locale, hash seeds, performance counters), encodes what it
     * observed as bytes, and writes them with {@code state_put("env", bytes)}; return Ok. Where a
     * source is unavailable, the program records that it is unavailable instead of failing.
     */
    ENVIRONMENT_PROBE,

    /**
     * Only for runtimes with {@code MemoryModel.DETERMINISTIC_LIMIT}. {@code apply_message}: grows
     * guest memory until the language reports that it cannot grow (WASM: {@code memory.grow}
     * returns -1, one 64 KiB page at a time), then {@code state_put("m", be64(pages))} with the
     * final memory size in 64 KiB pages; return Ok. A language that cannot observe the failure may
     * instead end with a memory-limit trap.
     */
    MEMORY_HOG
}
