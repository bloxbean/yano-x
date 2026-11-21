package org.yanoproject.x.framework.api.execution;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Consensus-relevant instance settings, fixed at load time. Everything here comes from the genesis
 * descriptor and the guest ABI version, never from node-local configuration.
 *
 * @param requiredExports entry points the program must export; loading fails without them
 * @param hostFunctions the host functions the guest may import, in ABI table order. The runtime
 *        binds them by name and calls {@link HostCalls#call(int, byte[][])} with the index.
 * @param memoryLimitBytes guest memory limit, enforced if the runtime's {@link MemoryModel} allows it
 */
public record InstanceConfig(Set<String> requiredExports, List<HostFunction> hostFunctions, long memoryLimitBytes) {

    public InstanceConfig {
        requiredExports = Set.copyOf(requiredExports);
        hostFunctions = List.copyOf(hostFunctions);
        Set<String> names = new HashSet<>();
        for (int i = 0; i < hostFunctions.size(); i++) {
            HostFunction function = hostFunctions.get(i);
            if (function.index() != i) {
                throw new IllegalArgumentException("host function " + function.name() + " must have index " + i);
            }
            if (!names.add(function.name())) {
                throw new IllegalArgumentException("duplicate host function " + function.name());
            }
        }
        if (memoryLimitBytes < 0) {
            throw new IllegalArgumentException("memoryLimitBytes must be >= 0");
        }
    }

    public HostFunction hostFunction(String name) {
        Objects.requireNonNull(name, "name");
        return hostFunctions.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }
}
