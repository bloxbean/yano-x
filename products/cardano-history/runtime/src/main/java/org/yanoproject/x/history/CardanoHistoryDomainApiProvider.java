package org.yanoproject.x.history;

import org.yanoproject.x.framework.api.plugin.domain.DomainApi;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiContext;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiProvider;

import java.util.Objects;

/** Read-only, bundle-owned Cardano History query API. */
public final class CardanoHistoryDomainApiProvider implements DomainApiProvider {
    @Override
    public String id() {
        return CardanoHistoryProduct.BUNDLE_ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return new CardanoHistoryDomainApi(Objects.requireNonNull(context, "context"));
    }
}
