package org.yanoproject.x.assets.contracts.internal;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * A bounded reader of Plutus Data from any CBOR serialization the Cardano ledger accepts for it.
 *
 * <p>A script never sees datum bytes, only the decoded Data, so a check that must agree with a validator has to
 * classify the decoded structure. This reader accepts what Plutus' own decoder accepts: constructors as compact tags
 * ({@code 121..127}, {@code 1280..1400}) or the general form ({@code 102 [index, fields]}), definite or indefinite
 * lists and maps, byte strings as definite strings or indefinite chunked ones, integers including tag 2/3 bignums,
 * and argument heads of any width. Anything else, trailing bytes, nesting deeper than {@link #MAX_DEPTH} or input
 * longer than {@link #MAX_BYTES} is rejected.
 */
public final class PlutusDataReader {
    public static final int MAX_DEPTH = 64;
    public static final int MAX_BYTES = 64 * 1024;

    /** A decoded constructor. */
    public record Constr(long index, List<Object> fields) {
    }

    /** A decoded map, as its key/value pairs in order. */
    public record MapData(List<Object[]> entries) {
    }

    private final byte[] input;
    private int position;

    private PlutusDataReader(byte[] input) {
        this.input = input;
    }

    /**
     * The decoded value: a {@link Constr}, a {@link MapData}, a {@code List<Object>}, a {@code byte[]} or a
     * {@link BigInteger}; {@code null} when the bytes are not exactly one Plutus Data value.
     */
    public static Object read(byte[] cbor) {
        if (cbor == null || cbor.length == 0 || cbor.length > MAX_BYTES) {
            return null;
        }
        PlutusDataReader reader = new PlutusDataReader(cbor);
        try {
            Object value = reader.item(0);
            return reader.position == cbor.length ? value : null;
        } catch (IllegalStateException | ArithmeticException invalid) {
            return null;
        }
    }

    /**
     * The constructor index of the outermost value, read from its head alone, in every encoding the ledger accepts
     * (compact tags {@code 121..127} and {@code 1280..1400}, the general {@code 102 [index, fields]} form, heads of
     * any width), without decoding the fields; {@code -1} when the value does not start as a constructor.
     */
    public static long outerConstructor(byte[] cbor) {
        if (cbor == null || cbor.length == 0) {
            return -1;
        }
        try {
            return new PlutusDataReader(cbor).constructorHead();
        } catch (IllegalStateException | ArithmeticException invalid) {
            return -1;
        }
    }

    /**
     * The first field of the outermost constructor when that constructor is {@code index} and its first field is a
     * byte string (definite or chunked), read from the prefix alone. The fields after it, and the end of the value,
     * are never decoded, exactly as a script that reads its fields by position never looks at them: trailing fields
     * of any shape or depth do not change the result. {@code null} otherwise.
     */
    public static byte[] leadingByteStringField(byte[] cbor, long index) {
        if (cbor == null || cbor.length == 0) {
            return null;
        }
        PlutusDataReader reader = new PlutusDataReader(cbor);
        try {
            if (reader.constructorHead() != index) return null;
            int fields = reader.next();
            if (fields >> 5 != 4) return null;
            if ((fields & 0x1f) == 31 ? reader.peek() == 0xff : reader.length(fields & 0x1f) == 0) return null;
            int initial = reader.next();
            if (initial >> 5 != 2) return null;
            return (initial & 0x1f) == 31 ? reader.chunkedBytes() : reader.bytes(reader.length(initial & 0x1f));
        } catch (IllegalStateException | ArithmeticException invalid) {
            return null;
        }
    }

    /**
     * Reads a constructor head and returns its index, leaving the reader at the fields list; {@code -1} when the value
     * does not start as a constructor.
     */
    private long constructorHead() {
        int initial = next();
        if (initial >> 5 != 6) return -1;
        BigInteger tag = argument(initial & 0x1f);
        if (tag.bitLength() > 31) return -1;
        long t = tag.longValue();
        if (t >= 121 && t <= 127) return t - 121;
        if (t >= 1280 && t <= 1400) return t - 1280 + 7;
        if (t != 102) return -1;
        int array = next();
        if (array >> 5 != 4 || ((array & 0x1f) != 31 && !argument(array & 0x1f).equals(BigInteger.TWO))) {
            return -1;
        }
        int index = next();
        if (index >> 5 != 0) return -1;
        BigInteger value = argument(index & 0x1f);
        return value.bitLength() > 62 ? -1 : value.longValue();
    }

    private Object item(int depth) {
        if (depth > MAX_DEPTH) throw invalid();
        int initial = next();
        int major = initial >> 5;
        int info = initial & 0x1f;
        return switch (major) {
            case 0 -> argument(info);
            case 1 -> argument(info).add(BigInteger.ONE).negate();
            case 2 -> info == 31 ? chunkedBytes() : bytes(length(info));
            case 4 -> list(info, depth);
            case 5 -> map(info, depth);
            case 6 -> tagged(argument(info), depth);
            default -> throw invalid();
        };
    }

    private Object tagged(BigInteger tag, int depth) {
        if (tag.equals(BigInteger.TWO) || tag.equals(BigInteger.valueOf(3))) {
            int initial = next();
            if (initial >> 5 != 2) throw invalid();
            byte[] magnitude = (initial & 0x1f) == 31 ? chunkedBytes() : bytes(length(initial & 0x1f));
            BigInteger value = new BigInteger(1, magnitude);
            return tag.equals(BigInteger.TWO) ? value : value.add(BigInteger.ONE).negate();
        }
        long t = tag.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 ? -1 : tag.longValue();
        if (t >= 121 && t <= 127) return new Constr(t - 121, fields(depth));
        if (t >= 1280 && t <= 1400) return new Constr(t - 1280 + 7, fields(depth));
        if (t == 102) {
            Object pair = item(depth + 1);
            if (!(pair instanceof List<?> two) || two.size() != 2 || !(two.get(0) instanceof BigInteger index)
                    || index.signum() < 0 || index.bitLength() > 63 || !(two.get(1) instanceof List<?> fields)) {
                throw invalid();
            }
            @SuppressWarnings("unchecked")
            List<Object> typed = (List<Object>) fields;
            return new Constr(index.longValueExact(), typed);
        }
        throw invalid();
    }

    @SuppressWarnings("unchecked")
    private List<Object> fields(int depth) {
        Object fields = item(depth + 1);
        if (!(fields instanceof List<?>)) throw invalid();
        return (List<Object>) fields;
    }

    private List<Object> list(int info, int depth) {
        List<Object> items = new ArrayList<>();
        if (info == 31) {
            while (peek() != 0xff) items.add(item(depth + 1));
            position++;
        } else {
            int count = length(info);
            for (int i = 0; i < count; i++) items.add(item(depth + 1));
        }
        return items;
    }

    private MapData map(int info, int depth) {
        List<Object[]> entries = new ArrayList<>();
        if (info == 31) {
            while (peek() != 0xff) entries.add(new Object[] {item(depth + 1), item(depth + 1)});
            position++;
        } else {
            int count = length(info);
            for (int i = 0; i < count; i++) entries.add(new Object[] {item(depth + 1), item(depth + 1)});
        }
        return new MapData(entries);
    }

    private byte[] chunkedBytes() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while (peek() != 0xff) {
            int initial = next();
            if (initial >> 5 != 2 || (initial & 0x1f) == 31) throw invalid();
            out.writeBytes(bytes(length(initial & 0x1f)));
        }
        position++;
        return out.toByteArray();
    }

    private byte[] bytes(int length) {
        if (length > input.length - position) throw invalid();
        byte[] out = java.util.Arrays.copyOfRange(input, position, position + length);
        position += length;
        return out;
    }

    /** A length argument, bounded by the remaining input (each element needs at least one byte). */
    private int length(int info) {
        BigInteger value = argument(info);
        if (value.compareTo(BigInteger.valueOf(input.length - position)) > 0) throw invalid();
        return value.intValueExact();
    }

    private BigInteger argument(int info) {
        if (info < 24) return BigInteger.valueOf(info);
        int width = switch (info) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw invalid();
        };
        return new BigInteger(1, bytes(width));
    }

    private int next() {
        if (position >= input.length) throw invalid();
        return input[position++] & 0xff;
    }

    private int peek() {
        if (position >= input.length) throw invalid();
        return input[position] & 0xff;
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("not Plutus Data");
    }
}
