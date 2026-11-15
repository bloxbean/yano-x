package org.yanoproject.x.framework.api.appchain;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.framework.api.appchain.codec.AppBlockCodec;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.x.proofs.commitment.FinalizedAppCommitmentV1;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinalizedCommitmentExportTest {
    private static final StateCommitmentIdentity IDENTITY = StateCommitmentIdentity.explicit(
            StateCommitmentProfiles.MPF, bytes(7));

    @Test
    void exportsExistingHashRootAndContextWithoutRewritingTheBlock() {
        AppBlock block = block("orders", 42);
        byte[] encodedBefore = AppBlockCodec.serialize(block);
        var exported = gateway(block, IDENTITY).finalizedCommitment(42).orElseThrow();
        assertThat(exported.blockHash()).containsExactly(AppBlockCodec.blockHash(block));
        assertThat(exported.stateRoot()).containsExactly(block.stateRoot());
        assertThat(exported.consensusContextDigest()).containsExactly(block.consensusContextDigest());
        assertThat(exported.genesisId()).containsExactly(IDENTITY.genesisId());
        assertThat(exported.formatFingerprint()).containsExactly(IDENTITY.profile().formatFingerprint());
        assertThat(exported.chainId()).isEqualTo("orders");
        assertThat(exported.height()).isEqualTo(42);
        assertThat(FinalizedAppCommitmentV1.decode(exported.encode())).isEqualTo(exported);
        assertThat(AppBlockCodec.serialize(block)).containsExactly(encodedBefore);
    }

    @Test
    void doesNotInventIdentityOrHistoryForLegacyOrUnavailableGateways() {
        assertThat(gateway(block("orders", 42), null).finalizedCommitment(42)).isEmpty();
        assertThat(gateway(null, IDENTITY).finalizedCommitment(42)).isEmpty();
        assertThatThrownBy(() -> gateway(null, IDENTITY).finalizedCommitment(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesMismatchedChainOrHeight() {
        assertThatThrownBy(() -> gateway(block("foreign", 42), IDENTITY).finalizedCommitment(42))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateway(block("orders", 41), IDENTITY).finalizedCommitment(42))
                .isInstanceOf(IllegalStateException.class);
    }

    private static AppChainGateway gateway(AppBlock block, StateCommitmentIdentity identity) {
        return (AppChainGateway) Proxy.newProxyInstance(AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class}, (proxy, method, args) -> {
                    if (method.isDefault() && method.getName().equals("finalizedCommitment")) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    return switch (method.getName()) {
                        case "chainId" -> "orders";
                        case "stateCommitmentIdentity" -> Optional.ofNullable(identity);
                        case "block" -> Optional.ofNullable(block);
                        default -> throw new AssertionError("Export attempted another operation: " + method);
                    };
                });
    }

    private static AppBlock block(String chain, long height) {
        return new AppBlock(AppBlock.BLOCK_VERSION, chain, height, bytes(1), 0,
                bytes(2), 0, new byte[0], 1000, bytes(3), bytes(4), List.of(),
                bytes(5), new byte[0], FinalityCert.empty());
    }

    private static byte[] bytes(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
