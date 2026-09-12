package org.yanoproject.x.evidence.profile;

import org.yanoproject.api.plugin.domain.DomainApi;
import org.yanoproject.api.plugin.domain.DomainApiContext;
import org.yanoproject.api.plugin.domain.DomainApiProvider;

/** Read-only JSON API for the evidence profile's role-workflow exact queries. */
public final class RoleEvidenceDomainApiProvider implements DomainApiProvider {
    public static final String ID = "org.yanoproject.x.evidence-profile";

    @Override public String id() { return ID; }
    @Override public DomainApi create(DomainApiContext context) {
        return new RoleEvidenceDomainApi(context);
    }
}
