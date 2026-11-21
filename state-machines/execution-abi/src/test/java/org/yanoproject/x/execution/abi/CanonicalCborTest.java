package org.yanoproject.x.execution.abi;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.execution.abi.cbor.CanonicalCbor;
import org.yanoproject.x.execution.abi.cbor.CborException;
import org.yanoproject.x.execution.abi.cbor.CborNull;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalCborTest {

    private static String hex(Object value) {
        return HexFormat.of().formatHex(CanonicalCbor.encode(value));
    }

    @Test
    void integerHeadsAreShortest() {
        assertEquals("17", hex(23L));
        assertEquals("1818", hex(24L));
        assertEquals("18ff", hex(255L));
        assertEquals("190100", hex(256L));
        assertEquals("1a00010000", hex(65_536L));
        assertEquals("1b0000000100000000", hex(4_294_967_296L));
        assertEquals("1b7fffffffffffffff", hex(Long.MAX_VALUE));
        assertEquals("20", hex(-1L));
        assertEquals("3b7fffffffffffffff", hex(Long.MIN_VALUE));
    }

    @Test
    void mapKeysSortByEncodedBytes() {
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put(256L, 1L);
        map.put("a", 2L);
        map.put(10L, 3L);
        map.put(-1L, 4L);
        // 0x0a < 0x190100 < 0x20 < 0x6161: integers by encoding, not by numeric value.
        assertEquals("a40a03190100012004616102", hex(map));
    }

    @Test
    void roundTripsRandomValues() {
        Random random = new Random(1);
        for (int i = 0; i < 2000; i++) {
            Object value = randomValue(random, 0);
            byte[] encoded = CanonicalCbor.encode(value);
            assertArrayEquals(encoded, CanonicalCbor.encode(CanonicalCbor.decode(encoded)));
        }
    }

    private static Object randomValue(Random random, int depth) {
        int kind = random.nextInt(depth > 3 ? 5 : 7);
        return switch (kind) {
            case 0 -> random.nextLong() >> random.nextInt(64);
            case 1 -> {
                byte[] b = new byte[random.nextInt(40)];
                random.nextBytes(b);
                yield b;
            }
            case 2 -> "s" + random.nextInt(1000) + "é中";
            case 3 -> random.nextBoolean();
            case 4 -> CborNull.NULL;
            case 5 -> {
                int n = random.nextInt(5);
                java.util.ArrayList<Object> list = new java.util.ArrayList<>();
                for (int i = 0; i < n; i++) {
                    list.add(randomValue(random, depth + 1));
                }
                yield list;
            }
            default -> {
                Map<Object, Object> map = new LinkedHashMap<>();
                int n = random.nextInt(5);
                for (int i = 0; i < n; i++) {
                    Object key = random.nextBoolean() ? (Object) (long) (random.nextInt(300) - 20) : "k" + random.nextInt(50);
                    map.put(key, randomValue(random, depth + 1));
                }
                yield map;
            }
        };
    }

    private static String rejectCode(String hex) {
        return assertThrows(CborException.class, () -> CanonicalCbor.decode(HexFormat.of().parseHex(hex))).code();
    }

    @Test
    void rejectsEverythingOutsideTheCanonicalSubset() {
        assertEquals(CborException.NON_CANONICAL_INT, rejectCode("1817"));
        assertEquals(CborException.NON_CANONICAL_INT, rejectCode("1900ff"));
        assertEquals(CborException.NON_CANONICAL_INT, rejectCode("5801ff"));
        assertEquals(CborException.INDEFINITE_LENGTH, rejectCode("9fff"));
        assertEquals(CborException.INDEFINITE_LENGTH, rejectCode("5f41ffff"));
        assertEquals(CborException.MAP_KEY_ORDER, rejectCode("a2010000 00".replace(" ", "")));
        assertEquals(CborException.DUPLICATE_KEY, rejectCode("a201000100"));
        assertEquals(CborException.TRAILING_BYTES, rejectCode("0000"));
        assertEquals(CborException.TRUNCATED, rejectCode("43ffff"));
        assertEquals(CborException.UNSUPPORTED_TYPE, rejectCode("f93c00"));
        assertEquals(CborException.UNSUPPORTED_TYPE, rejectCode("f7"));
        assertEquals(CborException.UNSUPPORTED_TYPE, rejectCode("c240"));
        assertEquals(CborException.UNSUPPORTED_TYPE, rejectCode("a14100f6"));
        assertEquals(CborException.INT_OUT_OF_RANGE, rejectCode("1bffffffffffffffff"));
        assertEquals(CborException.INVALID_UTF8, rejectCode("62c328"));
        assertEquals(CborException.DEPTH_LIMIT, rejectCode("81".repeat(20) + "00"));
        assertEquals(CborException.SIZE_LIMIT, rejectCode("9a00ffffff"));
    }

    @Test
    void diagnosticNotationIsStable() {
        assertEquals("{0: 1, 1: h'00ff', 2: [\"a\", true, null]}",
                CanonicalCbor.diagnostic(Map.of(2L, List.of("a", true, CborNull.NULL), 0L, 1L, 1L, new byte[] {0, -1})));
    }
}
