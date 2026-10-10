package org.yanoproject.x.eutxo.bridge.cardano;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The transactions of an L1 block whose effects exist. A transaction whose Plutus script fails (phase-2 invalid) is
 * still included in the block, but only its collateral is taken: its inputs are not spent and its outputs are never
 * created. An observer that read such a transaction would credit a deposit that never reached the vault, or treat a
 * settlement that never happened as real, for the price of the collateral; so every bridge observer reads only these.
 */
final class ValidTransactions {
    private ValidTransactions() {
    }

    static List<TransactionBody> of(Block block) {
        if (block == null || block.getTransactionBodies() == null) {
            return List.of();
        }
        Set<Integer> invalid = new HashSet<>();
        if (block.getInvalidTransactions() != null) {
            for (Integer index : block.getInvalidTransactions()) {
                if (index != null) {
                    invalid.add(index);
                }
            }
        }
        List<TransactionBody> bodies = block.getTransactionBodies();
        List<TransactionBody> valid = new ArrayList<>(bodies.size());
        for (int index = 0; index < bodies.size(); index++) {
            if (!invalid.contains(index) && bodies.get(index) != null) {
                valid.add(bodies.get(index));
            }
        }
        return valid;
    }
}
