package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.Interval;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.StakingCredential;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.vm.EvalResult;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The world of the ADR-061 custody tests: the pinned CIP-113 deployment ({@link Cip113Blueprint}), test transfer
 * hooks, the compiled and applied custody scripts {@code h} and {@code e} and the shard binding variant, all with
 * their real script hashes, and a full-transaction model that evaluates <b>every</b> script a vault transaction runs
 * (PLB per programmable input, the dispatcher, the transfer delegate, each hook, {@code h}, {@code e} and the shard)
 * in one script context (§10 P2).
 */
abstract class ProgrammableTestBase extends GatewayTestBase {
    static final Cip113Blueprint.Deployment CIP = Cip113Blueprint.DEVNET;
    static final byte[] PLB = CIP.plbHash();
    static final long NETWORK = 0;

    /** The vault's asset, a second registered policy and an unregistered one, in ascending policy order. */
    static final AssetId ASSET = TOKEN;
    static final AssetId ASSET_B = AssetId.of(filled(0x7b, 28), "EURM".getBytes());
    static final AssetId UNREGISTERED = AssetId.of(filled(0x7c, 28), "JUNK".getBytes());
    static final byte[] SENTINEL = filled(0xff, 30);

    static final Hash32 VAULT_ID = GatewayIds.vaultId(DOMAIN, ASSET, 0);

    // ------------------------------------------------------------------
    // Programs

    /** A test transfer-logic hook: refuses any PLB input or output owned by {@code frozen}; {@code salt} names it. */
    static final String HOOK_SOURCE = """
            package org.yanoproject.x.assets.onchain;

            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.ledger.TxInInfo;
            import org.julclang.ledger.TxOut;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.MultiValidator;
            import org.julclang.stdlib.annotation.Param;

            @MultiValidator
            public final class TestTransferHook {
                @Param
                static byte[] plbHash;
                @Param
                static byte[] salt;
                @Param
                static byte[] frozen;

                @Entrypoint
                public static boolean validate(PlutusData redeemer, ScriptContext context) {
                    PlutusData info = context.scriptInfo().toPlutusData();
                    long purpose = Builtins.constrTag(info);
                    if (purpose == 3) {
                        return Builtins.constrTag(Builtins.headList(Builtins.tailList(Builtins.constrFields(info))))
                                == 0;
                    }
                    return purpose == 2 && inputsClear(context.txInfo().inputs())
                            && outputsClear(context.txInfo().outputs());
                }

                private static boolean inputsClear(JulcList<TxInInfo> inputs) {
                    if (inputs.isEmpty()) {
                        return true;
                    }
                    return !frozenAt(inputs.head().resolved().address().toPlutusData())
                            && inputsClear(inputs.tail());
                }

                private static boolean outputsClear(JulcList<TxOut> outputs) {
                    if (outputs.isEmpty()) {
                        return true;
                    }
                    return !frozenAt(outputs.head().address().toPlutusData()) && outputsClear(outputs.tail());
                }

                private static boolean frozenAt(PlutusData address) {
                    PlutusData fields = Builtins.constrFields(address);
                    PlutusData payment = Builtins.headList(fields);
                    PlutusData stake = Builtins.headList(Builtins.tailList(fields));
                    if (Builtins.constrTag(payment) != 1 || Builtins.constrTag(stake) != 0
                            || !Builtins.equalsByteString(
                            Builtins.unBData(Builtins.headList(Builtins.constrFields(payment))), plbHash)) {
                        return false;
                    }
                    PlutusData staking = Builtins.headList(Builtins.constrFields(stake));
                    return Builtins.constrTag(staking) == 0 && Builtins.equalsByteString(Builtins.unBData(
                            Builtins.headList(Builtins.constrFields(Builtins.headList(
                            Builtins.constrFields(staking))))), frozen);
                }
            }
            """;

    private static final Map<String, Program> HOOKS = new LinkedHashMap<>();

    synchronized Program hook(int salt, byte[] frozen) {
        return HOOKS.computeIfAbsent(salt + ":" + HexFormat.of().formatHex(frozen), ignored -> compile(HOOK_SOURCE)
                .applyParams(PlutusData.bytes(PLB), PlutusData.bytes(new byte[] {(byte) salt}),
                        PlutusData.bytes(frozen)));
    }

    /** The {@code Settle} batch cap of the custody profile; a test may raise it to reach the V16 id cap. */
    static int settleCap = 16;

    static PlutusData[] delegateParams(AssetId asset) {
        PlutusData[] vault = vaultParams(asset);
        vault[19] = PlutusData.integer(settleCap);
        PlutusData[] params = Arrays.copyOf(vault, vault.length + 2);
        params[vault.length] = PlutusData.bytes(PLB);
        params[vault.length + 1] = PlutusData.integer(Cip113Blueprint.MAX_INLINE_DATUM_BYTES);
        return params;
    }

    static Program exitDelegate() {
        return program(ProgrammableVaultExitDelegate.class, delegateParams(ASSET));
    }

    static byte[] exitDelegateHash() {
        return Cip113Blueprint.hash(exitDelegate());
    }

    static Program custody() {
        PlutusData[] base = delegateParams(ASSET);
        PlutusData[] params = Arrays.copyOf(base, base.length + 1);
        params[base.length] = PlutusData.bytes(exitDelegateHash());
        return program(ProgrammableVaultValidator.class, params);
    }

