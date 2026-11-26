package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.eval.JulcTransactionEvaluator;
import org.julclang.clientlib.eval.SlotConfig;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
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
import org.yanoproject.x.assets.contracts.VaultOutputShape;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.MpfProofConverter;
import org.yanoproject.x.client.ProofVerifier;
import org.yanoproject.x.proofs.MpfNormalizedProof;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A complete L1 world for one vault: the committed julc templates applied with this vault's parameters (so the
 * scripts, their hashes and the addresses are the real ones), the anchor and exit-mode threads, accepted vault
 * custody, the 16 shard UTxOs, reference-script UTxOs, a fee wallet, and the julc phase-2 evaluator over all of them.
 * Built transactions are evaluated exactly as a node would before submission.
 */
final class PayoutWorld {
    static final long DAY = 86_400_000L;
    static final long HOUR = 3_600_000L;
    /** devnet-style slots: one second each from POSIX 0, so slot s is at s * 1000 ms. */
    static final SlotConfig SLOTS = new SlotConfig(0, 0, 1_000);
    static final long NOW_SLOT = 1_764_000_000L;
    static final long NOW_MS = SLOTS.slotToPosixMs(NOW_SLOT);
    static final long FLOOR = 2_000_000L;
    static final long EXIT_DELAY = DAY;
    static final long MAX_PAUSE = 7 * DAY;

    static final Hash32 GENESIS = Hash32.of(filled(32, 0x33));
    static final Hash32 DOMAIN = GatewayIds.domainId(0, 42, "payments-1", GENESIS, "asset-payments",
            "anchored-claim-vault-v1", 1);
    static final AssetId TOKEN = AssetId.of(filled(28, 0x7a), "USDM".getBytes(StandardCharsets.US_ASCII));
    static final byte[] ANCHOR_POLICY = filled(28, 0x51);
    static final byte[] ANCHOR_NAME = "payments-1".getBytes(StandardCharsets.US_ASCII);
    static final byte[] ANCHOR_HASH = filled(28, 0x52);
    static final byte[] SHARD_POLICY = filled(28, 0x71);
    static final byte[] EXIT_POLICY = filled(28, 0x72);
    static final byte[] EXIT_HASH = filled(28, 0x73);
    static final byte[] APPLICATION = "asset-payments".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PROFILE = ProofVerifier.MPF_BLAKE2B256_V1.getBytes(StandardCharsets.US_ASCII);
    static final byte[] FINGERPRINT = HexFormat.of().parseHex(
            ProofVerifier.profileMetadata(ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow().formatFingerprintHex());
    static final byte[] CLAIM_PREFIX = "ap/claim/".getBytes(StandardCharsets.US_ASCII);
    static final byte[] DEPOSIT_PREFIX = "ap/deposit/".getBytes(StandardCharsets.US_ASCII);
    static final byte[] STATUS_KEY = "ap/domain-status".getBytes(StandardCharsets.US_ASCII);
    static final byte[] FEE_KEY = filled(28, 0xee);
    static final byte[] FEE_ADDRESS = cat(new byte[] {0x60}, FEE_KEY);
    static final ProtocolParams PARAMS = protocolParams();

    /** Three Ed25519 member keys in ascending order, threshold 2. */
    static final List<byte[]> MEMBER_SEEDS;
    static final List<byte[]> MEMBERS;
    static final int THRESHOLD = 2;

    static {
        TreeMap<String, byte[]> byKey = new TreeMap<>();
        for (int seed = 1; seed <= 3; seed++) {
            byte[] secret = filled(32, 0x40 + seed);
            byKey.put(HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(secret)), secret);
        }
        MEMBER_SEEDS = List.copyOf(byKey.values());
        MEMBERS = byKey.keySet().stream().map(hex -> HexFormat.of().parseHex(hex)).toList();
    }

    private static final Map<String, Program> TEMPLATES = new HashMap<>();

