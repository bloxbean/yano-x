package org.yanoproject.x.eutxo.ledger;

import org.yanoproject.api.plugin.domain.DomainApi;
import org.yanoproject.api.plugin.domain.DomainApiContext;
import org.yanoproject.api.plugin.domain.DomainApiProvider;

/** Read-only transaction explorer API for the bundled EUTxO state machine. */
public final class EutxoDomainApiProvider implements DomainApiProvider {
    public static final String ID =
            "org.yanoproject.x.eutxo";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return new EutxoDomainApi(context);
    }
}