    static byte[] custodyHash() {
        return Cip113Blueprint.hash(custody());
    }

    static Program programmableShard() {
        return program(ProgrammableShardValidator.class, PlutusData.bytes(SHARD_POLICY),
                PlutusData.bytes(custodyHash()));
    }

    static byte[] programmableShardHash() {
        return Cip113Blueprint.hash(programmableShard());
    }

    // The Aiken backend (ADR-061 D3): the committed templates, applied with the julc scripts' parameters.

    static final java.nio.file.Path AIKEN_CUSTODY_ARTIFACT =
            java.nio.file.Path.of("src/main/resources/META-INF/plutus/ProgrammableVaultValidatorAiken.plutus.json");
    static final java.nio.file.Path AIKEN_EXIT_DELEGATE_ARTIFACT = java.nio.file.Path.of(
            "src/main/resources/META-INF/plutus/ProgrammableVaultExitDelegateAiken.plutus.json");
    static final java.nio.file.Path AIKEN_PROGRAMMABLE_SHARD_ARTIFACT =
            java.nio.file.Path.of("src/main/resources/META-INF/plutus/ProgrammableShardValidatorAiken.plutus.json");

    private static final Map<String, Program> AIKEN = new java.util.concurrent.ConcurrentHashMap<>();

    static Program aikenProgram(java.nio.file.Path artifact, PlutusData... params) {
        String key = artifact + Arrays.toString(params);
        return AIKEN.computeIfAbsent(key, ignored -> {
            Program template = aikenTemplate(artifact);
            return params.length == 0 ? template : template.applyParams(params);
        });
    }

    /** The Aiken {@code e} with the julc {@code e}'s parameters. */
    static Program aikenExitDelegate() {
        return aikenProgram(AIKEN_EXIT_DELEGATE_ARTIFACT, delegateParams(ASSET));
    }

    /** The Aiken {@code h} with the julc {@code h}'s parameters, the julc {@code e}'s hash included. */
    static Program aikenCustody() {
        PlutusData[] base = delegateParams(ASSET);
        PlutusData[] params = Arrays.copyOf(base, base.length + 1);
        params[base.length] = PlutusData.bytes(exitDelegateHash());
        return aikenProgram(AIKEN_CUSTODY_ARTIFACT, params);
    }

    /** The Aiken shard variant with the julc variant's parameters (the julc {@code h}'s hash). */
    static Program aikenProgrammableShard() {
        return aikenProgram(AIKEN_PROGRAMMABLE_SHARD_ARTIFACT, PlutusData.bytes(SHARD_POLICY),
                PlutusData.bytes(custodyHash()));
    }

    // ------------------------------------------------------------------
    // Addresses

    /** The vault smart wallet {@code base(Script(PLB), Script(h))}. */
    static Address vaultWallet() {
        return smartWalletOf(new Credential.ScriptCredential(new ScriptHash(custodyHash())));
    }

    static Address smartWalletOf(Credential owner) {
        return new Address(new Credential.ScriptCredential(new ScriptHash(PLB)),
                Optional.of(new StakingCredential.StakingHash(owner)));
    }

    /** The raw bytes of the key-owned smart wallet of {@code keyHash}: {@code 0x10 ‖ PLB ‖ keyHash}. */
    static byte[] smartWallet(byte[] keyHash) {
        return GatewayFixtures.concat(GatewayFixtures.concat(new byte[] {(byte) (0x10 | NETWORK)}, PLB), keyHash);
    }

    static Address ledger(byte[] raw) {
        return GatewayFixtures.ledgerAddress(raw);
    }

    // ------------------------------------------------------------------
    // Data

    static PlutusData continuation(List<byte[]> ids) {
        return continuationDatum(ids);
    }

    /** {@code ProgrammableDepositDatumV1}. */
    static PlutusData programmableDeposit(byte[] refundAddress, byte[] refundKeyHash, byte[] originTx, long originIndex,
                                          long outputIndex) {
        return PlutusData.constr(0, PlutusData.bytes(filled(0xa1, 28)), PlutusData.bytes(refundAddress),
                PlutusData.bytes(refundKeyHash), PlutusData.constr(0, PlutusData.bytes(originTx),
                        PlutusData.integer(originIndex), PlutusData.integer(outputIndex)));
    }

    static Hash32 programmableDepositId(byte[] originTx, long originIndex, long outputIndex) {
        return GatewayIds.programmableDepositId(VAULT_ID, Hash32.of(originTx), originIndex, outputIndex);
    }

    static PlutusData registryNode(byte[] key, byte[] next, PlutusData transferLogic) {
        PlutusData none = Cip113Blueprint.keyCredential(new byte[0]);
        return PlutusData.constr(0, PlutusData.bytes(key), PlutusData.bytes(next), none, transferLogic, none, none,
                PlutusData.bytes(new byte[0]));
    }

    static TxInInfo registryNodeInput(TxOutRef ref, byte[] key, PlutusData datum) {
        return new TxInInfo(ref, new TxOut(GatewayFixtures.scriptAddress(CIP.registryNodePolicy()),
                lovelace(1_500_000).merge(token(CIP.registryNodePolicy(), key, 1)),
                new OutputDatum.OutputDatumInline(datum), Optional.empty()));
    }

