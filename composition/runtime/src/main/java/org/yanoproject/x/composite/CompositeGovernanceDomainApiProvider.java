package org.yanoproject.x.composite;

import org.yanoproject.api.plugin.domain.DomainApi;
import org.yanoproject.api.plugin.domain.DomainApiContext;
import org.yanoproject.api.plugin.domain.DomainApiProvider;

/** Cataloged operator API for governed composite-profile commands. */
public final class CompositeGovernanceDomainApiProvider
        implements DomainApiProvider {
    public static final String ID =
            "org.yanoproject.x.composite";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return new CompositeGovernanceDomainApi(context);
    }
}
