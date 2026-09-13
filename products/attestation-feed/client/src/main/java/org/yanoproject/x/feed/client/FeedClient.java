package org.yanoproject.x.feed.client;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.AuthenticatedMapProofBundle;
import org.yanoproject.x.feed.profile.Aggregation;
import org.yanoproject.x.feed.profile.FeedDatum;
import org.yanoproject.x.feed.profile.FeedStarterProfile;
import org.yanoproject.x.feed.profile.FeedValues;
import org.yanoproject.x.roles.contracts.ActorStatementV1;
import org.yanoproject.x.roles.contracts.ApprovalPolicyV1;
import org.yanoproject.x.roles.contracts.DirectRolePolicyV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.trust.client.StatusAnswer;
import org.yanoproject.x.trust.client.TrustRegistryClient;
import org.yanoproject.x.trust.client.TrustRegistrySigner;

import java.security.SecureRandom;
import java.time.Clock;
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
 * The attestation feed starter over one chain (ADR-052 §2.2): governed feed and observation
 * writes and the round-close approval round through the ADR-049 map client and signer, the
 * aggregation recomputed from proof-bound answers, and round bundles assembled at the
 * observation height and the record height.
 */
public final class FeedClient {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final int MAX_REPLAY_COMMANDS = 5_000;
    public static final int LATEST_PROBE_ROUNDS = 64;
    public static final String APPROVAL_CONSUMPTION_FACT = AuthenticatedMapProofBundle.APPROVAL_CONSUMPTION;
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BLOCK_PAGE = 64;

    private final TrustRegistryClient chain;
    private final FeedProjection projection = new FeedProjection();
    private final Duration timeout;
    private final Clock clock;

    public FeedClient(TrustRegistryClient chain) {
        this(chain, DEFAULT_TIMEOUT, Clock.systemUTC());
    }

    public FeedClient(TrustRegistryClient chain, Duration timeout, Clock clock) {
        this.chain = Objects.requireNonNull(chain, "chain");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static FeedClient connect(String baseUrl, String chainId, String apiKey) {
        return new FeedClient(TrustRegistryClient.builder(baseUrl, chainId).apiKey(apiKey).build());
    }

    public TrustRegistryClient chain() {
        return chain;
    }

    public String chainId() {
        return chain.chainId();
    }

    public FeedProjection projection() {
        return projection;
    }

    public Clock clock() {
        return clock;
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
                identity.genesis() != null && FeedStarterProfile.matches(identity.genesis()));
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
                throw FeedException.usage("the chain has no block yet; pass the generated"
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
            throw FeedException.invalid(mismatch.getMessage());
        }
        return new Resolved(actor, genesisId, tip);
    }

    // ------------------------------------------------------------------ governed writes

    public record WriteResult(String messageIdHex, AuthenticatedMapContract.Receipt receipt) {
        public boolean applied() {
            return receipt.status() == AuthenticatedMapContract.RECEIPT_APPLIED;
        }

        public String errorName() {
            return FeedClient.errorName(receipt.errorCode());
        }
    }

    /** Submits a governed-role command signed by the signer under the collection's policy. */
    public WriteResult submitGoverned(Signer signer, String policyId,
                                      AuthenticatedMapContract.Command command) {
        Resolved resolved = resolve(signer);
        long policyRevision = 1;
        long lifetime = FeedStarterProfile.DIRECT_AUTHORIZATION_LIFETIME_BLOCKS;
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

    public WriteResult createFeed(Signer admin, String feedId, FeedValues.FeedValue value) {
        return submitGoverned(admin, FeedStarterProfile.FEED_ADMIN_POLICY,
                AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.putIfAbsent(
                        FeedStarterProfile.FEEDS, FeedStarterProfile.feedKey(feedId), value.encode())));
    }