    static TxInInfo paramsInput(PlutusData datum) {
        return new TxInInfo(ref(0x6a, 0), new TxOut(GatewayFixtures.scriptAddress(CIP.paramsPolicy()),
                lovelace(2_000_000).merge(token(CIP.paramsPolicy(), "ProtocolParams".getBytes(), 1)),
                new OutputDatum.OutputDatumInline(datum), Optional.empty()));
    }

    static TxInInfo walletInput(TxOutRef ref, Value value, PlutusData datum) {
        return new TxInInfo(ref, new TxOut(vaultWallet(), value,
                datum == null ? new OutputDatum.NoOutputDatum() : new OutputDatum.OutputDatumInline(datum),
                Optional.empty()));
    }

    static PlutusData baseSpend(int paramsIdx, int wdrlIdx) {
        return PlutusData.constr(0, PlutusData.integer(paramsIdx), PlutusData.integer(wdrlIdx));
    }

    static PlutusData transferAct() {
        return PlutusData.constr(0);
    }

    // ------------------------------------------------------------------
    // The full transaction

    /** A withdrawal of {@code amount} from {@code credential}, with its reward redeemer. */
    record Withdrawal(PlutusData credential, BigInteger amount, PlutusData redeemer) {
    }

    /**
     * One transaction, every script of it. {@link #finish} computes the PLB spend redeemers and the transfer
     * delegate's proofs against the final ledger order, unless a test fixed them.
     */
    final class FullTx {
        final List<Spent> inputs = new ArrayList<>();
        final List<TxInInfo> references = new ArrayList<>();
        final List<TxOut> outputs = new ArrayList<>();
        final List<PubKeyHash> signers = new ArrayList<>();
        final Map<String, Withdrawal> withdrawals = new LinkedHashMap<>();
        final List<PlutusData> certificates = new ArrayList<>();
        final Map<String, Program> scripts = new LinkedHashMap<>();
        Value mint = Value.zero();
        Interval range = range(NOW, NOW + 600_000);
        /** Set by a test to keep a hand-made redeemer for a PLB input or the transfer delegate. */
        boolean manualCip113Redeemers;

        FullTx() {
            script(PLB, CIP.plb());
            script(CIP.dispatcherHash(), CIP.dispatcher());
            script(CIP.transferHash(), CIP.transfer());
            script(CIP.thirdPartyHash(), CIP.thirdParty());
            script(custodyHash(), custody());
            script(exitDelegateHash(), exitDelegate());
            script(programmableShardHash(), programmableShard());
        }

        FullTx script(byte[] hash, Program program) {
            scripts.put(HexFormat.of().formatHex(hash), program);
            return this;
        }

        FullTx spend(TxInInfo input, PlutusData redeemer) {
            inputs.add(new Spent(input, redeemer));
            return this;
        }

        FullTx reference(TxInInfo input) {
            references.add(input);
            return this;
        }

        FullTx output(TxOut out) {
            outputs.add(out);
            return this;
        }

        FullTx withdraw(PlutusData credential, long amount, PlutusData redeemer) {
            withdrawals.put(key(credential), new Withdrawal(credential, BigInteger.valueOf(amount), redeemer));
            return this;
        }

        FullTx withdrawScript(byte[] hash, long amount, PlutusData redeemer) {
            return withdraw(Cip113Blueprint.scriptCredential(hash), amount, redeemer);
        }

        FullTx removeWithdrawal(byte[] hash) {
            withdrawals.remove(key(Cip113Blueprint.scriptCredential(hash)));
            return this;
        }

        PlutusData redeemerOf(byte[] hash) {
            return withdrawals.get(key(Cip113Blueprint.scriptCredential(hash))).redeemer();
        }

        FullTx signedByMembers(int count) {
            for (int i = 0; i < count; i++) {
                signers.add(keyHash(MEMBERS.get(i)));
            }
            return this;
        }

        List<TxInInfo> sortedInputs() {
            List<TxInInfo> sorted = new ArrayList<>();
            inputs.forEach(s -> sorted.add(s.input()));
            sorted.sort(OUTREF_ORDER);
            return sorted;
        }

        List<TxInInfo> sortedReferences() {
            List<TxInInfo> sorted = new ArrayList<>(references);
            sorted.sort(OUTREF_ORDER);
            return sorted;
        }

        /** Withdrawals in ledger order: script credentials before key credentials, bytewise within each. */
        List<Withdrawal> sortedWithdrawals() {
            List<Withdrawal> sorted = new ArrayList<>(withdrawals.values());
            sorted.sort(Comparator.comparing((Withdrawal w) -> ((PlutusData.ConstrData) w.credential()).tag() == 1 ? 0
                    : 1).thenComparing(w -> hex(credentialHash(w.credential()))));
            return sorted;
        }

