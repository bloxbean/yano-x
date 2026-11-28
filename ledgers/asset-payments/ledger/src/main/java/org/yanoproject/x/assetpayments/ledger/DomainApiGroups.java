package org.yanoproject.x.assetpayments.ledger;

import org.yanoproject.x.framework.api.plugin.domain.DomainApi;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiException;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiRequest;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiResponse;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiRoute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bundle's one domain API (schema v1 allows one {@code domain-api} contribution per bundle id) made of separate
 * route groups, each its own {@link DomainApi} with its own route ids and paths: a request goes to the group that
 * declared its route id. Route ids must be distinct across groups.
 */
final class DomainApiGroups implements DomainApi {
    private final List<DomainApi> groups;
    private final List<DomainApiRoute> routes;
    private final Map<String, DomainApi> byRoute = new LinkedHashMap<>();

    DomainApiGroups(List<DomainApi> groups) {
        this.groups = List.copyOf(groups);
        List<DomainApiRoute> all = new ArrayList<>();
        for (DomainApi group : this.groups) {
            for (DomainApiRoute route : group.routes()) {
                if (byRoute.putIfAbsent(route.routeId(), group) != null) {
                    throw new IllegalArgumentException("duplicate domain API route id " + route.routeId());
                }
                all.add(route);
            }
        }
        this.routes = List.copyOf(all);
    }

    @Override
    public List<DomainApiRoute> routes() {
        return routes;
    }

    @Override
    public DomainApiResponse handle(DomainApiRequest request) throws Exception {
        DomainApi group = byRoute.get(request.routeId());
        if (group == null) {
            throw new DomainApiException(DomainApiException.Code.INVALID_REQUEST, "Unknown payments route");
        }
        return group.handle(request);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (DomainApi group : groups) {
            try {
                group.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }
}
