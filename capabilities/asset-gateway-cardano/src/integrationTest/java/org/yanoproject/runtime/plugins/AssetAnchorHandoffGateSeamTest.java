package org.yanoproject.runtime.plugins;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventContext;
import com.bloxbean.cardano.yaci.events.api.EventListener;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.CanonicalBlockReference;
import org.yanoproject.api.ChainBlockReader;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.catalog.BundleContribution;
import org.yanoproject.catalog.BundleManifest;
import org.yanoproject.catalog.BundleManifestParser;
import org.yanoproject.catalog.ContributionKind;
import org.yanoproject.x.assets.cardano.gateway.AnchorSeamFixtures;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;
import org.yanoproject.x.framework.api.appchain.AppChainGateway;
import org.yanoproject.x.framework.api.appchain.FinalityCert;
import org.yanoproject.x.framework.api.appchain.codec.AppBlockCodec;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverProvider;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoff;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoffGate;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoffGateProvider;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.runtime.blockproducer.DevnetBlockBuilder;
import org.yanoproject.runtime.chain.BlockBodyRetentionRegistry;
import org.yanoproject.runtime.chain.InMemoryChainState;
import org.yanoproject.x.framework.cardano.AnchorPinFixtures;
import org.yanoproject.x.framework.cardano.AppChainSubsystem;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-059 D8b handoff through the real plugin seam (the pattern of {@code MembershipHandoffGateSeamTest}): the
 * gateway bundle's real manifest is parsed by the host's catalog parser, its {@code membership-handoff-gate} and
 * {@code asset-anchor-v1} contributions are resolved through the catalog registry and the production kind bindings and
 * facades, and a running app chain selects the gate by the consensus-committed {@code membership.anchor-handoff-gate}.
 * A governed change goes pending and stays pending across blocks that carry no anchor fact (the gate runs in every
 * block commit and is total), is cancelled, and the facade-wrapped gate finalizes the re-committed change only on the
 * real observer's L1-stable anchor fact for it, bound by the committed height. The chain pins its anchor identity
 * ({@code anchor-profile.pinned.*}, which the gate requires and the framework checks against its own anchor artifact,
 * genesis id, application id and commitment profile) to the identity the observer watches.
 */
@Timeout(120)
class AssetAnchorHandoffGateSeamTest {
    private static final String BUNDLE_ID = "org.yanoproject.x.asset-gateway.cardano";
    private static final String GATE_ID = "asset-anchor-handoff-v1";
    private static final String CHAIN_ID = "handoff-seam";
    private static final long PROTOCOL_MAGIC = 42;
    private static final PluginKinds KINDS = PluginKinds.load(AssetAnchorHandoffGateSeamTest.class.getClassLoader());

    @TempDir
    Path directory;

