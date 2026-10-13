package org.yanoproject.x.stdlib;

import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.x.dpp.profile.DppGenesis;
import org.yanoproject.x.dpp.profile.DppStarterProfile;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapSchema;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapSchema.MapField;
import org.yanoproject.x.trust.profile.TrustRegistryGenesis;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ADR-031.4 §6 recipes as checked-in YAML (examples/bindings): deterministic demo genesis documents built from
 * the DPP demo trust registry and the product fixtures' members, and the rules exactly as the ADR writes them.
 * Printed by {@code :state-machines:stdlib:printBindingProductExamples}; never use demo keys in production.
 */
public final class TypedViewsRecipes {
    /** Recipe names; each is {@code examples/bindings/<name>.yaml}. */
    public static final List<String> NAMES = List.of("asset-governed-limits", "dpp-namespace-isolation",
            "feed-slot-rules");
    private static final AuthenticatedMapSchema.Node UINT = AuthenticatedMapSchema.IntegerNode.uint();
    // ADR-031.4 §6 write-view clauses, verbatim; the Studio YAML profile keeps each expression on one line.
    private static final String OWNS_PRODUCT = "writes.all(w, (w.collection != \"product-versions\""
            + " && w.collection != \"events\") || (w.coverage == \"direct\""
            + " && startsWith(w.keyText, w.actorOrganizationId + \"/\")))";
    private static final String LIFECYCLE_ROLE = "writes.all(w, (w.op != \"REVOKE\" && w.op != \"RESTORE\")"
            + " || (w.coverage == \"direct\" && params.role in w.actorRoles))";
    private static final String OWN_SLOT = "writes.all(w, w.collection != \"observations\""
            + " || (w.op == \"PUT_IF_ABSENT\" && w.coverage == \"direct\""
            + " && startsWith(w.keyText, w.actorId + \"/\")))";
    private static final String IN_RANGE = "writes.all(w, w.collection != \"observations\""
            + " || (w.value.price >= reads.feed.value.min && w.value.price <= reads.feed.value.max))";

    private TypedViewsRecipes() { }

    /** The map genesis of a recipe; the passport recipe reuses the DPP demo registry. */
    static AuthenticatedMapContract.Genesis genesis(String recipe) {
        return switch (recipe) {
            case "asset-governed-limits" -> genesis("binding-asset-demo", List.of(
                    collection("holders", AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                            DppStarterProfile.MANUFACTURER_POLICY, "holder-v1"),
                    collection("settings", AuthenticatedMapContract.AUTH_APPROVAL,
                            DppStarterProfile.CERTIFICATION_POLICY, "setting-v1")), List.of(
                    schema("holder-v1", new MapField("acquiredHeight", true, UINT),
                            new MapField("maxTransfer", true, UINT)),
                    schema("setting-v1", new MapField("max", true, UINT))), List.of(
                    holder(0, 500, 0), holder(1, 5_000, 1_000_000),
                    new AuthenticatedMapContract.GenesisEntry("settings", bytes("transfer"), new byte[0],
                            value(Map.of("max", new UnsignedInteger(1_000))))));
            case "dpp-namespace-isolation" -> BindingProductFixtures.fixture(true).genesis();
            case "feed-slot-rules" -> genesis("binding-feed-rules-demo", List.of(
                    collection("feeds", AuthenticatedMapContract.AUTH_APPROVAL,
                            DppStarterProfile.CERTIFICATION_POLICY, "feed-v1"),
                    collection("observations", AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                            DppStarterProfile.OPERATOR_POLICY, "observation-v1")), List.of(
                    schema("feed-v1", new MapField("max", true, UINT), new MapField("min", true, UINT),
                            new MapField("status", true, AuthenticatedMapSchema.TextNode.any())),
                    schema("observation-v1", new MapField("price", true, UINT))), List.of(
                    new AuthenticatedMapContract.GenesisEntry("feeds", bytes("main"), new byte[0], value(Map.of(
                            "status", new UnicodeString("OPEN"), "min", new UnsignedInteger(100),
                            "max", new UnsignedInteger(200))))));
            default -> throw new IllegalArgumentException("unknown recipe " + recipe);
        };
    }