        /**
         * The PLB spend redeemers ({@code params_idx}, {@code wdrl_idx}) and the transfer delegate's
         * {@code TransferRedeemer}: one proof per distinct non-ADA policy of the PLB inputs, ascending, each naming
         * the reference input of its registry node ({@code TokenExists}) or covering node
         * ({@code TokenDoesNotExist}).
         */
        FullTx finish() {
            if (manualCip113Redeemers) {
                return this;
            }
            List<TxInInfo> refs = sortedReferences();
            int paramsIdx = -1;
            for (int i = 0; i < refs.size(); i++) {
                if (hasPolicy(refs.get(i).resolved().value(), CIP.paramsPolicy())) {
                    paramsIdx = i;
                }
            }
            List<Withdrawal> sorted = sortedWithdrawals();
            int wdrlIdx = -1;
            for (int i = 0; i < sorted.size(); i++) {
                if (Arrays.equals(credentialHash(sorted.get(i).credential()), CIP.dispatcherHash())) {
                    wdrlIdx = i;
                }
            }
            for (int i = 0; i < inputs.size(); i++) {
                Spent spent = inputs.get(i);
                if (Arrays.equals(paymentHash(spent.input().resolved().address()), PLB)) {
                    inputs.set(i, new Spent(spent.input(), baseSpend(paramsIdx, wdrlIdx)));
                }
            }
            if (withdrawals.containsKey(key(Cip113Blueprint.scriptCredential(CIP.transferHash())))) {
                withdrawScript(CIP.transferHash(), withdrawals.get(key(Cip113Blueprint.scriptCredential(
                        CIP.transferHash()))).amount().longValue(), transferRedeemer(refs));
            }
            return this;
        }

        private PlutusData transferRedeemer(List<TxInInfo> refs) {
            java.util.TreeSet<String> policies = new java.util.TreeSet<>();
            for (Spent spent : inputs) {
                TxOut out = spent.input().resolved();
                if (Arrays.equals(paymentHash(out.address()), PLB)) {
                    for (byte[] policy : policiesOf(out.value())) {
                        policies.add(hex(policy));
                    }
                }
            }
            List<PlutusData> proofs = new ArrayList<>();
            for (String policy : policies) {
                byte[] id = HexFormat.of().parseHex(policy);
                int exact = -1;
                int covering = -1;
                for (int i = 0; i < refs.size(); i++) {
                    Optional<byte[][]> node = nodeKeys(refs.get(i));
                    if (node.isEmpty()) {
                        continue;
                    }
                    byte[] key = node.get()[0];
                    byte[] next = node.get()[1];
                    if (Arrays.equals(key, id)) {
                        exact = i;
                    } else if (Arrays.compareUnsigned(key, id) < 0 && Arrays.compareUnsigned(id, next) < 0) {
                        covering = i;
                    }
                }
                proofs.add(exact >= 0 ? PlutusData.constr(0, PlutusData.integer(exact))
                        : PlutusData.constr(1, PlutusData.integer(covering)));
            }
            return PlutusData.constr(0, PlutusData.list(proofs.toArray(new PlutusData[0])));
        }

        PlutusData txInfo() {
            List<PlutusData> ins = new ArrayList<>();
            sortedInputs().forEach(i -> ins.add(canonicalInput(i.toPlutusData())));
            List<PlutusData> refs = new ArrayList<>();
            sortedReferences().forEach(i -> refs.add(canonicalInput(i.toPlutusData())));
            List<PlutusData> outs = new ArrayList<>();
            outputs.forEach(o -> outs.add(canonicalOutput(o.toPlutusData())));
            List<PlutusData.Pair> wdrl = new ArrayList<>();
            for (Withdrawal w : sortedWithdrawals()) {
                wdrl.add(new PlutusData.Pair(w.credential(), PlutusData.integer(w.amount())));
            }
            List<PlutusData> sigs = new ArrayList<>();
            signers.forEach(s -> sigs.add(PlutusData.bytes(s.hash())));
            List<PlutusData.Pair> redeemers = new ArrayList<>();
            for (Spent spent : sortedSpent()) {
                if (spent.redeemer() != null) {
                    redeemers.add(new PlutusData.Pair(PlutusData.constr(1, spent.input().outRef().toPlutusData()),
                            spent.redeemer()));
                }
            }
            for (Withdrawal w : sortedWithdrawals()) {
                if (w.redeemer() != null) {
                    redeemers.add(new PlutusData.Pair(PlutusData.constr(2, w.credential()), w.redeemer()));
                }
            }
            for (int i = 0; i < certificates.size(); i++) {
                redeemers.add(new PlutusData.Pair(PlutusData.constr(3, PlutusData.integer(i), certificates.get(i)),
                        PlutusData.constr(0)));
            }
            PlutusData none = PlutusData.constr(1);
            return PlutusData.constr(0, PlutusData.list(ins.toArray(new PlutusData[0])),
                    PlutusData.list(refs.toArray(new PlutusData[0])), PlutusData.list(outs.toArray(new PlutusData[0])),
                    PlutusData.integer(400_000), mintData(), PlutusData.list(certificates.toArray(new PlutusData[0])),
                    new PlutusData.MapData(wdrl), range.toPlutusData(),
                    PlutusData.list(sigs.toArray(new PlutusData[0])), new PlutusData.MapData(redeemers),
                    new PlutusData.MapData(List.of()), PlutusData.bytes(filled(0x99, 32)),
                    new PlutusData.MapData(List.of()), PlutusData.list(), none, none);
        }

        private PlutusData mintData() {
            PlutusData data = canonicalValue(mint.toPlutusData());
            if (data instanceof PlutusData.MapData map && !map.entries().isEmpty()) {
                // A V3 mint has no ADA entry.
                List<PlutusData.Pair> entries = new ArrayList<>();
                for (PlutusData.Pair pair : map.entries()) {
                    if (!(pair.key() instanceof PlutusData.BytesData b && b.value().length == 0)) {
                        entries.add(pair);
                    }
                }
                return new PlutusData.MapData(entries);
            }
            return new PlutusData.MapData(List.of());
        }

