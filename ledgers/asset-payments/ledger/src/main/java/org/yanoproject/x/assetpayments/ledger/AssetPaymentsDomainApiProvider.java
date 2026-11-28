package org.yanoproject.x.assetpayments.ledger;

import org.yanoproject.x.framework.api.plugin.domain.DomainApi;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiContext;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiProvider;

/** The read-only payments domain API, served at {@code /api/v1/plugins/org.yanoproject.x.asset-payments/}. */
public final class AssetPaymentsDomainApiProvider implements DomainApiProvider {
    /** Schema v1: the domain contribution name, this id and the bundle id are the same. */
    public static final String ID = "org.yanoproject.x.asset-payments";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return new AssetPaymentsDomainApi(context);
    }
}
