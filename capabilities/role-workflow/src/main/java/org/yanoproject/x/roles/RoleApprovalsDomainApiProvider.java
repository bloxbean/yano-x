package org.yanoproject.x.roles;

import org.yanoproject.api.plugin.domain.DomainApi;
import org.yanoproject.api.plugin.domain.DomainApiContext;
import org.yanoproject.api.plugin.domain.DomainApiProvider;

/** Read-only JSON API for the stock role-approvals profile. */
public final class RoleApprovalsDomainApiProvider implements DomainApiProvider {
    public static final String ID = "org.yanoproject.x.role-workflow";

    @Override public String id() { return ID; }
    @Override public DomainApi create(DomainApiContext context) {
        return new RoleApprovalsDomainApi(context);
    }
}
