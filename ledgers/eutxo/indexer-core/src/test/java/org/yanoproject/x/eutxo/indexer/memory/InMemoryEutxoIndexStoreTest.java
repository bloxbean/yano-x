package org.yanoproject.x.eutxo.indexer.memory;

import org.yanoproject.x.eutxo.indexer.EutxoIndexStore;
import org.yanoproject.x.eutxo.indexer.IndexIdentity;
import org.yanoproject.x.eutxo.indexer.testing.EutxoIndexStoreConformance;

final class InMemoryEutxoIndexStoreTest extends EutxoIndexStoreConformance {
    @Override
    protected EutxoIndexStore open(IndexIdentity identity) {
        return new InMemoryEutxoIndexStore(identity);
    }
}
