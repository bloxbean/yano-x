package org.yanoproject.x.eutxo.indexer.jdbc;

import org.yanoproject.x.eutxo.indexer.EutxoIndexStore;
import org.yanoproject.x.eutxo.indexer.EutxoIndexStoreContext;
import org.yanoproject.x.eutxo.indexer.EutxoIndexStoreProvider;

public final class SqliteEutxoIndexStoreProvider
        implements EutxoIndexStoreProvider {
    public static final String TYPE = "jdbc";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public EutxoIndexStore open(EutxoIndexStoreContext context) {
        return SqliteEutxoIndexStore.open(context);
    }
}
