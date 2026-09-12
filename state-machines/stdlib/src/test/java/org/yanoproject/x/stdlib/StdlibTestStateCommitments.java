package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;

import java.nio.charset.StandardCharsets;

final class StdlibTestStateCommitments {
    private StdlibTestStateCommitments() {
    }

    static StateCommitmentIdentity mpf(String chainId) {
        return StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF,
                Blake2bUtil.blake2bHash256(
                        ("stdlib-test-genesis-v1\0" + chainId)
                                .getBytes(StandardCharsets.UTF_8)));
    }
}