        private List<Spent> sortedSpent() {
            List<Spent> sorted = new ArrayList<>(inputs);
            sorted.sort(Comparator.comparing((Spent s) -> s.input(), OUTREF_ORDER));
            return sorted;
        }

        /** Every script run of the transaction: name and result. */
        Map<String, EvalResult> evaluate() {
            Map<String, EvalResult> results = new LinkedHashMap<>();
            for (ScriptRun run : runs()) {
                results.put(run.name(), run(run.program(), run.context()));
            }
            return results;
        }

        /** Every script run of the transaction, with its script context, in {@link #evaluate} order. */
        List<ScriptRun> runs() {
            PlutusData info = txInfo();
            List<ScriptRun> runs = new ArrayList<>();
            for (Spent spent : sortedSpent()) {
                byte[] hash = paymentHash(spent.input().resolved().address());
                Program program = hash == null ? null : scripts.get(hex(hash));
                if (program == null) {
                    continue;
                }
                PlutusData datum = spent.input().resolved().datum() instanceof OutputDatum.OutputDatumInline inline
                        ? PlutusData.constr(0, inline.datum()) : PlutusData.constr(1);
                PlutusData scriptInfo = PlutusData.constr(1, spent.input().outRef().toPlutusData(), datum);
                runs.add(new ScriptRun("spend:" + name(hash) + "#" + spent.input().outRef().index() + "@"
                        + hex(spent.input().outRef().txId().hash()).substring(0, 4), hash, program,
                        PlutusData.constr(0, info, spent.redeemer() == null ? PlutusData.constr(0) : spent.redeemer(),
                                scriptInfo)));
            }
            for (Withdrawal w : sortedWithdrawals()) {
                byte[] hash = credentialHash(w.credential());
                Program program = scripts.get(hex(hash));
                if (program == null || ((PlutusData.ConstrData) w.credential()).tag() != 1) {
                    continue;
                }
                runs.add(new ScriptRun("withdraw:" + name(hash), hash, program, PlutusData.constr(0, info,
                        w.redeemer() == null ? PlutusData.constr(0) : w.redeemer(),
                        PlutusData.constr(2, w.credential()))));
            }
            for (int i = 0; i < certificates.size(); i++) {
                PlutusData cert = certificates.get(i);
                byte[] hash = credentialHash(((PlutusData.ConstrData) cert).fields().get(0));
                Program program = scripts.get(hex(hash));
                if (program != null) {
                    runs.add(new ScriptRun("publish:" + name(hash) + "#" + i, hash, program, PlutusData.constr(0,
                            info, PlutusData.constr(0), PlutusData.constr(3, PlutusData.integer(i), cert))));
                }
            }
            return runs;
        }

        String name(byte[] hash) {
            if (Arrays.equals(hash, PLB)) return "plb";
            if (Arrays.equals(hash, CIP.dispatcherHash())) return "dispatcher";
            if (Arrays.equals(hash, CIP.transferHash())) return "transfer";
            if (Arrays.equals(hash, CIP.thirdPartyHash())) return "thirdParty";
            if (Arrays.equals(hash, custodyHash())) return "h";
            if (Arrays.equals(hash, exitDelegateHash())) return "e";
            if (Arrays.equals(hash, programmableShardHash())) return "shard";
            return "hook-" + hex(hash).substring(0, 6);
        }
    }

    /** One script run of a transaction: its name, the script's hash, the script and the full script context. */
    record ScriptRun(String name, byte[] hash, Program program, PlutusData context) {
    }

    static final Comparator<TxInInfo> OUTREF_ORDER = Comparator
            .comparing((TxInInfo r) -> HexFormat.of().formatHex(r.outRef().txId().hash()))
            .thenComparing(r -> r.outRef().index());

    /** The transaction passes: every script it runs succeeds, within the per-transaction budget. */
    void passes(FullTx tx) {
        Map<String, EvalResult> results = tx.finish().evaluate();
        long cpu = 0;
        long mem = 0;
        for (Map.Entry<String, EvalResult> entry : results.entrySet()) {
            assertThat(entry.getValue().isSuccess()).as("%s passes: %s", entry.getKey(), entry.getValue()).isTrue();
            cpu += entry.getValue().budgetConsumed().cpuSteps();
            mem += entry.getValue().budgetConsumed().memoryUnits();
        }
        assertThat(cpu).as("transaction cpu").isLessThan(MAX_TX_CPU);
        assertThat(mem).as("transaction memory").isLessThan(MAX_TX_MEM);
    }

    /** The transaction fails, and {@code script} is among the scripts that fail. */
    void failsIn(FullTx tx, String script) {
        Map<String, EvalResult> results = tx.finish().evaluate();
        List<String> failed = new ArrayList<>();
        results.forEach((name, result) -> {
            if (!result.isSuccess()) {
                failed.add(name);
            }
        });
        assertThat(failed).as("failing scripts of %s", results.keySet()).anyMatch(name -> name.contains(script));
    }

