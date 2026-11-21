package org.yanoproject.x.execution.abi.v1;

import java.util.Optional;

/** Read access to state for host functions. Implementations return copies. */
@FunctionalInterface
public interface StateView {
    Optional<byte[]> get(byte[] key);
}
