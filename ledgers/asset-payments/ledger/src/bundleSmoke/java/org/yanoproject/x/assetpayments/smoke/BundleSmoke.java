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
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.RedeemerTag;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import org.yanoproject.x.framework.api.appchain.*;
import org.yanoproject.x.framework.api.appchain.codec.AppBlockCodec;
import org.yanoproject.x.framework.api.appchain.effects.EffectOutcomeCommitment;
import org.yanoproject.x.framework.api.appchain.effects.FinalityGate;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverProvider;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoff;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoffGate;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoffGateProvider;

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
 * and credits that deposit; the payout observer reads a thread-authenticated Settle, which the
 * machine (holding no such reservation) records as a mismatch and halts on; the anchor and
 * shard-bootstrap observers load; and the membership handoff gate finalizes on an observed anchor.
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
        settlement(gateway, payments, observerSettings, settings, profile, usdmVault);
        executor(gateway, observerSettings);
        System.out.println("bundle smoke OK: observer " + observerProvider.getClass().getName() + ", machine "
                + machine.getClass().getName() + ", " + state.size() + " state keys, 1 receipt, 1 credited leaf");
    }

    /** The M4 contributions: payout, anchor and shard-bootstrap observers and the handoff gate. */
    static void settlement(ClassLoader gateway, ClassLoader payments, Map<String, String> observerSettings,
                           Map<String, String> ledgerSettings, AppChainConsensusProfile profile, String usdmVault)
            throws Exception {
        String shardPolicy = "a2".repeat(28);
        String shardAddress = AddressProvider.getEntAddress(Credential.fromScript(fill(28, 0x12)), Networks.testnet())
                .getAddress();
        Map<String, String> payoutSettings = new TreeMap<>(observerSettings);
        payoutSettings.put("vaults.0.shard-policy-id", shardPolicy);
        payoutSettings.put("vaults.0.shard-script-hash", "12".repeat(28));
        payoutSettings.put("vaults.0.shard-address", shardAddress);
        L1ObserverProvider payoutProvider = only(ServiceLoader.load(L1ObserverProvider.class, gateway),
                p -> p.type().equals("asset-vault-payout-v1"));
        if (payoutProvider.getClass().getClassLoader() != gateway) throw new AssertionError("payout not from bundle");
        payoutProvider.consensusIdentity("payouts", payoutSettings);
        for (String type : List.of("asset-shard-bootstrap-v1", "asset-anchor-v1")) {
            only(ServiceLoader.load(L1ObserverProvider.class, gateway), p -> p.type().equals(type));
        }
        only(ServiceLoader.load(L1ObserverProvider.class, gateway), p -> p.type().equals("asset-shard-bootstrap-v1"))
                .consensusIdentity("shards", payoutSettings);

        // A Settle of one claim on shard 3: payout, VaultContinuationV1([id]), the shard thread output.
        byte[] claimId = fill(32, 0x33);
        String recipient = AddressProvider.getEntAddress(Credential.fromKey(fill(28, 0x77)), Networks.testnet())
                .getAddress();
        Amount usdm = Amount.builder().unit(POLICY + ".5553444d").policyId(POLICY).assetNameBytes(hex("5553444d"))
                .quantity(BigInteger.TEN).build();
        Amount shardToken = Amount.builder().unit(shardPolicy + ".03").policyId(shardPolicy)
                .assetNameBytes(new byte[] {3}).quantity(BigInteger.ONE).build();
        TransactionBody settle = TransactionBody.builder().txHash("bb".repeat(32)).mint(List.of()).outputs(List.of(
                TransactionOutput.builder().address(recipient).amounts(List.of(lovelace(1_500_000), usdm)).build(),
                TransactionOutput.builder().address(usdmVault).amounts(List.of(lovelace(2_000_000)))
                        .inlineDatum("d87a81815820" + HexFormat.of().formatHex(claimId)).build(),
                TransactionOutput.builder().address(shardAddress).amounts(List.of(lovelace(1_200_000), shardToken))
                        .inlineDatum("d87983010358" + "20" + "07".repeat(32)).build())).build();
        Witnesses witnesses = Witnesses.builder().redeemers(List.of(Redeemer.builder().tag(RedeemerTag.Spend).index(0)
                .data(Datum.builder().cbor("d8798281d879815820" + HexFormat.of().formatHex(claimId) + "03").build())
                .build())).build();
        List<L1Observation> payouts = payoutProvider.create("payouts", payoutSettings).observe(2_000, fill(32, 0xb2),
                Block.builder().transactionBodies(List.of(settle)).transactionWitness(List.of(witnesses)).build());
        if (payouts.size() != 1) throw new AssertionError("expected one payout observation, got " + payouts);

        Map<String, String> settings = new TreeMap<>(ledgerSettings);
        settings.put("machines.asset-payments.payout-observer-id", "payouts");
        payoutSettings.forEach((k, v) -> settings.put("observers.payouts." + k, v));
        settings.put("observers.payouts.type", "asset-vault-payout-v1");
        AppStateMachineProvider machineProvider = only(ServiceLoader.load(AppStateMachineProvider.class, payments),
                p -> p.id().equals("asset-payments"));
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
        String statusKey = HexFormat.of().formatHex("ap/domain-status".getBytes(StandardCharsets.US_ASCII));
        apply(machine, writer, 1_764_000_000_000L, 1, message("ordinary", new byte[] {1}, 1));
        if (!HexFormat.of().formatHex(state.get(statusKey)).startsWith("850100")) {
            throw new AssertionError("genesis status leaf is not ACTIVE");
        }
        apply(machine, writer, 1_764_000_001_000L, 2, message("~l1/payouts", payouts.getFirst().encode(), 2));
        // A thread-authenticated payout of a claim this ledger never reserved is a mismatch: HALTED (D10).
        if (!HexFormat.of().formatHex(state.get(statusKey)).startsWith("850102")) {
            throw new AssertionError("an unreserved thread-authenticated payout did not halt the domain");
        }

        // The handoff gate: the observed anchor output carrying the pending set finalizes the pending change.
        Map<String, String> anchorSettings = new TreeMap<>(Map.of("type", "asset-anchor-v1",
                "anchor.thread-policy-id", "c1".repeat(28), "anchor.thread-asset-name", "",
                "anchor.script-hash", "21".repeat(28), "anchor.genesis-id", GENESIS,
                "anchor.application-id", "asset-payments", "anchor.commitment-profile", "mpf-blake2b256-v1",
                "anchor.format-fingerprint", "fa".repeat(32)));
        Map<String, String> chain = new TreeMap<>(Map.of("membership.mode", "governed",
                "membership.anchor-handoff", "true", "membership.anchor-handoff-gate", "asset-anchor-handoff-v1",
                "anchor-profile.version", "2"));
        anchorSettings.forEach((k, v) -> chain.put("observers.anchors." + k, v));
        // D8b: this custody profile pins its anchor identity to the one the observer watches.
        anchorSettings.forEach((k, v) -> {
            if (k.startsWith("anchor.")) chain.put("anchor-profile.pinned." + k.substring("anchor.".length()), v);
        });
        MembershipHandoffGateProvider gateProvider = only(ServiceLoader.load(MembershipHandoffGateProvider.class,
                gateway), p -> p.id().equals("asset-anchor-handoff-v1"));
        if (gateProvider.getClass().getClassLoader() != gateway) throw new AssertionError("gate not from bundle");
        MembershipHandoffGate gate = gateProvider.create(CHAIN, chain);
        String member = "0a".repeat(32);
        String anchorDatum = "d8798c02" + "4a" + HexFormat.of().formatHex(CHAIN.getBytes(StandardCharsets.UTF_8))
                + "5820" + GENESIS + "4e" + HexFormat.of().formatHex("asset-payments".getBytes(StandardCharsets.UTF_8))
                + "51" + HexFormat.of().formatHex("mpf-blake2b256-v1".getBytes(StandardCharsets.UTF_8))
                + "5820" + "fa".repeat(32) + "07" + "5820" + "bb".repeat(32) + "5820" + "55".repeat(32)
                + "815820" + member + "01" + "1903e8";
        Amount thread = Amount.builder().unit("c1".repeat(28)).policyId("c1".repeat(28)).assetNameBytes(new byte[0])
                .quantity(BigInteger.ONE).build();
        String anchorAddress = AddressProvider.getEntAddress(Credential.fromScript(fill(28, 0x21)),
                Networks.testnet()).getAddress();
        TransactionBody advance = TransactionBody.builder().txHash("cc".repeat(32)).mint(List.of()).outputs(List.of(
                TransactionOutput.builder().address(anchorAddress).amounts(List.of(lovelace(2_000_000), thread))
                        .inlineDatum(anchorDatum).build())).build();
        L1ObserverProvider anchorProvider = only(ServiceLoader.load(L1ObserverProvider.class, gateway),
                p -> p.type().equals("asset-anchor-v1"));
        List<L1Observation> anchors = anchorProvider.create("anchors", anchorSettings).observe(3_000, fill(32, 0xb3),
                Block.builder().transactionBodies(List.of(advance)).build());
        if (anchors.size() != 1) throw new AssertionError("expected one anchor observation, got " + anchors);
        List<AppMessage> list = List.of(message("~l1/anchors", anchors.getFirst().encode(), 3));
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN, 9, AppBlock.GENESIS_PREV_HASH, 0, new byte[0],
                1_000, AppBlockCodec.messagesRoot(list), new byte[32], list, new byte[32], FinalityCert.empty());
        if (!gate.finalizesHandoff(block, new MembershipHandoff(7, java.util.Set.of(member), 1, 1))
                || gate.finalizesHandoff(block, new MembershipHandoff(8, java.util.Set.of(member), 1, 1))) {
            throw new AssertionError("the handoff gate did not bind the anchor fact to its committed height");
        }
    }

    /**
     * The asset-settlement effect executor (ADR-059 D9): the factory loads from the bundle, a member's context-aware
     * creation registers the co-sign service and yields a product that owns no effect type, and the service's body
     * check (the support library's builder and checks over host cardano-client-lib) refuses a malformed request.
     */
    static void executor(ClassLoader gateway, Map<String, String> observerSettings) {
        org.yanoproject.x.framework.cardano.api.effects.AppEffectExecutorFactory factory = only(ServiceLoader.load(
                org.yanoproject.x.framework.cardano.api.effects.AppEffectExecutorFactory.class, gateway),
                f -> f.scheme().equals("asset-settlement"));
        if (factory.getClass().getClassLoader() != gateway) throw new AssertionError("executor not from bundle");
        if (!factory.create(CHAIN, Map.of()).isEmpty()) throw new AssertionError("config-only creation must decline");
        Map<String, String> config = new TreeMap<>(observerSettings);
        config.put("vaults.0.shard-policy-id", "a2".repeat(28));
        config.put("vaults.0.shard-script-hash", "12".repeat(28));
        config.put("vaults.0.shard-address", AddressProvider.getEntAddress(Credential.fromScript(fill(28, 0x12)),
                Networks.testnet()).getAddress());
        config.put("vaults.0.script-ref", "65".repeat(32) + "#0");
        config.put("vaults.0.shard-script-ref", "65".repeat(32) + "#1");
        config.putAll(Map.of("anchor.thread-policy-id", "c1".repeat(28), "anchor.thread-asset-name", "",
                "anchor.script-hash", "21".repeat(28), "anchor.genesis-id", GENESIS,
                "anchor.application-id", "asset-payments", "anchor.commitment-profile", "mpf-blake2b256-v1",
                "anchor.format-fingerprint", "fa".repeat(32), "exit-mode.policy-id", "d1".repeat(28),
                "exit-mode.script-hash", "31".repeat(28), "continuation-floor", "2000000"));
        // Every member bounds the validity interval it signs by its own L1 time (ADR-059 D8h §1 step 0).
        config.putAll(Map.of("slot.zero-time-ms", "0", "slot.zero-slot", "0", "slot.length-ms", "1000"));
        config.remove("min-stability-depth");
        org.yanoproject.x.framework.api.appchain.l1view.BridgeDiffusionHandler[] registered =
                new org.yanoproject.x.framework.api.appchain.l1view.BridgeDiffusionHandler[1];
        byte[] seed = fill(32, 7);
        var context = new org.yanoproject.x.framework.cardano.api.effects.AppChainEffectContext() {
            public String chainId() { return CHAIN; }
            public void diffuse(String topic, byte[] body) { throw new AssertionError("a refused body is never signed"); }
            public org.yanoproject.x.framework.api.appchain.signer.SignerProvider memberSigner() {
                return new org.yanoproject.x.framework.api.appchain.signer.SignerProvider() {
                    public byte[] sign(byte[] message) { return new EdDSASigningProvider().sign(message, seed); }
                    public byte[] publicKey() { return KeyGenUtil.getPublicKeyFromPrivateKey(seed); }
                };
            }
            public java.util.function.Supplier<Set<String>> members() { return Set::of; }
            public java.util.function.IntSupplier threshold() { return () -> 1; }
            public AppQueryResult query(String path, byte[] request) { return null; }
            public java.util.function.Supplier<org.yanoproject.api.utxo.UtxoState> l1UtxoView() { return () -> null; }
            public java.util.function.Supplier<com.bloxbean.cardano.client.api.model.ProtocolParams> protocolParams() {
                return com.bloxbean.cardano.client.api.model.ProtocolParams::new;
            }
            public org.yanoproject.api.TxEvaluationGateway txEvaluation() { return null; }
            public String submitTx(byte[] transactionCbor) { throw new AssertionError("a member never submits"); }
            public void registerBridgeDiffusionHandler(
                    org.yanoproject.x.framework.api.appchain.l1view.BridgeDiffusionHandler handler) {
                registered[0] = handler;
            }
        };
        var products = factory.create(CHAIN, config, context);
        if (products.size() != 1 || !products.getFirst().effectTypes().isEmpty() || registered[0] == null) {
            throw new AssertionError("a member gets the co-sign service and a product that owns no effect type");
        }
        byte[] request = cat(hex("8201"), head(3), new byte[] {1, 2, 3});
        registered[0].onBridgeMessage(message("~bridge/asset-settlement/sign", request, 9));
    }

    static Amount lovelace(long quantity) {
        return Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(quantity)).build();
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
