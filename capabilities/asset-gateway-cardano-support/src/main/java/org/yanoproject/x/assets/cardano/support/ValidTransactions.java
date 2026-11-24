package org.yanoproject.x.assets.cardano.support;

import com.bloxbean.cardano.yaci.core.model.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The transactions of an L1 block whose regular effects exist (ADR-059 D5 "only phase-2-valid
 * transactions are read"). A phase-2-invalid transaction stays in the block, but only its
 * collateral is consumed and only its collateral-return output is created: its regular outputs
 * never exist. An observer that read them would report a deposit that never reached the vault.
 */
public final class ValidTransactions {
    private ValidTransactions() {
    }

    /** One phase-2-valid transaction body and its position in the block. */
    public record Indexed(int blockIndex, com.bloxbean.cardano.yaci.core.model.TransactionBody body) { }

    /** The valid transactions in block order, skipping null bodies and the block's invalid indexes. */
    public static List<Indexed> of(Block block) {
        if (block == null || block.getTransactionBodies() == null) return List.of();
        Set<Integer> invalid = new HashSet<>();
        if (block.getInvalidTransactions() != null) {
            for (Integer index : block.getInvalidTransactions()) {
                if (index != null) invalid.add(index);
            }
        }
        var bodies = block.getTransactionBodies();
        List<Indexed> valid = new ArrayList<>(bodies.size());
        for (int index = 0; index < bodies.size(); index++) {
            if (!invalid.contains(index) && bodies.get(index) != null) valid.add(new Indexed(index, bodies.get(index)));
        }
        return List.copyOf(valid);
    }
}
