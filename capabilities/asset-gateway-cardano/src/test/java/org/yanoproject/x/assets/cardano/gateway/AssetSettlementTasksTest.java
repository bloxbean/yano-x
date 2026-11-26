package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.eval.JulcTransactionEvaluator;
import org.julclang.clientlib.eval.SlotConfig;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.TxEvaluationGateway;
import org.yanoproject.api.model.TxEvaluationResult;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.utxo.model.AssetAmount;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.x.assets.cardano.support.settlement.GatewayPlutus;
import org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositDatumV1;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.assets.contracts.SettlementRecords;
import org.yanoproject.x.assets.contracts.VaultOutputShape;
import org.yanoproject.x.framework.api.appchain.AppQueryResult;
import org.yanoproject.x.framework.api.appchain.FinalityCert;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectExecutor;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecution;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutionContext;
import org.yanoproject.x.framework.api.appchain.effects.EffectRecord;
import org.yanoproject.x.framework.api.appchain.effects.FinalityGate;
import org.yanoproject.x.framework.api.appchain.effects.PendingEffect;
import org.yanoproject.x.framework.api.appchain.effects.ResultPolicy;
import org.yanoproject.x.framework.api.appchain.l1view.BridgeDiffusionHandler;
import org.yanoproject.x.framework.api.appchain.signer.SignerProvider;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.x.framework.api.appchain.state.StateProofEnvelope;
import org.yanoproject.x.framework.api.appchain.state.StateSnapshot;
import org.yanoproject.x.framework.cardano.api.effects.AppChainEffectContext;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-059 M4 part 2 stage 3a at runtime, through the real factory, over an L1 whose vault and shard scripts are the
 * compiled, parameterized validators: every transaction the owner or a relayer builds is priced by running them
 * (julc), and every submitted one is evaluated again before it counts.
 * <ul>
 *   <li>{@code Accept} (D8f): the owner batches CREDITED-but-unaccepted deposits into a co-signed {@code Accept} that
 *       every member checks; deposits credited above the anchor, uncredited or unknown are left; without a quorum it
 *       falls back to proofs at the L1-stable anchor's height.</li>
 *   <li>Exit relayer (D9): an eligible claim is relayed by proof {@code Exit} with proofs from the node at the
 *       anchored height; ineligible claims are not; it waits for an L1-stable anchor and refuses in exit mode.</li>
 *   <li>Concurrency (D9): the relayer and the {@code Accept} batcher on the owner share the {@code Settle}
 *       executor's assembly lock and pending-spend holds, so no two pending transactions spend one outpoint and a
 *       payout never builds over a pending one's shard input.</li>
 * </ul>
 */
class AssetSettlementTasksTest {
    static final String CHAIN = "payments-1";
    static final String GENESIS = "6c".repeat(32);
    static final AssetId USDM = AssetId.ofHex("f0".repeat(28), "5553444d");
    static final byte[] ANCHOR_HASH = filled(28, 0x21);
    static final String ANCHOR_POLICY = "c1".repeat(28);
    static final byte[] EXIT_HASH = filled(28, 0x31);
    static final String EXIT_POLICY = "d1".repeat(28);
    static final String SHARD_POLICY = "a2".repeat(28);
    static final String FINGERPRINT = "fa".repeat(32);
    static final Hash32 DOMAIN = GatewayIds.domainId(0, 42, CHAIN, Hash32.ofHex(GENESIS), "asset-payments",
            "anchored-claim-vault-v1", 1);
    static final Hash32 VAULT = GatewayIds.vaultId(DOMAIN, USDM, 0);
    static final long NOW = 1_764_000_000_000L;
    static final long HOUR = 3_600_000L;
    static final long DAY = 24 * HOUR;
    static final long ANCHORED = 1_000;
    static final byte[] OWNER_SEED = filled(32, 0x41);
    static final byte[] MEMBER_SEED = filled(32, 0x42);
    static final byte[] FEE_SEED = filled(32, 0x43);
    static final byte[] RELAYER_FEE_SEED = filled(32, 0x47);
    static final byte[] CLAIM_PREFIX = "ap/claim/".getBytes(StandardCharsets.US_ASCII);
    static final byte[] DEPOSIT_PREFIX = "ap/deposit/".getBytes(StandardCharsets.US_ASCII);
    static final byte[] STATUS_KEY = "ap/domain-status".getBytes(StandardCharsets.US_ASCII);
    static final SlotConfig SLOTS = new SlotConfig(0, 0, 1_000);

    static final PlutusV3Script VAULT_SCRIPT;
    static final byte[] VAULT_HASH;
    static final PlutusV3Script SHARD_SCRIPT;
    static final byte[] SHARD_HASH;

