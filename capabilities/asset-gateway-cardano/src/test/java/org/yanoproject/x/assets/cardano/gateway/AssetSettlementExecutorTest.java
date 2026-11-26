package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.TxEvaluationGateway;
import org.yanoproject.api.model.TxEvaluationResult;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.utxo.model.AssetAmount;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.assets.contracts.SettlementRecords;
import org.yanoproject.x.framework.api.appchain.AppQueryResult;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectExecutor;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecution;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutionContext;
import org.yanoproject.x.framework.api.appchain.effects.EffectRecord;
import org.yanoproject.x.framework.api.appchain.effects.FinalityGate;
import org.yanoproject.x.framework.api.appchain.effects.PendingEffect;
import org.yanoproject.x.framework.api.appchain.effects.ResultPolicy;
import org.yanoproject.x.framework.api.appchain.l1view.BridgeDiffusionHandler;
import org.yanoproject.x.framework.api.appchain.signer.SignerProvider;
import org.yanoproject.x.framework.cardano.api.effects.AppChainEffectContext;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code asset-settlement} executor and co-sign service through the real factory (ADR-059 D9, D8h): the owner
 * turns a committed batch into a {@code Settle} that every member checks against its own state before signing; the
 * owner submits it with both members' and the fee wallet's witnesses; inclusion confirms the effect without changing
 * any claim (I12). A member whose state does not show a claim refuses, so the round fails closed; a claim above the
 * live anchor is not built (V12); a non-owner owns no effect type.
 */
class AssetSettlementExecutorTest {
    static final String CHAIN = "payments-1";
    static final String GENESIS = "6c".repeat(32);
    static final AssetId USDM = AssetId.ofHex("f0".repeat(28), "5553444d");
    static final byte[] VAULT_HASH = filled(28, 0x02);
    static final byte[] SHARD_HASH = filled(28, 0x12);
    static final String SHARD_POLICY = "a2".repeat(28);
    static final byte[] ANCHOR_HASH = filled(28, 0x21);
    static final String ANCHOR_POLICY = "c1".repeat(28);
    static final byte[] EXIT_HASH = filled(28, 0x31);
    static final String EXIT_POLICY = "d1".repeat(28);
    static final String FINGERPRINT = "fa".repeat(32);
    static final Hash32 DOMAIN = GatewayIds.domainId(0, 42, CHAIN, Hash32.ofHex(GENESIS), "asset-payments",
            "anchored-claim-vault-v1", 1);
    static final Hash32 VAULT = GatewayIds.vaultId(DOMAIN, USDM, 0);
    static final long NOW = 1_764_000_000_000L;
    static final byte[] OWNER_SEED = filled(32, 0x41);
    static final byte[] MEMBER_SEED = filled(32, 0x42);
    static final byte[] FEE_SEED = filled(32, 0x43);

    @TempDir
    Path temp;

    static byte[] filled(int length, int value) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    static String enterprise(byte[] scriptHash) {
        return AddressProvider.getEntAddress(Credential.fromScript(scriptHash), Networks.testnet()).getAddress();
    }

    // ------------------------------------------------------------------ the shared L1 and the per-node state

    /** One L1 seen identically by both nodes. */
    final Map<String, Utxo> l1 = new LinkedHashMap<>();
    final List<AppMessage> bus = new ArrayList<>();
    final List<byte[]> submitted = new ArrayList<>();

    void put(Utxo utxo) {
        l1.put(utxo.outpoint().txHash() + "#" + utxo.outpoint().index(), utxo);
    }

    static Utxo utxo(String txHash, int index, String address, long lovelace, List<AssetAmount> assets, byte[] datum,
                     String scriptHash) {
        return new Utxo(new Outpoint(txHash, index), address, BigInteger.valueOf(lovelace), assets, null, datum,
                scriptHash == null ? null : "00".repeat(5_000), scriptHash, false, 100, 10, "bb".repeat(32));
    }

