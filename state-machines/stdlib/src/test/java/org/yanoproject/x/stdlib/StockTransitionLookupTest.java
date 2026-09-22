package org.yanoproject.x.stdlib;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.x.stdlib.contracts.ApprovalsContract;
import org.yanoproject.x.stdlib.contracts.BalancesContract;
import org.yanoproject.x.stdlib.contracts.DocTrailContract;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockTransitionLookupTest {
    private final TransitionKernel<?, ?> approvals = StockTransitionKernels.approvals(new ApprovalsTransitions());
    private final TransitionKernel<?, ?> balances = StockTransitionKernels.balances(new BalancesTransitions(""));
    private final TransitionKernel<?, ?> documents = StockTransitionKernels.documentTrail(new DocTrailTransitions());

    @Test
    void logicalUtf8IdentifiersResolveThroughFrozenDomainHelpers() {
        String id = "invoice/é-123";
        byte[] input = id.getBytes(StandardCharsets.UTF_8);
        assertThat(approvals.lookupKey(input)).isEqualTo(ApprovalsContract.itemKey(id));
        assertThat(balances.lookupKey(input)).isEqualTo(BalancesContract.accountKey(id));
        assertThat(documents.lookupKey(input)).isEqualTo(DocTrailContract.entityKey(id));
        byte[] key = documents.lookupKey(input);
        key[0] = 0;
        assertThat(documents.lookupKey(input)).isEqualTo(DocTrailContract.entityKey(id));
        assertThat(input).isEqualTo(id.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void malformedUtf8EmptyAndOversizedIdentifiersFailBeforeStateLookup() {
        for (var kernel : List.of(approvals, balances, documents)) {
            for (byte[] invalid : new byte[][]{new byte[0], new byte[]{(byte) 0xc0, (byte) 0xaf},
                    new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80}, new byte[]{(byte) 0xff}, new byte[257]}) {
                assertThatThrownBy(() -> kernel.lookupKey(invalid)).isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> kernel.lookupKey(null)).isInstanceOf(IllegalArgumentException.class);
            // Domain prefixes consume two bytes of the existing 256-byte physical-key bound.
            assertThat(kernel.lookupKey("a".repeat(254).getBytes(StandardCharsets.UTF_8))).hasSize(256);
            assertThatThrownBy(() -> kernel.lookupKey("a".repeat(255).getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void registryKeepsOpaqueIdentityKeysWithoutUtf8Reinterpretation() {
        var registry = StockTransitionKernels.registry(
                new KvRegistryTransitions(KvRegistryTransitions.ValueFormat.RAW));
        byte[] logical = {(byte) 0xff, 0, 1};
        byte[] physical = registry.lookupKey(logical);
        assertThat(physical).isEqualTo(logical).isNotSameAs(logical);
        physical[0] = 0;
        assertThat(registry.lookupKey(logical)).containsExactly((byte) 0xff, (byte) 0, (byte) 1);
    }
}
