package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;

/** Bounded domain projection for the first-party authenticated-map profile. */
public final class AuthenticatedMapDomainApiProvider implements DomainApiProvider {
    public static final String ID = "org.yanoproject.x.stdlib";

    @Override public String id() { return ID; }

    @Override public DomainApi create(DomainApiContext context) {
        return new AuthenticatedMapDomainApi(context);
    }
}
