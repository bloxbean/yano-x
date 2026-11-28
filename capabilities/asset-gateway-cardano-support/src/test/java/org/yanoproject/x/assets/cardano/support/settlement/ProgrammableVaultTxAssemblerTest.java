package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.Cip113Requirements;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.FEE_ADDRESS;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.NOW_MS;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.NOW_SLOT;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.PARAMS;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.TOKEN;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.cat;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.filled;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.outpoint;

/**
 * ADR-061 §10 P5 and P2: transactions assembled by {@link ProgrammableVaultTxAssembler} pass the real pinned CIP-113
 * scripts, the custody scripts and the shard variant under julc's phase-2 evaluator, with every index computed
 * against the final ledger order and every withdrawal at its live reward balance.
 */
class ProgrammableVaultTxAssemblerTest {
    @Test
    void theRuntimeDerivesTheCustodyScriptsAsJulcDoes() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        // h's own hash is in the vault smart wallet; e's hash is h's last parameter.
        assertThat(world.custody.smartWallet()[0]).isEqualTo((byte) 0x30);
        assertThat(java.util.Arrays.copyOfRange(world.custody.smartWallet(), 29, 57))
                .isEqualTo(world.custody.custodyHash());
        assertThatThrownBy(() -> ProgrammableBackend.JULC.requireDerived(world.parameters, filled(28, 1),
                world.custody.exitDelegateHash())).hasMessageContaining("script-hash");
        assertThatThrownBy(() -> ProgrammableBackend.JULC.requireDerived(world.parameters,
                world.custody.custodyHash(), filled(28, 1))).hasMessageContaining("exit-delegate-hash");
        assertThat(ProgrammableBackend.JULC.requireDerived(world.parameters, world.custody.custodyHash(),
                world.custody.exitDelegateHash()).custodyHash()).isEqualTo(world.custody.custodyHash());
        assertThat(ProgrammableBackend.parse(null)).isEqualTo(ProgrammableBackend.JULC);
        assertThatThrownBy(() -> ProgrammableBackend.parse("plutarch")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDerivedCustodyScriptsAreJulcsParameterApplication() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        java.nio.file.Path onchain = java.nio.file.Path.of("../asset-gateway-onchain/src/main/resources/META-INF/plutus");
        List<org.julclang.core.PlutusData> params = new ArrayList<>();
        ProgrammableBackend.Parameters p = world.parameters;
        for (byte[] data : p.delegateData()) {
            params.add(org.julclang.core.cbor.PlutusDataCborDecoder.decode(data));
        }
        org.julclang.core.Program delegate = template(onchain.resolve("ProgrammableVaultExitDelegate.plutus.json"))
                .applyParams(params.toArray(new org.julclang.core.PlutusData[0]));
        assertThat(HexFormat.of().formatHex(world.custody.exitDelegateHash()))
                .isEqualTo(org.julclang.clientlib.JulcScriptAdapter.scriptHash(delegate));
        params.add(org.julclang.core.PlutusData.bytes(world.custody.exitDelegateHash()));
        org.julclang.core.Program custody = template(onchain.resolve("ProgrammableVaultValidator.plutus.json"))
                .applyParams(params.toArray(new org.julclang.core.PlutusData[0]));
        assertThat(HexFormat.of().formatHex(world.custody.custodyHash()))
                .isEqualTo(org.julclang.clientlib.JulcScriptAdapter.scriptHash(custody));
        // The exact derived bytes, published as a reference script, hash to the derived hash.
        assertThat(HexFormat.of().formatHex(ProgrammableWorld.script(world.custody.exitDelegateFlat()).getScriptHash()))
                .isEqualTo(HexFormat.of().formatHex(world.custody.exitDelegateHash()));
        assertThat(HexFormat.of().formatHex(ProgrammableWorld.script(world.custody.custodyFlat()).getScriptHash()))
                .isEqualTo(HexFormat.of().formatHex(world.custody.custodyHash()));
    }

