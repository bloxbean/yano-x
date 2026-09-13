package org.yanoproject.x.eutxo.bridge.cardano;

import org.yanoproject.api.plugin.domain.DomainApi;
import org.yanoproject.api.plugin.domain.DomainApiContext;
import org.yanoproject.api.plugin.domain.DomainApiProvider;

/** Cataloged transaction-building API for the Cardano EUTxO bridge. */
public final class EutxoBridgeDomainApiProvider implements DomainApiProvider {
    public static final String ID =
            "org.yanoproject.x.eutxo.bridge.cardano";

    @Override public String id() { return ID; }
    @Override public DomainApi create(DomainApiContext context) {
        return new EutxoBridgeDomainApi(context);
    }
}
