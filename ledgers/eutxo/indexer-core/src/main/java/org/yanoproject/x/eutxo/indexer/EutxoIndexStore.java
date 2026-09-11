package org.yanoproject.x.eutxo.indexer;

public interface EutxoIndexStore extends AutoCloseable {
    IndexIdentity identity();

    EutxoIndexWrite begin(SourcePoint source);

    IndexCheckpoint checkpoint();

    void rollbackTo(SourcePoint source);

    EutxoIndexReader reader();

    @Override
    void close();
}
