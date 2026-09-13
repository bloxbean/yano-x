package org.yanoproject.x.explorer;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.codec.AppBlockCodec;
import org.yanoproject.api.appchain.evidence.EvidenceBundle;
import org.yanoproject.api.appchain.evidence.EvidenceBundleCodec;
import org.yanoproject.api.appchain.evidence.EvidenceVerifier;
import org.yanoproject.api.appchain.transition.FinalizedBlockMessageRootIndex;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.ProofVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tails finalized blocks over the public REST API and verifies each one before it is written
 * (ADR-050 §2.1). The evidence bundle of a block's first message supplies the canonical block
 * and certificate; {@code EvidenceVerifier} establishes hashes, roots, message ids, and finality
 * for every block in the bundle's segment, which the follower harvests so one fetch often
 * covers several heights.
 */
public final class Follower {
    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Caller-pinned members and threshold, or null for the bundle's declared members. */
    public record Trust(Set<String> memberKeysHex, int threshold) {
        public Trust {
            memberKeysHex = Set.copyOf(Objects.requireNonNull(memberKeysHex, "memberKeysHex"));
            if (memberKeysHex.isEmpty() || threshold < 1 || threshold > memberKeysHex.size()) {
                throw new ExplorerException(ExplorerException.Error.USAGE, "invalid pinned membership");
            }
        }
    }

    private record Harvest(AppBlock block, List<String> memberKeysHex, int threshold, String anchorJson) { }

    private final NodeSource source;
    private final IndexStore store;
    private final Trust trust;
    private final String chainId;
    private final TreeMap<Long, Harvest> harvested = new TreeMap<>();
    private IndexIdentity identity;
    private StockModules.Routing routing = StockModules.Routing.defaults();

    public Follower(NodeSource source, IndexStore store, Trust trust) {
        this.source = Objects.requireNonNull(source, "source");
        this.store = Objects.requireNonNull(store, "store");
        this.trust = trust;
        this.chainId = source.chainId();
    }

    public String chainId() {
        return chainId;
    }

    public StockModules.Routing routing() {
        return routing;
    }

    /** Reads the node's identity, pins it into the store, and refuses another identity. */
    public IndexIdentity ensureIdentity() {
        if (identity != null) return identity;
        JsonNode status = source.status();
        JsonNode manifest = status.path("capabilityManifest");
        routing = StockModules.Routing.fromManifest(manifest);
        String applicationId = manifest.path("applicationId").asText("");
        if (applicationId.isBlank()) applicationId = status.path("stateMachineId").asText("");
        if (applicationId.isBlank()) applicationId = status.path("stateMachine").asText("");
        if (applicationId.isBlank()) applicationId = "unknown";
        String manifestDigest = manifest.path("manifestDigest").asText("");
        if (manifestDigest.isBlank()) manifestDigest = manifest.path("digest").asText("");
        JsonNode state = source.stateIdentity();
        String profile = state.path("profile").asText("");
        String genesis = state.path("genesisId").asText("");
        if (profile.isBlank() || !genesis.matches("[0-9a-f]{64}")) {
            throw new ExplorerException(ExplorerException.Error.MALFORMED_RESPONSE,
                    "the node's state identity for " + chainId + " is incomplete");
        }
        identity = new IndexIdentity(chainId, applicationId, profile, genesis,
                manifestDigest.matches("[0-9a-f]*") ? manifestDigest : "");
        store.pinIdentity(identity);
        return identity;
    }

    /**
     * Indexes up to {@code maxBlocks} blocks after the checkpoint and returns the new checkpoint
     * height. Stops early at the node's tip or when a block is not yet retrievable.
     */
    public long catchUp(int maxBlocks) {
        ensureIdentity();
        long tip = source.tipHeight();
        long next = store.checkpoint(chainId).height() + 1;
        int done = 0;
        while (next <= tip && done < maxBlocks) {
            Optional<JsonNode> json = source.blockJson(next);
            if (json.isEmpty()) break;
            index(next, json.get());
            harvested.headMap(next, true).clear();
            next++;
            done++;
        }
        return next - 1;
    }

    public long tipHeight() {
        return source.tipHeight();
    }

