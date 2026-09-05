package com.bloxbean.cardano.yano.appchain.trust.profile;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyProofV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorAuthorityV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GenesisActorV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedAuthorizationLimitsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedGenesisV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.stdlib.AuthenticatedMapGenesisFactory;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a registry genesis from a JSON descriptor with the stock
 * {@code AuthenticatedMapGenesisFactory}, and prints the four properties a node needs
 * (ADR-049 §2.1). The same descriptor, members, and threshold always yield the same
 * genesis id; the descriptor carries public keys and key proofs only, so seeds stay with
 * their owners.
 */
public final class TrustRegistryGenesis {
    public static final int DESCRIPTOR_VERSION = 1;
    public static final String DEFAULT_CHAIN_ID = "trust-registry-chain";
    public static final long BLOCK_INTERVAL_MS = 1_000;
    public static final int MAX_BATCH_ITEMS = 32;
    public static final String DEMO_FRAMEWORK = "yano-demo-framework-v1";
    public static final List<String> DEMO_ISSUER_AUTHORIZATIONS =
            List.of("issue:credential", "revoke:credential");
    public static final List<String> DEMO_ACTOR_IDS = List.of(
            "registry-admin-a", "registrar-a", "registrar-b", "issuer-a");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final int MAX_DESCRIPTOR_BYTES = 1_048_576;
    private static final int MAX_ORGANIZATIONS = 64;
    private static final int MAX_ACTORS = 256;
    private static final int MAX_INITIAL_ENTRIES = 256;

    private TrustRegistryGenesis() {
    }

    public record Organization(String id) {
        public Organization {
            id = identifier(id, "organization id");
        }
    }

    public record Actor(String id, String organizationId, List<String> roles, String keyId,
                        String publicKeyHex, String keyProofHex) {
        public Actor {
            id = identifier(id, "actor id");
            organizationId = identifier(organizationId, "actor organization id");
            roles = List.copyOf(Objects.requireNonNull(roles, "roles"));
            keyId = identifier(keyId, "key id");
            publicKeyHex = hex(publicKeyHex, 32, "publicKeyHex");
            keyProofHex = Objects.requireNonNull(keyProofHex, "keyProofHex");
            if (roles.isEmpty()) throw new IllegalArgumentException("actor needs a role");
        }
    }

    public record Authority(String id, List<String> administratorActorIds,
                            int distinctActorThreshold, long maximumLifetimeBlocks) {
        public Authority {
            id = identifier(id, "authority id");
            administratorActorIds = List.copyOf(
                    Objects.requireNonNull(administratorActorIds, "administratorActorIds"));
        }
    }

    public record InitialIssuer(String entityId, TrustRegistryValues.IssuerValue value) {
        public InitialIssuer {
            entityId = TrustRegistryProfile.requireIdentifier(entityId, "issuer entity id");
            Objects.requireNonNull(value, "value");
        }
    }

