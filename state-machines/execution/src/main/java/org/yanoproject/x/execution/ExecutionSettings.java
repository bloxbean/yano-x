package org.yanoproject.x.execution;

import org.yanoproject.x.execution.abi.cbor.CborException;
import org.yanoproject.x.execution.abi.v1.EffectLimits;
import org.yanoproject.x.execution.abi.v1.ExecutionDescriptor;
import org.yanoproject.x.framework.api.execution.RuntimeOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Settings of an execution-backed machine, read from the chain's {@code machines.execution.*}
 * entries.
 *
 * <ul>
 *   <li>{@code descriptor-cbor-hex}: the {@link ExecutionDescriptor}. Consensus: identical on every member.</li>
 *   <li>{@code program-path} or {@code program-hex}: where the program bytes are. Node-local; the bytes
 *       are checked against the descriptor's digest, so a wrong file cannot change results.</li>
 *   <li>{@code runtime-options.*}: node-local {@link RuntimeOptions}.</li>
 *   <li>{@code allow-degraded}: node-local; accept a runtime that reports DEGRADED for CONSENSUS.</li>
 * </ul>
 *
 * <p>The effect limits are read from the chain's consensus {@code effects.*} settings with Yano's
 * defaults.
 */
record ExecutionSettings(ExecutionDescriptor descriptor, byte[] program, RuntimeOptions runtimeOptions,
                         boolean allowDegraded, boolean effectsEnabled, int maxEffectsPerBlock,
                         int maxEffectPayloadBytes, long maxExpiryBlocks, long resultWindowBlocks) {

    static final String PREFIX = "machines." + ExecutionStateMachineProvider.ID + ".";
    static final int MAX_DESCRIPTOR_HEX = 65_536;
    static final int MAX_PROGRAM_BYTES = 64 * 1024 * 1024;

    static ExecutionSettings from(Map<String, String> settings) {
        String hex = settings.get(PREFIX + "descriptor-cbor-hex");
        if (hex == null || hex.isEmpty() || hex.length() > MAX_DESCRIPTOR_HEX || !hex.matches("([0-9a-f]{2})+")) {
            throw new IllegalArgumentException(PREFIX + "descriptor-cbor-hex must be bounded lowercase hex");
        }
        ExecutionDescriptor descriptor;
        try {
            descriptor = ExecutionDescriptor.decode(HexFormat.of().parseHex(hex));
        } catch (CborException e) {
            throw new IllegalArgumentException("invalid execution descriptor: " + e.getMessage(), e);
        }
        byte[] program = program(settings);
        TreeMap<String, String> options = new TreeMap<>();
        settings.forEach((key, value) -> {
            if (key.startsWith(PREFIX + "runtime-options.")) {
                options.put(key.substring((PREFIX + "runtime-options.").length()), value);
            }
        });
        return new ExecutionSettings(descriptor, program, new RuntimeOptions(options),
                Boolean.parseBoolean(settings.getOrDefault(PREFIX + "allow-degraded", "false")),
                Boolean.parseBoolean(settings.getOrDefault("effects.enabled", "false")),
                Integer.parseInt(settings.getOrDefault("effects.max-per-block", "256")),
                Integer.parseInt(settings.getOrDefault("effects.max-payload-bytes", "16384")),
                Long.parseLong(settings.getOrDefault("effects.max-expiry-blocks", "100000")),
                Long.parseLong(settings.getOrDefault("effects.result-window-blocks", "100000")));
    }

    private static byte[] program(Map<String, String> settings) {
        String path = settings.get(PREFIX + "program-path");
        String hex = settings.get(PREFIX + "program-hex");
        if ((path == null) == (hex == null)) {
            throw new IllegalArgumentException("set exactly one of " + PREFIX + "program-path and " + PREFIX + "program-hex");
        }
        if (hex != null) {
            if (hex.length() > 2L * MAX_PROGRAM_BYTES || !hex.matches("([0-9a-f]{2})*")) {
                throw new IllegalArgumentException(PREFIX + "program-hex must be bounded lowercase hex");
            }
            return HexFormat.of().parseHex(hex);
        }
        try {
            Path file = Path.of(path);
            if (Files.size(file) > MAX_PROGRAM_BYTES) {
                throw new IllegalArgumentException("program file is larger than " + MAX_PROGRAM_BYTES + " bytes");
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read program " + path + ": " + e.getMessage(), e);
        }
    }

    EffectLimits effectLimits(int emittedThisBlock) {
        if (!effectsEnabled) {
            return EffectLimits.disabled();
        }
        return new EffectLimits(true, maxEffectPayloadBytes, maxExpiryBlocks, resultWindowBlocks,
                Math.max(0, maxEffectsPerBlock - emittedThisBlock));
    }
}
