package org.yanoproject.x.framework.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.yanoproject.runtime.assembly.Yano;
import org.yanoproject.x.framework.api.appchain.AppAnchorCommitment;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppChainGateway;
import org.yanoproject.x.framework.api.appchain.AppChainGateways;
import org.yanoproject.x.framework.api.appchain.MessageRef;
import org.yanoproject.x.framework.api.appchain.ReceivedAppMessage;
import org.yanoproject.x.framework.api.appchain.evidence.EvidenceBundle;
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
import java.util.Set;

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

    /** The single hosted chain of this request, or {@link UnavailableAppChainGateway} when there is none. */
    @Produces
    @RequestScoped
    public AppChainGateway createAppChainGateway() {
        return AppLedgers.of(yano).appChains().single().orElse(UnavailableAppChainGateway.INSTANCE);
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

    private static IllegalStateException unavailableRole(String role) {
        return new IllegalStateException(role + " is not available for this Yano assembly");
    }

    /**
     * Placeholder for CDI injection sites when the app chain is disabled.
     */
    private enum UnavailableAppChainGateway implements AppChainGateway {
        INSTANCE;

        @Override
        public String chainId() {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public String submit(String topic, byte[] body) {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public List<ReceivedAppMessage> recentMessages(int limit) {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public Map<String, Object> status() {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public long tipHeight() {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public Optional<AppBlock> block(long height) {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public byte[] stateRoot() {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public Optional<byte[]> stateValue(byte[] key) {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public Optional<byte[]> stateProof(byte[] key) {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public Optional<AppAnchorCommitment>
                latestAnchorCommitment() {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public Optional<Long> messageHeight(byte[] messageId) {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public AutoCloseable subscribeFinalized(FinalizedBlockListener listener) {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public Optional<EvidenceBundle>
                evidence(byte[] messageId) {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public long snapshot(String snapshotPath) {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public List<MessageRef> messagesByTopic(
                String topic, long fromHeight, int limit) {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public List<MessageRef> messagesBySender(
                byte[] sender, long fromHeight, int limit) {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public void pauseSubmissions() {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public void resumeSubmissions() {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public boolean submissionsPaused() {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public int drainPool() {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public boolean forceAnchor() {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public boolean rebaselineL1Delivery() {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public Set<String> members() {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public int effectiveThreshold() {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public void addMember(String publicKeyHex) {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public void removeMember(String publicKeyHex) {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public void setThreshold(int threshold) {
            throw unavailableRole("AppChainGateway");
        }

        @Override
        public void resetMembers() {
            throw unavailableRole("AppChainGateway");
        }
    }
}
