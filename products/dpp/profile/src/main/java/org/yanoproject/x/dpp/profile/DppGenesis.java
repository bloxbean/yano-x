package org.yanoproject.x.dpp.profile;

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
 * Builds a DPP starter genesis with the stock {@code AuthenticatedMapGenesisFactory} from the
 * ADR-049 actor descriptor (organizations, actors with roles and key proofs, the authority;
 * its issuers and schemas lists are unused here) under the starter's policies, and prints the
 * four properties a node needs (ADR-051 §2.1). The same descriptor, members, and threshold
 * always yield the same genesis id.
 */
public final class DppGenesis {
    public static final String DEFAULT_CHAIN_ID = "dpp-starter-chain";
    public static final long BLOCK_INTERVAL_MS = TrustRegistryGenesis.BLOCK_INTERVAL_MS;
    public static final int MAX_BATCH_ITEMS = TrustRegistryGenesis.MAX_BATCH_ITEMS;
    public static final String DEMO_SEED_DOMAIN = "yano-dpp-starter-demo-actor:";
    public static final List<String> DEMO_ACTOR_IDS = List.of(
            "dpp-admin-a", "maker-a", "logistics-a", "issuer-a", "certifier-a", "auditor-a",
            "auditor-b");

    private DppGenesis() {
    }

    /**
     * The demo consortium: a manufacturer that also operates, a logistics operator, a claim
     * issuer, a certification body with its own auditor, an independent audit guild, and the
     * consortium administrator.
     */
    public static TrustRegistryGenesis.Descriptor demo(String chainId) {
        List<TrustRegistryGenesis.Organization> organizations = List.of(
                new TrustRegistryGenesis.Organization("dpp-consortium"),
                new TrustRegistryGenesis.Organization("acme-manufacturing"),
                new TrustRegistryGenesis.Organization("swift-logistics"),
                new TrustRegistryGenesis.Organization("green-labs"),
                new TrustRegistryGenesis.Organization("cert-body-a"),
                new TrustRegistryGenesis.Organization("audit-guild-b"));
        List<TrustRegistryGenesis.Actor> actors = List.of(
                demoActor(chainId, "dpp-admin-a", "dpp-consortium",
                        List.of(DppStarterProfile.ADMIN_ROLE)),
                demoActor(chainId, "maker-a", "acme-manufacturing",
                        List.of(DppStarterProfile.MANUFACTURER_ROLE, DppStarterProfile.OPERATOR_ROLE)),
                demoActor(chainId, "logistics-a", "swift-logistics",
                        List.of(DppStarterProfile.OPERATOR_ROLE)),
                demoActor(chainId, "issuer-a", "green-labs",
                        List.of(DppStarterProfile.CLAIM_ISSUER_ROLE)),
                demoActor(chainId, "certifier-a", "cert-body-a",
                        List.of(DppStarterProfile.CERTIFIER_ROLE)),
                demoActor(chainId, "auditor-a", "cert-body-a",
                        List.of(DppStarterProfile.AUDITOR_ROLE)),
                demoActor(chainId, "auditor-b", "audit-guild-b",
                        List.of(DppStarterProfile.AUDITOR_ROLE)));
        TrustRegistryGenesis.Authority authority = new TrustRegistryGenesis.Authority(
                DppStarterProfile.AUTHORITY_ID, List.of("dpp-admin-a"), 1,
                DppStarterProfile.ADMINISTRATOR_LIFETIME_BLOCKS);
        return new TrustRegistryGenesis.Descriptor(chainId, organizations, actors, authority,
                List.of(), List.of());
    }

    /**
     * Demo-only deterministic Ed25519 seed, {@code sha256("yano-dpp-starter-demo-actor:" + id)}.
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
                    "a DPP starter descriptor declares no initial issuers or schemas");
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
                DppStarterProfile.collections(), DppStarterProfile.validators(),
                List.of(), governedGenesis(descriptor));
    }

    public static GovernedGenesisV1 governedGenesis(TrustRegistryGenesis.Descriptor descriptor) {
        return TrustRegistryGenesis.governedGenesis(requireStarterDescriptor(descriptor),
                DppStarterProfile.directPolicies(), List.of(DppStarterProfile.certificationPolicy()));
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
