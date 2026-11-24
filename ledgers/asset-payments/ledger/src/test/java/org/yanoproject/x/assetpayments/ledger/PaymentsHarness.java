package org.yanoproject.x.assetpayments.ledger;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsMessageV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.ExitLeafV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppBlockExecutionContext;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;
import org.yanoproject.x.framework.api.appchain.AppChainConsensusProfile;
import org.yanoproject.x.framework.api.appchain.effects.EffectOutcomeCommitment;
import org.yanoproject.x.framework.api.appchain.effects.FinalityGate;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateMachineContext;
import org.yanoproject.x.framework.api.appchain.AppStateWriter;
import org.yanoproject.x.framework.api.appchain.FinalityCert;
import org.yanoproject.x.framework.api.appchain.codec.AppBlockCodec;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.internal.WalletEd25519;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Drives the machine block by block over an in-memory state, as the runtime would. */
final class PaymentsHarness {
    static final String CHAIN = "payments-1";
    static final String OBSERVER = "deposits";
    static final String GENESIS = "6c".repeat(32);
    static final long NETWORK_MAGIC = 42;
    static final String POLICY = "f0".repeat(28);
    static final AssetId USDM = AssetId.ofHex(POLICY, "5553444d");
    static final AssetId EURC = AssetId.ofHex(POLICY, "45555243");
    static final byte[] ADA_HASH = filled(28, 0x01);
    static final byte[] USDM_HASH = filled(28, 0x02);
    static final byte[] EURC_HASH = filled(28, 0x03);
    static final String ADA_ADDRESS = enterprise(ADA_HASH);
    static final String USDM_ADDRESS = enterprise(USDM_HASH);
    static final String EURC_ADDRESS = enterprise(EURC_HASH);
    static final long T0 = 1_764_000_000_000L;
    static final Hash32 DOMAIN = GatewayIds.domainId(0, NETWORK_MAGIC, CHAIN, Hash32.ofHex(GENESIS),
            "asset-payments", "anchored-claim-vault-v1", 1);
    static final Hash32 ADA_VAULT = GatewayIds.vaultId(DOMAIN, AssetId.ADA, 0);
    static final Hash32 USDM_VAULT = GatewayIds.vaultId(DOMAIN, USDM, 0);
    static final Hash32 EURC_VAULT = GatewayIds.vaultId(DOMAIN, EURC, 0);
    static final IntentEnvelopeV1.Domain INTENT_DOMAIN = new IntentEnvelopeV1.Domain(NETWORK_MAGIC, 0, CHAIN,
            Hash32.ofHex(GENESIS), "asset-payments");
    static final AssetAccounts KEYS = new AssetAccounts(AssetPaymentsContract.PREFIX, new AssetAccounts.Store() {
        @Override public Optional<byte[]> get(byte[] key) { return Optional.empty(); }
        @Override public void put(byte[] key, byte[] value) { }
        @Override public void delete(byte[] key) { }
    });

    static byte[] filled(int length, int value) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    static String enterprise(byte[] hash) {
        return AddressProvider.getEntAddress(Credential.fromScript(hash), Networks.testnet()).getAddress();
    }

    /** Ledger settings and the matching observer settings, as one chain configuration would carry them. */
    static Map<String, String> settings() {
        Map<String, String> settings = new LinkedHashMap<>();
        String m = "machines.asset-payments.";
        settings.put(m + "domain.address-network-id", "0");
        settings.put(m + "domain.network-magic", String.valueOf(NETWORK_MAGIC));
        settings.put(m + "domain.genesis-id", GENESIS);
        settings.put(m + "deposit-observer-id", OBSERVER);
        settings.put(m + "min-stability-depth", "1");
        String o = "observers." + OBSERVER + ".";
        settings.put(o + "type", "asset-vault-deposit-v1");
        settings.put(o + "domain.address-network-id", "0");
        settings.put(o + "domain.network-magic", String.valueOf(NETWORK_MAGIC));
        settings.put(o + "domain.chain-id", CHAIN);
        settings.put(o + "domain.genesis-id", GENESIS);
        settings.put(o + "min-stability-depth", "1");
        String[][] vaults = {{"lovelace", HexFormat.of().formatHex(ADA_HASH), ADA_ADDRESS, "ADA", "6"},
                {POLICY + ".5553444d", HexFormat.of().formatHex(USDM_HASH), USDM_ADDRESS, "USDM", "6"},
                {POLICY + ".45555243", HexFormat.of().formatHex(EURC_HASH), EURC_ADDRESS, "EURC", "2"}};
        for (int i = 0; i < vaults.length; i++) {
            settings.put(m + "vaults." + i + ".asset", vaults[i][0]);
            settings.put(m + "vaults." + i + ".script-hash", vaults[i][1]);
            settings.put(m + "vaults." + i + ".address", vaults[i][2]);
            settings.put(m + "vaults." + i + ".ticker", vaults[i][3]);
            settings.put(m + "vaults." + i + ".decimals", vaults[i][4]);
            settings.put(o + "vaults." + i + ".asset", vaults[i][0]);
            settings.put(o + "vaults." + i + ".script-hash", vaults[i][1]);
            settings.put(o + "vaults." + i + ".address", vaults[i][2]);
        }
        return settings;
    }

