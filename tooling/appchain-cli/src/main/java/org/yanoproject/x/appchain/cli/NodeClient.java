package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** The node REST API used by status, approve and anchor. Every call has a short timeout. */
final class NodeClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private final String base;
    private final String apiKey;

    NodeClient(String http, String apiKey) {
        this.base = http.endsWith("/") ? http.substring(0, http.length() - 1) : http;
        this.apiKey = apiKey;
    }

    static NodeClient local(NodeDir node) {
        return new NodeClient("http://127.0.0.1:" + node.requireIdentity().httpPort(), node.apiKey());
    }

    String base() {
        return base;
    }

    boolean ready() {
        try {
            return send(HttpRequest.newBuilder(URI.create(base + "/q/health/ready")).GET()).statusCode() == 200;
        } catch (IOException error) {
            return false;
        }
    }

    Optional<JsonNode> get(String path) {
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + path)).GET());
            if (response.statusCode() / 100 != 2) return Optional.empty();
            return Optional.of(JSON.readTree(response.body()));
        } catch (IOException error) {
            return Optional.empty();
        }
    }

    /** GETs JSON that must exist: any failure, including a missing resource, fails with what went wrong. */
    JsonNode require(String path) {
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + path)).GET());
            if (response.statusCode() / 100 != 2) {
                throw CliException.failure("The node answered HTTP " + response.statusCode() + " for "
                        + path.replaceAll("\\?.*", ""));
            }
            return JSON.readTree(response.body());
        } catch (IOException error) {
            throw CliException.failure("Cannot reach the node at " + base + ": " + error.getMessage());
        }
    }

    /** POSTs JSON; returns the parsed body, or fails with the node's message. */
    JsonNode post(String path, Object body) {
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
            JsonNode parsed = response.body() == null || response.body().isBlank()
                    ? JSON.createObjectNode() : parse(response.body());
            if (response.statusCode() / 100 != 2) {
                throw CliException.failure("The node refused " + path + " (HTTP " + response.statusCode() + "): "
                        + parsed.path("error").asText(parsed.path("message").asText(response.body())));
            }
            return parsed;
        } catch (IOException error) {
            throw CliException.failure("Cannot reach the node at " + base + ": " + error.getMessage());
        }
    }

    byte[] download(String path) throws IOException {
        HttpRequest request = withKey(HttpRequest.newBuilder(URI.create(base + path)).GET())
                .timeout(Duration.ofSeconds(30)).build();
        try {
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", interrupted);
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException {
        try {
            return HTTP.send(withKey(builder).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", interrupted);
        } catch (IllegalArgumentException invalid) {
            throw new IOException(invalid.getMessage(), invalid);
        }
    }

    private HttpRequest.Builder withKey(HttpRequest.Builder builder) {
        return apiKey == null ? builder : builder.header("X-API-Key", apiKey);
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException notJson) {
            return JSON.valueToTree(Map.of("message", body));
        }
    }
}
