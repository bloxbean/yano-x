package com.bloxbean.cardano.yano.appchain.explorer;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundleCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.MessageInclusionProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.api.appchain.state.StateSnapshot;
import com.bloxbean.cardano.yano.appchain.attest.client.AttestTrust;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.DocTrailContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The explorer facade: one index, one node, many chains. Reads come from the index; proofs and
 * state checks go to the node; bundles are assembled from captured material (ADR-050 §2.4, §2.5).
 */
public final class Explorer implements AutoCloseable {
    public static final int DEFAULT_BATCH = 200;
    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the index knows about one chain, plus the node's tip when reachable. */
    public record ChainView(IndexIdentity identity, IndexStore.Checkpoint checkpoint, long tipHeight,
                            long lagBlocks, Map<String, Long> levels, Map<String, Long> topics,
                            String diagnostic) { }

    /** The derived view of one subject with its proof-backed state check. */
    public record SubjectView(String module, String kind, String subject, List<IndexStore.RowRecord> rows,
                              Map<String, Object> derived, Map<String, Object> stateCheck) { }

    private final IndexStore store;
    private final ContentArchiver archiver;
    private final String nodeUrl;
    private final String apiKey;
    private final Follower.Trust trust;
    private final Map<String, Follower> followers = new ConcurrentHashMap<>();
    private final Map<String, NodeSource> sources = new ConcurrentHashMap<>();
    private final Map<String, String> diagnostics = new ConcurrentHashMap<>();

    public Explorer(IndexStore store, ContentArchiver archiver, String nodeUrl, String apiKey, Follower.Trust trust) {
        this.store = Objects.requireNonNull(store, "store");
        this.archiver = Objects.requireNonNull(archiver, "archiver");
        this.nodeUrl = NodeSource.normalize(nodeUrl);
        this.apiKey = apiKey;
        this.trust = trust;
    }

    public IndexStore store() {
        return store;
    }

    public ContentArchiver archiver() {
        return archiver;
    }

    public String nodeUrl() {
        return nodeUrl;
    }

    public List<String> discoverChains() {
        return NodeSource.listChains(nodeUrl, apiKey);
    }

    /** Chains the index holds plus the ones registered in this process. */
    public List<String> chains() {
        List<String> ids = new ArrayList<>(store.chains());
        for (String id : followers.keySet()) if (!ids.contains(id)) ids.add(id);
        ids.sort(String::compareTo);
        return ids;
    }

    public NodeSource source(String chainId) {
        return sources.computeIfAbsent(chainId, id -> new NodeSource(nodeUrl, id, apiKey));
    }

    public Follower follower(String chainId) {
        return followers.computeIfAbsent(chainId, id -> new Follower(source(id), store, trust));
    }

    /** Indexes up to {@code maxBlocks} new blocks of one chain; failures are recorded, not thrown. */
    public long catchUp(String chainId, int maxBlocks) {
        try {
            long height = follower(chainId).catchUp(maxBlocks);
            diagnostics.remove(chainId);
            return height;
        } catch (ExplorerException failure) {
            diagnostics.put(chainId, failure.error() + ": " + failure.getMessage());
            throw failure;
        }
    }

