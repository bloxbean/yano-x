package org.yanoproject.x.feed.profile;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import org.yanoproject.x.roles.contracts.GovernedGenesisV1;
import org.yanoproject.x.stdlib.AuthenticatedMapGenesisFactory;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.trust.profile.TrustRegistryGenesis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * Builds an attestation feed starter genesis with the stock {@code AuthenticatedMapGenesisFactory}
 * from the ADR-049 actor descriptor under the starter's policies, and prints the four properties
 * a node needs (ADR-052 §2.1). The same descriptor, members, and threshold always yield the same
 * genesis id.
 */
public final class FeedGenesis {
    public static final String DEFAULT_CHAIN_ID = "attestation-feed-chain";
    public static final long BLOCK_INTERVAL_MS = TrustRegistryGenesis.BLOCK_INTERVAL_MS;
    public static final int MAX_BATCH_ITEMS = TrustRegistryGenesis.MAX_BATCH_ITEMS;
    public static final String DEMO_SEED_DOMAIN = "yano-attestation-feed-demo-actor:";
    public static final List<String> DEMO_ACTOR_IDS = List.of(
            "feed-admin-a", "source-alpha", "source-beta", "source-gamma", "ops-a",
            "publisher-a", "publisher-c", "publisher-b");
    public static final List<String> DEMO_SOURCE_IDS = List.of("source-alpha", "source-beta", "source-gamma");

    private FeedGenesis() {
    }

    /**
     * The demo consortium: three cold-chain operators each running one source, a feed
     * operations organization with the operator and two publishers (so a same-organization
     * second approval can be shown insufficient), an independent audit guild with a publisher,
     * and the consortium administrator.
     */
    public static TrustRegistryGenesis.Descriptor demo(String chainId) {
        List<TrustRegistryGenesis.Organization> organizations = List.of(
                new TrustRegistryGenesis.Organization("feed-consortium"),
                new TrustRegistryGenesis.Organization("coldchain-alpha"),
                new TrustRegistryGenesis.Organization("coldchain-beta"),
                new TrustRegistryGenesis.Organization("coldchain-gamma"),
                new TrustRegistryGenesis.Organization("feed-ops"),
                new TrustRegistryGenesis.Organization("audit-guild"));
        List<TrustRegistryGenesis.Actor> actors = List.of(
                demoActor(chainId, "feed-admin-a", "feed-consortium", List.of(FeedStarterProfile.FEED_ADMIN_ROLE)),
                demoActor(chainId, "source-alpha", "coldchain-alpha", List.of(FeedStarterProfile.SOURCE_ROLE)),
                demoActor(chainId, "source-beta", "coldchain-beta", List.of(FeedStarterProfile.SOURCE_ROLE)),
                demoActor(chainId, "source-gamma", "coldchain-gamma", List.of(FeedStarterProfile.SOURCE_ROLE)),
                demoActor(chainId, "ops-a", "feed-ops", List.of(FeedStarterProfile.OPERATOR_ROLE)),
                demoActor(chainId, "publisher-a", "feed-ops", List.of(FeedStarterProfile.PUBLISHER_ROLE)),
                demoActor(chainId, "publisher-c", "feed-ops", List.of(FeedStarterProfile.PUBLISHER_ROLE)),
                demoActor(chainId, "publisher-b", "audit-guild", List.of(FeedStarterProfile.PUBLISHER_ROLE)));
        TrustRegistryGenesis.Authority authority = new TrustRegistryGenesis.Authority(
                FeedStarterProfile.AUTHORITY_ID, List.of("feed-admin-a"), 1,
                FeedStarterProfile.ADMINISTRATOR_LIFETIME_BLOCKS);
        return new TrustRegistryGenesis.Descriptor(chainId, organizations, actors, authority,
                List.of(), List.of());
    }

    /**
     * Demo-only deterministic Ed25519 seed, {@code sha256("yano-attestation-feed-demo-actor:" + id)}.
     * Never reuse outside a local demo.
     */
    public static byte[] demoActorSeed(String actorId) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    (DEMO_SEED_DOMAIN + actorId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    /** The demo feed: a cold-store temperature at scale 2 with one-minute rounds. */
    public static FeedValues.FeedValue demoFeed(long epochStart) {
        return new FeedValues.FeedValue("Cold store 7 air temperature", "degC", 2, epochStart, 60,
                DEMO_SOURCE_IDS, 2, 20_000, 50, -4_000, 1_000, FeedStarterProfile.FEED_ACTIVE);
    }

    private static TrustRegistryGenesis.Actor demoActor(String chainId, String actorId,
                                                        String organizationId, List<String> roles) {
        TrustRegistryGenesis.ActorKey key = TrustRegistryGenesis.actorKey(
                chainId, actorId, actorId + "-k1", demoActorSeed(actorId));
        return new TrustRegistryGenesis.Actor(actorId, organizationId, roles, key.keyId(),
                key.publicKeyHex(), key.keyProofHex());
    }

    // ------------------------------------------------------------------ genesis

    /** The descriptor must carry no initial issuers or schemas: those are registry collections. */
    public static TrustRegistryGenesis.Descriptor requireStarterDescriptor(
            TrustRegistryGenesis.Descriptor descriptor) {
        if (!descriptor.issuers().isEmpty() || !descriptor.schemas().isEmpty()) {
            throw new IllegalArgumentException(
                    "an attestation feed descriptor declares no initial issuers or schemas");
        }
        return descriptor;
    }

    public static AuthenticatedMapContract.Genesis genesis(
            TrustRegistryGenesis.Descriptor descriptor, List<String> members, int threshold) {
        requireStarterDescriptor(descriptor);
        AppChainConfig config = TrustRegistryGenesis.unsignedConfig(
                descriptor.chainId(), members, threshold);
        return AuthenticatedMapGenesisFactory.mpf(
                config, new byte[32], MAX_BATCH_ITEMS, AppChainConfig.DEFAULT_MAX_MESSAGE_BYTES,
                FeedStarterProfile.collections(), FeedStarterProfile.validators(),
                List.of(), governedGenesis(descriptor));
    }

    public static GovernedGenesisV1 governedGenesis(TrustRegistryGenesis.Descriptor descriptor) {
        return TrustRegistryGenesis.governedGenesis(requireStarterDescriptor(descriptor),
                FeedStarterProfile.directPolicies(), List.of(FeedStarterProfile.roundPolicy()));
    }

    /** The four node properties, unprefixed and sorted. */
    public static Map<String, String> settings(AuthenticatedMapContract.Genesis genesis) {
        return TrustRegistryGenesis.settings(genesis);
    }

    /** The node properties prefixed with {@code yano.app-chain.chains[index].}, one per line. */
    public static List<String> properties(AuthenticatedMapContract.Genesis genesis, int chainIndex) {
        return TrustRegistryGenesis.properties(genesis, chainIndex);
    }
}
