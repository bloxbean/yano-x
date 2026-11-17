package org.yanoproject.x.framework.rest;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.framework.config.AppChainConfigParser;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Moved from Yano baseline YanoProducerTest with the app-chain projection (ADR-064 D7). */
class AppChainGlobalsTest {

    @Test
    void certifiedObservationAndConsensusSettingsReachTheRuntimeParserForBothConfigShapes() {
        Map<String, String> settings = Map.of(
                "observations.profile-cbor-hex", "a100",
                "observations.providers.shipment.endpoint", "https://example.invalid/proof",
                "observations.reporters.ada-usd", "01".repeat(32),
                "observations.policy.ada-usd", "a100",
                "consensus.max-byzantine-members", "1",
                "consensus.round-timeout-ms", "5000");
        Map<String, String> properties = new LinkedHashMap<>();
        settings.forEach((key, value) -> {
            properties.put("yano.app-chain." + key, value);
            properties.put("yano.app-chain.chains[0]." + key, value);
        });
        properties.put("yano.app-chain.chains[0].chain-id", "observations");
        var producer = new AppChainGlobals();
        producer.appConfig = new PresentConfig(properties);

        Map<String, Object> globals = new LinkedHashMap<>();
        producer.forwardAppChainDynamicKeys(globals);
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("chain-id", "observations");
        globals.forEach((key, value) -> flat.put(key.substring("yano.app-chain.".length()), value));
        var flatConfig = AppChainConfigParser.parse(flat);
        var indexedConfig = AppChainConfigParser.parse(producer.parseAppChainChains().getFirst());
        settings.forEach((key, value) -> {
            assertEquals(value, flatConfig.pluginSettings().get(key), "flat: " + key);
            assertEquals(value, indexedConfig.pluginSettings().get(key), "indexed: " + key);
        });
    }


    @Test
    void dynamicSettingsAreForwardedForFlatAndIndexedAppChains() {
        var producer = new AppChainGlobals();
        producer.appConfig = new PresentConfig(Map.ofEntries(
                Map.entry("yano.app-chain.effects.enabled", "true"),
                Map.entry("yano.app-chain.observation.l1-network-genesis-id", "01".repeat(32)),
                Map.entry("yano.app-chain.effects.metrics.types", "cardano.payment,webhook"),
                Map.entry("yano.app-chain.state.commitment-profile", "mpf-blake2b256-v1"),
                Map.entry("yano.app-chain.state.l1-proof-consumption-required", "true"),
                Map.entry("yano.app-chain.capabilities.authenticated-snapshots.enabled", "true"),
                Map.entry("yano.app-chain.chains[0].chain-id", "payments"),
                Map.entry("yano.app-chain.chains[0].effects.enabled", "true"),
                Map.entry("yano.app-chain.chains[0].observation.l1-network-genesis-id", "02".repeat(32)),
                Map.entry("yano.app-chain.chains[0].effects.executor.enabled", "true"),
                Map.entry("yano.app-chain.chains[0].state.genesis-id", "ab".repeat(32)),
                Map.entry("yano.app-chain.chains[0].capabilities.authenticated-snapshots.enabled",
                        "true"),
                Map.entry("yano.app-chain.chains[0].unrelated.value", "ignored")));

        Map<String, Object> globals = new LinkedHashMap<>();
        producer.forwardAppChainDynamicKeys(globals);
        assertEquals("true", globals.get("yano.app-chain.effects.enabled"));
        assertEquals("01".repeat(32),
                globals.get("yano.app-chain.observation.l1-network-genesis-id"));
        assertEquals("cardano.payment,webhook",
                globals.get("yano.app-chain.effects.metrics.types"));
        assertEquals("mpf-blake2b256-v1",
                globals.get("yano.app-chain.state.commitment-profile"));
        assertEquals("true",
                globals.get("yano.app-chain.state.l1-proof-consumption-required"));
        assertEquals("true", globals.get(
                "yano.app-chain.capabilities.authenticated-snapshots.enabled"));

        var chain = producer.parseAppChainChains().getFirst();
        assertEquals("payments", chain.get("chain-id"));
        assertEquals("true", chain.get("effects.enabled"));
        assertEquals("02".repeat(32), chain.get("observation.l1-network-genesis-id"));
        assertEquals("true", chain.get("effects.executor.enabled"));
        assertEquals("ab".repeat(32), chain.get("state.genesis-id"));
        assertEquals("true", chain.get(
                "capabilities.authenticated-snapshots.enabled"));
        assertFalse(chain.containsKey("unrelated.value"));
    }