    /** Only the observer's own {@code observers.<id>.*} suffixes. */
    static Map<String, String> observerSettings() {
        Map<String, String> out = new TreeMap<>();
        String o = "observers." + OBSERVER + ".";
        settings().forEach((key, value) -> {
            if (key.startsWith(o)) out.put(key.substring(o.length()), value);
        });
        return out;
    }

    static AppStateMachine machine(Map<String, String> settings) {
        return machine(settings, Optional.of(profile(1)));
    }

    static AppStateMachine machine(Map<String, String> settings, Optional<AppChainConsensusProfile> profile) {
        return new AssetPaymentsStateMachineProvider().create(new AppStateMachineContext() {
            @Override public String chainId() { return CHAIN; }
            @Override public Map<String, String> settings() { return settings; }
            @Override public Optional<AppChainConsensusProfile> consensusProfile() { return profile; }
        });
    }

    /** A minimal v1 consensus profile with effects disabled and the default forward skew. */
    static AppChainConsensusProfile profile(int l1StabilityDepth) {
        int message = 64 * 1024;
        return new AppChainConsensusProfile(AppChainConsensusProfile.SCHEMA_VERSION, message, 100,
                message + AppChainConfig.BLOCK_ENVELOPE_HEADROOM_BYTES + AppChainConfig.MAX_FINALITY_CERT_HEADROOM_BYTES,
                l1StabilityDepth, l1StabilityDepth, AppChainConsensusProfile.DEFAULT_BLOCK_MAX_FORWARD_SKEW_MS,
                false, false, 0, 0, 0, 0, FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT, false, List.of());
    }

    record Message(String topic, byte[] body) { }

    static Message intent(PaymentsMessageV1 message) {
        return new Message(AssetPaymentsContract.INTENT_TOPIC, message.encode());
    }

    static Message deposit(L1Observation observation) {
        return new Message(observation.topic(), observation.encode());
    }

    // ------------------------------------------------------------------ signing helpers

    static IntentEnvelopeV1 envelope(byte[] account, String action, IntentEnvelopeV1.Authorizer who, int nonce,
                                     long notAfter, byte[] body) {
        byte[] nonceBytes = new byte[16];
        nonceBytes[0] = (byte) (nonce >>> 8);
        nonceBytes[1] = (byte) nonce;
        return new IntentEnvelopeV1(INTENT_DOMAIN, action, account, who, nonceBytes, notAfter, body);
    }

    static PaymentsMessageV1.Owner owner(TestWallet wallet, IntentEnvelopeV1 envelope) {
        byte[][] cose = wallet.signData(envelope.encode());
        return new PaymentsMessageV1.Owner(cose[0], cose[1]);
    }

    static PaymentsMessageV1.Session session(byte[] sessionSeed, IntentEnvelopeV1 envelope) {
        return new PaymentsMessageV1.Session(envelope.encode(), WalletEd25519.sign(envelope.encode(), sessionSeed));
    }

    static PaymentsMessageV1.Rotation rotation(TestWallet current, TestWallet next, IntentEnvelopeV1 envelope) {
        byte[][] first = current.signData(envelope.encode());
        byte[][] second = next.signData(envelope.encode());
        return new PaymentsMessageV1.Rotation(first[0], first[1], second[0], second[1]);
    }

    static Hash32 intentId(IntentEnvelopeV1 envelope) {
        return GatewayIds.intentId(envelope.encode());
    }