    private void index(long height, JsonNode json) {
        List<JsonNode> messageViews = new ArrayList<>();
        json.path("messages").forEach(messageViews::add);
        Harvest harvest = harvested.get(height);
        String diagnostic = "";
        if (harvest == null && !messageViews.isEmpty()) {
            String firstId = messageViews.getFirst().path("messageId").asText("");
            if (!firstId.matches("[0-9a-f]{64}")) {
                throw invalid("block " + height + " lists a message without a canonical id");
            }
            Optional<String> evidence = source.evidenceJson(firstId);
            if (evidence.isPresent()) {
                harvestBundle(firstId, evidence.get());
                harvest = harvested.get(height);
                if (harvest == null) {
                    throw invalid("evidence for message " + firstId + " does not carry block " + height);
                }
            } else {
                diagnostic = "evidence for message " + firstId + " is not retained";
            }
        }
        if (harvest == null) {
            writeFromJson(height, json, messageViews,
                    messageViews.isEmpty() ? VerificationLevel.HEADER_ONLY : VerificationLevel.JSON_ONLY,
                    diagnostic);
            return;
        }
        AppBlock block = harvest.block();
        byte[] blockHash = AppBlockCodec.blockHash(block);
        checkJsonView(height, json, messageViews, block);
        if (height > 1) {
            IndexedBlock previous = store.block(chainId, height - 1).orElse(null);
            if (previous != null && !previous.blockHashHex().isEmpty()
                    && !previous.blockHashHex().equals(HEX.formatHex(block.prevHash()))) {
                throw invalid("block " + height + " does not link to the indexed block " + (height - 1));
            }
        }
        byte[] messagesRoot = AppBlockCodec.messagesRoot(block.messages());
        if (!java.util.Arrays.equals(messagesRoot, block.messagesRoot())) {
            throw invalid("block " + height + " messages root does not recompute from its messages");
        }
        String recordProof = blockRecordProof(height, block, messagesRoot);
        List<IndexedMessage> messages = new ArrayList<>();
        List<List<SubjectRow>> rows = new ArrayList<>();
        for (int index = 0; index < block.messages().size(); index++) {
            IndexedMessage message = canonicalMessage(height, index, block.messages().get(index));
            messages.add(message);
            rows.add(StockModules.decode(message, routing));
        }
        IndexedBlock indexed = new IndexedBlock(height, HEX.formatHex(blockHash), HEX.formatHex(block.prevHash()),
                block.timestamp(), HEX.formatHex(block.messagesRoot()), HEX.formatHex(block.stateRoot()),
                HEX.formatHex(block.proposer()), block.messages().size(), block.cert().signatures().size(),
                trust != null ? VerificationLevel.VERIFIED_PINNED : VerificationLevel.VERIFIED_DECLARED,
                HEX.formatHex(AppBlockCodec.serialize(block)), harvest.memberKeysHex(), harvest.threshold(),
                harvest.anchorJson(), recordProof == null ? "" : recordProof,
                recordProof == null ? "block record proof unavailable" : "");
        store.writeBlock(chainId, indexed, messages, rows);
    }

