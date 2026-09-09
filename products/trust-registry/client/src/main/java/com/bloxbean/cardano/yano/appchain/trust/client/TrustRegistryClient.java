package com.bloxbean.cardano.yano.appchain.trust.client;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundleCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.MessageInclusionProof;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.AuthenticatedMapProofBundle;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedActorCommandV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.trust.profile.StatusProjection;
import com.bloxbean.cardano.yano.appchain.trust.profile.TrustRegistryProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads a registry chain through the endpoints every Yano node exposes and assembles
 * proof-bound answers (ADR-049 §2.2). Queries, blocks, submissions, and message proofs go
 * through the SDK client; state proofs and block pages are fetched raw so the answer keeps
 * the envelope JSON the node served, byte for byte, for offline verification.
 */
public final class TrustRegistryClient {
    public static final String COMPONENT_MAP = AuthenticatedMapContract.STATE_MACHINE_ID;
    public static final String COMPONENT_ACTORS = RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID;
    public static final String COMPONENT_APPROVALS =
            RoleWorkflowIdentifiers.ROLE_APPROVALS_COMPONENT_ID;
    /** Fact name of the map's genesis marker (value: the map genesis id) in direct-role answers. */
    public static final String GENESIS_MARKER_FACT = "genesis-marker";

    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int BLOCK_PAGE = 200;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final AppChainClient sdk;
    private final HttpClient http;
    private final String baseUrl;
    private final String chainId;
    private final String apiKey;
    private volatile Identity identity;

    private TrustRegistryClient(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.chainId = builder.chainId;
        this.apiKey = builder.apiKey;
        AppChainClient.Builder sdkBuilder = AppChainClient.builder(baseUrl).chainId(chainId);
        if (apiKey != null) sdkBuilder.apiKey(apiKey);
        this.sdk = sdkBuilder.build();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public static Builder builder(String baseUrl, String chainId) {
        return new Builder(baseUrl, chainId);
    }

    public static final class Builder {
        private final String baseUrl;
        private final String chainId;
        private String apiKey;

        private Builder(String baseUrl, String chainId) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl is required, e.g. http://localhost:7070/api/v1");
            }
            this.baseUrl = baseUrl.replaceAll("/+$", "");
            this.chainId = StatusAnswer.nonBlank(chainId, "chainId");
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
            return this;
        }

