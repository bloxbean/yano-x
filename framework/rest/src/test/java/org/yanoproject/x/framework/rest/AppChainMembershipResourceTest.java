package org.yanoproject.x.framework.rest;

import org.yanoproject.x.framework.api.appchain.AppChainGateway;
import org.yanoproject.x.framework.api.appchain.MembershipChangeRejectedException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** bloxbean/yano#163: an uncertifiable governed membership change is a coded 400. */
class AppChainMembershipResourceTest {
    private static final MembershipChangeRejectedException REJECTED = new MembershipChangeRejectedException(
            MembershipChangeRejectedException.QUORUM_INVALID, "A 2-of-4 membership breaks the quorum rules");

    @Test
    void quorumRejectionCarriesItsStableCode() {
        AppChainResource.ChainScopedResource resource = resource();
        String key = "aa".repeat(32);
        assertCoded(resource.addMember(new AppChainResource.ChainScopedResource.MemberRequest(key)));
        assertCoded(resource.removeMember(new AppChainResource.ChainScopedResource.MemberRequest(key)));
        assertCoded(resource.setThreshold(new AppChainResource.ChainScopedResource.ThresholdRequest(2)));
    }

    /** ADR-059 D8b: the handoff cancel is reachable next to the other governance endpoints. */
    @Test
    void membershipHandoffCancelSubmitsOrExplainsWhyNot() {
        java.util.concurrent.atomic.AtomicReference<Object> outcome = new java.util.concurrent.atomic.AtomicReference<>(42L);
        AppChainGateway gateway = (AppChainGateway) Proxy.newProxyInstance(AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "chainId" -> "chain";
                    case "cancelPendingMembershipHandoff" -> {
                        if (outcome.get() instanceof RuntimeException failure) {
                            throw failure;
                        }
                        yield outcome.get();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AppChainResource.ChainScopedResource resource = new AppChainResource.ChainScopedResource(gateway);
        try (Response response = resource.cancelMembershipHandoff()) {
            assertEquals(200, response.getStatus());
            assertEquals(Map.of("chainId", "chain", "cancelSubmitted", true, "committedHeight", 42L),
                    response.getEntity());
        }
        outcome.set(new IllegalStateException("No membership anchor handoff is pending"));
        try (Response response = resource.cancelMembershipHandoff()) {
            assertEquals(400, response.getStatus());
        }
        // A rejected submission is a 400 like the sibling member endpoints, never a 500.
        outcome.set(new IllegalArgumentException("bad governance command"));
        try (Response response = resource.cancelMembershipHandoff()) {
            assertEquals(400, response.getStatus());
        }
        outcome.set(REJECTED);
        assertCoded(resource.cancelMembershipHandoff());
    }

    private static void assertCoded(Response response) {
        try (response) {
            assertEquals(400, response.getStatus());
            assertEquals(Map.of("code", "MEMBERSHIP_QUORUM_INVALID",
                    "error", "A 2-of-4 membership breaks the quorum rules"), response.getEntity());
        }
    }

    private static AppChainResource.ChainScopedResource resource() {
        AppChainGateway gateway = (AppChainGateway) Proxy.newProxyInstance(AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class}, (proxy, method, args) -> {
                    if (Set.of("addMember", "removeMember", "setThreshold").contains(method.getName())) {
                        throw REJECTED;
                    }
                    throw new AssertionError("Unexpected gateway operation: " + method.getName());
                });
        return new AppChainResource.ChainScopedResource(gateway);
    }
}
