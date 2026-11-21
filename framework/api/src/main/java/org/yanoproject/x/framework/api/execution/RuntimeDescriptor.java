package org.yanoproject.x.framework.api.execution;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Identity of an open runtime.
 *
 * @param runtimeId the factory's {@link ExecutionRuntimeFactory#runtimeId()}
 * @param engineName human-readable engine name, for diagnostics only
 * @param engineVersion engine version, for diagnostics only (it must also appear in the fingerprint)
 * @param metering how the runtime meters guest compute
 * @param memory how the runtime bounds guest memory
 * @param abiVersions guest ABI versions this runtime can bind
 * @param mediaTypes program media types this runtime loads, for example {@code application/wasm}
 * @param fingerprintComponents every input that can change execution results, as sorted
 *        name/value pairs: engine version, metering unit, semantic options. The adapter hashes the
 *        canonical encoding of this map into the engine fingerprint pinned in genesis.
 */
public record RuntimeDescriptor(
        String runtimeId,
        String engineName,
        String engineVersion,
        MeteringModel metering,
        MemoryModel memory,
        Set<Integer> abiVersions,
        Set<String> mediaTypes,
        SortedMap<String, String> fingerprintComponents) {

    public RuntimeDescriptor {
        Objects.requireNonNull(runtimeId, "runtimeId");
        if (!runtimeId.matches("[a-z][a-z0-9.-]{0,63}")) {
            throw new IllegalArgumentException("invalid runtime id: " + runtimeId);
        }
        Objects.requireNonNull(engineName, "engineName");
        Objects.requireNonNull(engineVersion, "engineVersion");
        Objects.requireNonNull(metering, "metering");
        Objects.requireNonNull(memory, "memory");
        abiVersions = Collections.unmodifiableSet(new TreeSet<>(abiVersions));
        mediaTypes = Collections.unmodifiableSet(new TreeSet<>(mediaTypes));
        fingerprintComponents = Collections.unmodifiableSortedMap(new TreeMap<>(fingerprintComponents));
        if (!fingerprintComponents.containsKey("runtime.id")
                || !fingerprintComponents.get("runtime.id").equals(runtimeId)) {
            throw new IllegalArgumentException("fingerprint components must contain runtime.id=" + runtimeId);
        }
        if (!fingerprintComponents.containsKey("metering.unit")
                || !fingerprintComponents.get("metering.unit").equals(metering.unit())) {
            throw new IllegalArgumentException("fingerprint components must contain metering.unit");
        }
    }
}
