package org.yanoproject.x.stdlib;

import org.yanoproject.x.framework.api.plugin.domain.DomainApi;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiContext;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiProvider;

/** Bounded domain projection for the first-party authenticated-map profile. */
public final class AuthenticatedMapDomainApiProvider implements DomainApiProvider {
    public static final String ID = "org.yanoproject.x.stdlib";

    @Override public String id() { return ID; }

    @Override public DomainApi create(DomainApiContext context) {
        return new AuthenticatedMapDomainApi(context);
    }
}
