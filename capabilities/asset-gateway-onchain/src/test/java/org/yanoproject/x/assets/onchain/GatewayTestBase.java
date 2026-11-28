package org.yanoproject.x.assets.onchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.Address;
import org.julclang.ledger.Interval;
import org.julclang.ledger.IntervalBound;
import org.julclang.ledger.IntervalBoundType;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptPurpose;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.testkit.ContractTest;
import org.julclang.testkit.ScriptContextTestBuilder;
import org.julclang.testkit.SourceDiscovery;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.BeforeAll;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.MpfProofConverter;
import org.yanoproject.x.client.ProofVerifier;
import org.yanoproject.x.proofs.MpfNormalizedNonMembershipProof;
import org.yanoproject.x.proofs.MpfNormalizedProof;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared world of the gateway validator tests: pinned identities, compiled and parameterized programs, a real
 * mpf-blake2b256-v1 state trie with Yano-normalized proofs, real shard tries with Aiken-format insert proofs, and a
 * mutable transaction model that builds the {@code ScriptContext} each script sees.
 */
abstract class GatewayTestBase extends ContractTest {
    static final long MAX_TX_CPU = 10_000_000_000L;
    static final long MAX_TX_MEM = 16_500_000L;
    static final long HOUR = 3_600_000L;
    static final long DAY = 24 * HOUR;

    // Script identities. The tests evaluate each script directly, so these stand in for the real hashes.
    static final byte[] VAULT_HASH = filled(0x54, 28);
    static final byte[] OTHER_VAULT_HASH = filled(0x55, 28);
    static final byte[] SHARD_HASH = filled(0x53, 28);
    static final byte[] SHARD_POLICY = filled(0x71, 28);
    static final byte[] EXIT_POLICY = filled(0x72, 28);
    static final byte[] EXIT_HASH = filled(0x73, 28);
    static final byte[] INBOX_HASH = filled(0x74, 28);
    static final byte[] ANCHOR_POLICY = filled(0x51, 28);
    static final byte[] ANCHOR_NAME = "payments-1".getBytes(StandardCharsets.US_ASCII);
    static final byte[] ANCHOR_HASH = filled(0x52, 28);
    static final byte[] OTHER_SCRIPT = filled(0x5a, 28);

