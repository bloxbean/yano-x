package org.yanoproject.x.roles.contracts;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagedActorCommandV1Test {
    @Test
    void wrapperPreservesFrozenSignedCommandAndDefensivelyCopiesAction() {
        SignedActorCommandV1 signed = command(ActorStatementV1.Action.PROPOSE);
        byte[] action = {1, 2, 3};
        var staged = new StagedActorCommandV1(signed, action);
        byte[] encoded = staged.encode();
        assertThat(encoded[0]).isEqualTo((byte) 0x83);
        assertThat(encoded[1]).isEqualTo((byte) 1);
        action[0] = 9;
        staged.action()[0] = 9;
        var decoded = StagedActorCommandV1.decode(encoded);
        assertThat(decoded.action()).containsExactly(1, 2, 3);
        assertThat(decoded.command().encode()).isEqualTo(signed.encode());
        assertThat(decoded.encode()).isEqualTo(encoded);
        assertThat(new String(StagedActorCommandV1.stateKey("proposal"), StandardCharsets.US_ASCII))
                .isEqualTo("q-action/v1/proposal");
    }

    @Test
    void onlyProposeCarriesBoundedActionBytes() {
        assertThatThrownBy(() -> new StagedActorCommandV1(command(ActorStatementV1.Action.PROPOSE), new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StagedActorCommandV1(command(ActorStatementV1.Action.PROPOSE),
                new byte[StagedActorCommandV1.MAX_ACTION_BYTES + 1])).isInstanceOf(IllegalArgumentException.class);
        assertThat(StagedActorCommandV1.decode(new StagedActorCommandV1(command(ActorStatementV1.Action.PROPOSE),
                new byte[StagedActorCommandV1.MAX_ACTION_BYTES]).encode()).action())
                .hasSize(StagedActorCommandV1.MAX_ACTION_BYTES);
        for (var action : new ActorStatementV1.Action[]{ActorStatementV1.Action.APPROVE,
                ActorStatementV1.Action.REJECT, ActorStatementV1.Action.CANCEL}) {
            assertThatThrownBy(() -> new StagedActorCommandV1(command(action), new byte[]{1}))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(StagedActorCommandV1.decode(new StagedActorCommandV1(command(action), new byte[0]).encode())
                    .action()).isEmpty();
        }
    }

    @Test
    void rejectsAlternateTrailingUnsupportedAndOversizedWireForms() {
        byte[] valid = new StagedActorCommandV1(command(ActorStatementV1.Action.PROPOSE), new byte[]{1}).encode();
        byte[] alternate = new byte[valid.length + 1];
        alternate[0] = valid[0];
        alternate[1] = 0x18;
        alternate[2] = 1;
        System.arraycopy(valid, 2, alternate, 3, valid.length - 2);
        assertThatThrownBy(() -> StagedActorCommandV1.decode(alternate)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StagedActorCommandV1.decode(Arrays.copyOf(valid, valid.length + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] unsupported = valid.clone();
        unsupported[1] = 2;
        assertThatThrownBy(() -> StagedActorCommandV1.decode(unsupported)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StagedActorCommandV1.decode(new byte[StagedActorCommandV1.MAX_ENCODED_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SignedActorCommandV1 command(ActorStatementV1.Action action) {
        return SignedActorCommandV1.sign(new ActorStatementV1(action, "chain", "proposal", "policy", 1,
                "action.v1", new byte[32], 20, "actor", 1, "key",
                action == ActorStatementV1.Action.APPROVE || action == ActorStatementV1.Action.REJECT ? "clause" : ""),
                new byte[32]);
    }
}
