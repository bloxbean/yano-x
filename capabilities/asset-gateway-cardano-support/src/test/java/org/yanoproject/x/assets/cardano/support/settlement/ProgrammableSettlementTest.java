package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.Cip113Requirements;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.assets.contracts.SettlementRecords;
import org.yanoproject.x.assets.contracts.VaultOutputShape;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.DAY;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.FEE_ADDRESS;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.NOW_MS;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.NOW_SLOT;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.PARAMS;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.TOKEN;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.filled;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.outpoint;

/**
 * ADR-061 D5, D10, D13: the owner's CIP-113 {@code Settle} and co-signed {@code Accept} pass the pinned CIP-113
 * scripts and the custody scripts, and a member signs them only after the whole check (059-D8h with the D13 changes):
 * a held asset's {@code Settle} is refused, and a co-signed {@code Accept} is checked by origin identity.
 */
class ProgrammableSettlementTest {
    static final Map<Hash32, SettlementQueries.ClaimView> NONE = Map.of();

    @Test
    void anOwnerSettlePassesEveryScriptAndTheMembersWholeCheck() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        Settle settle = settle(world, 3);
        world.evaluate(settle.assembled());
        SettleBodyCheck.Outcome outcome = checkSettle(world, settle, vaultId -> Optional.of(false));
        assertThat(outcome).as(String.valueOf(outcome)).isInstanceOf(SettleBodyCheck.Signable.class);
        assertThat(((SettleBodyCheck.Signable) outcome).verified().claimIds())
                .containsExactlyElementsOf(settle.claims().stream().map(ClaimLeafV1::claimId).toList());
    }

    /** D14, Q8: members refuse a {@code Settle} while the asset is in ISSUER_HOLD, and when they cannot tell. */
    @Test
    void aHeldAssetsSettleIsRefused() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        Settle settle = settle(world, 1);
        assertThat(refusal(checkSettle(world, settle, vaultId -> Optional.of(true)))).contains("ISSUER_HOLD");
        assertThat(refusal(checkSettle(world, settle, vaultId -> Optional.empty()))).contains("ISSUER_HOLD");
    }

    /** D13, finding 3: a re-created deposit is accepted by its origin, never compared with its live outpoint. */
    @Test
    void aCosignedAcceptOfAMovedDepositIsCheckedByOrigin() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        ProgrammableDepositDatumV1 datum = new ProgrammableDepositDatumV1(filled(28, 0xb5), world.keyWallet(
                filled(28, 0xa7)), filled(28, 0xa7), outpoint(0x30, 0).txId(), 0, 0);
        // The live output is a no-op re-spend's copy at another outpoint; the record keeps the original as history.
        ResolvedOutput moved = world.add(new ResolvedOutput(outpoint(0x31, 2), world.vaultWallet(),
                BigInteger.valueOf(2_500_000), ProgrammableWorld.assets(TOKEN, 600), Optional.of(datum.encode()),
                Optional.empty()));
        Hash32 id = datum.depositId(world.vaultId());
        AcceptTxBuilder.Built built = ProgrammableSettlement.acceptCosigned(acceptContext(world), cip113(world),
                List.of(moved), PayoutWorld.signers(2), world.evaluator());
        assertThat(built.depositIds()).containsExactly(id);
        world.evaluate(new ProgrammableVaultTxAssembler.Assembled(built.transaction(), built.cbor(), built.txId(),
                built.resolvedInputs(), null, null, built.fee(), Map.of()));
        Map<Hash32, DepositRecordV1> records = new HashMap<>();
        records.put(id, record(world, id, DepositRecordV1.Status.CREDITED, 900));
        assertThat(checkAccept(world, built, records)).isInstanceOf(SettleBodyCheck.Signable.class);
        // Not CREDITED, or credited above the referenced anchor: refused.
        records.put(id, record(world, id, DepositRecordV1.Status.UNCREDITED, 900));
        assertThat(refusal(checkAccept(world, built, records))).contains("not CREDITED");
        records.put(id, record(world, id, DepositRecordV1.Status.CREDITED, 5_000));
        assertThat(refusal(checkAccept(world, built, records))).contains("above the referenced anchor");
    }

    /** Codex P2-9, Fable P3-4: the PLB outputs, reference scripts, redeemer indexes and h's redeemer. */
    @Test
    void theStructuralCheckMatchesTheActionsLayout() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        Transaction honest = settle(world, 2).built().transaction();
        assertThat(ProgrammableBodyCheck.check(honest, ProgrammableBodyCheckTest.view(world))).isEmpty();

        // A payout moved after the continuation: a PLB output out of its position.
        Transaction swapped = copy(honest);
        List<TransactionOutput> outputs = new ArrayList<>(swapped.getBody().getOutputs());
        outputs.add(2, outputs.remove(1));
        swapped.getBody().setOutputs(outputs);
        assertThat(ProgrammableBodyCheck.check(swapped, ProgrammableBodyCheckTest.view(world)))
                .anyMatch(r -> r.contains("neither a payout nor the continuation"));

        // A redeemer index past its list is a refusal, never an exception.
        Transaction pastEnd = copy(honest);
        pastEnd.getWitnessSet().getRedeemers().add(Redeemer.builder().tag(RedeemerTag.Reward)
                .index(BigInteger.valueOf(99)).data(ConstrPlutusData.of(0)).exUnits(
                        pastEnd.getWitnessSet().getRedeemers().getFirst().getExUnits()).build());
        pastEnd.getWitnessSet().getRedeemers().add(Redeemer.builder().tag(RedeemerTag.Spend)
                .index(BigInteger.valueOf(Long.MAX_VALUE)).data(ConstrPlutusData.of(0)).exUnits(
                        pastEnd.getWitnessSet().getRedeemers().getFirst().getExUnits()).build());
        assertThat(ProgrammableBodyCheck.check(pastEnd, ProgrammableBodyCheckTest.view(world)))
                .anyMatch(r -> r.contains("points at no withdrawal")).anyMatch(r -> r.contains("points at no input"));

        // h's redeemer must be one of the co-signed actions.
        Transaction foreignAction = copy(honest);
        PlutusDataSwap.custody(foreignAction, world.custody.custodyHash(), ConstrPlutusData.of(9));
        assertThat(ProgrammableBodyCheck.check(foreignAction, ProgrammableBodyCheckTest.view(world)))
                .anyMatch(r -> r.contains("neither Settle nor AcceptCosigned"));

        // A reference-script UTxO is allowed only for a script the transaction runs.
        ResolvedOutput runsH = world.add(new ResolvedOutput(outpoint(0x6c, 0), FEE_ADDRESS,
                BigInteger.valueOf(30_000_000), new java.util.TreeMap<>(), Optional.empty(),
                Optional.of(new ResolvedOutput.ReferenceScript(world.custody.custodyHash(), 100, Optional.empty()))));
        ResolvedOutput runsNothing = world.add(new ResolvedOutput(outpoint(0x6c, 1), FEE_ADDRESS,
                BigInteger.valueOf(30_000_000), new java.util.TreeMap<>(), Optional.empty(),
                Optional.of(new ResolvedOutput.ReferenceScript(filled(28, 0x6c), 100, Optional.empty()))));
        Transaction withRef = copy(honest);
        withRef.getBody().setReferenceInputs(new ArrayList<>(withRef.getBody().getReferenceInputs()));
        withRef.getBody().getReferenceInputs().add(PayoutTxBuilder.input(runsH.outpoint()));
        assertThat(ProgrammableBodyCheck.check(withRef, ProgrammableBodyCheckTest.view(world))).isEmpty();
        withRef.getBody().getReferenceInputs().add(PayoutTxBuilder.input(runsNothing.outpoint()));
        assertThat(ProgrammableBodyCheck.check(withRef, ProgrammableBodyCheckTest.view(world)))
                .anyMatch(r -> r.contains("not one the transaction needs"));
    }

    /** Codex P2-10: a key-credential transfer hook is refused before anything is built. */
    @Test
    void aKeyCredentialHookIsRefusedBeforeBuilding() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        ProgrammableVaultTxAssembler.L1 base = world.l1();
        ProgrammableVaultTxAssembler.L1 keyHooked = new ProgrammableVaultTxAssembler.L1() {
            @Override
            public Optional<Cip113Requirements.RegistryNode> node(byte[] policy) {
                return base.node(policy).map(node -> new Cip113Requirements.RegistryNode(node.outpoint(), node.key(),
                        node.next(), new Cip113Requirements.Credential(false, filled(28, 0x4b)),
                        node.globalStateCs()));
            }

            @Override
            public Optional<Cip113Requirements.RegistryNode> covering(byte[] policy) {
                return base.covering(policy);
            }

            @Override
            public Optional<ResolvedOutput> resolve(ProgrammableDepositDatumV1.Outpoint outpoint) {
                return base.resolve(outpoint);
            }
        };
        assertThatThrownBy(() -> settle(world, 1, new ProgrammableSettlement.Cip113(world.wiring(), keyHooked,
                world.adapter(), world.balances()), world.base.fee))
                .isInstanceOf(PayoutTxBuilder.BuildException.class).hasMessageContaining("key credential");
    }

    /** Codex P2-11: the CIP-113 assembler refuses a token-bearing funding input, as the native builder does. */
    @Test
    void aTokenBearingFeeUtxoIsRefused() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        ResolvedOutput tokenFee = world.add(new ResolvedOutput(outpoint(0x60, 7), FEE_ADDRESS,
                BigInteger.valueOf(80_000_000), ProgrammableWorld.assets(AssetId.of(filled(28, 0x99), new byte[] {1}),
                5), Optional.empty(), Optional.empty()));
        assertThatThrownBy(() -> settle(world, 1, cip113(world), tokenFee))
                .isInstanceOf(PayoutTxBuilder.BuildException.class).hasMessageContaining("lovelace only");
    }

    /** Codex P1-3: a key credential in the params datum's field 1 still lets every transfer-only build assemble. */
    @Test
    void aKeyIssuanceLogicStillAssemblesTransferOnlyTransactions() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        byte[] datum = ProgrammableWorld.serialize(ConstrPlutusData.of(0, ProgrammableWorld.credential(world.dispatcher),
                ConstrPlutusData.of(0, com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(world.issuanceLogic)),
                ProgrammableWorld.credential(world.transfer), ProgrammableWorld.credential(world.thirdParty),
                ProgrammableWorld.credential(filled(28, 0xc9)), ConstrPlutusData.of(1)));
        ResolvedOutput keyed = world.add(new ResolvedOutput(world.params.outpoint(), world.params.address(),
                world.params.lovelace(), world.params.assets(), Optional.of(datum), Optional.empty()));
        ProgrammableVaultTxAssembler.LiveWiring wiring = ProgrammableVaultTxAssembler.LiveWiring.of(world.deployment,
                keyed, List.of(world.tuple));
        assertThat(wiring.fields()).hasValueSatisfying(f -> assertThat(f.script(1)).isFalse());
        Settle settle = settle(world, 1, new ProgrammableSettlement.Cip113(wiring, world.l1(), world.adapter(),
                world.balances()), world.base.fee);
        world.evaluate(settle.assembled());
        // The user's deposit needs the exact approved fields, and these differ in field 1's kind.
        assertThatThrownBy(() -> ProgrammableDepositBuilderTest.deposit(world, wiring,
                ProgrammableDepositBuilderTest.single(TOKEN, 1_000)))
                .isInstanceOf(ProgrammableVaultTxAssembler.Drift.class);
    }

    /** Fable P3-1: a byte parameter over one Plutus chunk (64 bytes) is refused. */
    @Test
    void aCustodyByteParameterOverSixtyFourBytesIsRefused() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        ProgrammableBackend.Parameters p = world.parameters;
        assertThatThrownBy(() -> new ProgrammableBackend.Parameters(p.domainId(), p.asset(), 0, 0, p.anchor(),
                p.shardPolicyId(), p.exitModePolicyId(), p.exitModeScriptHash(), new byte[65], p.depositKeyPrefix(),
                p.exitKeyPrefix(), p.domainStatusKey(), 16, 8, p.continuationFloor(), p.exitDelayFloorMs(),
                p.exitDelayCeilingMs(), p.maxPauseMs(), p.plbHash(), p.maxInlineDatumBytes()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64 bytes");
        new ProgrammableBackend.Parameters(p.domainId(), p.asset(), 0, 0, p.anchor(), p.shardPolicyId(),
                p.exitModePolicyId(), p.exitModeScriptHash(), new byte[64], p.depositKeyPrefix(), p.exitKeyPrefix(),
                p.domainStatusKey(), 16, 8, p.continuationFloor(), p.exitDelayFloorMs(), p.exitDelayCeilingMs(),
                p.maxPauseMs(), p.plbHash(), p.maxInlineDatumBytes());
    }

    /**
     * Codex P1-4: the runtime reconciliation of a CIP-113 vault counts only spendable custody; a top-up holding the
     * asset and a copied credited datum carrying a foreign token are unavailable, and the shortfall is flagged.
     */
    @Test
    void theRuntimeReconciliationOfACip113VaultCountsOnlySpendableCustody() throws Exception {
        ProgrammableWorld world = new ProgrammableWorld();
        VaultPins pins = world.pins();
        ProgrammableDepositDatumV1 datum = new ProgrammableDepositDatumV1(filled(28, 0xb5), world.keyWallet(
                filled(28, 0xa7)), filled(28, 0xa7), outpoint(0x30, 0).txId(), 0, 0);
        Hash32 id = datum.depositId(world.vaultId());
        java.util.TreeMap<AssetId, BigInteger> mixed = ProgrammableWorld.assets(TOKEN, 300);
        mixed.put(AssetId.of(filled(28, 0x99), new byte[] {1}), BigInteger.ONE);
        List<ResolvedOutput> outputs = List.of(
                new ResolvedOutput(outpoint(0x61, 0), world.vaultWallet(), BigInteger.valueOf(5_000_000),
                        ProgrammableWorld.assets(TOKEN, 1_000), Optional.of(new byte[] {(byte) 0xd8, 0x7a,
                        (byte) 0x81, (byte) 0x80}), Optional.empty()),
                new ResolvedOutput(outpoint(0x61, 1), world.vaultWallet(), BigInteger.valueOf(2_000_000),
                        ProgrammableWorld.assets(TOKEN, 700), Optional.of(new byte[] {(byte) 0xd8, 0x7b, (byte) 0x80}),
                        Optional.empty()),
                new ResolvedOutput(outpoint(0x61, 2), world.vaultWallet(), BigInteger.valueOf(2_500_000),
                        ProgrammableWorld.assets(TOKEN, 500), Optional.of(datum.encode()), Optional.empty()),
                new ResolvedOutput(outpoint(0x61, 3), world.vaultWallet(), BigInteger.valueOf(2_500_000), mixed,
                        Optional.of(datum.encode()), Optional.empty()));
        assertThat(ProgrammableSettlement.depositIdOf(pins, outputs.get(3))).contains(id);
        assertThat(ProgrammableSettlement.depositId(pins, outputs.get(3))).isEmpty();
        DepositRecordV1 credited = record(world, id, DepositRecordV1.Status.CREDITED, 900);
        org.yanoproject.x.assets.contracts.AssetAccounts.VaultCounters counters =
                new org.yanoproject.x.assets.contracts.AssetAccounts.VaultCounters(BigInteger.valueOf(2_500),
                        BigInteger.ZERO, BigInteger.valueOf(2_500), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                        BigInteger.ZERO, BigInteger.ZERO);
        VaultReconciliation.Report report = VaultReconciliation.reconcileProgrammable(VaultReconciliation.Vault.of(
                pins), new VaultReconciliation.Consensus(900, counters, depositId -> depositId.equals(id)
                ? Optional.of(credited) : Optional.empty(), List.of(), java.util.Set.of()),
                new VaultReconciliation.L1Scan(Optional.of(new VaultReconciliation.L1Point(1, "ab".repeat(32))),
                        outputs, true), world.plb, 0);
        VaultReconciliation.Unit unit = report.units().getFirst();
        assertThat(unit.accepted()).isEqualTo(BigInteger.valueOf(1_500));
        assertThat(unit.unattributed()).isEqualTo(BigInteger.valueOf(1_000));
        assertThat(unit.difference()).isEqualTo(BigInteger.valueOf(-1_000));
        assertThat(report.verdict()).isEqualTo(VaultReconciliation.Verdict.DISCREPANCY);
    }

    // ------------------------------------------------------------------ fixtures

    record Settle(PayoutTxBuilder.Built built, List<ClaimLeafV1> claims) {
        ProgrammableVaultTxAssembler.Assembled assembled() {
            return new ProgrammableVaultTxAssembler.Assembled(built.transaction(), built.cbor(), built.txId(),
                    built.resolvedInputs(), null, null, built.fee(), Map.of());
        }
    }

    static Settle settle(ProgrammableWorld world, int k) {
        return settle(world, k, cip113(world), world.base.fee);
    }

    static Settle settle(ProgrammableWorld world, int k, ProgrammableSettlement.Cip113 cip113, ResolvedOutput fee) {
        int shard = 4;
        ResolvedOutput vault = world.add(new ResolvedOutput(outpoint(0x61, 0), world.vaultWallet(),
                BigInteger.valueOf(40_000_000), ProgrammableWorld.assets(TOKEN, 1_000_000),
                Optional.of(new byte[] {(byte) 0xd8, 0x7a, (byte) 0x81, (byte) 0x80}), Optional.empty()));
        ShardMirror mirror = new ShardMirror(shard);
        ResolvedOutput shardIn = world.add(new ResolvedOutput(outpoint(0x62, shard), world.shard.address(),
                BigInteger.valueOf(2_000_000), ProgrammableWorld.assets(AssetId.of(PayoutWorld.SHARD_POLICY,
                new byte[] {(byte) shard}), 1), Optional.of(ProgrammableVaultTxAssemblerTest.shardDatum(shard,
                mirror.root())), Optional.empty()));
        List<ClaimLeafV1> claims = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            byte[] id = filled(32, 0x20 + i);
            id[31] = (byte) ((id[31] & 0xf0) | shard);
            claims.add(new ClaimLeafV1(Hash32.of(id), world.vaultId(), ClaimKind.USER_WITHDRAWAL,
                    AssetValue.single(TOKEN, BigInteger.valueOf(500 + i)), BigInteger.valueOf(1_500_000),
                    world.keyWallet(filled(28, 0x80 + i)), 100 + i, NOW_MS - 2 * DAY));
        }
        PayoutTxBuilder.Context context = new PayoutTxBuilder.Context(world.pins(), List.of(vault), shardIn,
                world.base.anchor, world.base.exitMode, List.of(),
                new PayoutTxBuilder.Funding(List.of(fee), FEE_ADDRESS), NOW_SLOT, NOW_SLOT + 600, PARAMS, 8);
        PayoutTxBuilder.Built built = ProgrammableSettlement.settle(context, cip113, claims, mirror,
                PayoutWorld.signers(2), world.evaluator());
        return new Settle(built, claims);
    }

    static ProgrammableSettlement.Cip113 cip113(ProgrammableWorld world) {
        return new ProgrammableSettlement.Cip113(world.wiring(), world.l1(), world.adapter(), world.balances());
    }

    static AcceptTxBuilder.Context acceptContext(ProgrammableWorld world) {
        return new AcceptTxBuilder.Context(world.pins(), world.base.anchor, world.base.exitMode, List.of(),
                new PayoutTxBuilder.Funding(List.of(world.base.fee), FEE_ADDRESS), NOW_SLOT, NOW_SLOT + 600, PARAMS);
    }

    static SettleBodyCheck.Outcome checkSettle(ProgrammableWorld world, Settle settle,
                                               ProgrammableBodyCheck.Holds holds) {
        Map<Hash32, SettlementQueries.ClaimView> views = new HashMap<>();
        for (ClaimLeafV1 leaf : settle.claims()) {
            views.put(leaf.claimId(), new SettlementQueries.ClaimView(leaf, SettlementRecords.ClaimStatusV1.reserved(
                    leaf.claimId(), leaf.reservedHeight())));
        }
        SettleBodyCheck.Committed committed = new SettleBodyCheck.Committed() {
            @Override
            public Optional<SettlementQueries.ClaimView> claim(Hash32 claimId) {
                return Optional.ofNullable(views.get(claimId));
            }

            @Override
            public Optional<DomainStatusV1> domainStatus() {
                return Optional.of(new DomainStatusV1(DomainStatusV1.Status.ACTIVE, 50, 0, DAY));
            }
        };
        return ProgrammableBodyCheck.checkSettle(settle.built().cbor(), world.pins(),
                ProgrammableBodyCheckTest.view(world), filled(28, 0x01), view(world), committed, holds,
                PayoutTxBuilder.costModels(PARAMS), PayoutWorld.tip());
    }

    static SettleBodyCheck.Outcome checkAccept(ProgrammableWorld world, AcceptTxBuilder.Built built,
                                               Map<Hash32, DepositRecordV1> records) {
        return ProgrammableBodyCheck.checkAccept(built.cbor(), world.pins(), ProgrammableBodyCheckTest.view(world),
                filled(28, 0x01), view(world), depositId -> Optional.ofNullable(records.get(depositId)),
                PayoutTxBuilder.costModels(PARAMS), PayoutWorld.tip());
    }

    static Function<ResolvedOutput.Outpoint, Optional<ResolvedOutput>> view(ProgrammableWorld world) {
        return outpoint -> Optional.ofNullable(world.utxos.get(outpoint));
    }

    static DepositRecordV1 record(ProgrammableWorld world, Hash32 id, DepositRecordV1.Status status, long height) {
        boolean credited = status == DepositRecordV1.Status.CREDITED;
        return new DepositRecordV1(id, world.vaultId(), outpoint(0x40, 0).txId(), 0, 1_000, height,
                VaultOutputShape.DEPOSIT, status, credited ? DepositAdmission.Reason.NONE
                : DepositAdmission.Reason.UNKNOWN_ACTION, filled(28, 0xb5), BigInteger.valueOf(600),
                BigInteger.valueOf(2_500_000), 0, Hash32.of(filled(32, 0x5e)),
                credited ? BigInteger.valueOf(600) : BigInteger.ZERO,
                credited ? BigInteger.valueOf(2_500_000) : BigInteger.ZERO);
    }

    static String refusal(SettleBodyCheck.Outcome outcome) {
        assertThat(outcome).isInstanceOf(SettleBodyCheck.Refused.class);
        return ((SettleBodyCheck.Refused) outcome).refusal().detail();
    }

    static Transaction copy(Transaction tx) throws Exception {
        Transaction copy = Transaction.deserialize(tx.serialize());
        copy.getWitnessSet().setRedeemers(new ArrayList<>(copy.getWitnessSet().getRedeemers()));
        return copy;
    }

    /** Replaces {@code h}'s reward redeemer in place. */
    static final class PlutusDataSwap {
        static void custody(Transaction tx, byte[] custodyHash, com.bloxbean.cardano.client.plutus.spec.PlutusData data) {
            com.bloxbean.cardano.client.plutus.spec.PlutusData current = ProgrammableBodyCheck.custodyRedeemer(tx,
                    custodyHash);
            for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
                if (redeemer.getTag() == RedeemerTag.Reward && redeemer.getData().equals(current)) {
                    redeemer.setData(data);
                }
            }
        }
    }
}
