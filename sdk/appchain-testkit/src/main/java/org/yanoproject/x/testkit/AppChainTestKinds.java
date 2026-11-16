package org.yanoproject.x.testkit;

import org.yanoproject.catalog.ContributionKindRegistry;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The contribution kinds a host built from the test class path accepts: Yano's
 * built-in kinds plus the X kinds declared by the framework descriptors
 * ({@code META-INF/yano/contribution-kinds.json}) on that class path.
 */
public final class AppChainTestKinds {
    private AppChainTestKinds() {
    }

    public static ContributionKindRegistry classPathRegistry() {
        try {
            return ContributionKindRegistry.fromHostArtifacts(Arrays.stream(
                            System.getProperty("java.class.path").split(File.pathSeparator))
                    .map(Path::of)
                    .filter(Files::exists)
                    .toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
