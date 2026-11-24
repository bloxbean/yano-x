package org.yanoproject.x.walletauth;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.walletauth.internal.WalletEd25519;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletAuthorizerTest {
    static final IntentEnvelopeV1.Domain DOMAIN = new IntentEnvelopeV1.Domain(1, 0, "payments-1",
            Hash32.of(new byte[32]), "asset-payments");
    static final long NOW = 1_764_000_000_000L;
    static final long TTL = 10 * 60_000L;

    /** A test wallet: an Ed25519 key and its enterprise address on network 0. */
    record Wallet(byte[] seed, byte[] publicKey, byte[] address, CardanoCredential credential) {
        static Wallet of(int seedByte, int addressHeader) {
            byte[] seed = new byte[32];
            java.util.Arrays.fill(seed, (byte) seedByte);
            byte[] pk = com.bloxbean.cardano.client.crypto.KeyGenUtil.getPublicKeyFromPrivateKey(seed);
            byte[] hash = Blake2bUtil.blake2bHash224(pk);
            byte[] address = new byte[29];
            address[0] = (byte) addressHeader;
            System.arraycopy(hash, 0, address, 1, 28);
            return new Wallet(seed, pk, address, CardanoCredential.fromAddress(address));
        }

        byte[][] signData(byte[] payload) {
            return signData(payload, new byte[] {(byte) 0xa0});
        }

        byte[][] signData(byte[] payload, byte[] unprotected) {
            byte[] protectedHeader = cat(new byte[] {(byte) 0xa2, 0x01, 0x27, 0x67},
                    "address".getBytes(StandardCharsets.US_ASCII), bstrHead(address.length), address);
            byte[] signature = WalletEd25519.sign(CoseSign1Verifier.sigStructure(protectedHeader, payload), seed);
            byte[] sign1 = cat(new byte[] {(byte) 0x84}, bstrHead(protectedHeader.length), protectedHeader,
                    unprotected, bstrHead(payload.length), payload, bstrHead(64), signature);
            byte[] key = cat(new byte[] {(byte) 0xa4, 0x01, 0x01, 0x03, 0x27, 0x20, 0x06, 0x21, 0x58, 0x20}, publicKey);
            return new byte[][] {sign1, key};
        }
    }

    static IntentEnvelopeV1 envelope(byte[] accountId, String action, IntentEnvelopeV1.Authorizer who,
                                     int nonceByte, long notAfter) {
        byte[] nonce = new byte[16];
        nonce[0] = (byte) nonceByte;
        return new IntentEnvelopeV1(DOMAIN, action, accountId, who, nonce, notAfter, new byte[] {(byte) 0x81, 0x01});
    }

    final WalletAuthorizer authorizer = new WalletAuthorizer(DOMAIN, TTL);
    final Wallet alice = Wallet.of(1, 0x60);
    final Wallet bob = Wallet.of(2, 0x60);
    final Map<String, CardanoCredential> owners = new HashMap<>();
    final WalletAuthorizer.OwnerLookup lookup = id -> Optional.ofNullable(owners.get(java.util.HexFormat.of().formatHex(id)));

    void register(Wallet wallet) {
        owners.put(java.util.HexFormat.of().formatHex(WalletAuthorizer.accountId(wallet.credential())), wallet.credential());
    }

    @Test
    void registrationRequiresTheDerivedAccountIdAndAnUnusedAccount() {
        byte[] id = WalletAuthorizer.accountId(alice.credential());
        byte[][] cose = alice.signData(envelope(id, WalletAuthorizer.REGISTER_ACCOUNT,
                new IntentEnvelopeV1.Owner(), 1, NOW + 1_000).encode());
        assertThat(authorizer.authorizeOwner(cose[0], cose[1], lookup, NOW).owner()).contains(alice.credential());
        byte[][] forBob = alice.signData(envelope(WalletAuthorizer.accountId(bob.credential()),
                WalletAuthorizer.REGISTER_ACCOUNT, new IntentEnvelopeV1.Owner(), 2, NOW + 1_000).encode());
        assertThatThrownBy(() -> authorizer.authorizeOwner(forBob[0], forBob[1], lookup, NOW))
                .hasMessageContaining("derived");
        register(alice);
        byte[][] bobForAlice = bob.signData(envelope(id, WalletAuthorizer.REGISTER_ACCOUNT,
                new IntentEnvelopeV1.Owner(), 3, NOW + 1_000).encode());
        assertThatThrownBy(() -> authorizer.authorizeOwner(bobForAlice[0], bobForAlice[1], lookup, NOW))
                .hasMessageContaining("already registered");
    }

    @Test
    void aValidSignatureByAccountANamingAccountBIsRejected() {
        register(alice);
        register(bob);
        byte[] bobId = WalletAuthorizer.accountId(bob.credential());
        byte[][] cose = alice.signData(envelope(bobId, "TRANSFER", new IntentEnvelopeV1.Owner(), 3, NOW + 1_000).encode());
        assertThatThrownBy(() -> authorizer.authorizeOwner(cose[0], cose[1], lookup, NOW))
                .hasMessageContaining("not the account's registered owner");
    }

    @Test
    void domainExpiryAndTtlAreEnforced() {
        register(alice);
        byte[] id = WalletAuthorizer.accountId(alice.credential());
        byte[][] expired = alice.signData(envelope(id, "TRANSFER", new IntentEnvelopeV1.Owner(), 4, NOW - 1).encode());
        assertThatThrownBy(() -> authorizer.authorizeOwner(expired[0], expired[1], lookup, NOW)).hasMessageContaining("expired");
        byte[][] farOut = alice.signData(envelope(id, "TRANSFER", new IntentEnvelopeV1.Owner(), 5, NOW + TTL + 1).encode());
        assertThatThrownBy(() -> authorizer.authorizeOwner(farOut[0], farOut[1], lookup, NOW)).hasMessageContaining("TTL");
        IntentEnvelopeV1 otherChain = new IntentEnvelopeV1(new IntentEnvelopeV1.Domain(1, 0, "payments-2",
                Hash32.of(new byte[32]), "asset-payments"), "TRANSFER", id, new IntentEnvelopeV1.Owner(),
                new byte[16], NOW + 1_000, new byte[] {(byte) 0x80});
        byte[][] wrongDomain = alice.signData(otherChain.encode());
        assertThatThrownBy(() -> authorizer.authorizeOwner(wrongDomain[0], wrongDomain[1], lookup, NOW))
                .hasMessageContaining("another network, chain or application");
    }

    @Test
    void anAddressOnAnotherNetworkIdIsRejectedEvenIfMagicMatches() {
        Wallet mainnetAlice = Wallet.of(1, 0x61);
        register(alice);
        byte[] id = WalletAuthorizer.accountId(alice.credential());
        byte[][] cose = mainnetAlice.signData(envelope(id, "TRANSFER", new IntentEnvelopeV1.Owner(), 6, NOW + 1_000).encode());
        assertThatThrownBy(() -> authorizer.authorizeOwner(cose[0], cose[1], lookup, NOW))
                .hasMessageContaining("network id");
    }

    @Test
    void sessionIntentsNeedTheirGrantAccountActionAndKey() {
        register(alice);
        byte[] id = WalletAuthorizer.accountId(alice.credential());
        Wallet session = Wallet.of(9, 0x60);
        Hash32 grantId = Hash32.of(Blake2bUtil.blake2bHash256(new byte[] {7}));
        Hash32 vault = Hash32.of(new byte[32]);
        SessionGrantV1 grant = new SessionGrantV1(grantId, id, session.publicKey(), List.of("TRANSFER"),
                List.of(new SessionGrantV1.Limit(vault, BigInteger.valueOf(6), BigInteger.TEN)), List.of(), NOW + 60_000);
        assertThat(SessionGrantV1.decode(grant.encode()).encode()).isEqualTo(grant.encode());
        Map<Hash32, SessionGrantV1> grants = new HashMap<>(Map.of(grantId, grant));
        WalletAuthorizer.GrantLookup grantLookup = g -> Optional.ofNullable(grants.get(g));

        byte[] env = envelope(id, "TRANSFER", new IntentEnvelopeV1.Session(grantId), 8, NOW + 1_000).encode();
        byte[] sig = WalletEd25519.sign(env, session.seed());
        assertThat(authorizer.authorizeSession(env, sig, grantLookup, NOW).grant()).contains(grant);

        byte[] withdrawal = envelope(id, "REQUEST_WITHDRAWAL", new IntentEnvelopeV1.Session(grantId), 9, NOW + 1_000).encode();
        assertThatThrownBy(() -> authorizer.authorizeSession(withdrawal, WalletEd25519.sign(withdrawal, session.seed()),
                grantLookup, NOW)).hasMessageContaining("not granted");
        assertThatThrownBy(() -> authorizer.authorizeSession(env, WalletEd25519.sign(env, alice.seed()), grantLookup, NOW))
                .hasMessageContaining("does not verify");
        grants.clear(); // revoked
        assertThatThrownBy(() -> authorizer.authorizeSession(env, sig, grantLookup, NOW)).hasMessageContaining("revoked");

        // Cumulative limit within one block: 6 + 6 against a limit of 10.
        grant.checkPayment("TRANSFER", vault, BigInteger.valueOf(6), BigInteger.ZERO, new byte[] {1}, NOW);
        assertThatThrownBy(() -> grant.checkPayment("TRANSFER", vault, BigInteger.valueOf(6), BigInteger.valueOf(6),
                new byte[] {1}, NOW)).hasMessageContaining("cumulative");
    }

    IntentEnvelopeV1 rotation(byte[] id, CardanoCredential target, int nonceByte) {
        byte[] nonce = new byte[16];
        nonce[0] = (byte) nonceByte;
        return new IntentEnvelopeV1(DOMAIN, WalletAuthorizer.ROTATE_OWNER, id, new IntentEnvelopeV1.Owner(), nonce,
                NOW + 1_000, WalletAuthorizer.rotationBody(target));
    }

    @Test
    void rotationNeedsTheCurrentOwnerAndTheNewCredentialNamedInTheBody() {
        register(alice);
        byte[] id = WalletAuthorizer.accountId(alice.credential());
        byte[] env = rotation(id, bob.credential(), 10).encode();
        byte[][] byAlice = alice.signData(env);
        byte[][] byBob = bob.signData(env);
        assertThat(authorizer.authorizeRotation(byAlice[0], byAlice[1], byBob[0], byBob[1], lookup, NOW).owner())
                .contains(bob.credential());
        byte[][] bobOther = bob.signData(rotation(id, bob.credential(), 11).encode());
        assertThatThrownBy(() -> authorizer.authorizeRotation(byAlice[0], byAlice[1], bobOther[0], bobOther[1], lookup, NOW))
                .hasMessageContaining("different envelopes");
        // A body naming Carol but countersigned by Bob is rejected.
        Wallet carol = Wallet.of(3, 0x60);
        byte[] toCarol = rotation(id, carol.credential(), 12).encode();
        byte[][] a2 = alice.signData(toCarol);
        byte[][] b2 = bob.signData(toCarol);
        assertThatThrownBy(() -> authorizer.authorizeRotation(a2[0], a2[1], b2[0], b2[1], lookup, NOW))
                .hasMessageContaining("named in the body");
        // A non-rotation action cannot be used for rotation, and rotation needs both signatures.
        byte[] transfer = envelope(id, "TRANSFER", new IntentEnvelopeV1.Owner(), 13, NOW + 1_000).encode();
        byte[][] a3 = alice.signData(transfer);
        byte[][] b3 = bob.signData(transfer);
        assertThatThrownBy(() -> authorizer.authorizeRotation(a3[0], a3[1], b3[0], b3[1], lookup, NOW))
                .hasMessageContaining("not a ROTATE_OWNER");
        assertThatThrownBy(() -> authorizer.authorizeOwner(byAlice[0], byAlice[1], lookup, NOW))
                .hasMessageContaining("both signatures");
    }

    @Test
    void sessionsCanNeverPerformOwnerOnlyActions() {
        register(alice);
        byte[] id = WalletAuthorizer.accountId(alice.credential());
        Wallet session = Wallet.of(9, 0x60);
        Hash32 grantId = Hash32.of(Blake2bUtil.blake2bHash256(new byte[] {8}));
        SessionGrantV1 grant = new SessionGrantV1(grantId, id, session.publicKey(),
                List.of("REVOKE_SESSION", "ROTATE_OWNER"), List.of(), List.of(), NOW + 60_000);
        for (String action : List.of("REVOKE_SESSION", "ROTATE_OWNER")) {
            byte[] env = envelope(id, action, new IntentEnvelopeV1.Session(grantId), 20, NOW + 1_000).encode();
            assertThatThrownBy(() -> authorizer.authorizeSession(env, WalletEd25519.sign(env, session.seed()),
                    g -> Optional.of(grant), NOW)).hasMessageContaining("owner's signature");
        }
    }

    @Test
    void registrationIsIdempotentForTheCurrentOwner() {
        register(alice);
        byte[] id = WalletAuthorizer.accountId(alice.credential());
        byte[][] again = alice.signData(envelope(id, WalletAuthorizer.REGISTER_ACCOUNT, new IntentEnvelopeV1.Owner(),
                21, NOW + 1_000).encode());
        assertThat(authorizer.authorizeOwner(again[0], again[1], lookup, NOW).owner()).contains(alice.credential());
    }

    @Test
    void reviewRegressions() {
        // Out-of-range network id must not alias network 0.
        byte[] id = WalletAuthorizer.accountId(alice.credential());
        byte[] good = envelope(id, "TRANSFER", new IntentEnvelopeV1.Owner(), 22, NOW).encode();
        String hex = java.util.HexFormat.of().formatHex(good);
        String wide = hex.replaceFirst("8c6e79616e6f2d696e74656e742d76310100", "8c6e79616e6f2d696e74656e742d7631011b0000000100000000");
        assertThat(wide).isNotEqualTo(hex);
        assertThatThrownBy(() -> IntentEnvelopeV1.decode(java.util.HexFormat.of().parseHex(wide)))
                .isInstanceOf(WalletAuthException.class);
        // Non-canonical action body is rejected.
        assertThatThrownBy(() -> new IntentEnvelopeV1(DOMAIN, "TRANSFER", id, new IntentEnvelopeV1.Owner(),
                new byte[16], NOW, new byte[] {(byte) 0x81, 0x18, 0x01})).isInstanceOf(IllegalArgumentException.class);
        // Grant recipient list cannot be mutated through the accessor.
        SessionGrantV1 grant = new SessionGrantV1(Hash32.of(new byte[32]), id, new byte[32], List.of("TRANSFER"),
                List.of(), List.of(new byte[] {1, 2}), NOW);
        grant.recipients().getFirst()[1] = 9;
        assertThat(grant.recipientAllowed(new byte[] {1, 9})).isFalse();
        // Negative payment amounts are rejected.
        Hash32 vault = Hash32.of(new byte[32]);
        SessionGrantV1 limited = new SessionGrantV1(Hash32.of(new byte[32]), id, new byte[32], List.of("TRANSFER"),
                List.of(new SessionGrantV1.Limit(vault, BigInteger.valueOf(6), BigInteger.TEN)), List.of(), NOW);
        assertThatThrownBy(() -> limited.checkPayment("TRANSFER", vault, BigInteger.ONE.negate(), BigInteger.TEN,
                new byte[] {1}, NOW)).isInstanceOf(WalletAuthException.class);
        // Malformed pointer address (third natural unterminated) is rejected; a well-formed one is accepted.
        byte[] kh = alice.credential().keyHash();
        byte[] bad = cat(new byte[] {0x40}, kh, new byte[] {0x00, 0x00, (byte) 0x80});
        assertThatThrownBy(() -> CardanoCredential.fromAddress(bad)).isInstanceOf(IllegalArgumentException.class);
        byte[] ok = cat(new byte[] {0x40}, kh, new byte[] {0x00, (byte) 0x81, 0x00, 0x02});
        assertThat(CardanoCredential.fromAddress(ok).kind()).isEqualTo(CardanoCredential.Kind.PAYMENT_KEY);
        // TTL boundary with a huge configured TTL does not overflow.
        WalletAuthorizer lenient = new WalletAuthorizer(DOMAIN, Long.MAX_VALUE);
        register(alice);
        byte[][] cose = alice.signData(envelope(id, "TRANSFER", new IntentEnvelopeV1.Owner(), 23, NOW).encode());
        assertThat(lenient.authorizeOwner(cose[0], cose[1], lookup, NOW)).isNotNull();
    }

    @Test
    void envelopeDecodingIsStrict() {
        byte[] id = WalletAuthorizer.accountId(alice.credential());
        byte[] good = envelope(id, "TRANSFER", new IntentEnvelopeV1.Owner(), 12, NOW).encode();
        assertThat(IntentEnvelopeV1.decode(good).encode()).isEqualTo(good);
        byte[] trailing = java.util.Arrays.copyOf(good, good.length + 1);
        assertThatThrownBy(() -> IntentEnvelopeV1.decode(trailing)).isInstanceOf(WalletAuthException.class);
    }

    @Test
    void unprotectedHeaderRules() {
        byte[] payload = "p".getBytes();
        // A nested tag anywhere (here tag 1 inside an ignored unprotected value) is rejected.
        byte[][] tagged = alice.signData(payload, new byte[] {(byte) 0xa1, 0x61, 'x', (byte) 0xc1, (byte) 0xf6});
        assertThatThrownBy(() -> CoseSign1Verifier.verify(tagged[0], tagged[1])).isInstanceOf(WalletAuthException.class);
        // Ignored float values and huge integer labels are tolerated (unsigned, RFC 8152 §1.4).
        byte[][] floatValue = alice.signData(payload, new byte[] {(byte) 0xa1, 0x61, 'x', (byte) 0xf9, 0x3c, 0x00});
        assertThat(CoseSign1Verifier.verify(floatValue[0], floatValue[1]).payload()).isEqualTo(payload);
        byte[][] bigLabel = alice.signData(payload, new byte[] {(byte) 0xa1, 0x3b, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0, 0x00});
        assertThat(CoseSign1Verifier.verify(bigLabel[0], bigLabel[1]).payload()).isEqualTo(payload);
        // The 16 KiB limit applies to the Sign1 and the key together.
        byte[][] ok = alice.signData(payload);
        byte[] paddedKey = java.util.Arrays.copyOf(ok[1], 16_384 - ok[0].length + 1);
        assertThatThrownBy(() -> CoseSign1Verifier.verify(ok[0], paddedKey)).hasMessageContaining("16 KiB");
    }

    static byte[] bstrHead(int length) {
        if (length < 24) return new byte[] {(byte) (0x40 | length)};
        if (length < 256) return new byte[] {0x58, (byte) length};
        return new byte[] {0x59, (byte) (length >>> 8), (byte) length};
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
}