        public TrustRegistryClient build() {
            return new TrustRegistryClient(this);
        }
    }

    public String chainId() {
        return chainId;
    }

    public AppChainClient sdk() {
        return sdk;
    }

    // ------------------------------------------------------------------ identity

    /**
     * Chain identity the registry is scoped to. {@code stateGenesisIdHex} is the state
     * commitment identity every proof envelope carries; on a composite runtime it is the
     * application-profile-bound derivative of {@code mapGenesisIdHex}, the authenticated-map
     * genesis id that actor authorizations are signed with. Until the chain has finalized its
     * first block the map genesis cannot be queried, so {@code genesis} and
     * {@code mapGenesisIdHex} may be null and {@code registry} false.
     */
    public record Identity(String chainId, String applicationId,
                           AuthenticatedMapContract.Genesis genesis, String mapGenesisIdHex,
                           String stateGenesisIdHex, String profile, boolean registry) {
    }

    public Identity identity() {
        Identity cached = identity;
        if (cached != null) return cached;
        JsonNode status = status();
        String applicationId = status.path("capabilityManifest").path("applicationId").asText("");
        JsonNode stateIdentity;
        try {
            stateIdentity = sdk.stateIdentity();
        } catch (AppChainClient.AppChainClientException unavailable) {
            throw unavailable("state identity unavailable: " + unavailable.getMessage(),
                    unavailable);
        }
        String profile = stateIdentity.path("profile").asText("");
        String stateGenesisIdHex = stateIdentity.path("genesisId").asText("");
        if (profile.isBlank() || !stateGenesisIdHex.matches("[0-9a-f]{64}")) {
            throw malformed("node returned a malformed state identity");
        }
        AuthenticatedMapContract.Genesis genesis = null;
        try {
            AppChainClient.QueryResult result = sdk.query(
                    AuthenticatedMapContract.CAPABILITIES_QUERY_PATH, new byte[0]);
            genesis = AuthenticatedMapContract.decodeGenesis(result.payload());
        } catch (AppChainClient.AppChainClientException beforeFirstBlock) {
            // Components answer queries only once the first block is finalized.
        } catch (RuntimeException malformed) {
            throw malformed("node returned a malformed authenticated-map genesis", malformed);
        }
        if (genesis != null && !chainId.equals(genesis.chainId())) {
            throw malformed("genesis belongs to chain " + genesis.chainId());
        }
        Identity resolved = new Identity(chainId, applicationId, genesis,
                genesis == null ? null : HEX.formatHex(AuthenticatedMapContract.genesisId(genesis)),
                stateGenesisIdHex, profile,
                genesis != null && TrustRegistryProfile.matches(genesis));
        if (genesis != null) {
            identity = resolved;
        }
        return resolved;
    }

    /** The authenticated-map genesis id authorizations are scoped to, once the chain has a block. */
    public byte[] mapGenesisId() {
        Identity resolved = identity();
        if (resolved.mapGenesisIdHex() == null) {
            throw unavailable("the map genesis is not queryable before the first block; "
                    + "pass the generated state.genesis-id explicitly", null);
        }
        return HEX.parseHex(resolved.mapGenesisIdHex());
    }

    public JsonNode status() {
        try {
            return sdk.status();
        } catch (AppChainClient.AppChainClientException unavailable) {
            throw unavailable("chain status unavailable: " + unavailable.getMessage(), unavailable);
        }
    }

    public long tipHeight() {
        JsonNode status = status();
        if (status.path("tipHeight").isIntegralNumber()) {
            return status.path("tipHeight").asLong();
        }
        return blocks(1, 1).tipHeight();
    }

    // ------------------------------------------------------------------ reads

    public AuthenticatedMapContract.PointResult entry(String collection, byte[] key, Long height) {
        AuthenticatedMapContract.PointQuery query = height == null
                ? AuthenticatedMapContract.PointQuery.current(collection, key)
                : AuthenticatedMapContract.PointQuery.atHeight(height, collection, key);
        byte[] payload = query(AuthenticatedMapContract.POINT_QUERY_PATH,
                AuthenticatedMapContract.encodePointQuery(query));
        try {
            return AuthenticatedMapContract.decodePointResult(payload);
        } catch (RuntimeException malformed) {
            throw malformed("node returned a malformed point result", malformed);
        }
    }

    public Optional<AuthenticatedMapContract.Receipt> receipt(byte[] messageId) {
        byte[] payload = query(AuthenticatedMapContract.RECEIPT_QUERY_PATH,
                AuthenticatedMapContract.encodeReceiptQuery(
                        new AuthenticatedMapContract.ReceiptQuery(messageId)));
        try {
            AuthenticatedMapContract.ReceiptResult result =
                    AuthenticatedMapContract.decodeReceiptResult(payload);
            return Optional.ofNullable(result.receipt());
        } catch (RuntimeException malformed) {
            throw malformed("node returned a malformed receipt result", malformed);
        }
    }

    /** A state proof envelope exactly as served, plus its decoded form. */
    public record ProofEnvelope(String json, AppChainClient.Proof proof) {
    }

    /** Proof at an exact height, or the latest when {@code height} is null; empty when the node retains none. */
    public Optional<ProofEnvelope> stateProof(byte[] physicalKey, Long height) {
        String path = "/state/proof/" + HEX.formatHex(physicalKey)
                + (height == null ? "" : "?height=" + height);
        Optional<String> json = getOptional(chainPath(path));
        if (json.isEmpty()) return Optional.empty();
        AppChainClient.Proof proof;
        try {
            proof = AppChainClient.decodeProofEnvelope(json.get());
        } catch (RuntimeException malformed) {
            throw malformed("node returned a malformed state proof", malformed);
        }
        if (!HEX.formatHex(physicalKey).equals(proof.keyHex())
                || !chainId.equals(proof.chainId())
                || height != null && (proof.committedHeight() == null
                || proof.committedHeight() != height)) {
            throw malformed("state proof does not answer the requested key and height");
        }
        return Optional.of(new ProofEnvelope(json.get(), proof));
    }

    public ProofEnvelope requireStateProof(byte[] physicalKey, long height) {
        return stateProof(physicalKey, height).orElseThrow(() -> unavailable(
                "node retains no state proof at height " + height, null));
    }

    public Optional<AppChainClient.Block> block(long height) {
        try {
            return sdk.block(height);
        } catch (AppChainClient.AppChainClientException unavailable) {
            throw unavailable("block " + height + " unavailable: " + unavailable.getMessage(),
                    unavailable);
        }
    }

    public record BlockSummary(long height, int messageCount) {
    }

    public record BlockPage(long tipHeight, List<BlockSummary> blocks) {
    }

    public BlockPage blocks(long from, int limit) {
        String json = getOptional(chainPath("/blocks?from=" + from + "&limit=" + limit))
                .orElseThrow(() -> unavailable("block listing unavailable", null));
        try {
            JsonNode node = JSON.readTree(json);
            List<BlockSummary> blocks = new ArrayList<>();
            for (JsonNode block : node.path("blocks")) {
                blocks.add(new BlockSummary(block.path("height").asLong(),
                        block.path("messageCount").asInt(0)));
            }
            return new BlockPage(node.path("tipHeight").asLong(-1), List.copyOf(blocks));
        } catch (IOException | RuntimeException malformed) {
            throw malformed("node returned a malformed block listing", malformed);
        }
    }

    /** Current actor record, resolved through its current pointer at the tip. */
    public ActorRecordV1 actor(String actorId) {
        long revision = pointer(componentKey(COMPONENT_ACTORS, RoleWorkflowKeys.actorCurrent(actorId)),
                "actor " + actorId);
        byte[] value = presentValue(componentKey(COMPONENT_ACTORS,
                RoleWorkflowKeys.actorRevision(actorId, revision)), "actor " + actorId);
        try {
            return ActorRecordV1.decode(value);
        } catch (RuntimeException malformed) {
            throw malformed("actor record of " + actorId + " is malformed", malformed);
        }
    }

    public DirectRolePolicyV1 directPolicy(String policyId) {
        long revision = pointer(componentKey(COMPONENT_APPROVALS,
                RoleWorkflowKeys.directPolicyCurrent(policyId)), "policy " + policyId);
        byte[] value = presentValue(componentKey(COMPONENT_APPROVALS,
                RoleWorkflowKeys.directPolicyRevision(policyId, revision)), "policy " + policyId);
        try {
            return DirectRolePolicyV1.decode(value);
        } catch (RuntimeException malformed) {
            throw malformed("policy record of " + policyId + " is malformed", malformed);
        }
    }

    /** Current approval policy record, resolved through its current pointer at the tip. */
    public ApprovalPolicyV1 approvalPolicy(String policyId) {
        long revision = pointer(componentKey(COMPONENT_APPROVALS,
                RoleWorkflowKeys.policyCurrent(policyId)), "approval policy " + policyId);
        byte[] value = presentValue(componentKey(COMPONENT_APPROVALS,
                RoleWorkflowKeys.policyRevision(policyId, revision)), "approval policy " + policyId);
        try {
            return ApprovalPolicyV1.decode(value);
        } catch (RuntimeException malformed) {
            throw malformed("approval policy record of " + policyId + " is malformed", malformed);
        }
    }

    // ------------------------------------------------------------------ answers

    /**
     * Proof-bound answer for one key at the tip (null) or an exact retained height. The entry
     * is read from the state proof itself, so historical answers need only proof retention.
     */
    public StatusAnswer answer(String collection, byte[] key, Long requestedHeight) {
        Identity identity = identity();
        long height = requestedHeight != null ? requestedHeight : tipHeight();
        if (height < 1) {
            throw unavailable("the chain has not finalized a block yet", null);
        }
        byte[] entryKey = componentKey(COMPONENT_MAP,
                AuthenticatedMapContract.canonicalKey(collection, key));
        ProofEnvelope entryProof = requireStateProof(entryKey, height);
        AuthenticatedMapContract.Entry entry = null;
        byte[] expectedEntry = null;
        if (entryProof.proof().presence() == AppChainClient.ProofPresence.PRESENT) {
            if (entryProof.proof().valueHex() == null) {
                throw malformed("present state proof carries no value");
            }
            expectedEntry = HEX.parseHex(entryProof.proof().valueHex());
            try {
                entry = AuthenticatedMapContract.decodeEntry(expectedEntry);
            } catch (RuntimeException malformed) {
                throw malformed("state value is not an authenticated-map entry", malformed);
            }
        } else if (entryProof.proof().presence() != AppChainClient.ProofPresence.ABSENT) {
            throw malformed("unexpected state proof presence " + entryProof.proof().presence());
        }
        int presence = entry == null ? AuthenticatedMapContract.PRESENCE_ABSENT
                : entry.status() == AuthenticatedMapContract.STATUS_ACTIVE
                ? AuthenticatedMapContract.PRESENCE_ACTIVE
                : AuthenticatedMapContract.PRESENCE_REVOKED;
        List<StatusAnswer.Fact> facts = new ArrayList<>();
        facts.add(new StatusAnswer.Fact(AuthenticatedMapProofBundle.ENTRY, entryKey,
                expectedEntry, entryProof.json()));
        StatusAnswer.Provenance provenance = entry == null
                ? StatusAnswer.Provenance.none()
                : StatusAnswer.Provenance.genesis();
        byte[] actionCommitment = null;
        byte[] authorizationEvidence = null;

        if (entry != null && entry.lastMutationHeight() > 0) {
            Located located = locate(entry, collection, key);
            if (located == null) {
                throw malformed("no applied command for revision " + entry.revision()
                        + " at height " + entry.lastMutationHeight());
            }
            byte[] receiptKey = componentKey(COMPONENT_MAP,
                    AuthenticatedMapContract.receiptKey(located.messageId()));
            facts.add(new StatusAnswer.Fact(AuthenticatedMapProofBundle.RECEIPT, receiptKey,
                    AuthenticatedMapContract.encodeReceipt(located.receipt()),
                    requireStateProof(receiptKey, height).json()));
            actionCommitment = AuthenticatedMapAuthorizationContract.actionCommitment(
                    located.command().action());
            provenance = StatusAnswer.Provenance.receipt(
                    HEX.formatHex(located.messageId()), located.receipt().height());
            var assignment = located.command().action().authorizations().get(located.index());
            if (assignment.authorizationKind() == AuthenticatedMapContract.AUTH_GOVERNED_ROLE) {
                var evidence = located.command().evidence().get(assignment.evidenceHandle() - 1);
                if (evidence instanceof AuthenticatedMapAuthorizationContract
                        .MapActorAuthorizationV1 authorization) {
                    authorizationEvidence = authorization.encode();
                    provenance = directRoleFacts(facts, authorization, height);
                }
            }
        }
        String blockHash = entryProof.proof().block() != null
                ? entryProof.proof().block().blockHashHex() : null;
        if (blockHash == null) {
            throw malformed("state proof carries no certified block header");
        }
        if (!identity.stateGenesisIdHex().equals(entryProof.proof().genesisIdHex())) {
            throw malformed("state proof names a different genesis than the node's identity");
        }
        String evidenceJson = evidence(height, blockHash, entryProof.proof().stateRootHex());
        return new StatusAnswer(chainId, identity.profile(), identity.stateGenesisIdHex(), height,
                entryProof.proof().stateRootHex(), blockHash, collection, key,
                StatusAnswer.Presence.of(presence),
                entry == null ? null : StatusAnswer.Entry.of(entry), provenance,
                actionCommitment, authorizationEvidence, facts, evidenceJson);
    }

    /**
     * The evidence bundle of a message finalized in the block at {@code height}: it carries
     * that certified block in full, which is what an offline verifier checks the finality
     * certificate against (the state proof's block view is not hashable on its own).
     */
    private String evidence(long height, String blockHashHex, String stateRootHex) {
        AppChainClient.Block block = block(height).orElseThrow(() ->
                unavailable("block " + height + " is not retained", null));
        if (block.messages().isEmpty()) {
            throw malformed("block " + height + " carries no message to fetch evidence for");
        }
        String messageId = block.messages().getFirst().messageId();
        String json = getOptional(chainPath("/evidence/" + messageId)).orElseThrow(() ->
                unavailable("evidence bundle of block " + height + " is unavailable", null));
        EvidenceBundle bundle;
        try {
            bundle = EvidenceBundleCodec.fromJson(json);
        } catch (RuntimeException malformed) {
            throw malformed("node returned a malformed evidence bundle", malformed);
        }
        AppBlock certified = bundle.blocks().stream()
                .filter(candidate -> candidate.height() == height).findFirst().orElse(null);
        if (certified == null
                || !HEX.formatHex(AppBlockCodec.blockHash(certified)).equals(blockHashHex)
                || !HEX.formatHex(certified.stateRoot()).equals(stateRootHex)) {
            throw malformed("evidence bundle does not carry the certified block at height " + height);
        }
        return json;
    }

    private StatusAnswer.Provenance directRoleFacts(
            List<StatusAnswer.Fact> facts,
            AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1 authorization,
            long height
    ) {
        byte[] consumptionKey = componentKey(COMPONENT_MAP,
                AuthenticatedMapContract.directConsumptionKey(
                        authorization.actorId(), authorization.authorizationId()));
        ProofEnvelope consumptionProof = requireStateProof(consumptionKey, height);
        byte[] consumptionBytes = presentValue(consumptionProof, "direct consumption");
        AuthenticatedMapAuthorizationContract.DirectConsumptionV1 consumption;
        try {
            consumption = AuthenticatedMapAuthorizationContract.DirectConsumptionV1
                    .decode(consumptionBytes);
        } catch (RuntimeException malformed) {
            throw malformed("direct consumption record is malformed", malformed);
        }
        facts.add(new StatusAnswer.Fact(AuthenticatedMapProofBundle.DIRECT_CONSUMPTION,
                consumptionKey, consumptionBytes, consumptionProof.json()));
        addPresent(facts, AuthenticatedMapProofBundle.DIRECT_POLICY, componentKey(COMPONENT_APPROVALS,
                RoleWorkflowKeys.directPolicyRevision(
                        consumption.policyId(), consumption.policyRevision())), height);
        addPresent(facts, AuthenticatedMapProofBundle.DIRECT_POLICY_CURRENT,
                componentKey(COMPONENT_APPROVALS,
                        RoleWorkflowKeys.directPolicyCurrent(consumption.policyId())), height);
        addPresent(facts, AuthenticatedMapProofBundle.ACTOR, componentKey(COMPONENT_ACTORS,
                RoleWorkflowKeys.actorRevision(
                        consumption.actorId(), consumption.actorRevision())), height);
        addPresent(facts, AuthenticatedMapProofBundle.ACTOR_CURRENT, componentKey(COMPONENT_ACTORS,
                RoleWorkflowKeys.actorCurrent(consumption.actorId())), height);
        addPresent(facts, AuthenticatedMapProofBundle.ORGANIZATION, componentKey(COMPONENT_ACTORS,
                RoleWorkflowKeys.organizationRevision(
                        consumption.organizationId(), consumption.organizationRevision())), height);
        // The map genesis id the authorization was signed with, bound under the same root.
        addPresent(facts, GENESIS_MARKER_FACT, componentKey(COMPONENT_MAP,
                AuthenticatedMapContract.genesisMarkerKey()), height);
        return new StatusAnswer.Provenance(StatusAnswer.ProvenanceKind.DIRECT_ROLE,
                HEX.formatHex(consumption.messageId()), consumption.appliedHeight(),
                consumption.actorId(), consumption.organizationId(), consumption.keyId(),
                consumption.policyId(), consumption.policyRevision(), consumption.role());
    }

    private void addPresent(List<StatusAnswer.Fact> facts, String name, byte[] key, long height) {
        ProofEnvelope proof = requireStateProof(key, height);
        facts.add(new StatusAnswer.Fact(name, key, presentValue(proof, name), proof.json()));
    }

    private record Located(byte[] messageId,
                           AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1 command,
                           AuthenticatedMapContract.Receipt receipt, int index) {
    }

    /** The applied command in the entry's last-mutation block that produced its current revision. */
    private Located locate(AuthenticatedMapContract.Entry entry, String collection, byte[] key) {
        AppChainClient.Block block = block(entry.lastMutationHeight()).orElseThrow(() ->
                unavailable("block " + entry.lastMutationHeight() + " is not retained", null));
        for (AppChainClient.Message message : block.messages()) {
            if (!AuthenticatedMapContract.DEFAULT_TOPIC.equals(message.topic())) continue;
            AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1 command;
            try {
                command = AuthenticatedMapAuthorizationContract.decodeCommand(message.body());
            } catch (RuntimeException undecodable) {
                continue;
            }
            List<AuthenticatedMapContract.Mutation> mutations = command.action().mutations();
            for (int index = 0; index < mutations.size(); index++) {
                AuthenticatedMapContract.Mutation mutation = mutations.get(index);
                if (!mutation.collectionId().equals(collection)
                        || !Arrays.equals(mutation.applicationKey(), key)) continue;
                byte[] messageId = HEX.parseHex(message.messageId());
                Optional<AuthenticatedMapContract.Receipt> receipt = receipt(messageId);
                if (receipt.isEmpty()
                        || receipt.get().status() != AuthenticatedMapContract.RECEIPT_APPLIED) {
                    continue;
                }
                boolean produced = receipt.get().results().stream().anyMatch(result ->
                        result.collectionId().equals(collection)
                                && Arrays.equals(result.applicationKey(), key)
                                && result.revision() == entry.revision()
                                && result.status() == entry.status());
                if (produced) {
                    return new Located(messageId, command, receipt.get(), index);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ replay

    /** One finalized map command with its receipt, as seen by the replay. */
    public record ReplayedCommand(long height, byte[] messageId,
                                  AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1 command,
                                  AuthenticatedMapContract.Receipt receipt) {
    }

    /**
     * Applies every applied {@code status} mutation finalized after the projection's replayed
     * height and up to {@code toHeight}, confirming each command through its receipt. Returns
     * the height the projection now covers.
     */
    public long replay(StatusProjection projection, long toHeight) {
        long from = Math.max(projection.replayedHeight() + 1, 1);
        while (from <= toHeight) {
            BlockPage page = blocks(from, BLOCK_PAGE);
            if (page.blocks().isEmpty()) break;
            long last = from - 1;
            for (BlockSummary summary : page.blocks()) {
                if (summary.height() > toHeight) break;
                last = summary.height();
                if (summary.messageCount() == 0) continue;
                for (ReplayedCommand replayed : commands(summary.height())) {
                    if (replayed.receipt().status() != AuthenticatedMapContract.RECEIPT_APPLIED) {
                        continue;
                    }
                    List<AuthenticatedMapContract.Mutation> mutations =
                            replayed.command().action().mutations();
                    for (AuthenticatedMapContract.MutationResult result
                            : replayed.receipt().results()) {
                        for (AuthenticatedMapContract.Mutation mutation : mutations) {
                            if (mutation.collectionId().equals(result.collectionId())
                                    && Arrays.equals(mutation.applicationKey(),
                                    result.applicationKey())) {
                                projection.apply(replayed.height(), mutation);
                            }
                        }
                    }
                }
            }
            if (last < from) break;
            from = last + 1;
        }
        long covered = Math.min(toHeight, Math.max(projection.replayedHeight(), from - 1));
        projection.markReplayed(covered);
        return covered;
    }

    /** Finalized map commands of one block, each with its receipt when the node has one. */
    public List<ReplayedCommand> commands(long height) {
        AppChainClient.Block block = block(height).orElseThrow(() ->
                unavailable("block " + height + " is not retained", null));
        List<ReplayedCommand> replayed = new ArrayList<>();
        for (AppChainClient.Message message : block.messages()) {
            if (!AuthenticatedMapContract.DEFAULT_TOPIC.equals(message.topic())) continue;
            AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1 command;
            try {
                command = AuthenticatedMapAuthorizationContract.decodeCommand(message.body());
            } catch (RuntimeException undecodable) {
                continue;
            }
            byte[] messageId = HEX.parseHex(message.messageId());
            receipt(messageId).ifPresent(receipt ->
                    replayed.add(new ReplayedCommand(height, messageId, command, receipt)));
        }
        return replayed;
    }

    // ------------------------------------------------------------------ writes

    /** Submits an encoded map command; returns the message id. */
    public String submit(byte[] commandBytes) {
        return submit(AuthenticatedMapContract.DEFAULT_TOPIC, commandBytes);
    }

    /** Submits a signed role statement (proposal, approval); returns the message id. */
    public String submitStatement(byte[] signedStatement) {
        return submit(SignedActorCommandV1.DEFAULT_TOPIC, signedStatement);
    }

    private String submit(String topic, byte[] body) {
        try {
            return sdk.submit(topic, body).messageId();
        } catch (AppChainClient.AppChainClientException unavailable) {
            throw unavailable("submission failed: " + unavailable.getMessage(), unavailable);
        }
    }

    public MessageInclusionProof awaitFinalized(String messageIdHex, Duration timeout) {
        byte[] messageId = HEX.parseHex(messageIdHex);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<MessageInclusionProof> proof;
            try {
                proof = sdk.messageProof(messageId);
            } catch (AppChainClient.AppChainClientException unavailable) {
                throw unavailable("message proof unavailable: " + unavailable.getMessage(),
                        unavailable);
            }
            if (proof.isPresent()) return proof.get();
            if (System.nanoTime() >= deadline) {
                throw new TrustRegistryException(TrustRegistryException.Error.NOT_FINALIZED,
                        "message " + messageIdHex + " was not finalized within " + timeout);
            }
            sleep();
        }
    }

    /** Waits for finality, then for the node to hold the command's receipt. */
    public AuthenticatedMapContract.Receipt awaitReceipt(String messageIdHex, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        awaitFinalized(messageIdHex, timeout);
        byte[] messageId = HEX.parseHex(messageIdHex);
        while (true) {
            Optional<AuthenticatedMapContract.Receipt> receipt = receipt(messageId);
            if (receipt.isPresent()) return receipt.get();
            if (System.nanoTime() >= deadline) {
                throw new TrustRegistryException(TrustRegistryException.Error.NOT_FINALIZED,
                        "receipt of " + messageIdHex + " was not available within " + timeout);
            }
            sleep();
        }
    }

    // ------------------------------------------------------------------ helpers

    public static byte[] componentKey(String component, byte[] localKey) {
        return CompositeCommitmentV1.componentKey(component, localKey);
    }

    private long pointer(byte[] physicalKey, String what) {
        byte[] value = presentValue(physicalKey, what);
        if (value.length != Long.BYTES) {
            throw malformed(what + " has a malformed current pointer");
        }
        return ByteBuffer.wrap(value).getLong();
    }

    private byte[] presentValue(byte[] physicalKey, String what) {
        ProofEnvelope proof = stateProof(physicalKey, null).orElseThrow(() ->
                unavailable(what + " is unavailable", null));
        return presentValue(proof, what);
    }

    private byte[] presentValue(ProofEnvelope proof, String what) {
        if (proof.proof().presence() != AppChainClient.ProofPresence.PRESENT
                || proof.proof().valueHex() == null) {
            throw new TrustRegistryException(TrustRegistryException.Error.INVALID,
                    what + " is not present on the chain");
        }
        return HEX.parseHex(proof.proof().valueHex());
    }

    private byte[] query(String path, byte[] params) {
        try {
            return sdk.query(path, params).payload();
        } catch (AppChainClient.AppChainClientException unavailable) {
            throw unavailable("query " + path + " failed: " + unavailable.getMessage(), unavailable);
        }
    }

    private String chainPath(String suffix) {
        return baseUrl + "/app-chain/chains/" + chainId + suffix;
    }

    private Optional<String> getOptional(String url) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET();
        if (apiKey != null) request.header("X-API-Key", apiKey);
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException failure) {
            throw unavailable("node unreachable: " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("request interrupted", interrupted);
        }
        if (response.statusCode() == 404) return Optional.empty();
        if (response.body().length > MAX_RESPONSE_BYTES) {
            throw malformed("node response exceeds " + MAX_RESPONSE_BYTES + " bytes");
        }
        if (response.statusCode() != 200) {
            throw unavailable("HTTP " + response.statusCode() + " from " + url, null);
        }
        return Optional.of(new String(response.body(), StandardCharsets.UTF_8));
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TrustRegistryException(TrustRegistryException.Error.UNAVAILABLE,
                    "wait interrupted");
        }
    }

    private static TrustRegistryException unavailable(String message, Throwable cause) {
        return new TrustRegistryException(TrustRegistryException.Error.UNAVAILABLE, message, cause);
    }

    private static TrustRegistryException malformed(String message) {
        return new TrustRegistryException(TrustRegistryException.Error.MALFORMED_RESPONSE, message);
    }

    private static TrustRegistryException malformed(String message, Throwable cause) {
        return new TrustRegistryException(TrustRegistryException.Error.MALFORMED_RESPONSE, message,
                cause);
    }
}
