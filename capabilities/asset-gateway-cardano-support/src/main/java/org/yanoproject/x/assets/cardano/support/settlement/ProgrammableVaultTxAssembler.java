package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.Cip113Requirements;
import org.yanoproject.x.assets.contracts.Cip113Wiring;
import org.yanoproject.x.assets.contracts.Cip113WiringClaimV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Assembles every transaction that spends programmable (PLB) inputs for the gateway (ADR-061 D10): the custody's
 * {@code Settle}, {@code Exit}, {@code SweepExit}, {@code Accept} and the EXIT-only actions, and the user's deposit.
 * CCL 0.8.0-pre8 cannot authorize a script-owned smart wallet, so Yano X lays these out itself.
 *
 * <p><b>What it derives</b> ({@link Cip113Requirements#of}, from the policies in the PLB inputs): one registry proof
 * per policy, the transfer hooks merged by credential, and their reference inputs. The live wiring
 * ({@link LiveWiring}) contributes the dispatcher and its transfer delegate.
 *
 * <p><b>Ordering</b>, computed against the ledger's final order, never against insertion order:
 * <ol>
 *   <li>inputs sorted by (tx id, index); each PLB input gets {@code BaseSpendRedeemer{params_idx, wdrl_idx}}, every
 *       other script input its own redeemer, at its sorted position;</li>
 *   <li>reference inputs sorted the same way; {@code params_idx} is the live params UTxO's position and each proof's
 *       {@code node_idx} its registry node's;</li>
 *   <li>withdrawals in ledger order (script credentials, bytewise; a key-credential hook is refused):
 *       the dispatcher with {@code TransferAct}, the transfer delegate with {@code TransferRedeemer{proofs}}, one hook
 *       per distinct credential, and the caller's custody withdrawals ({@code h}, and {@code e} for the EXIT-only
 *       actions); {@code wdrl_idx} is the dispatcher's position once every entry is present, and each reward
 *       redeemer's index its withdrawal's position;</li>
 *   <li>every withdrawal takes its credential's <b>live</b> reward balance, never a hard-coded 0; balancing sends it
 *       to the fee or the change (D2, I43);</li>
 *   <li>outputs as the caller lays them out, then the change.</li>
 * </ol>
 *
 * <p>The live params UTxO must carry the params policy's {@code ProtocolParams} token, and its dispatcher must be one
 * of an approved tuple, whose transfer delegate is used; otherwise assembly fails closed ({@link Drift}, D10 rule 6).
 * A change of fields a vault transaction does not use (the issuance logic, the third-party credential) does not stop
 * it.
 */
public final class ProgrammableVaultTxAssembler {
    public static final byte[] PROTOCOL_PARAMS_NAME = "ProtocolParams".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private ProgrammableVaultTxAssembler() {
    }

    /** Protocol-level drift in a field the transaction uses: assembly refuses until {@code ASSET_REWIRE}. */
    public static final class Drift extends PayoutTxBuilder.BuildException {
        public Drift(String message) {
            super(message);
        }
    }

    /** The pinned CIP-113 deployment (D12). */
    public record Deployment(byte[] plbHash, byte[] paramsPolicy, byte[] registryNodePolicy, int networkId,
                             int maxInlineDatumBytes) {
        public Deployment {
            plbHash = hash28(plbHash, "PLB hash");
            paramsPolicy = hash28(paramsPolicy, "params policy");
            registryNodePolicy = hash28(registryNodePolicy, "registry node policy");
            if (networkId < 0 || networkId > 15) throw new IllegalArgumentException("network id is 0..15");
        }

        @Override
        public byte[] plbHash() {
            return plbHash.clone();
        }

        @Override
        public byte[] paramsPolicy() {
            return paramsPolicy.clone();
        }

        @Override
        public byte[] registryNodePolicy() {
            return registryNodePolicy.clone();
        }

        /** True when {@code raw} has the PLB payment credential. */
        public boolean programmable(byte[] raw) {
            return raw.length >= 29 && ((raw[0] & 0xff) >> 4) % 2 == 1
                    && Arrays.equals(Arrays.copyOfRange(raw, 1, 29), plbHash);
        }
    }

    /**
     * The live params UTxO from the builder's own L1 view, authenticated by the params token, its dispatcher, the
     * approved tuple with that dispatcher, and the params datum's four fields when they decode ({@code fields}). A
     * vault transaction uses only the dispatcher and the tuple's transfer delegate (D10 rule 6), so it is built from
     * the dispatcher alone, which decodes whatever fields 1 to 3 hold; the user's deposit needs the exact fields.
     */
    public record LiveWiring(ResolvedOutput params, byte[] dispatcher, Optional<Cip113WiringClaimV1.Fields> fields,
                             Cip113Wiring.ApprovedTuple tuple) {
        public LiveWiring {
            Objects.requireNonNull(params, "params");
            dispatcher = hash28(dispatcher, "dispatcher");
            Objects.requireNonNull(fields, "fields");
            Objects.requireNonNull(tuple, "tuple");
            if (!Arrays.equals(tuple.fields().dispatcher(), dispatcher) || !tuple.fields().script(0)) {
                throw new IllegalArgumentException("the tuple does not name this dispatcher");
            }
        }

        /**
         * Resolves the live wiring: {@code params} must carry the {@code ProtocolParams} token of the pinned params
         * policy and name a script dispatcher, and one of {@code approved} must name that dispatcher.
         *
         * @throws Drift when the dispatcher is in no approved tuple, or the UTxO is not the params UTxO
         */
        public static LiveWiring of(Deployment deployment, ResolvedOutput params,
                                    List<Cip113Wiring.ApprovedTuple> approved) {
            return of(deployment, params, dispatcher -> approved.stream()
                    .filter(tuple -> Arrays.equals(tuple.fields().dispatcher(), dispatcher)
                            && tuple.fields().script(0)).findFirst());
        }

        /** {@link #of(Deployment, ResolvedOutput, List)} with the approved tuple of a dispatcher looked up. */
        public static LiveWiring of(Deployment deployment, ResolvedOutput params,
                                    java.util.function.Function<byte[], Optional<Cip113Wiring.ApprovedTuple>>
                                            approvedFor) {
            AssetId token = AssetId.of(deployment.paramsPolicy(), PROTOCOL_PARAMS_NAME);
            if (!BigInteger.ONE.equals(params.quantityOf(token))) {
                throw new Drift("the referenced UTxO does not carry the protocol-params token");
            }
            byte[] datum = params.inlineDatum().orElseThrow(() -> new Drift("the protocol-params UTxO has no datum"));
            byte[] dispatcher = Cip113WiringClaimV1.dispatcherOf(datum)
                    .orElseThrow(() -> new Drift("the protocol-params datum names no script dispatcher"));
            Cip113Wiring.ApprovedTuple tuple = approvedFor.apply(dispatcher).orElseThrow(() -> new Drift(
                    "the live dispatcher " + HexFormat.of().formatHex(dispatcher)
                            + " is in no approved wiring tuple; assembly waits for ASSET_REWIRE"));
            return new LiveWiring(params, dispatcher, Cip113WiringClaimV1.Fields.ofDatum(datum), tuple);
        }

        @Override
        public byte[] dispatcher() {
            return dispatcher.clone();
        }

        public byte[] transferDelegate() {
            return tuple.transferDelegate();
        }
    }

    /** A script input other than a PLB input (the shard), with its own spend redeemer. */
    public record ScriptSpend(ResolvedOutput input, PlutusData redeemer) {
    }

    /** A withdrawal the caller adds: the custody script {@code h} or the exit delegate {@code e}, with its redeemer. */
    public record CustodyWithdrawal(byte[] scriptHash, PlutusData redeemer) {
        public CustodyWithdrawal {
            scriptHash = hash28(scriptHash, "custody withdrawal");
            Objects.requireNonNull(redeemer, "redeemer");
        }
    }

    /** A builder's live view of reward balances, by script credential (the only kind the assembler withdraws). */
    @FunctionalInterface
    public interface RewardBalances {
        BigInteger balance(byte[] scriptHash);
    }

    /** The builder's L1 view of the registry: the requirements' nodes, and the outputs they and the adapters name. */
    public interface L1 extends Cip113Requirements.Registry {
        Optional<ResolvedOutput> resolve(ProgrammableDepositDatumV1.Outpoint outpoint);
    }

    public record Plan(List<ResolvedOutput> plbInputs, List<ScriptSpend> scriptInputs,
                       PayoutTxBuilder.Funding funding, List<TransactionOutput> outputs,
                       List<ResolvedOutput> references, List<CustodyWithdrawal> custody,
                       List<byte[]> requiredSigners, long validFromSlot, long ttlSlot) {
        public Plan {
            plbInputs = List.copyOf(plbInputs);
            scriptInputs = List.copyOf(scriptInputs);
            Objects.requireNonNull(funding, "funding");
            outputs = List.copyOf(outputs);
            references = List.copyOf(references);
            custody = List.copyOf(custody);
            requiredSigners = requiredSigners.stream().map(byte[]::clone).toList();
        }
    }

    /** The indexes the assembler computed, for the P5 vectors and the body check. */
    public record Indexes(int paramsIndex, int dispatcherIndex, List<Integer> proofNodeIndexes,
                          List<String> withdrawalOrder, List<String> referenceOrder, List<String> inputOrder) {
    }

    public record Assembled(Transaction transaction, byte[] cbor, Hash32 txId, List<ResolvedOutput> resolved,
                            Cip113Requirements requirements, Indexes indexes, BigInteger fee,
                            Map<String, BigInteger> withdrawalAmounts) {
        public byte[] cbor() {
            return cbor.clone();
        }
    }

    /**
     * Assembles, evaluates and balances one transaction. The change (the funding's lovelace and every withdrawn
     * balance, less the fee and what the outputs need beyond the spent script inputs) goes to a last output at the
     * funding's change address.
     */
    public static Assembled assemble(Deployment deployment, LiveWiring wiring, Plan plan, L1 l1,
                                     Cip113Requirements.Adapter adapter, RewardBalances balances, ProtocolParams pp,
                                     TransactionEvaluator evaluator) {
        for (ResolvedOutput input : plan.plbInputs()) {
            if (!deployment.programmable(input.address())) {
                throw new IllegalArgumentException("a PLB input is not at the pinned base script");
            }
        }
        List<Map<byte[], Map<byte[], BigInteger>>> values = new ArrayList<>();
        for (ResolvedOutput input : plan.plbInputs()) values.add(policyMap(input.assets()));
        Cip113Requirements requirements = Cip113Requirements.of(values, wiring.dispatcher(),
                wiring.transferDelegate(), l1, adapter);
        // A hook keyed by a verification key would need that key's signature and a reward balance by key credential,
        // neither of which the gateway has (D12 requirement 2): such a policy is refused before anything is built.
        for (Cip113Requirements.Hook hook : requirements.hooks()) {
            if (!hook.credential().script()) {
                throw new PayoutTxBuilder.BuildException("the transfer hook of policy "
                        + HexFormat.of().formatHex(hook.policies().getFirst())
                        + " is a key credential, which Yano X does not assemble");
            }
        }

        // Reference inputs: the caller's, the live params UTxO, the requirements' nodes and adapter state.
        Map<ResolvedOutput.Outpoint, ResolvedOutput> references = new TreeMap<>();
        plan.references().forEach(ref -> references.put(ref.outpoint(), ref));
        references.put(wiring.params().outpoint(), wiring.params());
        for (ProgrammableDepositDatumV1.Outpoint ref : requirements.references()) {
            ResolvedOutput resolved = l1.resolve(ref).orElseThrow(() -> new PayoutTxBuilder.BuildException(
                    "reference input " + ref + " is not in the L1 view"));
            references.put(resolved.outpoint(), resolved);
        }
        List<ResolvedOutput.Outpoint> referenceOrder = new ArrayList<>(references.keySet());
        int paramsIndex = referenceOrder.indexOf(wiring.params().outpoint());
        List<Integer> nodeIndexes = new ArrayList<>();
        List<PlutusData> proofs = new ArrayList<>();
        for (Cip113Requirements.Proof proof : requirements.proofs()) {
            int index = referenceOrder.indexOf(outpoint(proof.node().outpoint()));
            nodeIndexes.add(index);
            proofs.add(constr(proof.registered() ? 0 : 1, BigIntPlutusData.of(index)));
        }

        // Withdrawals, in ledger order, each at its live balance.
        Map<String, PlutusData> withdrawals = new TreeMap<>(LEDGER_ORDER);
        withdrawals.put(scriptKey(wiring.dispatcher()), constr(0));
        withdrawals.put(scriptKey(wiring.transferDelegate()), constr(0, GatewayPlutus.list(proofs)));
        for (Cip113Requirements.Hook hook : requirements.hooks()) {
            String key = scriptKey(hook.credential().hash());
            try {
                withdrawals.put(key, PlutusData.deserialize(hook.redeemer()));
            } catch (Exception undecodable) {
                throw new PayoutTxBuilder.BuildException("hook redeemer does not decode", undecodable);
            }
        }
        for (CustodyWithdrawal custody : plan.custody()) {
            if (withdrawals.put(scriptKey(custody.scriptHash()), custody.redeemer()) != null) {
                throw new IllegalArgumentException("a custody withdrawal repeats another withdrawal's credential");
            }
        }
        List<String> withdrawalOrder = new ArrayList<>(withdrawals.keySet());
        int dispatcherIndex = withdrawalOrder.indexOf(scriptKey(wiring.dispatcher()));
        Network network = network(deployment.networkId());
        List<Withdrawal> bodyWithdrawals = new ArrayList<>();
        Map<String, BigInteger> amounts = new LinkedHashMap<>();
        BigInteger withdrawn = BigInteger.ZERO;
        for (String key : withdrawalOrder) {
            byte[] hash = HexFormat.of().parseHex(key.substring(1));
            // Every withdrawal is a script credential (key hooks are refused above), read by its script hash.
            BigInteger amount = Objects.requireNonNull(balances.balance(hash), "reward balance");
            if (amount.signum() < 0) throw new IllegalArgumentException("a reward balance is never negative");
            amounts.put(key, amount);
            withdrawn = withdrawn.add(amount);
            Credential credential = Credential.fromScript(hash);
            bodyWithdrawals.add(new Withdrawal(AddressProvider.getRewardAddress(credential, network).toBech32(),
                    amount));
        }

        // Inputs, in ledger order, with their spend redeemers.
        Map<ResolvedOutput.Outpoint, ResolvedOutput> spent = new TreeMap<>();
        Map<ResolvedOutput.Outpoint, PlutusData> spendRedeemers = new LinkedHashMap<>();
        PlutusData base = constr(0, BigIntPlutusData.of(paramsIndex), BigIntPlutusData.of(dispatcherIndex));
        for (ResolvedOutput input : plan.plbInputs()) {
            spent.put(input.outpoint(), input);
            spendRedeemers.put(input.outpoint(), base);
        }
        for (ScriptSpend spend : plan.scriptInputs()) {
            spent.put(spend.input().outpoint(), spend.input());
            spendRedeemers.put(spend.input().outpoint(), spend.redeemer());
        }
        for (ResolvedOutput input : plan.funding().inputs()) {
            // As the native builder: the funding pays fees, collateral and supplements in lovelace, and a token on it
            // would have to leave through the change output, which the balance never accounts for.
            if (!input.assets().isEmpty()) throw new PayoutTxBuilder.BuildException("funding inputs hold lovelace only");
            if (spent.put(input.outpoint(), input) != null) {
                throw new PayoutTxBuilder.BuildException("funding input is also spent");
            }
        }
        List<ResolvedOutput.Outpoint> inputOrder = new ArrayList<>(spent.keySet());

        ExUnits placeholder = ExUnits.builder().mem(new BigInteger(pp.getMaxTxExMem()))
                .steps(new BigInteger(pp.getMaxTxExSteps())).build();
        List<Redeemer> redeemers = new ArrayList<>();
        for (int i = 0; i < inputOrder.size(); i++) {
            PlutusData data = spendRedeemers.get(inputOrder.get(i));
            if (data != null) {
                redeemers.add(Redeemer.builder().tag(RedeemerTag.Spend).index(BigInteger.valueOf(i)).data(data)
                        .exUnits(placeholder).build());
            }
        }
        for (int i = 0; i < withdrawalOrder.size(); i++) {
            if (withdrawalOrder.get(i).charAt(0) != '0') continue;
            redeemers.add(Redeemer.builder().tag(RedeemerTag.Reward).index(BigInteger.valueOf(i))
                    .data(withdrawals.get(withdrawalOrder.get(i))).exUnits(placeholder).build());
        }

        // Outputs, then the change.
        List<TransactionOutput> outputs = new ArrayList<>(plan.outputs());
        outputs.add(PayoutTxBuilder.output(plan.funding().changeAddress(), BigInteger.ZERO, Map.of(), null));
        int changeIndex = outputs.size() - 1;
        BigInteger outputLovelace = BigInteger.ZERO;
        for (TransactionOutput out : plan.outputs()) outputLovelace = outputLovelace.add(out.getValue().getCoin());
        BigInteger scriptInputLovelace = BigInteger.ZERO;
        for (ResolvedOutput input : plan.plbInputs()) scriptInputLovelace = scriptInputLovelace.add(input.lovelace());
        for (ScriptSpend spend : plan.scriptInputs()) {
            scriptInputLovelace = scriptInputLovelace.add(spend.input().lovelace());
        }
        BigInteger supplements = outputLovelace.subtract(scriptInputLovelace).subtract(withdrawn);

        ResolvedOutput collateral = plan.funding().inputs().stream()
                .max(Comparator.comparing(ResolvedOutput::lovelace)).orElseThrow();
        TransactionBody body = TransactionBody.builder()
                .inputs(inputOrder.stream().map(PayoutTxBuilder::input).toList())
                .outputs(outputs)
                .fee(BigInteger.ZERO)
                .ttl(plan.ttlSlot())
                .validityStartInterval(plan.validFromSlot())
                .withdrawals(bodyWithdrawals)
                .collateral(List.of(PayoutTxBuilder.input(collateral.outpoint())))
                .referenceInputs(referenceOrder.stream().map(PayoutTxBuilder::input).toList())
                .requiredSigners(plan.requiredSigners().stream().map(byte[]::clone).toList())
                .build();
        TransactionWitnessSet witnesses = new TransactionWitnessSet();
        witnesses.setRedeemers(redeemers);
        Transaction tx = Transaction.builder().body(body).witnessSet(witnesses).isValid(true).build();

        Map<ResolvedOutput.Outpoint, ResolvedOutput> resolved = new LinkedHashMap<>(spent);
        resolved.putAll(references);
        BigInteger fee = PayoutTxBuilder.balance(tx, changeIndex, plan.funding(), supplements, collateral,
                resolved.values(), pp, evaluator, plan.requiredSigners().size());
        byte[] cbor = PayoutTxBuilder.serialize(tx);
        Indexes indexes = new Indexes(paramsIndex, dispatcherIndex, List.copyOf(nodeIndexes),
                List.copyOf(withdrawalOrder), referenceOrder.stream().map(Object::toString).toList(),
                inputOrder.stream().map(Object::toString).toList());
        return new Assembled(tx, cbor, Hash32.ofHex(TransactionUtil.getTxHash(tx)), List.copyOf(resolved.values()),
                requirements, indexes, fee, amounts);
    }

    /**
     * Ledger order of withdrawal keys {@code <kind><hash hex>}: kind {@code 0} (script) before {@code 1} (key), then
     * the hash bytewise, which equals the hex order.
     */
    static final Comparator<String> LEDGER_ORDER = Comparator.naturalOrder();

    static String scriptKey(byte[] hash) {
        return "0" + HexFormat.of().formatHex(hash);
    }

    static Map<byte[], Map<byte[], BigInteger>> policyMap(NavigableMap<AssetId, BigInteger> assets) {
        Map<String, Map<byte[], BigInteger>> byPolicy = new TreeMap<>();
        Map<String, byte[]> policies = new TreeMap<>();
        assets.forEach((asset, quantity) -> {
            String key = HexFormat.of().formatHex(asset.policyId());
            policies.put(key, asset.policyId());
            byPolicy.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(asset.assetName(), quantity);
        });
        Map<byte[], Map<byte[], BigInteger>> out = new LinkedHashMap<>();
        byPolicy.forEach((key, names) -> out.put(policies.get(key), names));
        return out;
    }

    static ResolvedOutput.Outpoint outpoint(ProgrammableDepositDatumV1.Outpoint outpoint) {
        return new ResolvedOutput.Outpoint(outpoint.txId(), Math.toIntExact(outpoint.index()));
    }

    static ConstrPlutusData constr(int alternative, PlutusData... fields) {
        return ConstrPlutusData.of(alternative, fields);
    }

    static Network network(int networkId) {
        return networkId == 1 ? com.bloxbean.cardano.client.common.model.Networks.mainnet()
                : com.bloxbean.cardano.client.common.model.Networks.testnet();
    }

    private static byte[] hash28(byte[] value, String name) {
        if (value == null || value.length != 28) throw new IllegalArgumentException(name + " must be 28 bytes");
        return value.clone();
    }

    /** Unused input kinds are rejected early; exposed for the body check. */
    static TransactionInput input(ResolvedOutput.Outpoint outpoint) {
        return PayoutTxBuilder.input(outpoint);
    }
}
