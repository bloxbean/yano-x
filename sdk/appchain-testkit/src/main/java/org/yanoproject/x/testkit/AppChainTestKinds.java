package org.yanoproject.x.testkit;

import org.yanoproject.catalog.ContributionKindRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The contribution kinds a host built from the test class path accepts: Yano's built-in kinds plus the
 * descriptor kinds of the contribution-kind bindings on that class path, by the host's own rule
 * ({@link ContributionKindRegistry#fromHostClassLoader(ClassLoader)}).
 */
public final class AppChainTestKinds {
    private AppChainTestKinds() {
    }

    public static ContributionKindRegistry classPathRegistry() {
        try {
            return ContributionKindRegistry.fromHostClassLoader(AppChainTestKinds.class.getClassLoader());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