    public record InitialSchema(String id, byte[] bytes) {
        public InitialSchema {
            TrustRegistryProfile.schemaKey(id);
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            if (bytes.length == 0 || bytes.length > TrustRegistryProfile.MAX_SCHEMA_VALUE_BYTES) {
                throw new IllegalArgumentException("schema bytes are outside the collection bound");
            }
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public record Descriptor(String chainId, List<Organization> organizations, List<Actor> actors,
                             Authority authority, List<InitialIssuer> issuers,
                             List<InitialSchema> schemas) {
        public Descriptor {
            chainId = Objects.requireNonNull(chainId, "chainId");
            organizations = List.copyOf(Objects.requireNonNull(organizations, "organizations"));
            actors = List.copyOf(Objects.requireNonNull(actors, "actors"));
            Objects.requireNonNull(authority, "authority");
            issuers = List.copyOf(Objects.requireNonNull(issuers, "issuers"));
            schemas = List.copyOf(Objects.requireNonNull(schemas, "schemas"));
            if (organizations.isEmpty() || organizations.size() > MAX_ORGANIZATIONS
                    || actors.isEmpty() || actors.size() > MAX_ACTORS
                    || issuers.size() + schemas.size() > MAX_INITIAL_ENTRIES) {
                throw new IllegalArgumentException("descriptor cardinalities are outside bounds");
            }
            Set<String> organizationIds = new LinkedHashSet<>();
            for (Organization organization : organizations) {
                if (!organizationIds.add(organization.id())) {
                    throw new IllegalArgumentException("duplicate organization " + organization.id());
                }
            }
            Set<String> actorIds = new LinkedHashSet<>();
            for (Actor actor : actors) {
                if (!actorIds.add(actor.id())) {
                    throw new IllegalArgumentException("duplicate actor " + actor.id());
                }
                if (!organizationIds.contains(actor.organizationId())) {
                    throw new IllegalArgumentException(
                            "actor " + actor.id() + " names an unknown organization");
                }
            }
            for (String administrator : authority.administratorActorIds()) {
                if (!actorIds.contains(administrator)) {
                    throw new IllegalArgumentException(
                            "authority names unknown actor " + administrator);
                }
            }
        }
    }

    public record ActorKey(String keyId, String publicKeyHex, String keyProofHex) {
    }

    // ------------------------------------------------------------------ descriptor JSON

    public static Descriptor parse(String json) {
        Objects.requireNonNull(json, "json");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_DESCRIPTOR_BYTES) {
            throw new IllegalArgumentException("descriptor exceeds 1 MiB");
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("descriptor is not well-formed JSON", malformed);
        }
        if (root == null || !root.isObject()
                || root.path("schemaVersion").asInt(-1) != DESCRIPTOR_VERSION) {
            throw new IllegalArgumentException(
                    "descriptor must be an object with schemaVersion " + DESCRIPTOR_VERSION);
        }
        List<Organization> organizations = new ArrayList<>();
        for (JsonNode node : array(root, "organizations")) {
            organizations.add(new Organization(text(node, "id")));
        }
        List<Actor> actors = new ArrayList<>();
        for (JsonNode node : array(root, "actors")) {
            List<String> roles = new ArrayList<>();
            for (JsonNode role : array(node, "roles")) {
                if (!role.isTextual()) throw new IllegalArgumentException("roles must be strings");
                roles.add(role.textValue());
            }
            actors.add(new Actor(text(node, "id"), text(node, "organizationId"), roles,
                    text(node, "keyId"), text(node, "publicKeyHex"), text(node, "keyProofHex")));
        }
        JsonNode authorityNode = root.path("authority");
        if (!authorityNode.isObject()) {
            throw new IllegalArgumentException("descriptor authority is required");
        }
        List<String> administrators = new ArrayList<>();
        for (JsonNode node : array(authorityNode, "administratorActorIds")) {
            if (!node.isTextual()) {
                throw new IllegalArgumentException("administratorActorIds must be strings");
            }
            administrators.add(node.textValue());
        }
        Authority authority = new Authority(text(authorityNode, "id"), administrators,
                authorityNode.path("distinctActorThreshold").asInt(1),
                authorityNode.path("maximumLifetimeBlocks").asLong(
                        TrustRegistryProfile.ADMINISTRATOR_LIFETIME_BLOCKS));
        List<InitialIssuer> issuers = new ArrayList<>();
        for (JsonNode node : optionalArray(root, "issuers")) {
            List<String> authorizations = new ArrayList<>();
            for (JsonNode item : array(node, "authorizations")) {
                if (!item.isTextual()) {
                    throw new IllegalArgumentException("authorizations must be strings");
                }
                authorizations.add(item.textValue());
            }
            issuers.add(new InitialIssuer(text(node, "entityId"),
                    new TrustRegistryValues.IssuerValue(text(node, "framework"), authorizations,
                            node.path("validFromHeight").asLong(0),
                            node.path("validUntilHeight").asLong(0))));
        }
        List<InitialSchema> schemas = new ArrayList<>();
        for (JsonNode node : optionalArray(root, "schemas")) {
            schemas.add(new InitialSchema(text(node, "id"),
                    parseHex(text(node, "bytesHex"), "schema bytesHex")));
        }
        return new Descriptor(text(root, "chainId"), organizations, actors, authority,
                issuers, schemas);
    }

