package org.yanoproject.x.eutxo.zk.runtime;

import org.yanoproject.x.eutxo.ledger.EutxoDomainApi;
import org.yanoproject.x.framework.api.plugin.domain.DomainApi;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiContext;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiProvider;

/** EUTxO query surface owned by the integrated ZeroJ runtime bundle. */
public final class EutxoZkDomainApiProvider implements DomainApiProvider {
    public static final String ID =
            "org.yanoproject.x.eutxo.zk.runtime";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return new EutxoDomainApi(context);
    }
}
