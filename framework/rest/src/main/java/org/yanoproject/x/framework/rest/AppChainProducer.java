package org.yanoproject.x.framework.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.yanoproject.runtime.assembly.Yano;
import org.yanoproject.x.framework.api.appchain.AppChainGateway;
import org.yanoproject.x.framework.api.appchain.AppChainGateways;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiAccess;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiGateway;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiResponse;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiRouteInfo;
import org.yanoproject.x.framework.api.plugin.domain.DomainHttpMethod;
import org.yanoproject.x.framework.host.AppLedgers;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * App-chain CDI producers. Every call resolves the application-ledger products through
 * {@link AppLedgers#of} on the application's {@link Yano}, so nothing is captured across a kernel
 * restart or before the plugin has assembled them (ADR-064 D6).
 */
@ApplicationScoped
public class AppChainProducer {

    private final Yano yano;

    @Inject
    public AppChainProducer(Yano yano) {
        this.yano = yano;
    }

    @Produces
    @ApplicationScoped
    public AppChainGateways createAppChainGateways() {
        return new AppChainGateways() {
            @Override
            public Optional<AppChainGateway> byId(String chainId) {
                return AppLedgers.of(yano).appChains().byId(chainId);
            }

            @Override
            public Collection<AppChainGateway> all() {
                return AppLedgers.of(yano).appChains().all();
            }
        };
    }

    @Produces
    @ApplicationScoped
    public DomainApiGateway createDomainApiGateway() {
        return new DomainApiGateway() {
            @Override
            public List<DomainApiRouteInfo> routes() {
                return AppLedgers.of(yano).domainApis().routes();
            }

            @Override
            public Optional<DomainApiAccess> access(String bundleId, DomainHttpMethod method,
                                                    String relativePath) {
                return AppLedgers.of(yano).domainApis().access(bundleId, method, relativePath);
            }

            @Override
            public DomainApiResponse dispatch(String bundleId, DomainHttpMethod method, String relativePath,
                                              Map<String, List<String>> queryParameters, byte[] body) {
                return AppLedgers.of(yano).domainApis()
                        .dispatch(bundleId, method, relativePath, queryParameters, body);
            }
        };
    }
}
