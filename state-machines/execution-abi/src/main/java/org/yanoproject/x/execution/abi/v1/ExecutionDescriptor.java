package org.yanoproject.x.execution.abi.v1;

import org.yanoproject.x.execution.abi.cbor.CanonicalCbor;
import org.yanoproject.x.framework.api.execution.RuntimeDescriptor;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The genesis-pinned description of an execution-backed state machine. Everything that can change
 * a result is here; node-local settings only say where to find the program bytes.
 *
 * @param runtimeId the runtime that must execute the program
 * @param engineFingerprint {@link #fingerprint(RuntimeDescriptor)} of the engine the chain was created with
 * @param programDigest blake2b-256 of the program bytes
 * @param memoryLimitBytes guest memory limit for runtimes that enforce one
 * @param config opaque program configuration passed to every entry point
 * @param receipts whether discarded transitions leave a {@link Receipt} in state
 */
public record ExecutionDescriptor(String runtimeId, byte[] engineFingerprint, String programMediaType,
                                  byte[] programDigest, int abiVersion, Budgets budgets, long memoryLimitBytes,
                                  byte[] config, boolean receipts) {

    /** Per-invocation budgets: compute in the runtime's unit, host fuel in ABI host units. */
    public record Budgets(long transitionCompute, long transitionHost, long validateCompute, long validateHost,
                          long queryCompute, long queryHost) {
        public Budgets {
            if (transitionCompute < 1 || transitionHost < 1 || validateCompute < 1 || validateHost < 1
                    || queryCompute < 1 || queryHost < 1) {
                throw new IllegalArgumentException("budgets must be positive");
            }
        }

        Map<Long, Object> toCbor() {
            return Map.of(0L, transitionCompute, 1L, transitionHost, 2L, validateCompute, 3L, validateHost,
                    4L, queryCompute, 5L, queryHost);
        }

        static Budgets fromCbor(Object value) {
            Fields f = Fields.of("budgets", value, Fields.keys(0, 1, 2, 3, 4, 5));
            return new Budgets(f.uint(0), f.uint(1), f.uint(2), f.uint(3), f.uint(4), f.uint(5));
        }
    }

    public ExecutionDescriptor {
        Objects.requireNonNull(runtimeId, "runtimeId");
        Objects.requireNonNull(programMediaType, "programMediaType");
        Objects.requireNonNull(budgets, "budgets");
        engineFingerprint = engineFingerprint.clone();
        programDigest = programDigest.clone();
        config = config.clone();
        if (engineFingerprint.length != 32 || programDigest.length != 32) {
            throw new IllegalArgumentException("fingerprint and digest must be 32 bytes");
        }
        if (abiVersion != AbiV1.VERSION) {
            throw new IllegalArgumentException("unsupported abi version " + abiVersion);
        }
        if (memoryLimitBytes < 0) {
            throw new IllegalArgumentException("memoryLimitBytes must be >= 0");
        }
    }

    public byte[] encode() {
        Map<Long, Object> m = new LinkedHashMap<>();
        m.put(0L, 1L);
        m.put(1L, runtimeId);
        m.put(2L, engineFingerprint.clone());
        m.put(3L, programMediaType);
        m.put(4L, programDigest.clone());
        m.put(5L, (long) abiVersion);
        m.put(6L, budgets.toCbor());
        m.put(7L, memoryLimitBytes);
        m.put(8L, config.clone());
        m.put(9L, receipts);
        return CanonicalCbor.encode(m);
    }

    public static ExecutionDescriptor decode(byte[] bytes) {
        Fields f = Fields.decode("execution-descriptor", bytes, Fields.keys(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
        if (f.uint(0) != 1) {
            throw Fields.schema("execution-descriptor", "descriptor version must be 1");
        }
        long abi = f.uint(5);
        if (abi != AbiV1.VERSION) {
            throw Fields.schema("execution-descriptor", "unsupported abi version " + abi);
        }
        return new ExecutionDescriptor(f.text(1), f.bytes(2, 32), f.text(3), f.bytes(4, 32), (int) abi,
                Budgets.fromCbor(f.raw(6)), f.uint(7), f.bytes(8), f.bool(9));
    }

    /** blake2b-256 of the canonical CBOR map of the runtime's fingerprint components. */
    public static byte[] fingerprint(RuntimeDescriptor runtime) {
        return Hashes.blake2b256(CanonicalCbor.encode(new LinkedHashMap<>(runtime.fingerprintComponents())));
    }

    @Override public byte[] engineFingerprint() { return engineFingerprint.clone(); }
    @Override public byte[] programDigest() { return programDigest.clone(); }
    @Override public byte[] config() { return config.clone(); }

    @Override public boolean equals(Object o) {
        return o instanceof ExecutionDescriptor d && runtimeId.equals(d.runtimeId)
                && Arrays.equals(engineFingerprint, d.engineFingerprint) && programMediaType.equals(d.programMediaType)
                && Arrays.equals(programDigest, d.programDigest) && abiVersion == d.abiVersion
                && budgets.equals(d.budgets) && memoryLimitBytes == d.memoryLimitBytes
                && Arrays.equals(config, d.config) && receipts == d.receipts;
    }

    @Override public int hashCode() {
        return Objects.hash(runtimeId, Arrays.hashCode(programDigest));
    }
}
