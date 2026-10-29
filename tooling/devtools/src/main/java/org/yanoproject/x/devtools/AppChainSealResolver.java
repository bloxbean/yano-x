package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.yanoproject.appchain.config.AppChainPropertyRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Resolves the chains of an app-chain setup draft (ADR-056) through the release's recipe and capability
 * catalog, once, at seal. The result is the exact per-chain consensus settings the sealed file carries.
 */
public final class AppChainSealResolver {
    private static final String CHAIN_PREFIX = "yano.app-chain.chains[0].";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private AppChainSealResolver() {
    }

    /** What seal adds to the catalog's resolution. */
    public interface SealHooks {
        /** Consensus settings, relative to the chain prefix, that replace recipe values before defaults apply. */
        Map<String, String> overrides(String chainId);

        /**
         * Chooses the genesis id of a chain whose state machine does not derive its own.
         *
         * @param settings every sealed setting of the chain, relative to its prefix, except the genesis id and a
         *                 compiled binding IR (the composite document stands for it)
         * @param composite the chain's declarative composite document, or {@code null}
         * @return 64 lowercase hex characters
         */
        String genesisId(String chainId, Map<String, String> settings, JsonNode composite);
    }

    public record ChainRequest(
            String chainId,
            String recipe,
            List<String> capabilities,
            Map<String, String> answers,
            JsonNode composite,
            JsonNode authenticatedMap) {
    }

    /**
     * @param finality {@code majority}, {@code two-thirds}, {@code all}, or an explicit signature count
     * @param memberKeys founding member public keys; the first is the fixed proposer
     * @param pluginsDirectory the distribution's plugin bundles, used to compile declarative bindings
     */
    public record Request(
            String application,
            String network,
            String yanoVersion,
            String finality,
            String membership,
            String sequencing,
            List<String> memberKeys,
            List<ChainRequest> chains,
            Path pluginsDirectory,
            Path baseDirectory,
            SealHooks hooks) {
    }

    /**
     * @param settings consensus settings relative to {@code yano.app-chain.chains[i].}
     * @param nodeSettings node-local settings template the recipe needs, relative to the same prefix
     * @param globalSettings settings outside the chain prefix (node-wide)
     * @param bundles plugin bundle ids the chain runs on (built-in host parts excluded)
     */
    public record ResolvedChain(
            String chainId,
            String recipe,
            int threshold,
            Map<String, String> settings,
            Map<String, String> nodeSettings,
            Map<String, String> globalSettings,
            List<String> bundles) {
    }

    /** Nested node configuration YAML for flat {@code a.b[0].c} keys, as generated projects use. */
    public static String yaml(Map<String, String> values, String header) {
        return AppChainYamlConfigRenderer.render(values, header);
    }

    /** SHA-256 of a public network's Shelley genesis, as {@code observation.l1-network-genesis-id} needs. */
    public static String publicNetworkGenesisId(String network) {
        return L1NetworkGenesis.publicNetworks().get(network);
    }

