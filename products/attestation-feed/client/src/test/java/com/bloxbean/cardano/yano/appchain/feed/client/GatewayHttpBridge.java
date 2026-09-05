package com.bloxbean.cardano.yano.appchain.feed.client;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundleCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.MessageInclusionProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateProofEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Serves one in-process gateway over the REST paths the registry client uses, rendering
 * each document the way the Yano node resource does so the pinned SDK decoders accept it:
 * status, submit, message proof, state proof, block, block page, and committed-state query.
 */
final class GatewayHttpBridge implements AutoCloseable {
    static final String API_PREFIX = "/api/v1";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    private static final HexFormat HEX = HexFormat.of();

    private final HttpServer server;
    private final AppChainGateway gateway;
    private final String apiKey;

    private GatewayHttpBridge(HttpServer server, AppChainGateway gateway, String apiKey) {
        this.server = server;
        this.gateway = gateway;
        this.apiKey = apiKey;
    }

    static GatewayHttpBridge start(AppChainGateway gateway, String apiKey) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        GatewayHttpBridge bridge = new GatewayHttpBridge(server, gateway, apiKey);
        server.createContext("/", bridge::handle);
        server.start();
        return bridge;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + API_PREFIX;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (apiKey != null && !apiKey.equals(exchange.getRequestHeaders().getFirst("X-API-Key"))) {
                respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String prefix = API_PREFIX + "/app-chain/chains/" + gateway.chainId();
            if (!path.startsWith(prefix)) {
                respond(exchange, 404, "{\"error\":\"unknown chain\"}");
                return;
            }
            String rest = path.substring(prefix.length());
            String method = exchange.getRequestMethod();
            if ("POST".equals(method) && rest.equals("/messages")) {
                JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
                String messageId = gateway.submit(body.path("topic").asText(),
                        HEX.parseHex(body.path("bodyHex").asText()));
                respond(exchange, 202, JSON.writeValueAsString(Map.of(
                        "messageId", messageId, "chainId", gateway.chainId(),
                        "topic", body.path("topic").asText())));
                return;
            }
            if ("POST".equals(method) && rest.startsWith("/query/")) {
                JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
                AppQueryResult result = gateway.query(rest.substring("/query/".length()),
                        HEX.parseHex(body.path("paramsHex").asText("")));
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("chainId", result.chainId());
                view.put("stateMachineId", result.stateMachineId());
                view.put("committedHeight", result.committedHeight());
                view.put("stateRoot", HEX.formatHex(result.stateRoot()));
                view.put("payloadHex", HEX.formatHex(result.payload()));
                respond(exchange, 200, JSON.writeValueAsString(view));
                return;
            }
            if (!"GET".equals(method)) {
                respond(exchange, 405, "{\"error\":\"method\"}");
                return;
            }
            if (rest.equals("/status")) {
                respond(exchange, 200, JSON.writeValueAsString(gateway.status()));
            } else if (rest.equals("/state/identity")) {
                StateCommitmentIdentity identity = gateway.stateCommitmentIdentity().orElseThrow();
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("schemaVersion", identity.schemaVersion());
                view.put("profile", identity.profile().id());
                view.put("backend", identity.profile().backendFamily().name().toLowerCase(Locale.ROOT));
                view.put("formatFingerprint", HEX.formatHex(identity.profile().formatFingerprint()));
                view.put("genesisId", HEX.formatHex(identity.genesisId()));
                view.put("version", gateway.tipHeight());
                view.put("stateRoot", HEX.formatHex(gateway.stateRoot()));
                respond(exchange, 200, JSON.writeValueAsString(view));
            } else if (rest.startsWith("/messages/") && rest.endsWith("/proof")) {
                byte[] id = HEX.parseHex(rest.substring("/messages/".length(), rest.length() - "/proof".length()));
                Optional<MessageInclusionProof> proof = gateway.messageInclusionProof(id);
                if (proof.isEmpty()) {
                    respond(exchange, 404, "{\"error\":\"not finalized\"}");
                } else {
                    respond(exchange, 200, JSON.writeValueAsString(messageProofView(proof.get())));
                }
            } else if (rest.startsWith("/evidence/")) {
                byte[] id = HEX.parseHex(rest.substring("/evidence/".length()));
                var bundle = gateway.evidence(id);
                if (bundle.isEmpty()) {
                    respond(exchange, 404, "{\"error\":\"not finalized\"}");
                } else {
                    respond(exchange, 200, EvidenceBundleCodec.toJson(bundle.get()));
                }
            } else if (rest.equals("/blocks")) {
                Map<String, String> query = query(exchange.getRequestURI().getQuery());
                long from = Long.parseLong(query.getOrDefault("from", "1"));
                int limit = Math.min(500, Integer.parseInt(query.getOrDefault("limit", "50")));
                respond(exchange, 200, JSON.writeValueAsString(blockPage(from, limit)));
            } else if (rest.startsWith("/blocks/")) {
                long height = Long.parseLong(rest.substring("/blocks/".length()));
                Optional<AppBlock> block = gateway.block(height);
                if (block.isEmpty()) {
                    respond(exchange, 404, "{\"error\":\"no block\"}");
                } else {
                    respond(exchange, 200, JSON.writeValueAsString(blockView(block.get())));
                }
            } else if (rest.startsWith("/state/proof/")) {
                byte[] key = HEX.parseHex(rest.substring("/state/proof/".length()));
                Long height = height(exchange.getRequestURI().getQuery());
                Optional<StateProofEnvelope> envelope = height == null
                        ? gateway.stateProofEnvelope(key)
                        : gateway.stateProofEnvelopeAtHeight(height, key);
                if (envelope.isEmpty()) {
                    respond(exchange, 404, "{\"error\":\"no proof\"}");
                } else {
                    respond(exchange, 200, JSON.writeValueAsString(stateProofView(envelope.get())));
                }
            } else {
                respond(exchange, 404, "{\"error\":\"unknown path\"}");
            }
        } catch (RuntimeException failure) {
            respond(exchange, 500, "{\"error\":\"" + failure.getMessage() + "\"}");
        }
    }

    private Map<String, Object> blockPage(long from, int limit) {
        long tip = gateway.tipHeight();
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (long height = from; height <= tip && blocks.size() < limit; height++) {
            Optional<AppBlock> block = gateway.block(height);
            if (block.isEmpty()) continue;
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("height", height);
            summary.put("timestamp", block.get().timestamp());
            summary.put("stateRoot", HEX.formatHex(block.get().stateRoot()));
            summary.put("messageCount", block.get().messages().size());
            summary.put("certSignatures", block.get().cert().signatures().size());
            blocks.add(summary);
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("tipHeight", tip);
        page.put("blocks", blocks);
        page.put("chainId", gateway.chainId());
        return page;
    }

    private Map<String, Object> blockView(AppBlock block) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (AppMessage message : block.messages()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("messageId", message.getMessageIdHex());
            view.put("chainId", block.chainId());
            view.put("topic", message.getTopic());
            view.put("sender", HEX.formatHex(message.getSender()));
            view.put("senderSeq", message.getSenderSeq());
            view.put("bodyHex", HEX.formatHex(message.getBody()));
            messages.add(view);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("height", block.height());
        result.put("chainId", block.chainId());
        result.put("prevHash", HEX.formatHex(block.prevHash()));
        result.put("timestamp", block.timestamp());
        result.put("messagesRoot", HEX.formatHex(block.messagesRoot()));
        result.put("stateRoot", HEX.formatHex(block.stateRoot()));
        result.put("proposer", HEX.formatHex(block.proposer()));
        result.put("certSignatures", block.cert().signatures().size());
        result.put("messages", messages);
        return result;
    }

    static Map<String, Object> messageProofView(MessageInclusionProof proof) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", proof.schemaVersion());
        result.put("treeId", proof.treeId());
        result.put("chainId", proof.chainId());
        result.put("blockHeight", proof.blockHeight());
        result.put("blockHash", HEX.formatHex(proof.blockHash()));
        result.put("messagesRoot", HEX.formatHex(proof.messagesRoot()));
        result.put("messageId", HEX.formatHex(proof.messageId()));
        result.put("messageIndex", proof.messageIndex());
        result.put("leafCount", proof.leafCount());
        result.put("siblings", proof.siblings().stream().map(HEX::formatHex).toList());
        return result;
    }

    private Map<String, Object> stateProofView(StateProofEnvelope envelope) {
        StateProof proof = envelope.proof();
        long version = proof.snapshot().height();
        AppBlock block = gateway.block(version).orElseThrow();
        StateCommitmentIdentity identity = proof.snapshot().identity();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", identity.schemaVersion());
        result.put("profile", identity.profile().id());
        result.put("backend", identity.profile().backendFamily().name().toLowerCase(Locale.ROOT));
        result.put("commitmentFormatId", identity.profile().commitmentFormatId());
        result.put("proofEncodingId", identity.profile().proofEncodingId());
        result.put("nativeVersioning", identity.profile().nativeVersioning());
        result.put("physicalDelete", identity.profile().physicalDelete());
        result.put("formatFingerprint", HEX.formatHex(identity.profile().formatFingerprint()));
        result.put("genesisId", HEX.formatHex(identity.genesisId()));
        result.put("version", version);
        result.put("stateRoot", HEX.formatHex(proof.snapshot().stateRoot()));
        result.put("oldestProvableHeight", gateway.oldestProvableHeight());
        result.put("proofSchemaVersion", envelope.proofSchemaVersion());
        result.put("key", HEX.formatHex(proof.canonicalKey()));
        result.put("chainId", gateway.chainId());
        result.put("committedHeight", version);
        result.put("presence", proof.presence().name());
        if (proof.value() != null) {
            result.put("valueHex", HEX.formatHex(proof.value()));
        }
        result.put("blockHash", HEX.formatHex(envelope.blockHash()));
        result.put("proofWireHex", HEX.formatHex(proof.nativeProof()));
        result.put("block", certifiedBlockView(block, envelope.blockHash()));
        result.put("finalityCertificate", finalityCertificateView(envelope.finalityCertificate()));
        return result;
    }

    private static Map<String, Object> certifiedBlockView(AppBlock block, byte[] blockHash) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", block.version());
        result.put("height", block.height());
        result.put("prevHash", HEX.formatHex(block.prevHash()));
        result.put("l1Slot", block.l1Slot());
        result.put("l1BlockHash", HEX.formatHex(block.l1BlockHash()));
        result.put("timestamp", block.timestamp());
        result.put("messagesRoot", HEX.formatHex(block.messagesRoot()));
        result.put("stateRoot", HEX.formatHex(block.stateRoot()));
        result.put("blockHash", HEX.formatHex(blockHash));
        return result;
    }

    private static Map<String, Object> finalityCertificateView(FinalityCert certificate) {
        List<Map<String, Object>> signatures = new ArrayList<>();
        for (FinalityCert.Signature signature : certificate.signatures()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("signer", HEX.formatHex(signature.signer()));
            view.put("signature", HEX.formatHex(signature.signature()));
            signatures.add(view);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scheme", certificate.scheme());
        result.put("signatures", signatures);
        return result;
    }

    private static Long height(String query) {
        String value = query(query).get("height");
        return value == null ? null : Long.parseLong(value);
    }

    private static Map<String, String> query(String query) {
        Map<String, String> values = new LinkedHashMap<>();
        if (query == null) return values;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                values.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static byte[] blockHash(AppBlock block) {
        return AppBlockCodec.blockHash(block);
    }
}
