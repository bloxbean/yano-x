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
import org.yanoproject.x.assets.cardano.support.settlement.ShardBackend;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code asset-settlement} executor and co-sign service through the real factory (ADR-059 D9, D8h): the owner
 * turns a committed batch into a {@code Settle} that every member checks against its own state before signing; the
 * owner submits it with both members' and the fee wallet's witnesses; the effect confirms only once committed state
 * shows its claims resolved, never on tip inclusion, and the executor changes no claim (I12). A member whose state
 * does not show a claim refuses, so the round fails closed; a claim above the live anchor is not built (V12); a
 * non-owner owns no effect type. The review fixes: unfunded payouts are left for Exit (D11), a missing signer is
 * rotated out on retry, the executor splits at its cap and on transaction limits, and a restart keeps the original
 * deadline.
 */
class AssetSettlementExecutorTest {
    static final String CHAIN = "payments-1";
    static final String GENESIS = "6c".repeat(32);
    static final AssetId USDM = AssetId.ofHex("f0".repeat(28), "5553444d");
    static final byte[] VAULT_HASH = filled(28, 0x02);
    static final String SHARD_POLICY = "a2".repeat(28);
    static final byte[] SHARD_HASH = ShardBackend.JULC.derive(HexFormat.of().parseHex(SHARD_POLICY), VAULT_HASH, 0)
            .scriptHash();
    static final byte[] ADA_SHARD_HASH = ShardBackend.JULC.derive(filled(28, 0xa3), filled(28, 0x03), 0)
            .scriptHash();
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
    static final byte[] THIRD_SEED = filled(32, 0x44);
    static final byte[] FOURTH_SEED = filled(32, 0x45);
    static final byte[] FIFTH_SEED = filled(32, 0x46);

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
    final List<byte[]> submitted = java.util.Collections.synchronizedList(new ArrayList<>());
    /** This node's wall clock, which the tests move. */
    long now = NOW;
    int maxTxSize = 16_384;
    /** Transactions the fake evaluator prices over the per-transaction memory limit. */
    java.util.function.Predicate<Transaction> overBudget = tx -> false;

    void put(Utxo utxo) {
        l1.put(utxo.outpoint().txHash() + "#" + utxo.outpoint().index(), utxo);
    }

    static Utxo utxo(String txHash, int index, String address, long lovelace, List<AssetAmount> assets, byte[] datum,
                     String scriptHash) {
        return new Utxo(new Outpoint(txHash, index), address, BigInteger.valueOf(lovelace), assets, null, datum,
                scriptHash == null ? null : "00".repeat(5_000), scriptHash, false, 100, 10, "bb".repeat(32));
    }

    /** The node's view; its applied tip follows the clock, one slot per second (ADR-059 D9 r22 holds read it). */
    interface TippedView extends UtxoState, org.yanoproject.api.rollback.RollbackCapableStore {
        @Override default String storeName() { return "test-utxo"; }

        @Override default long getRollbackFloorSlot() { return 0; }

        @Override default void rollbackToSlot(long slot) { throw new UnsupportedOperationException(); }
    }

