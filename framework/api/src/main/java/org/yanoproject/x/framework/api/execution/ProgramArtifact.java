package org.yanoproject.x.framework.api.execution;

import java.util.Objects;

/**
 * Program bytes as pinned in genesis. The caller has already checked the bytes against the pinned
 * digest; the runtime only interprets them.
 *
 * @param mediaType for example {@code application/wasm} or {@code text/javascript}
 * @param abiVersion the guest ABI version the program is written against
 */
public record ProgramArtifact(String mediaType, int abiVersion, byte[] bytes) {

    public ProgramArtifact {
        Objects.requireNonNull(mediaType, "mediaType");
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
        if (abiVersion < 1) {
            throw new IllegalArgumentException("abiVersion must be >= 1");
        }
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int size() {
        return bytes.length;
    }
}