    final AssetId asset;
    final Hash32 vaultId;
    final Program vaultProgram;
    final PlutusV3Script vaultScript;
    final byte[] vaultHash;
    final PlutusV3Script shardScript;
    final byte[] shardHash;
    final VaultPins pins;
    final Map<ResolvedOutput.Outpoint, ResolvedOutput> utxos = new LinkedHashMap<>();
    final List<ResolvedOutput> vaultUtxos = new ArrayList<>();
    final ResolvedOutput[] shards = new ResolvedOutput[16];
    final ShardMirror[] mirrors = new ShardMirror[16];
    ResolvedOutput anchor;
    final ResolvedOutput exitMode;
    final ResolvedOutput vaultRef;
    final ResolvedOutput shardRef;
    final ResolvedOutput fee;
    final StateTrie state = new StateTrie();
    long anchorHeight = 1_000;

    PayoutWorld(AssetId asset) {
        this.asset = asset;
        this.vaultId = GatewayIds.vaultId(DOMAIN, asset, 0);
        vaultProgram = template("AssetVaultValidator").applyParams(PlutusData.bytes(DOMAIN.bytes()),
                PlutusData.bytes(asset.policyId()), PlutusData.bytes(asset.assetName()), PlutusData.integer(0),
                PlutusData.integer(0), PlutusData.bytes(ANCHOR_POLICY), PlutusData.bytes(ANCHOR_NAME),
                PlutusData.bytes(ANCHOR_HASH), PlutusData.bytes(GENESIS.bytes()), PlutusData.bytes(APPLICATION),
                PlutusData.bytes(PROFILE), PlutusData.bytes(FINGERPRINT), PlutusData.bytes(SHARD_POLICY),
                PlutusData.bytes(EXIT_POLICY), PlutusData.bytes(EXIT_HASH), PlutusData.bytes(CLAIM_PREFIX),
                PlutusData.bytes(DEPOSIT_PREFIX),
                PlutusData.bytes("ap/x/".getBytes(StandardCharsets.US_ASCII)), PlutusData.bytes(STATUS_KEY),
                PlutusData.integer(16), PlutusData.integer(8), PlutusData.integer(FLOOR),
                PlutusData.integer(6 * HOUR), PlutusData.integer(30 * DAY), PlutusData.integer(MAX_PAUSE));
        vaultScript = JulcScriptAdapter.fromProgram(vaultProgram);
        vaultHash = HexFormat.of().parseHex(JulcScriptAdapter.scriptHash(vaultProgram));
        Program shardProgram = template("AssetShardValidator").applyParams(PlutusData.bytes(SHARD_POLICY),
                PlutusData.bytes(vaultHash));
        shardScript = JulcScriptAdapter.fromProgram(shardProgram);
        shardHash = HexFormat.of().parseHex(JulcScriptAdapter.scriptHash(shardProgram));
        pins = new VaultPins(vaultId, asset, 0, vaultHash, cat(new byte[] {0x70}, vaultHash), SHARD_POLICY, shardHash,
                cat(new byte[] {0x70}, shardHash), new VaultPins.Anchor(ANCHOR_POLICY, ANCHOR_NAME, ANCHOR_HASH,
                GENESIS.bytes(), APPLICATION, PROFILE, FINGERPRINT), EXIT_POLICY, EXIT_HASH, CLAIM_PREFIX,
                DEPOSIT_PREFIX, STATUS_KEY, BigInteger.valueOf(FLOOR), 16, 8, MAX_PAUSE);

        // Accepted custody: one continuation with plenty of the asset and lovelace, and a floor top-up.
        long custodyAsset = asset.isAda() ? 0 : 1_000_000;
        vaultUtxos.add(add(new ResolvedOutput(outpoint(0x61, 0), pins.vaultAddress(),
                BigInteger.valueOf(asset.isAda() ? 500_000_000L : 60_000_000L), assets(asset, custodyAsset),
                Optional.of(continuationDatum()), Optional.empty())));
        vaultUtxos.add(add(new ResolvedOutput(outpoint(0x61, 1), pins.vaultAddress(), BigInteger.valueOf(FLOOR),
                new TreeMap<>(), Optional.of(new byte[] {(byte) 0xd8, 0x7b, (byte) 0x80}), Optional.empty())));
        for (int shard = 0; shard < 16; shard++) {
            mirrors[shard] = new ShardMirror(shard);
            setShard(shard, outpoint(0x62, shard), mirrors[shard].root());
        }
        state.put(STATUS_KEY, new DomainStatusV1(DomainStatusV1.Status.ACTIVE, 50, 0, EXIT_DELAY).encode());
        anchor = add(anchorOutput(outpoint(0x63, 0), state.root(), anchorHeight));
        exitMode = add(new ResolvedOutput(outpoint(0x64, 0), cat(new byte[] {0x70}, EXIT_HASH),
                BigInteger.valueOf(2_000_000), assets(AssetId.of(EXIT_POLICY, new byte[0]), 1),
                Optional.of(new byte[] {(byte) 0xd8, 0x79, (byte) 0x80}), Optional.empty()));
        vaultRef = add(new ResolvedOutput(outpoint(0x65, 0), FEE_ADDRESS, BigInteger.valueOf(60_000_000),
                new TreeMap<>(), Optional.empty(), Optional.of(new ResolvedOutput.ReferenceScript(vaultHash,
                scriptBytes(vaultScript).length, Optional.empty()))));
        shardRef = add(new ResolvedOutput(outpoint(0x65, 1), FEE_ADDRESS, BigInteger.valueOf(20_000_000),
                new TreeMap<>(), Optional.empty(), Optional.of(new ResolvedOutput.ReferenceScript(shardHash,
                scriptBytes(shardScript).length, Optional.empty()))));
        fee = add(new ResolvedOutput(outpoint(0x60, 0), FEE_ADDRESS, BigInteger.valueOf(80_000_000), new TreeMap<>(),
                Optional.empty(), Optional.empty()));
    }

