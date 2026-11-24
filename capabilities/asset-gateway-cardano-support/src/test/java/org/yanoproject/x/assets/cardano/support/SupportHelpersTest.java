package org.yanoproject.x.assets.cardano.support;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.CardanoValueCbor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportHelpersTest {
    static final String POLICY = "f0".repeat(28);
    static final AssetId USDM = AssetId.ofHex(POLICY, "5553444d");

    static Amount lovelace(long quantity) {
        return Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(quantity)).build();
    }

    static Amount token(String policy, String nameHex, BigInteger quantity) {
        return Amount.builder().unit(policy + "." + nameHex).policyId(policy)
                .assetNameBytes(HexFormat.of().parseHex(nameHex)).quantity(quantity).build();
    }

    @Test
    void preflightAcceptsBoundedItemsAndRejectsDeepOrMalformedOnes() {
        DatumPreflight preflight = DatumPreflight.defaults();
        assertThat(preflight.accepts(HexFormat.of().parseHex("d87983581c" + "00".repeat(28) + "41004100"))).isTrue();
        assertThat(preflight.accepts(HexFormat.of().parseHex("d8799f5f4100ff4100ff"))).isTrue();
        assertThat(preflight.accepts(HexFormat.of().parseHex("a1010203"))).isFalse(); // trailing
        assertThat(preflight.accepts(HexFormat.of().parseHex("8301"))).isFalse(); // truncated
        assertThat(preflight.accepts(HexFormat.of().parseHex("ff"))).isFalse();
        assertThat(preflight.accepts(HexFormat.of().parseHex("5f6100ff"))).isFalse(); // text chunk in bytes
        assertThat(preflight.accepts(HexFormat.of().parseHex("5b7fffffffffffffff"))).isFalse(); // huge length
        assertThat(preflight.accepts(new byte[0])).isFalse();
        // 100 000 nested arrays: a recursive decoder would overflow the stack; this one just says no.
        byte[] deep = new byte[100_001];
        Arrays.fill(deep, (byte) 0x81);
        deep[deep.length - 1] = 0;
        assertThat(new DatumPreflight(65_536, 16).accepts(Arrays.copyOf(deep, 60_000))).isFalse();
        byte[] sixteen = new byte[17];
        Arrays.fill(sixteen, (byte) 0x81);
        sixteen[16] = 0;
        assertThat(preflight.accepts(sixteen)).isTrue();
        byte[] seventeen = new byte[18];
        Arrays.fill(seventeen, (byte) 0x81);
        seventeen[17] = 0;
        assertThat(preflight.accepts(seventeen)).isFalse();
        assertThat(preflight.accepts(new byte[DatumPreflight.DEFAULT_MAX_BYTES + 1])).isFalse();
    }

    @Test
    void outputValuesSummariseTheRegisteredAssetAndHashTheFullValue() {
        OutputValues.ObservedValue value = OutputValues.of(List.of(lovelace(1_500_000),
                token(POLICY, "5553444d", BigInteger.valueOf(100)), token(POLICY, "ff", BigInteger.ONE)));
        OutputValues.Summary summary = OutputValues.summary(value, USDM);
        assertThat(summary.registeredQuantity()).isEqualTo(BigInteger.valueOf(100));
        assertThat(summary.lovelace()).isEqualTo(BigInteger.valueOf(1_500_000));
        assertThat(summary.otherAssetCount()).isEqualTo(1);
        assertThat(summary.valueHash()).isEqualTo(CardanoValueCbor.hash(BigInteger.valueOf(1_500_000),
                Map.of(USDM, BigInteger.valueOf(100), AssetId.ofHex(POLICY, "ff"), BigInteger.ONE)));
        OutputValues.Summary ada = OutputValues.summary(value, AssetId.ADA);
        assertThat(ada.registeredQuantity()).isEqualTo(BigInteger.valueOf(1_500_000));
        assertThat(ada.otherAssetCount()).isEqualTo(2);
    }

    @Test
    void unrepresentableEntriesCountAsOtherAssetsAndNeverThrow() {
        List<Amount> amounts = new ArrayList<>(List.of(lovelace(2_000_000),
                token(POLICY, "5553444d", BigInteger.ONE.shiftLeft(64)),
                token("abcd", "00", BigInteger.ONE),
                token(POLICY, "00".repeat(33), BigInteger.ONE),
                token(POLICY, "01", BigInteger.ZERO)));
        amounts.add(null);
        OutputValues.ObservedValue value = OutputValues.of(amounts);
        assertThat(value.unrepresentable()).isEqualTo(3);
        assertThat(value.assets()).isEmpty();
        assertThat(OutputValues.summary(value, USDM).otherAssetCount()).isEqualTo(3);
        assertThat(OutputValues.of(null).lovelace()).isZero();
    }

    @Test
    void onlyPhaseTwoValidTransactionsAreRead() {
        TransactionBody a = TransactionBody.builder().txHash("aa".repeat(32)).build();
        TransactionBody b = TransactionBody.builder().txHash("bb".repeat(32)).build();
        TransactionBody c = TransactionBody.builder().txHash("cc".repeat(32)).build();
        Block block = Block.builder().transactionBodies(Arrays.asList(a, b, null, c))
                .invalidTransactions(Arrays.asList(1, null)).build();
        assertThat(ValidTransactions.of(block)).extracting(ValidTransactions.Indexed::blockIndex)
                .containsExactly(0, 3);
        assertThat(ValidTransactions.of(null)).isEmpty();
    }

    @Test
    void vaultAddressesMustBeEnterpriseScriptAddressesOfTheirHash() {
        byte[] hash = new byte[28];
        Arrays.fill(hash, (byte) 7);
        String enterprise = AddressProvider.getEntAddress(Credential.fromScript(hash), Networks.testnet()).getAddress();
        assertThat(VaultAddresses.requireEnterpriseScript(enterprise, hash, 0)).isEqualTo(enterprise);
        assertThatThrownBy(() -> VaultAddresses.requireEnterpriseScript(enterprise, hash, 1))
                .hasMessageContaining("network");
        byte[] other = hash.clone();
        other[0] = 8;
        assertThatThrownBy(() -> VaultAddresses.requireEnterpriseScript(enterprise, other, 0))
                .hasMessageContaining("script hash");
        String keyAddress = AddressProvider.getEntAddress(Credential.fromKey(hash), Networks.testnet()).getAddress();
        assertThatThrownBy(() -> VaultAddresses.requireEnterpriseScript(keyAddress, hash, 0))
                .hasMessageContaining("script");
        String base = AddressProvider.getBaseAddress(Credential.fromScript(hash), Credential.fromKey(hash),
                Networks.testnet()).getAddress();
        assertThatThrownBy(() -> VaultAddresses.requireEnterpriseScript(base, hash, 0))
                .hasMessageContaining("enterprise");
        assertThatThrownBy(() -> VaultAddresses.requireEnterpriseScript("not-an-address", hash, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void observerIdentityIsLengthDelimited() {
        assertThat(ObserverIdentity.encode("t", List.of("ab", "c")))
                .isNotEqualTo(ObserverIdentity.encode("t", List.of("a", "bc")));
        assertThat(ObserverIdentity.encode("t", List.of("a"))).isEqualTo(ObserverIdentity.encode("t", List.of("a")));
    }
}