    /** The recipe document: the governed components, the rules of ADR-031.4 §6, and the approval binding. */
    public static String yaml(String recipe) {
        var genesis = genesis(recipe);
        String roleGenesis = hex(genesis.governedGenesis().encode());
        StringBuilder text = new StringBuilder();
        text.append(switch (recipe) {
            case "asset-governed-limits" -> """
                    # ADR-031.4 §6.1: a tokenized asset. Transfers read a governed limit (settings/transfer, changed by
                    # an approved proposal, not a profile epoch) and the sender's holder record (tier and lock-up).
                    # Holders: the first member (tier 500, acquired at 0) and the second (tier 5000, acquired at
                    # 1,000,000); the third member is not a holder. The lock-up is 2 blocks here (the ADR's example
                    # writes 1000) so that the demo's first holder is past it within a few blocks.
                    """;
            case "dpp-namespace-isolation" -> """
                    # ADR-031.4 §6.2: product-passport namespace isolation on the DPP demo registry. A direct write to
                    # product-versions or events must be keyed under the covering actor's organization, and revoking
                    # or restoring needs the manufacturer role. Both rules read verified write coverage, so they run
                    # after the map verified the actors' signatures.
                    """;
            case "feed-slot-rules" -> """
                    # ADR-031.4 §6.3: a data feed. An observation must be the source's own insert-only slot
                    # (<actorId>/<round>, PUT_IF_ABSENT, verified coverage), and its price must lie within the range
                    # of the open feed record feeds/main, which an approved proposal may change.
                    """;
            default -> throw new IllegalArgumentException(recipe);
        });
        text.append("# Deterministic local-demo fixture; never use demo keys in production.\n");
        text.append("# Chain: ").append(genesis.chainId()).append("; membership.mode=governed; threshold=2\n");
        text.append("# Source-checkout fixture regeneration: :state-machines:stdlib:printBindingProductExamples\n");
        text.append("composite:\n  components:\n");
        component(text, "actors", "domain-actors-component", "actors.v1", Map.of("genesis-cbor-hex", roleGenesis),
                "");
        component(text, "reviews", "governed-role-approvals", "reviews.v1", Map.of("actor-component", "actors",
                "genesis-cbor-hex", roleGenesis), "");
        String registryRules = switch (recipe) {
            case "dpp-namespace-isolation" -> """
                          admission:
                            - rule: manufacturer-owns-product
                            - rule: admin-only-lifecycle-ops
                              params: { role: "manufacturer" }
                    """;
            case "feed-slot-rules" -> """
                          admission:
                            - rule: own-insert-only-slot
                            - rule: feed-open-and-in-range
                    """;
            default -> "";
        };
        component(text, "registry", "authenticated-map-component", "registry.v1", Map.of("actors", "actors",
                "approvals", "reviews", "genesis-cbor-hex", hex(AuthenticatedMapContract.encodeGenesis(genesis))),
                registryRules);
        text.append(switch (recipe) {
            case "asset-governed-limits" -> """
                        - id: token
                          machine: balances
                          topic: token.v1
                          config:
                            minter: ""
                          admission:
                            - rule: governed-transfer-limit
                            - rule: tier-limit
                            - rule: lock-up
                              params: { blocks: 2 }
                      rules:
                        - id: governed-transfer-limit
                          command: transfer
                          deny: TRANSFER_LIMIT_EXCEEDED
                          reads:
                            limits: { component: registry, namespace: settings, key: { literal: "transfer" } }
                          require:
                            - expr: 'command.amount <= reads.limits.value.max'
                        - id: tier-limit
                          command: transfer
                          deny: TIER_LIMIT_EXCEEDED
                          reads:
                            holder: { component: registry, namespace: holders, key: { context: sender } }
                          require:
                            - expr: 'reads.holder.present && reads.holder.status == "ACTIVE"'
                            - expr: 'command.amount <= reads.holder.value.maxTransfer'
                        - id: lock-up
                          command: transfer
                          deny: LOCKED
                          params:
                            blocks: { type: integer }
                          reads:
                            holder: { component: registry, namespace: holders, key: { context: sender } }
                          require:
                            - expr: 'context.height >= reads.holder.value.acquiredHeight + params.blocks'
                    """;
            case "dpp-namespace-isolation" -> """
                      rules:
                        - id: manufacturer-owns-product
                          deny: FOREIGN_PRODUCT
                          require:
                            - expr: '%s'
                        - id: admin-only-lifecycle-ops
                          deny: ADMIN_ROLE_REQUIRED
                          params:
                            role: { type: text }
                          require:
                            - expr: '%s'
                    """.formatted(OWNS_PRODUCT, LIFECYCLE_ROLE);
            case "feed-slot-rules" -> """
                      rules:
                        - id: own-insert-only-slot
                          deny: OBSERVATION_REJECTED
                          require:
                            - expr: '%s'
                        - id: feed-open-and-in-range
                          deny: OBSERVATION_OUT_OF_RANGE
                          reads:
                            feed: { component: registry, namespace: feeds, key: { literal: "main" } }
                          require:
                            - expr: 'reads.feed.present && reads.feed.value.status == "OPEN"'
                            - expr: '%s'
                    """.formatted(OWN_SLOT, IN_RANGE);
            default -> throw new IllegalArgumentException(recipe);
        });
        text.append("""
                  bindings:
                    - id: apply-approved
                      from: { component: reviews, event: role-approvals.proposal-approved.v1 }
                      to:
                        component: registry
                        command: apply-action
                        map:
                          action: { field: action }
                          approvalReference: { field: proposalId }
                """);
        return text.toString();
    }

