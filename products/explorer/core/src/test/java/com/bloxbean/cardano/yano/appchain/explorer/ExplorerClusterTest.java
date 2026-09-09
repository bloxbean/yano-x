package com.bloxbean.cardano.yano.appchain.explorer;

import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.appchain.attest.client.AttestTrust;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.DocTrailContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.KvRegistryContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-050 §7: the explorer against real three-member doc-trail and kv-registry clusters, through
 * the same REST shapes the node serves. Indexing from height 0, verification levels, the trail
 * head check, the archiver, row and state bundles under every trust input, tampering, rebuild
 * determinism, and the service routes. Writes the goldens the CLI and console tests pin.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExplorerClusterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final String API_KEY = "explorer-test-key";
    private static final byte[] DOCUMENT_ONE = "quality certificate v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DOCUMENT_TWO = "quality certificate v2".getBytes(StandardCharsets.UTF_8);

    private StockTestCluster cluster;
    private GatewayHttpBridge bridge;
    private Path tempDir;
    private String firstMessageId;
    private String secondMessageId;

    @BeforeAll
    void start() throws Exception {
        tempDir = Files.createTempDirectory("explorer-test-");
        cluster = StockTestCluster.start("explorer-documents", DocTrailContract.STATE_MACHINE_ID, 3);
        bridge = GatewayHttpBridge.start(cluster.node(2), API_KEY);
        firstMessageId = cluster.submit(DocTrailContract.DEFAULT_TOPIC, DocTrailContract.append("case-1",
                ContentArchiver.sha256(DOCUMENT_ONE), "https://docs.example/case-1/v1"));
        secondMessageId = cluster.submit(DocTrailContract.DEFAULT_TOPIC, DocTrailContract.append("case-1",
                ContentArchiver.sha256(DOCUMENT_TWO), "https://docs.example/case-1/v2"));
        cluster.submit(DocTrailContract.DEFAULT_TOPIC, DocTrailContract.append("case-2",
                ContentArchiver.sha256(DOCUMENT_ONE), ""));
    }

    @AfterAll
    void stop() {
        if (bridge != null) bridge.close();
        if (cluster != null) cluster.close();
    }

    private Explorer explorer(Follower.Trust trust) {
        IndexStore store = IndexStore.inMemory();
        return new Explorer(store, new ContentArchiver(store, tempDir.resolve("content-" + System.nanoTime())),
                bridge.baseUrl(), API_KEY, trust);
    }

    @Test
    void indexesVerifiesArchivesAndBundles() throws Exception {
        try (Explorer explorer = explorer(null)) {
            String chainId = cluster.chainId();
            long tip = cluster.node(2).tipHeight();
            assertThat(explorer.catchUp(chainId, 1000)).isEqualTo(tip);
            Explorer.ChainView view = explorer.chain(chainId);
            assertThat(view.identity().applicationId()).isEqualTo(DocTrailContract.STATE_MACHINE_ID);
            assertThat(view.lagBlocks()).isZero();
            assertThat(view.levels()).containsOnlyKeys(VerificationLevel.VERIFIED_DECLARED.name());
            for (long height = 1; height <= tip; height++) {
                IndexedBlock block = explorer.store().block(chainId, height).orElseThrow();
                assertThat(block.canonicalHex()).isNotEmpty();
                assertThat(block.blockRecordProofJson()).as("block record captured at " + height).isNotEmpty();
                assertThat(block.memberKeysHex()).containsExactlyInAnyOrderElementsOf(cluster.memberKeysHex());
                assertThat(block.threshold()).isEqualTo(cluster.threshold());
            }

            // Trail view: two revisions, recomputed head equals the authenticated head.
            Explorer.SubjectView trail = explorer.subject(chainId, StockModules.DOC_TRAIL, "case-1", true);
            assertThat(trail.rows()).hasSize(2);
            assertThat(trail.derived()).containsEntry("revisionCount", 2);
            assertThat(trail.stateCheck()).containsEntry("status", "MATCH").containsEntry("presence", "PRESENT");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> revisions = (List<Map<String, Object>>) trail.derived().get("revisions");
            assertThat(revisions.getFirst()).containsEntry("availability", "FINALIZED");

            // Archiver: the body hashes to the entry, so the revision becomes content-verified.
            IndexStore.ContentRecord record = explorer.archiver().add(DOCUMENT_ONE, "test",
                    HEX.formatHex(ContentArchiver.sha256(DOCUMENT_ONE)));
            assertThat(record.status()).isEqualTo(ContentArchiver.MATCHED);
            assertThat(explorer.archiver().read(record.sha256Hex())).contains(DOCUMENT_ONE);
            IndexStore.ContentRecord mismatch = explorer.archiver().add(DOCUMENT_TWO, "test", "ab".repeat(32));
            assertThat(mismatch.status()).isEqualTo(ContentArchiver.MISMATCH);
            assertThat(explorer.archiver().read(mismatch.sha256Hex())).isEmpty();
            trail = explorer.subject(chainId, StockModules.DOC_TRAIL, "case-1", false);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> after = (List<Map<String, Object>>) trail.derived().get("revisions");
            assertThat(after.getFirst()).containsEntry("availability", "CONTENT_VERIFIED");
            assertThat(after.get(1)).containsEntry("availability", "FINALIZED");

            // Row bundle under every trust input.
            Bundles.RowBundle row = explorer.rowBundle(chainId, secondMessageId);
            assertThat(row.ingestLevel()).isEqualTo(VerificationLevel.VERIFIED_DECLARED);
            assertThat(row.verification()).containsEntry("consistent", true);
            RowVerifier.Verification declared = RowVerifier.verify(row, new AttestTrust.BundleDeclared());
            assertThat(declared.failures()).isEmpty();
            assertThat(declared.trustLevel()).isEqualTo(ProofLabVocabulary.TrustLevel.INTERNAL_CONSISTENCY_ONLY);
            AttestTrust.CallerPinned pinned = new AttestTrust.CallerPinned(chainId, Set.copyOf(cluster.memberKeysHex()),
                    cluster.threshold(), row.profile(), row.stateGenesisIdHex());
            RowVerifier.Verification pinnedResult = RowVerifier.verify(row, pinned);
            assertThat(pinnedResult.failures()).isEmpty();
            assertThat(pinnedResult.trustLevel()).isEqualTo(ProofLabVocabulary.TrustLevel.CALLER_PINNED_ROOT);
            assertThat(pinnedResult.certSignatures()).isGreaterThanOrEqualTo(cluster.threshold());
            assertThat(pinnedResult.checks()).anyMatch(check -> check.contains("block record"));

            // The JSON round trip preserves verification.
            String rowJson = Bundles.toJson(row);
            Bundles.RowBundle parsed = Bundles.rowFromJson(rowJson);
            assertThat(RowVerifier.verify(parsed, pinned).consistent()).isTrue();

            // Wrong member set, tampered body, tampered root.
            List<String> wrongMembers = new ArrayList<>(cluster.memberKeysHex());
            wrongMembers.set(0, "cc".repeat(32));
            RowVerifier.Verification wrong = RowVerifier.verify(parsed, new AttestTrust.CallerPinned(chainId,
                    Set.copyOf(wrongMembers), cluster.threshold(), row.profile(), row.stateGenesisIdHex()));
            assertThat(wrong.consistent()).isFalse();
            assertThat(wrong.failures()).anyMatch(failure -> failure.startsWith("finality evidence invalid"));
            ObjectNode tamperedBody = (ObjectNode) JSON.readTree(rowJson);
            ((ObjectNode) tamperedBody.get("message")).put("bodyHex", "ff" + row.message().bodyHex().substring(2));
            RowVerifier.Verification body = RowVerifier.verify(Bundles.rowFromJson(tamperedBody.toString()), pinned);
            assertThat(body.consistent()).isFalse();
            assertThat(body.failures()).anyMatch(failure -> failure.contains("message copy differs"));
            ObjectNode tamperedRoot = (ObjectNode) JSON.readTree(rowJson);
            tamperedRoot.put("stateRoot", "00".repeat(32));
            RowVerifier.Verification root = RowVerifier.verify(Bundles.rowFromJson(tamperedRoot.toString()), pinned);
            assertThat(root.consistent()).isFalse();
            assertThat(root.failures()).anyMatch(failure -> failure.contains("does not carry the certified block"));

            // State bundle for the trail head at the tip.
            Bundles.StateBundle state = explorer.stateBundle(chainId, StockModules.DOC_TRAIL, "case-1", null);
            assertThat(state.decodedFact()).containsEntry("revision", 2L);
            RowVerifier.Verification stateResult = RowVerifier.verify(state, pinned);
            assertThat(stateResult.failures()).isEmpty();
            String stateJson = Bundles.toJson(state);
            ObjectNode tamperedFact = (ObjectNode) JSON.readTree(stateJson);
            ((ObjectNode) tamperedFact.get("decodedFact")).put("revision", 3);
            RowVerifier.Verification fact = RowVerifier.verify(Bundles.stateFromJson(tamperedFact.toString()), pinned);
            assertThat(fact.consistent()).isFalse();
            assertThat(fact.failures()).anyMatch(failure -> failure.contains("decoded fact differs"));
            // An absent subject proves absence, and a bundle claiming a fact for it is rejected.
            Bundles.StateBundle absent = explorer.stateBundle(chainId, StockModules.DOC_TRAIL, "case-9", null);
            assertThat(absent.decodedFact()).isEmpty();
            assertThat(RowVerifier.verify(absent, pinned).checks()).anyMatch(check -> check.contains("absent"));

            // Rebuild determinism: a second index from the same node exports identically.
            StringBuilder first = new StringBuilder();
            explorer.store().export(chainId, first);
            try (Explorer again = explorer(null)) {
                again.catchUp(chainId, 1000);
                StringBuilder second = new StringBuilder();
                again.store().export(chainId, second);
                assertThat(second.toString()).isEqualTo(first.toString());
            }
            assertThat(first.toString()).contains("\"type\":\"row\"").contains("\"subject\":\"case-2\"");

            // Search.
            assertThat(explorer.store().search(chainId, "case-", 10)).anyMatch(hit -> hit.subject().equals("case-1"));
            assertThat(explorer.store().search(chainId, firstMessageId, 10)).anyMatch(hit -> hit.type().equals("message"));
            assertThat(explorer.store().search(chainId, "1", 10)).anyMatch(hit -> hit.type().equals("block"));

            service(explorer, chainId, pinned);
            goldens(rowJson, stateJson, first.toString(), row);
        }
    }

    private void service(Explorer explorer, String chainId, AttestTrust.CallerPinned pinned) throws Exception {
        try (ExplorerService service = ExplorerService.start(explorer, List.of(chainId),
                new InetSocketAddress("127.0.0.1", 0), 0)) {
            HttpClient http = HttpClient.newHttpClient();
            JsonNode chains = get(http, service.baseUrl() + "/chains");
            assertThat(chains.get(0).path("chainId").asText()).isEqualTo(chainId);
            assertThat(chains.get(0).path("levels").path("VERIFIED_DECLARED").asLong()).isGreaterThan(0);
            JsonNode trail = get(http, service.baseUrl() + "/chains/" + chainId + "/trails/case-1");
            assertThat(trail.path("rows")).hasSize(2);
            assertThat(trail.path("stateCheck").path("status").asText()).isEqualTo("MATCH");
            JsonNode proof = get(http, service.baseUrl() + "/chains/" + chainId + "/messages/" + secondMessageId + "/proof");
            assertThat(proof.path("schema").asText()).isEqualTo(Bundles.ROW_SCHEMA);
            assertThat(RowVerifier.verify(Bundles.rowFromJson(proof.toString()), pinned).consistent()).isTrue();
            JsonNode stateProof = get(http, service.baseUrl() + "/chains/" + chainId + "/trails/case-1/proof");
            assertThat(RowVerifier.verify(Bundles.stateFromJson(stateProof.toString()), pinned).consistent()).isTrue();
            JsonNode search = get(http, service.baseUrl() + "/chains/" + chainId + "/search?q=case-");
            assertThat(search).isNotEmpty();
            JsonNode block = get(http, service.baseUrl() + "/chains/" + chainId + "/blocks/1");
            assertThat(block.path("messages")).hasSize(1);
            assertThat(block.path("messages").get(0).path("rows")).hasSize(1);
            assertThat(status(http, service.baseUrl() + "/chains/" + chainId + "/content/" + "00".repeat(32))).isEqualTo(404);
            String sha = HEX.formatHex(ContentArchiver.sha256(DOCUMENT_ONE));
            HttpResponse<byte[]> body = http.send(HttpRequest.newBuilder(URI.create(
                    service.baseUrl() + "/chains/" + chainId + "/content/" + sha)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(body.statusCode()).isEqualTo(200);
            assertThat(body.body()).isEqualTo(DOCUMENT_ONE);
            assertThat(body.headers().firstValue("X-Content-Sha256")).contains(sha);
            JsonNode meta = get(http, service.baseUrl() + "/chains/" + chainId + "/content/" + sha + "?meta=1");
            assertThat(meta.path("status").asText()).isEqualTo(ContentArchiver.MATCHED);
            assertThat(meta.path("available").asBoolean()).isTrue();
            assertThat(status(http, service.baseUrl() + "/chains/" + chainId + "/messages/" + "00".repeat(32))).isEqualTo(404);
            HttpURLConnection post = (HttpURLConnection) URI.create(service.baseUrl() + "/chains").toURL().openConnection();
            post.setRequestMethod("POST");
            assertThat(post.getResponseCode()).isEqualTo(405);
        }
    }

    @Test
    void pinnedFollowerLabelsBlocksAndRefusesWrongMembers() throws Exception {
        String chainId = cluster.chainId();
        try (Explorer explorer = explorer(new Follower.Trust(Set.copyOf(cluster.memberKeysHex()), cluster.threshold()))) {
            explorer.catchUp(chainId, 1000);
            assertThat(explorer.store().levels(chainId)).containsOnlyKeys(VerificationLevel.VERIFIED_PINNED.name());
        }
        List<String> wrong = new ArrayList<>(cluster.memberKeysHex());
        wrong.set(1, "dd".repeat(32));
        try (Explorer explorer = explorer(new Follower.Trust(Set.copyOf(wrong), cluster.threshold()))) {
            assertThatThrownBy(() -> explorer.catchUp(chainId, 1000))
                    .isInstanceOf(ExplorerException.class)
                    .hasMessageContaining("does not verify");
            assertThat(explorer.store().checkpoint(chainId).height()).isZero();
        }
    }

    @Test
    void identityMismatchIsRefused() throws Exception {
        String chainId = cluster.chainId();
        IndexStore store = IndexStore.inMemory();
        store.pinIdentity(new IndexIdentity(chainId, "other-machine", "mpf-blake2b256-v1", "ee".repeat(32), ""));
        try (Explorer explorer = new Explorer(store, new ContentArchiver(store, tempDir.resolve("x")),
                bridge.baseUrl(), API_KEY, null)) {
            assertThatThrownBy(() -> explorer.catchUp(chainId, 10))
                    .isInstanceOf(ExplorerException.class)
                    .hasMessageContaining("another identity");
        }
    }

    @Test
    void kvRegistryChainRoutesEveryTopicToItsMachine() throws Exception {
        try (StockTestCluster registry = StockTestCluster.start("explorer-registry", KvRegistryContract.STATE_MACHINE_ID, 3);
             GatewayHttpBridge registryBridge = GatewayHttpBridge.start(registry.node(1), API_KEY)) {
            byte[] key = "supplier-42".getBytes(StandardCharsets.UTF_8);
            registry.submit("kv.command.v1", KvRegistryContract.put(key, "active".getBytes(StandardCharsets.UTF_8)));
            registry.submit("kv.command.v1", KvRegistryContract.put(key, "suspended".getBytes(StandardCharsets.UTF_8)));
            String other = registry.submit(KvRegistryContract.DEFAULT_TOPIC,
                    KvRegistryContract.put("supplier-7".getBytes(StandardCharsets.UTF_8), new byte[] {1}));
            IndexStore store = IndexStore.inMemory();
            try (Explorer explorer = new Explorer(store, new ContentArchiver(store, tempDir.resolve("kv")),
                    registryBridge.baseUrl(), API_KEY, null)) {
                explorer.catchUp(registry.chainId(), 1000);
                Explorer.SubjectView view = explorer.subject(registry.chainId(), StockModules.KV_REGISTRY,
                        HEX.formatHex(key), true);
                assertThat(view.rows()).hasSize(2);
                assertThat(view.rows().get(0).fields()).containsEntry("keyText", "supplier-42");
                assertThat(view.derived()).containsEntry("latestValueText", "suspended");
                assertThat(view.stateCheck()).containsEntry("status", "MATCH");
                registry.submit("kv.command.v1", KvRegistryContract.delete(key));
                explorer.catchUp(registry.chainId(), 1000);
                view = explorer.subject(registry.chainId(), StockModules.KV_REGISTRY, HEX.formatHex(key), true);
                assertThat(view.derived()).containsEntry("latestOp", "DELETE");
                assertThat(view.stateCheck()).containsEntry("status", "MATCH").containsEntry("presence", "ABSENT");
                assertThat(explorer.store().message(registry.chainId(), other)).isPresent();
            }
        }
    }

    // --- helpers ---

    private static JsonNode get(HttpClient http, String url) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(url).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("*");
        return JSON.readTree(response.body());
    }

    private static int status(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private void goldens(String rowJson, String stateJson, String export, Bundles.RowBundle row) throws IOException {
        if (!Boolean.getBoolean("yano.explorer.golden.write")) return;
        ObjectNode members = JSON.createObjectNode();
        members.put("chainId", cluster.chainId());
        members.put("threshold", cluster.threshold());
        members.set("memberKeysHex", JSON.valueToTree(cluster.memberKeysHex()));
        members.put("profile", row.profile());
        members.put("genesisIdHex", row.stateGenesisIdHex());
        for (String property : List.of("yano.explorer.golden.dir", "yano.explorer.cli.golden.dir",
                "yano.explorer.ui.golden.dir")) {
            Path directory = Path.of(System.getProperty(property));
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("golden-row-bundle.json"), rowJson, StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("golden-state-bundle.json"), stateJson, StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("golden-members.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(members) + "\n", StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("golden-export.jsonl"), export, StandardCharsets.UTF_8);
            Files.write(directory.resolve("golden-document.txt"), DOCUMENT_TWO);
        }
    }
}
