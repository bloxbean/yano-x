package org.yanoproject.x.devtools;

import org.yanoproject.api.appchain.AppChainMembershipEpoch;
import org.yanoproject.appchain.config.AppChainEffectsConfig;
import org.yanoproject.x.dpp.profile.DppGenesis;
import org.yanoproject.x.feed.profile.FeedGenesis;
import org.yanoproject.x.roles.contracts.ApprovalPolicyV1;
import org.yanoproject.x.roles.contracts.GenesisActorV1;
import org.yanoproject.x.roles.contracts.GovernedGenesisV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.trust.profile.TrustRegistryGenesis;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptable governed approval-to-map starter construction using public product profile libraries.
 * No demo seeds, signing material, runtime provider construction, or trusted-state claims are introduced.
 * Actors supply their chain-bound proof-of-possession bytes; the public genesis builder verifies them.
 */
final class BindingRecipe {
    private BindingRecipe() { }

    /** Closed JSON input, deliberately excluding initial entries unsupported by these two starter profiles. */
    record Descriptor(int schemaVersion, String chainId, List<TrustRegistryGenesis.Organization> organizations,
                      List<TrustRegistryGenesis.Actor> actors, TrustRegistryGenesis.Authority authority,
                      List<Object> issuers, List<Object> schemas) {
        Descriptor {
            if (chainId == null || chainId.isBlank()) throw new IllegalArgumentException("$.chainId: required");
            if (organizations == null) throw new IllegalArgumentException("$.organizations: required");
            if (actors == null) throw new IllegalArgumentException("$.actors: required");
            if (authority == null) throw new IllegalArgumentException("$.authority: required");
            organizations = List.copyOf(organizations);
            actors = List.copyOf(actors);
        }
        TrustRegistryGenesis.Descriptor validated() {
            if (schemaVersion != 1) throw new IllegalArgumentException("$.schemaVersion: expected 1");
            if (issuers != null && !issuers.isEmpty() || schemas != null && !schemas.isEmpty()) {
                throw new IllegalArgumentException("$: starter descriptor must have no initial issuers or schemas");
            }
            return new TrustRegistryGenesis.Descriptor(chainId, organizations, actors, authority, List.of(), List.of());
        }
    }

    /** JSON/YAML-compatible document and explicit offline context, requiring subsequent catalog validation. */
    record Output(String assurance, String recipe, Map<String, Object> document,
                  BindingCatalogSession.ContextInput context, List<String> members, int threshold,
                  String proposer, long blockIntervalMs) { }

    static Output generate(String recipe, Descriptor input, List<String> members, int threshold) {
        var descriptor = input.validated();
        var normalized = TrustRegistryGenesis.normalizedMembers(members);
        var config = TrustRegistryGenesis.unsignedConfig(descriptor.chainId(), normalized, threshold);
        AuthenticatedMapContract.Genesis genesis = switch (recipe) {
            case "dpp" -> DppGenesis.genesis(descriptor, normalized, threshold);
            case "feed" -> FeedGenesis.genesis(descriptor, normalized, threshold);
            default -> throw new IllegalArgumentException("recipe must be dpp or feed");
        };
        requireStarterUsableAtGenesis(genesis.governedGenesis());
        String governed = HexFormat.of().formatHex(genesis.governedGenesis().encode());
        String map = HexFormat.of().formatHex(AuthenticatedMapContract.encodeGenesis(genesis));
        var components = List.of(
                component("actors", "domain-actors-component", Map.of("genesis-cbor-hex", governed)),
                component("reviews", "governed-role-approvals",
                        Map.of("actor-component", "actors", "genesis-cbor-hex", governed)),
                component("registry", "authenticated-map-component", Map.of("actors", "actors",
                        "approvals", "reviews", "genesis-cbor-hex", map)));
        var binding = Map.of("id", "apply-approved", "from", Map.of("component", "reviews",
                        "event", "role-approvals.proposal-approved.v1"),
                "to", Map.of("component", "registry", "command", "apply-action", "map",
                        Map.of("action", Map.of("field", "action"),
                                "approvalReference", Map.of("field", "proposalId"))));
        Map<String, String> settings = new LinkedHashMap<>();
        TrustRegistryGenesis.settings(genesis).forEach((key, value) -> {
            if (key.startsWith("state.")) settings.put(key, value);
        });
        settings.put("membership.mode", "governed");
        var context = new BindingCatalogSession.ContextInput(descriptor.chainId(), settings,
                AppChainEffectsConfig.from(config).consensusProfile(config),
                new AppChainMembershipEpoch(0, normalized, threshold));
        return new Output("generated from supplied public identities and verified key proofs; "
                + "validate against your exact plugin catalog before deployment", recipe,
                Map.of("composite", Map.of("components", components, "bindings", List.of(binding))), context,
                normalized, threshold, normalized.getFirst(), TrustRegistryGenesis.BLOCK_INTERVAL_MS);
    }

    private static Map<String, Object> component(String id, String machine, Map<String, String> config) {
        return Map.of("id", id, "machine", machine, "topic", id + ".v1", "config", config);
    }

    /**
     * Checks only the fixed starters at height 1, after public key-proof verification by the genesis builder.
     * Active actors need an active organization and signing key. Proposer roles are alternatives; a proposer
     * may also vote. Each starter approval policy has exactly one clause, so distinct eligible organizations
     * (or actors) suffice. This is not a general multi-clause allocation or future-state satisfiability checker.
     */
    static void requireStarterUsableAtGenesis(GovernedGenesisV1 genesis) {
        var activeOrganizations = genesis.organizations().stream()
                .filter(organization -> organization.status() == RecordStatus.ACTIVE)
                .map(organization -> organization.organizationId()).toList();
        var eligible = genesis.actors().stream().map(GenesisActorV1::actor)
                .filter(actor -> actor.status() == RecordStatus.ACTIVE
                        && activeOrganizations.contains(actor.organizationId())
                        && actor.keys().stream().anyMatch(key -> key.activeAt(1))).toList();
        for (var policy : genesis.directPolicies()) {
            if (policy.status() != RecordStatus.ACTIVE
                    || eligible.stream().noneMatch(actor -> actor.roles().contains(policy.requiredRole()))) {
                throw new IllegalArgumentException("$.actors: starter policy '" + policy.policyId()
                        + "' needs an eligible genesis actor with role '" + policy.requiredRole() + "'");
            }
        }
        for (var policy : genesis.approvalPolicies()) {
            if (policy.clauses().size() != 1) {
                throw new IllegalArgumentException("starter usability check requires a single approval clause");
            }
            if (policy.status() != RecordStatus.ACTIVE || eligible.stream().noneMatch(actor ->
                    policy.proposerRoles().isEmpty() || policy.proposerRoles().stream().anyMatch(actor.roles()::contains))) {
                throw new IllegalArgumentException("$.actors: starter policy '" + policy.policyId()
                        + "' needs an eligible genesis proposer with one of roles " + policy.proposerRoles());
            }
            var clause = policy.clauses().getFirst();
            long count = eligible.stream().filter(actor -> actor.roles().contains(clause.role()))
                    .map(actor -> clause.distinctBy() == ApprovalPolicyV1.DistinctBy.ORGANIZATION
                            ? actor.organizationId() : actor.actorId()).distinct().count();
            if (count < clause.minimumCount()) {
                throw new IllegalArgumentException("$.actors: starter policy '" + policy.policyId()
                        + "' clause '" + clause.clauseId() + "' needs " + clause.minimumCount()
                        + " eligible genesis voters with role '" + clause.role() + "', distinct by "
                        + clause.distinctBy());
            }
        }
    }
}