    // ------------------------------------------------------------------ the ledger under test

    final TreeMap<String, byte[]> state = new TreeMap<>();
    AppStateMachine machine;
    long height;
    long senderSeq;
    private byte[] prevHash = AppBlock.GENESIS_PREV_HASH;

    PaymentsHarness() {
        this(settings());
    }

    PaymentsHarness(Map<String, String> settings) {
        machine = machine(settings);
        machine.init(writer(), null);
    }

    AppStateWriter writer() {
        return new AppStateWriter() {
            @Override public Optional<byte[]> get(byte[] key) {
                return Optional.ofNullable(state.get(HexFormat.of().formatHex(key))).map(byte[]::clone);
            }
            @Override public byte[] stateRoot() { return new byte[32]; }
            @Override public void put(byte[] key, byte[] value) { state.put(HexFormat.of().formatHex(key), value.clone()); }
            @Override public void delete(byte[] key) { state.remove(HexFormat.of().formatHex(key)); }
        };
    }

    void block(long timestamp, Message... messages) {
        height++;
        byte[] sender = filled(32, 0x5e);
        List<AppMessage> appMessages = new ArrayList<>();
        for (Message message : messages) {
            long sequence = ++senderSeq;
            appMessages.add(AppMessage.builder()
                    .messageId(AppMessage.computeMessageId(CHAIN, message.topic(), sender, sequence, 4_000_000_000L,
                            message.body()))
                    .chainId(CHAIN).topic(message.topic()).sender(sender).senderSeq(sequence)
                    .expiresAt(4_000_000_000L).body(message.body())
                    .authScheme(AuthScheme.ED25519.getValue()).authProof(new byte[64]).build());
        }
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN, height, prevHash, 0, new byte[0], timestamp,
                AppBlockCodec.messagesRoot(appMessages), new byte[32], appMessages, sender, FinalityCert.empty());
        prevHash = AppBlockCodec.blockHash(block);
        machine.apply(AppBlockExecutionContext.fromValidatedBlock(block), writer(), null);
    }

    Optional<byte[]> get(byte[] key) {
        return writer().get(key);
    }

    BigInteger balance(Hash32 vault, TestWallet wallet) {
        return get(KEYS.exitLeafKey(vault, wallet.accountId())).map(ExitLeafV1::decode).map(ExitLeafV1::amount)
                .orElse(BigInteger.ZERO);
    }

    BigInteger minAda(Hash32 vault, TestWallet wallet) {
        return get(KEYS.exitLeafKey(vault, wallet.accountId())).map(ExitLeafV1::decode)
                .map(ExitLeafV1::vaultLovelace).orElse(BigInteger.ZERO);
    }

    Optional<ExitLeafV1> leaf(Hash32 vault, TestWallet wallet) {
        return get(KEYS.exitLeafKey(vault, wallet.accountId())).map(ExitLeafV1::decode);
    }

    AssetAccounts.VaultCounters counters(Hash32 vault) {
        return get(KEYS.countersKey(vault)).map(AssetAccounts.VaultCounters::decode)
                .orElse(AssetAccounts.VaultCounters.ZERO);
    }

    Optional<PaymentsRecords.ReceiptV1> receipt(IntentEnvelopeV1 envelope) {
        return get(AssetPaymentsContract.receiptKey(intentId(envelope))).map(PaymentsRecords.ReceiptV1::decode);
    }

    Optional<DepositRecordV1> depositRecord(Hash32 depositId) {
        byte[] key = AssetPaymentsContract.PREFIX.clone();
        byte[] segment = "deposit/".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] full = TestWallet.cat(key, segment, depositId.bytes());
        return get(full).map(DepositRecordV1::decode);
    }

    void checkInvariants() {
        AssetAccounts accounts = new AssetAccounts(AssetPaymentsContract.PREFIX, new AssetAccounts.Store() {
            @Override public Optional<byte[]> get(byte[] key) { return PaymentsHarness.this.get(key); }
            @Override public void put(byte[] key, byte[] value) { throw new IllegalStateException(); }
            @Override public void delete(byte[] key) { throw new IllegalStateException(); }
        });
        for (Hash32 vault : List.of(ADA_VAULT, USDM_VAULT, EURC_VAULT)) accounts.checkInvariant(vault);
    }
}
