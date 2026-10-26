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
         * @param settings the chain's resolved settings so far, relative to its prefix, without a genesis id
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
     * @param finality {@code majority}, {@code two-thirds} or {@code all}
     * @param memberKeys founding member public keys; the first is the fixed proposer
     * @param pluginsDirectory the distribution's plugin bundles, used to compile declarative bindings
     */
    public record Request(
            String application,
            String network,
            String yanoVersion,
            String finality,
            String membership,
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

    /** The recipe ids a draft may select, with their one-line summaries. */
    public static Map<String, String> recipes() throws IOException {
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(AppChainProjectCli.builtInRegistry());
        Map<String, String> recipes = new java.util.LinkedHashMap<>();
        for (AppChainProjectModel.Recipe recipe : catalog.recipes()) {
            if (recipe.effectiveSelectable()) recipes.put(recipe.id(), recipe.name() + " — " + recipe.primaryOutcome());
        }
        return recipes;
    }

    public static List<ResolvedChain> resolve(Request request) throws IOException {
        AppChainPropertyRegistry properties = AppChainProjectCli.builtInRegistry();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog, request.hooks());
        List<String> keys = List.copyOf(request.memberKeys());
        List<AppChainProjectModel.ChainIntent> intents = new ArrayList<>();
        for (ChainRequest chain : request.chains()) {
            AppChainProjectModel.Topology topology = new AppChainProjectModel.Topology(
                    keys.size(), keys, List.of(), request.finality(), "fixed", request.membership(), null, null);
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
