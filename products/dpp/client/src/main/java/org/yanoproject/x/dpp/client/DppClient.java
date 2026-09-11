package org.yanoproject.x.dpp.client;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.AuthenticatedMapProofBundle;
import org.yanoproject.x.dpp.profile.DppStarterProfile;
import org.yanoproject.x.dpp.profile.DppValues;
import org.yanoproject.x.roles.contracts.ActorStatementV1;
import org.yanoproject.x.roles.contracts.ApprovalPolicyV1;
import org.yanoproject.x.roles.contracts.DirectRolePolicyV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.trust.client.StatusAnswer;
import org.yanoproject.x.trust.client.TrustRegistryClient;
import org.yanoproject.x.trust.client.TrustRegistrySigner;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The DPP starter over one chain (ADR-051 §2.2): governed writes and the certification round
 * through the ADR-049 map client and signer, the replayed projection, and passport assembly
 * from proof-bound answers at one height.
 */
public final class DppClient {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final int MAX_REPLAY_COMMANDS = 5_000;
    public static final String APPROVAL_CONSUMPTION_FACT = AuthenticatedMapProofBundle.APPROVAL_CONSUMPTION;
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BLOCK_PAGE = 64;

    private final TrustRegistryClient chain;
    private final PassportProjection projection = new PassportProjection();
    private final Duration timeout;

    public DppClient(TrustRegistryClient chain) {
        this(chain, DEFAULT_TIMEOUT);
    }

    public DppClient(TrustRegistryClient chain, Duration timeout) {
        this.chain = Objects.requireNonNull(chain, "chain");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public static DppClient connect(String baseUrl, String chainId, String apiKey) {
        return new DppClient(TrustRegistryClient.builder(baseUrl, chainId).apiKey(apiKey).build());
    }

    public TrustRegistryClient chain() {
        return chain;
    }

    public String chainId() {
        return chain.chainId();
    }

    public PassportProjection projection() {
        return projection;
    }

    // ------------------------------------------------------------------ identity

    /** Chain identity; {@code starter} is true once the genesis is readable and matches the profile. */
    public record Identity(String chainId, String applicationId, String mapGenesisIdHex,
                           String stateGenesisIdHex, String profile, boolean starter) {
    }

    public Identity identity() {
        TrustRegistryClient.Identity identity = chain.identity();
        return new Identity(identity.chainId(), identity.applicationId(), identity.mapGenesisIdHex(),
                identity.stateGenesisIdHex(), identity.profile(),
                identity.genesis() != null && DppStarterProfile.matches(identity.genesis()));
    }

    public long tipHeight() {
        return chain.tipHeight();
    }

    // ------------------------------------------------------------------ signers

    /**
     * An actor that signs: its seed, and the overrides the first write on a chain without a
     * block needs (the generated genesis id, and the key id when it is not {@code <actor>-k1}).
     */
    public record Signer(String actorId, byte[] seed, String genesisIdHexOverride, String keyIdOverride) {
        public Signer {
            Objects.requireNonNull(actorId, "actorId");
            seed = Objects.requireNonNull(seed, "seed").clone();
            if (seed.length != 32) throw new IllegalArgumentException("seed must be 32 bytes");
            if (genesisIdHexOverride != null && !genesisIdHexOverride.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("genesis id override must be 32 bytes of hex");
            }
        }

        @Override public byte[] seed() { return seed.clone(); }

        public static Signer of(String actorId, byte[] seed) {
            return new Signer(actorId, seed, null, null);
        }
    }

    private record Resolved(TrustRegistrySigner.ActorContext actor, byte[] genesisId, long tip) {
    }

    private Resolved resolve(Signer signer) {
        long tip = chain.tipHeight();
        if (tip < 1) {
            if (signer.genesisIdHexOverride() == null) {
                throw DppException.usage("the chain has no block yet; pass the generated"
                        + " state.genesis-id as --genesis-id (and --key-id when it is not <actor>-k1)");
            }
            byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(signer.seed());
            String keyId = signer.keyIdOverride() != null ? signer.keyIdOverride()
                    : signer.actorId() + "-k1";
            return new Resolved(new TrustRegistrySigner.ActorContext(signer.actorId(), 1, keyId,
                    publicKey, signer.seed()), HEX.parseHex(signer.genesisIdHexOverride()), tip);
        }
        byte[] genesisId = signer.genesisIdHexOverride() != null
                ? HEX.parseHex(signer.genesisIdHexOverride()) : chain.mapGenesisId();
        TrustRegistrySigner.ActorContext actor;
        try {
            actor = TrustRegistrySigner.actorContext(chain.actor(signer.actorId()), signer.seed(), tip);
        } catch (IllegalArgumentException mismatch) {
            throw DppException.invalid(mismatch.getMessage());
        }
        return new Resolved(actor, genesisId, tip);
    }

    // ------------------------------------------------------------------ governed writes

    public record WriteResult(String messageIdHex, AuthenticatedMapContract.Receipt receipt) {
        public boolean applied() {
            return receipt.status() == AuthenticatedMapContract.RECEIPT_APPLIED;
        }

        public String errorName() {
            return DppClient.errorName(receipt.errorCode());
        }
    }

    /** Submits a governed-role command signed by the signer under the collection's policy. */
    public WriteResult submitGoverned(Signer signer, String policyId,
                                      AuthenticatedMapContract.Command command) {
        Resolved resolved = resolve(signer);
        long policyRevision = 1;
        long lifetime = DppStarterProfile.DIRECT_AUTHORIZATION_LIFETIME_BLOCKS;
        if (resolved.tip() >= 1) {
            DirectRolePolicyV1 policy = chain.directPolicy(policyId);
            policyRevision = policy.revision();
            lifetime = Math.min(lifetime, policy.maximumAuthorizationLifetimeBlocks());
        }
        long issued = Math.max(resolved.tip(), 1);
        byte[] authorizationId = new byte[32];
        RANDOM.nextBytes(authorizationId);
        byte[] bytes = TrustRegistrySigner.governedCommand(command, policyId, policyRevision,
                resolved.actor(), chainId(), resolved.genesisId(), issued, issued + lifetime,
                authorizationId);
        String messageId = chain.submit(bytes);
        return new WriteResult(messageId, chain.awaitReceipt(messageId, timeout));
    }

    public WriteResult registerProduct(Signer signer, String productId, DppValues.ProductValue value) {
        return submitGoverned(signer, DppStarterProfile.MANUFACTURER_POLICY,
                AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.putIfAbsent(
                        DppStarterProfile.PRODUCTS, DppStarterProfile.productKey(productId),
                        value.encode())));
    }

