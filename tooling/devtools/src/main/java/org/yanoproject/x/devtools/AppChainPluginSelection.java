package org.yanoproject.x.devtools;

import org.yanoproject.api.plugin.PluginBundleInfo;
import org.yanoproject.api.plugin.PluginContributionInfo;
import org.yanoproject.catalog.BundleContribution;
import org.yanoproject.catalog.IndexedBundle;
import org.yanoproject.catalog.PluginCatalogException;
import org.yanoproject.catalog.PluginCatalogInspection;
import org.yanoproject.catalog.PluginCatalogInspectionPolicy;
import org.yanoproject.catalog.PluginCatalogInspector;
import org.yanoproject.catalog.PluginIndexGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Checks an extracted distribution's {@code plugins/} the way a starting node selects it: the
 * host's own resource-only catalog validation, then every runtime bundle the project's artifacts
 * name, and a provider for every state machine and L1 observer it configures.
 */
final class AppChainPluginSelection {
    private static final int MAX_ARTIFACTS = 128;
    private static final Pattern STATE_MACHINE =
            Pattern.compile("yano\\.app-chain\\.chains\\[\\d+]\\.state-machine");
    private static final Pattern OBSERVER_TYPE =
            Pattern.compile("yano\\.app-chain\\.chains\\[\\d+]\\.observers\\.[^.]+\\.type");
    /**
     * State machines and observers the host itself provides. These are the names Yano's catalog
     * reserves as SYSTEM contributions, so no plugin bundle can provide them.
     */
    static final Set<String> HOST_BUILT_INS = Set.of(
            "app-state-machine/ordered-log",
            "l1-observer/address-deposit",
            "l1-observer/metadata-label");

    record Result(String status, String detail) {
    }

    private AppChainPluginSelection() {
    }

    static Result check(Path distribution, Map<String, String> consensusValues, Set<String> projectBundles)
            throws IOException {
        return check(distribution, consensusValues, projectBundles, Set.of());
    }

    /**
     * @param stagedBundles project bundles the release does not ship (first-party optional artifacts
     *                      still to be installed); their absence is pending rather than a failure
     */
    static Result check(Path distribution, Map<String, String> consensusValues, Set<String> projectBundles,
                        Set<String> stagedBundles) throws IOException {
        PluginCatalogInspection inspection;
        try {
            inspection = new PluginCatalogInspector().inspect(
                    artifacts(distribution.resolve("plugins")), PluginCatalogInspectionPolicy.current());
        } catch (PluginCatalogException failure) {
            return new Result("FAIL", "plugins/ selection is invalid: " + bounded(failure.getMessage()));
        }
        Map<String, String> selected = new TreeMap<>();
        for (PluginBundleInfo bundle : inspection.bundles()) {
            if (!bundle.selected()) continue;
            for (PluginContributionInfo contribution : bundle.contributions()) {
                selected.put(contribution.kind() + "/" + contribution.name(), bundle.id());
            }
        }
        Set<String> selectedBundles = new TreeSet<>(inspection.selectedBundleOrder());
        Map<String, String> optional = optionalProviders(distribution.resolve("optional-plugins"));
        List<String> missing = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        // optional-plugins/ only suggests the fix: every project bundle must be selected.
        for (String bundle : new TreeSet<>(projectBundles)) {
            if (selectedBundles.contains(bundle)) continue;
            if (optional.containsValue(bundle)) {
                missing.add(bundle + " (install it from optional-plugins/)");
            } else if (stagedBundles.contains(bundle)) {
                pending.add(bundle + " (not shipped by this release; install it into plugins/)");
            } else {
                missing.add(bundle + " (in neither plugins/ nor optional-plugins/)");
            }
        }
        for (String required : requiredContributions(consensusValues)) {
            if (selected.containsKey(required) || HOST_BUILT_INS.contains(required)) continue;
            if (optional.containsKey(required)) {
                missing.add(required + " (install " + optional.get(required) + " from optional-plugins/)");
            } else if (!pending.isEmpty()) {
                // A bundle still to be installed may be the provider.
                pending.add(required);
            } else {
                missing.add(required + " (no bundle in plugins/ provides it)");
            }
        }
        if (!missing.isEmpty()) {
            return new Result("FAIL", "plugins/ is missing " + String.join(", ", missing));
        }
        if (!pending.isEmpty()) {
            return new Result("PENDING", "plugins/ still needs " + String.join(", ", pending));
        }
        return new Result("PASS", inspection.selectedBundleOrder().size()
                + " selected bundles validate and provide every configured state machine and observer");
    }

    static TreeSet<String> requiredContributions(Map<String, String> consensusValues) {
        TreeSet<String> required = new TreeSet<>();
        consensusValues.forEach((key, value) -> {
            if (value == null || value.isBlank()) return;
            if (STATE_MACHINE.matcher(key).matches()) required.add("app-state-machine/" + value.trim());
            if (OBSERVER_TYPE.matcher(key).matches()) required.add("l1-observer/" + value.trim());
        });
        return required;
    }

    private static Map<String, String> optionalProviders(Path directory) throws IOException {
        Map<String, String> providers = new TreeMap<>();
        // One scan per artifact: alternative bundles may provide the same contribution.
        for (Path artifact : artifacts(directory)) {
            try {
                for (IndexedBundle bundle : new PluginIndexGenerator().generate(List.of(artifact)).bundles()) {
                    for (BundleContribution contribution : bundle.manifest().contributions()) {
                        providers.putIfAbsent(contribution.kind().manifestKey() + "/" + contribution.name(),
                                bundle.manifest().id());
                    }
                }
            } catch (PluginCatalogException ignored) {
                // An unreadable optional bundle cannot be the missing provider.
            }
        }
        return providers;
    }

    private static List<Path> artifacts(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(directory.getFileName() + " must be a non-symlink directory");
        }
        try (var contents = Files.list(directory)) {
            List<Path> jars = contents
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
            if (jars.size() > MAX_ARTIFACTS) {
                throw new IOException(directory.getFileName() + " contains too many plugin artifacts");
            }
            return jars;
        }
    }

    private static String bounded(String message) {
        String value = message == null || message.isBlank() ? "catalog validation failed" : message;
        value = value.replaceAll("[\\r\\n\\t]", " ");
        return value.length() > 240 ? value.substring(0, 240) : value;
    }
}
