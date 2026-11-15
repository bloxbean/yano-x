package org.yanoproject.x.framework.runtime.appchain;

import org.rocksdb.WriteBatch;
import org.yanoproject.x.framework.api.appchain.state.PreparedStateCommit;

/** Runtime extension that stages a frozen backend update into the shared ledger batch. */
interface StagedStateCommit extends PreparedStateCommit {
    void stage(WriteBatch batch);
}
