package org.yanoproject.x.eutxo.indexer;

import org.yanoproject.x.eutxo.contracts.EutxoDepositRecord;
import org.yanoproject.x.eutxo.contracts.EutxoTransactionSummary;
import org.yanoproject.x.eutxo.contracts.EutxoWithdrawalRecord;

import java.util.Optional;

public interface EutxoIndexReader {
    IndexCheckpoint checkpoint();

    Optional<EutxoTransactionSummary> transaction(String transactionId);

    Optional<EutxoTransactionSummary> message(String messageId);

    IndexPage<EutxoTransactionSummary> transactions(long before, int limit);

    Optional<EutxoDepositRecord> deposit(String acceptedOutpoint);

    IndexPage<EutxoDepositRecord> deposits(long before, int limit);

    Optional<EutxoWithdrawalRecord> withdrawal(String claimId);

    IndexPage<EutxoWithdrawalRecord> withdrawals(long before, int limit);

    IndexedAccount account(String address, int activityLimit);

    EutxoLineage lineage(String transactionId, int maximumDepth, int maximumNodes);

    /** Stable digest used by conformance and cross-node comparison. */
    String normalizedDigest();
}
