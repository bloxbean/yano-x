package org.yanoproject.x.eutxo.indexer;

import java.util.List;

/** Read-only source of validated, provider-neutral validity lifecycle facts. */
@FunctionalInterface
public interface EutxoValidityIndexSource {
    List<EutxoValidityBatchRecord> batches();
}
