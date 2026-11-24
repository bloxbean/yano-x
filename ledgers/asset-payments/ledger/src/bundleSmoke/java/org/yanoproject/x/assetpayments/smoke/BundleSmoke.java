package org.yanoproject.x.assetpayments.smoke;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import org.yanoproject.x.framework.api.appchain.*;
import org.yanoproject.x.framework.api.appchain.codec.AppBlockCodec;
import org.yanoproject.x.framework.api.appchain.effects.EffectOutcomeCommitment;
import org.yanoproject.x.framework.api.appchain.effects.FinalityGate;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Run by {@code verifyAssetGatewayBundlesLoad} with only the integrated yano.jar on the class path.
 * Loads each runtime bundle the way the host does (a child loader whose parent holds only the
 * integrated yano.jar, parent-first) and exercises its main paths using host types only:
 * the observer observes a deposit, the machine registers an owner-signed account (COSE + Ed25519)
 * and credits that deposit.
 */
public final class BundleSmoke {
    private BundleSmoke() {
    }

    static final String CHAIN = "payments-1", GENESIS = "6c".repeat(32), POLICY = "f0".repeat(28);

    public static void main(String[] args) throws Exception {
        ClassLoader host = BundleSmoke.class.getClassLoader();
        URLClassLoader gateway = new URLClassLoader(new URL[] {new File(args[0]).toURI().toURL()}, host);
        URLClassLoader payments = new URLClassLoader(new URL[] {new File(args[1]).toURI().toURL()}, host);

        byte[] usdmHash = fill(28, 2);
        String usdmVault = AddressProvider.getEntAddress(Credential.fromScript(usdmHash), Networks.testnet()).getAddress();
        Map<String, String> observerSettings = new TreeMap<>(Map.of(
                "domain.address-network-id", "0", "domain.network-magic", "42", "domain.chain-id", CHAIN,
                "domain.genesis-id", GENESIS, "vaults.0.asset", POLICY + ".5553444d",
                "vaults.0.script-hash", "02".repeat(28), "vaults.0.address", usdmVault));
        L1ObserverProvider observerProvider = only(ServiceLoader.load(L1ObserverProvider.class, gateway),
                p -> p.type().equals("asset-vault-deposit-v1"));
        if (observerProvider.getClass().getClassLoader() != gateway) throw new AssertionError("observer not from bundle");
        observerProvider.consensusIdentity("deposits", observerSettings);
        L1Observer observer = observerProvider.create("deposits", observerSettings);

        byte[] seed = fill(32, 1);
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        byte[] keyHash = Blake2bUtil.blake2bHash224(publicKey);
        byte[] address = cat(new byte[] {0x60}, keyHash);
        byte[] account = Blake2bUtil.blake2bHash224(cat("acct\0".getBytes(StandardCharsets.US_ASCII), new byte[] {0}, keyHash));
        byte[] datum = cat(hex("d87983581c"), account, hex("581d"), address, hex("581c"), keyHash);
        Amount lovelace = Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(1_500_000)).build();
        Amount usdm = Amount.builder().unit(POLICY + ".5553444d").policyId(POLICY)
                .assetNameBytes(hex("5553444d")).quantity(BigInteger.valueOf(100)).build();
        TransactionBody tx = TransactionBody.builder().txHash("aa".repeat(32)).outputs(List.of(TransactionOutput.builder()
                .address(usdmVault).amounts(List.of(lovelace, usdm)).inlineDatum(HexFormat.of().formatHex(datum))
                .build())).build();
        List<L1Observation> observations = observer.observe(1_000, fill(32, 0xb1),
                Block.builder().transactionBodies(List.of(tx)).build());
        if (observations.size() != 1) throw new AssertionError("expected one observation, got " + observations);

        Map<String, String> settings = new TreeMap<>();
        String m = "machines.asset-payments.";
        settings.putAll(Map.of(m + "domain.address-network-id", "0", m + "domain.network-magic", "42",
                m + "domain.genesis-id", GENESIS, m + "deposit-observer-id", "deposits", m + "min-stability-depth", "1",
                m + "vaults.0.asset", POLICY + ".5553444d", m + "vaults.0.script-hash", "02".repeat(28),
                m + "vaults.0.address", usdmVault));
        observerSettings.forEach((k, v) -> settings.put("observers.deposits." + k, v));
        settings.put("observers.deposits.type", "asset-vault-deposit-v1");
        AppStateMachineProvider machineProvider = only(ServiceLoader.load(AppStateMachineProvider.class, payments),
                p -> p.id().equals("asset-payments"));
        if (machineProvider.getClass().getClassLoader() != payments) throw new AssertionError("machine not from bundle");
        int message = 64 * 1024;
        AppChainConsensusProfile profile = new AppChainConsensusProfile(AppChainConsensusProfile.SCHEMA_VERSION, message,
                100, message + AppChainConfig.BLOCK_ENVELOPE_HEADROOM_BYTES + AppChainConfig.MAX_FINALITY_CERT_HEADROOM_BYTES,
                2, 2, 5_000, false, false, 0, 0, 0, 0, FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT,
                false, List.of());
        AppStateMachine machine = machineProvider.create(new AppStateMachineContext() {
            public String chainId() { return CHAIN; }
            public Map<String, String> settings() { return settings; }
            public Optional<AppChainConsensusProfile> consensusProfile() { return Optional.of(profile); }
        });
        TreeMap<String, byte[]> state = new TreeMap<>();
        AppStateWriter writer = new AppStateWriter() {
            public Optional<byte[]> get(byte[] k) { return Optional.ofNullable(state.get(HexFormat.of().formatHex(k))); }
            public byte[] stateRoot() { return new byte[32]; }
            public void put(byte[] k, byte[] v) { state.put(HexFormat.of().formatHex(k), v.clone()); }
            public void delete(byte[] k) { state.remove(HexFormat.of().formatHex(k)); }
        };
        machine.init(writer, null);

