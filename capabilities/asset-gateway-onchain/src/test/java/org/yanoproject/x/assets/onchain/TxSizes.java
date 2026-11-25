package org.yanoproject.x.assets.onchain;

import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcMap;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.StakingCredential;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The full serialized size of a test transaction as the ledger would see it: body (inputs, outputs with their inline
 * datums, reference inputs including the reference-script UTxOs, collateral and its return, required signers, fee,
 * validity interval, script data hash, mint) and witness set (one vkey witness per required signer plus the fee
 * payer, and one spend redeemer per script input with its execution units). Scripts are referenced, never inlined.
 */
final class TxSizes {
    private TxSizes() {
    }

    /** {@code units} maps each script input to its {cpu, mem}; {@code scriptRefs} is the number of script UTxOs. */
    static int bytes(GatewayTestBase.Tx tx, Map<TxOutRef, long[]> units, int scriptRefs) {
        try {
            List<GatewayTestBase.Spent> sorted = new ArrayList<>(tx.inputs);
            sorted.sort(Comparator.comparing((GatewayTestBase.Spent s) -> HexFormat.of().formatHex(
                    s.input().outRef().txId().hash())).thenComparing(s -> s.input().outRef().index()));
            List<TransactionInput> inputs = new ArrayList<>();
            List<Redeemer> redeemers = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) {
                GatewayTestBase.Spent spent = sorted.get(i);
                inputs.add(input(spent.input().outRef()));
                if (spent.redeemer() != null) {
                    long[] u = units.getOrDefault(spent.input().outRef(), new long[] {10_000_000_000L, 16_500_000L});
                    redeemers.add(Redeemer.builder().tag(RedeemerTag.Spend).index(i)
                            .data(PlutusDataAdapter.toClientLib(spent.redeemer()))
                            .exUnits(new ExUnits(BigInteger.valueOf(u[1]), BigInteger.valueOf(u[0]))).build());
                }
            }
            List<TransactionOutput> outputs = new ArrayList<>();
            for (TxOut out : tx.outputs) {
                outputs.add(output(out));
            }
            List<TransactionInput> references = new ArrayList<>();
            for (TxInInfo reference : tx.references) {
                references.add(input(reference.outRef()));
            }
            for (int i = 0; i < scriptRefs; i++) {
                references.add(input(new TxOutRef(new org.julclang.ledger.TxId(GatewayFixtures.filled(0x90 + i, 32)),
                        BigInteger.valueOf(i))));
            }
            List<byte[]> signers = new ArrayList<>();
            for (PubKeyHash signer : tx.signers) {
                signers.add(signer.hash());
            }
            byte[] feePayer = GatewayFixtures.enterpriseKeyAddress(GatewayFixtures.filled(0xee, 28));
            TransactionBody.TransactionBodyBuilder body = TransactionBody.builder()
                    .inputs(inputs).outputs(outputs).fee(BigInteger.valueOf(2_000_000))
                    .validityStartInterval(170_000_000L).ttl(170_000_600L)
                    .referenceInputs(references)
                    .collateral(List.of(input(new TxOutRef(new org.julclang.ledger.TxId(
                            GatewayFixtures.filled(0x9f, 32)), BigInteger.ZERO))))
                    .collateralReturn(TransactionOutput.builder().address(bech32(feePayer))
                            .value(com.bloxbean.cardano.client.transaction.spec.Value.fromCoin(
                                    BigInteger.valueOf(40_000_000))).build())
                    .totalCollateral(BigInteger.valueOf(3_000_000))
                    .scriptDataHash(GatewayFixtures.filled(0x5d, 32));
            if (!signers.isEmpty()) {
                body.requiredSigners(signers);
            }
            List<VkeyWitness> witnesses = new ArrayList<>();
            for (int i = 0; i < signers.size() + 1; i++) {
                witnesses.add(new VkeyWitness(GatewayFixtures.filled(0x30 + i, 32), GatewayFixtures.filled(0x31, 64)));
            }
            TransactionWitnessSet witnessSet = TransactionWitnessSet.builder().vkeyWitnesses(witnesses)
                    .redeemers(redeemers).build();
            return Transaction.builder().body(body.build()).witnessSet(witnessSet).isValid(true).build()
                    .serialize().length;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static TransactionInput input(TxOutRef ref) {
        return TransactionInput.builder().transactionId(HexFormat.of().formatHex(ref.txId().hash()))
                .index(ref.index().intValueExact()).build();
    }

    private static TransactionOutput output(TxOut out) {
        TransactionOutput.TransactionOutputBuilder builder = TransactionOutput.builder()
                .address(bech32(raw(out.address()))).value(value(out.value()));
        if (out.datum() instanceof OutputDatum.OutputDatumInline inline) {
            builder.inlineDatum(PlutusDataAdapter.toClientLib(inline.datum()));
        }
        return builder.build();
    }

    private static com.bloxbean.cardano.client.transaction.spec.Value value(Value value) {
        com.bloxbean.cardano.client.transaction.spec.Value result =
                com.bloxbean.cardano.client.transaction.spec.Value.fromCoin(value.lovelaceOf());
        JulcMap<PolicyId, JulcMap<TokenName, BigInteger>> policies = value.inner();
        for (PolicyId policy : policies.keys()) {
            if (policy.hash().length == 0) {
                continue;
            }
            JulcMap<TokenName, BigInteger> tokens = policies.get(policy);
            for (TokenName name : tokens.keys()) {
                BigInteger quantity = tokens.get(name);
                if (quantity.signum() != 0) {
                    result = result.add(HexFormat.of().formatHex(policy.hash()),
                            "0x" + HexFormat.of().formatHex(name.name()), quantity);
                }
            }
        }
        return result;
    }

    private static String bech32(byte[] raw) {
        return new com.bloxbean.cardano.client.address.Address(raw).toBech32();
    }

    /** Raw Shelley bytes (network 0) of a julc address. */
    static byte[] raw(Address address) {
        boolean script = address.credential() instanceof Credential.ScriptCredential;
        byte[] payment = script ? ((Credential.ScriptCredential) address.credential()).hash().hash()
                : ((Credential.PubKeyCredential) address.credential()).hash().hash();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (address.stakingCredential().isEmpty()) {
            out.write(script ? 0x70 : 0x60);
            out.writeBytes(payment);
            return out.toByteArray();
        }
        StakingCredential staking = address.stakingCredential().get();
        if (staking instanceof StakingCredential.StakingHash hash) {
            boolean stakeScript = hash.credential() instanceof Credential.ScriptCredential;
            byte[] stake = stakeScript ? ((Credential.ScriptCredential) hash.credential()).hash().hash()
                    : ((Credential.PubKeyCredential) hash.credential()).hash().hash();
            out.write((script ? 1 : 0) + (stakeScript ? 2 : 0) << 4);
            out.writeBytes(payment);
            out.writeBytes(stake);
            return out.toByteArray();
        }
        StakingCredential.StakingPtr ptr = (StakingCredential.StakingPtr) staking;
        out.write(script ? 0x50 : 0x40);
        out.writeBytes(payment);
        for (BigInteger n : List.of(ptr.slot(), ptr.txIndex(), ptr.certIndex())) {
            out.writeBytes(varNat(n.longValueExact()));
        }
        return out.toByteArray();
    }

    private static byte[] varNat(long n) {
        ByteArrayOutputStream reversed = new ByteArrayOutputStream();
        reversed.write((int) (n & 0x7f));
        n >>>= 7;
        while (n > 0) {
            reversed.write((int) ((n & 0x7f) | 0x80));
            n >>>= 7;
        }
        byte[] bytes = reversed.toByteArray();
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = bytes[bytes.length - 1 - i];
        }
        return out;
    }

    static int dataBytes(PlutusData data) {
        return org.julclang.core.cbor.PlutusDataCborEncoder.encode(data).length;
    }
}