    @Test
    void bothBackendsDeriveJulcsParameterApplicationOfTheirTemplates() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        java.nio.file.Path onchain = java.nio.file.Path.of("../asset-gateway-onchain/src/main/resources/META-INF/plutus");
        for (ProgrammableBackend backend : ProgrammableBackend.values()) {
            String suffix = backend == ProgrammableBackend.AIKEN ? "Aiken" : "";
            ProgrammableBackend.Custody custody = backend.derive(world.parameters);
            List<org.julclang.core.PlutusData> params = new ArrayList<>();
            for (byte[] data : world.parameters.delegateData()) {
                params.add(org.julclang.core.cbor.PlutusDataCborDecoder.decode(data));
            }
            assertThat(HexFormat.of().formatHex(custody.exitDelegateHash())).as("%s e", backend)
                    .isEqualTo(org.julclang.clientlib.JulcScriptAdapter.scriptHash(template(onchain.resolve(
                            "ProgrammableVaultExitDelegate" + suffix + ".plutus.json")).applyParams(
                            params.toArray(new org.julclang.core.PlutusData[0]))));
            params.add(org.julclang.core.PlutusData.bytes(custody.exitDelegateHash()));
            assertThat(HexFormat.of().formatHex(custody.custodyHash())).as("%s h", backend)
                    .isEqualTo(org.julclang.clientlib.JulcScriptAdapter.scriptHash(template(onchain.resolve(
                            "ProgrammableVaultValidator" + suffix + ".plutus.json")).applyParams(
                            params.toArray(new org.julclang.core.PlutusData[0]))));
            ShardBackend shardBackend = backend == ProgrammableBackend.AIKEN ? ShardBackend.AIKEN : ShardBackend.JULC;
            assertThat(HexFormat.of().formatHex(shardBackend.deriveProgrammable(PayoutWorld.SHARD_POLICY,
                    custody.custodyHash(), 0).scriptHash())).as("%s shard", backend)
                    .isEqualTo(org.julclang.clientlib.JulcScriptAdapter.scriptHash(template(onchain.resolve(
                            "ProgrammableShardValidator" + suffix + ".plutus.json")).applyParams(
                            org.julclang.core.PlutusData.bytes(PayoutWorld.SHARD_POLICY),
                            org.julclang.core.PlutusData.bytes(custody.custodyHash()))));
        }
        assertThat(ProgrammableBackend.AIKEN.derive(world.parameters).custodyHash())
                .isNotEqualTo(world.custody.custodyHash());
    }

    @Test
    void anAssembledSettlePassesEveryScriptUnderTheAikenBackend() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld(ProgrammableBackend.AIKEN, ShardBackend.AIKEN, false);
        assertThat(world.evaluate(settle(world, 4, 3))).hasSize(6);
        assertThat(world.evaluate(ProgrammableDepositBuilderTest.deposit(world,
                ProgrammableDepositBuilderTest.single(TOKEN, 1_000)).assembled())).hasSize(4);
    }

    static org.julclang.core.Program template(java.nio.file.Path file) throws Exception {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"")
                .matcher(java.nio.file.Files.readString(file));
        assertThat(matcher.find()).isTrue();
        return org.julclang.clientlib.JulcScriptAdapter.toProgram(matcher.group(1));
    }

    @Test
    void anAssembledSettlePassesEveryScript() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        ProgrammableVaultTxAssembler.Assembled settle = settle(world, 4, 3);
        List<EvaluationResult> results = world.evaluate(settle);
        // PLB once, the shard, the dispatcher, the transfer delegate, one hook and h.
        assertThat(results).hasSize(6);
        assertThat(settle.requirements().proofs()).hasSize(1);
        assertThat(settle.requirements().hooks()).hasSize(1);
        assertThat(settle.indexes().withdrawalOrder()).hasSize(4);
        System.out.printf("ASSEMBLED|Settle|4|%d bytes|fee %s%n", settle.cbor().length, settle.fee());
    }

    @Test
    void positiveRewardBalancesAreWithdrawnInFullAndGoToChange() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        world.rewards.put(HexFormat.of().formatHex(world.custody.custodyHash()), BigInteger.valueOf(3_000_000));
        world.rewards.put(HexFormat.of().formatHex(world.dispatcher), BigInteger.valueOf(1_000_000));
        ProgrammableVaultTxAssembler.Assembled withRewards = settle(world, 1, 5);
        world.evaluate(withRewards);
        assertThat(withRewards.withdrawalAmounts()).containsEntry("0" + HexFormat.of().formatHex(
                world.custody.custodyHash()), BigInteger.valueOf(3_000_000)).containsEntry("0"
                + HexFormat.of().formatHex(world.dispatcher), BigInteger.valueOf(1_000_000));
        ProgrammableWorld plain = new ProgrammableWorld();
        ProgrammableVaultTxAssembler.Assembled without = settle(plain, 1, 5);
        List<TransactionOutput> a = withRewards.transaction().getBody().getOutputs();
        List<TransactionOutput> b = without.transaction().getBody().getOutputs();
        BigInteger changeDelta = a.getLast().getValue().getCoin().subtract(b.getLast().getValue().getCoin());
        BigInteger feeDelta = withRewards.fee().subtract(without.fee());
        assertThat(changeDelta.add(feeDelta)).isEqualTo(BigInteger.valueOf(4_000_000));
    }

    @Test
    void driftInTheDispatcherFailsClosed() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        ResolvedOutput moved = new ResolvedOutput(world.params.outpoint(), world.params.address(),
                world.params.lovelace(), world.params.assets(), Optional.of(world.paramsDatum(filled(28, 0x44))),
                Optional.empty());
        assertThatThrownBy(() -> ProgrammableVaultTxAssembler.LiveWiring.of(world.deployment, moved,
                List.of(world.tuple))).isInstanceOf(ProgrammableVaultTxAssembler.Drift.class);
        ResolvedOutput lookAlike = new ResolvedOutput(world.params.outpoint(), world.params.address(),
                world.params.lovelace(), new java.util.TreeMap<>(), world.params.inlineDatum(), Optional.empty());
        assertThatThrownBy(() -> ProgrammableVaultTxAssembler.LiveWiring.of(world.deployment, lookAlike,
                List.of(world.tuple))).isInstanceOf(ProgrammableVaultTxAssembler.Drift.class);
    }

    @Test
    void anIssuanceOnlyChangeDoesNotBlockAssembly() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        // Field 1 moves, the dispatcher does not: a vault transaction still assembles and passes.
        byte[] datum = ProgrammableWorld.serialize(ConstrPlutusData.of(0, ProgrammableWorld.credential(world.dispatcher),
                ProgrammableWorld.credential(filled(28, 0x45)), ProgrammableWorld.credential(world.transfer),
                ProgrammableWorld.credential(world.thirdParty), ProgrammableWorld.credential(filled(28, 0xc9)),
                ConstrPlutusData.of(1)));
        ResolvedOutput rolled = world.add(new ResolvedOutput(world.params.outpoint(), world.params.address(),
                world.params.lovelace(), world.params.assets(), Optional.of(datum), Optional.empty()));
        ProgrammableVaultTxAssembler.LiveWiring wiring = ProgrammableVaultTxAssembler.LiveWiring.of(world.deployment,
                rolled, List.of(world.tuple));
        world.evaluate(settle(world, 1, 7, wiring));
    }

    @Test
    void sharedHookCredentialsMergeIntoOneWithdrawal() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld(ProgrammableBackend.JULC, ShardBackend.JULC, true);
        Map<byte[], Map<byte[], BigInteger>> value = new java.util.LinkedHashMap<>();
        value.put(TOKEN.policyId(), Map.of(TOKEN.assetName(), BigInteger.TEN));
        value.put(ProgrammableWorld.ASSET_B.policyId(), Map.of(ProgrammableWorld.ASSET_B.assetName(), BigInteger.ONE));
        value.put(ProgrammableWorld.UNREGISTERED.policyId(), Map.of(ProgrammableWorld.UNREGISTERED.assetName(),
                BigInteger.TWO));
        Cip113Requirements requirements = Cip113Requirements.of(List.of(value), world.dispatcher, world.transfer,
                world.l1(), world.adapter());
        assertThat(requirements.proofs()).extracting(Cip113Requirements.Proof::registered)
                .containsExactly(true, true, false);
        assertThat(requirements.hooks()).hasSize(1);
        assertThat(requirements.hooks().getFirst().policies()).hasSize(2);
        // Two nodes and the covering node of the unregistered token (node B covers it): node B appears once.
        assertThat(requirements.references()).hasSize(2);
        Cip113Requirements.Adapter disagreeing = (policy, node) -> Optional.of(new Cip113Requirements.HookCall(
                ProgrammableWorld.serialize(ConstrPlutusData.of(policy[0] == 0x7a ? 0 : 1)), List.of()));
        assertThatThrownBy(() -> Cip113Requirements.of(List.of(value), world.dispatcher, world.transfer, world.l1(),
                disagreeing)).isInstanceOf(Cip113Requirements.Unassemblable.class);
    }

    @Test
    void lovelaceOnlyInputsNeedNoProofAndNoHook() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        Cip113Requirements requirements = Cip113Requirements.of(List.of(Map.of()), world.dispatcher, world.transfer,
                world.l1(), world.adapter());
        assertThat(requirements.proofs()).isEmpty();
        assertThat(requirements.hooks()).isEmpty();
    }

    // ------------------------------------------------------------------

    static ProgrammableVaultTxAssembler.Assembled settle(ProgrammableWorld world, int k, int shard) {
        return settle(world, k, shard, world.wiring());
    }

    static ProgrammableVaultTxAssembler.Assembled settle(ProgrammableWorld world, int k, int shard,
                                                         ProgrammableVaultTxAssembler.LiveWiring wiring) {
        return settle(world, k, shard, wiring, world.base.fee, null);
    }

    /** A {@code Settle} funded by {@code fee}, with {@code extraReference} (a reference-script UTxO) when given. */
    static ProgrammableVaultTxAssembler.Assembled settle(ProgrammableWorld world, int k, int shard,
                                                         ProgrammableVaultTxAssembler.LiveWiring wiring,
                                                         ResolvedOutput fee, ResolvedOutput extraReference) {
        ResolvedOutput vault = world.add(new ResolvedOutput(outpoint(0x61, 0), world.vaultWallet(),
                BigInteger.valueOf(40_000_000), ProgrammableWorld.assets(TOKEN, 1_000_000),
                Optional.of(new byte[] {(byte) 0xd8, 0x7a, (byte) 0x81, (byte) 0x80}), Optional.empty()));
        ShardMirror mirror = new ShardMirror(shard);
        ResolvedOutput shardIn = world.add(new ResolvedOutput(outpoint(0x62, shard), world.shard.address(),
                BigInteger.valueOf(2_000_000), ProgrammableWorld.assets(AssetId.of(PayoutWorld.SHARD_POLICY,
                new byte[] {(byte) shard}), 1), Optional.of(shardDatum(shard, mirror.root())), Optional.empty()));
        Random random = new Random(61 + k);
        List<ClaimLeafV1> claims = new ArrayList<>();
        List<Hash32> ids = new ArrayList<>();
        long quantity = 0;
        long lovelace = 0;
        for (int i = 0; i < k; i++) {
            byte[] id = new byte[32];
            random.nextBytes(id);
            id[31] = (byte) ((id[31] & 0xf0) | shard);
            ClaimLeafV1 leaf = new ClaimLeafV1(Hash32.of(id), world.vaultId(), ClaimKind.USER_WITHDRAWAL,
                    AssetValue.single(TOKEN, BigInteger.valueOf(500 + i)), BigInteger.valueOf(1_500_000),
                    world.keyWallet(filled(28, 0x80 + i)), 100 + i, NOW_MS - 2 * PayoutWorld.DAY);
            claims.add(leaf);
            ids.add(leaf.claimId());
            quantity += 500 + i;
            lovelace += 1_500_000;
        }
        ShardMirror.Plan plan = mirror.plan(ids);
        List<TransactionOutput> outputs = new ArrayList<>();
        for (ClaimLeafV1 leaf : claims) {
            outputs.add(PayoutTxBuilder.output(leaf.recipient(), leaf.vaultLovelace(),
                    Map.of(TOKEN, leaf.value().quantityOf(TOKEN)), null));
        }
        outputs.add(PayoutTxBuilder.output(world.vaultWallet(), vault.lovelace().subtract(BigInteger.valueOf(lovelace)),
                Map.of(TOKEN, BigInteger.valueOf(1_000_000 - quantity)), GatewayPlutus.continuation(ids)));
        outputs.add(PayoutTxBuilder.output(world.shard.address(), BigInteger.valueOf(2_000_000),
                Map.of(AssetId.of(PayoutWorld.SHARD_POLICY, new byte[] {(byte) shard}), BigInteger.ONE),
                GatewayPlutus.shardDatum(shard, plan.after())));
        ProgrammableVaultTxAssembler.Plan txPlan = new ProgrammableVaultTxAssembler.Plan(List.of(vault),
                List.of(new ProgrammableVaultTxAssembler.ScriptSpend(shardIn, GatewayPlutus.shardRedeemer(plan.inserts()))),
                new PayoutTxBuilder.Funding(List.of(fee), FEE_ADDRESS), outputs,
                extraReference == null ? List.of(world.base.anchor, world.base.exitMode)
                        : List.of(world.base.anchor, world.base.exitMode, extraReference),
                List.of(new ProgrammableVaultTxAssembler.CustodyWithdrawal(world.custody.custodyHash(),
                        GatewayPlutus.settle(claims, TOKEN, shard))),
                PayoutWorld.signers(2), NOW_SLOT, NOW_SLOT + 600);
        return ProgrammableVaultTxAssembler.assemble(world.deployment, wiring, txPlan, world.l1(), world.adapter(),
                world.balances(), PARAMS, world.evaluator());
    }

    static byte[] shardDatum(int shard, byte[] root) {
        return cat(new byte[] {(byte) 0xd8, 0x79, (byte) 0x83, 0x01, (byte) shard, 0x58, 0x20}, root);
    }
}
