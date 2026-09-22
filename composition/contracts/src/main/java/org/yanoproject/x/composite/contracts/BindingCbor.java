package org.yanoproject.x.composite.contracts;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict canonical CBOR codec for the binding contracts, not a general-purpose application serializer.
 * Supports signed 64-bit integers, valid UTF-8 text, bytes, booleans, null, arrays, and text-keyed maps.
 * Tags, floating-point values, indefinite-length containers, duplicate/noncanonical map keys, and trailing
 * roots are rejected. Map keys are ordered by encoded text length and then unsigned UTF-8 bytes.
 */
public final class BindingCbor {
    private BindingCbor() { }

    /**
     * Encodes a supported value tree using definite lengths and canonical map ordering.
     * Callers constructing trees must enforce their contract's size/depth limits; this method does not
     * apply a document-specific output cap. Untrusted wire input must first pass through {@link #decode}.
     *
     * @throws IllegalArgumentException for unsupported values or invalid Unicode text
     */
    public static byte[] encode(Object value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            new CborEncoder(bytes).encode(item(value));
            return bytes.toByteArray();
        } catch (CborException failure) {
            throw new IllegalArgumentException("invalid binding CBOR", failure);
        }
    }

    /**
     * Preflights size, nesting and item count before recursive decoding, then verifies byte-exact re-encoding.
     * Integer values are returned as {@link Long}; containers preserve decoded order.
     *
     * @param encoded exactly one canonical CBOR value
     * @param maximumBytes caller-selected byte cap for the enclosing contract
     * @return the decoded scalar/container tree
     * @throws IllegalArgumentException for malformed, unsupported, noncanonical, or oversized input
     */
    public static Object decode(byte[] encoded, int maximumBytes) {
        if (encoded == null || encoded.length == 0 || encoded.length > maximumBytes) throw invalid();
        new Scanner(encoded).validate();
        try {
            List<DataItem> roots = new CborDecoder(new ByteArrayInputStream(encoded)).decode();
            if (roots.size() != 1) throw invalid();
            Object result = value(roots.getFirst());
            if (!Arrays.equals(encoded, encode(result))) throw invalid();
            return result;
        } catch (CborException | ArithmeticException failure) {
            throw new IllegalArgumentException("invalid binding CBOR", failure);
        }
    }

    public static List<?> array(Object value, int size) {
        if (!(value instanceof List<?> list) || list.size() != size) throw invalid();
        return list;
    }

    public static long integer(Object value) {
        if (!(value instanceof Long number)) throw invalid();
        return number;
    }

    public static String text(Object value) {
        if (!(value instanceof String text)) throw invalid();
        return text;
    }

    private static DataItem item(Object value) {
        if (value == null) return SimpleValue.NULL;
        if (value instanceof byte[] bytes) return new ByteString(bytes);
        if (value instanceof String text) {
            if (!text.equals(new String(text.getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8))) throw invalid();
            return new UnicodeString(text);
        }
        if (value instanceof Boolean bool) return bool ? SimpleValue.TRUE : SimpleValue.FALSE;
        if (value instanceof Long || value instanceof Integer) {
            long number = ((Number) value).longValue();
            return number >= 0 ? new UnsignedInteger(number) : new NegativeInteger(number);
        }
        if (value instanceof List<?> list) {
            Array array = new Array();
            list.forEach(element -> array.add(item(element)));
            return array;
        }
        if (value instanceof Map<?, ?> fields) {
            var map = new co.nstant.in.cbor.model.Map();
            fields.keySet().stream().map(BindingCbor::text).sorted((left, right) -> {
                byte[] a = left.getBytes(StandardCharsets.UTF_8);
                byte[] b = right.getBytes(StandardCharsets.UTF_8);
                return a.length != b.length ? Integer.compare(a.length, b.length) : Arrays.compareUnsigned(a, b);
            }).forEach(key -> map.put(item(key), item(fields.get(key))));
            return map;
        }
        throw invalid();
    }

    private static Object value(DataItem item) {
        if (item.hasTag()) throw invalid();
        if (item instanceof ByteString bytes) return bytes.getBytes().clone();
        if (item instanceof UnicodeString text) return text.getString();
        if (item instanceof UnsignedInteger number) return number.getValue().longValueExact();
        if (item instanceof NegativeInteger number) return number.getValue().longValueExact();
        if (item instanceof Array array) {
            List<Object> result = new ArrayList<>();
            array.getDataItems().forEach(child -> result.add(value(child)));
            return result;
        }
        if (item instanceof co.nstant.in.cbor.model.Map map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.getKeys().forEach(key -> result.put(text(value(key)), value(map.get(key))));
            return result;
        }
        if (SimpleValue.TRUE.equals(item)) return true;
        if (SimpleValue.FALSE.equals(item)) return false;
        if (SimpleValue.NULL.equals(item)) return null;
        throw invalid();
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid binding CBOR"); }

    /** Preflight before entering the library's recursive decoder. */
    private static final class Scanner {
        private final byte[] bytes;
        private int offset;
        private int items;
        Scanner(byte[] bytes) { this.bytes = bytes; }
        void validate() {
            scan(0);
            if (offset != bytes.length) throw invalid();
        }
        void scan(int depth) {
            // Every item consumes at least one wire byte. Match the enclosing 64-KiB wire contracts:
            // a separate smaller item cap would reject receipts that their encoder legitimately produces.
            if (depth > 72 || ++items > 65536 || offset >= bytes.length) throw invalid();
            int header = Byte.toUnsignedInt(bytes[offset++]);
            int major = header >>> 5;
            int info = header & 31;
            if (major == 6 || info > 27) throw invalid();
            long argument = info;
            if (info >= 24) {
                int count = 1 << (info - 24);
                if (count > bytes.length - offset) throw invalid();
                argument = 0;
                for (int i = 0; i < count; i++) argument = (argument << 8) | Byte.toUnsignedInt(bytes[offset++]);
            }
            if (major <= 1) return;
            if (major == 7) {
                if (info != 20 && info != 21 && info != 22) throw invalid();
                return;
            }
            if (argument < 0 || argument > bytes.length - offset) throw invalid();
            if (major == 2 || major == 3) offset += (int) argument;
            else {
                long children = major == 5 ? 2 * argument : argument;
                if (children > bytes.length - offset || children > 65536) throw invalid();
                for (long i = 0; i < children; i++) scan(depth + 1);
            }
        }
    }
}
