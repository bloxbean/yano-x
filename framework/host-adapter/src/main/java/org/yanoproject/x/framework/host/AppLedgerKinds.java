package org.yanoproject.x.framework.host;

import org.yanoproject.api.plugin.spi.ContributionKindBinding;
import org.yanoproject.catalog.ContributionKindRegistry;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

/**
 * The contribution kinds a Yano host accepts, for offline tooling and tests that build catalogs outside a
 * running host. Callers supply {@code yano-plugin-catalog}; this module only compiles against it.
 */
public final class AppLedgerKinds {
    private AppLedgerKinds() {
    }

    /**
     * Yano's built-in kinds plus the descriptor kinds of the host built on {@code hostLoader}, by the Yano
     * runtime's own rule: every {@link ContributionKindBinding} the loader's ServiceLoader finds names its
     * {@code descriptorOwner()}, and each owner's descriptor is read from the artifacts that hold
     * {@code META-INF/yano/contribution-kinds/<owner>.json} on that loader. This mirrors Yano's
     * package-private {@code org.yanoproject.runtime.plugins.PluginKinds.load}; keep the two in step.
     */
    public static ContributionKindRegistry hostRegistry(ClassLoader hostLoader) throws IOException {
        Objects.requireNonNull(hostLoader, "hostLoader");
        Set<String> owners = new TreeSet<>();
        for (ContributionKindBinding<?> binding : ServiceLoader.load(ContributionKindBinding.class, hostLoader)) {
            owners.add(Objects.requireNonNull(binding.descriptorOwner(),
                    "Contribution kind binding '" + binding.manifestKey() + "' names no descriptor owner"));
        }
        Set<Path> artifacts = new LinkedHashSet<>();
        for (String owner : owners) {
            String path = ContributionKindRegistry.resourcePath(owner);
            Enumeration<URL> descriptors = hostLoader.getResources(path);
            if (!descriptors.hasMoreElements()) {
                throw new IllegalStateException("Contribution kind bindings name descriptor owner '" + owner
                        + "' but the host class path has no " + path);
            }
            while (descriptors.hasMoreElements()) {
                artifacts.add(artifact(descriptors.nextElement(), path));
            }
        }
        return ContributionKindRegistry.fromHostArtifacts(artifacts);
    }

    /** The JAR file or exploded root that holds a class-path resource. */
    private static Path artifact(URL resource, String resourcePath) throws IOException {
        try {
            if ("jar".equals(resource.getProtocol())) {
                JarURLConnection connection = (JarURLConnection) resource.openConnection();
                connection.setUseCaches(false);
                URL jar = connection.getJarFileURL();
                if ("file".equals(jar.getProtocol())) return Path.of(jar.toURI());
            } else if ("file".equals(resource.getProtocol())) {
                Path root = Path.of(resource.toURI());
                for (int segment = 0; segment < resourcePath.split("/").length; segment++) {
                    root = root.getParent();
                    if (root == null) throw new IOException("Class-path descriptor has no artifact root");
                }
                return root;
            }
        } catch (URISyntaxException invalid) {
            throw new IOException("Class-path descriptor artifact URL is invalid", invalid);
        }
        throw new IOException("Class-path resource " + resourcePath
                + " must belong to a file JAR or exploded artifact");
    }
}
