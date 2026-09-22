package org.yanoproject.x.roles;

import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.transition.TransitionWorkAccounting;
import org.yanoproject.api.appchain.transition.TransitionWorkBudget;
import org.yanoproject.x.roles.contracts.RoleWorkflowKeys;


/** Shared, block-scoped governed-signature work fence stored in actor state. */
public final class GovernedCryptoWork {
    /** Shared identity advertised by the actor-owner kernel and referenced by governed callers. */
    public static final String BUDGET_ID = "governed-crypto-v1";

    private GovernedCryptoWork() {
    }

    /** Returns the owner-local descriptor; its limit comes only from committed governed genesis. */
    public static TransitionWorkBudget budget(int maximumUnits) {
        return new TransitionWorkBudget(BUDGET_ID, RoleWorkflowKeys.cryptoWork(), maximumUnits);
    }

    /**
     * Reserves signature work before verification using the same counter as declarative cascades.
     * A false result does not mutate state. Successful charges survive business rejection, while the
     * enclosing host block transaction remains responsible for infrastructure-failure rollback.
     */
    public static boolean reserve(
            AppStateWriter actorState,
            long height,
            int units,
            int maximumUnits
    ) {
        return TransitionWorkAccounting.reserve(actorState, budget(maximumUnits), height, units);
    }
}
