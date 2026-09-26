package org.yanoproject.x.devtools;

import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.plugin.PluginBundleInfo;
import org.yanoproject.api.plugin.PluginCatalogView;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.x.composite.bindings.BindingProgram;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Exact identities recorded in binding authoring catalogs and reports (ADR-031.2 common rules).
 *
 * <p>Versions come from the build; digests identify the actual executing JARs so SNAPSHOT and local builds remain
 * distinguishable. A digest is {@code null} when code runs from a class directory (for example during tests).
 * These identities establish that inputs match; they never establish provenance or trust.
 */
final class BindingToolIdentity {
    static final String AUTHORING_ENVIRONMENT = "yano-x-binding-authoring-environment-v1";
    private static final long MAX_JAR_BYTES = 512L * 1024 * 1024;
    private static volatile Map<String, Object> cached;

    private BindingToolIdentity() { }

    /** Producer and host identity, computed once per process. */
    static Map<String, Object> toolIdentity() {
        Map<String, Object> value = cached;
        if (value != null) return value;
        Properties properties = new Properties();
        try (InputStream input = BindingToolIdentity.class.getResourceAsStream(
                "/META-INF/yano-x/binding-tooling.properties")) {
            if (input != null) properties.load(input);
        } catch (IOException ignored) {
            // Identity stays "unknown" rather than guessed.
        }
        Map<String, Object> producer = new LinkedHashMap<>();
        producer.put("tool", "yano-x-devtools");
        producer.put("version", properties.getProperty("yanoXVersion", "unknown"));
        producer.put("jarSha256", jarDigest(BindingToolIdentity.class));
        producer.put("linkedCompositeJarSha256", jarDigest(BindingProgram.class));
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("version", properties.getProperty("yanoVersion", "unknown"));
        host.put("coreApiJarSha256", jarDigest(AppStateMachine.class));
        host.put("runtimeJarSha256", jarDigest(PluginProviderRegistry.class));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("producer", producer);
        result.put("host", host);
        result.put("authoringEnvironment", AUTHORING_ENVIRONMENT);
        // Insertion order is part of deterministic report/catalog output; Map.copyOf would not preserve it.
        cached = java.util.Collections.unmodifiableMap(result);
        return cached;
    }

    /** Version of devtools recorded at build time, or {@code unknown}. */
    static String toolVersion() {
        @SuppressWarnings("unchecked")
        var producer = (Map<String, Object>) toolIdentity().get("producer");
        return (String) producer.get("version");
    }

    /** Plugin catalog identity: fingerprint, plugin API level and every bundle's id, version and digest. */
    static Map<String, Object> catalogIdentity(PluginCatalogView catalog) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fingerprint", catalog.fingerprint());
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("major", catalog.pluginApiMajor());
        api.put("level", catalog.pluginApiLevel());
        result.put("pluginApi", api);
        List<Map<String, Object>> bundles = catalog.bundles().stream()
                .sorted(java.util.Comparator.comparing(PluginBundleInfo::id))
                .map(bundle -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", bundle.id());
                    item.put("version", bundle.version());
                    item.put("digest", bundle.digest());
                    item.put("digestMode", bundle.digestMode() == null ? null : bundle.digestMode().name());
                    item.put("selected", bundle.selected());
                    return item;
                }).toList();
        result.put("bundles", bundles);
        return result;
    }

    private static String jarDigest(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            Path path = Path.of(source.getLocation().toURI());
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_JAR_BYTES) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | URISyntaxException | SecurityException | IllegalArgumentException
                 | NoSuchAlgorithmException unavailable) {
            return null;
        }
    }
}
