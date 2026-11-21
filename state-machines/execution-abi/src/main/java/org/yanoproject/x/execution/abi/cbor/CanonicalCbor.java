package org.yanoproject.x.execution.abi.cbor;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical CBOR for the guest ABI: the RFC 8949 section 4.2.1 core deterministic encoding,
 * restricted to a small data model.
 *
 * <p>Java mapping: {@link Long} (integers in the int64 range), {@code byte[]}, {@link String}
 * (valid UTF-8), {@link List}, {@link Map} (keys {@link Long} or {@link String}), {@link Boolean}
 * and {@link CborNull}. Floats, tags, undefined, other simple values, indefinite lengths and
 * integers outside int64 are not part of the model.
 *
 * <p>The decoder is strict: it accepts exactly the bytes the encoder produces. Non-shortest
 * integer or length heads, unsorted or duplicate map keys and trailing bytes are errors. Strictness
 * is what makes the encoding usable on the consensus path: one value, one byte string.
 */
public final class CanonicalCbor {

    public static final int DEFAULT_MAX_DEPTH = 16;
    public static final int DEFAULT_MAX_ITEMS = 65_536;

    private CanonicalCbor() {
    }

    // ---------------------------------------------------------------- encoding

    public static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, value, 0);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Object value, int depth) {
        if (depth > DEFAULT_MAX_DEPTH) {
            throw new CborException(CborException.DEPTH_LIMIT, "nesting deeper than " + DEFAULT_MAX_DEPTH);
        }
        switch (value) {
            case null -> throw new IllegalArgumentException("Java null is not a CBOR value; use CborNull.NULL");
            case Long l -> {
                if (l >= 0) {
                    head(out, 0, l);
                } else {
                    head(out, 1, -1 - l);
                }
            }
            case Integer i -> write(out, (long) i, depth);
            case byte[] b -> {
                head(out, 2, b.length);
                out.writeBytes(b);
            }
            case String s -> {
                byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
                if (!new String(utf8, StandardCharsets.UTF_8).equals(s)) {
                    throw new IllegalArgumentException("string is not valid Unicode (lone surrogate)");
                }
                head(out, 3, utf8.length);
                out.writeBytes(utf8);
            }
            case List<?> list -> {
                head(out, 4, list.size());
                for (Object item : list) {
                    write(out, item, depth + 1);
                }
            }
            case Map<?, ?> map -> {
                TreeMap<byte[], byte[]> sorted = new TreeMap<>(Arrays::compareUnsigned);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof Long) && !(entry.getKey() instanceof Integer)
                            && !(entry.getKey() instanceof String)) {
                        throw new IllegalArgumentException("map keys must be integers or strings");
                    }
                    byte[] key = encodeAt(entry.getKey(), depth + 1);
                    if (sorted.put(key, encodeAt(entry.getValue(), depth + 1)) != null) {
                        throw new IllegalArgumentException("duplicate map key " + entry.getKey());
                    }
                }
                head(out, 5, sorted.size());
                sorted.forEach((k, v) -> {
                    out.writeBytes(k);
                    out.writeBytes(v);
                });
            }
            case Boolean b -> out.write(b ? 0xF5 : 0xF4);
            case CborNull ignored -> out.write(0xF6);
            default -> throw new IllegalArgumentException("not a CBOR model value: " + value.getClass().getName());
        }
    }

    private static byte[] encodeAt(Object value, int depth) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, value, depth);
        return out.toByteArray();
    }

    private static void head(ByteArrayOutputStream out, int major, long argument) {
        int m = major << 5;
        if (argument < 0) {
            throw new IllegalArgumentException("argument out of range");
        } else if (argument < 24) {
            out.write(m | (int) argument);
        } else if (argument < 0x100) {
            out.write(m | 24);
            out.write((int) argument);
        } else if (argument < 0x10000) {
            out.write(m | 25);
            out.write((int) (argument >>> 8));
            out.write((int) argument);
        } else if (argument < 0x1_0000_0000L) {
            out.write(m | 26);
            out.writeBytes(ByteBuffer.allocate(4).putInt((int) argument).array());
        } else {
            out.write(m | 27);
            out.writeBytes(ByteBuffer.allocate(8).putLong(argument).array());
        }
    }

    // ---------------------------------------------------------------- decoding

    public static Object decode(byte[] bytes) {
        return decode(bytes, DEFAULT_MAX_DEPTH, DEFAULT_MAX_ITEMS);
    }

    public static Object decode(byte[] bytes, int maxDepth, int maxItems) {
        Reader reader = new Reader(bytes, maxDepth, maxItems);
        Object value = reader.read(0);
        if (reader.pos != bytes.length) {
            throw new CborException(CborException.TRAILING_BYTES, (bytes.length - reader.pos) + " trailing bytes");
        }
        return value;
    }

    private static final class Reader {
        private final byte[] in;
        private final int maxDepth;
        private final int maxItems;
        private int pos;

        Reader(byte[] in, int maxDepth, int maxItems) {
            this.in = in;
            this.maxDepth = maxDepth;
            this.maxItems = maxItems;
        }

        Object read(int depth) {
            if (depth > maxDepth) {
                throw new CborException(CborException.DEPTH_LIMIT, "nesting deeper than " + maxDepth);
            }
            int initial = u8();
            int major = initial >>> 5;
            int info = initial & 0x1F;
            if (major == 7) {
                return switch (info) {
                    case 20 -> Boolean.FALSE;
                    case 21 -> Boolean.TRUE;
                    case 22 -> CborNull.NULL;
                    default -> throw new CborException(CborException.UNSUPPORTED_TYPE,
                            "major type 7 value " + info + " (floats, undefined and simple values are not allowed)");
                };
            }
            if (major == 6) {
                throw new CborException(CborException.UNSUPPORTED_TYPE, "tags are not allowed");
            }
            long argument = argument(info);
            return switch (major) {
                case 0 -> {
                    if (argument < 0) {
                        throw new CborException(CborException.INT_OUT_OF_RANGE, "unsigned integer above int64");
                    }
                    yield argument;
                }
                case 1 -> {
                    if (argument < 0) {
                        throw new CborException(CborException.INT_OUT_OF_RANGE, "negative integer below int64");
                    }
                    yield -1 - argument;
                }
                case 2 -> bytes(length(argument));
                case 3 -> text(length(argument));
                case 4 -> {
                    int count = count(argument);
                    List<Object> list = new ArrayList<>(Math.min(count, 1024));
                    for (int i = 0; i < count; i++) {
                        list.add(read(depth + 1));
                    }
                    yield List.copyOf(list);
                }
                case 5 -> {
                    int count = count(argument);
                    Map<Object, Object> map = new LinkedHashMap<>();
                    byte[] previous = null;
                    for (int i = 0; i < count; i++) {
                        int keyStart = pos;
                        Object key = read(depth + 1);
                        if (!(key instanceof Long) && !(key instanceof String)) {
                            throw new CborException(CborException.UNSUPPORTED_TYPE, "map keys must be integers or text");
                        }
                        byte[] encodedKey = Arrays.copyOfRange(in, keyStart, pos);
                        if (previous != null) {
                            int order = Arrays.compareUnsigned(previous, encodedKey);
                            if (order == 0) {
                                throw new CborException(CborException.DUPLICATE_KEY, "duplicate map key " + key);
                            }
                            if (order > 0) {
                                throw new CborException(CborException.MAP_KEY_ORDER, "map keys not in canonical order");
                            }
                        }
                        previous = encodedKey;
                        map.put(key, read(depth + 1));
                    }
                    yield java.util.Collections.unmodifiableMap(map);
                }
                default -> throw new IllegalStateException("unreachable major " + major);
            };
        }

        private long argument(int info) {
            if (info < 24) {
                return info;
            }
            return switch (info) {
                case 24 -> {
                    long v = u8();
                    if (v < 24) {
                        throw new CborException(CborException.NON_CANONICAL_INT, "one-byte argument below 24");
                    }
                    yield v;
                }
                case 25 -> {
                    long v = ((long) u8() << 8) | u8();
                    if (v < 0x100) {
                        throw new CborException(CborException.NON_CANONICAL_INT, "two-byte argument below 256");
                    }
                    yield v;
                }
                case 26 -> {
                    long v = 0;
                    for (int i = 0; i < 4; i++) {
                        v = (v << 8) | u8();
                    }
                    if (v < 0x10000) {
                        throw new CborException(CborException.NON_CANONICAL_INT, "four-byte argument below 65536");
                    }
                    yield v;
                }
                case 27 -> {
                    long v = 0;
                    for (int i = 0; i < 8; i++) {
                        v = (v << 8) | u8();
                    }
                    if (v >= 0 && v < 0x1_0000_0000L) {
                        throw new CborException(CborException.NON_CANONICAL_INT, "eight-byte argument below 2^32");
                    }
                    yield v; // may be negative as signed long: caller rejects for its major type
                }
                case 31 -> throw new CborException(CborException.INDEFINITE_LENGTH, "indefinite lengths are not allowed");
                default -> throw new CborException(CborException.UNSUPPORTED_TYPE, "reserved additional info " + info);
            };
        }

        private int length(long argument) {
            if (argument < 0 || argument > in.length - pos) {
                throw new CborException(CborException.TRUNCATED, "string longer than the remaining input");
            }
            return (int) argument;
        }

        private int count(long argument) {
            if (argument < 0 || argument > maxItems) {
                throw new CborException(CborException.SIZE_LIMIT, "container larger than " + maxItems + " items");
            }
            if (argument > in.length - pos) {
                throw new CborException(CborException.TRUNCATED, "container longer than the remaining input");
            }
            return (int) argument;
        }

        private byte[] bytes(int length) {
            byte[] out = Arrays.copyOfRange(in, pos, pos + length);
            pos += length;
            return out;
        }

        private String text(int length) {
            byte[] utf8 = bytes(length);
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(utf8))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new CborException(CborException.INVALID_UTF8, "text string is not valid UTF-8");
            }
        }

        private int u8() {
            if (pos >= in.length) {
                throw new CborException(CborException.TRUNCATED, "unexpected end of input");
            }
            return in[pos++] & 0xFF;
        }
    }

    // ---------------------------------------------------------------- diagnostic notation

    /** RFC 8949 diagnostic notation, used in golden vectors and test failure messages. */
    public static String diagnostic(Object value) {
        StringBuilder out = new StringBuilder();
        diag(out, value);
        return out.toString();
    }

    private static void diag(StringBuilder out, Object value) {
        switch (value) {
            case Long l -> out.append(l);
            case Integer i -> out.append(i);
            case byte[] b -> out.append("h'").append(java.util.HexFormat.of().formatHex(b)).append('\'');
            case String s -> {
                out.append('"');
                for (char c : s.toCharArray()) {
                    switch (c) {
                        case '"' -> out.append("\\\"");
                        case '\\' -> out.append("\\\\");
                        default -> {
                            if (c < 0x20 || c > 0x7E) {
                                out.append(String.format("\\u%04x", (int) c));
                            } else {
                                out.append(c);
                            }
                        }
                    }
                }
                out.append('"');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    diag(out, list.get(i));
                }
                out.append(']');
            }
            case Map<?, ?> map -> {
                // Print in canonical key order so the notation is stable.
                Map<?, ?> canonical = (Map<?, ?>) decode(encode(map));
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : canonical.entrySet()) {
                    if (!first) {
                        out.append(", ");
                    }
                    first = false;
                    diag(out, entry.getKey());
                    out.append(": ");
                    diag(out, entry.getValue());
                }
                out.append('}');
            }
            case Boolean b -> out.append(b);
            case CborNull ignored -> out.append("null");
            default -> throw new IllegalArgumentException("not a CBOR model value: " + value);
        }
    }
}
