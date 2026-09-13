package org.yanoproject.x.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObserverConsensusIdentityTest {
    private static final byte[] VAULT_HASH = filled(28, 7);
    private static final String VAULT_ADDRESS = AddressProvider.getEntAddress(
            Credential.fromScript(VAULT_HASH), Networks.testnet()).getAddress();

    @Test
    void bridgeIdentityIsNormalizedAndBindsEveryDeterministicSetting() {
        CardanoBridgeObserverProvider provider = new CardanoBridgeObserverProvider();
        Map<String, String> normalized = Map.of(
                "chain-id", "payments",
                "vault-address", VAULT_ADDRESS,
                "vault-script-hash", HexFormat.of().formatHex(VAULT_HASH),
                "max-lovelace", "100");
        Map<String, String> equivalent = Map.of(
                "chain-id", " payments ",
                "vault-address", " " + VAULT_ADDRESS + " ",
                "vault-script-hash", HexFormat.of().formatHex(VAULT_HASH),
                "max-lovelace", "00100");

        byte[] expected = provider.consensusIdentity("bridge", normalized)
                .canonicalIdentityBytes();

        assertThat(provider.consensusIdentity("bridge", equivalent).canonicalIdentityBytes())
                .containsExactly(expected);
        assertThat(provider.consensusIdentity("bridge", withMax(normalized, "101"))
                .canonicalIdentityBytes()).isNotEqualTo(expected);
    }

    @Test
    void withdrawalProvidersDeclareCanonicalIdentity() {
        Map<String, String> settings = Map.of(
                "chain-id", "payments",
                "bridge-epoch", "0",
                "vault-address", VAULT_ADDRESS);

        assertThat(new CardanoWithdrawalConfirmationObserverProvider()
                .consensusIdentity("withdrawal", settings).canonicalIdentityBytes())
                .isNotEmpty();
        assertThat(new CardanoBatchWithdrawalConfirmationObserverProvider()
                .consensusIdentity("batch", settings).canonicalIdentityBytes())
                .isNotEmpty();
    }

    private static Map<String, String> withMax(Map<String, String> settings, String max) {
        return Map.of(
                "chain-id", settings.get("chain-id"),
                "vault-address", settings.get("vault-address"),
                "vault-script-hash", settings.get("vault-script-hash"),
                "max-lovelace", max);
    }

    private static byte[] filled(int size, int value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