    /**
     * The non-secret role bootstrap plan template for a role-approvals or role-evidence chain; operators replace every
     * {@code REPLACE_*} value and run {@code yano.sh appchain role bootstrap --plan <file>}.
     */
    public static String roleBootstrapTemplate(String chainId, String profile, int threshold) {
        return """
                # Generated non-secret planning template. Replace every REPLACE_* value.
                apiVersion: yano.bloxbean.com/v1alpha1
                kind: RoleBootstrapPlan
                chainId: %s
                profile: %s
                idempotency:
                  beforeEveryOperation: QUERY_COMMITTED_RECORD_AND_VERIFY_PROOF
                  whenAbsent: PROPOSE_APPROVE_TO_THRESHOLD_AND_ACTIVATE
                  whenExactRevisionAndValueMatch: SKIP_AND_RECORD_PROOF
                  whenRevisionOrValueDiffers: FAIL_CLOSED
                  replacement: FORBIDDEN
                organizations:
                  - organizationId: organization-a
                    revision: 1
                    status: ACTIVE
                    metadataCommitment: REPLACE_64_HEX_OR_EMPTY
                  - organizationId: organization-b
                    revision: 1
                    status: ACTIVE
                    metadataCommitment: REPLACE_64_HEX_OR_EMPTY
                actors:
                  - actorId: proposer-a
                    organizationId: organization-a
                    revision: 1
                    roles: [proposer]
                    publicKeys:
                      - keyId: proposer-key-v1
                        publicKey: REPLACE_64_HEX
                        validFromHeight: 1
                        validUntilHeight: 0
                        proofOfPossession: REQUIRED
                  - actorId: reviewer-a
                    organizationId: organization-b
                    revision: 1
                    roles: [reviewer]
                    publicKeys:
                      - keyId: reviewer-key-v1
                        publicKey: REPLACE_64_HEX
                        validFromHeight: 1
                        validUntilHeight: 0
                        proofOfPossession: REQUIRED
                policies:
                  - policyId: application-approval
                    revision: 1
                    proposerRoles: [proposer]
                    clauses:
                      - clauseId: reviewers
                        role: reviewer
                        minimumCount: 1
                        distinctBy: ORGANIZATION
                    rejectionMode: ANY_ELIGIBLE
                    maximumLifetimeBlocks: 1000
                governance:
                  administrators: genesis-app-chain-members
                  threshold: %d
                  sequence: [PROPOSE, APPROVE_TO_THRESHOLD, ACTIVATE]
                  proposalIdsMustBeUnique: true
                verification:
                  organization: organizations/{id}?revision={revision}
                  actor: actors/{id}?revision={revision}
                  policy: policies/{id}?revision={revision}
                  proposal: proposals/{id}
                  stats: stats
                  proofs: responses include proofKey, recordValue, stateRoot, and committedHeight
                """.formatted(chainId, profile, threshold);
    }

    /** Checks of a chain's authenticated map that seal reports: errors refuse the seal, warnings are shown. */
    public record MapFindings(List<String> errors, List<String> warnings) {
    }

