package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.eval.JulcTransactionEvaluator;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.Cip113Requirements;
import org.yanoproject.x.assets.contracts.Cip113Wiring;
import org.yanoproject.x.assets.contracts.Cip113WiringClaimV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.cat;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.filled;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.outpoint;

/**
 * The ADR-061 world of the support tests: the pinned CIP-113 v0.0.1 blueprint (the on-chain module's test copy,
 * sha256 checked) applied as a fixture deployment, the custody scripts derived by {@link ProgrammableBackend}, the
 * shard variant by {@link ShardBackend#deriveProgrammable}, and an L1 of resolved outputs. Assembled transactions are
 * evaluated with julc's phase-2 evaluator against every script they run.
 */
final class ProgrammableWorld {
    static final Path BLUEPRINT = Path.of("../asset-gateway-onchain/src/test/resources/cip113/plutus.json");
    static final String BLUEPRINT_SHA256 = "b6c8cb096a15e02f1b9c719fb8c617b624c1aa7719f2258d45faa3e8f144e7b9";
    static final int BOUND = 1024;
    static final byte[] EXIT_PREFIX = "ap/x/".getBytes(StandardCharsets.US_ASCII);
    static final AssetId ASSET_B = AssetId.of(filled(28, 0x7b), "EURM".getBytes(StandardCharsets.US_ASCII));
    static final AssetId UNREGISTERED = AssetId.of(filled(28, 0x7c), "JUNK".getBytes(StandardCharsets.US_ASCII));

    final Map<String, PlutusScript> scripts = new HashMap<>();
    final Map<ResolvedOutput.Outpoint, ResolvedOutput> utxos = new LinkedHashMap<>();
    final Map<String, BigInteger> rewards = new HashMap<>();
    final byte[] paramsPolicy;
    final byte[] registryPolicy;
    final byte[] plb;
    final byte[] dispatcher;
    final byte[] transfer;
    final byte[] thirdParty;
    final byte[] issuanceLogic;
    final byte[] hookA;
    final byte[] hookB;
    final ProgrammableVaultTxAssembler.Deployment deployment;
    final ProgrammableBackend.Parameters parameters;
    final ProgrammableBackend.Custody custody;
    final ShardBackend.ShardScript shard;
    final Cip113Wiring.ApprovedTuple tuple;
    final ResolvedOutput params;
    final List<ResolvedOutput> nodes;
    final PayoutWorld base = new PayoutWorld(PayoutWorld.TOKEN);

    ProgrammableWorld() throws Exception {
        this(ProgrammableBackend.JULC, ShardBackend.JULC, false);
    }

