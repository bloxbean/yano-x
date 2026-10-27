package org.yanoproject.x.showcase;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.x.roles.contracts.ActorKeyEpochV1;
import org.yanoproject.x.roles.contracts.ActorKeyProofV1;
import org.yanoproject.x.roles.contracts.ActorRecordV1;
import org.yanoproject.x.roles.contracts.AdministratorAuthorityV1;
import org.yanoproject.x.roles.contracts.ApprovalPolicyV1;
import org.yanoproject.x.roles.contracts.GenesisActorV1;
import org.yanoproject.x.roles.contracts.GovernedAuthorizationLimitsV1;
import org.yanoproject.x.roles.contracts.GovernedGenesisV1;
import org.yanoproject.x.roles.contracts.OrganizationRecordV1;
import org.yanoproject.x.roles.contracts.RecordStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * The document-review chain's governed genesis: the showcase's demo organizations and actors, whose keys come from
 * public demo seeds. The authenticated-map chains declare the same records in showcase.template.yaml.
 */
final class ShowcaseGovernedGenesis {
    static final String AUTHORITY_ID = "registry-admins";

    private ShowcaseGovernedGenesis() {
    }

    /**
     * Demo-only deterministic Ed25519 seed shared with showcase.sh, which derives the same value via
     * {@code printf 'yano-showcase-demo-actor:%s' <id> | shasum -a 256}. Never reuse outside the packaged showcase.
     */
    static byte[] demoActorSeed(String actorId) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    ("yano-showcase-demo-actor:" + actorId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    static GovernedGenesisV1 documentReviewGenesis(String chainId) {
        OrganizationRecordV1 manufacturer = new OrganizationRecordV1(
                "acme-manufacturing", 1, RecordStatus.ACTIVE, new byte[0]);
        OrganizationRecordV1 guildA = new OrganizationRecordV1(
                "auditor-guild-a", 1, RecordStatus.ACTIVE, new byte[0]);
        OrganizationRecordV1 guildB = new OrganizationRecordV1(
                "auditor-guild-b", 1, RecordStatus.ACTIVE, new byte[0]);
        List<GenesisActorV1> actors = List.of(
                genesisActor(chainId, "registry-admin-a", manufacturer.organizationId(), List.of("registry-admin")),
                genesisActor(chainId, "issuer-a", manufacturer.organizationId(), List.of("issuer")),
                genesisActor(chainId, "auditor-a", guildA.organizationId(), List.of("auditor")),
                genesisActor(chainId, "auditor-b", guildB.organizationId(), List.of("auditor")));
        AdministratorAuthorityV1 authority = new AdministratorAuthorityV1(
                AUTHORITY_ID, 1, List.of("registry-admin-a"), 1, 1_000);
        ApprovalPolicyV1 approvalPolicy = new ApprovalPolicyV1(
                DocumentReviewPreset.POLICY_ID, 1, RecordStatus.ACTIVE, List.of("issuer"),
                List.of(new ApprovalPolicyV1.RequiredClause(
                        "independent-auditors", "auditor", 2, ApprovalPolicyV1.DistinctBy.ORGANIZATION)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 600);
        return new GovernedGenesisV1(
                chainId, authority, List.of(manufacturer, guildA, guildB), actors,
                List.of(), List.of(approvalPolicy), GovernedAuthorizationLimitsV1.defaults());
    }

    private static GenesisActorV1 genesisActor(
            String chainId, String actorId, String organizationId, List<String> roles) {
        byte[] seed = demoActorSeed(actorId);
        ActorKeyEpochV1 key = new ActorKeyEpochV1(
                actorId + "-k1", KeyGenUtil.getPublicKeyFromPrivateKey(seed), 1, 0, RecordStatus.ACTIVE);
        ActorRecordV1 actor = new ActorRecordV1(
                actorId, organizationId, 1, RecordStatus.ACTIVE, roles, List.of(key), new byte[0]);
        return new GenesisActorV1(actor, List.of(ActorKeyProofV1.sign(chainId, actorId, 1, key, seed)));
    }
}
