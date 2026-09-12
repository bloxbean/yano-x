package org.yanoproject.x.eutxo.indexer.jdbc;

import org.yanoproject.api.plugin.domain.DomainApi;
import org.yanoproject.api.plugin.domain.DomainApiContext;
import org.yanoproject.api.plugin.domain.DomainApiProvider;
import org.yanoproject.x.eutxo.indexer.api.EutxoIndexDomainApiProvider;

/** Bundle-local provider entry point for the storage-neutral index domain API. */
public final class EutxoJdbcIndexDomainApiProvider implements DomainApiProvider {
    private final EutxoIndexDomainApiProvider delegate = new EutxoIndexDomainApiProvider();

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return delegate.create(context);
    }
}
