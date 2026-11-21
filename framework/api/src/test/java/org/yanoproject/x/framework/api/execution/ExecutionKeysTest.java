package org.yanoproject.x.framework.api.execution;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.framework.api.appchain.effects.FxKeys;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionKeysTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void receiptKeysLieInTheReservedNamespace() {
        assertThat(ExecutionKeys.messageReceiptKey(new byte[] {1, 2})).isEqualTo(b("~exec/r/\u0001\u0002"));
        assertThat(ExecutionKeys.effectReceiptKey(new byte[] {9})).isEqualTo(b("~exec/fx/\t"));
        assertThat(ExecutionKeys.isReserved(ExecutionKeys.messageReceiptKey(new byte[32]))).isTrue();
        assertThat(ExecutionKeys.isReserved(ExecutionKeys.effectReceiptKey(new byte[32]))).isTrue();
    }

    @Test
    void reservationIsExactlyThePrefix() {
        assertThat(ExecutionKeys.isReserved(b("~exec/"))).isTrue();
        assertThat(ExecutionKeys.isReserved(b("~exec"))).isFalse();
        assertThat(ExecutionKeys.isReserved(b("~execx/"))).isFalse();
        assertThat(ExecutionKeys.isReserved(b("exec/r/"))).isFalse();
        assertThat(ExecutionKeys.isReserved(null)).isFalse();
        // Disjoint from the effect system's namespace in both directions.
        assertThat(ExecutionKeys.isReserved(b("~fx/root/"))).isFalse();
        assertThat(FxKeys.isReserved(ExecutionKeys.effectReceiptKey(new byte[32]))).isFalse();
    }
}