    static {
        Program vault = template("AssetVaultValidator").applyParams(PlutusData.bytes(DOMAIN.bytes()),
                PlutusData.bytes(USDM.policyId()), PlutusData.bytes(USDM.assetName()), PlutusData.integer(0),
                PlutusData.integer(0), PlutusData.bytes(HexFormat.of().parseHex(ANCHOR_POLICY)),
                PlutusData.bytes(new byte[0]), PlutusData.bytes(ANCHOR_HASH),
                PlutusData.bytes(HexFormat.of().parseHex(GENESIS)),
                PlutusData.bytes("asset-payments".getBytes(StandardCharsets.US_ASCII)),
                PlutusData.bytes("mpf-blake2b256-v1".getBytes(StandardCharsets.US_ASCII)),
                PlutusData.bytes(HexFormat.of().parseHex(FINGERPRINT)),
                PlutusData.bytes(HexFormat.of().parseHex(SHARD_POLICY)),
                PlutusData.bytes(HexFormat.of().parseHex(EXIT_POLICY)), PlutusData.bytes(EXIT_HASH),
                PlutusData.bytes(CLAIM_PREFIX), PlutusData.bytes(DEPOSIT_PREFIX),
                PlutusData.bytes("ap/x/".getBytes(StandardCharsets.US_ASCII)), PlutusData.bytes(STATUS_KEY),
                PlutusData.integer(16), PlutusData.integer(8), PlutusData.integer(2_000_000),
                PlutusData.integer(6 * HOUR), PlutusData.integer(30 * DAY), PlutusData.integer(7 * DAY));
        VAULT_SCRIPT = JulcScriptAdapter.fromProgram(vault);
        VAULT_HASH = HexFormat.of().parseHex(JulcScriptAdapter.scriptHash(vault));
        Program shard = template("AssetShardValidator").applyParams(
                PlutusData.bytes(HexFormat.of().parseHex(SHARD_POLICY)), PlutusData.bytes(VAULT_HASH));
        SHARD_SCRIPT = JulcScriptAdapter.fromProgram(shard);
        SHARD_HASH = HexFormat.of().parseHex(JulcScriptAdapter.scriptHash(shard));
    }

    @TempDir
    Path temp;

    /** One L1 seen identically by every node. */
    final Map<String, Utxo> l1 = Collections.synchronizedMap(new LinkedHashMap<>());
    final List<AppMessage> bus = new ArrayList<>();
    final List<byte[]> submitted = Collections.synchronizedList(new ArrayList<>());
    /** The anchored state: claim leaves, deposit records and the domain status, as the app ledger commits them. */
    final MpfTrie trie = new MpfTrie(new MapNodeStore());
    long now = NOW;
    long anchorHeight = ANCHORED;
    String exitModeDatum = "d87980";

    // ------------------------------------------------------------------ the L1

    static byte[] filled(int length, int value) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    static String enterprise(byte[] scriptHash) {
        return AddressProvider.getEntAddress(Credential.fromScript(scriptHash), Networks.testnet()).getAddress();
    }

    static String keyAddress(byte[] seed) {
        return AddressProvider.getEntAddress(Credential.fromKey(Blake2bUtil.blake2bHash224(publicKey(seed))),
                Networks.testnet()).getAddress();
    }

    static byte[] publicKey(byte[] seed) {
        return KeyGenUtil.getPublicKeyFromPrivateKey(seed);
    }

    static Utxo utxo(String txHash, int index, String address, long lovelace, List<AssetAmount> assets, byte[] datum,
                     String scriptHash) {
        return new Utxo(new Outpoint(txHash, index), address, BigInteger.valueOf(lovelace), assets, null, datum,
                scriptHash == null ? null : "00".repeat(5_000), scriptHash, false, 100, 10, "bb".repeat(32));
    }

    static List<AssetAmount> asset(String policy, String name, long quantity) {
        return List.of(new AssetAmount(policy, name, BigInteger.valueOf(quantity)));
    }

    void put(Utxo utxo) {
        l1.put(utxo.outpoint().txHash() + "#" + utxo.outpoint().index(), utxo);
    }