    /**
     * Records the version and advances {@code currentVersion} in one batch; the product entry is
     * updated with {@code COMPARE_AND_SET} on the revision read, so a concurrent rewrite is
     * rejected by the map (ADR-026 §7.2).
     */
    public WriteResult publishVersion(Signer signer, String productId, long version,
                                      DppValues.VersionValue value) {
        Current current = currentProduct(productId);
        return submitGoverned(signer, DppStarterProfile.MANUFACTURER_POLICY,
                AuthenticatedMapContract.Command.batch(List.of(
                        AuthenticatedMapContract.Mutation.putIfAbsent(DppStarterProfile.VERSIONS,
                                DppStarterProfile.versionKey(productId, version), value.encode()),
                        AuthenticatedMapContract.Mutation.compareAndSet(DppStarterProfile.PRODUCTS,
                                DppStarterProfile.productKey(productId),
                                current.value().withCurrentVersion(version).encode(),
                                current.revision(), null))));
    }

    public WriteResult setStatus(Signer signer, String productId, int status, String successor) {
        Current current = currentProduct(productId);
        return submitGoverned(signer, DppStarterProfile.MANUFACTURER_POLICY,
                AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.compareAndSet(
                        DppStarterProfile.PRODUCTS, DppStarterProfile.productKey(productId),
                        current.value().withStatus(status, successor).encode(),
                        current.revision(), null)));
    }

    /** ADR-026's {@code passport.revoke}: the product entry becomes a tombstone. */
    public WriteResult revokeProduct(Signer signer, String productId) {
        Current current = currentProduct(productId);
        return submitGoverned(signer, DppStarterProfile.MANUFACTURER_POLICY,
                AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.revoke(
                        DppStarterProfile.PRODUCTS, DppStarterProfile.productKey(productId),
                        current.revision(), null)));
    }

    public WriteResult putClaim(Signer signer, String productId, String claimType, String claimId,
                                DppValues.ClaimValue value) {
        return submitGoverned(signer, DppStarterProfile.CLAIM_ISSUER_POLICY,
                AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.put(
                        DppStarterProfile.CLAIMS,
                        DppStarterProfile.claimKey(productId, claimType, claimId), value.encode())));
    }

    public WriteResult appendEvent(Signer signer, String productId, String eventId,
                                   DppValues.EventValue value) {
        return submitGoverned(signer, DppStarterProfile.OPERATOR_POLICY,
                AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.putIfAbsent(
                        DppStarterProfile.EVENTS, DppStarterProfile.eventKey(productId, eventId),
                        value.encode())));
    }

    /** A raw governed {@code PUT}; what a rewrite looks like when a client ignores the profile's conventions. */
    public WriteResult put(Signer signer, String collection, byte[] key, byte[] value) {
        return submitGoverned(signer, DppStarterProfile.policyOf(collection),
                AuthenticatedMapContract.Command.single(
                        AuthenticatedMapContract.Mutation.put(collection, key, value)));
    }

    private record Current(DppValues.ProductValue value, long revision) {
    }

    private Current currentProduct(String productId) {
        AuthenticatedMapContract.PointResult point = chain.entry(DppStarterProfile.PRODUCTS,
                DppStarterProfile.productKey(productId), null);
        if (point.presence() != AuthenticatedMapContract.PRESENCE_ACTIVE) {
            throw DppException.invalid("product " + productId + " is "
                    + (point.presence() == AuthenticatedMapContract.PRESENCE_REVOKED ? "revoked" : "not registered"));
        }
        try {
            return new Current(DppValues.ProductValue.decode(point.entry().value()),
                    point.entry().revision());
        } catch (RuntimeException malformed) {
            throw DppException.malformed("product entry of " + productId + " is malformed");
        }
    }

    // ------------------------------------------------------------------ certification round

    /** Proposes a certificate: the certifier signs {@code PROPOSE} over the approval-routed action. */
    public CertificationRequest proposeCertificate(Signer certifier, String certificateId,
                                                   DppValues.CertificateValue value) {
        return propose(certifier, value.productId(), certificateId, "PUT_IF_ABSENT",
                AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.putIfAbsent(
                        DppStarterProfile.CERTIFICATES, DppStarterProfile.certificateKey(certificateId),
                        value.encode())));
    }

    /** Proposes the revocation of an active certificate through the same round. */
    public CertificationRequest proposeRevocation(Signer certifier, String certificateId) {
        byte[] key = DppStarterProfile.certificateKey(certificateId);
        AuthenticatedMapContract.PointResult point = chain.entry(DppStarterProfile.CERTIFICATES, key, null);
        if (point.presence() != AuthenticatedMapContract.PRESENCE_ACTIVE) {
            throw DppException.invalid("certificate " + certificateId + " is not active");
        }
        String productId;
        try {
            productId = DppValues.CertificateValue.decode(point.entry().value()).productId();
        } catch (RuntimeException malformed) {
            throw DppException.malformed("certificate entry of " + certificateId + " is malformed");
        }
        return propose(certifier, productId, certificateId, "REVOKE",
                AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.revoke(
                        DppStarterProfile.CERTIFICATES, key, point.entry().revision(), null)));
    }

    private CertificationRequest propose(Signer certifier, String productId, String certificateId,
                                         String operation, AuthenticatedMapContract.Command command) {
        Resolved resolved = resolve(certifier);
        if (resolved.tip() < 1) {
            throw DppException.usage("certification needs a chain with at least one block");
        }
        ApprovalPolicyV1 policy = chain.approvalPolicy(DppStarterProfile.CERTIFICATION_POLICY);
        var action = TrustRegistrySigner.approvalAction(command, DppStarterProfile.CERTIFICATION_POLICY);
        byte[] payloadHash = TrustRegistrySigner.approvalPayloadHash(resolved.genesisId(), action);
        long deadline = resolved.tip() + Math.min(policy.maximumLifetimeBlocks(),
                DppStarterProfile.CERTIFICATION_LIFETIME_BLOCKS);
        byte[] random = new byte[8];
        RANDOM.nextBytes(random);
        String proposalId = "cert-" + HEX.formatHex(random);
        String messageId = chain.submitStatement(TrustRegistrySigner.signedStatement(
                ActorStatementV1.Action.PROPOSE, chainId(), proposalId,
                DppStarterProfile.CERTIFICATION_POLICY, policy.revision(), payloadHash, deadline,
                resolved.actor(), ""));
        chain.awaitFinalized(messageId, timeout);
        return new CertificationRequest(chainId(), HEX.formatHex(resolved.genesisId()),
                DppStarterProfile.CERTIFICATION_POLICY, policy.revision(), proposalId,
                HEX.formatHex(AuthenticatedMapContract.encodeCommand(command)),
                HEX.formatHex(payloadHash), deadline, productId, certificateId, operation, messageId);
    }

    /** An auditor's {@code APPROVE} (clause {@code independent-auditors}) or {@code REJECT}; returns the message id. */
    public String decideCertification(Signer auditor, CertificationRequest request, boolean approve) {
        requireRequest(request);
        Resolved resolved = resolve(auditor);
        String messageId = chain.submitStatement(TrustRegistrySigner.signedStatement(
                approve ? ActorStatementV1.Action.APPROVE : ActorStatementV1.Action.REJECT,
                chainId(), request.proposalId(), request.policyId(), request.policyRevision(),
                request.payloadHash(), request.deadlineHeight(), resolved.actor(),
                approve ? DppStarterProfile.CERTIFICATION_CLAUSE : ""));
        chain.awaitFinalized(messageId, timeout);
        return messageId;
    }

    /** Submits the map command carrying the approval reference; the map rejects it unless the proposal is approved. */
    public WriteResult applyCertification(CertificationRequest request) {
        requireRequest(request);
        String messageId = chain.submit(TrustRegistrySigner.approvalCommand(request.action(),
                request.proposalId(), request.policyId(), request.policyRevision()));
        return new WriteResult(messageId, chain.awaitReceipt(messageId, timeout));
    }

    private void requireRequest(CertificationRequest request) {
        if (!request.chainId().equals(chainId())) {
            throw DppException.usage("the request belongs to chain " + request.chainId());
        }
        if (!request.consistent()) {
            throw DppException.invalid("the request's payload hash does not match its command");
        }
    }

    // ------------------------------------------------------------------ reads

    public Optional<AuthenticatedMapContract.Entry> entry(String collection, byte[] key) {
        AuthenticatedMapContract.PointResult point = chain.entry(collection, key, null);
        return Optional.ofNullable(point.entry());
    }

    /** Replays finalized, receipt-confirmed map commands into the projection up to {@code toHeight}. */
    public long replay(long toHeight) {
        long from = projection.replayedHeight() + 1;
        while (from <= toHeight) {
            TrustRegistryClient.BlockPage page = chain.blocks(from, BLOCK_PAGE);
            if (page.blocks().isEmpty()) break;
            long last = from - 1;
            for (TrustRegistryClient.BlockSummary summary : page.blocks()) {
                if (summary.height() > toHeight) break;
                last = summary.height();
                if (summary.messageCount() == 0) continue;
                int position = 0;
                for (TrustRegistryClient.ReplayedCommand replayed : chain.commands(summary.height())) {
                    projection.countCommand();
                    if (projection.commandCount() > MAX_REPLAY_COMMANDS) {
                        throw DppException.unavailable("the projection bound of "
                                + MAX_REPLAY_COMMANDS + " commands was exceeded");
                    }
                    if (replayed.receipt().status() == AuthenticatedMapContract.RECEIPT_APPLIED) {
                        List<AuthenticatedMapContract.Mutation> mutations =
                                replayed.command().action().mutations();
                        for (AuthenticatedMapContract.MutationResult result : replayed.receipt().results()) {
                            for (AuthenticatedMapContract.Mutation mutation : mutations) {
                                if (mutation.collectionId().equals(result.collectionId())
                                        && Arrays.equals(mutation.applicationKey(), result.applicationKey())) {
                                    projection.apply(replayed.height(), position,
                                            HEX.formatHex(replayed.messageId()), mutation);
                                }
                            }
                        }
                    }
                    position++;
                }
                projection.markReplayed(summary.height());
            }
            if (last < from) break;
            from = last + 1;
        }
        projection.markReplayed(Math.min(toHeight, Math.max(projection.replayedHeight(), from - 1)));
        return projection.replayedHeight();
    }

    /** The passport of a product at the tip (null) or an exact retained height. */
    public PassportBundle passport(String productId, Long requestedHeight) {
        DppStarterProfile.requireProductId(productId);
        long height = requestedHeight != null ? requestedHeight : chain.tipHeight();
        if (height < 1) {
            throw DppException.unavailable("the chain has not finalized a block yet");
        }
        replay(height);
        PassportProjection.ProductKeys keys = projection.keysAt(productId, height);
        StatusAnswer product = chain.answer(DppStarterProfile.PRODUCTS,
                DppStarterProfile.productKey(productId), height);
        List<StatusAnswer> versions = answers(DppStarterProfile.VERSIONS, keys.versions(), height);
        List<StatusAnswer> claims = answers(DppStarterProfile.CLAIMS, keys.claims(), height);
        List<StatusAnswer> events = answers(DppStarterProfile.EVENTS, keys.events(), height);
        List<StatusAnswer> certificates = new ArrayList<>();
        for (byte[] key : keys.certificates().values()) {
            certificates.add(certificateAnswer(DppStarterProfile.text(key), height));
        }
        return new PassportBundle(chainId(), product.profile(), product.genesisIdHex(), height,
                product.stateRootHex(), product.blockHashHex(), productId, product, versions,
                claims, events, certificates, projection.timeline(productId, height));
    }

    private List<StatusAnswer> answers(String collection, Map<String, byte[]> keys, long height) {
        List<StatusAnswer> answers = new ArrayList<>();
        for (byte[] key : keys.values()) {
            answers.add(chain.answer(collection, key, height));
        }
        return answers;
    }

    /**
     * A certificate answer with the state proof of its proposal's one-use approval consumption
     * added, the proposal id read from the applied command's approval reference.
     */
    public StatusAnswer certificateAnswer(String certificateId, long height) {
        StatusAnswer answer = chain.answer(DppStarterProfile.CERTIFICATES,
                DppStarterProfile.certificateKey(certificateId), height);
        if (answer.entry() == null || answer.provenance().messageIdHex() == null) {
            return answer;
        }
        String proposalId = proposalIdOf(answer);
        if (proposalId == null) {
            return answer;
        }
        byte[] consumptionKey = TrustRegistryClient.componentKey(TrustRegistryClient.COMPONENT_MAP,
                AuthenticatedMapContract.approvalConsumptionKey(proposalId));
        TrustRegistryClient.ProofEnvelope proof = chain.requireStateProof(consumptionKey, height);
        if (proof.proof().presence() != AppChainClient.ProofPresence.PRESENT
                || proof.proof().valueHex() == null) {
            throw DppException.malformed("approval consumption of proposal " + proposalId
                    + " is not present at height " + height);
        }
        List<StatusAnswer.Fact> facts = new ArrayList<>(answer.facts());
        facts.add(new StatusAnswer.Fact(APPROVAL_CONSUMPTION_FACT, consumptionKey,
                HEX.parseHex(proof.proof().valueHex()), proof.json()));
        return new StatusAnswer(answer.chainId(), answer.profile(), answer.genesisIdHex(),
                answer.height(), answer.stateRootHex(), answer.blockHashHex(), answer.collection(),
                answer.key(), answer.presence(), answer.entry(), answer.provenance(),
                answer.actionCommitment(), answer.authorizationEvidence(), facts, answer.evidenceJson());
    }

    private String proposalIdOf(StatusAnswer answer) {
        Optional<AppChainClient.Block> block = chain.block(answer.entry().lastMutationHeight());
        if (block.isEmpty()) {
            return null;
        }
        for (AppChainClient.Message message : block.get().messages()) {
            if (!message.messageId().equals(answer.provenance().messageIdHex())) continue;
            AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1 command;
            try {
                command = AuthenticatedMapAuthorizationContract.decodeCommand(message.body());
            } catch (RuntimeException undecodable) {
                return null;
            }
            List<AuthenticatedMapContract.Mutation> mutations = command.action().mutations();
            for (int index = 0; index < mutations.size(); index++) {
                AuthenticatedMapContract.Mutation mutation = mutations.get(index);
                if (!mutation.collectionId().equals(answer.collection())
                        || !Arrays.equals(mutation.applicationKey(), answer.key())) continue;
                var assignment = command.action().authorizations().get(index);
                if (assignment.authorizationKind() != AuthenticatedMapContract.AUTH_APPROVAL) {
                    return null;
                }
                var evidence = command.evidence().get(assignment.evidenceHandle() - 1);
                if (evidence instanceof AuthenticatedMapAuthorizationContract.MapApprovalReferenceV1 reference) {
                    return reference.proposalId();
                }
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    public static String errorName(int code) {
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(AuthenticatedMapContract.ERROR_NONE, "NONE");
        names.put(AuthenticatedMapContract.ERROR_UNKNOWN_COLLECTION, "UNKNOWN_COLLECTION");
        names.put(AuthenticatedMapContract.ERROR_COLLECTION_BOUNDS, "COLLECTION_BOUNDS");
        names.put(AuthenticatedMapContract.ERROR_UNAUTHORIZED, "UNAUTHORIZED");
        names.put(AuthenticatedMapContract.ERROR_ALREADY_EXISTS, "ALREADY_EXISTS");
        names.put(AuthenticatedMapContract.ERROR_ABSENT, "ABSENT");
        names.put(AuthenticatedMapContract.ERROR_REVOKED, "REVOKED");
        names.put(AuthenticatedMapContract.ERROR_ACTIVE, "ACTIVE");
        names.put(AuthenticatedMapContract.ERROR_PRECONDITION, "PRECONDITION");
        names.put(AuthenticatedMapContract.ERROR_RESTORE_FORBIDDEN, "RESTORE_FORBIDDEN");
        names.put(AuthenticatedMapContract.ERROR_VALUE_ENCODING, "VALUE_ENCODING");
        names.put(AuthenticatedMapContract.ERROR_VALUE_SCHEMA, "VALUE_SCHEMA");
        names.put(AuthenticatedMapContract.ERROR_VALUE_VALIDATOR, "VALUE_VALIDATOR");
        names.put(AuthenticatedMapContract.ERROR_AUTHORIZATION_ASSIGNMENT, "AUTHORIZATION_ASSIGNMENT");
        names.put(AuthenticatedMapContract.ERROR_UNKNOWN_POLICY, "UNKNOWN_POLICY");
        names.put(AuthenticatedMapContract.ERROR_POLICY_INACTIVE, "POLICY_INACTIVE");
        names.put(AuthenticatedMapContract.ERROR_ACTOR_INELIGIBLE, "ACTOR_INELIGIBLE");
        names.put(AuthenticatedMapContract.ERROR_ACTOR_SIGNATURE, "ACTOR_SIGNATURE");
        names.put(AuthenticatedMapContract.ERROR_AUTHORIZATION_DEADLINE, "AUTHORIZATION_DEADLINE");
        names.put(AuthenticatedMapContract.ERROR_DIRECT_AUTHORIZATION_REPLAY, "DIRECT_AUTHORIZATION_REPLAY");
        names.put(AuthenticatedMapContract.ERROR_APPROVAL_NOT_APPROVED, "APPROVAL_NOT_APPROVED");
        names.put(AuthenticatedMapContract.ERROR_APPROVAL_MISMATCH, "APPROVAL_MISMATCH");
        names.put(AuthenticatedMapContract.ERROR_APPROVAL_REPLAY, "APPROVAL_REPLAY");
        names.put(AuthenticatedMapContract.ERROR_CAPACITY_EXCEEDED, "CAPACITY_EXCEEDED");
        names.put(AuthenticatedMapContract.ERROR_CRYPTO_WORK_EXCEEDED, "CRYPTO_WORK_EXCEEDED");
        names.put(AuthenticatedMapContract.ERROR_GOVERNED_ROUTE_UNSUPPORTED, "GOVERNED_ROUTE_UNSUPPORTED");
        names.put(AuthenticatedMapContract.ERROR_WRONG_GENESIS, "WRONG_GENESIS");
        names.put(AuthenticatedMapContract.ERROR_WRONG_REVISION, "WRONG_REVISION");
        return names.getOrDefault(code, "UNKNOWN");
    }
}
