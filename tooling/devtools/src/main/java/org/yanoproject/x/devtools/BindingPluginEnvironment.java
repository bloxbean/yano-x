package org.yanoproject.x.devtools;

import org.yanoproject.api.config.PluginsOptions;
import org.yanoproject.runtime.plugins.PluginLoaderHandle;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.runtime.plugins.PluginRuntimeEnvironment;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Owned offline lifetime for the host's real, manifested directory plugin catalog.
 *
 * <p>Devtools itself links some pure X libraries and implementation modules. They must not shadow the
 * selected directory bundle's provider or helpers. The parent projection therefore hides exact class and
 * resource entries found in the host loader's immutable artifact snapshots, before catalog activation.
 * Discovery, compatibility checks, provider facades, origin validation, and teardown remain host-owned.
 * This is not a provider loader, a service-discovery fallback, or a security sandbox for untrusted Java code.
 */
final class BindingPluginEnvironment implements AutoCloseable {
    private final PluginRuntimeEnvironment runtime;

    private BindingPluginEnvironment(PluginRuntimeEnvironment runtime) { this.runtime = runtime; }

    static BindingPluginEnvironment open(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("plugin directory does not exist");
        SnapshotParent parent = new SnapshotParent(BindingPluginEnvironment.class.getClassLoader());
        PluginLoaderHandle handle = PluginLoaderHandle.directory(directory, parent);
        try {
            if (handle.artifacts().isEmpty()) throw new IllegalArgumentException("plugin directory is empty");
            parent.initialize(handle.artifacts());
            return new BindingPluginEnvironment(PluginRuntimeEnvironment.open(PluginsOptions.defaults(), handle));
        } catch (IOException | RuntimeException | Error error) {
            try { handle.close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    PluginProviderRegistry providers() { return runtime.providers(); }

    String fingerprint() { return runtime.catalog().fingerprint(); }

    /** Exact validated catalog view: plugin API level, fingerprint and bundle inventory. */
    org.yanoproject.api.plugin.PluginCatalogView catalog() { return runtime.catalog(); }

    @Override public void close() { runtime.close(); }

    /** Parent-first SPI identities are shared; all snapshotted implementation entries remain bundle-owned. */
    static final class SnapshotParent extends ClassLoader {
        private Set<String> entries = Set.of();
        private boolean initialized;

        SnapshotParent(ClassLoader parent) { super(parent); }

        void initialize(List<Path> artifacts) throws IOException {
            if (initialized) throw new IllegalStateException("plugin parent already initialized");
            Set<String> owned = new HashSet<>();
            for (Path artifact : artifacts) {
                try (JarFile jar = new JarFile(artifact.toFile())) {
                    var all = jar.entries();
                    boolean manifested = false;
                    while (all.hasMoreElements()) {
                        var entry = all.nextElement();
                        if (entry.isDirectory()) continue;
                        String name = entry.getName();
                        if (name.startsWith("META-INF/yano/plugins/") && name.endsWith(".json")) manifested = true;
                        if (name.startsWith("META-INF/versions/")) {
                            int separator = name.indexOf('/', "META-INF/versions/".length());
                            if (separator > 0) name = name.substring(separator + 1);
                        }
                        owned.add(name);
                        if (owned.size() > 250_000) throw new IllegalArgumentException("plugin entry budget exceeded");
                    }
                    if (!manifested) {
                        throw new IllegalArgumentException("offline binding plugins must be manifested bundles");
                    }
                }
            }
            entries = Set.copyOf(owned);
            initialized = true;
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (hidden(name.replace('.', '/') + ".class")) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }

        @Override public URL getResource(String name) { return hidden(name) ? null : super.getResource(name); }

        @Override public Enumeration<URL> getResources(String name) throws IOException {
            return hidden(name) ? Collections.emptyEnumeration() : super.getResources(name);
        }

        private boolean hidden(String name) {
            // Host classes are stripped from published bundles. SPI identity must never be hidden even
            // for an invalid bundle; catalog validation will reject a bundle that embeds the host API.
            if (name.startsWith("org/yanoproject/api/") || name.startsWith("com/bloxbean/cardano/")
                    || name.startsWith("com/fasterxml/jackson/")
                    || name.startsWith("co/nstant/in/cbor/")) return false;
            return name.equals("META-INF/yano-plugin-index-v1.json")
                    || name.startsWith("META-INF/services/") || name.startsWith("META-INF/yano/plugins/")
                    || entries.contains(name);
        }
    }
}