    /** {@code sharedHook}: both registered policies name the same hook credential. */
    ProgrammableWorld(ProgrammableBackend backend, ShardBackend shardBackend, boolean sharedHook) throws Exception {
        String text = blueprint();
        Program protocolParams = applied(text, "protocol_params.protocol_params.mint", seed(0xd1));
        paramsPolicy = hash(protocolParams);
        byte[] alwaysFail = hash(applied(text, "always_fail.always_fail.spend", PlutusData.bytes(new byte[0])));
        byte[] template = hash(applied(text, "issuance_cbor_hex_mint.issuance_cbor_hex_mint.mint", seed(0xd2),
                PlutusData.bytes(alwaysFail)));
        registryPolicy = hash(applied(text, "registry.registry.mint", seed(0xd3), PlutusData.bytes(template)));
        Program plbProgram = applied(text, "programmable_logic_base.programmable_logic_base.spend",
                PlutusData.bytes(paramsPolicy));
        plb = register(plbProgram);
        PlutusData plbCredential = PlutusData.constr(1, PlutusData.bytes(plb));
        transfer = register(applied(text, "transfer.transfer.withdraw", plbCredential, PlutusData.bytes(registryPolicy),
                PlutusData.integer(BOUND)));
        thirdParty = register(applied(text, "third_party.third_party.withdraw", plbCredential,
                PlutusData.bytes(registryPolicy), PlutusData.integer(BOUND)));
        dispatcher = register(applied(text, "programmable_logic_global.programmable_logic_global.withdraw",
                PlutusData.bytes(transfer), PlutusData.bytes(thirdParty), PlutusData.bytes(new byte[28])));
        issuanceLogic = hash(applied(text, "issuance_logic.issuance_logic.withdraw", plbCredential,
                PlutusData.bytes(registryPolicy), PlutusData.bytes(paramsPolicy), PlutusData.integer(BOUND)));
        hookA = register(hook(1));
        hookB = sharedHook ? hookA : register(hook(2));
        deployment = new ProgrammableVaultTxAssembler.Deployment(plb, paramsPolicy, registryPolicy, 0, BOUND);
        parameters = new ProgrammableBackend.Parameters(PayoutWorld.DOMAIN, PayoutWorld.TOKEN, 0, 0,
                base.pins.anchor(), PayoutWorld.SHARD_POLICY, PayoutWorld.EXIT_POLICY, PayoutWorld.EXIT_HASH,
                PayoutWorld.CLAIM_PREFIX, PayoutWorld.DEPOSIT_PREFIX, EXIT_PREFIX, PayoutWorld.STATUS_KEY, 16, 8,
                BigInteger.valueOf(PayoutWorld.FLOOR), 6 * PayoutWorld.HOUR, 30 * PayoutWorld.DAY,
                PayoutWorld.MAX_PAUSE, plb, BOUND);
        custody = backend.derive(parameters);
        scripts.put(HexFormat.of().formatHex(custody.custodyHash()), script(custody.custodyFlat()));
        scripts.put(HexFormat.of().formatHex(custody.exitDelegateHash()), script(custody.exitDelegateFlat()));
        shard = shardBackend.deriveProgrammable(PayoutWorld.SHARD_POLICY, custody.custodyHash(), 0);
        scripts.put(HexFormat.of().formatHex(shard.scriptHash()), script(shard.flat()));
        Cip113WiringClaimV1.Fields fields = new Cip113WiringClaimV1.Fields(dispatcher, issuanceLogic, transfer,
                thirdParty);
        tuple = new Cip113Wiring.ApprovedTuple(fields, transfer, thirdParty, new byte[28]);
        params = add(new ResolvedOutput(outpoint(0x6a, 0), cat(new byte[] {0x70}, paramsPolicy),
                BigInteger.valueOf(2_000_000), assets(AssetId.of(paramsPolicy,
                ProgrammableVaultTxAssembler.PROTOCOL_PARAMS_NAME), 1), Optional.of(paramsDatum(dispatcher)),
                Optional.empty()));
        nodes = List.of(
                add(node(0, new byte[0], PayoutWorld.TOKEN.policyId(), null)),
                add(node(1, PayoutWorld.TOKEN.policyId(), ASSET_B.policyId(), hookA)),
                add(node(2, ASSET_B.policyId(), filled(30, 0xff), hookB)));
        base.utxos.values().forEach(this::add);
    }

    // ------------------------------------------------------------------ L1 data

    byte[] paramsDatum(byte[] dispatcherHash) {
        return serialize(ConstrPlutusData.of(0, credential(dispatcherHash), credential(issuanceLogic),
                credential(transfer), credential(thirdParty), credential(filled(28, 0xc9)), ConstrPlutusData.of(1)));
    }

    ResolvedOutput node(int index, byte[] key, byte[] next, byte[] hook) {
        com.bloxbean.cardano.client.plutus.spec.PlutusData none = ConstrPlutusData.of(0, BytesPlutusData.of(new byte[0]));
        com.bloxbean.cardano.client.plutus.spec.PlutusData logic = hook == null ? none : credential(hook);
        byte[] datum = serialize(ConstrPlutusData.of(0, BytesPlutusData.of(key), BytesPlutusData.of(next), none, logic,
                none, none, BytesPlutusData.of(new byte[0])));
        return new ResolvedOutput(outpoint(0x6b, index), cat(new byte[] {0x70}, registryPolicy),
                BigInteger.valueOf(1_500_000), assets(AssetId.of(registryPolicy, key), 1), Optional.of(datum),
                Optional.empty());
    }