    // ------------------------------------------------------------------ claims and anchoring

    /** A reserved user claim on {@code shard}: an enterprise key recipient, reserved two days before now. */
    ClaimLeafV1 claim(int shard, int seed) {
        byte[] id = Blake2bUtil.blake2bHash256(("claim-" + seed).getBytes(StandardCharsets.US_ASCII));
        id[31] = (byte) ((id[31] & 0xf0) | shard);
        byte[] recipient = cat(new byte[] {0x60}, filled(28, 0x80 + (seed % 16)));
        BigInteger quantity = BigInteger.valueOf(asset.isAda() ? 5_000_000L + seed : 1_000L + seed);
        BigInteger lovelace = BigInteger.valueOf(asset.isAda() ? 0 : 1_500_000L);
        return new ClaimLeafV1(Hash32.of(id), vaultId, ClaimKind.USER_WITHDRAWAL, AssetValue.single(asset, quantity),
                lovelace, recipient, 100 + seed, NOW_MS - 2 * DAY);
    }

    List<ClaimLeafV1> claims(int shard, int count) {
        List<ClaimLeafV1> claims = new ArrayList<>();
        for (int i = 0; i < count; i++) claims.add(claim(shard, 1_000 * shard + i));
        return claims;
    }

    /** Writes the claims' leaves into the anchored state and re-anchors at {@code height}. */
    void anchor(List<ClaimLeafV1> claims, long height) {
        for (ClaimLeafV1 leaf : claims) state.put(cat(CLAIM_PREFIX, leaf.claimId().bytes()), leaf.encode());
        anchorHeight = height;
        utxos.remove(anchor.outpoint());
        anchor = add(anchorOutput(outpoint(0x63, (int) (height % 1000)), state.root(), height));
    }

    // ------------------------------------------------------------------ deposits (Accept, D8f)

    static final byte[] REFUND_KEY = filled(28, 0xc1);

    /** A deposit-shaped UTxO at the vault ({@code DepositDatumV1}, the vault asset and lovelace), on this L1. */
    ResolvedOutput deposit(int seed) {
        byte[] datum = new DepositDatumV1(filled(28, 0xa1), cat(new byte[] {0x60}, REFUND_KEY), REFUND_KEY).encode();
        long lovelace = asset.isAda() ? 10_000_000L + seed : 2_000_000L + seed;
        return add(new ResolvedOutput(outpoint(0x90 + seed / 64, seed % 64), pins.vaultAddress(),
                BigInteger.valueOf(lovelace), assets(asset, asset.isAda() ? 0 : 500 + seed), Optional.of(datum),
                Optional.empty()));
    }

