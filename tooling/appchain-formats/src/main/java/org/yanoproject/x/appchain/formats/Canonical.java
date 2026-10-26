package org.yanoproject.x.appchain.formats;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Canonical JSON (sorted keys, no whitespace) and SHA-256, the basis of every setup identity. */
public final class Canonical {
    public static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    private static final ObjectMapper PRETTY = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private Canonical() {
    }

    /** Sorted-key, whitespace-free UTF-8 JSON of a value. */
    public static byte[] bytes(Object value) {
        try {
            return JSON.writeValueAsBytes(sorted(JSON.valueToTree(value)));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot encode canonical JSON", error);
        }
    }

    /** Sorted-key, indented JSON for files people may open. */
    public static String pretty(Object value) {
        try {
            return PRETTY.writeValueAsString(sorted(PRETTY.valueToTree(value))) + "\n";
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot encode JSON", error);
        }
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) digest.update(part);
            return digest.digest();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** Groups hex characters in fours for reading aloud, e.g. {@code 3f9a-77c1-0b2e}. */
    public static String grouped(String hex) {
        List<String> groups = new ArrayList<>();
        for (int index = 0; index < hex.length(); index += 4) {
            groups.add(hex.substring(index, Math.min(hex.length(), index + 4)));
        }
        return String.join("-", groups);
    }

    static JsonNode sorted(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            names.sort(null);
            for (String name : names) result.set(name, sorted(object.get(name)));
            return result;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            array.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return node;
    }
}
