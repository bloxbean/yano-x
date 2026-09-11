package org.yanoproject.x.explorer;

import org.yanoproject.x.client.AppChainClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The node REST surface the follower and the bundle builders read (ADR-050 §2.1). Public
 * contracts only: no node storage, no plugin bridge. Every response is bounded before it is
 * decoded; the API key travels in the {@code X-API-Key} header and is never echoed.
 */
public final class NodeSource {
    public static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    public static final int MAX_EVIDENCE_BYTES = 40 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String chainId;
    private final String apiKey;
    private final HttpClient http;
    private final AppChainClient sdk;

    public NodeSource(String baseUrl, String chainId, String apiKey) {
        this.baseUrl = normalize(baseUrl);
        this.chainId = requireChain(chainId);
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        AppChainClient.Builder builder = AppChainClient.builder(this.baseUrl).chainId(this.chainId);
        if (this.apiKey != null) builder.apiKey(this.apiKey);
        this.sdk = builder.build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String chainId() {
        return chainId;
    }

    /** The SDK client for typed calls (state entries, queries). */
    public AppChainClient sdk() {
        return sdk;
    }

    /** Chain ids the node lists, in node order. */
    public static List<String> listChains(String baseUrl, String apiKey) {
        NodeSource probe = new NodeSource(baseUrl, "probe", apiKey);
        JsonNode chains = probe.getJson(probe.baseUrl + "/app-chain/chains")
                .orElseThrow(() -> new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                        "the node lists no app chains"));
        List<String> ids = new ArrayList<>();
        for (JsonNode chain : chains) {
            String id = chain.path("chainId").asText("");
            if (!id.isBlank()) ids.add(id);
        }
        return ids;
    }

    public JsonNode status() {
        return getJson(chainPath("/status")).orElseThrow(() -> unavailable("chain status"));
    }

    public JsonNode stateIdentity() {
        return getJson(chainPath("/state/identity")).orElseThrow(() -> unavailable("state identity"));
    }

    public long tipHeight() {
        JsonNode page = getJson(chainPath("/blocks?from=1&limit=1"))
                .orElseThrow(() -> unavailable("block page"));
        return page.path("tipHeight").asLong(0);
    }

    public long oldestProvableHeight() {
        try {
            return sdk.oldestProvableHeight();
        } catch (RuntimeException failure) {
            return 1;
        }
    }

    public Optional<JsonNode> blockJson(long height) {
        return getJson(chainPath("/blocks/" + height));
    }

    public Optional<String> evidenceJson(String messageIdHex) {
        return getText(chainPath("/evidence/" + requireHex64(messageIdHex)), MAX_EVIDENCE_BYTES);
    }

    public Optional<String> stateProofJson(String keyHex, Long height) {
        String suffix = height == null ? "" : "?height=" + height;
        return getText(chainPath("/state/proof/" + requireHex(keyHex) + suffix), MAX_JSON_BYTES);
    }

    public Optional<String> messageProofJson(String messageIdHex) {
        return getText(chainPath("/messages/" + requireHex64(messageIdHex) + "/proof"), MAX_JSON_BYTES);
    }

    public Optional<JsonNode> messageJson(String messageIdHex) {
        return getJson(chainPath("/messages/" + requireHex64(messageIdHex)));
    }

    private String chainPath(String suffix) {
        return baseUrl + "/app-chain/chains/" + chainId + suffix;
    }

    Optional<JsonNode> getJson(String url) {
        return getText(url, MAX_JSON_BYTES).map(text -> {
            try {
                return JSON.readTree(text);
            } catch (IOException malformed) {
                throw new ExplorerException(ExplorerException.Error.MALFORMED_RESPONSE,
                        "node returned malformed JSON from " + url, malformed);
            }
        });
    }

    private Optional<String> getText(String url, int maximumBytes) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET();
        if (apiKey != null) request.header("X-API-Key", apiKey);
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException failure) {
            throw new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                    "node unreachable: " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExplorerException(ExplorerException.Error.UNAVAILABLE, "request interrupted");
        }
        if (response.statusCode() == 404) return Optional.empty();
        if (response.body().length > maximumBytes) {
            throw new ExplorerException(ExplorerException.Error.MALFORMED_RESPONSE,
                    "node response exceeds " + maximumBytes + " bytes");
        }
        if (response.statusCode() != 200) {
            throw new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                    "HTTP " + response.statusCode() + " from " + url);
        }
        return Optional.of(new String(response.body(), StandardCharsets.UTF_8));
    }

    private ExplorerException unavailable(String what) {
        return new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                "the node has no " + what + " for chain " + chainId);
    }

    static String normalize(String baseUrl) {
        String value = Objects.requireNonNull(baseUrl, "baseUrl").trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            throw new ExplorerException(ExplorerException.Error.USAGE,
                    "the node URL must start with http:// or https://");
        }
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.endsWith("/api/v1") ? value : value + "/api/v1";
    }

    private static String requireChain(String chainId) {
        String value = Objects.requireNonNull(chainId, "chainId").trim();
        if (value.isEmpty() || value.length() > 128 || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new ExplorerException(ExplorerException.Error.USAGE, "invalid chain id");
        }
        return value;
    }

    private static String requireHex64(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new ExplorerException(ExplorerException.Error.USAGE,
                    "message id must be 32 bytes of lowercase hex");
        }
        return value;
    }

    private static String requireHex(String value) {
        if (value == null || value.isEmpty() || value.length() > 1024
                || (value.length() & 1) != 0 || !value.matches("[0-9a-f]+")) {
            throw new ExplorerException(ExplorerException.Error.USAGE,
                    "state key must be bounded lowercase hex");
        }
        return value;
    }
}