    /** The transaction fails in exactly the scripts named. */
    void failsOnlyIn(FullTx tx, String... scripts) {
        Map<String, EvalResult> results = tx.finish().evaluate();
        List<String> failed = new ArrayList<>();
        results.forEach((name, result) -> {
            if (!result.isSuccess()) {
                failed.add(name.substring(name.indexOf(':') + 1).replaceAll("#.*", ""));
            }
        });
        assertThat(failed).as("failing scripts").containsExactlyInAnyOrder(scripts);
    }

    /** The total measured budget of an honest transaction, per script. */
    Map<String, EvalResult> measure(FullTx tx) {
        Map<String, EvalResult> results = tx.finish().evaluate();
        results.forEach((name, result) -> assertThat(result.isSuccess()).as("%s passes: %s", name, result)
                .isTrue());
        return results;
    }

    static PlutusData canonicalInput(PlutusData input) {
        List<PlutusData> fields = ((PlutusData.ConstrData) input).fields();
        return PlutusData.constr(0, fields.get(0), canonicalOutput(fields.get(1)));
    }

    /** A {@code TxOut} whose value map is in ledger order: ADA first, then policies and names bytewise. */
    static PlutusData canonicalOutput(PlutusData output) {
        List<PlutusData> fields = new ArrayList<>(((PlutusData.ConstrData) output).fields());
        fields.set(1, canonicalValue(fields.get(1)));
        return PlutusData.constr(0, fields.toArray(new PlutusData[0]));
    }

    static PlutusData canonicalValue(PlutusData value) {
        List<PlutusData.Pair> policies = new ArrayList<>();
        for (PlutusData.Pair policy : ((PlutusData.MapData) value).entries()) {
            List<PlutusData.Pair> names = new ArrayList<>(((PlutusData.MapData) policy.value()).entries());
            names.sort(Comparator.comparing(p -> ((PlutusData.BytesData) p.key()).value(), Arrays::compareUnsigned));
            policies.add(new PlutusData.Pair(policy.key(), new PlutusData.MapData(names)));
        }
        policies.sort(Comparator.comparing(p -> ((PlutusData.BytesData) p.key()).value(), Arrays::compareUnsigned));
        return new PlutusData.MapData(policies);
    }

    static String key(PlutusData credential) {
        return ((PlutusData.ConstrData) credential).tag() + ":" + hex(credentialHash(credential));
    }

    static byte[] credentialHash(PlutusData credential) {
        return ((PlutusData.BytesData) ((PlutusData.ConstrData) credential).fields().get(0)).value();
    }

    static byte[] paymentHash(Address address) {
        return address.credential() instanceof Credential.ScriptCredential script ? script.hash().hash() : null;
    }

    static boolean hasPolicy(Value value, byte[] policy) {
        for (byte[] candidate : policiesOf(value)) {
            if (Arrays.equals(candidate, policy)) {
                return true;
            }
        }
        return false;
    }

    static List<byte[]> policiesOf(Value value) {
        List<byte[]> policies = new ArrayList<>();
        for (PlutusData.Pair pair : ((PlutusData.MapData) value.toPlutusData()).entries()) {
            byte[] policy = ((PlutusData.BytesData) pair.key()).value();
            if (policy.length > 0) {
                policies.add(policy);
            }
        }
        return policies;
    }

