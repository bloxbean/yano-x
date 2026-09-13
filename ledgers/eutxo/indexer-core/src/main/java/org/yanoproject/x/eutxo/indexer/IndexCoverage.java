package org.yanoproject.x.eutxo.indexer;

/** Truthful coverage of the disposable read model. */
public enum IndexCoverage {
    FULL,
    PARTIAL,
    REBUILDING,
    FAILED
}
