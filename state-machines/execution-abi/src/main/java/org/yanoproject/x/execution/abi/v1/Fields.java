package org.yanoproject.x.execution.abi.v1;

import org.yanoproject.x.execution.abi.cbor.CanonicalCbor;
import org.yanoproject.x.execution.abi.cbor.CborException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict reader for an integer-keyed ABI map: unknown keys and wrong types are schema errors. */
final class Fields {

    private final String type;
    private final Map<?, ?> map;

    private Fields(String type, Map<?, ?> map) {
        this.type = type;
        this.map = map;
    }

    static Fields decode(String type, byte[] bytes, Set<Long> allowedKeys) {
        return of(type, CanonicalCbor.decode(bytes), allowedKeys);
    }

    static Fields of(String type, Object value, Set<Long> allowedKeys) {
        if (!(value instanceof Map<?, ?> map)) {
            throw schema(type, "expected a map");
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof Long l) || !allowedKeys.contains(l)) {
                throw schema(type, "unexpected key " + key);
            }
        }
        return new Fields(type, map);
    }

    boolean has(long key) {
        return map.containsKey(key);
    }

    long uint(long key) {
        Object v = required(key);
        if (!(v instanceof Long l) || l < 0) {
            throw schema(type, "key " + key + " must be an unsigned integer");
        }
        return l;
    }

    long integer(long key) {
        Object v = required(key);
        if (!(v instanceof Long l)) {
            throw schema(type, "key " + key + " must be an integer");
        }
        return l;
    }

    byte[] bytes(long key) {
        Object v = required(key);
        if (!(v instanceof byte[] b)) {
            throw schema(type, "key " + key + " must be a byte string");
        }
        return b;
    }

    byte[] bytes(long key, int exactLength) {
        byte[] b = bytes(key);
        if (b.length != exactLength) {
            throw schema(type, "key " + key + " must be " + exactLength + " bytes");
        }
        return b;
    }

    String text(long key) {
        Object v = required(key);
        if (!(v instanceof String s)) {
            throw schema(type, "key " + key + " must be a text string");
        }
        return s;
    }

    boolean bool(long key) {
        Object v = required(key);
        if (!(v instanceof Boolean b)) {
            throw schema(type, "key " + key + " must be a boolean");
        }
        return b;
    }

    Map<?, ?> map(long key) {
        Object v = required(key);
        if (!(v instanceof Map<?, ?> m)) {
            throw schema(type, "key " + key + " must be a map");
        }
        return m;
    }

    Object raw(long key) {
        return required(key);
    }

    private Object required(long key) {
        Object v = map.get(key);
        if (v == null) {
            throw schema(type, "missing key " + key);
        }
        return v;
    }

    static CborException schema(String type, String message) {
        return new CborException(CborException.SCHEMA, type + ": " + message);
    }

    static Set<Long> keys(long... keys) {
        return Set.copyOf(Arrays.stream(keys).boxed().toList());
    }

    static String code(String type, String code) {
        if (!AbiV1.isCode(code)) {
            throw schema(type, "invalid code '" + code + "'");
        }
        return code;
    }

    static <T> List<T> listOf(List<T> list) {
        return List.copyOf(list);
    }
}
