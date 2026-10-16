package org.yanoproject.x.eutxo.demo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/** Deterministic installed-provider inventory with duplicate rejection. */
public final class EutxoDemoScenarioRegistry {
    /** Scenarios no distribution installs any more; their workspaces are explained rather than unknown. */
    private static final Set<String> WITHDRAWN = Set.of("zk");

    private final Map<String, EutxoDemoScenarioProvider> providers;

    public EutxoDemoScenarioRegistry() {
        this(Thread.currentThread().getContextClassLoader());
    }

    EutxoDemoScenarioRegistry(ClassLoader loader) {
        List<EutxoDemoScenarioProvider> discovered = new ArrayList<>();
        ServiceLoader.load(EutxoDemoScenarioProvider.class, loader).forEach(discovered::add);
        discovered.sort(Comparator.comparing(EutxoDemoScenarioProvider::id)
                .thenComparing(provider -> provider.getClass().getName()));
        Map<String, EutxoDemoScenarioProvider> indexed = new LinkedHashMap<>();
        for (EutxoDemoScenarioProvider provider : discovered) {
            String id = provider.id();
            if (id == null || !id.matches("[a-z][a-z0-9-]{0,31}")) {
                throw new IllegalStateException("invalid installed EUTxO demo scenario ID");
            }
            if (indexed.putIfAbsent(id, provider) != null) {
                throw new IllegalStateException(
                        "duplicate installed EUTxO demo scenario: " + id);
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public List<EutxoDemoScenarioProvider> all() {
        return providers.values().stream()
                .sorted(Comparator.comparing(EutxoDemoScenarioProvider::id))
                .toList();
    }

    public EutxoDemoScenarioProvider require(String id) {
        EutxoDemoScenarioProvider provider = providers.get(id);
        if (provider == null && WITHDRAWN.contains(id)) {
            throw new IllegalArgumentException("EUTxO demo scenario " + id
                    + " has been withdrawn; stop any nodes it started and delete its workspace directory");
        }
        if (provider == null) {
            throw new IllegalArgumentException(
                    "EUTxO demo scenario is not installed: " + id);
        }
        return provider;
    }
}