    private void harvestBundle(String messageIdHex, String evidenceJson) {
        EvidenceBundle bundle;
        try {
            bundle = EvidenceBundleCodec.fromJson(evidenceJson);
        } catch (RuntimeException malformed) {
            throw new ExplorerException(ExplorerException.Error.MALFORMED_RESPONSE,
                    "evidence bundle for " + messageIdHex + " is malformed: " + malformed.getMessage());
        }
        if (!chainId.equals(bundle.chainId())) {
            throw invalid("evidence bundle for " + messageIdHex + " names chain " + bundle.chainId());
        }
        EvidenceVerifier.Result result;
        try {
            result = trust == null
                    ? EvidenceVerifier.verifyInternalConsistencyAgainstDeclaredMembers(bundle)
                    : EvidenceVerifier.verify(bundle, new EvidenceVerifier.TrustContext(
                            chainId, trust.memberKeysHex(), trust.threshold()));
        } catch (RuntimeException rejected) {
            throw invalid("evidence bundle for " + messageIdHex + " rejected: " + rejected.getMessage());
        }
        if (!result.valid()) {
            throw invalid("evidence bundle for " + messageIdHex + " does not verify: " + result.failure());
        }
        String anchorJson = "";
        if (bundle.anchor() != null) {
            Map<String, Object> anchor = new LinkedHashMap<>();
            anchor.put("anchoredHeight", bundle.anchor().anchoredHeight());
            anchor.put("anchoredBlockHash", bundle.anchor().anchoredBlockHashHex());
            anchor.put("txHash", bundle.anchor().txHash());
            anchor.put("l1Slot", bundle.anchor().l1Slot());
            try {
                anchorJson = JSON.writeValueAsString(anchor);
            } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        for (AppBlock block : bundle.blocks()) {
            harvested.put(block.height(), new Harvest(block, bundle.memberKeysHex(), bundle.threshold(), anchorJson));
        }
    }

    /** The JSON view must describe the canonical block; a node that disagrees with itself is invalid. */
    private void checkJsonView(long height, JsonNode json, List<JsonNode> views, AppBlock block) {
        if (json.path("height").asLong(-1) != height || block.height() != height
                || !chainId.equals(block.chainId())
                || !json.path("prevHash").asText("").equals(HEX.formatHex(block.prevHash()))
                || !json.path("messagesRoot").asText("").equals(HEX.formatHex(block.messagesRoot()))
                || !json.path("stateRoot").asText("").equals(HEX.formatHex(block.stateRoot()))
                || views.size() != block.messages().size()) {
            throw invalid("the node's JSON view of block " + height + " differs from its certified block");
        }
        for (int index = 0; index < views.size(); index++) {
            AppMessage message = block.messages().get(index);
            JsonNode view = views.get(index);
            if (!view.path("messageId").asText("").equals(message.getMessageIdHex())
                    || !view.path("topic").asText("").equals(message.getTopic())) {
                throw invalid("message " + index + " of block " + height + " differs between the JSON view and the certified block");
            }
        }
    }

    /** Captures the authenticated block record proof at ingest and checks it against the block. */
    private String blockRecordProof(long height, AppBlock block, byte[] messagesRoot) {
        String keyHex = HEX.formatHex(FinalizedBlockMessageRootIndex.blockKey(height));
        Optional<String> json;
        try {
            json = source.stateProofJson(keyHex, height);
        } catch (ExplorerException unavailable) {
            return null;
        }
        if (json.isEmpty()) return null;
        AppChainClient.Proof proof;
        try {
            proof = AppChainClient.decodeProofEnvelope(json.get());
        } catch (RuntimeException malformed) {
            throw new ExplorerException(ExplorerException.Error.MALFORMED_RESPONSE,
                    "block record proof at " + height + " is malformed: " + malformed.getMessage());
        }
        String stateRootHex = HEX.formatHex(block.stateRoot());
        if (proof.committedHeight() == null || proof.committedHeight() != height
                || !stateRootHex.equals(proof.stateRootHex())
                || !chainId.equals(proof.chainId())) {
            throw invalid("block record proof at " + height + " names another height, root, or chain");
        }
        if (!ProofVerifier.verifyAgainstRoot(proof, stateRootHex)) {
            throw invalid("block record proof at " + height + " does not verify against the certified state root");
        }
        if (proof.presence() != AppChainClient.ProofPresence.PRESENT) {
            return null;
        }
        FinalizedBlockMessageRootIndex.BlockRecord record;
        try {
            record = FinalizedBlockMessageRootIndex.decode(HEX.parseHex(proof.valueHex()));
        } catch (RuntimeException malformed) {
            throw invalid("block record at " + height + " does not decode: " + malformed.getMessage());
        }
        if (record.height() != height || !java.util.Arrays.equals(record.messagesRoot(), messagesRoot)
                || record.messageCount() != block.messages().size()) {
            throw invalid("block record at " + height + " does not match the certified block");
        }
        return json.get();
    }

    private IndexedMessage canonicalMessage(long height, int index, AppMessage message) {
        boolean tombstone = message.getBody().length == 0 && message.getAuthProof().length == 0
                && !message.hasValidMessageId();
        if (!tombstone && !message.hasValidMessageId()) {
            throw invalid("message " + index + " of block " + height + " has an id that does not recompute");
        }
        return new IndexedMessage(height, index, message.getMessageIdHex(), message.getTopic(),
                HEX.formatHex(message.getSender()), message.getSenderSeq(), message.getExpiresAt(),
                HEX.formatHex(message.getBody()), message.getAuthScheme(), HEX.formatHex(message.getAuthProof()),
                tombstone ? IndexedMessage.State.TOMBSTONE : IndexedMessage.State.FULL);
    }

    private void writeFromJson(long height, JsonNode json, List<JsonNode> views, VerificationLevel level,
                               String diagnostic) {
        List<IndexedMessage> messages = new ArrayList<>();
        List<List<SubjectRow>> rows = new ArrayList<>();
        for (int index = 0; index < views.size(); index++) {
            JsonNode view = views.get(index);
            String id = view.path("messageId").asText("");
            if (!id.matches("[0-9a-f]{64}")) {
                throw invalid("block " + height + " lists a message without a canonical id");
            }
            IndexedMessage message = new IndexedMessage(height, index, id, view.path("topic").asText(""),
                    view.path("sender").asText(""), view.path("senderSeq").asLong(0), 0,
                    view.path("bodyHex").asText(""), -1, "", IndexedMessage.State.JSON);
            messages.add(message);
            rows.add(StockModules.decode(message, routing));
        }
        IndexedBlock indexed = new IndexedBlock(height, "", json.path("prevHash").asText(""),
                json.path("timestamp").asLong(0), json.path("messagesRoot").asText(""),
                json.path("stateRoot").asText(""), json.path("proposer").asText(""), views.size(),
                json.path("certSignatures").asInt(0), level, "", List.of(), 0, "", "", diagnostic);
        store.writeBlock(chainId, indexed, messages, rows);
    }

    private ExplorerException invalid(String message) {
        return new ExplorerException(ExplorerException.Error.INVALID, message);
    }
}
