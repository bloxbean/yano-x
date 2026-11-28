package org.yanoproject.x.walletauth;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.cip.cip30.CIP30DataSigner;
import com.bloxbean.cardano.client.cip.cip30.DataSignature;
import com.bloxbean.cardano.client.common.model.Networks;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Differential check of the cryptographic step against cardano-client-lib's CIP-30 signer (the
 * reference implementation wallets are tested against). CCL is an oracle only: its verify does
 * not apply the application policy, which the other tests cover.
 */
class CclDifferentialTest {
    @Test
    void signaturesFromTheCclCip30SignerVerify() throws Exception {
        Account account = new Account(Networks.testnet());
        byte[] address = account.getBaseAddress().getBytes();
        byte[] payload = "yano intent bytes".getBytes();
        DataSignature signature = CIP30DataSigner.INSTANCE.signData(address, payload, account);
        HexFormat hex = HexFormat.of();
        CoseSign1Verifier.Verified verified = CoseSign1Verifier.verify(
                hex.parseHex(signature.signature()), hex.parseHex(signature.key()));
        assertThat(verified.payload()).isEqualTo(payload);
        assertThat(verified.credential().kind()).isEqualTo(CardanoCredential.Kind.PAYMENT_KEY);
        assertThat(verified.credential().addressNetworkId()).isZero();
        assertThat(CIP30DataSigner.INSTANCE.verify(signature)).isTrue();
    }

    @Test
    void cclAgreesWithOurSignatures() {
        WalletAuthorizerTest.Wallet wallet = WalletAuthorizerTest.Wallet.of(5, 0x60);
        byte[][] cose = wallet.signData("payload".getBytes());
        HexFormat hex = HexFormat.of();
        DataSignature ours = new DataSignature(hex.formatHex(cose[0]), hex.formatHex(cose[1]));
        assertThat(CIP30DataSigner.INSTANCE.verify(ours)).isTrue();
        assertThat(CoseSign1Verifier.verify(cose[0], cose[1]).payload()).isEqualTo("payload".getBytes());
    }
}