    List<ResolvedOutput> deposits(int from, int count) {
        List<ResolvedOutput> deposits = new ArrayList<>();
        for (int i = 0; i < count; i++) deposits.add(deposit(from + i));
        return deposits;
    }

    /** The record admission would write for {@code deposit} at {@code height}, CREDITED unless {@code reason}. */
    DepositRecordV1 record(ResolvedOutput deposit, long height, DepositAdmission.Reason reason) {
        Hash32 depositId = AcceptTxBuilder.depositId(pins, deposit.outpoint());
        BigInteger quantity = asset.isAda() ? deposit.lovelace() : deposit.quantityOf(asset);
        boolean credited = reason == DepositAdmission.Reason.NONE;
        return new DepositRecordV1(depositId, vaultId, deposit.outpoint().txId(), deposit.outpoint().index(),
                NOW_SLOT - 10_000, height, VaultOutputShape.DEPOSIT,
                credited ? DepositRecordV1.Status.CREDITED : DepositRecordV1.Status.UNCREDITED, reason,
                credited ? filled(28, 0xa1) : new byte[0], quantity, deposit.lovelace(), 0,
                Hash32.of(filled(32, 0x5e)), credited ? quantity : BigInteger.ZERO,
                credited && !asset.isAda() ? deposit.lovelace() : BigInteger.ZERO);
    }

    /** Records {@code deposits} CREDITED at {@code height} in the state, and re-anchors at {@code anchorAt}. */
    List<DepositRecordV1> credit(List<ResolvedOutput> deposits, long height, long anchorAt) {
        List<DepositRecordV1> records = new ArrayList<>();
        for (ResolvedOutput deposit : deposits) {
            DepositRecordV1 record = record(deposit, height, DepositAdmission.Reason.NONE);
            state.put(AcceptTxBuilder.depositKey(pins, record.depositId()), record.encode());
            records.add(record);
        }
        anchor(List.of(), anchorAt);
        return records;
    }

    AcceptTxBuilder.Context acceptContext() {
        return new AcceptTxBuilder.Context(pins, anchor, exitMode, List.of(vaultRef),
                new PayoutTxBuilder.Funding(List.of(fee), FEE_ADDRESS), NOW_SLOT, NOW_SLOT + 600, PARAMS);
    }

    /** Evaluates every script of {@code built} and fails with the evaluator's message unless all succeed. */
    List<EvaluationResult> evaluate(AcceptTxBuilder.Built built) throws Exception {
        Result<List<EvaluationResult>> result = evaluator().evaluateTx(built.cbor(),
                PayoutTxBuilder.utxos(built.resolvedInputs()));
        if (!result.isSuccessful()) throw new AssertionError("phase-2 evaluation failed: " + result.getResponse());
        assertBalanced(built.transaction(), built.resolvedInputs());
        return result.getValue();
    }

    /** Applies an included {@code Accept}: its deposits leave the L1 and its continuation joins the vault. */
    ResolvedOutput include(AcceptTxBuilder.Built built) {
        built.deposits().forEach(deposit -> utxos.remove(deposit.outpoint()));
        vaultUtxos.removeIf(out -> built.deposits().stream().anyMatch(d -> d.outpoint().equals(out.outpoint())));
        ResolvedOutput continuation = add(built.continuation());
        vaultUtxos.add(continuation);
        return continuation;
    }

    /** Applies a confirmed payout of {@code built} to the shard UTxO and its mirror, as the L1 would. */
    void confirm(PayoutTxBuilder.Built built) {
        mirrors[built.shardIndex()].apply(built.shardPlan());
        setShard(built.shardIndex(), new ResolvedOutput.Outpoint(built.txId(), built.claimIds().size() + 1),
                mirrors[built.shardIndex()].root());
    }

    PayoutTxBuilder.Context context() {
        return context(NOW_SLOT);
    }

    PayoutTxBuilder.Context context(long validFromSlot) {
        return new PayoutTxBuilder.Context(pins, vaultUtxos, shards[0], anchor, exitMode, List.of(vaultRef, shardRef),
                new PayoutTxBuilder.Funding(List.of(fee), FEE_ADDRESS), validFromSlot, validFromSlot + 600, PARAMS, 8);
    }

