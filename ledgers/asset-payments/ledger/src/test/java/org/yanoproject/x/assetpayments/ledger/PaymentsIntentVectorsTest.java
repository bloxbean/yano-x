package org.yanoproject.x.assetpayments.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assetpayments.contracts.PaymentsMessageV1;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.walletauth.CardanoCredential;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.SessionGrantV1;
import org.yanoproject.x.walletauth.WalletAuthorizer;
import org.yanoproject.x.walletauth.internal.WalletEd25519;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden vectors for clients that build intents themselves (#109 §3.1: sign exact bytes). The reference web client's
 * encoder ({@code examples/payments-client}) is tested against this file, so a Java change that moves a byte fails
 * here first. Regenerate with the environment variable {@code PAYMENTS_VECTORS_WRITE=true} only for a deliberate, versioned
 * format change.
 */
class PaymentsIntentVectorsTest {
    static final Path FILE = Path.of("src/test/resources/asset-payments/intent-vectors-v1.json");
    static final HexFormat HEX = HexFormat.of();
    static final IntentEnvelopeV1.Domain DOMAIN = new IntentEnvelopeV1.Domain(42, 0, "payments-devnet-1",
            Hash32.ofHex("6c".repeat(32)), "asset-payments");
    static final long NOT_AFTER = 1_764_000_600_000L;
    static final AssetId USDM = AssetId.ofHex("f0".repeat(28), "5553444d");
    static final byte[] SESSION_SEED = filled(0x77);

    final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    final TestWallet alice = TestWallet.of(1);
    final TestWallet bob = TestWallet.of(2);
    final TestWallet carol = TestWallet.of(3);

    @Test
    void vectorsMatchTheCheckedInFile() throws Exception {
        byte[] generated = json.writeValueAsBytes(vectors());
        if ("true".equals(System.getenv("PAYMENTS_VECTORS_WRITE"))) {
            Files.createDirectories(FILE.getParent());
            Files.write(FILE, generated);
        }
        assertThat(Files.readString(FILE).strip()).isEqualTo(new String(generated).strip());
    }

    ObjectNode vectors() {
        ObjectNode root = json.createObjectNode();
        root.put("description", "asset-payments intent vectors v1 (ADR-059 D13/D14); every hex is lowercase");
        ObjectNode domain = root.putObject("domain");
        domain.put("networkMagic", DOMAIN.networkMagic());
        domain.put("addressNetworkId", DOMAIN.addressNetworkId());
        domain.put("chainId", DOMAIN.chainId());
        domain.put("genesisId", DOMAIN.genesisId().hex());
        domain.put("applicationId", DOMAIN.applicationId());
        root.put("intentTopic", AssetPaymentsContract.INTENT_TOPIC);

        ArrayNode accounts = root.putArray("accounts");
        byte[] base = new byte[57];
        base[0] = 0x00;
        System.arraycopy(alice.keyHash(), 0, base, 1, 28);
        System.arraycopy(bob.keyHash(), 0, base, 29, 28);
        byte[] reward = new byte[29];
        reward[0] = (byte) 0xe0;
        System.arraycopy(carol.keyHash(), 0, reward, 1, 28);
        for (byte[] address : List.of(alice.address(), base, reward)) {
            CardanoCredential credential = CardanoCredential.fromAddress(address);
            ObjectNode entry = accounts.addObject();
            entry.put("addressHex", HEX.formatHex(address));
            entry.put("credentialKind", credential.kind().code());
            entry.put("keyHash", HEX.formatHex(credential.keyHash()));
            entry.put("accountId", HEX.formatHex(WalletAuthorizer.accountId(credential)));
        }

        Hash32 adaVault = GatewayIds.vaultId(GatewayIds.domainId(0, 42, DOMAIN.chainId(), DOMAIN.genesisId(),
                "asset-payments", "anchored-claim-vault-v1", 1), AssetId.ADA, 0);
        Hash32 grant = Hash32.ofHex("a1".repeat(32));
        byte[] sessionKey = com.bloxbean.cardano.client.crypto.KeyGenUtil.getPublicKeyFromPrivateKey(SESSION_SEED);
        ArrayNode intents = root.putArray("intents");
        intent(intents, "register", alice, WalletAuthorizer.REGISTER_ACCOUNT, owner(), 1,
                PaymentsBodies.REGISTER_ACCOUNT, json.createObjectNode());
        ObjectNode transferAda = json.createObjectNode();
        transferAda.set("asset", asset(AssetId.ADA));
        transferAda.put("toAccount", HEX.formatHex(bob.accountId()));
        transferAda.put("amount", "1500000");
        intent(intents, "transfer-ada", alice, AssetPaymentsContract.TRANSFER, owner(), 2,
                new PaymentsBodies.Transfer(AssetId.ADA, bob.accountId(), BigInteger.valueOf(1_500_000)).encode(),
                transferAda);
        ObjectNode transferMax = json.createObjectNode();
        transferMax.set("asset", asset(USDM));
        transferMax.put("toAccount", HEX.formatHex(bob.accountId()));
        transferMax.put("amount", "18446744073709551615");
        intent(intents, "transfer-token-max-u64", alice, AssetPaymentsContract.TRANSFER, owner(), 3,
                new PaymentsBodies.Transfer(USDM, bob.accountId(), new BigInteger("18446744073709551615")).encode(),
                transferMax);
        ObjectNode authorize = json.createObjectNode();
        authorize.put("sessionKey", HEX.formatHex(sessionKey));
        authorize.putArray("actions").add(AssetPaymentsContract.TRANSFER);
        ObjectNode limit = authorize.putArray("limits").addObject();
        limit.put("vaultId", adaVault.hex());
        limit.put("perPayment", "1000000");
        limit.put("cumulative", "5000000");
        authorize.putArray("recipients").add(HEX.formatHex(bob.accountId()));
        authorize.put("notAfterMs", NOT_AFTER + 3_600_000);
        intent(intents, "authorize-session", alice, WalletAuthorizer.AUTHORIZE_SESSION, owner(), 4,
                new PaymentsBodies.AuthorizeSession(sessionKey, List.of(AssetPaymentsContract.TRANSFER),
                        List.of(new SessionGrantV1.Limit(adaVault, BigInteger.valueOf(1_000_000),
                                BigInteger.valueOf(5_000_000))), List.of(bob.accountId()), NOT_AFTER + 3_600_000)
                        .encode(), authorize);
        ObjectNode revoke = json.createObjectNode();
        revoke.put("grantId", grant.hex());
        intent(intents, "revoke-session", alice, WalletAuthorizer.REVOKE_SESSION, owner(), 5,
                new PaymentsBodies.RevokeSession(grant).encode(), revoke);
        ObjectNode withdraw = json.createObjectNode();
        withdraw.put("vaultId", adaVault.hex());
        withdraw.put("recipient", HEX.formatHex(alice.address()));
        withdraw.put("amount", "2000000");
        withdraw.put("vaultLovelace", "0");
        intent(intents, "request-withdrawal", alice, AssetPaymentsContract.REQUEST_WITHDRAWAL, owner(), 6,
                new PaymentsBodies.RequestWithdrawal(adaVault, alice.address(), BigInteger.valueOf(2_000_000),
                        BigInteger.ZERO).encode(), withdraw);
        ObjectNode rotate = json.createObjectNode();
        rotate.put("address", HEX.formatHex(carol.address()));
        intent(intents, "rotate-owner", alice, WalletAuthorizer.ROTATE_OWNER, owner(), 7,
                WalletAuthorizer.rotationBody(carol.credential()), rotate);
        ObjectNode sessionPay = json.createObjectNode();
        sessionPay.set("asset", asset(AssetId.ADA));
        sessionPay.put("toAccount", HEX.formatHex(bob.accountId()));
        sessionPay.put("amount", "250000");
        intent(intents, "session-transfer", alice, AssetPaymentsContract.TRANSFER,
                new IntentEnvelopeV1.Session(grant), 8,
                new PaymentsBodies.Transfer(AssetId.ADA, bob.accountId(), BigInteger.valueOf(250_000)).encode(),
                sessionPay);
        root.put("sessionSeedHex", HEX.formatHex(SESSION_SEED));
        root.put("sessionPublicKeyHex", HEX.formatHex(sessionKey));
        return root;
    }

    void intent(ArrayNode out, String name, TestWallet signer, String action, IntentEnvelopeV1.Authorizer who,
                int nonceByte, byte[] body, ObjectNode bodyJson) {
        IntentEnvelopeV1 envelope = new IntentEnvelopeV1(DOMAIN, action, signer.accountId(), who, nonce(nonceByte),
                NOT_AFTER, body);
        byte[] bytes = envelope.encode();
        ObjectNode entry = out.addObject();
        entry.put("name", name);
        entry.put("action", action);
        entry.put("accountId", HEX.formatHex(signer.accountId()));
        ObjectNode authorizer = entry.putObject("authorizer");
        if (who instanceof IntentEnvelopeV1.Session session) {
            authorizer.put("kind", "SESSION");
            authorizer.put("grantId", session.grantId().hex());
        } else {
            authorizer.put("kind", "OWNER");
        }
        entry.put("nonce", HEX.formatHex(envelope.nonce()));
        entry.put("notAfterMs", NOT_AFTER);
        entry.set("body", bodyJson);
        entry.put("bodyHex", HEX.formatHex(body));
        entry.put("envelopeHex", HEX.formatHex(bytes));
        entry.put("intentId", GatewayIds.intentId(bytes).hex());
        // The JSON form selects exactly these bytes (the domain API's intents/encode route).
        assertThat(PaymentsIntentJson.envelope(entry, DOMAIN).encode()).isEqualTo(bytes);
        PaymentsMessageV1 message;
        if (who instanceof IntentEnvelopeV1.Session) {
            byte[] signature = WalletEd25519.sign(bytes, SESSION_SEED);
            entry.put("sessionSignatureHex", HEX.formatHex(signature));
            message = new PaymentsMessageV1.Session(bytes, signature);
        } else {
            byte[][] cose = signer.signData(bytes);
            entry.put("coseSign1Hex", HEX.formatHex(cose[0]));
            entry.put("coseKeyHex", HEX.formatHex(cose[1]));
            message = new PaymentsMessageV1.Owner(cose[0], cose[1]);
        }
        entry.put("messageBodyHex", HEX.formatHex(message.encode()));
    }

    ObjectNode asset(AssetId asset) {
        ObjectNode node = json.createObjectNode();
        node.put("policyId", HEX.formatHex(asset.policyId()));
        node.put("assetName", HEX.formatHex(asset.assetName()));
        return node;
    }

    static IntentEnvelopeV1.Owner owner() {
        return new IntentEnvelopeV1.Owner();
    }

    static byte[] nonce(int value) {
        byte[] nonce = new byte[16];
        Arrays.fill(nonce, (byte) value);
        nonce[0] = (byte) 0xa0;
        return nonce;
    }

    static byte[] filled(int value) {
        byte[] out = new byte[32];
        Arrays.fill(out, (byte) value);
        return out;
    }
}
