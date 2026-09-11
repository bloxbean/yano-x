package org.yanoproject.x.eutxo.indexer;

public interface EutxoIndexStoreProvider {
    String type();

    EutxoIndexStore open(EutxoIndexStoreContext context);
}