    /** Replaces the feed record with {@code COMPARE_AND_SET} on the revision read. */
    public WriteResult updateFeed(Signer admin, String feedId, FeedValues.FeedValue value) {
        Current current = currentFeed(feedId);
        return submitGoverned(admin, FeedStarterProfile.FEED_ADMIN_POLICY,
                AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.compareAndSet(
                        FeedStarterProfile.FEEDS, FeedStarterProfile.feedKey(feedId), value.encode(),
                        current.revision(), null)));
    }

    /**
     * Records the signer's observation for {@code round} with {@code PUT_IF_ABSENT}: an honest
     * retry is harmless, a rewrite has to be a deliberate raw {@code PUT} and raises the revision.
     */
    public WriteResult observe(Signer source, String feedId, long round, FeedValues.ObservationValue value) {
        return submitGoverned(source, FeedStarterProfile.SOURCE_POLICY,
                AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.putIfAbsent(
                        FeedStarterProfile.OBSERVATIONS,
                        FeedStarterProfile.observationKey(feedId, round, source.actorId()), value.encode())));
    }

    /** A raw governed {@code PUT}; what a rewrite or a foreign write looks like. */
    public WriteResult put(Signer signer, String collection, byte[] key, byte[] value) {
        return submitGoverned(signer, FeedStarterProfile.policyOf(collection),
                AuthenticatedMapContract.Command.single(
                        AuthenticatedMapContract.Mutation.put(collection, key, value)));
    }

    private record Current(FeedValues.FeedValue value, long revision) {
    }

    private Current currentFeed(String feedId) {
        AuthenticatedMapContract.PointResult point = chain.entry(FeedStarterProfile.FEEDS,
                FeedStarterProfile.feedKey(feedId), null);
        if (point.presence() != AuthenticatedMapContract.PRESENCE_ACTIVE) {
            throw FeedException.invalid("feed " + feedId + " is "
                    + (point.presence() == AuthenticatedMapContract.PRESENCE_REVOKED ? "revoked" : "not defined"));
        }
        try {
            return new Current(FeedValues.FeedValue.decode(point.entry().value()), point.entry().revision());
        } catch (RuntimeException malformed) {
            throw FeedException.malformed("feed entry of " + feedId + " is malformed");
        }
    }

    // ------------------------------------------------------------------ aggregation

    /**
     * The round computed from the chain at one height: the feed and observation answers, the
     * aggregation, and the hashes the round record binds.
     */
    public record Computation(StatusAnswer feed, FeedValues.FeedValue feedValue,
                              List<StatusAnswer> observations, Aggregation.Result result,
                              byte[] policySha256, byte[] datumSha256, byte[] datum) {
        public FeedValues.RoundValue record(long closedAtHeight) {
            return result.record(closedAtHeight, policySha256, datumSha256);
        }
    }

    /** Reads the feed and every configured source's observation at {@code height} and aggregates. */
    public Computation compute(String feedId, long round, long height) {
        StatusAnswer feed = chain.answer(FeedStarterProfile.FEEDS, FeedStarterProfile.feedKey(feedId), height);
        if (feed.presence() != StatusAnswer.Presence.ACTIVE) {
            throw FeedException.invalid("feed " + feedId + " is not defined at height " + height);
        }
        FeedValues.FeedValue value;
        try {
            value = FeedValues.FeedValue.decode(feed.entry().value());
        } catch (RuntimeException malformed) {
            throw FeedException.malformed("feed entry of " + feedId + " is malformed");
        }
        List<StatusAnswer> observations = new ArrayList<>();
        List<Aggregation.Input> inputs = new ArrayList<>();
        for (String source : value.sources()) {
            StatusAnswer answer = chain.answer(FeedStarterProfile.OBSERVATIONS,
                    FeedStarterProfile.observationKey(feedId, round, source), height);
            observations.add(answer);
            inputs.add(RoundBundle.input(source, answer));
        }
        Aggregation.Result result = Aggregation.aggregate(value, round, inputs);
        byte[] policySha256 = FeedValues.sha256(feed.entry().value());
        byte[] datum = null;
        byte[] datumSha256 = null;
        if (result.closed()) {
            datum = FeedDatum.encode(datumFields(chainId(), feedId, round, value, result, height, feed.stateRootHex()));
            datumSha256 = FeedValues.sha256(datum);
        }
        return new Computation(feed, value, observations, result, policySha256, datumSha256, datum);
    }

    static FeedDatum.Fields datumFields(String chainId, String feedId, long round, FeedValues.FeedValue feed,
                                        Aggregation.Result result, long closedAtHeight, String stateRootHex) {
        return new FeedDatum.Fields(chainId, feedId, round, result.aggregate(), result.scale(),
                feed.roundEnd(round), closedAtHeight, HEX.parseHex(stateRootHex), result.acceptedSources().size());
    }

    // ------------------------------------------------------------------ round close

    /**
     * Proposes the round record: aggregates at {@code height} (the tip when null), builds the
     * approval-routed {@code PUT_IF_ABSENT}, and signs the operator's {@code PROPOSE}.
     */
    public RoundRequest proposeRound(Signer operator, String feedId, long round, Long height) {
        Resolved resolved = resolve(operator);
        if (resolved.tip() < 1) {
            throw FeedException.usage("a round close needs a chain with at least one block");
        }
        long closedAtHeight = height != null ? height : resolved.tip();
        if (closedAtHeight > resolved.tip()) {
            throw FeedException.invalid("height " + closedAtHeight + " is beyond the tip " + resolved.tip());
        }
        Computation computation = compute(feedId, round, closedAtHeight);
        if (!computation.feedValue().active()) {
            throw FeedException.invalid("feed " + feedId + " is paused; it closes no rounds");
        }
        AuthenticatedMapContract.PointResult existing = chain.entry(FeedStarterProfile.ROUNDS,
                FeedStarterProfile.roundKey(feedId, round), null);
        if (existing.presence() != AuthenticatedMapContract.PRESENCE_ABSENT) {
            throw FeedException.invalid("round " + round + " of " + feedId + " already has a record");
        }
        return propose(operator, resolved, feedId, round, computation.record(closedAtHeight));
    }

    /** The proposal itself, for any record; the test suite uses it to push a wrong record. */
    RoundRequest propose(Signer operator, Resolved resolved, String feedId, long round,
                         FeedValues.RoundValue record) {
        AuthenticatedMapContract.Command command = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.putIfAbsent(FeedStarterProfile.ROUNDS,
                        FeedStarterProfile.roundKey(feedId, round), record.encode()));
        ApprovalPolicyV1 policy = chain.approvalPolicy(FeedStarterProfile.ROUND_POLICY);
        var action = TrustRegistrySigner.approvalAction(command, FeedStarterProfile.ROUND_POLICY);
        byte[] payloadHash = TrustRegistrySigner.approvalPayloadHash(resolved.genesisId(), action);
        long deadline = resolved.tip() + Math.min(policy.maximumLifetimeBlocks(),
                FeedStarterProfile.ROUND_LIFETIME_BLOCKS);
        byte[] random = new byte[8];
        RANDOM.nextBytes(random);
        String proposalId = "round-" + HEX.formatHex(random);
        String messageId = chain.submitStatement(TrustRegistrySigner.signedStatement(
                ActorStatementV1.Action.PROPOSE, chainId(), proposalId,
                FeedStarterProfile.ROUND_POLICY, policy.revision(), payloadHash, deadline,
                resolved.actor(), ""));
        chain.awaitFinalized(messageId, timeout);
        return new RoundRequest(chainId(), HEX.formatHex(resolved.genesisId()),
                FeedStarterProfile.ROUND_POLICY, policy.revision(), proposalId,
                HEX.formatHex(AuthenticatedMapContract.encodeCommand(command)),
                HEX.formatHex(payloadHash), deadline, feedId, round, messageId);
    }

    RoundRequest propose(Signer operator, String feedId, long round, FeedValues.RoundValue record) {
        return propose(operator, resolve(operator), feedId, round, record);
    }

    /**
     * A publisher's {@code APPROVE}: the round is recomputed at the request's declared closing
     * height first, and the approval is refused when the record does not equal the recomputation.
     */
    public String approveRound(Signer publisher, RoundRequest request) {
        requireRequest(request);
        FeedValues.RoundValue declared = request.recordValue();
        Computation computation = compute(request.feedId(), request.round(), declared.closedAtHeight());
        FeedValues.RoundValue recomputed = computation.record(declared.closedAtHeight());
        if (!recomputed.equals(declared)) {
            throw FeedException.invalid("the recomputation of round " + request.round() + " of "
                    + request.feedId() + " at height " + declared.closedAtHeight() + " ("
                    + recomputed.statusName() + " " + FeedValues.decimal(recomputed.aggregate(), recomputed.scale())
                    + " from " + recomputed.acceptedSources() + ") disagrees with the request ("
                    + declared.statusName() + " " + FeedValues.decimal(declared.aggregate(), declared.scale())
                    + " from " + declared.acceptedSources() + "); not approving");
        }
        return decide(publisher, request, true);
    }

    public String rejectRound(Signer publisher, RoundRequest request) {
        requireRequest(request);
        return decide(publisher, request, false);
    }

    /** The signed statement without the recomputation; package-private so a test can push a wrong record. */
    String decide(Signer publisher, RoundRequest request, boolean approve) {
        Resolved resolved = resolve(publisher);
        String messageId = chain.submitStatement(TrustRegistrySigner.signedStatement(
                approve ? ActorStatementV1.Action.APPROVE : ActorStatementV1.Action.REJECT,
                chainId(), request.proposalId(), request.policyId(), request.policyRevision(),
                request.payloadHash(), request.deadlineHeight(), resolved.actor(),
                approve ? FeedStarterProfile.ROUND_CLAUSE : ""));
        chain.awaitFinalized(messageId, timeout);
        return messageId;
    }

    /** Submits the map command carrying the approval reference; the map rejects it unless the proposal is approved. */
    public WriteResult applyRound(RoundRequest request) {
        requireRequest(request);
        String messageId = chain.submit(TrustRegistrySigner.approvalCommand(request.action(),
                request.proposalId(), request.policyId(), request.policyRevision()));
        return new WriteResult(messageId, chain.awaitReceipt(messageId, timeout));
    }

    private void requireRequest(RoundRequest request) {
        if (!request.chainId().equals(chainId())) {
            throw FeedException.usage("the request belongs to chain " + request.chainId());
        }
        if (!request.consistent()) {
            throw FeedException.invalid("the request's payload hash or command does not match the document");
        }
    }

    // ------------------------------------------------------------------ reads

    public Optional<AuthenticatedMapContract.Entry> entry(String collection, byte[] key) {
        return Optional.ofNullable(chain.entry(collection, key, null).entry());
    }

    /** The feed record at the tip with its proof, or null when it was never defined. */
    public StatusAnswer feedAnswer(String feedId, Long height) {
        return chain.answer(FeedStarterProfile.FEEDS, FeedStarterProfile.feedKey(feedId), height);
    }

    /**
     * The round bundle. Without a height: the record's declared closing height answers the
     * feed and the observations and the record's apply height answers the record, so a closed
     * round always yields the same bundle; an open round is answered at the tip. With a height,
     * everything is answered at that height, which lets a consumer re-answer a closed round
     * later and compare (ADR-052 §4).
     */
    public RoundBundle round(String feedId, long round, Long requestedHeight) {
        FeedStarterProfile.requireFeedId(feedId);
        FeedStarterProfile.requireRound(round);
        long tip = chain.tipHeight();
        if (tip < 1) {
            throw FeedException.unavailable("the chain has not finalized a block yet");
        }
        long observationHeight;
        long recordHeight;
        if (requestedHeight != null) {
            observationHeight = requestedHeight;
            recordHeight = requestedHeight;
        } else {
            AuthenticatedMapContract.PointResult point = chain.entry(FeedStarterProfile.ROUNDS,
                    FeedStarterProfile.roundKey(feedId, round), null);
            if (point.presence() == AuthenticatedMapContract.PRESENCE_ACTIVE) {
                FeedValues.RoundValue record;
                try {
                    record = FeedValues.RoundValue.decode(point.entry().value());
                } catch (RuntimeException malformed) {
                    throw FeedException.malformed("round record of " + feedId + "/" + round + " is malformed");
                }
                observationHeight = record.closedAtHeight();
                recordHeight = Math.max(point.entry().lastMutationHeight(), observationHeight);
            } else {
                observationHeight = tip;
                recordHeight = tip;
            }
        }
        StatusAnswer feed = chain.answer(FeedStarterProfile.FEEDS, FeedStarterProfile.feedKey(feedId), observationHeight);
        if (feed.presence() != StatusAnswer.Presence.ACTIVE) {
            throw FeedException.invalid("feed " + feedId + " is not defined at height " + observationHeight);
        }
        FeedValues.FeedValue value;
        try {
            value = FeedValues.FeedValue.decode(feed.entry().value());
        } catch (RuntimeException malformed) {
            throw FeedException.malformed("feed entry of " + feedId + " is malformed");
        }
        List<StatusAnswer> observations = new ArrayList<>();
        for (String source : value.sources()) {
            observations.add(chain.answer(FeedStarterProfile.OBSERVATIONS,
                    FeedStarterProfile.observationKey(feedId, round, source), observationHeight));
        }
        StatusAnswer record = answerWithConsumption(FeedStarterProfile.ROUNDS,
                FeedStarterProfile.roundKey(feedId, round), recordHeight);
        return new RoundBundle(chainId(), feed.profile(), feed.genesisIdHex(), feedId, round,
                observationHeight, feed, observations, record);
    }

    /**
     * The newest round with a record, found by probing backwards from the calendar's current
     * round (the client's clock) through at most {@link #LATEST_PROBE_ROUNDS} rounds; null when
     * none has a record in that window.
     */
    public RoundBundle latestRound(String feedId) {
        Current current = currentFeed(feedId);
        long now = clock.instant().getEpochSecond();
        if (now < current.value().epochStart()) {
            return null;
        }
        long round = current.value().roundOf(now);
        for (int probe = 0; probe <= LATEST_PROBE_ROUNDS && round - probe >= 0; probe++) {
            long candidate = round - probe;
            AuthenticatedMapContract.PointResult point = chain.entry(FeedStarterProfile.ROUNDS,
                    FeedStarterProfile.roundKey(feedId, candidate), null);
            if (point.presence() == AuthenticatedMapContract.PRESENCE_ACTIVE) {
                return round(feedId, candidate, null);
            }
        }
        return null;
    }

    /** The calendar's current round for the feed at the client's clock, or -1 before the epoch. */
    public long currentRound(FeedValues.FeedValue feed) {
        long now = clock.instant().getEpochSecond();
        return now < feed.epochStart() ? -1 : feed.roundOf(now);
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
                for (TrustRegistryClient.ReplayedCommand replayed : chain.commands(summary.height())) {
                    projection.countCommand();
                    if (projection.commandCount() > MAX_REPLAY_COMMANDS) {
                        throw FeedException.unavailable("the projection bound of "
                                + MAX_REPLAY_COMMANDS + " commands was exceeded");
                    }
                    if (replayed.receipt().status() != AuthenticatedMapContract.RECEIPT_APPLIED) continue;
                    List<AuthenticatedMapContract.Mutation> mutations = replayed.command().action().mutations();
                    for (AuthenticatedMapContract.MutationResult result : replayed.receipt().results()) {
                        for (AuthenticatedMapContract.Mutation mutation : mutations) {
                            if (mutation.collectionId().equals(result.collectionId())
                                    && Arrays.equals(mutation.applicationKey(), result.applicationKey())) {
                                projection.apply(mutation);
                            }
                        }
                    }
                }
                projection.markReplayed(summary.height());
            }
            if (last < from) break;
            from = last + 1;
        }
        projection.markReplayed(Math.min(toHeight, Math.max(projection.replayedHeight(), from - 1)));
        return projection.replayedHeight();
    }

    /** The feed ids the projection has seen up to the tip. */
    public List<String> feeds() {
        long tip = chain.tipHeight();
        if (tip >= 1) replay(tip);
        return projection.feedIds();
    }

    /**
     * An answer with the state proof of its proposal's one-use approval consumption added, the
     * proposal id read from the applied command's approval reference.
     */
    public StatusAnswer answerWithConsumption(String collection, byte[] key, long height) {
        StatusAnswer answer = chain.answer(collection, key, height);
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
            throw FeedException.malformed("approval consumption of proposal " + proposalId
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