    @Test
    void theBundlesGateIsSelectedThroughTheCatalogAndFinalizesOnlyItsOwnHandoff() throws Exception {
        CatalogPluginProviderRegistry registry = registry();
        assertThat(registry.names(MembershipHandoffGateProvider.class)).containsExactly(GATE_ID);
        assertThat(registry.names(L1ObserverProvider.class)).contains("asset-anchor-v1", "asset-vault-payout-v1",
                "asset-shard-bootstrap-v1", "asset-vault-deposit-v1");

        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 0x47);
        String member = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
        // The pin names the chain's real identity: its genesis id is derived from the configuration (the pin and
        // the observer's identity settings are not inputs to it), so a probe instance reads it first.
        byte[] genesisId = genesisId(seed, member, settings(identity(fill(0x31))), registry);
        AnchorSeamFixtures.Identity identity = identity(genesisId);
        Map<String, String> settings = settings(identity);
        L1 l1 = new L1();
        l1.append(10, List.of());
        l1.append(20, List.of());
        DirectEventBus events = new DirectEventBus();
        AppChainSubsystem node = new AppChainSubsystem(config(seed, member, settings), PROTOCOL_MAGIC, events, null,
                directory.resolve("chain").toString(), null, registry,
                LoggerFactory.getLogger(AssetAnchorHandoffGateSeamTest.class));
        assertThat(node.stateCommitmentIdentity().orElseThrow().genesisId()).isEqualTo(genesisId);
        node.wireL1(cbor -> "00".repeat(32), () -> EMPTY_UTXOS);
        node.wireL1Chain(l1.reader(), new BlockBodyRetentionRegistry().register(OptionalLong.of(0)));
        Hash32 key = Hash32.ofHex(member);
        try {
            node.start();
            awaitDelivered(node, l1.tip());
            l1.append(30, List.of());
            l1.append(40, List.of());
            wake(events);
            awaitDelivered(node, l1.tip());
            assertThat(handoff(node).containsKey("pendingCommittedHeight")).isFalse();

            // A governed change goes pending; blocks without an anchor fact never finalize it.
            node.setThreshold(1);
            await(() -> handoff(node).containsKey("pendingCommittedHeight"));
            long first = pending(node);
            for (int i = 0; i < 3; i++) nextBlock(node, i);
            assertThat(pending(node)).isEqualTo(first);

            // Cancelled and re-committed: an L1-stable anchor built for the cancelled change does not finalize it.
            AppChainGateway gateway = node;
            assertThat(gateway.cancelPendingMembershipHandoff()).isEqualTo(first);
            await(() -> !handoff(node).containsKey("pendingCommittedHeight"));
            node.setThreshold(1);
            await(() -> handoff(node).containsKey("pendingCommittedHeight"));
            long second = pending(node);
            assertThat(second).isGreaterThan(first);
            long epochBefore = ((Number) node.status().get("membershipEpochFromHeight")).longValue();

            l1.append(50, List.of(AnchorSeamFixtures.anchorAdvanceTransaction(identity, 1, first, 1, key)));
            l1.append(60, List.of());
            wake(events);
            awaitDelivered(node, l1.tip());
            nextBlock(node, 9);
            nextBlock(node, 10);
            assertThat(pending(node)).isEqualTo(second);

            // The real observer's L1-stable anchor output carrying this change finalizes it in consensus.
            l1.append(70, List.of(AnchorSeamFixtures.anchorAdvanceTransaction(identity, 2, second, 1, key)));
            l1.append(80, List.of());
            wake(events);
            await(() -> !handoff(node).containsKey("pendingCommittedHeight"));
            long epochAfter = ((Number) node.status().get("membershipEpochFromHeight")).longValue();
            assertThat(epochAfter).isGreaterThan(Math.max(epochBefore, second));

            // The same gate through the catalog facade, against the real observer's facts.
            MembershipHandoffGate gate = registry.find(MembershipHandoffGateProvider.class, GATE_ID).orElseThrow()
                    .create(CHAIN_ID, settings);
            L1Observer observer = registry.find(L1ObserverProvider.class, "asset-anchor-v1").orElseThrow()
                    .create(AnchorSeamFixtures.OBSERVER, identity.observerSettings());
            MembershipHandoff change = new MembershipHandoff(second, Set.of(member), 1, 1);
            assertThat(gate.finalizesHandoff(block(AnchorSeamFixtures.anchorAdvance(observer, identity, 0x61, first, 1,
                    key)),
                    change)).isFalse();
            assertThat(gate.finalizesHandoff(block(AnchorSeamFixtures.anchorAdvance(observer, identity, 0x62, second, 1,
                    key)),
                    change)).isTrue();
        } finally {
            node.close();
        }
    }

    /** The bundle's own manifest, parsed by the host's catalog parser, as catalog registry entries. */
    private static CatalogPluginProviderRegistry registry() throws Exception {
        ClassLoader loader = AssetAnchorHandoffGateSeamTest.class.getClassLoader();
        String resource = "META-INF/yano/plugins/" + BUNDLE_ID + ".json";
        BundleManifest manifest;
        try (InputStream input = loader.getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            manifest = new BundleManifestParser(KINDS.registry()).parse(resource, input);
        }
        assertThat(manifest.id()).isEqualTo(BUNDLE_ID);
        List<CatalogPluginProviderRegistry.Entry> entries = new ArrayList<>();
        for (BundleContribution contribution : manifest.contributions()) {
            ContributionKind kind = contribution.kind();
            Class<?> providerClass = Class.forName(contribution.provider(), true, loader);
            entries.add(new CatalogPluginProviderRegistry.Entry(BUNDLE_ID, kind, contribution.name(),
                    contribution.provider(), manifest, () -> {
                        try {
                            return providerClass.getConstructor().newInstance();
                        } catch (ReflectiveOperationException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }));
        }
        return new CatalogPluginProviderRegistry(KINDS, entries, List.of(BUNDLE_ID), List.of(), loader);
    }

    /** The anchor profile v2 settings the chain and its pinned validator hash share. */
    private static Map<String, String> anchorProfile() {
        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("anchor-profile.version", "2");
        profile.put("anchor-profile.slot-zero", "0");
        profile.put("anchor-profile.slot-zero-time-ms", "1700000000000");
        profile.put("anchor-profile.slot-length-ms", "1000");
        return profile;
    }

    /** The genesis id a chain with this configuration derives, read from an instance that is never started. */
    private byte[] genesisId(byte[] seed, String member, Map<String, String> settings,
                             CatalogPluginProviderRegistry registry) {
        AppChainSubsystem probe = new AppChainSubsystem(config(seed, member, settings), PROTOCOL_MAGIC,
                new DirectEventBus(), null, directory.resolve("probe").toString(), null, registry,
                LoggerFactory.getLogger(AssetAnchorHandoffGateSeamTest.class));
        try {
            return probe.stateCommitmentIdentity().orElseThrow().genesisId();
        } finally {
            probe.close();
        }
    }

    /**
     * The chain's real anchor identity: the framework's v2 anchor validator for the thread policy, the chain's
     * genesis id, its application id (the default state machine) and the MPF commitment profile.
     */
    private static AnchorSeamFixtures.Identity identity(byte[] genesisId) {
        byte[] policy = new byte[28];
        Arrays.fill(policy, (byte) 0xc1);
        byte[] scriptHash = AnchorPinFixtures.validatorScriptHash(policy, anchorProfile(), PROTOCOL_MAGIC);
        return new AnchorSeamFixtures.Identity(CHAIN_ID, HexUtil.encodeHexString(policy),
                "anchor".getBytes(StandardCharsets.US_ASCII), scriptHash, HexUtil.encodeHexString(genesisId),
                AppChainConfig.DEFAULT_STATE_MACHINE, StateCommitmentProfiles.MPF.id(),
                HexUtil.encodeHexString(StateCommitmentProfiles.MPF.formatFingerprint()));
    }

    private static Map<String, String> settings(AnchorSeamFixtures.Identity identity) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("membership.mode", "governed");
        settings.put("membership.anchor-handoff", "true");
        settings.put("membership.anchor-handoff-gate", GATE_ID);
        settings.putAll(anchorProfile());
        settings.putAll(identity.pinnedSettings());
        settings.put("observation.l1-network-genesis-id", "01".repeat(32));
        identity.observerSettings().forEach((key, value) ->
                settings.put("observers." + AnchorSeamFixtures.OBSERVER + "." + key, value));
        return settings;
    }

    private static AppChainConfig config(byte[] seed, String member, Map<String, String> settings) {
        return AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(HexUtil.encodeHexString(seed))
                .memberKeysHex(Set.of(member))
                .proposerKeyHex(member)
                .threshold(1)
                .blockIntervalMs(50)
                .l1StabilityDepth(1)
                .stateCommitmentIdentity(StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF, fill(0x31)))
                .pluginSettings(settings)
                .build();
    }

    private static AppBlock block(List<L1Observation> observations) {
        List<AppMessage> messages = new ArrayList<>();
        long seq = 1;
        byte[] sender = fill(0x09);
        for (L1Observation observation : observations) {
            byte[] body = observation.encode();
            messages.add(AppMessage.builder()
                    .messageId(AppMessage.computeMessageId(CHAIN_ID, observation.topic(), sender, seq,
                            4_000_000_000L, body))
                    .chainId(CHAIN_ID).topic(observation.topic()).sender(sender).senderSeq(seq++)
                    .expiresAt(4_000_000_000L).body(body).authScheme(AuthScheme.ED25519.getValue())
                    .authProof(new byte[64]).build());
        }
        return new AppBlock(AppBlock.BLOCK_VERSION, CHAIN_ID, 99, AppBlock.GENESIS_PREV_HASH, 0, new byte[0],
                1_000, AppBlockCodec.messagesRoot(messages), new byte[32], messages, new byte[32],
                FinalityCert.empty());
    }

    /** App ledgers make blocks only on messages: submits one and waits for its block. */
    private static void nextBlock(AppChainSubsystem node, int nonce) throws InterruptedException {
        long next = node.tipHeight() + 1;
        node.submit("ordinary", new byte[] {(byte) nonce});
        await(() -> node.tipHeight() >= next);
    }

    private static void awaitDelivered(AppChainSubsystem node, long blockNumber) throws InterruptedException {
        await(() -> node.status().get("l1Delivery") instanceof Map<?, ?> delivery
                && delivery.get("cursorBlock") instanceof Number cursor && cursor.longValue() >= blockNumber
                && "RUNNING".equals(delivery.get("state")) && !delivery.containsKey("pending"));
    }

    /** A node event carries nothing the delivery loop reads; it only wakes it. */
    private static void wake(DirectEventBus events) {
        events.publish(new BlockAppliedEvent(null, 0, 0, "00".repeat(32), null), EventMetadata.builder().build(),
                PublishOptions.builder().build());
    }

    /** A small L1: real Conway blocks from the devnet block builder in an in-memory chain state. */
    private static final class L1 {
        private final InMemoryChainState chain = new InMemoryChainState();
        private final DevnetBlockBuilder builder = new DevnetBlockBuilder();
        private final List<CanonicalBlockReference> blocks = new ArrayList<>();

        synchronized void append(long slot, List<byte[]> transactions) {
            long number = blocks.size();
            byte[] previous = blocks.isEmpty() ? null : blocks.getLast().blockHash();
            DevnetBlockBuilder.BlockBuildResult built = builder.buildBlock(number, slot, previous, transactions);
            chain.storeBlockHeader(built.blockHash(), number, slot, built.wrappedHeaderCbor());
            chain.storeBlock(built.blockHash(), number, slot, built.blockCbor());
            blocks.add(new CanonicalBlockReference(number, slot, built.blockHash()));
        }

        synchronized long tip() {
            return blocks.size() - 1;
        }

        ChainBlockReader reader() {
            return new ChainBlockReader() {
                @Override public ChainTip getLocalTip() { return chain.getTip(); }
                @Override public byte[] getBlockByNumber(long blockNumber) {
                    return chain.getBlockByNumber(blockNumber);
                }
                @Override public Era getBlockEra(long blockNumber) { return chain.getBlockEra(blockNumber); }
                @Override public Optional<CanonicalBlockReference> getCanonicalBlockReference(long blockNumber) {
                    return chain.getCanonicalBlockReference(blockNumber);
                }
                @Override public Optional<CanonicalBlockReference> getCanonicalBlockReferenceAtSlot(long slot) {
                    return chain.getCanonicalBlockReferenceAtSlot(slot);
                }
                @Override public OptionalLong canonicalMutationSequence() {
                    return chain.canonicalMutationSequence();
                }
                @Override public OptionalLong getEarliestRetainedBodyBlockNumber() { return OptionalLong.of(0); }
                @Override public OptionalLong getEarliestIndexedSlot() { return OptionalLong.empty(); }
            };
        }
    }

    /** Delivers every event synchronously to its subscribers. */
    private static final class DirectEventBus implements EventBus {
        private final Map<Class<?>, CopyOnWriteArrayList<EventListener<?>>> subscribers = new ConcurrentHashMap<>();

        @Override
        public <E extends Event> SubscriptionHandle subscribe(Class<E> eventType, EventListener<E> listener,
                                                              SubscriptionOptions options) {
            CopyOnWriteArrayList<EventListener<?>> list =
                    subscribers.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>());
            list.add(listener);
            return new SubscriptionHandle() {
                @Override public void close() { list.remove(listener); }
                @Override public boolean isActive() { return list.contains(listener); }
            };
        }

        @Override
        @SuppressWarnings("unchecked")
        public <E extends Event> void publish(E event, EventMetadata metadata, PublishOptions options) {
            for (EventListener<?> raw : subscribers.getOrDefault(event.getClass(), new CopyOnWriteArrayList<>())) {
                try {
                    ((EventListener<E>) raw).onEvent(new EventContext<>() {
                        @Override public E event() { return event; }
                        @Override public EventMetadata metadata() { return metadata; }
                    });
                } catch (RuntimeException | Error failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
        }

        @Override
        public void close() {
            subscribers.clear();
        }
    }

    private static Map<?, ?> handoff(AppChainSubsystem node) {
        Object value = node.status().get("membershipHandoff");
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static long pending(AppChainSubsystem node) {
        return ((Number) handoff(node).get("pendingCommittedHeight")).longValue();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static byte[] fill(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final UtxoState EMPTY_UTXOS = new UtxoState() {
        @Override
        public List<Utxo> getUtxosByAddress(String address, int page, int pageSize) {
            return List.of();
        }

        @Override
        public List<Utxo> getUtxosByPaymentCredential(String credential, int page, int pageSize) {
            return List.of();
        }

        @Override
        public Optional<Utxo> getUtxo(Outpoint outpoint) {
            return Optional.empty();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    };
}