    @Test
    void enabledAppChainsRequireTheApplicationLedgerRootBundle() throws Exception {
        Map<String, String> indexed = Map.of("yano.app-chain.chains[0].chain-id", "orders");

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> projection(indexed, Optional.of("org.example.other")).globals());
        assertEquals("yano.app-chain.enabled=true requires bundle org.yanoproject.x.app-ledger "
                + "in yano.plugins.required", missing.getMessage());
        assertThrows(IllegalStateException.class, () -> projection(indexed, Optional.empty()).globals());

        Map<String, Object> globals = projection(indexed,
                Optional.of("org.example.other, org.yanoproject.x.app-ledger")).globals();
        assertEquals(true, globals.get("yano.app-chain.enabled"));
        assertEquals(List.of(Map.of("chain-id", "orders")), globals.get("yano.app-chain.chains"));
        assertEquals("appchain-chainstate", globals.get("yano.app-chain.storage.path"));
        assertFalse(globals.containsKey("yano.plugins.required"));

        // An explicit enabled=false disables the indexed chains, and needs no root bundle.
        var disabled = projection(indexed, Optional.empty());
        disabled.appChainEnabledOpt = Optional.of(false);
        assertEquals(false, disabled.globals().get("yano.app-chain.enabled"));
        assertFalse(disabled.globals().containsKey("yano.app-chain.chains"));
    }

    /** The projection as CDI would inject it: annotated defaults, empty optionals. */
    private static AppChainGlobals projection(Map<String, String> properties, Optional<String> required)
            throws Exception {
        var projection = new AppChainGlobals();
        projection.appConfig = new PresentConfig(properties);
        for (Field field : AppChainGlobals.class.getDeclaredFields()) {
            ConfigProperty property = field.getAnnotation(ConfigProperty.class);
            if (property == null) {
                continue;
            }
            String value = property.defaultValue();
            Object injected = switch (field.getType().getName()) {
                case "java.util.Optional" -> Optional.empty();
                case "java.lang.String" -> value;
                case "boolean" -> Boolean.parseBoolean(value);
                case "int" -> Integer.parseInt(value);
                case "long" -> Long.parseLong(value);
                default -> throw new IllegalStateException(field.toString());
            };
            field.set(projection, injected);
        }
        projection.requiredPlugins = required;
        return projection;
    }

    private record PresentConfig(Map<String, String> values) implements Config {
        @Override
        public <T> T getValue(String propertyName, Class<T> propertyType) {
            return getOptionalValue(propertyName, propertyType)
                    .orElseThrow(() -> new IllegalArgumentException("Missing config property: " + propertyName));
        }

        @Override
        public ConfigValue getConfigValue(String propertyName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
            String value = values.get(propertyName);
            if (value == null) {
                return Optional.empty();
            }
            if (propertyType == String.class) {
                return Optional.of(propertyType.cast(value));
            }
            if (propertyType == Long.class) {
                return Optional.of(propertyType.cast(Long.valueOf(value)));
            }
            if (propertyType == Boolean.class) {
                return Optional.of(propertyType.cast(Boolean.valueOf(value)));
            }
            if (propertyType == String[].class) {
                return Optional.of(propertyType.cast(value.split(",")));
            }
            throw new UnsupportedOperationException("Unsupported config type: " + propertyType.getName());
        }

        @Override
        public Iterable<String> getPropertyNames() {
            return values.keySet();
        }

        @Override
        public Iterable<ConfigSource> getConfigSources() {
            return List.of();
        }

        @Override
        public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
            return Optional.empty();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new IllegalArgumentException("Cannot unwrap to " + type.getName());
        }
    }
}
