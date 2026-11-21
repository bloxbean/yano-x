package org.yanoproject.x.execution.abi;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.execution.abi.v1.AbiV1;
import org.yanoproject.x.execution.abi.v1.EffectIntentArg;
import org.yanoproject.x.execution.abi.v1.EffectLimits;
import org.yanoproject.x.execution.abi.v1.HostCostsV1;
import org.yanoproject.x.execution.abi.v1.HostSessionV1;
import org.yanoproject.x.execution.abi.v1.HostTableV1;
import org.yanoproject.x.execution.abi.v1.Write;
import org.yanoproject.x.framework.api.execution.HostAbort;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HostSessionV1Test {

    private static final EffectLimits EFFECTS = new EffectLimits(true, 16_384, 1000, 1000, 2);

    private static HostSessionV1 session(HostSessionV1.Mode mode, long budget) {
        return new HostSessionV1(mode, key -> key[0] == 'a' ? Optional.of(new byte[] {9}) : Optional.empty(),
                budget, EFFECTS, HostSessionV1.LogSink.DISCARD);
    }

    private static byte[] b(String s) {
        return s.getBytes();
    }

    @Test
    void readYourWritesAndFirstWriteOrder() {
        HostSessionV1 s = session(HostSessionV1.Mode.TRANSITION, 1_000_000);
        s.call(HostTableV1.STATE_PUT, new byte[][] {b("z"), b("1")});
        s.call(HostTableV1.STATE_PUT, new byte[][] {b("a"), b("2")});
        s.call(HostTableV1.STATE_PUT, new byte[][] {b("z"), b("3")});
        s.call(HostTableV1.STATE_DELETE, new byte[][] {b("a")});
        assertArrayEquals(b("3"), s.call(HostTableV1.STATE_GET, new byte[][] {b("z")}));
        assertNull(s.call(HostTableV1.STATE_GET, new byte[][] {b("a")}));
        assertEquals(List.of(new Write(b("z"), b("3")), new Write(b("a"), null)), s.writes());
    }

    @Test
    void chargesAreExact() {
        HostSessionV1 s = session(HostSessionV1.Mode.TRANSITION, 1_000_000);
        s.call(HostTableV1.STATE_GET, new byte[][] {b("ab")}); // found, 1 byte value
        long get = HostCostsV1.CALL + 2 * HostCostsV1.PER_INPUT_BYTE + HostCostsV1.PER_OUTPUT_BYTE;
        assertEquals(get, s.hostFuelUsed());
        s.call(HostTableV1.STATE_PUT, new byte[][] {b("k"), new byte[10]});
        long put = HostCostsV1.CALL + 11 * HostCostsV1.PER_INPUT_BYTE + HostCostsV1.STATE_WRITE
                + 10 * HostCostsV1.PER_VALUE_BYTE_WRITTEN;
        assertEquals(get + put, s.hostFuelUsed());
    }

    @Test
    void fuelExhaustionAbortsBeforeTheCallRuns() {
        long budget = HostCostsV1.CALL + 1; // exactly one state_get("k")
        HostSessionV1 s = session(HostSessionV1.Mode.TRANSITION, budget);
        s.call(HostTableV1.STATE_GET, new byte[][] {b("k")});
        HostAbort abort = assertThrows(HostAbort.class, () -> s.call(HostTableV1.STATE_PUT, new byte[][] {b("k"), b("v")}));
        assertEquals(HostAbort.Kind.HOST_FUEL_EXHAUSTED, abort.kind());
        assertEquals(List.of(), s.writes());
    }

    @Test
    void violations() {
        HostSessionV1 t = session(HostSessionV1.Mode.TRANSITION, 10_000_000);
        assertEquals(HostSessionV1.RESERVED_KEY, code(() -> t.call(HostTableV1.STATE_GET, new byte[][] {b("~fx/a")})));
        assertEquals(HostSessionV1.INVALID_KEY, code(() -> t.call(HostTableV1.STATE_GET, new byte[][] {new byte[0]})));
        assertEquals(HostSessionV1.INVALID_KEY, code(() -> t.call(HostTableV1.STATE_GET, new byte[][] {new byte[1025]})));
        assertEquals(HostSessionV1.VALUE_TOO_LARGE,
                code(() -> t.call(HostTableV1.STATE_PUT, new byte[][] {b("k"), new byte[AbiV1.MAX_VALUE_BYTES + 1]})));
        assertEquals(HostSessionV1.INVALID_EFFECT, code(() -> t.call(HostTableV1.EFFECT_EMIT, new byte[][] {b("x")})));
        assertEquals(HostSessionV1.INVALID_LOG, code(() -> t.call(HostTableV1.LOG, new byte[][] {{7}, b("m")})));

        HostSessionV1 r = session(HostSessionV1.Mode.READ_ONLY, 10_000_000);
        assertEquals(HostSessionV1.READ_ONLY, code(() -> r.call(HostTableV1.STATE_PUT, new byte[][] {b("k"), b("v")})));
        assertEquals(HostSessionV1.READ_ONLY, code(() -> r.call(HostTableV1.STATE_DELETE, new byte[][] {b("k")})));
    }

    @Test
    void effectLimitsFollowTheChain() {
        HostSessionV1 s = session(HostSessionV1.Mode.TRANSITION, 10_000_000);
        byte[] intent = new EffectIntentArg("demo.pay", new byte[3], "", -1, 0, 0).encode();
        s.call(HostTableV1.EFFECT_EMIT, new byte[][] {intent});
        s.call(HostTableV1.EFFECT_EMIT, new byte[][] {intent});
        assertEquals(HostSessionV1.EFFECT_LIMIT, code(() -> s.call(HostTableV1.EFFECT_EMIT, new byte[][] {intent})));
        assertEquals(2, s.effects().size());

        HostSessionV1 disabled = new HostSessionV1(HostSessionV1.Mode.TRANSITION, key -> Optional.empty(), 10_000_000,
                EffectLimits.disabled(), HostSessionV1.LogSink.DISCARD);
        assertEquals(HostSessionV1.EFFECTS_DISABLED, code(() -> disabled.call(HostTableV1.EFFECT_EMIT, new byte[][] {intent})));
    }

    /** The ABI's own caps apply before the chain's limits, whatever the chain allows. */
    @Test
    void effectScopeAndPayloadCapsAreAbiRules() {
        HostSessionV1 roomy = new HostSessionV1(HostSessionV1.Mode.TRANSITION, key -> Optional.empty(), 10_000_000,
                new EffectLimits(true, 1_000_000, 1000, 1000, 10), HostSessionV1.LogSink.DISCARD);
        roomy.call(HostTableV1.EFFECT_EMIT, new byte[][] {rawIntent("s".repeat(AbiV1.MAX_SCOPE_BYTES), AbiV1.MAX_EFFECT_PAYLOAD_BYTES)});
        assertEquals(HostSessionV1.INVALID_EFFECT, code(() -> roomy.call(HostTableV1.EFFECT_EMIT,
                new byte[][] {rawIntent("s".repeat(AbiV1.MAX_SCOPE_BYTES + 1), 1)})));
        assertEquals(HostSessionV1.INVALID_EFFECT, code(() -> roomy.call(HostTableV1.EFFECT_EMIT,
                new byte[][] {rawIntent("s", AbiV1.MAX_EFFECT_PAYLOAD_BYTES + 1)})));

        HostSessionV1 tight = session(HostSessionV1.Mode.TRANSITION, 10_000_000);
        assertEquals(HostSessionV1.EFFECT_LIMIT, code(() -> tight.call(HostTableV1.EFFECT_EMIT,
                new byte[][] {rawIntent("s", 16_385)})));
    }

    private static byte[] rawIntent(String scope, int payloadBytes) {
        java.util.Map<Long, Object> m = new java.util.LinkedHashMap<>();
        m.put(0L, "demo.pay");
        m.put(1L, new byte[payloadBytes]);
        m.put(2L, scope);
        return org.yanoproject.x.execution.abi.cbor.CanonicalCbor.encode(m);
    }

    @Test
    void hashes() {
        HostSessionV1 s = session(HostSessionV1.Mode.READ_ONLY, 10_000_000);
        assertEquals("0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
                HexFormat.of().formatHex(s.call(HostTableV1.BLAKE2B_256, new byte[][] {new byte[0]})));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                HexFormat.of().formatHex(s.call(HostTableV1.SHA2_256, new byte[][] {new byte[0]})));
    }

    private static String code(Runnable call) {
        return assertThrows(HostAbort.class, call::run).code();
    }
}