    UtxoState view() {
        return new TippedView() {
            @Override
            public long getLatestAppliedSlot() {
                return now / 1_000;
            }

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
        seed(anchorHeight, OWNER_SEED, MEMBER_SEED);
    }

    /** The same world with the anchor's member set the keys of {@code memberSeeds}, threshold 2. */
    void seed(long anchorHeight, byte[]... memberSeeds) {
        seed(anchorHeight, 2, memberSeeds);
    }

    void seed(long anchorHeight, int threshold, byte[]... memberSeeds) {
        put(utxo("61".repeat(32), 0, enterprise(VAULT_HASH), 50_000_000, asset("f0".repeat(28), "5553444d", 100_000),
                HexFormat.of().parseHex("d87a8180"), null));
        for (int shard = 0; shard < 16; shard++) {
            put(utxo("62".repeat(32), shard, enterprise(SHARD_HASH), 2_000_000,
                    asset(SHARD_POLICY, String.format("%02x", shard), 1),
                    serialize(ConstrPlutusData.of(0, BigIntPlutusData.of(1), BigIntPlutusData.of(shard),
                            BytesPlutusData.of(new byte[32]))), null));
        }
        List<PlutusData> members = new ArrayList<>();
        TreeMap<String, byte[]> keys = new TreeMap<>();
        for (byte[] memberSeed : memberSeeds) {
            keys.put(HexFormat.of().formatHex(publicKey(memberSeed)), publicKey(memberSeed));
        }
        keys.values().forEach(key -> members.add(BytesPlutusData.of(key)));
        PlutusData anchor = ConstrPlutusData.of(0, BigIntPlutusData.of(2), BytesPlutusData.of(CHAIN.getBytes()),
                BytesPlutusData.of(HexFormat.of().parseHex(GENESIS)), BytesPlutusData.of("asset-payments".getBytes()),
                BytesPlutusData.of("mpf-blake2b256-v1".getBytes()), BytesPlutusData.of(HexFormat.of().parseHex(FINGERPRINT)),
                BigIntPlutusData.of(anchorHeight), BytesPlutusData.of(filled(32, 0x66)), BytesPlutusData.of(filled(32, 0x55)),
                ListPlutusData.builder().plutusDataList(members).build(), BigIntPlutusData.of(threshold),
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

        /** A committed payout observation resolved the claim. */
        void resolve(Hash32 claimId, SettlementRecords.ClaimStatus to) {
            resolve(claimId, to, Hash32.of(filled(32, 0x77)));
        }

        /** A committed payout observation of transaction {@code txId} resolved the claim. */
        void resolve(Hash32 claimId, SettlementRecords.ClaimStatus to, Hash32 txId) {
            status.put(claimId, status.get(claimId).resolve(to, txId, 600));
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

        @Override public Supplier<ProtocolParams> protocolParams() { return AssetSettlementExecutorTest.this::params; }

        @Override
        public TxEvaluationGateway txEvaluation() {
            return new TxEvaluationGateway() {
                @Override public boolean isTransactionEvaluationAvailable() { return true; }

                @Override
                public List<TxEvaluationResult> evaluateTransaction(byte[] cbor) throws Exception {
                    // Units grow with the payouts (outputs less continuation, shard and change), as real scripts' do.
                    Transaction tx = Transaction.deserialize(cbor);
                    long payouts = overBudget.test(tx) ? 3 : Math.max(1, tx.getBody().getOutputs().size() - 3);
                    List<TxEvaluationResult> out = new ArrayList<>();
                    for (var redeemer : tx.getWitnessSet().getRedeemers()) {
                        out.add(new TxEvaluationResult("spend", redeemer.getIndex().intValue(), 3_000_000L * payouts,
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

    ProtocolParams params() {
        ProtocolParams pp = new ProtocolParams();
        pp.setMinFeeA(44);
        pp.setMinFeeB(155_381);
        pp.setMaxTxSize(maxTxSize);
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
        // Every member bounds the validity it signs by its own L1 time, so the slot clock is every node's setting.
        config.put("slot.zero-time-ms", "0");
        config.put("slot.zero-slot", "0");
        config.put("slot.length-ms", "1000");
        if (owner) {
            Path key = temp.resolve("fee.skey");
            Files.writeString(key, HexFormat.of().formatHex(FEE_SEED));
            config.put("owner", "true");
            config.put("fee-wallet.key-file", key.toString());
        }
        return config;
    }

    static ClaimLeafV1 claim(int shard, int seed, long height) {
        return claim(shard, seed, height, 1_500_000);
    }

    static ClaimLeafV1 claim(int shard, int seed, long height, long vaultLovelace) {
        byte[] id = Blake2bUtil.blake2bHash256(("claim-" + seed).getBytes());
        id[31] = (byte) ((id[31] & 0xf0) | shard);
        return new ClaimLeafV1(Hash32.of(id), VAULT, ClaimKind.USER_WITHDRAWAL, AssetValue.single(USDM,
                BigInteger.valueOf(100 + seed)), BigInteger.valueOf(vaultLovelace), concat(new byte[] {0x60},
                filled(28, 0x80 + seed)), height, NOW - 86_400_000L);
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static PendingEffect effect(SettlementBatchV1 batch) {
        return effect(batch, 0);
    }

    /** The effect at {@code ordinal} of block 5: effects of one executor need distinct ordinals. */
    static PendingEffect effect(SettlementBatchV1 batch, int ordinal) {
        return PendingEffect.of(new EffectRecord(EffectRecord.RECORD_VERSION, CHAIN, 5, ordinal,
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
        pump(2_000, nodes);
    }

    void pump(long millis, Node... nodes) {
        Thread pump = new Thread(() -> {
            long until = System.currentTimeMillis() + millis;
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
        return executor(node, config(owner));
    }

    AppEffectExecutor executor(Node node, Map<String, String> config) {
        return executor(node, config, () -> now);
    }

    /** A product started an hour before {@code now}: past its startup grace (D9), so it assembles at once. */
    AppEffectExecutor executor(Node node, Map<String, String> config, java.util.function.LongSupplier clock) {
        long at = now;
        now = at - 3_600_000L;
        List<AppEffectExecutor> products;
        try {
            products = AssetSettlementExecutorFactory.create(config, node, clock);
        } finally {
            now = at;
        }
        assertThat(products).hasSize(1);
        return products.getFirst();
    }

    /** The claim ids a submitted Settle pays, from its vault continuation (output k, after the k payouts). */
    static List<Hash32> paidBy(byte[] signedTx) throws Exception {
        Transaction tx = Transaction.deserialize(signedTx);
        var outputs = tx.getBody().getOutputs();
        int k = outputs.size() - 3;
        List<Hash32> ids = new ArrayList<>();
        for (PlutusData id : ((ListPlutusData) ((ConstrPlutusData) outputs.get(k).getInlineDatum()).getData()
                .getPlutusDataList().getFirst()).getPlutusDataList()) {
            ids.add(Hash32.of(((BytesPlutusData) id).getValue()));
        }
        return ids;
    }

    static byte[] txIdOf(byte[] ref) {
        return Arrays.copyOf(ref, 32);
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
        byte[] ref = ((EffectExecution.Submitted) first).externalRef();
        assertThat(txIdOf(ref)).isEqualTo(bodyHash);
        List<VkeyWitness> witnesses = tx.getWitnessSet().getVkeyWitnesses();
        assertThat(witnesses).hasSize(3);
        for (VkeyWitness witness : witnesses) {
            assertThat(CryptoConfiguration.INSTANCE.getSigningProvider().verify(witness.getSignature(), bodyHash,
                    witness.getVkey())).isTrue();
        }
        assertThat(tx.getBody().getRequiredSigners()).hasSize(2);

        // Not yet on L1: still submitted. Included on the tip but not yet confirmed by the payout observer (review
        // P1, a tip block can roll back): still submitted, and the executor changes no claim status (I12).
        assertThat(executor.execute(attempt(2, ref), effect)).isInstanceOf(EffectExecution.Submitted.class);
        put(utxo(HexFormat.of().formatHex(bodyHash), 0, "addr_test1vz", 1, List.of(), null, null));
        assertThat(executor.execute(attempt(3, ref), effect)).isInstanceOf(EffectExecution.Submitted.class);
        for (ClaimLeafV1 leaf : claims) {
            assertThat(owner.ledger.status.get(leaf.claimId()).status())
                    .isEqualTo(SettlementRecords.ClaimStatus.RESERVED);
        }
        // Once committed state shows the claims paid, the effect confirms.
        claims.forEach(leaf -> owner.ledger.resolve(leaf.claimId(), SettlementRecords.ClaimStatus.PAID,
                Hash32.of(bodyHash)));
        assertThat(executor.execute(attempt(4, ref), effect)).isInstanceOf(EffectExecution.Confirmed.class);
    }

    /** Review P1 (rolled-back Settle) and P2-8 (restart timeout): no confirmation by the deadline fails the batch. */
    @Test
    void aSettleNotConfirmedByItsDeadlineFailsEvenAcrossARestart() throws Exception {
        seed(500);
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        AppEffectExecutor executor = executor(owner, true);
        executor(member, false);
        ClaimLeafV1 leaf = claim(4, 5, 400);
        owner.ledger.reserve(leaf);
        member.ledger.reserve(leaf);
        pump(owner, member);
        PendingEffect effect = effect(new SettlementBatchV1(VAULT, 4, List.of(leaf.claimId())));
        byte[] ref = ((EffectExecution.Submitted) executor.execute(attempt(1, new byte[0]), effect)).externalRef();
        // Included, then rolled back: the claim never leaves RESERVED in committed state.
        put(utxo(HexFormat.of().formatHex(txIdOf(ref)), 0, "addr_test1vz", 1, List.of(), null, null));
        l1.remove(HexFormat.of().formatHex(txIdOf(ref)) + "#0");

        // A restart loses the journal; the persisted reference keeps the original deadline.
        now = NOW + 899_000;
        AppEffectExecutor restarted = executor(owner, true);
        assertThat(restarted.execute(attempt(2, ref), effect)).isInstanceOf(EffectExecution.Submitted.class);
        now = NOW + 900_001;
        AppEffectExecutor again = executor(owner, true);
        EffectExecution result = again.execute(attempt(3, ref), effect);
        assertThat(result).isInstanceOf(EffectExecution.Failed.class);
        assertThat(((EffectExecution.Failed) result).retryable()).isFalse();
        assertThat(executor.execute(attempt(3, ref), effect)).isInstanceOf(EffectExecution.Failed.class);
        assertThat(submitted).hasSize(1);
    }

    /** Review P1 (fee-wallet drain): token payouts that need a supplement are left out and stay RESERVED for Exit. */
    @Test
    void unfundedTokenPayoutsAreLeftForExitSoTheFeeWalletIsNotDrained() throws Exception {
        seed(500);
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        AppEffectExecutor executor = executor(owner, true);
        executor(member, false);
        List<ClaimLeafV1> unfunded = new ArrayList<>();
        for (int i = 0; i < 8; i++) unfunded.add(claim(2, 20 + i, 400, 0)); // one-unit style claims, no min-ADA
        ClaimLeafV1 funded = claim(2, 40, 400);
        List<ClaimLeafV1> all = new ArrayList<>(unfunded);
        all.add(funded);
        for (ClaimLeafV1 leaf : all) {
            owner.ledger.reserve(leaf);
            member.ledger.reserve(leaf);
        }
        pump(owner, member);

        // A batch of only unfunded claims builds nothing: the fee wallet pays no supplement (default cap 0).
        PendingEffect onlyUnfunded = effect(new SettlementBatchV1(VAULT, 2,
                unfunded.stream().map(ClaimLeafV1::claimId).toList()));
        assertThat(executor.execute(attempt(1, new byte[0]), onlyUnfunded)).isInstanceOf(EffectExecution.Retry.class);
        assertThat(submitted).isEmpty();

        // A mixed batch pays only the funded claim; every output of the fee wallet's lovelace goes to fee and change.
        PendingEffect mixed = effect(new SettlementBatchV1(VAULT, 2, all.stream().map(ClaimLeafV1::claimId).toList()));
        assertThat(executor.execute(attempt(1, new byte[0]), mixed)).isInstanceOf(EffectExecution.Submitted.class);
        assertThat(paidBy(submitted.getFirst())).containsExactly(funded.claimId());
        Transaction tx = Transaction.deserialize(submitted.getFirst());
        BigInteger change = tx.getBody().getOutputs().getLast().getValue().getCoin();
        assertThat(change.add(tx.getBody().getFee())).as("no supplement left the fee wallet")
                .isEqualTo(BigInteger.valueOf(80_000_000));
        for (ClaimLeafV1 leaf : unfunded) {
            assertThat(owner.ledger.status.get(leaf.claimId()).status())
                    .isEqualTo(SettlementRecords.ClaimStatus.RESERVED);
        }
    }

    /** Review P1 (fixed signer subset): with B offline, a later attempt rotates to A and C. */
    @Test
    void aMissingSignerIsRotatedOutOnRetry() throws Exception {
        seed(500, OWNER_SEED, MEMBER_SEED, THIRD_SEED);
        Node owner = new Node(OWNER_SEED);
        // The owner's co-signers in key order: the first one is offline.
        List<byte[]> others = new ArrayList<>(List.of(MEMBER_SEED, THIRD_SEED));
        others.sort((a, b) -> HexFormat.of().formatHex(publicKey(a)).compareTo(HexFormat.of().formatHex(publicKey(b))));
        Node online = new Node(others.get(1));
        AppEffectExecutor executor = executor(owner, true);
        executor(online, false);
        ClaimLeafV1 leaf = claim(7, 6, 400);
        owner.ledger.reserve(leaf);
        online.ledger.reserve(leaf);
        pump(owner, online);
        PendingEffect effect = effect(new SettlementBatchV1(VAULT, 7, List.of(leaf.claimId())));

        EffectExecution first = executor.execute(attempt(1, new byte[0]), effect);
        assertThat(first).isInstanceOf(EffectExecution.Failed.class);
        assertThat(((EffectExecution.Failed) first).retryable()).isTrue();
        assertThat(submitted).isEmpty();

        assertThat(executor.execute(attempt(2, new byte[0]), effect)).isInstanceOf(EffectExecution.Submitted.class);
        Transaction tx = Transaction.deserialize(submitted.getFirst());
        assertThat(tx.getBody().getRequiredSigners()).containsExactlyInAnyOrder(
                Blake2bUtil.blake2bHash224(publicKey(OWNER_SEED)),
                Blake2bUtil.blake2bHash224(publicKey(others.get(1))));
        assertThat(tx.getWitnessSet().getVkeyWitnesses()).extracting(VkeyWitness::getVkey)
                .noneMatch(key -> Arrays.equals(key, publicKey(others.get(0))));
    }

    /** Review P1 (batch-size mismatch): the executor pays at most its cap and halves on a transaction limit. */
    @Test
    void theExecutorSplitsAtItsCapAndOnATransactionLimit() throws Exception {
        seed(500);
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        Map<String, String> capped = config(true);
        capped.put("max-settle-batch", "2");
        AppEffectExecutor executor = executor(owner, capped);
        executor(member, false);
        List<ClaimLeafV1> claims = List.of(claim(9, 51, 400), claim(9, 52, 400), claim(9, 53, 400));
        for (ClaimLeafV1 leaf : claims) {
            owner.ledger.reserve(leaf);
            member.ledger.reserve(leaf);
        }
        pump(owner, member);
        PendingEffect effect = effect(new SettlementBatchV1(VAULT, 9, claims.stream().map(ClaimLeafV1::claimId)
                .toList()));
        byte[] ref = ((EffectExecution.Submitted) executor.execute(attempt(1, new byte[0]), effect)).externalRef();
        assertThat(paidBy(submitted.getFirst())).containsExactly(claims.get(0).claimId(), claims.get(1).claimId());
        // The paid part confirms the effect; the third claim is still RESERVED and the ledger re-queues it at close.
        owner.ledger.resolve(claims.get(0).claimId(), SettlementRecords.ClaimStatus.PAID, Hash32.of(txIdOf(ref)));
        owner.ledger.resolve(claims.get(1).claimId(), SettlementRecords.ClaimStatus.PAID, Hash32.of(txIdOf(ref)));
        assertThat(executor.execute(attempt(2, ref), effect)).isInstanceOf(EffectExecution.Confirmed.class);

        // Three payouts exceed the per-transaction memory limit here (3M units per payout per redeemer): halved.
        AppEffectExecutor uncapped = executor(owner, true);
        List<ClaimLeafV1> others = List.of(claim(10, 54, 400), claim(10, 55, 400), claim(10, 56, 400));
        for (ClaimLeafV1 leaf : others) {
            owner.ledger.reserve(leaf);
            member.ledger.reserve(leaf);
        }
        PendingEffect three = effect(new SettlementBatchV1(VAULT, 10, others.stream().map(ClaimLeafV1::claimId)
                .toList()));
        assertThat(uncapped.execute(attempt(1, new byte[0]), three)).isInstanceOf(EffectExecution.Submitted.class);
        assertThat(paidBy(submitted.getLast())).containsExactly(others.getFirst().claimId());
    }

    /** Re-review #3: 3-of-5 with C and E offline: the members that answered stay, the missing one is replaced. */
    @Test
    void aQuorumIsReachedByKeepingTheMembersThatAnswered() throws Exception {
        seed(500, 3, OWNER_SEED, MEMBER_SEED, THIRD_SEED, FOURTH_SEED, FIFTH_SEED);
        List<byte[]> others = new ArrayList<>(List.of(MEMBER_SEED, THIRD_SEED, FOURTH_SEED, FIFTH_SEED));
        others.sort((a, b) -> HexFormat.of().formatHex(publicKey(a)).compareTo(HexFormat.of().formatHex(publicKey(b))));
        Node owner = new Node(OWNER_SEED);
        Node b = new Node(others.get(0));
        Node d = new Node(others.get(2));
        AppEffectExecutor executor = executor(owner, true);
        executor(b, false);
        executor(d, false);
        ClaimLeafV1 leaf = claim(8, 7, 400);
        for (Node node : List.of(owner, b, d)) node.ledger.reserve(leaf);
        pump(owner, b, d);
        PendingEffect effect = effect(new SettlementBatchV1(VAULT, 8, List.of(leaf.claimId())));

        // Attempt 1 asks B and C; C is offline. Attempt 2 keeps B and replaces C with D, the next untried member.
        assertThat(executor.execute(attempt(1, new byte[0]), effect)).isInstanceOf(EffectExecution.Failed.class);
        assertThat(executor.execute(attempt(2, new byte[0]), effect)).isInstanceOf(EffectExecution.Submitted.class);
        Transaction tx = Transaction.deserialize(submitted.getFirst());
        assertThat(tx.getBody().getRequiredSigners()).containsExactlyInAnyOrder(
                Blake2bUtil.blake2bHash224(publicKey(OWNER_SEED)), Blake2bUtil.blake2bHash224(publicKey(others.get(0))),
                Blake2bUtil.blake2bHash224(publicKey(others.get(2))));
    }

    /** Confirmation pass #1: 2-of-3, B and C both miss their first requests, then C returns: a new pass reaches it. */
    @Test
    void aMemberThatComesBackIsReachedInTheNextPass() throws Exception {
        seed(500, OWNER_SEED, MEMBER_SEED, THIRD_SEED);
        List<byte[]> others = new ArrayList<>(List.of(MEMBER_SEED, THIRD_SEED));
        others.sort((a, b) -> HexFormat.of().formatHex(publicKey(a)).compareTo(HexFormat.of().formatHex(publicKey(b))));
        Node owner = new Node(OWNER_SEED);
        Node c = new Node(others.get(1));
        AppEffectExecutor executor = executor(owner, true);
        executor(c, false);
        ClaimLeafV1 leaf = claim(6, 17, 400);
        owner.ledger.reserve(leaf);
        c.ledger.reserve(leaf);
        pump(owner);
        PendingEffect effect = effect(new SettlementBatchV1(VAULT, 6, List.of(leaf.claimId())));
        // Attempt 1 asks B and attempt 2 asks C; neither answers.
        assertThat(executor.execute(attempt(1, new byte[0]), effect)).isInstanceOf(EffectExecution.Failed.class);
        assertThat(executor.execute(attempt(2, new byte[0]), effect)).isInstanceOf(EffectExecution.Failed.class);
        pump(owner, c); // C is back; B stays offline
        EffectExecution third = executor.execute(attempt(3, new byte[0]), effect);
        EffectExecution result = third instanceof EffectExecution.Submitted ? third
                : executor.execute(attempt(4, new byte[0]), effect);
        assertThat(result).as("attempt 3 or 4 reaches {A, C}").isInstanceOf(EffectExecution.Submitted.class);
        assertThat(Transaction.deserialize(submitted.getFirst()).getBody().getRequiredSigners())
                .containsExactlyInAnyOrder(Blake2bUtil.blake2bHash224(publicKey(OWNER_SEED)),
                        Blake2bUtil.blake2bHash224(publicKey(others.get(1))));
    }

    /** Confirmation pass #2: a 64-claim batch's reference that pays only its last position recovers. */
    @Test
    void aSixtyFourClaimReferenceRecoversItsLastPosition() {
        List<Hash32> ids = new ArrayList<>();
        for (int seed = 0; seed < 64; seed++) ids.add(claim(5, 1_000 + seed, 400).claimId());
        SettlementBatchV1 batch = new SettlementBatchV1(VAULT, 5, ids);
        AssetSettlementExecutor.Journal journal = AssetSettlementExecutor.Journal.of(Hash32.of(filled(32, 0x3c)),
                batch, List.of(ids.getLast()), NOW + 1, Set.of());
        assertThat(AssetSettlementExecutor.Journal.recover(journal.ref(), batch)).hasValueSatisfying(recovered -> {
            assertThat(recovered.claimIds()).containsExactly(ids.getLast());
            assertThat(recovered.deadlineMs()).isEqualTo(NOW + 1);
        });
        // A smaller batch still rejects a bit past its last position.
        SettlementBatchV1 small = new SettlementBatchV1(VAULT, 5, ids.subList(0, 3));
        assertThat(AssetSettlementExecutor.Journal.recover(journal.ref(), small)).isEmpty();
    }

    /** Confirmation pass #3: signer memory is dropped at a terminal result and capped for expired batches. */
    @Test
    void signerMemoryIsBounded() throws Exception {
        seed(500);
        Node owner = new Node(OWNER_SEED);
        AssetSettlementExecutor executor = (AssetSettlementExecutor) executor(owner, true);
        List<Hash32> members = List.of(Hash32.of(publicKey(OWNER_SEED)), Hash32.of(publicKey(MEMBER_SEED)));
        String member = HexFormat.of().formatHex(publicKey(MEMBER_SEED));
        for (int i = 0; i < AssetSettlementExecutor.SIGNER_MEMORY_CAP + 50; i++) {
            executor.remember("effect-" + i, Set.of(member), Set.of(), members);
        }
        assertThat(executor.signerMemorySize()).isEqualTo(AssetSettlementExecutor.SIGNER_MEMORY_CAP);

        // An effect whose round failed is forgotten once it ends here (nothing reserved: confirmed).
        ClaimLeafV1 leaf = claim(4, 18, 400);
        owner.ledger.reserve(leaf);
        PendingEffect effect = effect(new SettlementBatchV1(VAULT, 4, List.of(leaf.claimId())));
        assertThat(executor.execute(attempt(1, new byte[0]), effect)).isInstanceOf(EffectExecution.Failed.class);
        int before = executor.signerMemorySize();
        owner.ledger.resolve(leaf.claimId(), SettlementRecords.ClaimStatus.PAID);
        assertThat(executor.execute(attempt(2, new byte[0]), effect)).isInstanceOf(EffectExecution.Confirmed.class);
        assertThat(executor.signerMemorySize()).isEqualTo(before - 1);
    }

    /** Confirmation pass #4: the owner's own passing check clears a recorded tip-bound refusal. */
    @Test
    void theOwnersPassingCheckClearsAnEarlierRefusal() throws Exception {
        org.yanoproject.x.assets.cardano.support.settlement.SettleBodyCheck.Outcome[] next = {
                new org.yanoproject.x.assets.cardano.support.settlement.SettleBodyCheck.Refused(
                        new org.yanoproject.x.assets.cardano.support.settlement.SettleBodyCheck.Refusal(
                                org.yanoproject.x.assets.cardano.support.settlement.SettleBodyCheck.Refusal.Kind
                                        .L1_TIP_BOUND, "behind"))};
        Node owner = new Node(OWNER_SEED);
        AssetCosignService cosign = new AssetCosignService((topic, body) -> { }, owner.memberSigner(),
                tx -> next[0], java.time.Duration.ofMillis(100));
        String self = HexFormat.of().formatHex(publicKey(OWNER_SEED));
        assertThat(cosign.collect(new byte[] {1}, Set.of(self))).isEmpty();
        assertThat(AssetSettlementExecutorFactory.cosignSnapshot(cosign).readiness())
                .isEqualTo(org.yanoproject.x.framework.api.appchain.effects.EffectExecutorOperationalSnapshot
                        .Readiness.DEGRADED);
        next[0] = new org.yanoproject.x.assets.cardano.support.settlement.SettleBodyCheck.Signable(
                new org.yanoproject.x.assets.cardano.support.settlement.SettleBodyCheck.Verified(filled(32, 0x2b),
                        List.of(), 0, new org.yanoproject.x.assets.contracts.AnchorObservationV1.Anchored(1,
                        Hash32.of(filled(32, 1)), Hash32.of(filled(32, 2)), NOW, 1,
                        List.of(Hash32.of(publicKey(OWNER_SEED))))));
        assertThat(cosign.collect(new byte[] {1}, Set.of(self))).hasValueSatisfying(signatures ->
                assertThat(signatures).containsOnlyKeys(self));
        assertThat(cosign.lastRefusal()).isEmpty();
        assertThat(AssetSettlementExecutorFactory.cosignSnapshot(cosign).readiness())
                .isEqualTo(org.yanoproject.x.framework.api.appchain.effects.EffectExecutorOperationalSnapshot
                        .Readiness.UNKNOWN);
    }

    /** Re-review N5: a claim that alone exceeds a limit is left RESERVED and the next claim is paid. */
    @Test
    void aSingletonOverALimitIsLeftForExitAndTheNextClaimIsPaid() throws Exception {
        seed(500);
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        AppEffectExecutor executor = executor(owner, true);
        executor(member, false);
        ClaimLeafV1 big = claim(11, 71, 400);
        ClaimLeafV1 small = claim(11, 72, 400);
        for (ClaimLeafV1 leaf : List.of(big, small)) {
            owner.ledger.reserve(leaf);
            member.ledger.reserve(leaf);
        }
        String bigRecipient = new com.bloxbean.cardano.client.address.Address(big.recipient()).toBech32();
        overBudget = tx -> tx.getBody().getOutputs().stream().anyMatch(out -> out.getAddress().equals(bigRecipient));
        pump(owner, member);
        PendingEffect effect = effect(new SettlementBatchV1(VAULT, 11, List.of(big.claimId(), small.claimId())));
        assertThat(executor.execute(attempt(1, new byte[0]), effect)).isInstanceOf(EffectExecution.Submitted.class);
        assertThat(paidBy(submitted.getFirst())).containsExactly(small.claimId());
        assertThat(owner.ledger.status.get(big.claimId()).status()).isEqualTo(SettlementRecords.ClaimStatus.RESERVED);
    }

    /** Re-review N6: the persisted reference names the claims the transaction pays, so a restart waits on those. */
    @Test
    void aRestartWaitsOnlyOnTheClaimsTheTransactionPays() throws Exception {
        seed(500);
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        Map<String, String> capped = config(true);
        capped.put("max-settle-batch", "2");
        AppEffectExecutor executor = executor(owner, capped);
        executor(member, false);
        List<ClaimLeafV1> claims = List.of(claim(12, 81, 400), claim(12, 82, 400), claim(12, 83, 400));
        for (ClaimLeafV1 leaf : claims) {
            owner.ledger.reserve(leaf);
            member.ledger.reserve(leaf);
        }
        pump(owner, member);
        PendingEffect effect = effect(new SettlementBatchV1(VAULT, 12, claims.stream().map(ClaimLeafV1::claimId)
                .toList()));
        byte[] ref = ((EffectExecution.Submitted) executor.execute(attempt(1, new byte[0]), effect)).externalRef();
        for (ClaimLeafV1 leaf : claims.subList(0, 2)) {
            owner.ledger.resolve(leaf.claimId(), SettlementRecords.ClaimStatus.PAID, Hash32.of(txIdOf(ref)));
        }
        AppEffectExecutor restarted = executor(owner, capped);
        assertThat(restarted.execute(attempt(2, ref), effect)).isInstanceOf(EffectExecution.Confirmed.class);
    }

    /** Re-review N7: a claim resolved by another payout, or as PAID_MISMATCH, never confirms this transaction. */
    @Test
    void aCompetingPayoutSupersedesInsteadOfConfirming() throws Exception {
        seed(500);
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        AppEffectExecutor executor = executor(owner, true);
        executor(member, false);
        ClaimLeafV1 first = claim(13, 91, 400);
        ClaimLeafV1 second = claim(15, 92, 400); // another shard: shard 13's mirror now holds the competing payout
        for (ClaimLeafV1 leaf : List.of(first, second)) {
            owner.ledger.reserve(leaf);
            member.ledger.reserve(leaf);
        }
        pump(owner, member);
        PendingEffect effect = effect(new SettlementBatchV1(VAULT, 13, List.of(first.claimId())));
        byte[] ref = ((EffectExecution.Submitted) executor.execute(attempt(1, new byte[0]), effect)).externalRef();
        owner.ledger.resolve(first.claimId(), SettlementRecords.ClaimStatus.PAID, Hash32.of(filled(32, 0xe1)));
        EffectExecution superseded = executor.execute(attempt(2, ref), effect);
        assertThat(superseded).isInstanceOf(EffectExecution.Failed.class);
        assertThat(((EffectExecution.Failed) superseded).retryable()).isFalse();
        assertThat(((EffectExecution.Failed) superseded).reason()).contains("superseded");

        PendingEffect other = effect(new SettlementBatchV1(VAULT, 15, List.of(second.claimId())));
        AppEffectExecutor next = executor(owner, true);
        byte[] secondRef = ((EffectExecution.Submitted) next.execute(attempt(1, new byte[0]), other)).externalRef();
        owner.ledger.resolve(second.claimId(), SettlementRecords.ClaimStatus.PAID_MISMATCH,
                Hash32.of(txIdOf(secondRef)));
        assertThat(next.execute(attempt(2, secondRef), other)).isInstanceOf(EffectExecution.Failed.class);
    }

    /** Re-review N9 and Fable P3-2: a member's own window setting is not checked; a lagging clock shows in status. */
    @Test
    void aMemberIgnoresTheOwnersWindowAndALaggingClockShowsAsDegraded() throws Exception {
        seed(500);
        Map<String, String> wide = config(false);
        wide.put("validity-window-slots", "601");
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        AppEffectExecutor memberProduct = executor(member, wide, () -> now - 20 * 60_000);
        assertThat(memberProduct.effectTypes()).isEmpty();
        AppEffectExecutor executor = executor(owner, true);
        ClaimLeafV1 leaf = claim(14, 95, 400);
        owner.ledger.reserve(leaf);
        member.ledger.reserve(leaf);
        pump(owner, member);
        assertThat(executor.execute(attempt(1, new byte[0]), effect(new SettlementBatchV1(VAULT, 14,
                List.of(leaf.claimId()))))).isInstanceOf(EffectExecution.Failed.class);
        assertThat(((AssetCosignService) member.handler).lastRefusal()).hasValueSatisfying(refusal ->
                assertThat(refusal.kind()).isEqualTo(
                        org.yanoproject.x.assets.cardano.support.settlement.SettleBodyCheck.Refusal.Kind.L1_TIP_BOUND));
        var snapshot = memberProduct.operationalSnapshot();
        assertThat(snapshot.readiness())
                .isEqualTo(org.yanoproject.x.framework.api.appchain.effects.EffectExecutorOperationalSnapshot
                        .Readiness.DEGRADED);
        assertThat(snapshot.failureCode())
                .isEqualTo(org.yanoproject.x.framework.api.appchain.effects.EffectExecutorOperationalSnapshot
                        .FailureCode.CONFIGURATION);
    }

    /** Settings consistency: the inclusion timeout outlasts the stability window, the validity window is bounded. */
    @Test
    void inconsistentOwnerSettingsFailAtStartup() throws Exception {
        Node owner = new Node(OWNER_SEED);
        Map<String, String> early = config(true);
        early.put("inclusion-timeout-ms", "600000");
        assertThatThrownBy(() -> executor(owner, early)).hasMessageContaining("must exceed stability-window-ms");
        Map<String, String> wide = config(true);
        wide.put("validity-window-slots", "601");
        assertThatThrownBy(() -> executor(owner, wide)).hasMessageContaining("validity-window-slots");
    }

    /** Review P3-2: a PAID_MISMATCH claim's nullifier is in the shard, so the mirror includes it. */
    @Test
    void theMirrorSourceIncludesMismatchedPayouts() {
        Ledger ledger = new Ledger();
        ClaimLeafV1 paid = claim(3, 61, 400);
        ClaimLeafV1 mismatched = claim(3, 62, 400);
        ClaimLeafV1 reserved = claim(3, 63, 400);
        for (ClaimLeafV1 leaf : List.of(paid, mismatched, reserved)) ledger.reserve(leaf);
        ledger.resolve(paid.claimId(), SettlementRecords.ClaimStatus.PAID);
        ledger.resolve(mismatched.claimId(), SettlementRecords.ClaimStatus.PAID_MISMATCH);
        assertThat(new CommittedClaims(ledger::answer).paid(VAULT, 3)).hasValueSatisfying(ids ->
                assertThat(ids).containsExactly(paid.claimId(), mismatched.claimId()));
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

    // ------------------------------------------------------------------ reviewer3 M4S2-F1: concurrent attempts

    String feeAddress() {
        return AddressProvider.getEntAddress(Credential.fromKey(Blake2bUtil.blake2bHash224(publicKey(FEE_SEED))),
                Networks.testnet()).getAddress();
    }

    /** Applies a submitted transaction to the shared L1: its inputs are spent and its outputs are live. */
    void land(byte[] signedTx) throws Exception {
        Transaction tx = Transaction.deserialize(signedTx);
        String id = TransactionUtil.getTxHash(signedTx);
        for (var input : tx.getBody().getInputs()) l1.remove(input.getTransactionId() + "#" + input.getIndex());
        var outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            var out = outputs.get(i);
            List<AssetAmount> assets = new ArrayList<>();
            if (out.getValue().getMultiAssets() != null) {
                for (var multi : out.getValue().getMultiAssets()) {
                    for (var a : multi.getAssets()) {
                        assets.add(new AssetAmount(multi.getPolicyId(),
                                HexFormat.of().formatHex(a.getNameAsBytes()), a.getValue()));
                    }
                }
            }
            byte[] datum = out.getInlineDatum() == null ? null : out.getInlineDatum().serializeToBytes();
            put(new Utxo(new Outpoint(id, i), out.getAddress(), out.getValue().getCoin(), assets, null, datum, null,
                    null, false, 100, 10, "bb".repeat(32)));
        }
    }

    static Set<String> inputsOf(byte[] signedTx) throws Exception {
        Set<String> inputs = new java.util.HashSet<>();
        for (var input : Transaction.deserialize(signedTx).getBody().getInputs()) {
            inputs.add(input.getTransactionId() + "#" + input.getIndex());
        }
        return inputs;
    }

    /** No two submitted transactions spend one outpoint. */
    void assertNoDoubleSpend() throws Exception {
        Set<String> seen = new java.util.HashSet<>();
        for (byte[] tx : List.copyOf(submitted)) {
            for (String input : inputsOf(tx)) assertThat(seen.add(input)).as("spent twice: " + input).isTrue();
        }
    }

    /**
     * Four batches at once on four workers (max-parallel 4): three shards and a second batch on one of them. The
     * attempts are serialized, no two Settles spend one outpoint, the same-shard batch waits while the first is not
     * included, and once it is, the waiting batch chains on its shard output, as does a third one, without a mirror
     * mismatch.
     */
    @Test
    void concurrentBatchesNeverSpendOneOutpointTwiceAndASameShardBatchWaits() throws Exception {
        seed(500);
        // Custody and fee funds for four Settles at once.
        for (int i = 1; i < 4; i++) {
            put(utxo("61".repeat(32), i, enterprise(VAULT_HASH), 50_000_000,
                    asset("f0".repeat(28), "5553444d", 100_000), HexFormat.of().parseHex("d87a8180"), null));
        }
        for (int i = 1; i < 12; i++) put(utxo("60".repeat(32), i, feeAddress(), 80_000_000, List.of(), null, null));
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        AssetSettlementExecutor executor = (AssetSettlementExecutor) executor(owner, true);
        executor(member, false);
        ClaimLeafV1 first = claim(1, 101, 400);
        ClaimLeafV1 second = claim(1, 102, 400);
        ClaimLeafV1 third = claim(1, 105, 400);
        List<ClaimLeafV1> all = List.of(first, second, third, claim(2, 103, 400), claim(3, 104, 400));
        for (ClaimLeafV1 leaf : all) {
            owner.ledger.reserve(leaf);
            member.ledger.reserve(leaf);
        }
        pump(30_000, owner, member);
        List<PendingEffect> effects = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ClaimLeafV1 leaf = List.of(first, second, all.get(3), all.get(4)).get(i);
            effects.add(effect(new SettlementBatchV1(VAULT, SettlementBatchV1.shardOf(leaf.claimId()),
                    List.of(leaf.claimId())), i));
        }

        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<EffectExecution>> futures = new ArrayList<>();
        for (PendingEffect effect : effects) {
            futures.add(workers.submit(() -> {
                start.await();
                return executor.execute(attempt(1, new byte[0]), effect);
            }));
        }
        start.countDown();
        List<EffectExecution> results = new ArrayList<>();
        for (var future : futures) results.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
        workers.shutdown();

        assertNoDoubleSpend();
        assertThat(submitted).hasSize(3);
        assertThat(results.subList(2, 4)).allMatch(EffectExecution.Submitted.class::isInstance);
        // One shard-1 batch went first; the other waits for it, and builds nothing that would conflict.
        int ahead = results.get(0) instanceof EffectExecution.Submitted ? 0 : 1;
        int behind = 1 - ahead;
        assertThat(results.get(ahead)).isInstanceOf(EffectExecution.Submitted.class);
        assertThat(results.get(behind)).isInstanceOf(EffectExecution.Retry.class);
        assertThat(executor.execute(attempt(2, new byte[0]), effects.get(behind)))
                .isInstanceOf(EffectExecution.Retry.class);
        assertThat(executor.lastOutcome()).isEqualTo("awaiting the pending payout on this shard");
        assertThat(submitted).hasSize(3);

        // The first shard-1 Settle is included, not yet confirmed: the waiting batch chains on its shard output.
        byte[] aheadRef = ((EffectExecution.Submitted) results.get(ahead)).externalRef();
        byte[] aheadTx = submitted.stream().filter(tx -> {
            try {
                return TransactionUtil.getTxHash(tx).equals(HexFormat.of().formatHex(txIdOf(aheadRef)));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }).findFirst().orElseThrow();
        land(aheadTx);
        EffectExecution chained = executor.execute(attempt(3, new byte[0]), effects.get(behind));
        assertThat(chained).as(executor.lastOutcome()).isInstanceOf(EffectExecution.Submitted.class);
        assertThat(inputsOf(submitted.getLast())).contains(TransactionUtil.getTxHash(aheadTx) + "#"
                + (Transaction.deserialize(aheadTx).getBody().getOutputs().size() - 2));
        assertNoDoubleSpend();

        // The first one confirms and the second lands: a third batch on the shard still finds its mirror.
        ClaimLeafV1 aheadClaim = ahead == 0 ? first : second;
        owner.ledger.resolve(aheadClaim.claimId(), SettlementRecords.ClaimStatus.PAID, Hash32.of(txIdOf(aheadRef)));
        member.ledger.resolve(aheadClaim.claimId(), SettlementRecords.ClaimStatus.PAID, Hash32.of(txIdOf(aheadRef)));
        assertThat(executor.execute(attempt(2, aheadRef), effects.get(ahead)))
                .isInstanceOf(EffectExecution.Confirmed.class);
        land(submitted.getLast());
        PendingEffect next = effect(new SettlementBatchV1(VAULT, 1, List.of(third.claimId())), 4);
        EffectExecution last = executor.execute(attempt(1, new byte[0]), next);
        assertThat(last).as(executor.lastOutcome()).isInstanceOf(EffectExecution.Submitted.class);
        assertNoDoubleSpend();
    }

    /** M4S2-F1: the fee wallet's only output is held by a pending Settle, so another batch waits until it closes. */
    @Test
    void aBatchWaitsForFeeFundsHeldByAPendingSettleUntilItCloses() throws Exception {
        seed(500); // one lovelace-only fee output
        put(utxo("61".repeat(32), 1, enterprise(VAULT_HASH), 50_000_000, asset("f0".repeat(28), "5553444d", 100_000),
                HexFormat.of().parseHex("d87a8180"), null));
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        AssetSettlementExecutor executor = (AssetSettlementExecutor) executor(owner, true);
        executor(member, false);
        ClaimLeafV1 x = claim(1, 111, 400);
        ClaimLeafV1 y = claim(2, 112, 400);
        for (ClaimLeafV1 leaf : List.of(x, y)) {
            owner.ledger.reserve(leaf);
            member.ledger.reserve(leaf);
        }
        pump(owner, member);
        PendingEffect firstEffect = effect(new SettlementBatchV1(VAULT, 1, List.of(x.claimId())), 0);
        PendingEffect secondEffect = effect(new SettlementBatchV1(VAULT, 2, List.of(y.claimId())), 1);
        byte[] ref = ((EffectExecution.Submitted) executor.execute(attempt(1, new byte[0]), firstEffect))
                .externalRef();

        assertThat(executor.execute(attempt(1, new byte[0]), secondEffect)).isInstanceOf(EffectExecution.Retry.class);
        assertThat(executor.lastOutcome()).isEqualTo("no fee wallet funds");
        assertThat(submitted).hasSize(1);

        // The first closes unconfirmed past its deadline (never included), and L1 is past its TTL: its outputs are
        // released.
        now = NOW + 900_001;
        pump(owner, member);
        assertThat(executor.execute(attempt(2, ref), firstEffect)).isInstanceOf(EffectExecution.Failed.class);
        EffectExecution second = executor.execute(attempt(2, new byte[0]), secondEffect);
        assertThat(second).as(executor.lastOutcome()).isInstanceOf(EffectExecution.Submitted.class);
        assertThat(submitted).hasSize(2);
    }

    /** M4S2-F1 non-blocking: with two vaults configured, the refusal reported is the spent vault's own. */
    @Test
    void aMemberWithTwoVaultsReportsTheSpentVaultsRefusal() throws Exception {
        seed(500);
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        AppEffectExecutor executor = executor(owner, true);
        Map<String, String> twoVaults = config(false);
        twoVaults.put("vaults.1.asset", "lovelace");
        twoVaults.put("vaults.1.script-hash", HexFormat.of().formatHex(filled(28, 0x03)));
        twoVaults.put("vaults.1.address", enterprise(filled(28, 0x03)));
        twoVaults.put("vaults.1.shard-policy-id", "a3".repeat(28));
        twoVaults.put("vaults.1.shard-script-hash", HexFormat.of().formatHex(ADA_SHARD_HASH));
        twoVaults.put("vaults.1.shard-address", enterprise(ADA_SHARD_HASH));
        twoVaults.put("vaults.1.script-ref", "65".repeat(32) + "#0");
        twoVaults.put("vaults.1.shard-script-ref", "65".repeat(32) + "#1");
        executor(member, twoVaults);
        ClaimLeafV1 leaf = claim(5, 113, 400);
        owner.ledger.reserve(leaf); // the member's committed state does not have it
        pump(owner, member);
        assertThat(executor.execute(attempt(1, new byte[0]),
                effect(new SettlementBatchV1(VAULT, 5, List.of(leaf.claimId()))))).isInstanceOf(
                EffectExecution.Failed.class);
        assertThat(((AssetCosignService) member.handler).lastRefusal()).hasValueSatisfying(refusal ->
                assertThat(refusal.detail()).contains("unknown claim"));
    }

    /** M4S2-F1 non-blocking: a fee wallet holding the member key would witness twice; startup refuses it. */
    @Test
    void theFeeWalletMustNotHoldTheMemberKey() throws Exception {
        Node owner = new Node(OWNER_SEED);
        Map<String, String> config = config(true);
        Files.writeString(temp.resolve("fee.skey"), HexFormat.of().formatHex(OWNER_SEED));
        assertThatThrownBy(() -> executor(owner, config)).hasMessageContaining("member key");
    }
}
