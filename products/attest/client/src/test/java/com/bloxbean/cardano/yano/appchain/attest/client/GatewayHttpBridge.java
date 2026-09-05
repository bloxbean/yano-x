package com.bloxbean.cardano.yano.appchain.attest.client;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppAnchorCommitment;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
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
 * Serves one in-process gateway over the REST paths the attest client uses,
 * rendering each document exactly the way the Yano node resource does so the
 * pinned SDK decoders accept it. This keeps the attest tests on real cluster
 * output without booting the full node application.
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
            if (!"GET".equals(method)) {
                respond(exchange, 405, "{\"error\":\"method\"}");
                return;
            }
            if (rest.equals("/status")) {
                respond(exchange, 200, JSON.writeValueAsString(gateway.status()));
            } else if (rest.equals("/anchor/commitment")) {
                Optional<AppAnchorCommitment> commitment = gateway.latestAnchorCommitment();
                if (commitment.isEmpty()) {
                    respond(exchange, 404, "{\"error\":\"no anchor\"}");
                } else {
                    respond(exchange, 200, JSON.writeValueAsString(anchorView(commitment.get())));
                }
            } else if (rest.startsWith("/messages/") && rest.endsWith("/proof")) {
                byte[] id = HEX.parseHex(rest.substring("/messages/".length(), rest.length() - "/proof".length()));
                Optional<MessageInclusionProof> proof = gateway.messageInclusionProof(id);
                if (proof.isEmpty()) {
                    respond(exchange, 404, "{\"error\":\"not finalized\"}");
                } else {
                    respond(exchange, 200, JSON.writeValueAsString(messageProofView(proof.get())));
                }
            } else if (rest.startsWith("/messages/")) {
                byte[] id = HEX.parseHex(rest.substring("/messages/".length()));
                Optional<Map<String, Object>> view = messageView(id);
                if (view.isEmpty()) {
                    respond(exchange, 404, "{\"error\":\"not finalized\"}");
                } else {
                    respond(exchange, 200, JSON.writeValueAsString(view.get()));
                }
            } else if (rest.startsWith("/evidence/")) {
                byte[] id = HEX.parseHex(rest.substring("/evidence/".length()));
                var bundle = gateway.evidence(id);
                if (bundle.isEmpty()) {
                    respond(exchange, 404, "{\"error\":\"not finalized\"}");
                } else {
                    respond(exchange, 200, EvidenceBundleCodec.toJson(bundle.get()));
                }
            } else if (rest.startsWith("/state/proof/")) {
                byte[] key = HEX.parseHex(rest.substring("/state/proof/".length()));
                Long height = queryHeight(exchange.getRequestURI().getQuery());
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

    private Optional<Map<String, Object>> messageView(byte[] id) {
        String idHex = HEX.formatHex(id);
        return gateway.messageHeight(id).flatMap(height -> gateway.block(height).flatMap(block -> {
            int index = 0;
            for (AppMessage message : block.messages()) {
                if (message.getMessageIdHex().equalsIgnoreCase(idHex)) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("messageId", message.getMessageIdHex());
                    result.put("chainId", block.chainId());
                    result.put("height", height);
                    result.put("index", index);
                    result.put("topic", message.getTopic());
                    result.put("sender", HEX.formatHex(message.getSender()));
                    result.put("senderSeq", message.getSenderSeq());
                    result.put("bodyHex", HEX.formatHex(message.getBody()));
                    return Optional.of(result);
                }
                index++;
            }
            return Optional.empty();
        }));
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

    private static Map<String, Object> anchorView(AppAnchorCommitment commitment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chainId", commitment.chainId());
        result.put("mode", commitment.mode());
        result.put("anchoredHeight", commitment.anchoredHeight());
        result.put("stateRoot", HEX.formatHex(commitment.stateRoot()));
        result.put("blockHash", HEX.formatHex(commitment.blockHash()));
        result.put("transactionHash", commitment.transactionHash());
        result.put("l1Slot", commitment.l1Slot());
        return result;
    }

    private static Long queryHeight(String query) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals("height")) {
                return Long.parseLong(URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return null;
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
