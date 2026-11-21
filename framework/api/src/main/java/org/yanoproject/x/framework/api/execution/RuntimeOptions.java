package org.yanoproject.x.framework.api.execution;

import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Node-local runtime options, such as a compilation cache directory or diagnostics switches.
 *
 * <p>Options must never change execution results. An option that would change results is a
 * semantic option: it belongs in {@link RuntimeDescriptor#fingerprintComponents()} and in the
 * genesis descriptor, not here.
 */
public record RuntimeOptions(SortedMap<String, String> values) {

    public RuntimeOptions {
        values = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNull(values, "values")));
    }

    public static RuntimeOptions none() {
        return new RuntimeOptions(new TreeMap<>());
    }

    public static RuntimeOptions of(Map<String, String> values) {
        return new RuntimeOptions(new TreeMap<>(values));
    }

    public String get(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }
}