    private static void component(StringBuilder text, String id, String machine, String topic,
                                  Map<String, String> config, String admission) {
        text.append("    - id: ").append(id).append("\n      machine: ").append(machine)
                .append("\n      topic: ").append(topic).append("\n      config:\n");
        config.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                text.append("        ").append(entry.getKey()).append(": \"").append(entry.getValue()).append("\"\n"));
        text.append(admission);
    }

    private static AuthenticatedMapContract.Genesis genesis(String chain,
                                                            List<AuthenticatedMapContract.CollectionDescriptor> all,
                                                            List<AuthenticatedMapContract.ValidatorDescriptor> schemas,
                                                            List<AuthenticatedMapContract.GenesisEntry> entries) {
        return AuthenticatedMapGenesisFactory.mpf(TrustRegistryGenesis.unsignedConfig(chain,
                        BindingProductFixtures.members(), 2), new byte[32], DppGenesis.MAX_BATCH_ITEMS,
                AppChainConfig.DEFAULT_MAX_MESSAGE_BYTES, all, schemas, entries,
                DppGenesis.governedGenesis(DppGenesis.demo(chain)));
    }

    private static AuthenticatedMapContract.CollectionDescriptor collection(String id, int authorization,
                                                                           String policy, String schema) {
        return new AuthenticatedMapContract.CollectionDescriptor(id, authorization, policy, false, 64, 1024,
                AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, schema);
    }

    private static AuthenticatedMapContract.ValidatorDescriptor schema(String id, MapField... members) {
        return AuthenticatedMapContract.ValidatorDescriptor.schema(id, AuthenticatedMapSchema.of(
                new AuthenticatedMapSchema.MapNode(List.of(members))).definition());
    }

    /** A holder record keyed by the public key of member seed {@code index}. */
    private static AuthenticatedMapContract.GenesisEntry holder(int index, long maxTransfer, long acquired) {
        return new AuthenticatedMapContract.GenesisEntry("holders", KeyGenUtil.getPublicKeyFromPrivateKey(
                BindingProductFixtures.memberSeeds().get(index)), new byte[0], value(Map.of(
                "maxTransfer", new UnsignedInteger(maxTransfer), "acquiredHeight", new UnsignedInteger(acquired))));
    }

    static byte[] value(Map<String, DataItem> members) {
        var map = new co.nstant.in.cbor.model.Map();
        new LinkedHashMap<>(members).forEach((name, item) -> map.put(new UnicodeString(name), item));
        return CborSerializationUtil.serialize(map);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
