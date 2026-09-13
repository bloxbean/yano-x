package org.yanoproject.x.ipfs.internal.kubo;

import org.yanoproject.x.ipfs.internal.IpfsPinClient;
import org.yanoproject.x.ipfs.internal.IpfsPinClientFactory;

import java.util.Objects;

/** Opens Kubo HTTP clients bound to one validated target configuration. */
public final class KuboIpfsPinClientFactory implements IpfsPinClientFactory {
    private final KuboClientConfig config;

    /**
     * Creates a bound Kubo factory.
     *
     * @param config validated internal construction values
     */
    public KuboIpfsPinClientFactory(KuboClientConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public IpfsPinClient open() {
        return new KuboIpfsPinClient(config);
    }
}