    PayoutTxBuilder.Context context(int shard) {
        PayoutTxBuilder.Context base = context();
        return new PayoutTxBuilder.Context(base.pins(), base.vaultUtxos(), shards[shard], base.anchor(),
                base.exitMode(), base.scriptRefs(), base.funding(), base.validFromSlot(), base.ttlSlot(),
                base.protocolParams(), base.maxVaultInputs());
    }

    /** A context on {@code shard} with its own validity interval and protocol parameters. */
    PayoutTxBuilder.Context context(int shard, long validFromSlot, long ttlSlot, ProtocolParams pp) {
        return new PayoutTxBuilder.Context(pins, vaultUtxos, shards[shard], anchor, exitMode,
                List.of(vaultRef, shardRef), new PayoutTxBuilder.Funding(List.of(fee), FEE_ADDRESS), validFromSlot,
                ttlSlot, pp, 8);
    }

    /** The member's L1 tip at the world's now (one-second slots). */
    static SettleBodyCheck.Tip tip() {
        return new SettleBodyCheck.Tip(NOW_SLOT, 1_000);
    }

    /** The required signers of a Settle: the first {@code count} members' key hashes. */
    static List<byte[]> signers(int count) {
        return MEMBERS.subList(0, count).stream().map(Blake2bUtil::blake2bHash224).toList();
    }

    // ------------------------------------------------------------------ evaluation