    public static MapFindings authenticatedMapFindings(JsonNode authenticatedMap) {
        if (authenticatedMap == null) return new MapFindings(List.of(), List.of());
        AppChainProjectModel.AuthenticatedMapIntent intent;
        try {
            intent = JSON.treeToValue(authenticatedMap, AppChainProjectModel.AuthenticatedMapIntent.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return new MapFindings(List.of("the authenticatedMap section is invalid: " + invalid.getOriginalMessage()),
                    List.of());
        }
        List<AppChainProjectModel.AuthenticatedMapCollectionIntent> collections =
                intent.collections() == null ? List.of() : intent.collections();
        java.util.Set<String> schemas = new java.util.TreeSet<>();
        if (intent.schemas() != null) intent.schemas().forEach(schema -> schemas.add(schema.id()));
        List<String> errors = new ArrayList<>();
        collections.stream()
                .filter(collection -> collection.validator() != null && schemas.contains(collection.validator()))
                .filter(collection -> !"canonical-cbor".equals(collection.valueEncoding()))
                .forEach(collection -> errors.add("collection " + collection.id()
                        + " uses a schema, so its valueEncoding must be canonical-cbor"));
        List<String> warnings = new ArrayList<>();
        var records = intent.genesisRecords();
        java.util.Set<String> direct = new java.util.TreeSet<>();
        java.util.Set<String> approval = new java.util.TreeSet<>();
        if (records != null) {
            if (records.directPolicies() != null) records.directPolicies().stream()
                    .filter(policy -> policy.status() == null || "active".equals(policy.status()))
                    .forEach(policy -> direct.add(policy.id()));
            if (records.approvalPolicies() != null) records.approvalPolicies().stream()
                    .filter(policy -> policy.status() == null || "active".equals(policy.status()))
                    .forEach(policy -> approval.add(policy.id()));
        }
        for (var collection : collections) {
            boolean governedRole = "governed-role".equals(collection.authorization());
            if (!governedRole && !"approval".equals(collection.authorization())) continue;
            String policy = collection.authorizationPolicy();
            if (policy == null || (governedRole ? !direct.contains(policy) : !approval.contains(policy))) {
                warnings.add("collection " + collection.id() + " has no active "
                        + (governedRole ? "direct" : "approval") + " policy at genesis ("
                        + (policy == null ? "none named" : policy) + "); it accepts no writes until the governance "
                        + "authority activates one");
            }
        }
        return new MapFindings(List.copyOf(errors), List.copyOf(warnings));
    }

    /** The recipe ids a draft may select, with their one-line summaries. */
    public static Map<String, String> recipes() throws IOException {
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(AppChainProjectCli.builtInRegistry());
        Map<String, String> recipes = new java.util.LinkedHashMap<>();
        for (AppChainProjectModel.Recipe recipe : catalog.recipes()) {
            if (recipe.effectiveSelectable()) recipes.put(recipe.id(), recipe.name() + " — " + recipe.primaryOutcome());
        }
        return recipes;
    }

    /** The non-secret answers a recipe needs, such as an eUTxO genesis address. */
    public static List<String> recipeAnswers(String recipe) throws IOException {
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(AppChainProjectCli.builtInRegistry());
        List<String> answers = new ArrayList<>(catalog.recipe(recipe).nonSecretAnswers() == null ? List.of()
                : catalog.recipe(recipe).nonSecretAnswers());
        for (String capability : catalog.recipe(recipe).capabilities()) {
            var definition = catalog.capability(capability).nonSecretAnswers();
            if (definition != null) definition.stream().filter(answer -> !answers.contains(answer)).forEach(answers::add);
        }
        return List.copyOf(answers);
    }

    public static List<ResolvedChain> resolve(Request request) throws IOException {
        AppChainPropertyRegistry properties = AppChainProjectCli.builtInRegistry();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog, request.hooks());
        List<String> keys = List.copyOf(request.memberKeys());
        List<AppChainProjectModel.ChainIntent> intents = new ArrayList<>();
        for (ChainRequest chain : request.chains()) {
            AppChainProjectModel.Topology topology = new AppChainProjectModel.Topology(
                    keys.size(), keys, List.of(), request.finality(), request.sequencing(), request.membership(),
                    null, null);
            AppChainProjectModel.AuthenticatedMapIntent map = chain.authenticatedMap() != null
                    ? JSON.treeToValue(chain.authenticatedMap(), AppChainProjectModel.AuthenticatedMapIntent.class)
                    : "authenticated-map".equals(chain.recipe())
                    ? AppChainProjectCli.defaultAuthenticatedMapIntent() : null;
            intents.add(new AppChainProjectModel.ChainIntent(chain.chainId(), chain.recipe(),
                    chain.capabilities() == null ? List.of() : chain.capabilities(),
                    chain.answers() == null ? Map.of() : chain.answers(), topology, map, chain.composite()));
        }
        String plugins = request.pluginsDirectory() == null ? null
                : request.pluginsDirectory().toAbsolutePath().normalize().toString();
        AppChainProjectModel.Blueprint blueprint = new AppChainProjectModel.Blueprint(
                AppChainProjectModel.API_VERSION, AppChainProjectModel.BLUEPRINT_KIND,
                new AppChainProjectModel.Metadata(request.application()),
                new AppChainProjectModel.Spec(request.yanoVersion(), request.network(),
                        new AppChainProjectModel.RuntimeSelection("jvm", plugins),
                        new AppChainProjectModel.DeploymentSelection("host"), List.copyOf(intents)));
        AppChainProjectModel.Resolution resolution = resolver.resolve(blueprint, request.baseDirectory());
        List<ResolvedChain> resolved = new ArrayList<>();
        for (AppChainProjectModel.Resolution chain : resolution.chains()) {
            Map<String, String> settings = new TreeMap<>();
            Map<String, String> global = new TreeMap<>();
            split(chain.consensusProperties(), settings, global);
            Map<String, String> nodeSettings = new TreeMap<>();
            split(chain.nodePropertyTemplate(), nodeSettings, global);
            List<String> bundles = chain.artifacts().stream()
                    .map(id -> catalog.artifact(id).bundleId())
                    .filter(id -> !id.startsWith("builtin:"))
                    .distinct().sorted().toList();
            resolved.add(new ResolvedChain(settings.get("chain-id"), chain.recipe().id(), chain.threshold(),
                    settings, nodeSettings, global, bundles));
        }
        return List.copyOf(resolved);
    }

    private static void split(Map<String, String> values, Map<String, String> chain, Map<String, String> global) {
        values.forEach((key, value) -> {
            if (key.startsWith(CHAIN_PREFIX)) {
                chain.put(key.substring(CHAIN_PREFIX.length()), value);
            } else {
                global.put(key, value);
            }
        });
    }
}
