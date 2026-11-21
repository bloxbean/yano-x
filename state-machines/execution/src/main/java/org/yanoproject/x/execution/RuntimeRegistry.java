package org.yanoproject.x.execution;

import org.yanoproject.x.framework.api.execution.ExecutionRuntime;
import org.yanoproject.x.framework.api.execution.ExecutionRuntimeFactory;
import org.yanoproject.x.framework.api.execution.RuntimeOptions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shares one open runtime per factory and option set, because engines are expensive and machines
 * have no close hook. Factories are the catalog's facades, so a new plugin catalog gives new keys.
 */
final class RuntimeRegistry {

    private record Key(ExecutionRuntimeFactory factory, RuntimeOptions options) {
    }

    private static final Map<Key, ExecutionRuntime> OPEN = new ConcurrentHashMap<>();

    static ExecutionRuntime open(ExecutionRuntimeFactory factory, RuntimeOptions options) {
        return OPEN.computeIfAbsent(new Key(factory, options), key -> key.factory().open(key.options()));
    }

    private RuntimeRegistry() {
    }
}
