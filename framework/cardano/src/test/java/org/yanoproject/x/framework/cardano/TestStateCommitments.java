package org.yanoproject.x.framework.cardano;

import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentProfiles;

/** Explicit authenticated-state identities used by runtime unit tests. */
final class TestStateCommitments {
    static final StateCommitmentIdentity MPF = StateCommitmentIdentity.explicit(
            StateCommitmentProfiles.MPF, new byte[32]);

    private TestStateCommitments() {
    }
}
