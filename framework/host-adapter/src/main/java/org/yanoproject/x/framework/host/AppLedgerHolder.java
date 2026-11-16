package org.yanoproject.x.framework.host;

import org.yanoproject.x.framework.api.appchain.AppChainGateways;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiGateway;
import org.yanoproject.x.framework.api.plugin.domain.LocalReadModelHost;

import java.util.Objects;
import java.util.Optional;

/**
 * Stable plugin service registered during {@code init} and populated by the assembly callback. It reports
 * "not assembled" before assembly and after the owning plugin closes; it lives as long as the plugin.
 */
final class AppLedgerHolder {
    private volatile Assembled assembled;

    void publish(Assembled value) {
        assembled = Objects.requireNonNull(value, "value");
    }

    void clear() {
        assembled = null;
    }

    Optional<Assembled> current() {
        return Optional.ofNullable(assembled);
    }

    record Assembled(AppChainGateways appChains, DomainApiGateway domainApis, LocalReadModelHost localReadModels) {
        Assembled {
            Objects.requireNonNull(appChains, "appChains");
            Objects.requireNonNull(domainApis, "domainApis");
            Objects.requireNonNull(localReadModels, "localReadModels");
        }
    }
}
