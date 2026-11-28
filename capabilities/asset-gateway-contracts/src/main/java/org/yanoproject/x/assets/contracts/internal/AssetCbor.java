package org.yanoproject.x.assets.contracts.internal;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * The strict CBOR subset used by every ADR-059 contract: definite-length arrays, byte strings,
 * text strings and unsigned integers only, encoded in preferred (shortest) form. Maps and tags are
 * never used, so RFC 8949 §4.2.1 deterministic encoding reduces to "encode as built". A decoder
 * accepts a value only if re-encoding it yields the exact input bytes.
 */
public final class AssetCbor {
    public static final int MAX_WIRE_BYTES = 65_536;
    private static final int MAX_ITEMS = 4_096;
    private static final int MAX_DEPTH = 8;

    private AssetCbor() {
    }

    public static byte[] encode(DataItem item) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new CborEncoder(output).encode(item);
            byte[] encoded = output.toByteArray();
            if (encoded.length == 0 || encoded.length > MAX_WIRE_BYTES) {
                throw malformed("encoded value is empty or too large");
            }
            return encoded;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw malformed("value cannot be encoded");
        }
    }

    /** Decodes one canonical array whose arity is one of {@code allowedArities}. */
    public static Array decodeArray(byte[] input, int... allowedArities) {
        if (input == null || input.length == 0 || input.length > MAX_WIRE_BYTES) {
            throw malformed("input is empty or too large");
        }
        try {
            CborDecoder decoder = new CborDecoder(new ByteArrayInputStream(input));
            decoder.setMaxPreallocationSize(MAX_WIRE_BYTES);
            List<DataItem> roots = decoder.decode();
            if (roots.size() != 1 || !(roots.getFirst() instanceof Array array)
                    || array.isChunked() || !allowed(array.getDataItems().size(), allowedArities)) {
                throw malformed("expected one definite array of an allowed arity");
            }
            requireSafeTree(array);
            if (!Arrays.equals(input, encode(array))) {
                throw malformed("input is not in canonical form");
            }
            return array;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception | StackOverflowError failure) {
            throw malformed("input is not valid CBOR");
        }
    }

    public static Array array(DataItem value, int minItems, int maxItems) {
        if (!(value instanceof Array array) || array.isChunked()) throw malformed("expected array");
        int size = array.getDataItems().size();
        if (size < minItems || size > maxItems) throw malformed("array size out of range");
        return array;
    }

    public static byte[] bytes(DataItem value, int minLength, int maxLength) {
        if (!(value instanceof ByteString bytes) || bytes.isChunked()) throw malformed("expected bytes");
        byte[] parsed = bytes.getBytes();
        if (parsed.length < minLength || parsed.length > maxLength) {
            throw malformed("byte string length out of range");
        }
        return parsed.clone();
    }

    public static String text(DataItem value, int maxUtf8Bytes) {
        if (!(value instanceof UnicodeString text) || text.isChunked()) throw malformed("expected text");
        String parsed = text.getString();
        if (parsed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxUtf8Bytes) {
            throw malformed("text too long");
        }
        return parsed;
    }

    public static BigInteger uint(DataItem value, BigInteger maxInclusive) {
        if (!(value instanceof UnsignedInteger number)) throw malformed("expected unsigned integer");
        BigInteger parsed = number.getValue();
        if (parsed.signum() < 0 || parsed.compareTo(maxInclusive) > 0) {
            throw malformed("unsigned integer out of range");
        }
        return parsed;
    }

    public static long uintLong(DataItem value) {
        return uint(value, BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
    }

    public static UnsignedInteger uint(BigInteger value) {
        if (value == null || value.signum() < 0) throw malformed("negative unsigned integer");
        return new UnsignedInteger(value);
    }

    public static UnsignedInteger uint(long value) {
        if (value < 0) throw malformed("negative unsigned integer");
        return new UnsignedInteger(value);
    }

    public static ByteString bstr(byte[] value) {
        return new ByteString(value.clone());
    }

    public static IllegalArgumentException malformed(String reason) {
        return new IllegalArgumentException("invalid asset-gateway contract value: " + reason);
    }

    private static boolean allowed(int arity, int[] allowedArities) {
        if (allowedArities.length == 0) return true;
        for (int candidate : allowedArities) if (arity == candidate) return true;
        return false;
    }

    private static void requireSafeTree(DataItem root) {
        record Pending(DataItem item, int depth) { }
        Deque<Pending> pending = new ArrayDeque<>();
        pending.add(new Pending(root, 1));
        int count = 0;
        while (!pending.isEmpty()) {
            Pending current = pending.removeLast();
            DataItem item = current.item();
            if (++count > MAX_ITEMS || current.depth() > MAX_DEPTH || item.hasTag()) {
                throw malformed("value nests too deeply, is too large, or is tagged");
            }
            if (item instanceof Array array) {
                if (array.isChunked()) throw malformed("indefinite array");
                for (DataItem child : array.getDataItems()) {
                    pending.add(new Pending(child, current.depth() + 1));
                }
            } else if (item instanceof ByteString bytes) {
                if (bytes.isChunked()) throw malformed("indefinite byte string");
            } else if (item instanceof UnicodeString text) {
                if (text.isChunked()) throw malformed("indefinite text string");
            } else if (!(item instanceof UnsignedInteger)) {
                throw malformed("unsupported CBOR item");
            }
        }
    }
}
