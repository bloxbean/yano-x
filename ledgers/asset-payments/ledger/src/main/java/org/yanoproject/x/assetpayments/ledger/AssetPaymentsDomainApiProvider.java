package org.yanoproject.x.assetpayments.ledger;

import org.yanoproject.x.framework.api.plugin.domain.DomainApi;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiContext;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiProvider;

import java.util.List;

/**
 * The payments domain API, served at {@code /api/v1/plugins/org.yanoproject.x.asset-payments/}: the bundle's route
 * groups combined by {@link DomainApiGroups}. Those are the read-only payments routes ({@link AssetPaymentsDomainApi})
 * and the members' governance route group ({@link PaymentsGovernanceDomainApi}); other groups are added to the list.
 */
public final class AssetPaymentsDomainApiProvider implements DomainApiProvider {
    /** Schema v1: the domain contribution name, this id and the bundle id are the same. */
    public static final String ID = "org.yanoproject.x.asset-payments";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return new DomainApiGroups(List.of(new AssetPaymentsDomainApi(context),
                new PaymentsGovernanceDomainApi(context)));
    }
}
