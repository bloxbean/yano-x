package org.yanoproject.x.framework.config;

import java.nio.file.Path;

/** Resolves the node-local app-chain storage root independently of L1 RocksDB state. */
public final class AppChainStoragePaths {
    /** Default of {@link AppChainPropertyKeys#STORAGE_PATH}. */
    public static final String DEFAULT_STORAGE_PATH = "appchain-chainstate";

    private AppChainStoragePaths() {
    }

    /** Relative app-chain paths use the process working directory, like L1 storage paths. */
    public static Path resolve(String l1StoragePath, String configuredAppChainPath) {
        Path l1Path = l1Path(l1StoragePath);
        String value;
        if (configuredAppChainPath == null) {
            value = DEFAULT_STORAGE_PATH;
        } else {
            value = configuredAppChainPath.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        AppChainPropertyKeys.STORAGE_PATH + " must not be blank");
            }
        }

        Path result = Path.of(value).toAbsolutePath().normalize();

        if (result.equals(l1Path)) {
            throw new IllegalArgumentException(
                    AppChainPropertyKeys.STORAGE_PATH
                            + " must be different from yano.storage.path");
        }
        return result;
    }

    private static Path l1Path(String l1StoragePath) {
        String value = l1StoragePath == null || l1StoragePath.isBlank()
                ? "./chainstate"
                : l1StoragePath.trim();
        return Path.of(value).toAbsolutePath().normalize();
    }
}