        long t0 = 1_764_000_000_000L;
        byte[] envelope = encode(array(new UnicodeString("yano-intent-v1"), uint(42), uint(0), new UnicodeString(CHAIN),
                new ByteString(hex(GENESIS)), new UnicodeString("asset-payments"), new UnicodeString("REGISTER_ACCOUNT"),
                new ByteString(account), array(uint(0)), new ByteString(new byte[16]), uint(t0 + 60_000),
                new ByteString(new byte[] {(byte) 0x80})));
        byte[] protectedHeader = cat(hex("a20127"), hex("67"), "address".getBytes(StandardCharsets.US_ASCII),
                hex("581d"), address);
        byte[] sigStructure = cat(hex("846a"), "Signature1".getBytes(StandardCharsets.US_ASCII), head(protectedHeader.length),
                protectedHeader, hex("40"), head(envelope.length), envelope);
        byte[] signature = new EdDSASigningProvider().sign(sigStructure, seed);
        byte[] sign1 = cat(hex("84"), head(protectedHeader.length), protectedHeader, hex("a0"), head(envelope.length),
                envelope, head(64), signature);
        byte[] coseKey = cat(hex("a4010103272006215820"), publicKey);
        byte[] intent = encode(array(uint(1), uint(0), new ByteString(sign1), new ByteString(coseKey)));
        AppMessage intentMessage = message("asset-payments.intents", intent, 1);
        if (!machine.validate(intentMessage).isAccepted()) throw new AssertionError("intent not admitted");

        apply(machine, writer, t0, 1, intentMessage);
        apply(machine, writer, t0 + 1_000, 2, message("~l1/deposits", observations.getFirst().encode(), 2));
        String leafPrefix = HexFormat.of().formatHex("ap/x/".getBytes(StandardCharsets.US_ASCII));
        long leaves = state.keySet().stream().filter(k -> k.startsWith(leafPrefix)).count();
        String receiptPrefix = HexFormat.of().formatHex("ap/receipt/".getBytes(StandardCharsets.US_ASCII));
        long receipts = state.keySet().stream().filter(k -> k.startsWith(receiptPrefix)).count();
        if (leaves != 1 || receipts != 1) throw new AssertionError("expected one credited leaf and one receipt: " + leaves + "/" + receipts);
        System.out.println("bundle smoke OK: observer " + observerProvider.getClass().getName() + ", machine "
                + machine.getClass().getName() + ", " + state.size() + " state keys, 1 receipt, 1 credited leaf");
    }

    static void apply(AppStateMachine machine, AppStateWriter writer, long time, long height, AppMessage... messages) {
        List<AppMessage> list = List.of(messages);
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN, height, AppBlock.GENESIS_PREV_HASH, 0, new byte[0],
                time, AppBlockCodec.messagesRoot(list), new byte[32], list, new byte[32], FinalityCert.empty());
        machine.apply(AppBlockExecutionContext.fromValidatedBlock(block), writer, null);
    }

    static AppMessage message(String topic, byte[] body, long seq) {
        byte[] sender = fill(32, 9);
        return AppMessage.builder().messageId(AppMessage.computeMessageId(CHAIN, topic, sender, seq, 4_000_000_000L, body))
                .chainId(CHAIN).topic(topic).sender(sender).senderSeq(seq).expiresAt(4_000_000_000L).body(body)
                .authScheme(AuthScheme.ED25519.getValue()).authProof(new byte[64]).build();
    }

    static <T> T only(ServiceLoader<T> loader, java.util.function.Predicate<T> filter) {
        List<T> found = new ArrayList<>();
        for (T provider : loader) if (filter.test(provider)) found.add(provider);
        if (found.size() != 1) throw new AssertionError("expected exactly one provider, found " + found);
        return found.getFirst();
    }

    static Array array(co.nstant.in.cbor.model.DataItem... items) {
        Array array = new Array();
        for (var item : items) array.add(item);
        return array;
    }

    static UnsignedInteger uint(long value) { return new UnsignedInteger(value); }

    static byte[] encode(co.nstant.in.cbor.model.DataItem item) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CborEncoder(out).encode(item);
        return out.toByteArray();
    }

    static byte[] head(int length) {
        if (length < 24) return new byte[] {(byte) (0x40 | length)};
        if (length < 256) return new byte[] {0x58, (byte) length};
        return new byte[] {0x59, (byte) (length >>> 8), (byte) length};
    }

    static byte[] fill(int n, int v) { byte[] b = new byte[n]; Arrays.fill(b, (byte) v); return b; }

    static byte[] hex(String s) { return HexFormat.of().parseHex(s); }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }
}
