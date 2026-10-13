package org.yanoproject.x.eutxo.bridge.cardano;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidTransactionsTest {
    @Test
    void skipsInvalidIndexesAndNullBodiesAndIgnoresIndexesItCannotPlace() {
        TransactionBody first = TransactionBody.builder().txHash("a1".repeat(32)).build();
        TransactionBody second = TransactionBody.builder().txHash("a2".repeat(32)).build();
        TransactionBody third = TransactionBody.builder().txHash("a3".repeat(32)).build();
        Block block = Block.builder()
                .transactionBodies(Arrays.asList(first, null, second, third))
                .invalidTransactions(Arrays.asList(2, null, -1, 99))
                .build();

        assertThat(ValidTransactions.of(block)).containsExactly(first, third);
        assertThat(ValidTransactions.of(Block.builder().transactionBodies(List.of(first)).build()))
                .containsExactly(first);
        assertThat(ValidTransactions.of(null)).isEmpty();
        assertThat(ValidTransactions.of(Block.builder().build())).isEmpty();
    }
}