    /** The julc phase-2 evaluator over this world's UTxOs and scripts. */
    JulcTransactionEvaluator evaluator() {
        UtxoSupplier supplier = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer count, Integer page, OrderEnum order) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int index) {
                ResolvedOutput out = utxos.get(new ResolvedOutput.Outpoint(Hash32.ofHex(txHash), index));
                return Optional.ofNullable(out).map(PayoutTxBuilder::utxo);
            }
        };
        ProtocolParamsSupplier params = () -> PARAMS;
        Map<String, PlutusScript> scripts = Map.of(HexFormat.of().formatHex(vaultHash), vaultScript,
                HexFormat.of().formatHex(shardHash), shardScript);
        return new JulcTransactionEvaluator(supplier, params, hash -> Optional.ofNullable(scripts.get(hash)), SLOTS);
    }

    /** Evaluates every script of {@code built} and fails with the evaluator's message unless all succeed. */
    List<EvaluationResult> evaluate(PayoutTxBuilder.Built built) throws Exception {
        Result<List<EvaluationResult>> result = evaluator().evaluateTx(built.cbor(),
                PayoutTxBuilder.utxos(built.resolvedInputs()));
        if (!result.isSuccessful()) throw new AssertionError("phase-2 evaluation failed: " + result.getResponse());
        assertBalanced(built);
        return result.getValue();
    }

    /** Phase 1's value conservation: the spent inputs equal the outputs plus the fee, per unit. */
    static void assertBalanced(PayoutTxBuilder.Built built) {
        assertBalanced(built.transaction(), built.resolvedInputs());
    }

    static void assertBalanced(com.bloxbean.cardano.client.transaction.spec.Transaction tx,
                               List<ResolvedOutput> resolvedInputs) {
        Map<String, BigInteger> balance = new TreeMap<>();
        var body = tx.getBody();
        for (var input : body.getInputs()) {
            ResolvedOutput spent = resolvedInputs.stream().filter(out -> out.outpoint().txIdHex()
                    .equals(input.getTransactionId()) && out.outpoint().index() == input.getIndex()).findFirst()
                    .orElseThrow();
            balance.merge("lovelace", spent.lovelace(), BigInteger::add);
            spent.assets().forEach((asset, quantity) -> balance.merge(asset.toString(), quantity, BigInteger::add));
        }
        balance.merge("lovelace", body.getFee().negate(), BigInteger::add);
        for (var out : body.getOutputs()) {
            balance.merge("lovelace", out.getValue().getCoin().negate(), BigInteger::add);
            if (out.getValue().getMultiAssets() == null) continue;
            for (var multi : out.getValue().getMultiAssets()) {
                for (var token : multi.getAssets()) {
                    AssetId asset = AssetId.of(HexFormat.of().parseHex(multi.getPolicyId()), token.getNameAsBytes());
                    balance.merge(asset.toString(), token.getValue().negate(), BigInteger::add);
                }
            }
        }
        balance.forEach((unit, rest) -> {
            if (rest.signum() != 0) throw new AssertionError("value not conserved for " + unit + ": " + rest);
        });
    }

    Optional<ResolvedOutput> resolve(ResolvedOutput.Outpoint outpoint) {
        return Optional.ofNullable(utxos.get(outpoint));
    }

    // ------------------------------------------------------------------ the anchored state trie

    static final class StateTrie {
        private final MpfTrie trie = new MpfTrie(new MapNodeStore());

        void put(byte[] key, byte[] value) {
            trie.put(key, value);
        }

        byte[] root() {
            return trie.getRootHash();
        }

        /** The node's native proof of {@code key} at {@code height}, under the current root. */
        NodeStateProofs.NativeProof nativeProof(byte[] key, long height) {
            return new NodeStateProofs.NativeProof(height, root(), key, trie.get(key),
                    trie.getProofWire(key).orElseThrow());
        }

        StateProof proof(byte[] key) {
            ProofVerifier.ProfileMetadata metadata = ProofVerifier.profileMetadata(
                    ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow();
            HexFormat hex = HexFormat.of();
            AppChainClient.Proof wire = new AppChainClient.Proof(hex.formatHex(key), "test", hex.formatHex(root()),
                    hex.formatHex(trie.getProofWire(key).orElseThrow()), hex.formatHex(trie.get(key)), 42L, 42L, 1,
                    ProofVerifier.MPF_BLAKE2B256_V1, metadata.backend(), metadata.commitmentFormatId(),
                    metadata.formatFingerprintHex(), "11".repeat(32), metadata.proofEncodingId(),
                    metadata.nativeVersioning(), metadata.physicalDelete(), 42L, AppChainClient.ProofPresence.PRESENT,
                    null, null);
            MpfNormalizedProof normalized = MpfProofConverter.convert(wire);
            List<StateProof.Fold> folds = new ArrayList<>();
            for (MpfNormalizedProof.FoldStep step : normalized.folds()) {
                folds.add(new StateProof.Fold(step.cursor(), step.prefix(), step.nibble(), step.neighbors(),
                        step.branchValueHash()));
            }
            return new StateProof(normalized.key(), normalized.value(), normalized.leafSuffix(), folds);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void setShard(int shard, ResolvedOutput.Outpoint outpoint, byte[] root) {
        if (shards[shard] != null) utxos.remove(shards[shard].outpoint());
        byte[] datum = cat(new byte[] {(byte) 0xd8, 0x79, (byte) 0x83, 0x01, (byte) shard, 0x58, 0x20}, root);
        shards[shard] = add(new ResolvedOutput(outpoint, pins.shardAddress(), BigInteger.valueOf(2_000_000),
                assets(pins.shardToken(shard).isAda() ? null : pins.shardToken(shard), 1), Optional.of(datum),
                Optional.empty()));
    }

    private ResolvedOutput anchorOutput(ResolvedOutput.Outpoint outpoint, byte[] root, long height) {
        List<com.bloxbean.cardano.client.plutus.spec.PlutusData> members = new ArrayList<>();
        for (byte[] key : MEMBERS) members.add(com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(key));
        var datum = com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0,
                com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(2),
                com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(ANCHOR_NAME),
                com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(GENESIS.bytes()),
                com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(APPLICATION),
                com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(PROFILE),
                com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(FINGERPRINT),
                com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(height),
                com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(filled(32, 0x66)),
                com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(root),
                GatewayPlutus.list(members),
                com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(THRESHOLD),
                com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(NOW_MS - HOUR));
        try {
            return new ResolvedOutput(outpoint, cat(new byte[] {0x70}, ANCHOR_HASH), BigInteger.valueOf(2_000_000),
                    assets(AssetId.of(ANCHOR_POLICY, ANCHOR_NAME), 1), Optional.of(datum.serializeToBytes()),
                    Optional.empty());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] continuationDatum() {
        return cat(new byte[] {(byte) 0xd8, 0x7a, (byte) 0x81, (byte) 0x80});
    }

    ResolvedOutput add(ResolvedOutput out) {
        utxos.put(out.outpoint(), out);
        return out;
    }

    static TreeMap<AssetId, BigInteger> assets(AssetId asset, long quantity) {
        TreeMap<AssetId, BigInteger> map = new TreeMap<>();
        if (asset != null && !asset.isAda() && quantity > 0) map.put(asset, BigInteger.valueOf(quantity));
        return map;
    }

    static ResolvedOutput.Outpoint outpoint(int fill, int index) {
        return new ResolvedOutput.Outpoint(Hash32.of(filled(32, fill)), index);
    }

    static byte[] scriptBytes(PlutusV3Script script) {
        try {
            return script.scriptRefBytes();
        } catch (Exception failure) {
            return HexFormat.of().parseHex(script.getCborHex());
        }
    }

    private static synchronized Program template(String name) {
        return TEMPLATES.computeIfAbsent(name, key -> {
            try (InputStream in = Objects.requireNonNull(PayoutWorld.class.getResourceAsStream(
                    "/META-INF/plutus/" + key + ".plutus.json"), key)) {
                Matcher matcher = Pattern.compile("\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"")
                        .matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!matcher.find()) throw new IllegalStateException("no cborHex in " + key);
                return JulcScriptAdapter.toProgram(matcher.group(1));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    static ProtocolParams protocolParams() {
        try (InputStream in = Objects.requireNonNull(PayoutWorld.class.getResourceAsStream(
                "/cardano-fixtures/devnet-protocol-param.json"))) {
            JsonNode json = new ObjectMapper().readTree(in);
            ProtocolParams pp = new ProtocolParams();
            pp.setMinFeeA(json.get("min_fee_a").asInt());
            pp.setMinFeeB(json.get("min_fee_b").asInt());
            pp.setMaxTxSize(json.get("max_tx_size").asInt());
            pp.setPriceMem(new BigDecimal(json.get("price_mem").asText()));
            pp.setPriceStep(new BigDecimal(json.get("price_step").asText()));
            pp.setMaxTxExMem(json.get("max_tx_ex_mem").asText());
            pp.setMaxTxExSteps(json.get("max_tx_ex_steps").asText());
            pp.setCollateralPercent(new BigDecimal(json.get("collateral_percent").asText()));
            pp.setMaxCollateralInputs(3);
            pp.setCoinsPerUtxoSize(json.get("coins_per_utxo_size").asText());
            pp.setMinFeeRefScriptCostPerByte(new BigDecimal(json.get("min_fee_ref_script_cost_per_byte").asText()));
            pp.setProtocolMajorVer(json.get("protocol_major_ver").asInt());
            pp.setProtocolMinorVer(0);
            LinkedHashMap<String, LinkedHashMap<String, Long>> models = new LinkedHashMap<>();
            json.get("cost_models").fields().forEachRemaining(language -> {
                LinkedHashMap<String, Long> costs = new LinkedHashMap<>();
                language.getValue().fields().forEachRemaining(cost -> costs.put(cost.getKey(), cost.getValue().asLong()));
                models.put(language.getKey(), costs);
            });
            pp.setCostModels(models);
            LinkedHashMap<String, List<Long>> raw = new LinkedHashMap<>();
            json.get("cost_models_raw").fields().forEachRemaining(language -> {
                List<Long> costs = new ArrayList<>();
                language.getValue().forEach(cost -> costs.add(cost.asLong()));
                raw.put(language.getKey(), costs);
            });
            pp.setCostModelsRaw(raw);
            return pp;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static byte[] filled(int length, int value) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    static byte[] cat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();

        @Override public byte[] get(byte[] hash) { return nodes.get(HexFormat.of().formatHex(hash)); }

        @Override public void put(byte[] hash, byte[] nodeBytes) { nodes.put(HexFormat.of().formatHex(hash), nodeBytes); }

        @Override public void delete(byte[] hash) { nodes.remove(HexFormat.of().formatHex(hash)); }
    }
}