    /** The builder's L1: the registry by key or covering node, and every known output. */
    ProgrammableVaultTxAssembler.L1 l1() {
        return new ProgrammableVaultTxAssembler.L1() {
            @Override
            public Optional<Cip113Requirements.RegistryNode> node(byte[] policy) {
                return registryNodes().stream().filter(n -> java.util.Arrays.equals(n.key(), policy)).findFirst();
            }

            @Override
            public Optional<Cip113Requirements.RegistryNode> covering(byte[] policy) {
                return registryNodes().stream().filter(n -> n.covers(policy)).findFirst();
            }

            @Override
            public Optional<ResolvedOutput> resolve(ProgrammableDepositDatumV1.Outpoint outpoint) {
                return Optional.ofNullable(utxos.get(ProgrammableVaultTxAssembler.outpoint(outpoint)));
            }
        };
    }

    List<Cip113Requirements.RegistryNode> registryNodes() {
        return nodes.stream().map(out -> {
            var fields = ((ConstrPlutusData) deserialize(out.inlineDatum().orElseThrow())).getData().getPlutusDataList();
            ConstrPlutusData logic = (ConstrPlutusData) fields.get(3);
            byte[] logicHash = ((BytesPlutusData) logic.getData().getPlutusDataList().getFirst()).getValue();
            return new Cip113Requirements.RegistryNode(new ProgrammableDepositDatumV1.Outpoint(out.outpoint().txId(),
                    out.outpoint().index()), ((BytesPlutusData) fields.get(0)).getValue(),
                    ((BytesPlutusData) fields.get(1)).getValue(), new Cip113Requirements.Credential(
                    logic.getAlternative() == 1, logicHash.length == 28 ? logicHash : new byte[28]),
                    ((BytesPlutusData) fields.get(6)).getValue());
        }).toList();
    }

    /** The test substandard's adapter: an empty redeemer, no extra reference inputs. */
    Cip113Requirements.Adapter adapter() {
        return (policy, node) -> Optional.of(new Cip113Requirements.HookCall(serialize(ConstrPlutusData.of(0)),
                List.of()));
    }

    ProgrammableVaultTxAssembler.LiveWiring wiring() {
        return ProgrammableVaultTxAssembler.LiveWiring.of(deployment, params, List.of(tuple));
    }

    ProgrammableVaultTxAssembler.RewardBalances balances() {
        return hash -> rewards.getOrDefault(HexFormat.of().formatHex(hash), BigInteger.ZERO);
    }

    byte[] vaultWallet() {
        return custody.smartWallet();
    }

    /** The runtime pins of this CIP-113 vault: {@code h}, its smart wallet and the binding shard variant. */
    VaultPins pins() {
        VaultPins b = base.pins;
        return new VaultPins(vaultId(), PayoutWorld.TOKEN, 0, custody.custodyHash(), vaultWallet(),
                PayoutWorld.SHARD_POLICY, shard.scriptHash(), shard.address(), b.anchor(), PayoutWorld.EXIT_POLICY,
                PayoutWorld.EXIT_HASH, PayoutWorld.CLAIM_PREFIX, PayoutWorld.DEPOSIT_PREFIX, PayoutWorld.STATUS_KEY,
                BigInteger.valueOf(PayoutWorld.FLOOR), 16, 8, PayoutWorld.MAX_PAUSE, ShardBackend.JULC,
                Optional.of(new VaultPins.Programmable(deployment, custody.exitDelegateHash(), ProgrammableBackend.JULC,
                        params.address(), nodes.getFirst().address(), List.of(), Cip113L1.TEST_SUBSTANDARD)));
    }

    byte[] keyWallet(byte[] keyHash) {
        return ProgrammableBackend.keySmartWallet(plb, keyHash, 0);
    }

    ResolvedOutput add(ResolvedOutput out) {
        utxos.put(out.outpoint(), out);
        return out;
    }

    Hash32 vaultId() {
        return GatewayIds.vaultId(PayoutWorld.DOMAIN, PayoutWorld.TOKEN, 0);
    }

