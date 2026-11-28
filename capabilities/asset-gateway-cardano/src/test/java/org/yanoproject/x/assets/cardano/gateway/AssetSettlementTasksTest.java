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
import org.yanoproject.x.assets.cardano.support.settlement.ShardBackend;
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
 *       falls back to proofs at the live anchor's height.</li>
 *   <li>Exit relayer (D9): an eligible claim is relayed by proof {@code Exit} with proofs from the node at the
 *       live anchor's height, however far that anchor is past the L1-stable one; ineligible claims are not; it refuses
 *       in exit mode.</li>
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
    /** How long before {@code now} a product starts: past its startup grace, so it assembles at once. */
    long startedAgoMs = HOUR;
    long anchorHeight = ANCHORED;
    String exitModeDatum = "d87980";
    /** Addresses whose lookups fail in the L1 view. */
    final Set<String> brokenAddresses = Collections.synchronizedSet(new HashSet<>());

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

    /** Outputs the L1 has spent: the spent half of the host's spent-or-unspent reads. */
    final Map<String, Utxo> spent = Collections.synchronizedMap(new LinkedHashMap<>());
    /** The applied tip slot of a stale view; null while the view follows the clock (one slot per second). */
    Long frozenTipSlot;
    /** Whether spent-or-unspent point reads fail. */
    boolean pointReadsFail;
    /** Whether the node's UTxO store is disabled. */
    boolean storeDisabled;

    /** The effective TTL file of {@code chain} and the fee wallet of {@code feeSeed} under {@code base}. */
    static Path ttlFileOf(Path base, String chain, byte[] feeSeed) {
        String wallet = HexFormat.of().formatHex(Blake2bUtil.blake2bHash224(publicKey(feeSeed)), 0, 4);
        return base.resolveSibling(base.getFileName() + "." + chain + "." + wallet);
    }

    /** Undoes {@link #land}: a rollback of the transaction's inclusion. */
    void unland(byte[] signedTx) throws Exception {
        Transaction tx = tx(signedTx);
        String id = TransactionUtil.getTxHash(signedTx);
        for (int i = 0; i < tx.getBody().getOutputs().size(); i++) {
            l1.remove(id + "#" + i);
            spent.remove(id + "#" + i);
        }
        for (var input : tx.getBody().getInputs()) {
            Utxo back = spent.remove(input.getTransactionId() + "#" + input.getIndex());
            if (back != null) put(back);
        }
    }

    UtxoState view() {
        return new View();
    }

    /** The node's UTxO view: unspent outputs by address, spent-or-unspent point reads, and its applied tip slot. */
    final class View implements UtxoState, org.yanoproject.api.rollback.RollbackCapableStore {
        @Override
        public List<Utxo> getUtxosByAddress(String address, int page, int count) {
            if (brokenAddresses.contains(address)) throw new IllegalStateException("unreadable " + address);
            List<Utxo> all;
            synchronized (l1) {
                all = l1.values().stream().filter(u -> u.address().equals(address)).toList();
            }
            int from = (page - 1) * count;
            return from >= all.size() ? List.of() : all.subList(from, Math.min(all.size(), from + count));
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
        public Optional<Utxo> getUtxoSpentOrUnspent(Outpoint outpoint) {
            if (pointReadsFail) throw new IllegalStateException("point read failed");
            String key = outpoint.txHash() + "#" + outpoint.index();
            return Optional.ofNullable(l1.get(key)).or(() -> Optional.ofNullable(spent.get(key)));
        }

        @Override
        public boolean isEnabled() {
            return !storeDisabled;
        }

        @Override
        public String storeName() {
            return "test-utxo";
        }

        @Override
        public long getLatestAppliedSlot() {
            Long frozen = frozenTipSlot;
            return frozen != null ? frozen : now / 1_000;
        }

        @Override
        public long getRollbackFloorSlot() {
            return 0;
        }

        @Override
        public void rollbackToSlot(long slot) {
            throw new UnsupportedOperationException();
        }
    }

    /** Spends {@code key} on L1 (by a transaction this test does not build). */
    void spend(String key) {
        Utxo out = l1.remove(key);
        if (out != null) spent.put(key, out);
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
        /** How often the claim index was walked (its count read). */
        final java.util.concurrent.atomic.AtomicInteger indexWalks = new java.util.concurrent.atomic.AtomicInteger();
        DomainStatusV1 domain = new DomainStatusV1(DomainStatusV1.Status.ACTIVE, 50, 0, DAY);

        byte[] answer(String path, byte[] request) {
            return switch (path) {
                case SettlementQueries.CLAIM -> {
                    Hash32 id = Hash32.of(request);
                    yield SettlementQueries.encodeClaim(Optional.ofNullable(leaves.get(id)).map(ClaimLeafV1::encode),
                            Optional.ofNullable(status.get(id)).map(SettlementRecords.ClaimStatusV1::encode));
                }
                case SettlementQueries.DOMAIN_STATUS -> domain.encode();
                case SettlementQueries.CLAIM_COUNT -> {
                    indexWalks.incrementAndGet();
                    yield SettlementQueries.encodeCount(leaves.size());
                }
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
        return reserve(shard, seed, height, reservedAtMs, 1_500_000, nodes);
    }

    /** {@link #reserve(int, int, long, long, Node...)} of a claim whose payout takes {@code vaultLovelace}. */
    ClaimLeafV1 reserve(int shard, int seed, long height, long reservedAtMs, long vaultLovelace, Node... nodes) {
        byte[] id = Blake2bUtil.blake2bHash256(("claim-" + seed).getBytes());
        id[31] = (byte) ((id[31] & 0xf0) | shard);
        ClaimLeafV1 leaf = new ClaimLeafV1(Hash32.of(id), VAULT, ClaimKind.USER_WITHDRAWAL,
                AssetValue.single(USDM, BigInteger.valueOf(100 + seed)), BigInteger.valueOf(vaultLovelace),
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
        return deposit(index, height, 9_000, reason, nodes);
    }

    /** {@link #deposit(int, long, DepositAdmission.Reason, Node...)} of an output created at L1 {@code slot}. */
    ResolvedOutput.Outpoint deposit(int index, long height, long slot, DepositAdmission.Reason reason,
                                    Node... nodes) {
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
        DepositRecordV1 record = new DepositRecordV1(depositId, VAULT, tx, index, slot, height,
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
        /** The chain this node runs: the TTL file is per chain. */
        String chain = CHAIN;
        /** How far a submission moves the clock: a slow submission. */
        long submitTakesMs;
        /** How far a diffused message moves the clock: a slow co-sign round. */
        long diffuseTakesMs;
        final List<Long> proofHeights = Collections.synchronizedList(new ArrayList<>());

        Node(byte[] seed) {
            this.seed = seed;
        }

        @Override public String chainId() { return chain; }

        @Override
        public void diffuse(String topic, byte[] body) {
            now += diffuseTakesMs;
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
            now += submitTakesMs;
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

        /**
         * The retained proof at the live anchor's height only, as a node with the L1-stable anchor floor serves it: the
         * floor keeps every height from the L1-stable anchor's on, so a live anchor newer than that one is proved too.
         */
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
        return product(node, owner, relayer, Map.of());
    }

    AppEffectExecutor product(Node node, boolean owner, boolean relayer, Map<String, String> extra) throws Exception {
        Map<String, String> config = config(owner, relayer);
        config.putAll(extra);
        long at = now;
        now = at - startedAgoMs;
        List<AppEffectExecutor> products;
        try {
            products = AssetSettlementExecutorFactory.create(config, node, () -> now);
        } finally {
            now = at;
        }
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

    /** Fable P1: the live anchor is newer than the L1-stable one on a chain that keeps anchoring; it is proved at. */
    @Test
    void withoutAQuorumTheAcceptFallsBackToProofsAtTheLiveAnchor() throws Exception {
        seed();
        Node owner = new Node(OWNER_SEED);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner);
        deposit(1, 950, DepositAdmission.Reason.NONE, owner);
        anchor();
        AssetSettlementExecutor executor = (AssetSettlementExecutor) product(owner, true, false);
        DepositAcceptor acceptor = executor.acceptor().orElseThrow();
        pump(owner); // the other member is offline

        String outcome = acceptor.runOnce();
        assertThat(outcome).as(outcome).startsWith("accepted (proved) 2 deposits");
        byte[] accept = submitted.getFirst();
        assertPasses(accept);
        assertThat(tx(accept).getBody().getRequiredSigners()).isEmpty();
        assertThat(((ConstrPlutusData) leadRedeemer(accept)).getAlternative()).isEqualTo(GatewayPlutus.ACCEPT_PROVED);
        assertThat(owner.proofHeights).isNotEmpty().containsOnly(ANCHORED);
    }

    /** Codex P2-4: the proved fallback's validity interval starts after the co-sign round's wait, not before it. */
    @Test
    void theProvedFallbackIsValidFromAfterTheCoSignRound() throws Exception {
        seed();
        Node owner = new Node(OWNER_SEED);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner);
        anchor();
        AssetSettlementExecutor executor = (AssetSettlementExecutor) product(owner, true, false);
        pump(owner); // the other member is offline
        owner.diffuseTakesMs = 11 * 60_000; // the round outlasts a whole validity window

        String outcome = executor.acceptor().orElseThrow().runOnce();

        assertThat(outcome).as(outcome).startsWith("accepted (proved) 1 deposits");
        var body = tx(submitted.getFirst()).getBody();
        long slot = now / 1_000;
        assertThat(body.getValidityStartInterval()).isLessThanOrEqualTo(slot);
        assertThat(body.getTtl()).isGreaterThan(slot);
        assertPasses(submitted.getFirst());
    }

    /**
     * Codex P2-7: the batcher reads one window of vault outputs per pass and carries a cursor across passes, so a
     * credited deposit past the first window (5,000 outputs) is reached.
     */
    @Test
    void aCreditedDepositPastTheFirstWindowOfVaultOutputsIsAccepted() throws Exception {
        seed(); // two custody outputs at the vault
        for (int i = 0; i < NodeL1View.PAGE * NodeL1View.MAX_PAGES; i++) {
            put(utxo("7e".repeat(32), i, enterprise(VAULT_HASH), 2_000_000, List.of(), null, null));
        }
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner, member); // output 5,003 at the vault address
        anchor();
        AssetSettlementExecutor executor = (AssetSettlementExecutor) product(owner, true, false);
        product(member, false, false);
        pump(owner, member);
        DepositAcceptor acceptor = executor.acceptor().orElseThrow();

        assertThat(acceptor.runOnce()).isEqualTo("nothing to accept");
        assertThat(acceptor.runOnce()).startsWith("accepted (co-signed) 1 deposits");
        // The window wraps back to the first page.
        assertThat(acceptor.runOnce()).isEqualTo("nothing to accept");
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

        // The live anchor is newer than the node's L1-stable one (Fable P1): the proof floor still covers its height.
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

    /** Replaces the vault's accepted custody with outputs of these lovelace amounts, each with plenty of USDM. */
    void custody(long... lovelace) {
        synchronized (l1) {
            l1.keySet().removeIf(key -> key.startsWith("61".repeat(32)));
        }
        for (int i = 0; i < lovelace.length; i++) {
            put(utxo("61".repeat(32), i, enterprise(VAULT_HASH), lovelace[i],
                    asset("f0".repeat(28), "5553444d", 100_000), HexFormat.of().parseHex("d87a8180"), null));
        }
    }

    /** Includes {@code signedTx} on the L1: its inputs leave the view and its outputs join it. */
    void land(byte[] signedTx) throws Exception {
        Transaction tx = tx(signedTx);
        String id = TransactionUtil.getTxHash(signedTx);
        for (var input : tx.getBody().getInputs()) spend(input.getTransactionId() + "#" + input.getIndex());
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

    /**
     * Fable P2-2: with the owner down, a relaying member proves an {@code Accept} with its own wallet and assembly,
     * and then relays an {@code Exit} that spends the custody it accepted.
     */
    @Test
    void withTheOwnerDownARelayerProvesAnAcceptAndRelaysAnExitFromIt() throws Exception {
        seed();
        custody(); // no accepted custody yet
        Node relayerNode = new Node(MEMBER_SEED);
        ClaimLeafV1 claim = reserve(3, 71, 900, NOW - 2 * DAY, relayerNode);
        for (int i = 0; i < 5; i++) deposit(i, 900, DepositAdmission.Reason.NONE, relayerNode);
        anchor();
        AssetSettlementExecutorFactory.CosignOnly product =
                (AssetSettlementExecutorFactory.CosignOnly) product(relayerNode, false, true);
        assertThat(product.effectTypes()).isEmpty();
        ExitRelayTask relayer = product.relayer().orElseThrow();
        assertThat(relayer.runOnce()).as("nothing to pay from yet").contains("shard 3:");

        String accepted = product.acceptor().orElseThrow().runOnce();

        assertThat(accepted).as(accepted).startsWith("accepted (proved) 5 deposits");
        byte[] accept = submitted.getLast();
        assertPasses(accept);
        assertThat(tx(accept).getBody().getRequiredSigners()).isNullOrEmpty();
        assertThat(inputs(accept)).contains("6a".repeat(32) + "#0"); // the relayer's own fee wallet
        land(accept);

        String relayed = relayer.runOnce();
        assertThat(relayed).as(relayed).startsWith("relayed 1 on shard 3");
        byte[] exit = submitted.getLast();
        assertPasses(exit);
        assertThat(inputs(exit)).contains(TransactionUtil.getTxHash(accept) + "#0");
        assertThat(paidBy(exit)).containsExactly(claim.claimId());
    }

    /** The claim ids an Exit or Settle pays, from its vault continuation (output k, after the k payouts). */
    static List<Hash32> paidBy(byte[] cbor) {
        Transaction tx = tx(cbor);
        int k = tx.getBody().getOutputs().size() - 3;
        return GatewayPlutus.decodeContinuation(tx.getBody().getOutputs().get(k).getInlineDatum());
    }

    /**
     * Codex P1-2, first case: an older claim no Exit can pay (one vault input of 6 ADA cannot cover 8 ADA and the
     * 2 ADA floor) does not take its shard's turn: the later 2 ADA claim on the same shard is relayed.
     */
    @Test
    void anUnpayableClaimDoesNotStarveTheNextClaimOnItsShard() throws Exception {
        seed();
        custody(6_000_000, 6_000_000);
        Node relayerNode = new Node(MEMBER_SEED);
        reserve(3, 51, 900, NOW - 3 * DAY, 8_000_000, relayerNode);
        ClaimLeafV1 payable = reserve(3, 52, 900, NOW - 2 * DAY, 2_000_000, relayerNode);
        anchor();
        ExitRelayTask relayer = ((AssetSettlementExecutorFactory.CosignOnly) product(relayerNode, false, true,
                Map.of("max-exit-batch", "1", "max-vault-inputs", "1"))).relayer().orElseThrow();

        String outcome = relayer.runOnce();

        assertThat(outcome).as(outcome).contains("relayed 1 on shard 3");
        assertThat(submitted).hasSize(1);
        assertPasses(submitted.getFirst());
        assertThat(paidBy(submitted.getFirst())).containsExactly(payable.claimId());
    }

    /**
     * Codex P1-2, second case: custody that cannot pay the whole batch (8 ADA for two 4 ADA claims and a 2 ADA floor)
     * pays a smaller one.
     */
    @Test
    void custodyShortOfTheBatchPaysASmallerOne() throws Exception {
        seed();
        custody(8_000_000);
        Node relayerNode = new Node(MEMBER_SEED);
        ClaimLeafV1 older = reserve(3, 53, 900, NOW - 3 * DAY, 4_000_000, relayerNode);
        reserve(3, 54, 900, NOW - 2 * DAY, 4_000_000, relayerNode);
        anchor();
        ExitRelayTask relayer = ((AssetSettlementExecutorFactory.CosignOnly) product(relayerNode, false, true))
                .relayer().orElseThrow();

        String outcome = relayer.runOnce();

        assertThat(outcome).as(outcome).startsWith("relayed 1 on shard 3");
        assertThat(submitted).hasSize(1);
        assertPasses(submitted.getFirst());
        assertThat(paidBy(submitted.getFirst())).containsExactly(older.claimId());
    }

    // ------------------------------------------------------------------ robustness

    /** A second vault, configured first, whose vault and shard addresses the L1 view cannot read. */
    static final byte[] BROKEN_VAULT_HASH = filled(28, 0x99);
    static final byte[] BROKEN_SHARD_HASH = ShardBackend.JULC.derive(filled(28, 0xb3), BROKEN_VAULT_HASH, 0)
            .scriptHash();

    /** The settings with the broken ADA vault as vault 0 and this test's vault as vault 1. */
    Map<String, String> withBrokenVaultFirst(Map<String, String> config) {
        Map<String, String> out = new LinkedHashMap<>();
        config.forEach((key, value) -> out.put(key.startsWith("vaults.0.") ? "vaults.1." + key.substring(9) : key,
                value));
        out.put("vaults.0.asset", "lovelace");
        out.put("vaults.0.script-hash", HexFormat.of().formatHex(BROKEN_VAULT_HASH));
        out.put("vaults.0.address", enterprise(BROKEN_VAULT_HASH));
        out.put("vaults.0.shard-policy-id", "b3".repeat(28));
        out.put("vaults.0.shard-script-hash", HexFormat.of().formatHex(BROKEN_SHARD_HASH));
        out.put("vaults.0.shard-address", enterprise(BROKEN_SHARD_HASH));
        out.put("vaults.0.script-ref", "65".repeat(32) + "#0");
        out.put("vaults.0.shard-script-ref", "65".repeat(32) + "#1");
        return out;
    }

    /**
     * Fable P3-2 and P3-4: a vault whose L1 reads fail is reported and does not stop the next vault's Accept or Exit,
     * and the relayer walks the claim index once per pass for every vault.
     */
    @Test
    void oneVaultsFailureDoesNotStopTheOthersAndTheClaimIndexIsWalkedOncePerPass() throws Exception {
        seed();
        brokenAddresses.add(enterprise(BROKEN_VAULT_HASH));
        brokenAddresses.add(enterprise(BROKEN_SHARD_HASH));
        Node relayerNode = new Node(MEMBER_SEED);
        // A reserved claim of the broken vault, so its relaying reaches its unreadable shard.
        Hash32 brokenVault = GatewayIds.vaultId(DOMAIN, AssetId.ADA, 0);
        ClaimLeafV1 template = reserve(2, 81, 900, NOW - 2 * DAY);
        ClaimLeafV1 broken = new ClaimLeafV1(template.claimId(), brokenVault, ClaimKind.USER_WITHDRAWAL,
                AssetValue.single(AssetId.ADA, BigInteger.valueOf(3_000_000)), BigInteger.ZERO, template.recipient(),
                900, NOW - 2 * DAY);
        relayerNode.ledger.leaves.put(broken.claimId(), broken);
        relayerNode.ledger.status.put(broken.claimId(),
                SettlementRecords.ClaimStatusV1.reserved(broken.claimId(), broken.reservedHeight()));
        ClaimLeafV1 claim = reserve(3, 82, 900, NOW - 2 * DAY, relayerNode);
        deposit(0, 900, DepositAdmission.Reason.NONE, relayerNode);
        anchor();
        List<AppEffectExecutor> products;
        long at = now;
        now = at - startedAgoMs;
        try {
            products = AssetSettlementExecutorFactory.create(withBrokenVaultFirst(config(false, true)), relayerNode,
                    () -> now);
        } finally {
            now = at;
        }
        var product = (AssetSettlementExecutorFactory.CosignOnly) products.getFirst();

        String accepted = product.acceptor().orElseThrow().runOnce();
        assertThat(accepted).as(accepted).startsWith("vault " + brokenVault + " failed:")
                .contains("; accepted (proved) 1 deposits");

        // The Accept spent the relayer's fee output: fund the Exit.
        put(utxo("6a".repeat(32), 1, keyAddress(RELAYER_FEE_SEED), 80_000_000, List.of(), null, null));
        relayerNode.ledger.indexWalks.set(0);
        String relayed = product.relayer().orElseThrow().runOnce();
        assertThat(relayed).as(relayed).startsWith("vault " + brokenVault + " failed:")
                .contains("; relayed 1 on shard 3");
        assertThat(paidBy(submitted.getLast())).containsExactly(claim.claimId());
        assertThat(relayerNode.ledger.indexWalks).hasValue(1);
    }

    /** Fable P3-3: an Error thrown by a pass is logged; the timer keeps running the later passes. */
    @Test
    void aPassThatThrowsAnErrorDoesNotStopTheTimer() throws Exception {
        java.util.concurrent.CountDownLatch passes = new java.util.concurrent.CountDownLatch(3);
        try (SettlementTasks tasks = new SettlementTasks(CHAIN)) {
            tasks.every(5, "failing pass", () -> {
                passes.countDown();
                throw new AssertionError("a pass that fails with an Error");
            });
            assertThat(passes.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
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

    // ------------------------------------------------------------------ hold lifetime (r22: L1 evidence only)

    /** A transaction assembled at NOW has its TTL slot one validity window (600 slots of 1 s) later. */
    static final long TTL_MS = 600_000;
    /** The startup grace without a pending-TTL file: the longest permitted window plus the slack. */
    static final long GRACE_MS = AssetSettlementSettings.MAX_VALIDITY_WINDOW_MS + OwnerAssembly.SLACK_MS;

    /**
     * Codex P1-1: a Settle whose effect expires is never probed again; its hold ends once the L1 view is past its TTL
     * slot, freeing its fee input and shard, and its journal entry goes with it.
     */
    @Test
    void anExpiredSettleEffectFreesItsWalletAndShardOnceL1PassesItsTtl() throws Exception {
        seed(); // one plain fee-wallet output
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        ClaimLeafV1 first = reserve(3, 41, 900, NOW - HOUR, owner, member);
        ClaimLeafV1 second = reserve(3, 42, 900, NOW - HOUR, owner, member);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner, member);
        anchor();
        AssetSettlementExecutor executor = (AssetSettlementExecutor) product(owner, true, false);
        product(member, false, false);
        pump(owner, member);
        PendingEffect later = effect(new SettlementBatchV1(VAULT, 3, List.of(second.claimId())), 1);
        assertThat(executor.execute(attempt(1, new byte[0]),
                effect(new SettlementBatchV1(VAULT, 3, List.of(first.claimId())), 0)))
                .as(executor.lastOutcome()).isInstanceOf(EffectExecution.Submitted.class);

        // Its effect expires: the runtime never probes it again. Until L1 is past its TTL it still holds both.
        now = NOW + TTL_MS;
        assertThat(executor.acceptor().orElseThrow().runOnce()).isEqualTo("no fee wallet funds");
        assertThat(executor.execute(attempt(1, new byte[0]), later)).isInstanceOf(EffectExecution.Retry.class);
        assertThat(executor.lastOutcome()).isEqualTo("awaiting the pending payout on this shard");
        assertThat(executor.journalSize()).isEqualTo(1);

        // Past it, the fee input and the shard are free again, and the expired effect's journal entry is gone.
        now = NOW + TTL_MS + 1_000;
        assertThat(executor.execute(attempt(1, new byte[0]), later)).as(executor.lastOutcome())
                .isInstanceOf(EffectExecution.Submitted.class);
        assertThat(executor.journalSize()).isEqualTo(1);
        assertThat(submitted).hasSize(2);
        assertThat(inputs(submitted.get(1))).contains("60".repeat(32) + "#0", "62".repeat(32) + "#3");
    }

    /**
     * Codex (rollback revival): an Accept submitted against anchor A is not given up when the anchor moves to B,
     * because A can be restored by a rollback and the Accept land after all. No second transaction spends its fee input
     * until L1 is past its TTL.
     */
    @Test
    void anAcceptWhoseAnchorMovesKeepsItsFeeInputUntilL1PassesItsTtl() throws Exception {
        seed(); // one plain fee-wallet output
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        ClaimLeafV1 claim = reserve(3, 31, 900, NOW - HOUR, owner, member);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner, member);
        anchor();
        AssetSettlementExecutor executor = (AssetSettlementExecutor) product(owner, true, false);
        product(member, false, false);
        pump(owner, member);
        assertThat(executor.acceptor().orElseThrow().runOnce()).startsWith("accepted (co-signed) 1 deposits");
        PendingEffect settle = effect(new SettlementBatchV1(VAULT, 3, List.of(claim.claimId())), 0);

        now = NOW + 60_000;
        anchorHeight = ANCHORED + 1;
        anchor(); // A is spent by the advance to B
        assertThat(executor.execute(attempt(1, new byte[0]), settle)).isInstanceOf(EffectExecution.Retry.class);
        assertThat(executor.lastOutcome()).isEqualTo("no fee wallet funds");
        anchorHeight = ANCHORED;
        anchor(); // a rollback restores A
        assertThat(executor.execute(attempt(1, new byte[0]), settle)).isInstanceOf(EffectExecution.Retry.class);
        assertThat(executor.lastOutcome()).isEqualTo("no fee wallet funds");
        assertThat(submitted).hasSize(1);

        now = NOW + TTL_MS + 1_000;
        assertThat(executor.execute(attempt(1, new byte[0]), settle)).as(executor.lastOutcome())
                .isInstanceOf(EffectExecution.Submitted.class);
        assertThat(inputs(submitted.getLast())).contains("60".repeat(32) + "#0");
    }

    /**
     * Codex (stale view): the wall clock passes the Accept's TTL while the node's L1 view is stuck before it; the
     * Accept may have landed unseen, so its consumed fee input is not reused. Once the view catches up and shows it
     * landed, the next transaction spends its change instead.
     */
    @Test
    void aStaleViewKeepsTheConsumedFeeInputHeld() throws Exception {
        seed(); // one plain fee-wallet output
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        ClaimLeafV1 claim = reserve(3, 32, 900, NOW - HOUR, owner, member);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner, member);
        anchor();
        AssetSettlementExecutor executor = (AssetSettlementExecutor) product(owner, true, false);
        product(member, false, false);
        pump(owner, member);
        assertThat(executor.acceptor().orElseThrow().runOnce()).startsWith("accepted (co-signed) 1 deposits");
        byte[] accept = submitted.getFirst();
        PendingEffect settle = effect(new SettlementBatchV1(VAULT, 3, List.of(claim.claimId())), 0);

        frozenTipSlot = NOW / 1_000;
        now = NOW + 20 * 60_000;
        assertThat(executor.execute(attempt(1, new byte[0]), settle)).isInstanceOf(EffectExecution.Retry.class);
        assertThat(executor.lastOutcome()).isEqualTo("no fee wallet funds");

        // The view catches up: the Accept landed.
        land(accept);
        frozenTipSlot = null;
        assertThat(executor.execute(attempt(1, new byte[0]), settle)).as(executor.lastOutcome())
                .isInstanceOf(EffectExecution.Submitted.class);
        assertThat(inputs(submitted.getLast())).doesNotContain("60".repeat(32) + "#0")
                .contains(TransactionUtil.getTxHash(accept) + "#1");
        assertNoDoubleSpend();
    }

    /**
     * Codex: a Settle whose confirmation times out closes its effect, but the transaction may still land until its
     * TTL, so its fee input stays held until L1 is past it.
     */
    @Test
    void aSettleWhoseConfirmationTimesOutKeepsItsFeeInputUntilL1PassesItsTtl() throws Exception {
        seed(); // one plain fee-wallet output
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        ClaimLeafV1 claim = reserve(3, 33, 900, NOW - HOUR, owner, member);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner, member);
        anchor();
        AssetSettlementExecutor executor = (AssetSettlementExecutor) product(owner, true, false,
                Map.of("inclusion-timeout-ms", "60000", "stability-window-ms", "30000"));
        product(member, false, false);
        pump(owner, member);
        PendingEffect settle = effect(new SettlementBatchV1(VAULT, 3, List.of(claim.claimId())), 0);
        byte[] ref = ((EffectExecution.Submitted) executor.execute(attempt(1, new byte[0]), settle)).externalRef();

        now = NOW + 61_000;
        EffectExecution timedOut = executor.execute(attempt(2, ref), settle);
        assertThat(timedOut).isInstanceOf(EffectExecution.Failed.class);
        assertThat(((EffectExecution.Failed) timedOut).retryable()).isFalse();
        assertThat(executor.journalSize()).isZero();
        assertThat(executor.acceptor().orElseThrow().runOnce()).isEqualTo("no fee wallet funds");

        now = NOW + TTL_MS + 1_000;
        assertThat(executor.acceptor().orElseThrow().runOnce()).startsWith("accepted (co-signed) 1 deposits");
    }

    /**
     * Codex P2-5: holds are in memory. Without a pending-TTL file a node that (re)starts assembles nothing for the
     * longest permitted validity window plus the slack, whatever its own window setting (Codex: a restart with a
     * shorter validity-window-slots); a Settle submitted before it is still probed from its persisted reference.
     */
    @Test
    void aStartedNodeAssemblesNothingUntilItsGraceHasPassed() throws Exception {
        seed();
        for (int i = 1; i < 12; i++) {
            put(utxo("60".repeat(32), i, keyAddress(FEE_SEED), 80_000_000, List.of(), null, null));
        }
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        ClaimLeafV1 claim = reserve(3, 61, 900, NOW - 2 * DAY, owner, member);
        ClaimLeafV1 other = reserve(4, 62, 900, NOW - HOUR, owner, member);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner, member);
        anchor();
        product(member, false, false);
        pump(owner, member);
        AssetSettlementExecutor before = (AssetSettlementExecutor) product(owner, true, true);
        PendingEffect pending = effect(new SettlementBatchV1(VAULT, 4, List.of(other.claimId())), 1);
        byte[] ref = ((EffectExecution.Submitted) before.execute(attempt(1, new byte[0]), pending)).externalRef();

        // The node restarts with a shorter window: its holds are gone, and the old window still bounds what is pending.
        startedAgoMs = 0;
        AssetSettlementExecutor restarted = (AssetSettlementExecutor) product(owner, true, true,
                Map.of("validity-window-slots", "10"));
        PendingEffect fresh = effect(new SettlementBatchV1(VAULT, 3, List.of(claim.claimId())), 0);
        assertThat(restarted.acceptor().orElseThrow().runOnce()).isEqualTo(OwnerAssembly.WARMING_UP);
        assertThat(restarted.relayer().orElseThrow().runOnce()).isEqualTo(OwnerAssembly.WARMING_UP);
        assertThat(restarted.execute(attempt(1, new byte[0]), fresh)).isInstanceOf(EffectExecution.Retry.class);
        assertThat(restarted.lastOutcome()).isEqualTo(OwnerAssembly.WARMING_UP);
        assertThat(restarted.execute(attempt(2, ref), pending)).as("the pending Settle is still probed")
                .isInstanceOf(EffectExecution.Submitted.class);
        assertThat(submitted).hasSize(1);

        now = NOW + GRACE_MS - 1_000;
        assertThat(restarted.acceptor().orElseThrow().runOnce()).isEqualTo(OwnerAssembly.WARMING_UP);
        now = NOW + GRACE_MS + 1_000;
        assertThat(restarted.acceptor().orElseThrow().runOnce()).startsWith("accepted (co-signed) 1 deposits");
        assertThat(restarted.relayer().orElseThrow().runOnce()).startsWith("relayed 1 on shard 3");
    }

    /**
     * Codex P2-5 and Fable P3-1: with a pending-TTL file, a restart waits exactly until the L1 view is past the latest
     * TTL submitted before it, even with a shorter window setting after the restart, and not at all when that TTL is
     * already past.
     */
    @Test
    void aRestartWaitsUntilL1PassesThePersistedTtl() throws Exception {
        seed();
        for (int i = 1; i < 12; i++) {
            put(utxo("60".repeat(32), i, keyAddress(FEE_SEED), 80_000_000, List.of(), null, null));
        }
        Path ttlFile = temp.resolve("state").resolve("asset-settlement.pending-ttl");
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        ClaimLeafV1 claim = reserve(4, 63, 900, NOW - HOUR, owner, member);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner, member);
        deposit(1, 900, DepositAdmission.Reason.NONE, owner, member);
        anchor();
        product(member, false, false);
        pump(owner, member);
        Map<String, String> withFile = Map.of("pending-ttl-file", ttlFile.toString());
        AssetSettlementExecutor before = (AssetSettlementExecutor) product(owner, true, false, withFile);
        assertThat(before.execute(attempt(1, new byte[0]),
                effect(new SettlementBatchV1(VAULT, 4, List.of(claim.claimId())), 0)))
                .isInstanceOf(EffectExecution.Submitted.class);
        assertThat(Files.readString(ttlFileOf(ttlFile, CHAIN, FEE_SEED)).trim())
                .isEqualTo(Long.toString(NOW / 1_000 + 600));

        startedAgoMs = 0;
        now = NOW + 5 * 60_000;
        Map<String, String> shorter = new LinkedHashMap<>(withFile);
        shorter.put("validity-window-slots", "10");
        DepositAcceptor restarted = ((AssetSettlementExecutor) product(owner, true, false, shorter)).acceptor()
                .orElseThrow();
        assertThat(restarted.runOnce()).isEqualTo(OwnerAssembly.WARMING_UP);
        now = NOW + TTL_MS;
        assertThat(restarted.runOnce()).isEqualTo(OwnerAssembly.WARMING_UP);
        now = NOW + TTL_MS + 1_000; // before the no-file grace would end
        assertThat(restarted.runOnce()).startsWith("accepted (co-signed)");

        // Restarted again an hour later: the persisted TTL is long past, so it assembles at once.
        now = NOW + HOUR;
        DepositAcceptor later = ((AssetSettlementExecutor) product(owner, true, false, withFile)).acceptor()
                .orElseThrow();
        assertThat(later.runOnce()).isNotEqualTo(OwnerAssembly.WARMING_UP);
    }

    /** Fable P3-1: a relaying non-owner's validity window, and so its holds, are bounded like the owner's. */
    @Test
    void aRelayersValidityWindowIsBounded() throws Exception {
        Node relayerNode = new Node(MEMBER_SEED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> product(relayerNode, false, true,
                Map.of("validity-window-slots", "601"))).hasMessageContaining("validity-window-slots");
        assertThat(product(new Node(MEMBER_SEED), false, false, Map.of("validity-window-slots", "601"))
                .effectTypes()).as("a member that builds nothing is not bounded").isEmpty();
    }

    /**
     * Codex and Fable P2-1: a landed payout's inserts stay in the mirror until committed state accounts for them, even
     * once every output of that payout is spent (its first payout by its recipient), so the shard is not stopped and
     * a third claim is still relayed after two consecutive Exits on it.
     */
    @Test
    void aLandedPayoutsInsertsStayUntilCommittedSoTheShardKeepsRelaying() throws Exception {
        seed();
        custody(50_000_000); // one custody output: each Exit continues the previous one's
        Node relayerNode = new Node(MEMBER_SEED);
        List<ClaimLeafV1> claims = new ArrayList<>();
        for (int i = 0; i < 3; i++) claims.add(reserve(3, 91 + i, 900, NOW - 3 * DAY + i * HOUR, relayerNode));
        anchor();
        ExitRelayTask relayer = ((AssetSettlementExecutorFactory.CosignOnly) product(relayerNode, false, true,
                Map.of("max-exit-batch", "1"))).relayer().orElseThrow();

        for (int i = 0; i < 2; i++) {
            String outcome = relayer.runOnce();
            assertThat(outcome).as(outcome).startsWith("relayed 1 on shard 3");
            assertPasses(submitted.getLast());
            assertThat(paidBy(submitted.getLast())).containsExactly(claims.get(i).claimId());
            land(submitted.getLast());
        }
        // The first claim's recipient spends its payout: every output of the first Exit is now spent.
        spend(TransactionUtil.getTxHash(submitted.getFirst()) + "#0");

        String third = relayer.runOnce();
        assertThat(third).as(third).startsWith("relayed 1 on shard 3");
        assertPasses(submitted.getLast());
        assertThat(paidBy(submitted.getLast())).containsExactly(claims.get(2).claimId());
    }

    /**
     * Codex P1 (r22): payout custody pages through the vault address. With 5,000 outputs that are not custody ahead of
     * the accepted custody, a Settle and a relayed Exit still find it.
     */
    @Test
    void payoutCustodyPastTheFirstWindowOfVaultOutputsIsSelected() throws Exception {
        seed();
        for (int i = 1; i < 12; i++) {
            put(utxo("60".repeat(32), i, keyAddress(FEE_SEED), 80_000_000, List.of(), null, null));
        }
        for (int i = 0; i < NodeL1View.PAGE * NodeL1View.MAX_PAGES; i++) {
            put(utxo("7e".repeat(32), i, enterprise(VAULT_HASH), 2_000_000, List.of(), null, null));
        }
        custody(50_000_000, 50_000_000); // now behind the 5,000 others
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        ClaimLeafV1 settled = reserve(3, 101, 900, NOW - HOUR, owner, member);
        ClaimLeafV1 relayed = reserve(4, 102, 900, NOW - 2 * DAY, owner, member);
        anchor();
        AssetSettlementExecutor executor = (AssetSettlementExecutor) product(owner, true, true);
        product(member, false, false);
        pump(owner, member);

        assertThat(executor.execute(attempt(1, new byte[0]),
                effect(new SettlementBatchV1(VAULT, 3, List.of(settled.claimId())), 0)))
                .as(executor.lastOutcome()).isInstanceOf(EffectExecution.Submitted.class);
        String outcome = executor.relayer().orElseThrow().runOnce();
        assertThat(outcome).as(outcome).startsWith("relayed 1 on shard 4");
        assertNoDoubleSpend();
        synchronized (submitted) {
            for (byte[] cbor : submitted) {
                assertPasses(cbor);
                assertThat(inputs(cbor)).anyMatch(input -> input.startsWith("61".repeat(32)));
            }
        }
        assertThat(paidBy(submitted.getLast())).containsExactly(relayed.claimId());
    }

    /**
     * Codex P2 (r22): fee funding that does not cover a batch is split on, like short custody. With one 2 ADA fee
     * output, the older claim that needs a min-UTxO supplement does not fit with the other one, nor alone; the payable
     * one is relayed.
     */
    @Test
    void feeFundingShortOfTheBatchRelaysThePayableClaim() throws Exception {
        seed();
        l1.remove("6a".repeat(32) + "#0");
        put(utxo("6a".repeat(32), 0, keyAddress(RELAYER_FEE_SEED), 2_000_000, List.of(), null, null));
        Node relayerNode = new Node(MEMBER_SEED);
        reserve(3, 111, 900, NOW - 3 * DAY, 0, relayerNode); // its payout needs a supplement
        ClaimLeafV1 payable = reserve(3, 112, 900, NOW - 2 * DAY, relayerNode);
        anchor();
        ExitRelayTask relayer = ((AssetSettlementExecutorFactory.CosignOnly) product(relayerNode, false, true))
                .relayer().orElseThrow();

        String outcome = relayer.runOnce();

        assertThat(outcome).as(outcome).startsWith("relayed 1 on shard 3");
        assertPasses(submitted.getFirst());
        assertThat(paidBy(submitted.getFirst())).containsExactly(payable.claimId());
    }

    /**
     * Fable P3-2 (r22), reviewer3 M4S3a-F2: a relaying non-owner proves an Accept only of deposits that could have been
     * CREDITED at least accept.relayer-delay-ms ago (three Accept intervals by default). A deposit is credited only a
     * stability window (600,000 ms by default) after its L1 inclusion, so the delay counts from then, and the relayer
     * still waits behind a healthy owner that accepts as soon as the credit is committed.
     */
    @Test
    void aRelayerProvesOnlyDepositsTheOwnerHasLeft() throws Exception {
        seed();
        Node relayerNode = new Node(MEMBER_SEED);
        long included = NOW - 600_000; // on L1 one stability window ago, so credited about now
        deposit(0, 900, included / 1_000, DepositAdmission.Reason.NONE, relayerNode);
        anchor();
        DepositAcceptor acceptor = ((AssetSettlementExecutorFactory.CosignOnly) product(relayerNode, false, true))
                .acceptor().orElseThrow();

        // Counted from inclusion, the default 3-minute delay would have passed 7 minutes ago.
        assertThat(acceptor.runOnce()).isEqualTo("nothing to accept");
        now = NOW + 3 * 60_000 - 1_000;
        assertThat(acceptor.runOnce()).isEqualTo("nothing to accept");
        now = NOW + 3 * 60_000;
        String outcome = acceptor.runOnce();
        assertThat(outcome).as(outcome).startsWith("accepted (proved) 1 deposits");
    }

    /**
     * reviewer3 M4S3a-F1: a store with no applied point yet reports slot -1, and a disabled store answers nothing; the
     * view then knows no tip, so an empty point read is UNKNOWN, not ABSENT, and a TTL file holding the largest slot
     * cannot end the startup grace through an overflowing comparison.
     */
    @Test
    void aViewWithoutAnAppliedPointOrWithADisabledStoreKnowsNoTip() throws Exception {
        NodeL1View l1 = new NodeL1View(this::view);
        ResolvedOutput.Outpoint absent = new ResolvedOutput.Outpoint(Hash32.of(filled(32, 0x3a)), 0);
        assertThat(l1.known(absent)).isEqualTo(NodeL1View.Read.ABSENT);

        frozenTipSlot = -1L;
        assertThat(l1.appliedTipSlot()).isEmpty();
        assertThat(l1.known(absent)).isEqualTo(NodeL1View.Read.UNKNOWN);
        Path file = temp.resolve("overflow.ttl");
        Files.writeString(file, Long.toString(Long.MAX_VALUE));
        OwnerAssembly assembly = new OwnerAssembly(NOW, new AssetSettlementSettings.SlotClock(0, 0, 1_000),
                Optional.of(file));
        assertThat(assembly.warmingUp(NOW + DAY, l1)).isTrue();

        frozenTipSlot = null;
        storeDisabled = true;
        assertThat(l1.appliedTipSlot()).isEmpty();
        assertThat(l1.known(absent)).isEqualTo(NodeL1View.Read.UNKNOWN);
        storeDisabled = false;
        assertThat(l1.known(absent)).isEqualTo(NodeL1View.Read.ABSENT);
        // A readable tip and the largest stored slot: invalid, so the fallback grace applies instead.
        now = NOW + DAY;
        assertThat(new OwnerAssembly(NOW, new AssetSettlementSettings.SlotClock(0, 0, 1_000), Optional.of(file))
                .warmingUp(NOW + DAY, l1)).isFalse();
    }

    /** reviewer3 note: an impossibly high stored TTL is overwritten by the first valid submission. */
    @Test
    void anImpossiblyHighStoredTtlIsOverwrittenByTheFirstSubmission() throws Exception {
        long ttl = NOW / 1_000 + 600;
        for (long stored : new long[] {ttl + 5_000, Long.MAX_VALUE, -7}) {
            Path file = temp.resolve("high-" + (stored & 0xffff) + ".ttl");
            Files.writeString(file, Long.toString(stored));
            new OwnerAssembly(NOW, new AssetSettlementSettings.SlotClock(0, 0, 1_000), Optional.of(file))
                    .beforeSubmit(ttl);
            assertThat(Files.readString(file).trim()).as("stored %d", stored).isEqualTo(Long.toString(ttl));
        }
        // A plausible higher stored TTL (another writer's, within one window) is kept.
        Path file = temp.resolve("plausible.ttl");
        Files.writeString(file, Long.toString(ttl + 400));
        new OwnerAssembly(NOW, new AssetSettlementSettings.SlotClock(0, 0, 1_000), Optional.of(file))
                .beforeSubmit(ttl);
        assertThat(Files.readString(file).trim()).isEqualTo(Long.toString(ttl + 400));
    }

    /** reviewer3 note: the Accept builder halves the batch on short funding as on a per-transaction limit. */
    @Test
    void theAcceptBatchIsHalvedOnShortFundingAndOnALimit() {
        List<ResolvedOutput> deposits = Collections.nCopies(8, null);
        List<Integer> tried = new ArrayList<>();
        DepositAcceptor.Attempt funded = DepositAcceptor.build(deposits, 8, batch -> {
            tried.add(batch.size());
            if (batch.size() > 2) throw new PayoutTxBuilder.FundingShort("funding does not cover the fee");
            throw new PayoutTxBuilder.BuildException("stopped at " + batch.size());
        });
        assertThat(tried).containsExactly(8, 4, 2);
        assertThat(funded.failure()).isEqualTo("stopped at 2");

        tried.clear();
        DepositAcceptor.Attempt limited = DepositAcceptor.build(deposits, 8, batch -> {
            tried.add(batch.size());
            throw new PayoutTxBuilder.LimitExceeded("too large");
        });
        assertThat(tried).containsExactly(8, 4, 2, 1);
        assertThat(limited.failure()).isEqualTo("too large");
    }

    /** Fable P3-3 (r22): a StackOverflowError of one pass is logged like any failure; the timer keeps running. */
    @Test
    void aPassThatOverflowsItsStackDoesNotStopTheTimer() throws Exception {
        java.util.concurrent.CountDownLatch passes = new java.util.concurrent.CountDownLatch(3);
        try (SettlementTasks tasks = new SettlementTasks(CHAIN)) {
            tasks.every(5, "overflowing pass", () -> {
                passes.countDown();
                throw new StackOverflowError("a pass that overflows its stack");
            });
            assertThat(passes.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    // ------------------------------------------------------------------ r22 confirmation pass

    /**
     * Codex #2: a landed Accept keeps its input hold until L1 is past its TTL, so when its own inclusion rolls back
     * and its fee input is unspent again, nothing else spends that input while the Accept can still land.
     */
    @Test
    void aRolledBackInclusionCannotReleaseItsInputs() throws Exception {
        seed(); // one plain fee-wallet output
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        ClaimLeafV1 claim = reserve(3, 121, 900, NOW - HOUR, owner, member);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner, member);
        anchor();
        AssetSettlementExecutor executor = (AssetSettlementExecutor) product(owner, true, false);
        product(member, false, false);
        pump(owner, member);
        DepositAcceptor acceptor = executor.acceptor().orElseThrow();
        assertThat(acceptor.runOnce()).startsWith("accepted (co-signed) 1 deposits");
        byte[] accept = submitted.getFirst();
        land(accept);
        assertThat(acceptor.runOnce()).isEqualTo("nothing to accept"); // a pass while it is landed
        unland(accept); // a rollback: the Accept is pending again, its fee input unspent
        PendingEffect settle = effect(new SettlementBatchV1(VAULT, 3, List.of(claim.claimId())), 0);
        assertThat(executor.execute(attempt(1, new byte[0]), settle)).isInstanceOf(EffectExecution.Retry.class);
        assertThat(executor.lastOutcome()).isEqualTo("no fee wallet funds");

        now = NOW + TTL_MS + 1_000;
        assertThat(executor.execute(attempt(1, new byte[0]), settle)).as(executor.lastOutcome())
                .isInstanceOf(EffectExecution.Submitted.class);
    }

    /** Codex #3: a read that cannot answer never retires a pending insert, even past the payout's TTL. */
    @Test
    void anUnanswerableReadKeepsALandedPayoutsInserts() throws Exception {
        seed();
        custody(50_000_000);
        Node relayerNode = new Node(MEMBER_SEED);
        reserve(3, 131, 900, NOW - 3 * DAY, relayerNode);
        ClaimLeafV1 next = reserve(3, 132, 900, NOW - 2 * DAY, relayerNode);
        anchor();
        ExitRelayTask relayer = ((AssetSettlementExecutorFactory.CosignOnly) product(relayerNode, false, true,
                Map.of("max-exit-batch", "1"))).relayer().orElseThrow();
        assertThat(relayer.runOnce()).startsWith("relayed 1 on shard 3");
        land(submitted.getLast());

        pointReadsFail = true;
        now = NOW + TTL_MS + 1_000; // past the first Exit's TTL; its claim is not yet PAID in committed state
        String outcome = relayer.runOnce();
        assertThat(outcome).as(outcome).startsWith("relayed 1 on shard 3");
        assertPasses(submitted.getLast());
        assertThat(paidBy(submitted.getLast())).containsExactly(next.claimId());
    }

    /**
     * Codex #1: custody is collected from every page, not up to the first prefix that sums to enough. Behind 5,000
     * outputs of 2 ADA, one 30 ADA output pays an 18 ADA claim and the 2 ADA floor within max-vault-inputs.
     */
    @Test
    void custodySelectionSeesPastASufficientPrefix() throws Exception {
        seed();
        custody();
        for (int i = 0; i < NodeL1View.PAGE * NodeL1View.MAX_PAGES; i++) {
            put(utxo("61".repeat(32), i, enterprise(VAULT_HASH), 2_000_000, asset("f0".repeat(28), "5553444d", 1_000),
                    HexFormat.of().parseHex("d87a8180"), null));
        }
        put(utxo("6e".repeat(32), 0, enterprise(VAULT_HASH), 30_000_000, asset("f0".repeat(28), "5553444d", 100_000),
                HexFormat.of().parseHex("d87a8180"), null));
        Node relayerNode = new Node(MEMBER_SEED);
        ClaimLeafV1 claim = reserve(3, 141, 900, NOW - 2 * DAY, 18_000_000, relayerNode);
        anchor();
        ExitRelayTask relayer = ((AssetSettlementExecutorFactory.CosignOnly) product(relayerNode, false, true))
                .relayer().orElseThrow();

        String outcome = relayer.runOnce();

        assertThat(outcome).as(outcome).startsWith("relayed 1 on shard 3");
        assertPasses(submitted.getFirst());
        assertThat(inputs(submitted.getFirst())).contains("6e".repeat(32) + "#0");
        assertThat(paidBy(submitted.getFirst())).containsExactly(claim.claimId());
    }

    /** Codex #7: without a TTL file, the grace also waits for the applied tip to pass the start slot plus 10 min. */
    @Test
    void theFallbackGraceWaitsForABehindView() throws Exception {
        seed();
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner, member);
        anchor();
        product(member, false, false);
        pump(owner, member);
        startedAgoMs = 0;
        DepositAcceptor acceptor = ((AssetSettlementExecutor) product(owner, true, false)).acceptor().orElseThrow();

        now = NOW + GRACE_MS + 60_000;
        frozenTipSlot = NOW / 1_000 + 600; // the view is behind: not yet past the start slot plus the window
        assertThat(acceptor.runOnce()).isEqualTo(OwnerAssembly.WARMING_UP);
        frozenTipSlot = NOW / 1_000 + 601;
        assertThat(acceptor.runOnce()).startsWith("accepted (co-signed) 1 deposits");
    }

    /**
     * Codex (TTL file): the file is per chain and fee wallet, and a write keeps the larger of the file's slot and the
     * new one, so two chains on one node never lower each other's slot.
     */
    @Test
    void twoChainsNeverLowerEachOthersTtl() throws Exception {
        seed();
        for (int i = 1; i < 12; i++) {
            put(utxo("60".repeat(32), i, keyAddress(FEE_SEED), 80_000_000, List.of(), null, null));
        }
        Path base = temp.resolve("data").resolve("asset-settlement.pending-ttl");
        Map<String, String> withFile = Map.of("pending-ttl-file", base.toString(), "accept.max-batch", "1",
                "accept.max-proved-batch", "1");
        Node member = new Node(MEMBER_SEED);
        Node first = new Node(OWNER_SEED);
        Node second = new Node(OWNER_SEED);
        second.chain = "payments-2";
        for (int i = 0; i < 3; i++) deposit(i, 900, DepositAdmission.Reason.NONE, first, second, member);
        anchor();
        product(member, false, false);
        pump(first, member);
        DepositAcceptor a = ((AssetSettlementExecutor) product(first, true, false, withFile)).acceptor().orElseThrow();
        DepositAcceptor b = ((AssetSettlementExecutor) product(second, true, false, withFile)).acceptor()
                .orElseThrow();
        Path fileA = ttlFileOf(base, CHAIN, FEE_SEED);
        Path fileB = ttlFileOf(base, "payments-2", FEE_SEED);

        now = NOW + 5 * 60_000;
        assertThat(a.runOnce()).startsWith("accepted");
        now = NOW;
        assertThat(b.runOnce()).startsWith("accepted"); // a lower TTL, on the other chain
        assertThat(Files.readString(fileA).trim()).isEqualTo(Long.toString(NOW / 1_000 + 300 + 600));
        assertThat(Files.readString(fileB).trim()).isEqualTo(Long.toString(NOW / 1_000 + 600));

        // Another writer raised chain 2's file meanwhile: a lower TTL does not lower it.
        Files.writeString(fileB, Long.toString(NOW / 1_000 + 1_000));
        assertThat(b.runOnce()).startsWith("accepted");
        assertThat(Files.readString(fileB).trim()).isEqualTo(Long.toString(NOW / 1_000 + 1_000));
    }

    /** Codex (TTL file): a negative stored slot, or one beyond any TTL, is invalid and the fallback grace applies. */
    @Test
    void anInvalidStoredTtlUsesTheFallbackGrace() throws Exception {
        seed();
        Node owner = new Node(OWNER_SEED);
        Node member = new Node(MEMBER_SEED);
        deposit(0, 900, DepositAdmission.Reason.NONE, owner, member);
        anchor();
        product(member, false, false);
        pump(owner, member);
        Path base = temp.resolve("bad").resolve("ttl");
        Files.createDirectories(base.getParent());
        startedAgoMs = 0;
        for (long stored : new long[] {-1, Long.MAX_VALUE}) {
            now = NOW;
            Files.writeString(ttlFileOf(base, CHAIN, FEE_SEED), Long.toString(stored));
            DepositAcceptor acceptor = ((AssetSettlementExecutor) product(owner, true, false,
                    Map.of("pending-ttl-file", base.toString()))).acceptor().orElseThrow();
            now = NOW + 5 * 60_000;
            assertThat(acceptor.runOnce()).as("stored " + stored).isEqualTo(OwnerAssembly.WARMING_UP);
            now = NOW + GRACE_MS + 1_000;
            assertThat(acceptor.runOnce()).as("stored " + stored).startsWith("accepted (co-signed) 1 deposits");
            submitted.clear();
            Files.deleteIfExists(ttlFileOf(base, CHAIN, FEE_SEED));
        }
    }

    static boolean paysVault(com.bloxbean.cardano.client.transaction.spec.TransactionOutput out) {
        byte[] raw = new com.bloxbean.cardano.client.address.Address(out.getAddress()).getBytes();
        return raw.length == 29 && Arrays.equals(Arrays.copyOfRange(raw, 1, 29), VAULT_HASH);
    }
}