    public ChainView chain(String chainId) {
        IndexIdentity identity = store.identity(chainId).orElse(null);
        if (identity == null) {
            try {
                identity = follower(chainId).ensureIdentity();
            } catch (ExplorerException unavailable) {
                throw new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                        "chain " + chainId + " is not indexed and the node is unreachable: " + unavailable.getMessage());
            }
        }
        IndexStore.Checkpoint checkpoint = store.checkpoint(chainId);
        long tip = -1;
        String diagnostic = diagnostics.getOrDefault(chainId, "");
        try {
            tip = source(chainId).tipHeight();
        } catch (ExplorerException unreachable) {
            diagnostic = diagnostic.isEmpty() ? "node unreachable: " + unreachable.getMessage() : diagnostic;
        }
        long lag = tip < 0 ? -1 : Math.max(0, tip - checkpoint.height());
        return new ChainView(identity, checkpoint, tip, lag, store.levels(chainId), store.topics(chainId), diagnostic);
    }

    // --- subjects ---

    public SubjectView subject(String chainId, String module, String subject, boolean checkState) {
        List<IndexStore.RowRecord> rows = store.subjectRows(chainId, module, subject, 10_000);
        if (rows.isEmpty()) {
            throw new ExplorerException(ExplorerException.Error.INVALID,
                    "no " + module + " rows for subject " + subject);
        }
        String kind = rows.getFirst().kind();
        Map<String, Object> derived = derive(module, rows);
        Map<String, Object> check = checkState ? stateCheck(chainId, module, subject, derived) : Map.of("status", "UNCHECKED");
        return new SubjectView(module, kind, subject, rows, derived, check);
    }

    /** The module's derived view: what the finalized commands imply if every one of them applied. */
    Map<String, Object> derive(String module, List<IndexStore.RowRecord> rows) {
        Map<String, Object> derived = new LinkedHashMap<>();
        switch (module) {
            case StockModules.DOC_TRAIL -> {
                List<byte[]> hashes = new ArrayList<>();
                List<byte[]> authors = new ArrayList<>();
                List<Map<String, Object>> revisions = new ArrayList<>();
                int revision = 0;
                for (IndexStore.RowRecord row : rows) {
                    if (!"APPEND".equals(row.op())) continue;
                    String entryHash = String.valueOf(row.fields().get("entryHashHex"));
                    String author = String.valueOf(row.fields().get("authorHex"));
                    hashes.add(HEX.parseHex(entryHash));
                    authors.add(HEX.parseHex(author));
                    revision++;
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("revision", revision);
                    view.put("entryHashHex", entryHash);
                    view.put("reference", row.fields().get("reference"));
                    view.put("authorHex", author);
                    view.put("height", row.height());
                    view.put("index", row.index());
                    view.put("messageId", row.messageIdHex());
                    view.put("level", row.level().name());
                    Optional<IndexStore.ContentRecord> content = archiver.forEntryHash(entryHash);
                    view.put("availability", content.isPresent() && ContentArchiver.MATCHED.equals(content.get().status())
                            ? "CONTENT_VERIFIED" : "FINALIZED");
                    content.ifPresent(record -> view.put("contentSha256", record.sha256Hex()));
                    revisions.add(view);
                }
                derived.put("revisionCount", revision);
                derived.put("headDigestHex", revision == 0 ? "" : HEX.formatHex(DocTrailContract.computeHead(hashes, authors)));
                derived.put("revisions", revisions);
            }
            case StockModules.KV_REGISTRY -> {
                IndexStore.RowRecord last = rows.getLast();
                derived.put("latestOp", last.op());
                derived.put("latestValueHex", last.fields().getOrDefault("valueHex", ""));
                derived.put("latestValueText", last.fields().getOrDefault("valueText", ""));
                derived.put("latestSenderHex", last.fields().getOrDefault("senderHex", ""));
                derived.put("commandCount", rows.size());
            }
            case StockModules.BALANCES -> {
                java.math.BigInteger credited = java.math.BigInteger.ZERO;
                for (IndexStore.RowRecord row : rows) {
                    credited = credited.add(new java.math.BigInteger(String.valueOf(row.fields().get("amount"))));
                }
                derived.put("creditedIfApplied", credited.toString());
                derived.put("commandCount", rows.size());
            }
            case StockModules.APPROVALS -> {
                int approvals = 0;
                int rejections = 0;
                for (IndexStore.RowRecord row : rows) {
                    if ("PROPOSE".equals(row.op())) {
                        derived.put("proposerHex", row.fields().get("senderHex"));
                        derived.put("payloadHashHex", row.fields().get("payloadHashHex"));
                        derived.put("required", row.fields().get("required"));
                        derived.put("deadlineMillis", row.fields().get("deadlineMillis"));
                    } else if ("APPROVE".equals(row.op())) {
                        approvals++;
                    } else if ("REJECT".equals(row.op())) {
                        rejections++;
                    }
                }
                derived.put("approveCommands", approvals);
                derived.put("rejectCommands", rejections);
            }
            case StockModules.AUTHENTICATED_MAP -> {
                IndexStore.RowRecord last = rows.getLast();
                derived.put("latestOp", last.op());
                derived.put("latestValueHex", last.fields().getOrDefault("valueHex", ""));
                derived.put("mutationCount", rows.size());
            }
            default -> derived.put("commandCount", rows.size());
        }
        return derived;
    }

    /** Fetches the subject's authenticated value at the tip and compares it with the derived view. */
    public Map<String, Object> stateCheck(String chainId, String module, String subject, Map<String, Object> derived) {
        Map<String, Object> check = new LinkedHashMap<>();
        String subjectId = Subjects.forModule(module);
        if (subjectId == null) {
            check.put("status", "UNCHECKED");
            return check;
        }
        try {
            Map<String, String> coordinates = Subjects.coordinates(module, subject);
            String component = componentFor(chainId, module);
            byte[] key = Subjects.key(subjectId, coordinates, component);
            Optional<String> proofJson = source(chainId).stateProofJson(HEX.formatHex(key), null);
            if (proofJson.isEmpty()) {
                check.put("status", "UNCHECKED");
                check.put("reason", "the node returned no proof for the subject key");
                return check;
            }
            AppChainClient.Proof proof = AppChainClient.decodeProofEnvelope(proofJson.get());
            check.put("height", proof.committedHeight());
            check.put("presence", proof.presence().name());
            check.put("keyHex", proof.keyHex());
            if (proof.presence() != AppChainClient.ProofPresence.PRESENT) {
                check.put("status", derivedImpliesPresence(module, derived) ? "DIVERGES" : "MATCH");
                check.put("reason", "the subject is absent from the authenticated state");
                return check;
            }
            Map<String, Object> fact = Subjects.decode(subjectId, HEX.parseHex(proof.valueHex()));
            check.put("fact", fact);
            check.put("status", compare(module, derived, fact));
        } catch (ExplorerException failure) {
            check.put("status", "UNCHECKED");
            check.put("reason", failure.getMessage());
        } catch (RuntimeException malformed) {
            check.put("status", "UNCHECKED");
            check.put("reason", "state read failed: " + malformed.getMessage());
        }
        return check;
    }

    private static boolean derivedImpliesPresence(String module, Map<String, Object> derived) {
        return switch (module) {
            case StockModules.DOC_TRAIL -> ((Number) derived.getOrDefault("revisionCount", 0)).intValue() > 0;
            case StockModules.KV_REGISTRY -> "PUT".equals(derived.get("latestOp"));
            default -> false;
        };
    }

    private static String compare(String module, Map<String, Object> derived, Map<String, Object> fact) {
        return switch (module) {
            case StockModules.DOC_TRAIL -> String.valueOf(derived.get("headDigestHex")).equals(String.valueOf(fact.get("headDigestHex")))
                    && String.valueOf(derived.get("revisionCount")).equals(String.valueOf(fact.get("revision")))
                    ? "MATCH" : "DIVERGES";
            case StockModules.KV_REGISTRY -> "PUT".equals(derived.get("latestOp"))
                    && String.valueOf(derived.get("latestValueHex")).equals(String.valueOf(fact.get("valueHex")))
                    ? "MATCH" : "DIVERGES";
            case StockModules.APPROVALS -> String.valueOf(derived.get("payloadHashHex")).equals(String.valueOf(fact.get("payloadDigestHex")))
                    && String.valueOf(derived.get("approveCommands")).equals(String.valueOf(fact.get("approvalCount")))
                    ? "MATCH" : "DIVERGES";
            case StockModules.AUTHENTICATED_MAP -> String.valueOf(derived.get("latestValueHex")).equals(String.valueOf(fact.get("valueHex")))
                    && !"REVOKE".equals(derived.get("latestOp")) == "ACTIVE".equals(fact.get("status"))
                    ? "MATCH" : "DIVERGES";
            default -> "READ";
        };
    }

    /**
     * The composite component that owns a module's keys, or empty for a plain chain. A plain
     * chain's single component lives in the {@code application/v1} namespace; composite members
     * live in {@code component/<id>/v1} and their keys carry the component prefix.
     */
    public String componentFor(String chainId, String module) {
        JsonNode components = source(chainId).status().path("capabilityManifest").path("components");
        StockModules.Module stock = StockModules.all().stream().filter(m -> m.id().equals(module)).findFirst().orElse(null);
        String byId = "";
        for (JsonNode component : components) {
            String id = component.path("id").asText("");
            if (!component.path("stateNamespace").asText("").startsWith("component/")) continue;
            if (id.equals(module)) byId = id;
            for (JsonNode topic : component.path("topics")) {
                if (stock != null && stock.accepts(topic.asText(""))) return id;
            }
        }
        return byId;
    }

    // --- bundles ---

    public Bundles.RowBundle rowBundle(String chainId, String messageIdHex) {
        IndexedMessage message = store.message(chainId, messageIdHex).orElseThrow(() ->
                new ExplorerException(ExplorerException.Error.INVALID, "message " + messageIdHex + " is not indexed"));
        IndexedBlock block = store.block(chainId, message.height()).orElseThrow();
        IndexIdentity identity = store.identity(chainId).orElseThrow();
        if (block.canonicalHex().isEmpty()) {
            throw new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                    "block " + block.height() + " was indexed " + block.level() + "; its evidence was not retained, so the row cannot be proven from the index");
        }
        AppBlock canonical = AppBlockCodec.deserialize(HEX.parseHex(block.canonicalHex()));
        MessageInclusionProof proof = MessageInclusionProof.fromBlock(canonical, HEX.parseHex(messageIdHex))
                .orElseThrow(() -> new ExplorerException(ExplorerException.Error.INVALID,
                        "the certified block does not contain message " + messageIdHex));
        Bundles.RowBundle bundle = new Bundles.RowBundle(chainId, identity.applicationId(), identity.profile(),
                identity.stateGenesisIdHex(), message.height(), message.index(), block.blockHashHex(),
                block.stateRootHex(), message, inclusionProofJson(proof), block.blockRecordProofJson(),
                evidenceJson(identity, messageIdHex, canonical, block), block.level(), Map.of());
        return bundle.withVerification(Bundles.explanation(RowVerifier.verify(bundle, new AttestTrust.BundleDeclared())));
    }

    public Bundles.StateBundle stateBundle(String chainId, String module, String subject, Long height) {
        String subjectId = Subjects.forModule(module);
        if (subjectId == null) {
            throw new ExplorerException(ExplorerException.Error.USAGE, "module " + module + " has no state subject");
        }
        IndexIdentity identity = store.identity(chainId).orElseThrow(() ->
                new ExplorerException(ExplorerException.Error.INVALID, "chain " + chainId + " is not indexed"));
        Map<String, String> coordinates = Subjects.coordinates(module, subject);
        String component = componentFor(chainId, module);
        byte[] key = Subjects.key(subjectId, coordinates, component);
        long at = height != null ? height : store.checkpoint(chainId).height();
        IndexedBlock block = store.block(chainId, at).orElseThrow(() ->
                new ExplorerException(ExplorerException.Error.INVALID, "height " + at + " is not indexed"));
        if (block.canonicalHex().isEmpty() || block.messageCount() == 0) {
            throw new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                    "height " + at + " has no certified block with a message; choose a height with finalized messages");
        }
        String proofJson = source(chainId).stateProofJson(HEX.formatHex(key), at).orElseThrow(() ->
                new ExplorerException(ExplorerException.Error.UNAVAILABLE, "the node has no proof at height " + at));
        AppChainClient.Proof proof = AppChainClient.decodeProofEnvelope(proofJson);
        Map<String, Object> fact = proof.presence() == AppChainClient.ProofPresence.PRESENT
                ? Subjects.decode(subjectId, HEX.parseHex(proof.valueHex())) : Map.of();
        AppBlock canonical = AppBlockCodec.deserialize(HEX.parseHex(block.canonicalHex()));
        String firstId = canonical.messages().getFirst().getMessageIdHex();
        Bundles.StateBundle bundle = new Bundles.StateBundle(chainId, identity.applicationId(), identity.profile(),
                identity.stateGenesisIdHex(), at, block.blockHashHex(), block.stateRootHex(), subjectId, coordinates,
                component, HEX.formatHex(key), proofJson, evidenceJson(identity, firstId, canonical, block), fact, Map.of());
        return bundle.withVerification(Bundles.explanation(RowVerifier.verify(bundle, new AttestTrust.BundleDeclared())));
    }

    /**
     * A one-block evidence bundle rebuilt from the captured canonical block, its declared trust,
     * and the chain's pinned state identity, so a caller-pinned verification can bind it.
     */
    static String evidenceJson(IndexIdentity identity, String messageIdHex, AppBlock block, IndexedBlock indexed) {
        EvidenceBundle.AnchorRef anchor = null;
        if (!indexed.anchorJson().isEmpty()) {
            try {
                JsonNode node = JSON.readTree(indexed.anchorJson());
                if (node.path("anchoredHeight").asLong(0) == block.height()) {
                    anchor = new EvidenceBundle.AnchorRef(node.path("anchoredHeight").asLong(),
                            node.path("anchoredBlockHash").asText(""), node.path("txHash").asText(""),
                            node.path("l1Slot").asLong());
                }
            } catch (IOException ignored) {
                anchor = null;
            }
        }
        StateSnapshot snapshot = StateCommitmentProfiles.find(identity.profile())
                .map(profile -> new StateSnapshot(StateCommitmentIdentity.explicit(profile,
                        HEX.parseHex(identity.stateGenesisIdHex())), block.height(), block.stateRoot()))
                .orElse(null);
        return EvidenceBundleCodec.toJson(new EvidenceBundle(identity.chainId(), messageIdHex, List.of(block),
                indexed.memberKeysHex(), indexed.threshold(), anchor, snapshot));
    }

    static String inclusionProofJson(MessageInclusionProof proof) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("schemaVersion", proof.schemaVersion());
        view.put("treeId", proof.treeId());
        view.put("chainId", proof.chainId());
        view.put("blockHeight", proof.blockHeight());
        view.put("blockHash", HEX.formatHex(proof.blockHash()));
        view.put("messagesRoot", HEX.formatHex(proof.messagesRoot()));
        view.put("messageId", HEX.formatHex(proof.messageId()));
        view.put("messageIndex", proof.messageIndex());
        view.put("leafCount", proof.leafCount());
        view.put("siblings", proof.siblings().stream().map(HEX::formatHex).toList());
        try {
            return JSON.writeValueAsString(view);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public void close() {
        store.close();
    }
}
