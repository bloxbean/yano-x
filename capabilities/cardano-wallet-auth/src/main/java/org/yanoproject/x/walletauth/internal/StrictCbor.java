package org.yanoproject.x.walletauth.internal;

import java.math.BigInteger;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict CBOR reader for untrusted wallet output (ADR-059 D13 §1). Unlike a general
 * decoder it never silently drops data: it rejects indefinite lengths, duplicate map keys,
 * trailing bytes, reserved additional-information values, unknown simple values and any tag
 * except one at the root when the caller allows it, and it bounds depth, item count and total
 * size. Floats are accepted only as opaque values (they may appear in ignored headers). It does
 * not require canonical encoding: wallet bytes are signed as received, never re-encoded.
 */
public final class StrictCbor {
    public static final int MAX_BYTES = 16 * 1024;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ITEMS = 512;

    /** A decoded item. Maps keep insertion order; keys are {@link Long} or {@link String}. */
    public sealed interface Item permits UInt, NInt, Bytes, Text, Arr, MapItem, Bool, Null, Tagged, Opaque { }

    public record UInt(BigInteger value) implements Item { }

    public record NInt(BigInteger value) implements Item { }

    public record Bytes(byte[] value) implements Item {
        public Bytes {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }
    }

    public record Text(String value) implements Item { }

    public record Arr(List<Item> items) implements Item { }

    public record MapItem(Map<Object, Item> entries) implements Item { }

    public record Bool(boolean value) implements Item { }

    public record Null() implements Item { }

    public record Tagged(long tag, Item item) implements Item { }

    /** A float or other simple value: kept opaque, never interpreted. */
    public record Opaque() implements Item { }

    public static final class Malformed extends Exception {
        public Malformed(String message) {
            super(message);
        }
    }

    private final byte[] input;
    private final boolean rootTagAllowed;
    private int offset;
    private int items;

    private StrictCbor(byte[] input, boolean rootTagAllowed) {
        this.input = input;
        this.rootTagAllowed = rootTagAllowed;
    }

    /** Decodes exactly one item that spans the whole input. No tags anywhere. */
    public static Item decode(byte[] input) throws Malformed {
        return decode(input, false);
    }

    /** As {@link #decode(byte[])}, optionally allowing one tag at the root only. */
    public static Item decode(byte[] input, boolean rootTagAllowed) throws Malformed {
        if (input == null || input.length == 0 || input.length > MAX_BYTES) {
            throw new Malformed("input empty or larger than 16 KiB");
        }
        StrictCbor reader = new StrictCbor(input, rootTagAllowed);
        Item item = reader.read(1);
        if (reader.offset != input.length) throw new Malformed("trailing bytes");
        return item;
    }

    private Item read(int depth) throws Malformed {
        if (depth > MAX_DEPTH) throw new Malformed("nesting too deep");
        if (++items > MAX_ITEMS) throw new Malformed("too many items");
        int initial = next();
        int major = initial >>> 5;
        int info = initial & 0x1f;
        if (info == 31) throw new Malformed("indefinite length");
        if (info >= 28) throw new Malformed("reserved additional information");
        switch (major) {
            case 0:
                return new UInt(argument(info));
            case 1:
                return new NInt(BigInteger.valueOf(-1).subtract(argument(info)));
            case 2:
                return new Bytes(take(length(info)));
            case 3: {
                byte[] raw = take(length(info));
                try {
                    String text = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(raw)).toString();
                    return new Text(text);
                } catch (CharacterCodingException invalid) {
                    throw new Malformed("invalid UTF-8");
                }
            }
            case 4: {
                int count = length(info);
                List<Item> list = new ArrayList<>(Math.min(count, 64));
                for (int i = 0; i < count; i++) list.add(read(depth + 1));
                return new Arr(List.copyOf(list));
            }
            case 5: {
                int count = length(info);
                Map<Object, Item> map = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    Item key = read(depth + 1);
                    Object normalized = switch (key) {
                        case UInt u -> u.value().bitLength() < 64 ? (Object) u.value().longValue() : u.value();
                        case NInt n -> n.value().bitLength() < 64 ? (Object) n.value().longValue() : n.value();
                        case Text t -> t.value();
                        default -> throw new Malformed("map key must be an integer or text");
                    };
                    if (map.containsKey(normalized)) throw new Malformed("duplicate map key " + normalized);
                    map.put(normalized, read(depth + 1));
                }
                return new MapItem(map);
            }
            case 6: {
                if (!(rootTagAllowed && depth == 1)) throw new Malformed("tags are only allowed at the root");
                BigInteger tag = argument(info);
                if (tag.bitLength() > 63) throw new Malformed("unsupported tag");
                return new Tagged(tag.longValue(), read(depth + 1));
            }
            default: {
                if (info == 20) return new Bool(false);
                if (info == 21) return new Bool(true);
                if (info == 22) return new Null();
                if (info >= 25 && info <= 27) {
                    take(info == 25 ? 2 : info == 26 ? 4 : 8);
                    return new Opaque();
                }
                throw new Malformed("unsupported simple value");
            }
        }
    }

    private BigInteger argument(int info) throws Malformed {
        if (info < 24) return BigInteger.valueOf(info);
        int size = switch (info) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw new Malformed("bad argument");
        };
        return new BigInteger(1, take(size));
    }

    private int length(int info) throws Malformed {
        BigInteger value = argument(info);
        if (value.compareTo(BigInteger.valueOf(MAX_BYTES)) > 0) throw new Malformed("length too large");
        return value.intValueExact();
    }

    private int next() throws Malformed {
        if (offset >= input.length) throw new Malformed("unexpected end of input");
        return input[offset++] & 0xff;
    }

    private byte[] take(int count) throws Malformed {
        if (count < 0 || offset + count > input.length) throw new Malformed("unexpected end of input");
        byte[] out = Arrays.copyOfRange(input, offset, offset + count);
        offset += count;
        return out;
    }
}
