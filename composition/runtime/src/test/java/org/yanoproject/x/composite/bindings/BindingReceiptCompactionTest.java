package org.yanoproject.x.composite.bindings;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dense rejection traces retain the binding/clause diagnosis even when unrelated trace history cannot fit. */
class BindingReceiptCompactionTest {
    private static final String LONG_NAME = "a".repeat(127);
    private static final List<String> EVENTS = Collections.nCopies(257, LONG_NAME);
    private static final List<BindingReceiptV1.Condition> CONDITIONS = Collections.nCopies(
            256, new BindingReceiptV1.Condition(LONG_NAME, 7));

    @Test
    void highestOrdinalFailureIsNotDiscardedWithOversizedHistory() {
        var history = new BindingReceiptV1.Step(0, 0, null, "source", new byte[32], EVENTS,
                CONDITIONS, "PLANNED", "", false);
        var failed = new BindingReceiptV1.Step(1, 1, "rejected-binding", "target", new byte[32], List.of(),
                List.of(new BindingReceiptV1.Condition("rejected-binding", 3)),
                "REJECTED", "EXPRESSION_DIVISION_BY_ZERO", true);
        var trace = new ArrayList<>(List.of(history, failed));
        assertThatThrownBy(() -> receipt(trace, 1).encode()).isInstanceOf(IllegalArgumentException.class);

        EventBindingWorkflow.compactFailureTrace(trace, 1);

        var decoded = BindingReceiptV1.decode(receipt(trace, 1).encode());
        assertThat(decoded.failedStepOrdinal()).isEqualTo(1);
        assertThat(decoded.steps()).singleElement().satisfies(step -> {
            assertThat(step.bindingId()).isEqualTo(failed.bindingId());
            assertThat(step.code()).isEqualTo(failed.code());
            assertThat(step.messageId()).containsExactly(failed.messageId());
        });
        assertThat(decoded.steps().getFirst().conditions())
                .containsExactly(new BindingReceiptV1.Condition("rejected-binding", 3));
    }

    @Test
    void singleOversizedFailedStepDropsOnlyEventNamesAndKeepsEveryCondition() {
        var failed = new BindingReceiptV1.Step(17, 3, LONG_NAME, "target", new byte[32], EVENTS,
                CONDITIONS, "REJECTED", "EXPRESSION_DIVISION_BY_ZERO", true);
        var trace = new ArrayList<>(List.of(failed));
        assertThatThrownBy(() -> receipt(trace, 17).encode()).isInstanceOf(IllegalArgumentException.class);

        EventBindingWorkflow.compactFailureTrace(trace, 17);

        byte[] encoded = receipt(trace, 17).encode();
        assertThat(encoded).hasSizeLessThanOrEqualTo(BindingReceiptV1.MAX_BYTES);
        var decoded = BindingReceiptV1.decode(encoded);
        assertThat(decoded.steps()).singleElement().satisfies(step -> {
            assertThat(step.ordinal()).isEqualTo(17);
            assertThat(step.depth()).isEqualTo(3);
            assertThat(step.bindingId()).isEqualTo(LONG_NAME);
            assertThat(step.targetComponentId()).isEqualTo("target");
            assertThat(step.messageId()).containsExactly(failed.messageId());
            assertThat(step.eventsProduced()).isEmpty();
            assertThat(step.conditions()).containsExactlyElementsOf(CONDITIONS);
            assertThat(step.status()).isEqualTo("REJECTED");
            assertThat(step.code()).isEqualTo("EXPRESSION_DIVISION_BY_ZERO");
            assertThat(step.rawBody()).isTrue();
        });
    }

    private static BindingReceiptV1 receipt(List<BindingReceiptV1.Step> trace, int failedOrdinal) {
        return new BindingReceiptV1(new byte[32], 1, false, failedOrdinal, "RECEIPT_CAPACITY_EXCEEDED", trace);
    }
}