    static final Hash32 DOMAIN = GatewayFixtures.DOMAIN;
    static final byte[] GENESIS = GatewayFixtures.GENESIS.bytes();
    static final byte[] APPLICATION = "asset-payments".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PROFILE = ProofVerifier.MPF_BLAKE2B256_V1.getBytes(StandardCharsets.US_ASCII);
    static final byte[] FINGERPRINT = HexFormat.of().parseHex(
            ProofVerifier.profileMetadata(ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow().formatFingerprintHex());
    static final AssetId TOKEN = GatewayFixtures.TOKEN;
    static final Hash32 TOKEN_VAULT = GatewayIds.vaultId(DOMAIN, TOKEN, 0);
    static final Hash32 ADA_VAULT = GatewayIds.vaultId(DOMAIN, AssetId.ADA, 0);

    static final byte[] CLAIM_PREFIX = ascii("ap/claim/");
    static final byte[] DEPOSIT_PREFIX = ascii("ap/deposit/");
    static final byte[] EXIT_PREFIX = ascii("ap/exit/");
    static final byte[] FORCED_PREFIX = ascii("ap/forced/");
    static final byte[] STATUS_KEY = ascii("ap/domain-status");

    static final long EXIT_DELAY = DAY;
    static final long DELAY_FLOOR = 6 * HOUR;
    static final long DELAY_CEILING = 30 * DAY;
    static final long MAX_PAUSE = 7 * DAY;
    static final long HEARTBEAT = 72 * HOUR;
    static final long FORCED_DEADLINE = 72 * HOUR;
    static final long FLOOR = 2_000_000L;
    static final long NOW = 1_764_000_000_000L;

    static final List<byte[]> MEMBERS = List.of(filled(0x11, 32), filled(0x22, 32), filled(0x33, 32));
    static final int THRESHOLD = 2;

    private static final Map<String, Program> PROGRAMS = new HashMap<>();

    @BeforeAll
    static void crypto() {
        initCrypto();
    }

    // ------------------------------------------------------------------
    // Programs

    static synchronized Program program(Class<?> validator, PlutusData... params) {
        String key = validator.getName() + Arrays.toString(params);
        return PROGRAMS.computeIfAbsent(key, ignored -> {
            var compiled = SourceDiscovery.compile(validator, GatewayFixtures.SOURCES);
            assertThat(compiled.hasErrors()).as("%s compiles: %s", validator.getSimpleName(),
                    compiled.diagnostics()).isFalse();
            return params.length == 0 ? compiled.program() : compiled.program().applyParams(params);
        });
    }

    /** The vault for {@code asset}, with the default profile (maxSettle 16, maxExit 8). */
    static Program vault(AssetId asset) {
        return program(AssetVaultValidator.class, vaultParams(asset));
    }

    static PlutusData[] vaultParams(AssetId asset) {
        return new PlutusData[] {
                PlutusData.bytes(DOMAIN.bytes()), PlutusData.bytes(asset.policyId()),
                PlutusData.bytes(asset.assetName()), PlutusData.integer(0), PlutusData.integer(0),
                PlutusData.bytes(ANCHOR_POLICY), PlutusData.bytes(ANCHOR_NAME), PlutusData.bytes(ANCHOR_HASH),
                PlutusData.bytes(GENESIS), PlutusData.bytes(APPLICATION), PlutusData.bytes(PROFILE),
                PlutusData.bytes(FINGERPRINT), PlutusData.bytes(SHARD_POLICY), PlutusData.bytes(EXIT_POLICY),
                PlutusData.bytes(EXIT_HASH), PlutusData.bytes(CLAIM_PREFIX), PlutusData.bytes(DEPOSIT_PREFIX),
                PlutusData.bytes(EXIT_PREFIX), PlutusData.bytes(STATUS_KEY), PlutusData.integer(16),
                PlutusData.integer(16), PlutusData.integer(FLOOR), PlutusData.integer(DELAY_FLOOR),
                PlutusData.integer(DELAY_CEILING), PlutusData.integer(MAX_PAUSE)};
    }

    static Program shard() {
        return program(AssetShardValidator.class, PlutusData.bytes(SHARD_POLICY), PlutusData.bytes(VAULT_HASH));
    }

    /** The Aiken shard backend's committed template (ADR-059 r23), as a deployment parameterizes it. */
    static final java.nio.file.Path AIKEN_SHARD_ARTIFACT =
            java.nio.file.Path.of("src/main/resources/META-INF/plutus/AssetShardValidatorAiken.plutus.json");

    static synchronized Program aikenShardTemplate() {
        return aikenTemplate(AIKEN_SHARD_ARTIFACT);
    }

    /** A committed Aiken template, read from its text envelope. */
    static synchronized Program aikenTemplate(java.nio.file.Path artifact) {
        return PROGRAMS.computeIfAbsent("aiken:template:" + artifact, ignored -> {
            try {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"").matcher(
                        java.nio.file.Files.readString(artifact));
                assertThat(matcher.find()).as("%s carries cborHex", artifact).isTrue();
                return org.julclang.clientlib.JulcScriptAdapter.toProgram(matcher.group(1));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    /** The Aiken shard with the same parameters as {@link #shard()}. */
    static synchronized Program aikenShard() {
        Program template = aikenShardTemplate();
        return PROGRAMS.computeIfAbsent("aiken:shard", ignored -> template.applyParams(PlutusData.bytes(SHARD_POLICY),
                PlutusData.bytes(VAULT_HASH)));
    }

    static Program exitModeValidator() {
        return program(ExitModeThreadValidator.class, PlutusData.bytes(EXIT_POLICY), PlutusData.bytes(ANCHOR_POLICY),
                PlutusData.bytes(ANCHOR_NAME), PlutusData.bytes(ANCHOR_HASH), PlutusData.bytes(GENESIS),
                PlutusData.bytes(APPLICATION), PlutusData.bytes(PROFILE), PlutusData.bytes(FINGERPRINT),
                PlutusData.bytes(INBOX_HASH), PlutusData.integer(HEARTBEAT), PlutusData.integer(FORCED_DEADLINE),
                PlutusData.integer(MAX_PAUSE), PlutusData.bytes(FORCED_PREFIX), PlutusData.bytes(STATUS_KEY));
    }

    static Program inbox() {
        return program(ForcedInboxValidator.class, PlutusData.bytes(DOMAIN.bytes()), PlutusData.bytes(ANCHOR_POLICY),
                PlutusData.bytes(ANCHOR_NAME), PlutusData.bytes(ANCHOR_HASH), PlutusData.bytes(GENESIS),
                PlutusData.bytes(APPLICATION), PlutusData.bytes(PROFILE), PlutusData.bytes(FINGERPRINT),
                PlutusData.bytes(EXIT_POLICY), PlutusData.bytes(FORCED_PREFIX), PlutusData.integer(10 * 60_000L));
    }

    // ------------------------------------------------------------------
    // The app-ledger state trie (mpf-blake2b256-v1, Yano-normalized proofs)

    static final class StateTrie {
        private final MpfTrie trie = new MpfTrie(new GatewayFixtures.MapNodeStore());

        StateTrie fill(String prefix, int count) {
            for (int i = 0; i < count; i++) {
                byte[] key = ascii(prefix + i);
                trie.put(key, Blake2bUtil.blake2bHash256(key));
            }
            return this;
        }

        StateTrie put(byte[] key, byte[] value) {
            trie.put(key, value);
            return this;
        }

        byte[] root() {
            return trie.getRootHash();
        }

        MpfNormalizedProof inclusion(byte[] key) {
            return MpfProofConverter.convert(wire(key, trie.get(key)));
        }

        PlutusData proof(byte[] key) {
            return proofData(inclusion(key));
        }

        PlutusData absence(byte[] key) {
            MpfNormalizedNonMembershipProof proof = absenceProof(key);
            return absenceData(proof.key(), proof.conflictingLeafSuffix(), proof.folds(), proof.terminalCursor(),
                    proof.conflictingKeyHash(), proof.conflictingValueHash());
        }

        MpfNormalizedNonMembershipProof absenceProof(byte[] key) {
            return MpfProofConverter.convertAbsence(wire(key, null));
        }

        /**
         * An absence proof of the PRESENT {@code key}: its own leaf, named through a "conflicting" key hash that
         * differs from the key's hash in nibble {@code flip} above the leaf, where nothing authenticates it.
         */
        PlutusData absenceOfPresentKey(byte[] key, int flip) {
            MpfNormalizedProof present = inclusion(key);
            byte[] conflicting = Blake2bUtil.blake2bHash256(key);
            conflicting[flip / 2] ^= (byte) (flip % 2 == 0 ? 0x10 : 0x01);
            return absenceData(key, present.leafSuffix(), present.folds(), leafCursor(present.leafSuffix()),
                    conflicting, Blake2bUtil.blake2bHash256(present.value()));
        }

        int depth(byte[] key) {
            return inclusion(key).folds().size();
        }

        private AppChainClient.Proof wire(byte[] key, byte[] value) {
            ProofVerifier.ProfileMetadata metadata = ProofVerifier.profileMetadata(
                    ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow();
            HexFormat hex = HexFormat.of();
            return new AppChainClient.Proof(
                    hex.formatHex(key), "test", hex.formatHex(root()),
                    hex.formatHex(trie.getProofWire(key).orElseThrow()),
                    value == null ? null : hex.formatHex(value), 42L, 42L, 1,
                    ProofVerifier.MPF_BLAKE2B256_V1, metadata.backend(),
                    metadata.commitmentFormatId(), metadata.formatFingerprintHex(),
                    "11".repeat(32), metadata.proofEncodingId(), metadata.nativeVersioning(),
                    metadata.physicalDelete(), 42L,
                    value == null ? AppChainClient.ProofPresence.ABSENT : AppChainClient.ProofPresence.PRESENT,
                    null, null);
        }
    }

    /**
     * A state trie in which {@code query} is absent and its path leaves a branch's compressed prefix one level below
     * the root: exactly two filler keys share {@code query}'s first nibble, and they share a second nibble that
     * {@code query} does not have. Fifteen more fillers sit under other first nibbles.
     */
    static StateTrie branchPrefixTrie(byte[] query) {
        byte[] path = Blake2bUtil.blake2bHash256(query);
        int first = (path[0] & 0xff) >> 4;
        int second = path[0] & 0x0f;
        List<byte[]> pair = new ArrayList<>();
        List<byte[]> others = new ArrayList<>();
        int pairSecond = -1;
        for (int i = 0; pair.size() < 2 || others.size() < 15; i++) {
            byte[] key = ascii("ap/account/" + i);
            byte[] hash = Blake2bUtil.blake2bHash256(key);
            int a = (hash[0] & 0xff) >> 4;
            int b = hash[0] & 0x0f;
            if (a != first) {
                if (others.size() < 15) others.add(key);
            } else if (b != second && pair.size() < 2 && (pairSecond < 0 || b == pairSecond)) {
                pairSecond = b;
                pair.add(key);
            }
        }
        StateTrie state = new StateTrie();
        for (byte[] key : pair) state.put(key, Blake2bUtil.blake2bHash256(key));
        for (byte[] key : others) state.put(key, Blake2bUtil.blake2bHash256(key));
        MpfNormalizedNonMembershipProof proof = state.absenceProof(query);
        assertThat(proof.conflictingBranch()).as("the query leaves a branch prefix").isTrue();
        assertThat(proof.terminalCursor()).isEqualTo(1);
        return state;
    }

    /** {@code MpfOnChainVerifier.Proof(key, value, leafSuffix, folds)}. */
    static PlutusData proofData(MpfNormalizedProof proof) {
        return PlutusData.constr(0, PlutusData.bytes(proof.key()), PlutusData.bytes(proof.value()),
                PlutusData.bytes(proof.leafSuffix()), folds(proof.folds()));
    }

    /** {@code MpfOnChainVerifier.AbsenceProof}, the seven-field layout. */
    static PlutusData absenceData(byte[] key, byte[] leafSuffix, List<MpfNormalizedProof.FoldStep> folds,
                                  long terminalCursor, byte[] conflictingKeyHash, byte[] conflictingValueHash) {
        return PlutusData.constr(0, PlutusData.bytes(key), PlutusData.bytes(new byte[0]),
                PlutusData.bytes(leafSuffix), folds(folds), PlutusData.integer(terminalCursor),
                PlutusData.bytes(conflictingKeyHash), PlutusData.bytes(conflictingValueHash));
    }

    /** The depth, in nibbles, of the leaf whose encoded suffix is {@code leafSuffix}. */
    static long leafCursor(byte[] leafSuffix) {
        long nibbles = (leafSuffix[0] & 0xff) == 0 ? 1 + (leafSuffix.length - 2) * 2L : (leafSuffix.length - 1) * 2L;
        return MpfNormalizedProof.PATH_NIBBLES - nibbles;
    }

    static PlutusData withValue(PlutusData proof, byte[] value) {
        List<PlutusData> fields = new ArrayList<>(((PlutusData.ConstrData) proof).fields());
        fields.set(1, PlutusData.bytes(value));
        return PlutusData.constr(0, fields.toArray(new PlutusData[0]));
    }

    static PlutusData withKey(PlutusData proof, byte[] key) {
        List<PlutusData> fields = new ArrayList<>(((PlutusData.ConstrData) proof).fields());
        fields.set(0, PlutusData.bytes(key));
        return PlutusData.constr(((PlutusData.ConstrData) proof).tag(), fields.toArray(new PlutusData[0]));
    }

    private static PlutusData folds(List<MpfNormalizedProof.FoldStep> folds) {
        PlutusData[] result = new PlutusData[folds.size()];
        for (int i = 0; i < folds.size(); i++) {
            MpfNormalizedProof.FoldStep fold = folds.get(i);
            List<byte[]> n = fold.neighbors();
            result[i] = PlutusData.constr(0, PlutusData.integer(fold.cursor()), PlutusData.bytes(fold.prefix()),
                    PlutusData.integer(fold.nibble()), PlutusData.bytes(n.get(0)), PlutusData.bytes(n.get(1)),
                    PlutusData.bytes(n.get(2)), PlutusData.bytes(n.get(3)),
                    PlutusData.bytes(fold.branchValueHash()));
        }
        return PlutusData.list(result);
    }

    // ------------------------------------------------------------------
    // Shard tries (Aiken MPF format, value = key)

    static final class ShardTrie {
        private final MpfTrie trie = new MpfTrie(new GatewayFixtures.MapNodeStore());
        final int index;

        ShardTrie(int index) {
            this.index = index;
        }

        /** {@code count} already-paid ids of this shard. */
        ShardTrie seed(int count) {
            for (int i = 0; i < count; i++) {
                byte[] id = shardId(index, 0x9000 + i);
                trie.put(id, id);
            }
            return this;
        }

        byte[] root() {
            byte[] root = trie.getRootHash();
            return root == null ? new byte[32] : root;
        }

        void insert(byte[] id) {
            trie.put(id, id);
        }

        /**
         * The insert batch for {@code ids} from the current root: each id's exclusion proof at the running root, then
         * the id is inserted. The trie advances to the post-batch state, as a confirmed payout would.
         */
        Inserts plan(List<byte[]> ids) {
            byte[] before = root();
            List<PlutusData> items = new ArrayList<>();
            for (byte[] id : ids) {
                byte[] wire = trie.getProofWire(id).orElseThrow();
                try {
                    items.add(PlutusData.constr(0, PlutusData.bytes(id), PlutusDataAdapter.fromClientLib(
                            com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(wire))));
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                trie.put(id, id);
            }
            return new Inserts(PlutusData.constr(0, PlutusData.list(items.toArray(new PlutusData[0]))), before,
                    root());
        }
    }

    record Inserts(PlutusData redeemer, byte[] before, byte[] after) {
    }

    /** A 32-byte id whose last nibble routes it to {@code shard}. */
    static byte[] shardId(int shard, int seed) {
        byte[] id = Blake2bUtil.blake2bHash256(ascii("id-" + seed));
        id[31] = (byte) ((id[31] & 0xF0) | shard);
        return id;
    }

    static PlutusData shardDatum(int index, byte[] root) {
        return PlutusData.constr(0, PlutusData.integer(1), PlutusData.integer(index), PlutusData.bytes(root));
    }

    // ------------------------------------------------------------------
    // Claims

    record ClaimFx(byte[] id, int kind, long quantity, long vaultLovelace, byte[] recipient, long height,
                   long atMs) {
        PlutusData data() {
            return PlutusData.constr(0, PlutusData.bytes(id), PlutusData.integer(kind), PlutusData.integer(quantity),
                    PlutusData.integer(vaultLovelace), PlutusData.bytes(recipient), PlutusData.integer(height),
                    PlutusData.integer(atMs));
        }

        byte[] key() {
            return GatewayFixtures.concat(CLAIM_PREFIX, id);
        }

        /** The contract's {@code ClaimLeafV1} bytes for {@code vault} on {@code asset}. */
        byte[] leaf(Hash32 vault, AssetId asset) {
            AssetValue value = quantity == 0 ? AssetValue.EMPTY
                    : AssetValue.single(asset, BigInteger.valueOf(quantity));
            return new ClaimLeafV1(Hash32.of(id), vault, ClaimKind.fromCode(kind), value,
                    BigInteger.valueOf(vaultLovelace), recipient, height, atMs).encode();
        }

        ClaimFx withKind(int newKind) {
            return new ClaimFx(id, newKind, quantity, vaultLovelace, recipient, height, atMs);
        }

        ClaimFx withQuantity(long newQuantity) {
            return new ClaimFx(id, kind, newQuantity, vaultLovelace, recipient, height, atMs);
        }

        ClaimFx withRecipient(byte[] newRecipient) {
            return new ClaimFx(id, kind, quantity, vaultLovelace, newRecipient, height, atMs);
        }

        ClaimFx withAtMs(long newAtMs) {
            return new ClaimFx(id, kind, quantity, vaultLovelace, recipient, height, newAtMs);
        }
    }

    /** A user claim on {@code shard}, reserved two days before {@link #NOW}. */
    static ClaimFx userClaim(int shard, int seed, AssetId asset) {
        byte[] recipient = GatewayFixtures.enterpriseKeyAddress(filled(0x80 + (seed % 16), 28));
        long quantity = asset.isAda() ? 5_000_000L + seed : 1_000L + seed;
        long lovelace = asset.isAda() ? 0 : 1_500_000L;
        return new ClaimFx(shardId(shard, seed), 0, quantity, lovelace, recipient, 100 + seed, NOW - 2 * DAY);
    }

    static byte[] statusLeaf(DomainStatusV1.Status status, long sinceMs, long delay) {
        return new DomainStatusV1(status, 50, sinceMs, delay).encode();
    }

    // ------------------------------------------------------------------
    // Values, addresses and datums

    static Value lovelace(long amount) {
        return Value.lovelace(BigInteger.valueOf(amount));
    }

    static Value assetValue(AssetId asset, long lovelace, long quantity) {
        if (asset.isAda()) {
            return lovelace(lovelace + quantity);
        }
        Value value = lovelace(lovelace);
        return quantity == 0 ? value : value.merge(token(asset.policyId(), asset.assetName(), quantity));
    }

    static Value token(byte[] policy, byte[] name, long quantity) {
        return Value.singleton(new PolicyId(policy), new TokenName(name), BigInteger.valueOf(quantity));
    }

    static Address vaultAddress() {
        return GatewayFixtures.scriptAddress(VAULT_HASH);
    }

    static PlutusData continuationDatum(List<byte[]> ids) {
        List<PlutusData> items = new ArrayList<>();
        for (byte[] id : ids) {
            items.add(PlutusData.bytes(id));
        }
        return PlutusData.constr(1, PlutusData.list(items.toArray(new PlutusData[0])));
    }

    static PlutusData floorDatum() {
        return PlutusData.constr(2);
    }

    static PlutusData depositDatum(byte[] refundAddress, byte[] refundKeyHash) {
        return PlutusData.constr(0, PlutusData.bytes(filled(0xa1, 28)), PlutusData.bytes(refundAddress),
                PlutusData.bytes(refundKeyHash));
    }

    static PlutusData anchorDatum(int version, byte[] root, long height, List<byte[]> members, int threshold,
                                  long updatedAtMs) {
        List<PlutusData> keys = new ArrayList<>();
        for (byte[] key : members) {
            keys.add(PlutusData.bytes(key));
        }
        List<PlutusData> fields = new ArrayList<>(List.of(PlutusData.integer(version),
                PlutusData.bytes(ascii("payments-1")), PlutusData.bytes(GENESIS), PlutusData.bytes(APPLICATION),
                PlutusData.bytes(PROFILE), PlutusData.bytes(FINGERPRINT), PlutusData.integer(height),
                PlutusData.bytes(filled(0x66, 32)), PlutusData.bytes(root),
                PlutusData.list(keys.toArray(new PlutusData[0])), PlutusData.integer(threshold)));
        if (version == 2) {
            fields.add(PlutusData.integer(updatedAtMs));
        }
        return PlutusData.constr(0, fields.toArray(new PlutusData[0]));
    }

    static TxInInfo anchorInput(PlutusData datum) {
        return new TxInInfo(ref(0x63, 0), new TxOut(GatewayFixtures.scriptAddress(ANCHOR_HASH),
                lovelace(2_000_000).merge(token(ANCHOR_POLICY, ANCHOR_NAME, 1)),
                new OutputDatum.OutputDatumInline(datum), Optional.empty()));
    }

    static PlutusData normalMode() {
        return PlutusData.constr(0);
    }

    static PlutusData exitMode(byte[] frozenRoot) {
        return PlutusData.constr(1, PlutusData.bytes(frozenRoot), PlutusData.integer(42), PlutusData.integer(NOW));
    }

    static TxInInfo exitModeInput(PlutusData datum) {
        return new TxInInfo(ref(0x64, 0), new TxOut(GatewayFixtures.scriptAddress(EXIT_HASH),
                lovelace(2_000_000).merge(token(EXIT_POLICY, new byte[0], 1)),
                new OutputDatum.OutputDatumInline(datum), Optional.empty()));
    }

    static TxOutRef ref(int fill, int index) {
        return new TxOutRef(new TxId(filled(fill, 32)), BigInteger.valueOf(index));
    }

    static Interval range(long lower, long upper) {
        return new Interval(
                new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(lower)), true),
                new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(upper)), false));
    }

    static PubKeyHash keyHash(byte[] key) {
        return new PubKeyHash(Blake2bUtil.blake2bHash224(key));
    }

    // ------------------------------------------------------------------
    // The transaction model

    /** One spent input with its spend redeemer (null for a key input). */
    record Spent(TxInInfo input, PlutusData redeemer) {
    }

    static final class Tx {
        final List<Spent> inputs = new ArrayList<>();
        final List<TxInInfo> references = new ArrayList<>();
        final List<TxOut> outputs = new ArrayList<>();
        final List<PubKeyHash> signers = new ArrayList<>();
        final Map<String, PlutusData> otherRedeemers = new HashMap<>();
        /** Datum preimages the transaction witnesses, for inputs that carry only a datum hash. */
        final Map<org.julclang.ledger.DatumHash, PlutusData> witnessDatums = new HashMap<>();
        Value mint = Value.zero();
        Interval range = range(NOW, NOW + 600_000);
        boolean withdrawal;

        Tx spend(TxInInfo input, PlutusData redeemer) {
            inputs.add(new Spent(input, redeemer));
            return this;
        }

        Tx reference(TxInInfo input) {
            references.add(input);
            return this;
        }

        Tx output(TxOut out) {
            outputs.add(out);
            return this;
        }

        Tx signer(PubKeyHash hash) {
            signers.add(hash);
            return this;
        }

        Tx signedByMembers(int count) {
            for (int i = 0; i < count; i++) {
                signers.add(keyHash(MEMBERS.get(i)));
            }
            return this;
        }

        /** Every input that carries {@code redeemer}, replaced by {@code replacement}. */
        Tx replaceRedeemer(PlutusData redeemer, PlutusData replacement) {
            for (int i = 0; i < inputs.size(); i++) {
                Spent spent = inputs.get(i);
                if (redeemer.equals(spent.redeemer)) {
                    inputs.set(i, new Spent(spent.input, replacement));
                }
            }
            return this;
        }

        /**
         * The script context of spending {@code own}; inputs and reference inputs in ledger order. The spending
         * script info carries the datum as the ledger does: the inline datum, the witnessed preimage of a datum
         * hash, or none for an output without a datum.
         */
        PlutusData spending(TxOutRef own) {
            Spent ownSpent = inputs.stream().filter(s -> s.input.outRef().equals(own)).findFirst().orElseThrow();
            OutputDatum datum = ownSpent.input.resolved().datum();
            ScriptContextTestBuilder builder;
            if (datum instanceof OutputDatum.OutputDatumInline inline) {
                builder = ScriptContextTestBuilder.spending(own, inline.datum());
            } else if (datum instanceof OutputDatum.OutputDatumHash hash) {
                PlutusData preimage = witnessDatums.get(hash.hash());
                builder = ScriptContextTestBuilder.spending(own, preimage).datum(hash.hash(), preimage);
            } else {
                builder = ScriptContextTestBuilder.spending(own);
            }
            return fill(builder.redeemer(ownSpent.redeemer)).buildPlutusData();
        }

        PlutusData minting(byte[] policy, PlutusData redeemer) {
            ScriptContextTestBuilder builder = ScriptContextTestBuilder.minting(new PolicyId(policy))
                    .redeemer(redeemer)
                    .redeemerEntry(new ScriptPurpose.Minting(new PolicyId(policy)), redeemer);
            return fill(builder).buildPlutusData();
        }

        private ScriptContextTestBuilder fill(ScriptContextTestBuilder builder) {
            List<Spent> sorted = new ArrayList<>(inputs);
            sorted.sort(Comparator.comparing((Spent s) -> HexFormat.of().formatHex(
                    s.input.outRef().txId().hash()))
                    .thenComparing(s -> s.input.outRef().index()));
            for (Spent spent : sorted) {
                builder.input(spent.input);
                if (spent.redeemer != null) {
                    builder.redeemerEntry(new ScriptPurpose.Spending(spent.input.outRef()), spent.redeemer);
                }
            }
            List<TxInInfo> refs = new ArrayList<>(references);
            refs.sort(Comparator.comparing((TxInInfo r) -> HexFormat.of().formatHex(
                    r.outRef().txId().hash())).thenComparing(r -> r.outRef().index()));
            refs.forEach(builder::referenceInput);
            outputs.forEach(builder::output);
            signers.forEach(builder::signer);
            builder.fee(BigInteger.valueOf(400_000)).validRange(range);
            if (!mint.equals(Value.zero())) {
                builder.mint(mint);
            }
            if (withdrawal) {
                builder.withdrawal(new org.julclang.ledger.Credential.ScriptCredential(
                        new org.julclang.ledger.ScriptHash(OTHER_SCRIPT)), BigInteger.ZERO);
            }
            return builder;
        }

    }

    static TxInInfo vaultInput(TxOutRef ref, Value value, PlutusData datum) {
        return new TxInInfo(ref, new TxOut(vaultAddress(), value, new OutputDatum.OutputDatumInline(datum),
                Optional.empty()));
    }

    /** A vault input with no datum at all. */
    static TxInInfo datumlessVaultInput(TxOutRef ref, Value value) {
        return new TxInInfo(ref, new TxOut(vaultAddress(), value, new OutputDatum.NoOutputDatum(), Optional.empty()));
    }

    /** A vault input that carries only the hash of {@code datum}; the transaction witnesses the preimage. */
    static TxInInfo datumHashVaultInput(Tx tx, TxOutRef ref, Value value, PlutusData datum) {
        org.julclang.ledger.DatumHash hash = new org.julclang.ledger.DatumHash(Blake2bUtil.blake2bHash256(
                org.julclang.core.cbor.PlutusDataCborEncoder.encode(datum)));
        tx.witnessDatums.put(hash, datum);
        return new TxInInfo(ref, new TxOut(vaultAddress(), value, new OutputDatum.OutputDatumHash(hash),
                Optional.empty()));
    }

    /** The marker a non-lead vault input may carry instead of the lead's redeemer (V13). */
    static PlutusData follow() {
        return PlutusData.constr(9);
    }

    static PlutusData consolidate() {
        return PlutusData.constr(8);
    }

    static TxInInfo keyInput(TxOutRef ref, long lovelace) {
        return new TxInInfo(ref, new TxOut(GatewayFixtures.ledgerAddress(
                GatewayFixtures.enterpriseKeyAddress(filled(0xee, 28))), lovelace(lovelace),
                new OutputDatum.NoOutputDatum(), Optional.empty()));
    }

    static TxOut out(Address address, Value value, PlutusData datum) {
        return new TxOut(address, value,
                datum == null ? new OutputDatum.NoOutputDatum() : new OutputDatum.OutputDatumInline(datum),
                Optional.empty());
    }

    static TxOut payTo(byte[] rawAddress, Value value) {
        return out(GatewayFixtures.ledgerAddress(rawAddress), value, null);
    }

    // ------------------------------------------------------------------
    // Payout transactions (Settle, Exit, BalanceExit)

    static final TxOutRef VAULT_REF = ref(0x61, 0);
    static final TxOutRef VAULT_REF_2 = ref(0x61, 1);
    static final TxOutRef FEE_REF = ref(0x60, 0);

    static TxOutRef shardRef(int index) {
        return ref(0x62, index);
    }

    /** A payout transaction under construction; {@link #build} lays it out in the canonical order. */
    static final class Payout {
        AssetId asset = TOKEN;
        List<ClaimFx> claims = new ArrayList<>();
        int shard;
        ShardTrie shardTrie;
        List<byte[]> inserted;
        PlutusData vaultRedeemer;
        PlutusData mode = normalMode();
        PlutusData anchor;
        long extraLovelace = 10_000_000L;
        long extraQuantity = 1_000L;
        boolean floorTopUpInput;
        /** Further accepted vault inputs (floor top-ups) beyond the main one, carrying {@link #follow()}. */
        int followerInputs;
        int signers = THRESHOLD;
        long lowerBound = NOW;
        /** The value each payout carries beyond the claim; for the ADA vault this is extra lovelace. */
        long payoutSupplement;
        /** The debit of the vault for each claim: quantity and lovelace (normally the claim's). */
        List<ClaimFx> paidAs;

        Scenario build() {
            List<ClaimFx> paid = paidAs == null ? claims : paidAs;
            long quantity = 0;
            long lovelace = 0;
            for (ClaimFx claim : paid) {
                quantity += claim.quantity();
                lovelace += claim.vaultLovelace();
            }
            Tx tx = new Tx();
            long inLovelace = FLOOR + lovelace + extraLovelace + (asset.isAda() ? quantity : 0);
            long inQuantity = asset.isAda() ? 0 : quantity + extraQuantity;
            List<byte[]> ids = new ArrayList<>();
            for (ClaimFx claim : claims) {
                ids.add(claim.id());
            }
            if (floorTopUpInput) {
                tx.spend(vaultInput(VAULT_REF, assetValue(asset, inLovelace - FLOOR, inQuantity),
                        continuationDatum(List.of(shardId(3, 1)))), vaultRedeemer);
                tx.spend(vaultInput(VAULT_REF_2, lovelace(FLOOR), floorDatum()), vaultRedeemer);
            } else {
                tx.spend(vaultInput(VAULT_REF, assetValue(asset, inLovelace - followerInputs * FLOOR, inQuantity),
                        continuationDatum(List.of(shardId(3, 1)))), vaultRedeemer);
                for (int i = 0; i < followerInputs; i++) {
                    tx.spend(vaultInput(ref(0x61, 1 + i), lovelace(FLOOR), floorDatum()), follow());
                }
            }
            Inserts inserts = shardTrie.plan(inserted == null ? ids : inserted);
            tx.spend(new TxInInfo(shardRef(shard), new TxOut(GatewayFixtures.scriptAddress(SHARD_HASH),
                    lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {(byte) shard}, 1)),
                    new OutputDatum.OutputDatumInline(shardDatum(shard, inserts.before())), Optional.empty())),
                    inserts.redeemer());
            tx.spend(keyInput(FEE_REF, 50_000_000L), null);
            for (ClaimFx claim : paid) {
                tx.output(payTo(claim.recipient(), asset.isAda()
                        ? lovelace(claim.quantity() + payoutSupplement)
                        : assetValue(asset, claim.vaultLovelace() + payoutSupplement, claim.quantity())));
            }
            tx.output(out(vaultAddress(), asset.isAda() ? lovelace(inLovelace - quantity)
                    : assetValue(asset, inLovelace - lovelace, inQuantity - quantity), continuationDatum(ids)));
            tx.output(out(GatewayFixtures.scriptAddress(SHARD_HASH),
                    lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {(byte) shard}, 1)),
                    shardDatum(shard, inserts.after())));
            tx.output(payTo(GatewayFixtures.enterpriseKeyAddress(filled(0xee, 28)), lovelace(40_000_000L)));
            if (anchor != null) {
                tx.reference(anchorInput(anchor));
            }
            tx.reference(exitModeInput(mode));
            tx.signedByMembers(signers);
            tx.range = range(lowerBound, lowerBound + 600_000);
            return new Scenario(tx, VAULT_REF, shardRef(shard), inserts);
        }
    }

    record Scenario(Tx tx, TxOutRef vaultRef, TxOutRef shardRef, Inserts inserts) {
        PlutusData vault() {
            return tx.spending(vaultRef);
        }

        PlutusData shard() {
            return tx.spending(shardRef);
        }
    }

    static PlutusData settleRedeemer(List<ClaimFx> claims, int shard) {
        List<PlutusData> items = new ArrayList<>();
        for (ClaimFx claim : claims) {
            items.add(claim.data());
        }
        return PlutusData.constr(0, PlutusData.list(items.toArray(new PlutusData[0])), PlutusData.integer(shard));
    }

    static PlutusData exitRedeemer(List<ClaimFx> claims, List<PlutusData> proofs, PlutusData statusProof,
                                   int shard) {
        List<PlutusData> items = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            items.add(PlutusData.constr(0, claims.get(i).data(), proofs.get(i)));
        }
        return PlutusData.constr(1, PlutusData.list(items.toArray(new PlutusData[0])), statusProof,
                PlutusData.integer(shard));
    }

    /** A state trie holding {@code fill} filler leaves, the status leaf and every claim's leaf for {@code vault}. */
    static StateTrie stateWith(int fill, byte[] status, Hash32 vault, AssetId asset, List<ClaimFx> claims) {
        StateTrie state = new StateTrie().fill("ap/account/", fill).put(STATUS_KEY, status);
        for (ClaimFx claim : claims) {
            state.put(claim.key(), claim.leaf(vault, asset));
        }
        return state;
    }

    static List<PlutusData> claimProofs(StateTrie state, List<ClaimFx> claims) {
        List<PlutusData> proofs = new ArrayList<>();
        for (ClaimFx claim : claims) {
            proofs.add(state.proof(claim.key()));
        }
        return proofs;
    }

    // ------------------------------------------------------------------
    // Evaluation

    EvalResult run(Program program, PlutusData context) {
        return evaluate(program, context);
    }

    void ok(Program program, PlutusData context) {
        EvalResult result = evaluate(program, context);
        assertSuccess(result);
        assertBudgetUnder(result, MAX_TX_CPU, MAX_TX_MEM);
    }

    void rejected(Program program, PlutusData context) {
        assertFailure(evaluate(program, context));
    }

    static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] filled(int value, int length) {
        return GatewayFixtures.filled(value, length);
    }
}