    public static String toJson(Descriptor descriptor) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", DESCRIPTOR_VERSION);
        root.put("chainId", descriptor.chainId());
        ArrayNode organizations = root.putArray("organizations");
        descriptor.organizations().forEach(organization ->
                organizations.addObject().put("id", organization.id()));
        ArrayNode actors = root.putArray("actors");
        for (Actor actor : descriptor.actors()) {
            ObjectNode node = actors.addObject();
            node.put("id", actor.id());
            node.put("organizationId", actor.organizationId());
            ArrayNode roles = node.putArray("roles");
            actor.roles().forEach(roles::add);
            node.put("keyId", actor.keyId());
            node.put("publicKeyHex", actor.publicKeyHex());
            node.put("keyProofHex", actor.keyProofHex());
        }
        ObjectNode authority = root.putObject("authority");
        authority.put("id", descriptor.authority().id());
        ArrayNode administrators = authority.putArray("administratorActorIds");
        descriptor.authority().administratorActorIds().forEach(administrators::add);
        authority.put("distinctActorThreshold", descriptor.authority().distinctActorThreshold());
        authority.put("maximumLifetimeBlocks", descriptor.authority().maximumLifetimeBlocks());
        ArrayNode issuers = root.putArray("issuers");
        for (InitialIssuer issuer : descriptor.issuers()) {
            ObjectNode node = issuers.addObject();
            node.put("entityId", issuer.entityId());
            node.put("framework", issuer.value().framework());
            ArrayNode authorizations = node.putArray("authorizations");
            issuer.value().authorizations().forEach(authorizations::add);
            node.put("validFromHeight", issuer.value().validFromHeight());
            node.put("validUntilHeight", issuer.value().validUntilHeight());
        }
        ArrayNode schemas = root.putArray("schemas");
        for (InitialSchema schema : descriptor.schemas()) {
            ObjectNode node = schemas.addObject();
            node.put("id", schema.id());
            node.put("bytesHex", HEX.formatHex(schema.bytes()));
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ------------------------------------------------------------------ keys and demo

    /** Public key and genesis key proof for an actor whose seed the operator holds. */
    public static ActorKey actorKey(String chainId, String actorId, String keyId, byte[] seed) {
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        ActorKeyEpochV1 key = new ActorKeyEpochV1(keyId, publicKey, 1, 0, RecordStatus.ACTIVE);
        ActorKeyProofV1 proof = ActorKeyProofV1.sign(chainId, actorId, 1, key, seed);
        return new ActorKey(keyId, HEX.formatHex(publicKey), HEX.formatHex(proof.encode()));
    }

    /**
     * Demo-only deterministic Ed25519 seed, {@code sha256("yano-trust-registry-demo-actor:" + id)}.
     * Never reuse outside a local demo.
     */
    public static byte[] demoActorSeed(String actorId) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    ("yano-trust-registry-demo-actor:" + actorId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    /** The demo registry: one operator, an independent registrar guild, one seeded issuer. */
    public static Descriptor demo(String chainId) {
        List<Organization> organizations = List.of(
                new Organization("registry-operator"),
                new Organization("registrar-guild-b"),
                new Organization("issuer-org-a"));
        List<Actor> actors = List.of(
                demoActor(chainId, "registry-admin-a", "registry-operator",
                        List.of(TrustRegistryProfile.ADMIN_ROLE)),
                demoActor(chainId, "registrar-a", "registry-operator",
                        List.of(TrustRegistryProfile.REGISTRAR_ROLE)),
                demoActor(chainId, "registrar-b", "registrar-guild-b",
                        List.of(TrustRegistryProfile.REGISTRAR_ROLE)),
                demoActor(chainId, "issuer-a", "issuer-org-a",
                        List.of(TrustRegistryProfile.ISSUER_ROLE)));
        Authority authority = new Authority(TrustRegistryProfile.AUTHORITY_ID,
                List.of("registry-admin-a"), 1, TrustRegistryProfile.ADMINISTRATOR_LIFETIME_BLOCKS);
        List<InitialIssuer> issuers = List.of(new InitialIssuer("issuer-a",
                new TrustRegistryValues.IssuerValue(
                        DEMO_FRAMEWORK, DEMO_ISSUER_AUTHORIZATIONS, 1, 0)));
        return new Descriptor(chainId, organizations, actors, authority, issuers, List.of());
    }

    private static Actor demoActor(String chainId, String actorId, String organizationId,
                                   List<String> roles) {
        ActorKey key = actorKey(chainId, actorId, actorId + "-k1", demoActorSeed(actorId));
        return new Actor(actorId, organizationId, roles, key.keyId(), key.publicKeyHex(),
                key.keyProofHex());
    }

    // ------------------------------------------------------------------ genesis

    /** The unsigned chain configuration the genesis is derived from; nodes must use the same values. */
    public static AppChainConfig unsignedConfig(String chainId, List<String> members, int threshold) {
        List<String> normalized = normalizedMembers(members);
        if (threshold < 1 || threshold > normalized.size()) {
            throw new IllegalArgumentException(
                    "threshold must be between 1 and the member count");
        }
        return AppChainConfig.builder(chainId)
                .signingKeyHex("00".repeat(32))
                .memberKeysHex(new LinkedHashSet<>(normalized))
                .proposerKeyHex(normalized.getFirst())
                .threshold(threshold)
                .blockIntervalMs(BLOCK_INTERVAL_MS)
                .stateMachineId(AuthenticatedMapContract.STATE_MACHINE_ID)
                .pluginSettings(Map.of("membership.mode", "governed"))
                .build();
    }

    public static AuthenticatedMapContract.Genesis genesis(Descriptor descriptor,
                                                          List<String> members, int threshold) {
        AppChainConfig config = unsignedConfig(descriptor.chainId(), members, threshold);
        return AuthenticatedMapGenesisFactory.mpf(
                config, new byte[32], MAX_BATCH_ITEMS, AppChainConfig.DEFAULT_MAX_MESSAGE_BYTES,
                TrustRegistryProfile.collections(), TrustRegistryProfile.validators(),
                initialEntries(descriptor), governedGenesis(descriptor));
    }

    /** The four node properties, unprefixed and sorted. */
    public static Map<String, String> settings(AuthenticatedMapContract.Genesis genesis) {
        return Collections.unmodifiableMap(
                new TreeMap<>(AuthenticatedMapGenesisFactory.settings(genesis)));
    }

    /** The node properties prefixed with {@code yano.app-chain.chains[index].}, one per line. */
    public static List<String> properties(AuthenticatedMapContract.Genesis genesis, int chainIndex) {
        if (chainIndex < 0 || chainIndex > 255) {
            throw new IllegalArgumentException("chain index must be 0-255");
        }
        String prefix = "yano.app-chain.chains[" + chainIndex + "].";
        List<String> lines = new ArrayList<>();
        settings(genesis).forEach((key, value) -> lines.add(prefix + key + "=" + value));
        return List.copyOf(lines);
    }

    public static GovernedGenesisV1 governedGenesis(Descriptor descriptor) {
        List<OrganizationRecordV1> organizations = new ArrayList<>();
        for (Organization organization : descriptor.organizations()) {
            organizations.add(new OrganizationRecordV1(
                    organization.id(), 1, RecordStatus.ACTIVE, new byte[0]));
        }
        List<GenesisActorV1> actors = new ArrayList<>();
        for (Actor actor : descriptor.actors()) {
            ActorKeyEpochV1 key = new ActorKeyEpochV1(actor.keyId(),
                    HEX.parseHex(actor.publicKeyHex()), 1, 0, RecordStatus.ACTIVE);
            ActorKeyProofV1 proof = ActorKeyProofV1.decode(parseHex(actor.keyProofHex(),
                    "keyProofHex"));
            if (!proof.chainId().equals(descriptor.chainId())
                    || !proof.actorId().equals(actor.id())
                    || proof.actorRevision() != 1
                    || !proof.key().keyId().equals(key.keyId())
                    || !java.util.Arrays.equals(proof.key().publicKey(), key.publicKey())
                    || proof.key().validFromHeight() != key.validFromHeight()
                    || proof.key().validUntilHeight() != key.validUntilHeight()
                    || proof.key().status() != key.status()
                    || !proof.verify()) {
                throw new IllegalArgumentException(
                        "key proof of actor " + actor.id() + " does not bind its key to this chain");
            }
            ActorRecordV1 record = new ActorRecordV1(actor.id(), actor.organizationId(), 1,
                    RecordStatus.ACTIVE, actor.roles(), List.of(key), new byte[0]);
            actors.add(new GenesisActorV1(record, List.of(proof)));
        }
        AdministratorAuthorityV1 authority = new AdministratorAuthorityV1(
                descriptor.authority().id(), 1, descriptor.authority().administratorActorIds(),
                descriptor.authority().distinctActorThreshold(),
                descriptor.authority().maximumLifetimeBlocks());
        return new GovernedGenesisV1(descriptor.chainId(), authority, organizations, actors,
                TrustRegistryProfile.directPolicies(),
                List.of(TrustRegistryProfile.onboardingPolicy()),
                GovernedAuthorizationLimitsV1.defaults());
    }

    static List<AuthenticatedMapContract.GenesisEntry> initialEntries(Descriptor descriptor) {
        List<AuthenticatedMapContract.GenesisEntry> entries = new ArrayList<>();
        for (InitialIssuer issuer : descriptor.issuers()) {
            entries.add(new AuthenticatedMapContract.GenesisEntry(TrustRegistryProfile.ISSUERS,
                    TrustRegistryProfile.issuerKey(issuer.entityId()), new byte[0],
                    issuer.value().encode()));
        }
        for (InitialSchema schema : descriptor.schemas()) {
            entries.add(new AuthenticatedMapContract.GenesisEntry(TrustRegistryProfile.SCHEMAS,
                    TrustRegistryProfile.schemaKey(schema.id()), new byte[0], schema.bytes()));
        }
        return entries;
    }

    public static List<String> normalizedMembers(List<String> members) {
        if (members == null || members.isEmpty() || members.size() > AppChainConfig.MAX_MEMBERS) {
            throw new IllegalArgumentException("members must contain 1-"
                    + AppChainConfig.MAX_MEMBERS + " keys");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String member : members) {
            if (member == null || !member.matches("[0-9a-f]{64}") || !unique.add(member)) {
                throw new IllegalArgumentException(
                        "members must be unique lowercase 32-byte hex keys");
            }
        }
        return List.copyOf(unique);
    }

    private static Iterable<JsonNode> array(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isArray()) {
            throw new IllegalArgumentException("descriptor field " + field + " must be an array");
        }
        return node;
    }

    private static Iterable<JsonNode> optionalArray(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (node.isMissingNode() || node.isNull()) return List.of();
        return array(parent, field);
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException("descriptor field " + field + " must be text");
        }
        return node.textValue();
    }

    private static String identifier(String value, String name) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " must match [a-z][a-z0-9-]{0,62}");
        }
        return value;
    }

    private static String hex(String value, int bytes, String name) {
        if (value == null || !value.matches("[0-9a-f]{" + (bytes * 2) + "}")) {
            throw new IllegalArgumentException(name + " must be " + bytes + " bytes of lowercase hex");
        }
        return value;
    }

    private static byte[] parseHex(String value, String name) {
        if (value == null || value.isEmpty() || !value.matches("(?:[0-9a-f]{2})+")) {
            throw new IllegalArgumentException(name + " must be lowercase hex");
        }
        return HEX.parseHex(value);
    }
}