    static byte[] serialize(com.bloxbean.cardano.client.plutus.spec.PlutusData data) {
        try {
            return data.serializeToBytes();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    UtxoState view() {
        return new UtxoState() {
            @Override
            public List<Utxo> getUtxosByAddress(String address, int page, int count) {
                if (page > 1) return List.of();
                synchronized (l1) {
                    return l1.values().stream().filter(u -> u.address().equals(address)).toList();
                }
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
                return List.of();
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
    }

    /** Accepted custody, the 16 empty shards, the exit-mode thread, the reference scripts and the fee wallets. */
    void seed() {
        put(utxo("61".repeat(32), 0, enterprise(VAULT_HASH), 50_000_000, asset("f0".repeat(28), "5553444d", 100_000),
                HexFormat.of().parseHex("d87a8180"), null));
        put(utxo("61".repeat(32), 1, enterprise(VAULT_HASH), 50_000_000, asset("f0".repeat(28), "5553444d", 100_000),
                HexFormat.of().parseHex("d87a8180"), null));
        for (int shard = 0; shard < 16; shard++) {
            put(utxo("62".repeat(32), shard, enterprise(SHARD_HASH), 2_000_000,
                    asset(SHARD_POLICY, String.format("%02x", shard), 1),
                    serialize(ConstrPlutusData.of(0, BigIntPlutusData.of(1), BigIntPlutusData.of(shard),
                            BytesPlutusData.of(new byte[32]))), null));
        }
        put(utxo("64".repeat(32), 0, enterprise(EXIT_HASH), 2_000_000, asset(EXIT_POLICY, "", 1),
                HexFormat.of().parseHex(exitModeDatum), null));
        put(utxo("65".repeat(32), 0, keyAddress(FEE_SEED), 30_000_000, List.of(), null,
                HexFormat.of().formatHex(VAULT_HASH)));
        put(utxo("65".repeat(32), 1, keyAddress(FEE_SEED), 30_000_000, List.of(), null,
                HexFormat.of().formatHex(SHARD_HASH)));
        put(utxo("60".repeat(32), 0, keyAddress(FEE_SEED), 80_000_000, List.of(), null, null));
        put(utxo("6a".repeat(32), 0, keyAddress(RELAYER_FEE_SEED), 80_000_000, List.of(), null, null));
        trie.put(STATUS_KEY, new DomainStatusV1(DomainStatusV1.Status.ACTIVE, 50, 0, DAY).encode());
    }

    /** The live anchor at {@code anchorHeight}, members owner and member, threshold 2, over the trie's root. */
    void anchor() {
        List<com.bloxbean.cardano.client.plutus.spec.PlutusData> members = new ArrayList<>();
        TreeMap<String, byte[]> keys = new TreeMap<>();
        for (byte[] seed : List.of(OWNER_SEED, MEMBER_SEED)) {
            keys.put(HexFormat.of().formatHex(publicKey(seed)), publicKey(seed));
        }
        keys.values().forEach(key -> members.add(BytesPlutusData.of(key)));
        var datum = ConstrPlutusData.of(0, BigIntPlutusData.of(2), BytesPlutusData.of(CHAIN.getBytes()),
                BytesPlutusData.of(HexFormat.of().parseHex(GENESIS)), BytesPlutusData.of("asset-payments".getBytes()),
                BytesPlutusData.of("mpf-blake2b256-v1".getBytes()),
                BytesPlutusData.of(HexFormat.of().parseHex(FINGERPRINT)), BigIntPlutusData.of(anchorHeight),
                BytesPlutusData.of(filled(32, 0x66)), BytesPlutusData.of(trie.getRootHash()),
                ListPlutusData.builder().plutusDataList(members).build(), BigIntPlutusData.of(2),
                BigIntPlutusData.of(NOW - 60_000));
        synchronized (l1) {
            l1.keySet().removeIf(key -> key.startsWith("63".repeat(32)));
        }
        put(utxo("63".repeat(32), (int) (anchorHeight % 1000), enterprise(ANCHOR_HASH), 2_000_000,
                asset(ANCHOR_POLICY, "", 1), serialize(datum), null));
    }

    // ------------------------------------------------------------------ committed state

    /** A node's committed state, as its ledger answers the SettlementQueries. */
    static final class Ledger {
        final Map<Hash32, ClaimLeafV1> leaves = new LinkedHashMap<>();
        final Map<Hash32, SettlementRecords.ClaimStatusV1> status = new HashMap<>();
        final Map<Hash32, DepositRecordV1> deposits = new HashMap<>();
        DomainStatusV1 domain = new DomainStatusV1(DomainStatusV1.Status.ACTIVE, 50, 0, DAY);

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
                case SettlementQueries.DEPOSIT -> Optional.ofNullable(deposits.get(Hash32.of(request)))
                        .map(DepositRecordV1::encode).orElse(new byte[0]);
                default -> throw new IllegalArgumentException(path);
            };
        }
    }

    /** A claim reserved at {@code height} and {@code reservedAtMs}, committed on every node and in the trie. */
    ClaimLeafV1 reserve(int shard, int seed, long height, long reservedAtMs, Node... nodes) {
        byte[] id = Blake2bUtil.blake2bHash256(("claim-" + seed).getBytes());
        id[31] = (byte) ((id[31] & 0xf0) | shard);
        ClaimLeafV1 leaf = new ClaimLeafV1(Hash32.of(id), VAULT, ClaimKind.USER_WITHDRAWAL,
                AssetValue.single(USDM, BigInteger.valueOf(100 + seed)), BigInteger.valueOf(1_500_000),
                concat(new byte[] {0x60}, filled(28, 0x80 + seed % 64)), height, reservedAtMs);
        for (Node node : nodes) {
            node.ledger.leaves.put(leaf.claimId(), leaf);
            node.ledger.status.put(leaf.claimId(),
                    SettlementRecords.ClaimStatusV1.reserved(leaf.claimId(), leaf.reservedHeight()));
        }
        if (height <= anchorHeight) trie.put(concat(CLAIM_PREFIX, leaf.claimId().bytes()), leaf.encode());
        return leaf;
    }

    /** A deposit-shaped output at the vault, recorded on every node with {@code reason} at {@code height}. */
    ResolvedOutput.Outpoint deposit(int index, long height, DepositAdmission.Reason reason, Node... nodes) {
        String txHash = "7d".repeat(32);
        byte[] refundKey = filled(28, 0xc1);
        byte[] datum = new DepositDatumV1(filled(28, 0xa1), concat(new byte[] {0x60}, refundKey), refundKey).encode();
        long lovelace = 2_000_000L + index;
        long quantity = 500 + index;
        put(utxo(txHash, index, enterprise(VAULT_HASH), lovelace, asset("f0".repeat(28), "5553444d", quantity), datum,
                null));
        Hash32 tx = Hash32.ofHex(txHash);
        Hash32 depositId = GatewayIds.depositId(VAULT, tx, index);
        boolean credited = reason == DepositAdmission.Reason.NONE;
        DepositRecordV1 record = new DepositRecordV1(depositId, VAULT, tx, index, 9_000, height,
                VaultOutputShape.DEPOSIT,
                credited ? DepositRecordV1.Status.CREDITED : DepositRecordV1.Status.UNCREDITED,
                reason, credited ? filled(28, 0xa1) : new byte[0], BigInteger.valueOf(quantity),
                BigInteger.valueOf(lovelace), 0, Hash32.of(filled(32, 0x5e)),
                credited ? BigInteger.valueOf(quantity) : BigInteger.ZERO,
                credited ? BigInteger.valueOf(lovelace) : BigInteger.ZERO);
        for (Node node : nodes) node.ledger.deposits.put(depositId, record);
        if (height <= anchorHeight) trie.put(concat(DEPOSIT_PREFIX, depositId.bytes()), record.encode());
        return new ResolvedOutput.Outpoint(tx, index);
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ------------------------------------------------------------------ nodes

    final class Node implements AppChainEffectContext {
        final byte[] seed;
        final Ledger ledger = new Ledger();
        BridgeDiffusionHandler handler;
        /** The node's L1-stable anchor frontier (the proof floor). */
        long stable = ANCHORED;
        final List<Long> proofHeights = Collections.synchronizedList(new ArrayList<>());

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

                @Override public byte[] publicKey() { return AssetSettlementTasksTest.publicKey(seed); }
            };
        }

        @Override public Supplier<Set<String>> members() { return Set::of; }

        @Override public IntSupplier threshold() { return () -> 2; }

        @Override
        public AppQueryResult query(String path, byte[] request) {
            return new AppQueryResult(CHAIN, "asset-payments", 1, new byte[32], ledger.answer(path, request));
        }

        @Override public Supplier<UtxoState> l1UtxoView() { return AssetSettlementTasksTest.this::view; }

        @Override public Supplier<ProtocolParams> protocolParams() { return AssetSettlementTasksTest::params; }

        @Override
        public TxEvaluationGateway txEvaluation() {
            return new TxEvaluationGateway() {
                @Override public boolean isTransactionEvaluationAvailable() { return true; }

                @Override
                public List<TxEvaluationResult> evaluateTransaction(byte[] cbor) {
                    List<TxEvaluationResult> out = new ArrayList<>();
                    for (EvaluationResult result : evaluate(cbor)) {
                        out.add(new TxEvaluationResult("spend", result.getIndex(),
                                result.getExUnits().getMem().longValueExact(),
                                result.getExUnits().getSteps().longValueExact()));
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

        /** The retained proof at the anchored height only, as a node with the L1-stable anchor floor serves it. */
        @Override
        public Optional<StateProofEnvelope> stateProofAtHeight(long height, byte[] key) {
            proofHeights.add(height);
            byte[] value = trie.get(key);
            if (height != anchorHeight || value == null) return Optional.empty();
            var identity = StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF,
                    HexFormat.of().parseHex(GENESIS));
            var proof = new org.yanoproject.x.framework.api.appchain.state.StateProof(
                    new StateSnapshot(identity, height, trie.getRootHash()), key, value,
                    org.yanoproject.x.framework.api.appchain.state.StateProof.Presence.PRESENT,
                    StateCommitmentProfiles.MPF.proofEncodingId(), trie.getProofWire(key).orElseThrow());
            return Optional.of(new StateProofEnvelope(1, CHAIN, filled(32, 0x0b), proof, FinalityCert.empty()));
        }

        @Override
        public long stableAnchoredHeight() {
            return stable;
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
        pp.setMaxCollateralInputs(3);
        pp.setCoinsPerUtxoSize("4310");
        pp.setMinFeeRefScriptCostPerByte(new java.math.BigDecimal(15));
        pp.setProtocolMajorVer(11);
        pp.setProtocolMinorVer(0);
        LinkedHashMap<String, List<Long>> raw = new LinkedHashMap<>();
        raw.put("PlutusV3", PLUTUS_V3_COSTS);
        pp.setCostModelsRaw(raw);
        return pp;
    }

    /** The devnet's PlutusV3 cost model (protocol version 11), which prices every builtin the validators use. */
    static final List<Long> PLUTUS_V3_COSTS = costModel();

    static List<Long> costModel() {
        try (InputStream in = Objects.requireNonNull(AssetSettlementTasksTest.class.getResourceAsStream(
                "/cardano-fixtures/plutus-v3-cost-model.txt"))) {
            return Arrays.stream(new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim().split("\\s+"))
                    .map(Long::parseLong).toList();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ------------------------------------------------------------------ the compiled validators

    /** Evaluates every script of {@code cbor} with the compiled validators over this L1; throws unless all pass. */
    List<EvaluationResult> evaluate(byte[] cbor) {
        UtxoSupplier supplier = new UtxoSupplier() {
            @Override
            public List<com.bloxbean.cardano.client.api.model.Utxo> getPage(String address, Integer count,
                                                                            Integer page, OrderEnum order) {
                return List.of();
            }

            @Override
            public Optional<com.bloxbean.cardano.client.api.model.Utxo> getTxOutput(String txHash, int index) {
                return Optional.ofNullable(l1.get(txHash + "#" + index))
                        .map(utxo -> PayoutTxBuilder.utxo(NodeL1View.convert(utxo)));
            }
        };
        ProtocolParamsSupplier params = AssetSettlementTasksTest::params;
        Map<String, PlutusScript> scripts = Map.of(HexFormat.of().formatHex(VAULT_HASH), VAULT_SCRIPT,
                HexFormat.of().formatHex(SHARD_HASH), SHARD_SCRIPT);
        JulcTransactionEvaluator evaluator = new JulcTransactionEvaluator(supplier, params,
                hash -> Optional.ofNullable(scripts.get(hash)), SLOTS);
        try {
            Transaction tx = Transaction.deserialize(cbor);
            Set<com.bloxbean.cardano.client.api.model.Utxo> inputs = new HashSet<>();
            List<TransactionInput> all = new ArrayList<>(tx.getBody().getInputs());
            if (tx.getBody().getReferenceInputs() != null) all.addAll(tx.getBody().getReferenceInputs());
            if (tx.getBody().getCollateral() != null) all.addAll(tx.getBody().getCollateral());
            for (TransactionInput input : all) {
                supplier.getTxOutput(input.getTransactionId(), input.getIndex()).ifPresent(inputs::add);
            }
            Result<List<EvaluationResult>> result = evaluator.evaluateTx(cbor, inputs);
            if (!result.isSuccessful()) throw new IllegalStateException("phase 2: " + result.getResponse());
            return result.getValue();
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static Program template(String name) {
        try (InputStream in = Objects.requireNonNull(AssetSettlementTasksTest.class.getResourceAsStream(
                "/META-INF/plutus/" + name + ".plutus.json"), name)) {
            Matcher matcher = Pattern.compile("\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"")
                    .matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!matcher.find()) throw new IllegalStateException("no cborHex in " + name);
            return JulcScriptAdapter.toProgram(matcher.group(1));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();

        @Override public byte[] get(byte[] hash) { return nodes.get(HexFormat.of().formatHex(hash)); }

        @Override
        public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(HexFormat.of().formatHex(hash), nodeBytes);
        }

        @Override public void delete(byte[] hash) { nodes.remove(HexFormat.of().formatHex(hash)); }
    }

    // ------------------------------------------------------------------ the factory

    Map<String, String> config(boolean owner, boolean relayer) throws Exception {
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
        config.put("round-timeout-ms", "300");
        config.put("slot.zero-time-ms", "0");
        config.put("slot.zero-slot", "0");
        config.put("slot.length-ms", "1000");
        if (owner) {
            Path key = temp.resolve("fee.skey");
            Files.writeString(key, HexFormat.of().formatHex(FEE_SEED));
            config.put("owner", "true");
            config.put("fee-wallet.key-file", key.toString());
        }
        if (relayer) {
            config.put("exit-relayer.enabled", "true");
            if (!owner) {
                Path key = temp.resolve("relayer.skey");
                Files.writeString(key, HexFormat.of().formatHex(RELAYER_FEE_SEED));
                config.put("exit-relayer.fee-wallet.key-file", key.toString());
            }
        }
        return config;
    }

    AppEffectExecutor product(Node node, boolean owner, boolean relayer) throws Exception {
        List<AppEffectExecutor> products = AssetSettlementExecutorFactory.create(config(owner, relayer), node,
                () -> now);
        assertThat(products).hasSize(1);
        return products.getFirst();
    }

    void pump(Node... nodes) {
        Thread pump = new Thread(() -> {
            long until = System.currentTimeMillis() + 30_000;
            int delivered = 0;
            while (System.currentTimeMillis() < until) {
                List<AppMessage> pending;
                synchronized (bus) {
                    pending = new ArrayList<>(bus.subList(delivered, bus.size()));
                }
                for (AppMessage message : pending) {
                    for (Node node : nodes) {
                        if (!Arrays.equals(publicKey(node.seed), message.getSender())) {
                            node.handler.onBridgeMessage(message);
                        }
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

    // ------------------------------------------------------------------ helpers on submitted transactions

    static Transaction tx(byte[] cbor) {
        try {
            return Transaction.deserialize(cbor);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static Set<String> inputs(byte[] cbor) {
        Set<String> inputs = new HashSet<>();
        for (TransactionInput input : tx(cbor).getBody().getInputs()) {
            inputs.add(input.getTransactionId() + "#" + input.getIndex());
        }
        return inputs;
    }

    /** No outpoint is spent by two submitted transactions. */
    void assertNoDoubleSpend() {
        Set<String> seen = new HashSet<>();
        synchronized (submitted) {
            for (byte[] cbor : submitted) {
                for (String input : inputs(cbor)) assertThat(seen.add(input)).as("spent twice: " + input).isTrue();
            }
        }
    }

    /** The submitted transaction, signed, passes the compiled validators. */
    void assertPasses(byte[] cbor) {
        assertThat(evaluate(cbor)).isNotEmpty();
    }

    /** The lead vault redeemer of a submitted transaction. */
    com.bloxbean.cardano.client.plutus.spec.PlutusData leadRedeemer(byte[] cbor) {
        Transaction tx = tx(cbor);
        List<String> order = tx.getBody().getInputs().stream()
                .map(in -> in.getTransactionId() + "#" + in.getIndex()).sorted().toList();
        for (int i = 0; i < order.size(); i++) {
            Utxo spent = l1.get(order.get(i));
            if (spent != null && spent.address().equals(enterprise(VAULT_HASH))) {
                int index = i;
                return tx.getWitnessSet().getRedeemers().stream()
                        .filter(r -> r.getTag() == RedeemerTag.Spend && r.getIndex().intValue() == index)
                        .findFirst().orElseThrow().getData();
            }
        }
        throw new AssertionError("no vault input");
    }

    static EffectExecutionContext attempt(int number, byte[] submittedRef) {
        return new EffectExecutionContext() {
            @Override public String chainId() { return CHAIN; }

            @Override public long tipHeight() { return ANCHORED; }

            @Override public long anchoredHeight() { return ANCHORED; }

            @Override public int attempt() { return number; }

            @Override public byte[] submittedRef() { return submittedRef; }

            @Override public Map<String, String> settings() { return Map.of(); }
        };
    }

    static PendingEffect effect(SettlementBatchV1 batch, int ordinal) {
        return PendingEffect.of(new EffectRecord(EffectRecord.RECORD_VERSION, CHAIN, 5, ordinal,
                SettlementBatchV1.EFFECT_TYPE, batch.encode(), SettlementBatchV1.scope(0), FinalityGate.APP_FINAL,
                ResultPolicy.CHAIN, 100, null));
    }

    // ------------------------------------------------------------------ Accept

    @Test
    void theOwnerAcceptsCreditedDepositsWithACoSignedAcceptThatPassesTheCompiledValidator() throws Exception {
        seed();
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        List<ResolvedOutput.Outpoint> credited = new ArrayList<>();
        for (int i = 0; i < 3; i++) credited.add(deposit(i, 900, DepositAdmission.Reason.NONE, owner, member));
        deposit(3, ANCHORED + 1, DepositAdmission.Reason.NONE, owner, member);       // credited above the anchor
        deposit(4, 900, DepositAdmission.Reason.DEPOSITS_PAUSED, owner, member);     // UNCREDITED
        deposit(5, 900, DepositAdmission.Reason.NONE);                               // unknown to the ledger
        anchor();
        AssetSettlementExecutor executor = (AssetSettlementExecutor) product(owner, true, false);
        product(member, false, false);
        pump(owner, member);

        String outcome = executor.acceptor().orElseThrow().runOnce();

        assertThat(outcome).as(outcome).startsWith("accepted (co-signed) 3 deposits");
        assertThat(submitted).hasSize(1);
        byte[] accept = submitted.getFirst();
        assertPasses(accept);
        Set<String> spent = inputs(accept);
        for (ResolvedOutput.Outpoint outpoint : credited) assertThat(spent).contains(outpoint.toString());
        assertThat(spent).hasSize(4); // the three credited deposits and the fee wallet
        assertThat(GatewayPlutus.isAcceptCosigned(leadRedeemer(accept))).isTrue();
        Transaction tx = tx(accept);
        assertThat(tx.getBody().getRequiredSigners()).hasSize(2);
        assertThat(tx.getWitnessSet().getVkeyWitnesses()).hasSize(3);
        assertThat(GatewayPlutus.decodeContinuation(tx.getBody().getOutputs().getFirst().getInlineDatum())).isEmpty();

        // A second pass does not accept the same deposits again while the first Accept is pending.
        assertThat(executor.acceptor().orElseThrow().runOnce()).isEqualTo("nothing to accept");
        assertThat(submitted).hasSize(1);
    }

    @Test
    void withoutAQuorumTheAcceptFallsBackToProofsAtTheL1StableAnchor() throws Exception {
        seed();
        Node owner = new Node(OWNER_SEED);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner);
        deposit(1, 950, DepositAdmission.Reason.NONE, owner);
        anchor();
        AssetSettlementExecutor executor = (AssetSettlementExecutor) product(owner, true, false);
        DepositAcceptor acceptor = executor.acceptor().orElseThrow();
        pump(owner); // the other member is offline

        // The live anchor is not yet L1-stable: the proofs at its height are not guaranteed, so nothing is built.
        owner.stable = ANCHORED - 10;
        assertThat(acceptor.runOnce()).contains("co-sign round incomplete").contains("awaiting an L1-stable anchor");
        assertThat(submitted).isEmpty();

        owner.stable = ANCHORED;
        String outcome = acceptor.runOnce();
        assertThat(outcome).as(outcome).startsWith("accepted (proved) 2 deposits");
        byte[] accept = submitted.getFirst();
        assertPasses(accept);
        assertThat(tx(accept).getBody().getRequiredSigners()).isEmpty();
        assertThat(((ConstrPlutusData) leadRedeemer(accept)).getAlternative()).isEqualTo(GatewayPlutus.ACCEPT_PROVED);
        assertThat(owner.proofHeights).isNotEmpty().containsOnly(ANCHORED);
    }

    // ------------------------------------------------------------------ the exit relayer

    @Test
    void anEligibleClaimIsRelayedByProofAndIneligibleOnesAreNot() throws Exception {
        seed();
        Node owner = new Node(OWNER_SEED);
        Node relayerNode = new Node(MEMBER_SEED);
        ClaimLeafV1 eligible = reserve(3, 1, 900, NOW - 2 * DAY, owner, relayerNode);
        ClaimLeafV1 immature = reserve(3, 2, 900, NOW - HOUR, owner, relayerNode);
        ClaimLeafV1 unanchored = reserve(4, 3, ANCHORED + 5, NOW - 2 * DAY, owner, relayerNode);
        ClaimLeafV1 paid = reserve(5, 4, 900, NOW - 2 * DAY, owner, relayerNode);
        relayerNode.ledger.status.put(paid.claimId(), relayerNode.ledger.status.get(paid.claimId())
                .resolve(SettlementRecords.ClaimStatus.PAID, Hash32.of(filled(32, 0x77)), 950));
        anchor();
        AppEffectExecutor product = product(relayerNode, false, true);
        assertThat(product.effectTypes()).as("a relaying non-owner still owns no effect type").isEmpty();
        ExitRelayTask relayer = ((AssetSettlementExecutorFactory.CosignOnly) product).relayer().orElseThrow();

        // Not yet L1-stable: no proof floor covers the live anchor's height.
        relayerNode.stable = ANCHORED - 1;
        assertThat(relayer.runOnce()).isEqualTo("waiting for the live anchor to be L1-stable");
        assertThat(submitted).isEmpty();

        relayerNode.stable = ANCHORED;
        String outcome = relayer.runOnce();
        assertThat(outcome).as(outcome).startsWith("relayed 1 on shard 3");
        assertThat(submitted).hasSize(1);
        byte[] exit = submitted.getFirst();
        assertPasses(exit);
        Transaction tx = tx(exit);
        assertThat(tx.getBody().getRequiredSigners()).isNullOrEmpty();
        assertThat(((ConstrPlutusData) leadRedeemer(exit)).getAlternative()).isEqualTo(GatewayPlutus.EXIT);
        int k = tx.getBody().getOutputs().size() - 3;
        assertThat(GatewayPlutus.decodeContinuation(tx.getBody().getOutputs().get(k).getInlineDatum()))
                .containsExactly(eligible.claimId());
        assertThat(inputs(exit)).contains("6a".repeat(32) + "#0").doesNotContain("60".repeat(32) + "#0");
        assertThat(relayerNode.proofHeights).isNotEmpty().containsOnly(ANCHORED);
        // The claim is not relayed twice while its Exit is pending (its insert is in the mirror).
        assertThat(relayer.runOnce()).isEqualTo("no claim is EXIT_ELIGIBLE");
        assertThat(immature.claimId()).isNotEqualTo(unanchored.claimId());
    }

    @Test
    void theRelayerRefusesInExitMode() throws Exception {
        exitModeDatum = "d87a80";
        seed();
        Node relayerNode = new Node(MEMBER_SEED);
        reserve(3, 1, 900, NOW - 2 * DAY, relayerNode);
        anchor();
        ExitRelayTask relayer = ((AssetSettlementExecutorFactory.CosignOnly) product(relayerNode, false, true))
                .relayer().orElseThrow();
        assertThat(relayer.runOnce()).isEqualTo(org.yanoproject.x.assets.cardano.support.settlement.ExitRelayer
                .EXIT_MODE_REFUSAL);
        assertThat(submitted).isEmpty();
    }

    // ------------------------------------------------------------------ the concurrency contract

    /**
     * On the owner, a Settle, the Accept batcher and the exit relayer run at once on three threads: they serialize on
     * one assembly lock, no two submissions spend one outpoint, the relayer leaves the shard a pending Settle spends,
     * and a Settle on the shard a pending Exit spends waits for its inclusion.
     */
    @Test
    void theRelayerAndTheAcceptBatcherShareTheSettleExecutorsAssembly() throws Exception {
        seed();
        // Each transaction spends up to three fee-wallet outputs: enough for four at once.
        for (int i = 1; i < 12; i++) {
            put(utxo("60".repeat(32), i, keyAddress(FEE_SEED), 80_000_000, List.of(), null, null));
        }
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        // Shard 3: an eligible claim that a Settle batch also names. Shard 4: an eligible claim only the relayer pays.
        ClaimLeafV1 settled = reserve(3, 11, 900, NOW - 2 * DAY, owner, member);
        ClaimLeafV1 relayed = reserve(4, 12, 900, NOW - 2 * DAY, owner, member);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner, member);
        anchor();
        AssetSettlementExecutor executor = (AssetSettlementExecutor) product(owner, true, true);
        product(member, false, false);
        pump(owner, member);
        PendingEffect settle = effect(new SettlementBatchV1(VAULT, 3, List.of(settled.claimId())), 0);

        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(3);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        var settleResult = workers.submit(() -> {
            start.await();
            return executor.execute(attempt(1, new byte[0]), settle);
        });
        var relayResult = workers.submit(() -> {
            start.await();
            return executor.relayer().orElseThrow().runOnce();
        });
        var acceptResult = workers.submit(() -> {
            start.await();
            return executor.acceptor().orElseThrow().runOnce();
        });
        start.countDown();
        EffectExecution settleOutcome = settleResult.get(120, java.util.concurrent.TimeUnit.SECONDS);
        String relayOutcome = relayResult.get(120, java.util.concurrent.TimeUnit.SECONDS);
        String acceptOutcome = acceptResult.get(120, java.util.concurrent.TimeUnit.SECONDS);
        workers.shutdown();

        assertNoDoubleSpend();
        synchronized (submitted) {
            for (byte[] cbor : submitted) assertPasses(cbor);
        }
        assertThat(acceptOutcome).as(acceptOutcome).startsWith("accepted (co-signed) 1 deposits");
        // Whichever went first, shard 4's claim is relayed; shard 3's claim is paid once, by the Settle or the Exit.
        assertThat(relayOutcome).as(relayOutcome).contains("on shard 4");
        List<Hash32> paidOnShard3 = new ArrayList<>();
        synchronized (submitted) {
            for (byte[] cbor : submitted) {
                Transaction tx = tx(cbor);
                for (var out : tx.getBody().getOutputs()) {
                    if (!paysVault(out) || out.getInlineDatum() == null) continue;
                    for (Hash32 id : GatewayPlutus.decodeContinuation(out.getInlineDatum())) {
                        if (id.equals(settled.claimId())) paidOnShard3.add(id);
                    }
                }
            }
        }
        assertThat(paidOnShard3).hasSize(1);
        if (settleOutcome instanceof EffectExecution.Submitted) {
            assertThat(relayOutcome).doesNotContain("on shard 3");
        } else {
            assertThat(settleOutcome).isInstanceOf(EffectExecution.Retry.class);
            assertThat(executor.lastOutcome())
                    .isIn("awaiting the pending payout on this shard", "awaiting confirmation");
        }
        assertThat(relayed.claimId()).isNotEqualTo(settled.claimId());
    }

    /**
     * Both orders, one at a time: a pending Settle keeps the relayer off its shard (and its fee and custody inputs),
     * and a pending Exit makes a Settle on its shard wait; an Accept never takes a pending payout's fee input.
     */
    @Test
    void aPendingPayoutKeepsTheOtherOffItsShardAndItsInputs() throws Exception {
        seed();
        for (int i = 1; i < 12; i++) {
            put(utxo("60".repeat(32), i, keyAddress(FEE_SEED), 80_000_000, List.of(), null, null));
        }
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        ClaimLeafV1 first = reserve(3, 21, 900, NOW - 2 * DAY, owner, member);
        ClaimLeafV1 second = reserve(6, 22, 900, NOW - 2 * DAY, owner, member);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner, member);
        anchor();
        AssetSettlementExecutor executor = (AssetSettlementExecutor) product(owner, true, true);
        product(member, false, false);
        pump(owner, member);

        // The Settle goes first on shard 3: the relayer leaves shard 3 and pays shard 6.
        EffectExecution settle = executor.execute(attempt(1, new byte[0]),
                effect(new SettlementBatchV1(VAULT, 3, List.of(first.claimId())), 0));
        assertThat(settle).as(executor.lastOutcome()).isInstanceOf(EffectExecution.Submitted.class);
        String relayed = executor.relayer().orElseThrow().runOnce();
        assertThat(relayed).as(relayed).startsWith("relayed 1 on shard 6").doesNotContain("shard 3");
        // The Accept takes neither one's fee inputs.
        assertThat(executor.acceptor().orElseThrow().runOnce()).startsWith("accepted (co-signed) 1 deposits");
        assertNoDoubleSpend();
        synchronized (submitted) {
            for (byte[] cbor : submitted) assertPasses(cbor);
        }

        // The Exit is pending on shard 6: a Settle there waits for its inclusion instead of conflicting with it.
        EffectExecution waiting = executor.execute(attempt(1, new byte[0]),
                effect(new SettlementBatchV1(VAULT, 6, List.of(second.claimId())), 1));
        assertThat(waiting).isInstanceOf(EffectExecution.Retry.class);
        assertThat(executor.lastOutcome()).isEqualTo("awaiting the pending payout on this shard");
        assertThat(submitted).hasSize(3);
    }

    static boolean paysVault(com.bloxbean.cardano.client.transaction.spec.TransactionOutput out) {
        byte[] raw = new com.bloxbean.cardano.client.address.Address(out.getAddress()).getBytes();
        return raw.length == 29 && Arrays.equals(Arrays.copyOfRange(raw, 1, 29), VAULT_HASH);
    }
}
