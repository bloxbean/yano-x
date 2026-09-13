package org.yanoproject.x.eutxo.indexer.api;

import org.yanoproject.api.plugin.domain.DomainApi;
import org.yanoproject.api.plugin.domain.DomainApiContext;
import org.yanoproject.api.plugin.domain.DomainApiProvider;
import org.yanoproject.x.eutxo.indexer.EutxoLocalReadModel;

public final class EutxoIndexDomainApiProvider implements DomainApiProvider {
    @Override
    public String id() {
        return EutxoLocalReadModel.MODEL_ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return new EutxoIndexDomainApi(context);
    }
}
