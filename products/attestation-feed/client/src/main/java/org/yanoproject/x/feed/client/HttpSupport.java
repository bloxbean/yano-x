package org.yanoproject.x.feed.client;

import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared HTTP plumbing of the portal and the signing gateway. */
final class HttpSupport {
    static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_PATH_CHARS = 512;
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private HttpSupport() {
    }

    static ObjectNode error(String message) {
        return JSON.createObjectNode().put("error", message);
    }

    static Map<String, String> query(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return query;
        for (String pair : rawQuery.split("&")) {
            int at = pair.indexOf('=');
            String key = at < 0 ? pair : pair.substring(0, at);
            String value = at < 0 ? "" : pair.substring(at + 1);
            query.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }

    static Long height(Map<String, String> query) {
        String value = query.get("height");
        if (value == null || value.isBlank()) return null;
        try {
            long height = Long.parseLong(value);
            if (height < 1) throw new IllegalArgumentException("height must be positive");
            return height;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("height must be an integer");
        }
    }

    static String decode(String segment) {
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }

    static JsonNode readJson(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("request body exceeds " + MAX_BODY_BYTES + " bytes");
        }
        JsonNode node = JSON.readTree(body);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("request body must be a JSON object");
        }
        return node;
    }

    static void cors(HttpExchange exchange, String allowedHeaders) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", allowedHeaders);
        exchange.getResponseHeaders().add("Access-Control-Max-Age", "600");
    }

    static void respond(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static ObjectNode receiptNode(FeedClient.WriteResult result) {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageId", result.messageIdHex());
        AuthenticatedMapContract.Receipt receipt = result.receipt();
        node.put("status", result.applied() ? "APPLIED" : "REJECTED");
        node.put("height", receipt.height());
        node.put("errorCode", receipt.errorCode());
        node.put("errorName", result.errorName());
        ArrayNode results = node.putArray("results");
        for (AuthenticatedMapContract.MutationResult mutation : receipt.results()) {
            ObjectNode row = results.addObject();
            row.put("collection", mutation.collectionId());
            row.put("key", new String(mutation.applicationKey(), StandardCharsets.US_ASCII));
            row.put("revision", mutation.revision());
            row.put("status", mutation.status() == AuthenticatedMapContract.STATUS_ACTIVE
                    ? "ACTIVE" : "REVOKED");
        }
        return node;
    }
}