    UtxoState view() {
        return new UtxoState() {
            @Override
            public List<Utxo> getUtxosByAddress(String address, int page, int count) {
                if (page > 1) return List.of();
                return l1.values().stream().filter(u -> u.address().equals(address)).toList();
            }

            @Override
            public List<Utxo> getUtxosByPaymentCredential(String credential, int page, int count) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getUtxo(Outpoint outpoint) {
                return Optional.ofNullable(l1.get(outpoint.txHash() + "#" + outpoint.index()));
            }

            @Override
            public List<Utxo> getOutputsByTxHash(String txHash) {
                return l1.values().stream().filter(u -> u.outpoint().txHash().equals(txHash)).toList();
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
    }

    static byte[] serialize(PlutusData data) {
        try {
            return data.serializeToBytes();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static List<AssetAmount> asset(String policy, String name, long quantity) {
        return List.of(new AssetAmount(policy, name, BigInteger.valueOf(quantity)));
    }

    /** The L1 world: accepted custody, shard 0..15 empty, the anchor at {@code anchorHeight}, the exit-mode thread. */
    void seed(long anchorHeight) {
        put(utxo("61".repeat(32), 0, enterprise(VAULT_HASH), 50_000_000, asset("f0".repeat(28), "5553444d", 100_000),
                HexFormat.of().parseHex("d87a8180"), null));
        for (int shard = 0; shard < 16; shard++) {
            put(utxo("62".repeat(32), shard, enterprise(SHARD_HASH), 2_000_000,
                    asset(SHARD_POLICY, String.format("%02x", shard), 1),
                    serialize(ConstrPlutusData.of(0, BigIntPlutusData.of(1), BigIntPlutusData.of(shard),
                            BytesPlutusData.of(new byte[32]))), null));
        }
        List<PlutusData> members = new ArrayList<>();
        new TreeMap<String, byte[]>(Map.of(HexFormat.of().formatHex(publicKey(OWNER_SEED)), publicKey(OWNER_SEED),
                HexFormat.of().formatHex(publicKey(MEMBER_SEED)), publicKey(MEMBER_SEED)))
                .values().forEach(key -> members.add(BytesPlutusData.of(key)));
        PlutusData anchor = ConstrPlutusData.of(0, BigIntPlutusData.of(2), BytesPlutusData.of(CHAIN.getBytes()),
                BytesPlutusData.of(HexFormat.of().parseHex(GENESIS)), BytesPlutusData.of("asset-payments".getBytes()),
                BytesPlutusData.of("mpf-blake2b256-v1".getBytes()), BytesPlutusData.of(HexFormat.of().parseHex(FINGERPRINT)),
                BigIntPlutusData.of(anchorHeight), BytesPlutusData.of(filled(32, 0x66)), BytesPlutusData.of(filled(32, 0x55)),
                ListPlutusData.builder().plutusDataList(members).build(), BigIntPlutusData.of(2),
                BigIntPlutusData.of(NOW - 60_000));
        put(utxo("63".repeat(32), 0, enterprise(ANCHOR_HASH), 2_000_000, asset(ANCHOR_POLICY, "", 1),
                serialize(anchor), null));
        put(utxo("64".repeat(32), 0, enterprise(EXIT_HASH), 2_000_000, asset(EXIT_POLICY, "", 1),
                HexFormat.of().parseHex("d87980"), null));
        String feeAddress = AddressProvider.getEntAddress(Credential.fromKey(Blake2bUtil.blake2bHash224(
                publicKey(FEE_SEED))), Networks.testnet()).getAddress();
        put(utxo("65".repeat(32), 0, feeAddress, 30_000_000, List.of(), null, HexFormat.of().formatHex(VAULT_HASH)));
        put(utxo("65".repeat(32), 1, feeAddress, 30_000_000, List.of(), null, HexFormat.of().formatHex(SHARD_HASH)));
        put(utxo("60".repeat(32), 0, feeAddress, 80_000_000, List.of(), null, null));
    }

    static byte[] publicKey(byte[] seed) {
        return KeyGenUtil.getPublicKeyFromPrivateKey(seed);
    }

    /** A node's committed claim state, as its ledger would answer the SettlementQueries. */
    static final class Ledger {
        final Map<Hash32, ClaimLeafV1> leaves = new LinkedHashMap<>();
        final Map<Hash32, SettlementRecords.ClaimStatusV1> status = new HashMap<>();
        DomainStatusV1 domain = new DomainStatusV1(DomainStatusV1.Status.ACTIVE, 0, 0, 86_400_000L);

        void reserve(ClaimLeafV1 leaf) {
            leaves.put(leaf.claimId(), leaf);
            status.put(leaf.claimId(), SettlementRecords.ClaimStatusV1.reserved(leaf.claimId(), leaf.reservedHeight()));
        }

        byte[] answer(String path, byte[] request) {
            return switch (path) {
                case SettlementQueries.CLAIM -> {
                    Hash32 id = Hash32.of(request);
                    yield SettlementQueries.encodeClaim(Optional.ofNullable(leaves.get(id)).map(ClaimLeafV1::encode),
                            Optional.ofNullable(status.get(id)).map(SettlementRecords.ClaimStatusV1::encode));
                }
                case SettlementQueries.DOMAIN_STATUS -> domain.encode();
                case SettlementQueries.CLAIM_COUNT -> SettlementQueries.encodeCount(leaves.size());
                case SettlementQueries.CLAIM_BY_SEQUENCE -> new ArrayList<>(leaves.keySet())
                        .get((int) SettlementQueries.decodeSequence(request)).bytes();
                default -> throw new IllegalArgumentException(path);
            };
        }
    }

    final class Node implements AppChainEffectContext {
        final byte[] seed;
        final Ledger ledger = new Ledger();
        BridgeDiffusionHandler handler;

        Node(byte[] seed) {
            this.seed = seed;
        }

        @Override public String chainId() { return CHAIN; }

        @Override
        public void diffuse(String topic, byte[] body) {
            synchronized (bus) {
                bus.add(AppMessage.builder().chainId(CHAIN).topic(topic).sender(publicKey(seed)).body(body).build());
            }
        }

        @Override
        public SignerProvider memberSigner() {
            return new SignerProvider() {
                @Override public byte[] sign(byte[] message) {
                    return CryptoConfiguration.INSTANCE.getSigningProvider().sign(message, seed);
                }

                @Override public byte[] publicKey() { return AssetSettlementExecutorTest.publicKey(seed); }
            };
        }

        @Override public Supplier<Set<String>> members() { return Set::of; }

        @Override public IntSupplier threshold() { return () -> 2; }

        @Override
        public AppQueryResult query(String path, byte[] request) {
            return new AppQueryResult(CHAIN, "asset-payments", 1, new byte[32], ledger.answer(path, request));
        }

        @Override public Supplier<UtxoState> l1UtxoView() { return AssetSettlementExecutorTest.this::view; }

        @Override public Supplier<ProtocolParams> protocolParams() { return AssetSettlementExecutorTest::params; }

        @Override
        public TxEvaluationGateway txEvaluation() {
            return new TxEvaluationGateway() {
                @Override public boolean isTransactionEvaluationAvailable() { return true; }

                @Override
                public List<TxEvaluationResult> evaluateTransaction(byte[] cbor) throws Exception {
                    List<TxEvaluationResult> out = new ArrayList<>();
                    for (var redeemer : Transaction.deserialize(cbor).getWitnessSet().getRedeemers()) {
                        out.add(new TxEvaluationResult("spend", redeemer.getIndex().intValue(), 3_000_000L,
                                900_000_000L));
                    }
                    return out;
                }
            };
        }

        @Override
        public String submitTx(byte[] transactionCbor) {
            submitted.add(transactionCbor);
            try {
                return TransactionUtil.getTxHash(transactionCbor);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public void registerBridgeDiffusionHandler(BridgeDiffusionHandler handler) {
            this.handler = handler;
        }
    }

    static ProtocolParams params() {
        ProtocolParams pp = new ProtocolParams();
        pp.setMinFeeA(44);
        pp.setMinFeeB(155_381);
        pp.setMaxTxSize(16_384);
        pp.setPriceMem(new java.math.BigDecimal("0.0577"));
        pp.setPriceStep(new java.math.BigDecimal("0.0000721"));
        pp.setMaxTxExMem("16500000");
        pp.setMaxTxExSteps("10000000000");
        pp.setCollateralPercent(new java.math.BigDecimal(150));
        pp.setCoinsPerUtxoSize("4310");
        pp.setMinFeeRefScriptCostPerByte(new java.math.BigDecimal(15));
        LinkedHashMap<String, List<Long>> raw = new LinkedHashMap<>();
        raw.put("PlutusV3", Arrays.stream(CostModelUtil.plutusV3Costs).boxed().toList());
        pp.setCostModelsRaw(raw);
        return pp;
    }

    Map<String, String> config(boolean owner) throws Exception {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("domain.address-network-id", "0");
        config.put("domain.network-magic", "42");
        config.put("domain.chain-id", CHAIN);
        config.put("domain.genesis-id", GENESIS);
        config.put("vaults.0.asset", "f0".repeat(28) + ".5553444d");
        config.put("vaults.0.script-hash", HexFormat.of().formatHex(VAULT_HASH));
        config.put("vaults.0.address", enterprise(VAULT_HASH));
        config.put("vaults.0.shard-policy-id", SHARD_POLICY);
        config.put("vaults.0.shard-script-hash", HexFormat.of().formatHex(SHARD_HASH));
        config.put("vaults.0.shard-address", enterprise(SHARD_HASH));
        config.put("vaults.0.script-ref", "65".repeat(32) + "#0");
        config.put("vaults.0.shard-script-ref", "65".repeat(32) + "#1");
        config.put("anchor.thread-policy-id", ANCHOR_POLICY);
        config.put("anchor.thread-asset-name", "");
        config.put("anchor.script-hash", HexFormat.of().formatHex(ANCHOR_HASH));
        config.put("anchor.genesis-id", GENESIS);
        config.put("anchor.application-id", "asset-payments");
        config.put("anchor.commitment-profile", "mpf-blake2b256-v1");
        config.put("anchor.format-fingerprint", FINGERPRINT);
        config.put("exit-mode.policy-id", EXIT_POLICY);
        config.put("exit-mode.script-hash", HexFormat.of().formatHex(EXIT_HASH));
        config.put("continuation-floor", "2000000");
        config.put("round-timeout-ms", "200");
        if (owner) {
            Path key = temp.resolve("fee.skey");
            Files.writeString(key, HexFormat.of().formatHex(FEE_SEED));
            config.put("owner", "true");
            config.put("fee-wallet.key-file", key.toString());
            config.put("slot.zero-time-ms", "0");
            config.put("slot.zero-slot", "0");
            config.put("slot.length-ms", "1000");
        }
        return config;
    }

    static ClaimLeafV1 claim(int shard, int seed, long height) {
        byte[] id = Blake2bUtil.blake2bHash256(("claim-" + seed).getBytes());
        id[31] = (byte) ((id[31] & 0xf0) | shard);
        return new ClaimLeafV1(Hash32.of(id), VAULT, ClaimKind.USER_WITHDRAWAL, AssetValue.single(USDM,
                BigInteger.valueOf(100 + seed)), BigInteger.valueOf(1_500_000), concat(new byte[] {0x60},
                filled(28, 0x80 + seed)), height, NOW - 86_400_000L);
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static PendingEffect effect(SettlementBatchV1 batch) {
        return PendingEffect.of(new EffectRecord(EffectRecord.RECORD_VERSION, CHAIN, 5, 0,
                SettlementBatchV1.EFFECT_TYPE, batch.encode(), SettlementBatchV1.scope(0), FinalityGate.APP_FINAL,
                ResultPolicy.CHAIN, 100, null));
    }

    static EffectExecutionContext attempt(int number, byte[] submittedRef) {
        return new EffectExecutionContext() {
            @Override public String chainId() { return CHAIN; }

            @Override public long tipHeight() { return 10; }

            @Override public long anchoredHeight() { return 10; }

            @Override public int attempt() { return number; }

            @Override public byte[] submittedRef() { return submittedRef; }

            @Override public Map<String, String> settings() { return Map.of(); }
        };
    }

    /** Delivers diffused messages to every other node until the bus is quiet, on a helper thread. */
    void pump(Node... nodes) {
        Thread pump = new Thread(() -> {
            long until = System.currentTimeMillis() + 2_000;
            int delivered = 0;
            while (System.currentTimeMillis() < until) {
                List<AppMessage> pending;
                synchronized (bus) {
                    pending = new ArrayList<>(bus.subList(delivered, bus.size()));
                }
                for (AppMessage message : pending) {
                    for (Node node : nodes) {
                        if (!Arrays.equals(publicKey(node.seed), message.getSender())) node.handler.onBridgeMessage(message);
                    }
                }
                delivered += pending.size();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        });
        pump.setDaemon(true);
        pump.start();
    }

    AppEffectExecutor executor(Node node, boolean owner) throws Exception {
        List<AppEffectExecutor> products = new AssetSettlementExecutorFactory().create(CHAIN, config(owner), node);
        assertThat(products).hasSize(1);
        return products.getFirst();
    }

    @Test
    void theOwnerSettlesABatchThatEveryMemberVerifiedAndInclusionConfirmsWithoutTouchingClaims() throws Exception {
        seed(500);
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        AppEffectExecutor executor = executor(owner, true);
        assertThat(executor(member, false).effectTypes()).as("a non-owner owns no effect type").isEmpty();
        List<ClaimLeafV1> claims = List.of(claim(3, 1, 400), claim(3, 2, 450));
        for (ClaimLeafV1 leaf : claims) {
            owner.ledger.reserve(leaf);
            member.ledger.reserve(leaf);
        }
        pump(owner, member);
        PendingEffect effect = effect(new SettlementBatchV1(VAULT, 3,
                claims.stream().map(ClaimLeafV1::claimId).toList()));

        EffectExecution first = executor.execute(attempt(1, new byte[0]), effect);

        assertThat(first).isInstanceOf(EffectExecution.Submitted.class);
        assertThat(submitted).hasSize(1);
        Transaction tx = Transaction.deserialize(submitted.getFirst());
        byte[] bodyHash = HexFormat.of().parseHex(TransactionUtil.getTxHash(tx));
        assertThat(((EffectExecution.Submitted) first).externalRef()).isEqualTo(bodyHash);
        List<VkeyWitness> witnesses = tx.getWitnessSet().getVkeyWitnesses();
        assertThat(witnesses).hasSize(3);
        for (VkeyWitness witness : witnesses) {
            assertThat(CryptoConfiguration.INSTANCE.getSigningProvider().verify(witness.getSignature(), bodyHash,
                    witness.getVkey())).isTrue();
        }
        assertThat(tx.getBody().getRequiredSigners()).hasSize(2);

        // Not yet on L1: still submitted. Once included, the effect confirms; no claim status changes (I12).
        assertThat(executor.execute(attempt(2, bodyHash), effect)).isInstanceOf(EffectExecution.Submitted.class);
        put(utxo(HexFormat.of().formatHex(bodyHash), 0, "addr_test1vz", 1, List.of(), null, null));
        assertThat(executor.execute(attempt(3, bodyHash), effect)).isInstanceOf(EffectExecution.Confirmed.class);
        for (ClaimLeafV1 leaf : claims) {
            assertThat(owner.ledger.status.get(leaf.claimId()).status())
                    .isEqualTo(SettlementRecords.ClaimStatus.RESERVED);
        }
    }

    @Test
    void aMemberThatCannotSeeAClaimRefusesSoTheRoundFailsClosed() throws Exception {
        seed(500);
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        AppEffectExecutor executor = executor(owner, true);
        executor(member, false);
        ClaimLeafV1 leaf = claim(5, 3, 400);
        owner.ledger.reserve(leaf); // the member's committed state does not have it
        pump(owner, member);
        EffectExecution result = executor.execute(attempt(1, new byte[0]),
                effect(new SettlementBatchV1(VAULT, 5, List.of(leaf.claimId()))));
        assertThat(result).isInstanceOf(EffectExecution.Failed.class);
        assertThat(((EffectExecution.Failed) result).retryable()).isTrue();
        assertThat(submitted).isEmpty();
        AssetCosignService service = (AssetCosignService) member.handler;
        assertThat(service.lastRefusal()).hasValueSatisfying(refusal ->
                assertThat(refusal.detail()).contains("unknown claim"));
    }

    @Test
    void aClaimAboveTheLiveAnchorWaitsAndAPausedDomainWaits() throws Exception {
        seed(399);
        Node owner = new Node(OWNER_SEED);
        AppEffectExecutor executor = executor(owner, true);
        ClaimLeafV1 leaf = claim(6, 4, 400);
        owner.ledger.reserve(leaf);
        PendingEffect effect = effect(new SettlementBatchV1(VAULT, 6, List.of(leaf.claimId())));
        assertThat(executor.execute(attempt(1, new byte[0]), effect)).isInstanceOf(EffectExecution.Retry.class);
        l1.clear();
        seed(400);
        owner.ledger.domain = new DomainStatusV1(DomainStatusV1.Status.PAUSED, 1, 1, 86_400_000L);
        assertThat(executor.execute(attempt(2, new byte[0]), effect)).isInstanceOf(EffectExecution.Retry.class);
        assertThat(submitted).isEmpty();
    }
}