    // ------------------------------------------------------------------ evaluation

    JulcTransactionEvaluator evaluator() {
        UtxoSupplier supplier = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer count, Integer page, OrderEnum order) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int index) {
                return Optional.ofNullable(utxos.get(new ResolvedOutput.Outpoint(Hash32.ofHex(txHash), index)))
                        .map(PayoutTxBuilder::utxo);
            }
        };
        return new JulcTransactionEvaluator(supplier, () -> PayoutWorld.PARAMS,
                hash -> Optional.ofNullable(scripts.get(hash)), PayoutWorld.SLOTS);
    }

    /** Evaluates every script of {@code assembled}; fails with the evaluator's message unless all pass. */
    List<EvaluationResult> evaluate(ProgrammableVaultTxAssembler.Assembled assembled) throws Exception {
        Result<List<EvaluationResult>> result = evaluator().evaluateTx(assembled.cbor(),
                PayoutTxBuilder.utxos(assembled.resolved()));
        if (!result.isSuccessful()) throw new AssertionError("phase-2 evaluation failed: " + result.getResponse());
        return result.getValue();
    }

    // ------------------------------------------------------------------ helpers

    private static String blueprint() throws Exception {
        byte[] bytes = Files.readAllBytes(BLUEPRINT);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!sha.equals(BLUEPRINT_SHA256)) throw new IllegalStateException("not the pinned CIP-113 blueprint");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Program applied(String text, String title, PlutusData... params) {
        int at = text.indexOf("\"title\": \"" + title + "\"");
        Matcher code = Pattern.compile("\"compiledCode\"\\s*:\\s*\"([0-9a-f]+)\"").matcher(text);
        if (at < 0 || !code.find(at)) throw new IllegalArgumentException(title);
        byte[] single = HexFormat.of().parseHex(code.group(1));
        Program template = JulcScriptAdapter.toProgram(HexFormat.of().formatHex(FlatTemplates.cborBytes(single)));
        return template.applyParams(params);
    }

    /** A test transfer hook that always passes; {@code salt} names it. */
    private static Program hook(int salt) {
        return Program.plutusV3(Term.lam("salt", Term.lam("ctx", Term.const_(Constant.unit()))))
                .applyParams(PlutusData.bytes(new byte[] {(byte) salt}));
    }

    private byte[] register(Program program) {
        byte[] hash = hash(program);
        scripts.put(HexFormat.of().formatHex(hash), JulcScriptAdapter.fromProgram(program));
        return hash;
    }

    /** The exact flat program as a reference script, without re-encoding it. */
    static PlutusV3Script script(byte[] flat) {
        return PlutusV3Script.builder().type("PlutusScriptV3").cborHex(HexFormat.of().formatHex(
                FlatTemplates.cborBytes(FlatTemplates.cborBytes(flat)))).build();
    }

    private static byte[] hash(Program program) {
        return HexFormat.of().parseHex(JulcScriptAdapter.scriptHash(program));
    }

    private static PlutusData seed(int fill) {
        return PlutusData.constr(0, PlutusData.bytes(filled(32, fill)), PlutusData.integer(0));
    }

    static com.bloxbean.cardano.client.plutus.spec.PlutusData credential(byte[] scriptHash) {
        return ConstrPlutusData.of(1, BytesPlutusData.of(scriptHash));
    }

    static TreeMap<AssetId, BigInteger> assets(AssetId asset, long quantity) {
        TreeMap<AssetId, BigInteger> map = new TreeMap<>();
        if (quantity > 0) map.put(asset, BigInteger.valueOf(quantity));
        return map;
    }

    static byte[] serialize(com.bloxbean.cardano.client.plutus.spec.PlutusData data) {
        try {
            return data.serializeToBytes();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static com.bloxbean.cardano.client.plutus.spec.PlutusData deserialize(byte[] bytes) {
        try {
            return com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(bytes);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static BigIntPlutusData integer(long n) {
        return BigIntPlutusData.of(n);
    }
}
