package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicNames;
import org.yanoproject.api.appchain.topics.TopicWire.Config;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.x.client.Hex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded topic REST reads; no redirect can forward the optional API credential to a different origin. */
final class TopicsPeer {
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base;
    private final String chain;
    private final String apiKey;

    TopicsPeer(String peer, String chain, String apiKey) {
        URI uri = URI.create(peer);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw TopicPlan.invalid("peer must be an HTTP(S) URL without credentials, query or fragment");
        }
        if (chain == null || chain.isBlank() || chain.length() > 128) throw TopicPlan.invalid("invalid chain id");
        String root = peer.replaceAll("/+$", "");
        if (uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/")) root += "/api/v1";
        this.base = root + "/app-chain/chains/" + segment(chain);
        this.chain = chain;
        this.apiKey = apiKey;
    }

    TopicPlan.Snapshot snapshot(JsonNode document) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode command : document.path("commands")) {
            String target = TopicPlan.text(command, "target");
            names.addAll(TopicNames.applicableNames(target));
            String authority = TopicPlan.text(command, "authority");
            if (TopicNames.isValidEntryName(authority)) names.addAll(TopicNames.applicableNames(authority));
        }
        return snapshot(names);
    }

    TopicPlan.Snapshot snapshot(Set<String> names) throws IOException {
        // Existing REST entry reads are tip reads. A stable root on both sides prevents mixed-revision plans.
        for (int attempt = 0; attempt < 3; attempt++) {
            JsonNode before = get("/status");
            JsonNode identity = before.path("stateCommitment");
            String generation = TopicPlan.text(identity, "generationId");
            String stateRoot = TopicPlan.text(identity, "stateRoot");
            long height = TopicPlan.number(identity, "version", 1);
            JsonNode configuration = get("/topics/config");
            Config config = configuration.path("enabled").asBoolean(false)
                    ? TopicPlan.JSON.treeToValue(configuration.get("config"), Config.class) : null;
            Map<String, EntryRecord> entries = new LinkedHashMap<>();
            for (String name : names) {
                JsonNode value = optional("/topics/entries/" + segment(name));
                if (value != null) {
                    EntryRecord entry = TopicPlan.JSON.treeToValue(value, EntryRecord.class);
                    if (!name.equals(entry.name())) throw new IOException("peer returned a different topic entry");
                    entries.put(name, entry);
                }
            }
            Map<String, Long> freedHeads = new LinkedHashMap<>();
            for (String name : names) {
                if (entries.containsKey(name)) continue;
                JsonNode freed = get("/state/entry/" + Hex.encode(TopicKeys.freed(name)) + "?height=" + height);
                if (!stateRoot.equals(freed.path("stateRoot").asText())) {
                    throw new IOException("freed head is not from the inspected root");
                }
                String presence = TopicPlan.text(freed, "presence");
                if (!Set.of("PRESENT", "ABSENT", "TOMBSTONED").contains(presence)) {
                    throw new IOException("invalid freed-head presence");
                }
                if ("PRESENT".equals(presence)) {
                    freedHeads.put(name, TopicKeys.u64(TopicPlan.hex(freed, "valueHex", 8)));
                }
            }
            JsonNode after = get("/status");
            if (identity.equals(after.path("stateCommitment"))) {
                if (!chain.equals(before.path("chainId").asText())) throw new IOException("peer chain mismatch");
                JsonNode maximum = before.path("consensusProfile").path("maxMessageBytes");
                if (!maximum.isIntegralNumber() || !maximum.canConvertToLong() || maximum.longValue() < 1) {
                    throw new IOException("peer did not return the committed message bound");
                }
                return new TopicPlan.Snapshot(chain, generation, height, stateRoot,
                        maximum.longValue(), config, entries, freedHeads);
            }
        }
        throw new IOException("chain advanced during topic snapshot; retry planning");
    }

    JsonNode get(String path) throws IOException {
        JsonNode value = request(path, null, false);
        if (value == null) throw new IOException("topic resource not found");
        return value;
    }

    JsonNode optional(String path) throws IOException {
        return request(path, null, true);
    }

    JsonNode submit(byte[] body) throws IOException {
        ObjectNode request = TopicPlan.JSON.createObjectNode();
        request.put("topic", "~topics/registry").put("bodyHex", Hex.encode(body));
        return request("/messages", request, false);
    }

    private JsonNode request(String path, JsonNode body, boolean allowMissing) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/json");
        if (apiKey != null) request.header("X-API-Key", apiKey);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(
                HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request.build(), ignored -> new LimitedBody());
        try {
            HttpResponse<byte[]> response = pending.get(30, TimeUnit.SECONDS);
            byte[] bytes = response.body();
            if (response.statusCode() == 404 && allowMissing) return null;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // A remote diagnostic is untrusted and can echo headers or request bodies. Print only a known code.
                String code = null;
                try {
                    JsonNode error = TopicPlan.JSON.readTree(bytes);
                    String candidate = error.path("code").asText();
                    if (candidate.matches("TOPIC_[A-Z_]{1,64}")) code = candidate;
                } catch (RuntimeException ignored) {
                    // HTTP status remains enough to identify the failed operation.
                }
                if (code != null) throw new Refusal(code);
                throw new IOException("topic request returned HTTP " + response.statusCode());
            }
            JsonNode result = TopicPlan.JSON.readTree(bytes);
            if (result == null || !result.isObject()) throw new IOException("malformed topic response");
            return result;
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("topic request interrupted", interrupted);
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw new IOException("topic request timed out", timeout);
        } catch (ExecutionException failed) {
            throw new IOException("topic request failed", failed.getCause());
        }
    }

    static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static final class Refusal extends IOException {
        private final String code;

        Refusal(String code) {
            super(code);
            this.code = code;
        }

        String code() { return code; }
    }

    /** Completes with the body, so the HTTP timeout also bounds a server that stalls after its headers. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return result; }

        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            subscription.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("topic response exceeds 4 MiB"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }

        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