    /** The {@code (key, next)} of a registry-node reference input, by its token. */
    static Optional<byte[][]> nodeKeys(TxInInfo input) {
        if (!hasPolicy(input.resolved().value(), CIP.registryNodePolicy())
                || !(input.resolved().datum() instanceof OutputDatum.OutputDatumInline inline)) {
            return Optional.empty();
        }
        List<PlutusData> fields = ((PlutusData.ConstrData) inline.datum()).fields();
        return Optional.of(new byte[][] {((PlutusData.BytesData) fields.get(0)).value(),
                ((PlutusData.BytesData) fields.get(1)).value()});
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    // ------------------------------------------------------------------
    // Honest transactions of every action

    static final TxOutRef W1 = ref(0x61, 0);
    static final TxOutRef W2 = ref(0x61, 1);
    static final TxOutRef ORIGIN = ref(0x41, 0);
    static final byte[] CHANGE = GatewayFixtures.enterpriseKeyAddress(filled(0xee, 28));
    static final byte[] REFUND_KEY = filled(0xa2, 28);
    static final byte[] HOLDER_KEY = filled(0xa3, 28);

    /** A user claim on {@code shard} paying the smart wallet of a key, reserved two days before {@link #NOW}. */
    static ClaimFx walletClaim(int shard, int seed) {
        return new ClaimFx(shardId(shard, seed), 0, 1_000L + seed, 1_500_000L, smartWallet(filled(0x80 + seed % 16, 28)),
                100 + seed, NOW - 2 * DAY);
    }

    static Value assetValue(long lovelace, long quantity) {
        return assetValue(ASSET, lovelace, quantity);
    }

    /** The registry: origin {@code "" -> A}, {@code A -> B} with hook {@code hookA}, {@code B -> sentinel}. */
    List<TxInInfo> registry(Program hookA, Program hookB) {
        return List.of(
                registryNodeInput(ref(0x6b, 0), new byte[0], registryNode(new byte[0], ASSET.policyId(),
                        Cip113Blueprint.keyCredential(new byte[0]))),
                registryNodeInput(ref(0x6b, 1), ASSET.policyId(), registryNode(ASSET.policyId(),
                        ASSET_B.policyId(), Cip113Blueprint.scriptCredential(Cip113Blueprint.hash(hookA)))),
                registryNodeInput(ref(0x6b, 2), ASSET_B.policyId(), registryNode(ASSET_B.policyId(), SENTINEL,
                        Cip113Blueprint.scriptCredential(Cip113Blueprint.hash(hookB)))));
    }

    Program hookA() {
        return hook(1, new byte[0]);
    }

    Program hookB() {
        return hook(2, new byte[0]);
    }

    /**
     * The common frame of a vault transaction: the params UTxO, the registry, the exit-mode thread in {@code mode},
     * the fee input and the dispatcher's {@code TransferAct}; the hooks of the registered policies present are added
     * by the caller with {@link #withHooks}.
     */
    FullTx frame(PlutusData mode) {
        FullTx tx = new FullTx();
        tx.reference(paramsInput(CIP.paramsDatum()));
        registry(hookA(), hookB()).forEach(tx::reference);
        tx.reference(exitModeInput(mode));
        tx.spend(keyInput(FEE_REF, 50_000_000L), null);
        tx.withdrawScript(CIP.dispatcherHash(), 0, transferAct());
        tx.withdrawScript(CIP.transferHash(), 0, PlutusData.constr(0, PlutusData.list()));
        tx.script(Cip113Blueprint.hash(hookA()), hookA());
        tx.script(Cip113Blueprint.hash(hookB()), hookB());
        return tx;
    }

    /** Adds the withdrawal of each registered policy's hook present in the PLB inputs. */
    FullTx withHooks(FullTx tx) {
        boolean a = false;
        boolean b = false;
        for (Spent spent : tx.inputs) {
            TxOut out = spent.input().resolved();
            if (Arrays.equals(paymentHash(out.address()), PLB)) {
                a |= hasPolicy(out.value(), ASSET.policyId());
                b |= hasPolicy(out.value(), ASSET_B.policyId());
            }
        }
        if (a) {
            tx.withdrawScript(Cip113Blueprint.hash(hookA()), 0, PlutusData.constr(0));
        }
        if (b) {
            tx.withdrawScript(Cip113Blueprint.hash(hookB()), 0, PlutusData.constr(0));
        }
        return tx;
    }

    TxInInfo shardInput(int shard, byte[] root) {
        return new TxInInfo(shardRef(shard), new TxOut(GatewayFixtures.scriptAddress(programmableShardHash()),
                lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {(byte) shard}, 1)),
                new OutputDatum.OutputDatumInline(shardDatum(shard, root)), Optional.empty()));
    }

    TxOut shardOutput(int shard, byte[] root) {
        return out(GatewayFixtures.scriptAddress(programmableShardHash()),
                lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {(byte) shard}, 1)), shardDatum(shard, root));
    }

    /** {@code Settle} of {@code claims} from one accepted vault input, co-signed by the threshold. */
    FullTx settle(List<ClaimFx> claims, int shard) {
        FullTx tx = payout(claims, shard, settleRedeemer(claims, shard), normalMode());
        tx.reference(anchorInput(anchorDatum(2, filled(0x44, 32), 77, MEMBERS, THRESHOLD, NOW)));
        tx.signedByMembers(THRESHOLD);
        return tx;
    }

    /** The payout frame: one accepted vault input, the claims at outputs {@code 0..k-1}, then the continuation. */
    FullTx payout(List<ClaimFx> claims, int shard, PlutusData action, PlutusData mode) {
        long quantity = 0;
        long lovelace = 0;
        List<byte[]> ids = new ArrayList<>();
        for (ClaimFx claim : claims) {
            quantity += claim.quantity();
            lovelace += claim.vaultLovelace();
            ids.add(claim.id());
        }
        long inLovelace = FLOOR + lovelace + 10_000_000L;
        long inQuantity = quantity + 1_000L;
        FullTx tx = frame(mode);
        tx.spend(walletInput(W1, assetValue(inLovelace, inQuantity), continuation(List.of(shardId(3, 1)))), null);
        Inserts inserts = new ShardTrie(shard).seed(5).plan(ids);
        tx.spend(shardInput(shard, inserts.before()), inserts.redeemer());
        for (ClaimFx claim : claims) {
            tx.output(payTo(claim.recipient(), assetValue(claim.vaultLovelace(), claim.quantity())));
        }
        tx.output(out(vaultWallet(), assetValue(inLovelace - lovelace, inQuantity - quantity), continuation(ids)));
        tx.output(shardOutput(shard, inserts.after()));
        tx.output(payTo(CHANGE, lovelace(40_000_000L)));
        tx.withdrawScript(custodyHash(), 0, action);
        return withHooks(tx);
    }

    /** {@code Exit} of {@code claims}: proved against the anchor root (NORMAL) or {@code frozenRoot} (EXIT). */
    FullTx exit(List<ClaimFx> claims, int shard, boolean exitMode) {
        StateTrie state = stateWith(64, statusLeaf(org.yanoproject.x.assets.contracts.DomainStatusV1.Status.ACTIVE,
                0, EXIT_DELAY), VAULT_ID, ASSET, claims);
        PlutusData action = exitRedeemer(claims, claimProofs(state, claims), state.proof(STATUS_KEY), shard);
        FullTx tx = payout(claims, shard, action, exitMode ? exitMode(state.root()) : normalMode());
        if (!exitMode) {
            tx.reference(anchorInput(anchorDatum(2, state.root(), 77, MEMBERS, THRESHOLD, NOW)));
        }
        return tx;
    }

    /** A deposit-shaped vault input whose datum origin is {@code originTx#0}, created at output {@code index}. */
    static TxInInfo depositInput(TxOutRef at, byte[] originTx, long outputIndex, Value value) {
        return walletInput(at, value, programmableDeposit(smartWallet(REFUND_KEY), REFUND_KEY, originTx, 0,
                outputIndex));
    }

    /** {@code AcceptCosigned} of the deposit inputs into one {@code VaultContinuationV1([])}. */
    FullTx acceptCosigned(List<TxInInfo> deposits) {
        FullTx tx = merge(deposits, PlutusData.constr(3), normalMode());
        tx.reference(anchorInput(anchorDatum(2, filled(0x44, 32), 77, MEMBERS, THRESHOLD, NOW)));
        tx.signedByMembers(THRESHOLD);
        return tx;
    }

    /** {@code AcceptProved} with one CREDITED-record proof per input's origin-keyed id, under the anchor or frozen. */
    FullTx acceptProved(List<TxInInfo> deposits, boolean exitMode) {
        StateTrie state = new StateTrie().fill("ap/account/", 32);
        for (TxInInfo deposit : deposits) {
            state.put(depositKey(deposit), creditedRecord(deposit).encode());
        }
        List<PlutusData> proofs = new ArrayList<>();
        List<TxInInfo> sorted = new ArrayList<>(deposits);
        sorted.sort(OUTREF_ORDER);
        for (TxInInfo deposit : sorted) {
            proofs.add(state.proof(depositKey(deposit)));
        }
        PlutusData action = PlutusData.constr(4, PlutusData.list(proofs.toArray(new PlutusData[0])));
        FullTx tx = merge(deposits, action, exitMode ? exitMode(state.root()) : normalMode());
        if (!exitMode) {
            tx.reference(anchorInput(anchorDatum(2, state.root(), 77, MEMBERS, THRESHOLD, NOW)));
        }
        return tx;
    }

    FullTx merge(List<TxInInfo> inputs, PlutusData action, PlutusData mode) {
        FullTx tx = frame(mode);
        long lovelace = 0;
        long quantity = 0;
        for (TxInInfo input : inputs) {
            tx.spend(input, null);
            lovelace += input.resolved().value().lovelaceOf().longValue();
            quantity += input.resolved().value().assetOf(new org.julclang.ledger.PolicyId(ASSET.policyId()),
                    new org.julclang.ledger.TokenName(ASSET.assetName())).longValue();
        }
        tx.output(payTo(CHANGE, lovelace(40_000_000L)));
        tx.output(out(vaultWallet(), assetValue(lovelace, quantity), continuation(List.of())));
        tx.withdrawScript(custodyHash(), 0, action);
        return withHooks(tx);
    }

    /** Adds {@code e}'s withdrawal with {@code DelegatedActionV1(h, action)} to a transaction carrying {@code action}. */
    FullTx delegated(FullTx tx, PlutusData action) {
        tx.withdrawScript(exitDelegateHash(), 0, PlutusData.constr(0, PlutusData.bytes(custodyHash()), action));
        return tx;
    }

    static byte[] depositKey(TxInInfo deposit) {
        return GatewayFixtures.concat(DEPOSIT_PREFIX, depositIdOf(deposit).bytes());
    }

    static Hash32 depositIdOf(TxInInfo deposit) {
        List<PlutusData> fields = ((PlutusData.ConstrData) ((OutputDatum.OutputDatumInline) deposit.resolved()
                .datum()).datum()).fields();
        List<PlutusData> origin = ((PlutusData.ConstrData) fields.get(3)).fields();
        return programmableDepositId(((PlutusData.BytesData) origin.get(0)).value(),
                ((PlutusData.IntData) origin.get(1)).value().longValue(),
                ((PlutusData.IntData) origin.get(2)).value().longValue());
    }

    static org.yanoproject.x.assets.contracts.DepositRecordV1 creditedRecord(TxInInfo deposit) {
        return record(deposit, org.yanoproject.x.assets.contracts.DepositRecordV1.Status.CREDITED,
                org.yanoproject.x.assets.contracts.DepositAdmission.Reason.NONE);
    }

    static org.yanoproject.x.assets.contracts.DepositRecordV1 record(TxInInfo deposit,
            org.yanoproject.x.assets.contracts.DepositRecordV1.Status status,
            org.yanoproject.x.assets.contracts.DepositAdmission.Reason reason) {
        boolean credited = status == org.yanoproject.x.assets.contracts.DepositRecordV1.Status.CREDITED;
        return new org.yanoproject.x.assets.contracts.DepositRecordV1(depositIdOf(deposit), VAULT_ID,
                Hash32.of(filled(0x41, 32)), 3, 9_000, 77,
                org.yanoproject.x.assets.contracts.VaultOutputShape.DEPOSIT, status, reason,
                credited ? filled(0xa1, 28) : new byte[0], BigInteger.valueOf(500), BigInteger.valueOf(2_000_000), 0,
                Hash32.of(filled(0x5e, 32)), credited ? BigInteger.valueOf(500) : BigInteger.ZERO,
                credited ? BigInteger.valueOf(2_000_000) : BigInteger.ZERO);
    }
}
