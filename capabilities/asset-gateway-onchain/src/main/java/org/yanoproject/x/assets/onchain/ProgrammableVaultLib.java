package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ValuesLib;

import java.math.BigInteger;

/**
 * The rules ADR-061 adds to the anchored-claim vault for a CIP-113 asset, shared by the custody script
 * ({@code ProgrammableVaultValidator}, {@code h}) and its exit delegate ({@code ProgrammableVaultExitDelegate},
 * {@code e}). The codec, the state-proof verifiers and the anchor reader are the native vault's ({@link GatewayCodec},
 * {@link GatewayTx}, {@code MpfOnChainVerifier}, {@code MpfAnchorLib}); this library holds only what differs.
 *
 * <p>The vault's custody is every UTxO at the vault smart wallet {@code (Script(PLB), Inline(Script(h)))} (D1). The
 * custody scripts read no CIP-113 state: the pinned PLB, dispatcher and transfer delegate do. A CIP-113 vault always
 * holds a native asset, never ADA alone.
 */
@OnchainLibrary
public final class ProgrammableVaultLib {
    private ProgrammableVaultLib() {
    }

    // ------------------------------------------------------------------
    // Addresses

    /** The Plutus Data of {@code base(Script(plb), Script(owner))}: the smart wallet of the script {@code owner}. */
    public static PlutusData smartWallet(byte[] plb, byte[] owner) {
        PlutusData payment = Builtins.constrData(1, Builtins.mkCons(Builtins.bData(plb), Builtins.mkNilData()));
        PlutusData ownerCredential = Builtins.constrData(1, Builtins.mkCons(Builtins.bData(owner),
                Builtins.mkNilData()));
        PlutusData stake = Builtins.constrData(0, Builtins.mkCons(Builtins.constrData(0,
                Builtins.mkCons(ownerCredential, Builtins.mkNilData())), Builtins.mkNilData()));
        return Builtins.constrData(0, Builtins.mkCons(payment, Builtins.mkCons(stake, Builtins.mkNilData())));
    }

    /**
     * True when {@code address} may receive a payout (D7): a key-owned smart wallet of the pinned deployment (a base
     * address with payment {@code Script(plb)} and an inline key-hash stake credential); or, when the payout carries
     * no native asset, also an address with a key payment credential, as for a native vault.
     */
    public static boolean payable(PlutusData address, byte[] plb, boolean carriesTokens) {
        PlutusData fields = Builtins.constrFields(address);
        PlutusData payment = Builtins.headList(fields);
        if (Builtins.constrTag(payment) == 0) {
            return !carriesTokens;
        }
        PlutusData stake = Builtins.headList(Builtins.tailList(fields));
        if (Builtins.constrTag(stake) != 0) {
            return false;
        }
        PlutusData staking = Builtins.headList(Builtins.constrFields(stake));
        return Builtins.equalsByteString(Builtins.unBData(Builtins.headList(Builtins.constrFields(payment))), plb)
                && Builtins.constrTag(staking) == 0
                && Builtins.constrTag(Builtins.headList(Builtins.constrFields(staking))) == 0;
    }

    /**
     * True when the raw address {@code raw} is a key-owned smart wallet of the pinned deployment on network
     * {@code networkId}: 57 bytes, the base-address header for a script payment and a key stake part
     * ({@code 0x10 | network}), and {@code plb} as the payment credential (D6, D7).
     */
    public static boolean rawSmartWallet(byte[] raw, byte[] plb, long networkId) {
        return Builtins.lengthOfByteString(raw) == 57
                && Builtins.indexByteString(raw, 0) == 16 + networkId
                && Builtins.equalsByteString(Builtins.sliceByteString(1, 28, raw), plb);
    }

    // ------------------------------------------------------------------
    // Inputs and outputs

    /** V14: the inputs at exactly {@code vault}, the vault smart wallet, in ledger order. */
    public static JulcList<TxInInfo> vaultInputs(JulcList<TxInInfo> inputs, PlutusData vault) {
        if (inputs.isEmpty()) {
            return JulcList.empty();
        }
        TxInInfo input = inputs.head();
        JulcList<TxInInfo> rest = vaultInputs(inputs.tail(), vault);
        return Builtins.equalsData(input.resolved().address().toPlutusData(), vault) ? rest.prepend(input) : rest;
    }

    /**
     * V8, as {@code shards + 256 * otherScripts}: inputs that are not vault inputs and carry a shard thread token, and
     * every other script input, any PLB input of another owner included.
     */
    public static BigInteger foreignInputs(JulcList<TxInInfo> inputs, PlutusData vault, byte[] shardPolicy) {
        if (inputs.isEmpty()) {
            return BigInteger.ZERO;
        }
        TxOut out = inputs.head().resolved();
        BigInteger rest = foreignInputs(inputs.tail(), vault, shardPolicy);
        PlutusData address = out.address().toPlutusData();
        if (Builtins.equalsData(address, vault)) {
            return rest;
        }
        if (ValuesLib.containsPolicy(out.value(), shardPolicy)) {
            return rest.add(BigInteger.ONE);
        }
        return Builtins.lengthOfByteString(GatewayCodec.paymentScriptHash(address)) > 0
                ? rest.add(BigInteger.valueOf(256)) : rest;
    }

    /** The number of outputs at exactly {@code address}. */
    public static long outputsAt(JulcList<TxOut> outputs, PlutusData address) {
        if (outputs.isEmpty()) {
            return 0;
        }
        long rest = outputsAt(outputs.tail(), address);
        return Builtins.equalsData(outputs.head().address().toPlutusData(), address) ? rest + 1 : rest;
    }

    /**
     * V15: no output has {@code h} or {@code e} as its payment credential, and exactly {@code expected} outputs sit at
     * the full vault smart-wallet address.
     */
    public static boolean vaultOutputs(JulcList<TxOut> outputs, PlutusData vault, byte[] custodyHash,
                                       byte[] delegateHash, long expected) {
        return GatewayTx.outputsWithCredential(outputs, custodyHash) == 0
                && GatewayTx.outputsWithCredential(outputs, delegateHash) == 0
                && outputsAt(outputs, vault) == expected;
    }

    /** V17: no certificates and no mint. Withdrawals are not restricted. */
    public static boolean noCertificatesOrMint(TxInfo tx) {
        return tx.certificates().isEmpty() && Builtins.nullList(Builtins.unMapData(tx.mint()));
    }

    /**
     * True when {@code Script(hash)} is among the withdrawals, at any amount; {@code withdrawals} is the transaction's
     * withdrawal map as its list of pairs.
     */
    public static boolean withdrawn(PlutusData withdrawals, byte[] hash) {
        PlutusData credential = Builtins.constrData(1, Builtins.mkCons(Builtins.bData(hash), Builtins.mkNilData()));
        return hasKey(withdrawals, credential);
    }

    private static boolean hasKey(PlutusData pairs, PlutusData key) {
        if (Builtins.nullList(pairs)) {
            return false;
        }
        return Builtins.equalsData(Builtins.fstPair(Builtins.headList(pairs)), key)
                || hasKey(Builtins.tailList(pairs), key);
    }

    /**
     * The redeemer of the reward purpose {@code Script(hash)}, or {@code I -1} when it has none; {@code redeemers} is
     * the transaction's redeemer map as its list of pairs.
     */
    public static PlutusData rewardRedeemer(PlutusData redeemers, byte[] hash) {
        PlutusData purpose = Builtins.constrData(2, Builtins.mkCons(Builtins.constrData(1,
                Builtins.mkCons(Builtins.bData(hash), Builtins.mkNilData())), Builtins.mkNilData()));
        return valueOf(redeemers, purpose);
    }

    private static PlutusData valueOf(PlutusData pairs, PlutusData key) {
        if (Builtins.nullList(pairs)) {
            return Builtins.iData(-1);
        }
        PlutusData pair = Builtins.headList(pairs);
        return Builtins.equalsData(Builtins.fstPair(pair), key) ? Builtins.sndPair(pair)
                : valueOf(Builtins.tailList(pairs), key);
    }

    /** {@code DelegatedActionV1 = Constr 0 [custodyHash, action]}. */
    public static PlutusData delegatedAction(byte[] custodyHash, PlutusData action) {
        return Builtins.constrData(0, Builtins.mkCons(Builtins.bData(custodyHash),
                Builtins.mkCons(action, Builtins.mkNilData())));
    }

    // ------------------------------------------------------------------
    // Values and vault shapes

    /** The value holds lovelace and at most the one vault asset {@code policy.name}, and nothing else. */
    public static boolean cleanValue(Value value, byte[] policy, byte[] name) {
        PlutusData rest = Builtins.tailList(Builtins.unMapData(value));
        if (Builtins.nullList(rest)) {
            return true;
        }
        PlutusData entry = Builtins.headList(rest);
        PlutusData tokens = Builtins.unMapData(Builtins.sndPair(entry));
        return Builtins.nullList(Builtins.tailList(rest))
                && Builtins.equalsByteString(Builtins.unBData(Builtins.fstPair(entry)), policy)
                && Builtins.nullList(Builtins.tailList(tokens))
                && Builtins.equalsByteString(Builtins.unBData(Builtins.fstPair(Builtins.headList(tokens))), name);
    }

    /**
     * V10, the native vault's acceptance rule: {@code FloorTopUpV1} holding lovelace only, or a canonical
     * {@code VaultContinuationV1} (at most 64 ids) holding only lovelace and the vault asset. Total over every datum.
     */
    public static boolean accepted(PlutusData datum, Value value, byte[] policy, byte[] name) {
        if (Builtins.equalsData(datum, Builtins.constrData(2, Builtins.mkNilData()))) {
            return Builtins.nullList(Builtins.tailList(Builtins.unMapData(value)));
        }
        byte[] encoded = Builtins.serialiseData(datum);
        long length = Builtins.lengthOfByteString(encoded);
        // Big-endian: d87a9f80ff, d87a9f9f and ffff.
        return cleanValue(value, policy, name)
                && ((length == 5 && Builtins.byteStringToInteger(true, encoded)
                .equals(BigInteger.valueOf(929770209535L)))
                || (Builtins.byteStringToInteger(true, Builtins.sliceByteString(0, 4, encoded))
                .equals(BigInteger.valueOf(3631914911L))
                && Builtins.byteStringToInteger(true, Builtins.sliceByteString(length - 2, 2, encoded))
                .equals(BigInteger.valueOf(65535L))
                && (length - 6) % 34 == 0 && length - 6 <= 64 * 34
                && idChunks(encoded, 4, length - 2)));
    }

    private static boolean idChunks(byte[] encoded, long at, long end) {
        if (at == end) {
            return true;
        }
        return Builtins.indexByteString(encoded, at) == 88 && Builtins.indexByteString(encoded, at + 1) == 32
                && idChunks(encoded, at + 34, end);
    }

    /** V10 over every vault input. */
    public static boolean allAccepted(JulcList<TxInInfo> vaults, byte[] policy, byte[] name) {
        if (vaults.isEmpty()) {
            return true;
        }
        TxOut out = vaults.head().resolved();
        return accepted(GatewayTx.inlineDatum(out.datum()), out.value(), policy, name)
                && allAccepted(vaults.tail(), policy, name);
    }

    public static BigInteger inputLovelace(JulcList<TxInInfo> vaults) {
        if (vaults.isEmpty()) {
            return BigInteger.ZERO;
        }
        return ValuesLib.lovelaceOf(vaults.head().resolved().value()).add(inputLovelace(vaults.tail()));
    }

    public static BigInteger inputAsset(JulcList<TxInInfo> vaults, byte[] policy, byte[] name) {
        if (vaults.isEmpty()) {
            return BigInteger.ZERO;
        }
        return ValuesLib.assetOf(vaults.head().resolved().value(), policy, name)
                .add(inputAsset(vaults.tail(), policy, name));
    }

    // ------------------------------------------------------------------
    // ProgrammableDepositDatumV1 (D6)

    /**
     * The total deposit-shaped predicate (D6, r4): an inline {@code Constr 0} datum of exactly four fields, a 28-byte
     * beneficiary, a refund address of 1 to 64 bytes that is a key-owned smart wallet of the pinned deployment, a
     * 28-byte refund key hash, and an origin {@code Constr 0 [32-byte tx id, uint, uint]} with no other field, where
     * each uint is below 2^64. It never fails: every field's kind is read from its canonical serialization before it
     * is decoded, so any other datum is simply not deposit-shaped.
     */
    public static boolean depositShaped(PlutusData datum, byte[] plb, long networkId) {
        if (!isConstr0(datum)) {
            return false;
        }
        PlutusData f0 = Builtins.constrFields(datum);
        if (Builtins.nullList(f0)) {
            return false;
        }
        PlutusData f1 = Builtins.tailList(f0);
        if (Builtins.nullList(f1)) {
            return false;
        }
        PlutusData f2 = Builtins.tailList(f1);
        if (Builtins.nullList(f2)) {
            return false;
        }
        PlutusData f3 = Builtins.tailList(f2);
        if (Builtins.nullList(f3) || !Builtins.nullList(Builtins.tailList(f3))) {
            return false;
        }
        PlutusData origin = Builtins.headList(f3);
        return bytesOfLength(Builtins.headList(f0), 28)
                && isBytes(Builtins.headList(f1))
                && rawSmartWallet(Builtins.unBData(Builtins.headList(f1)), plb, networkId)
                && bytesOfLength(Builtins.headList(f2), 28)
                && originShaped(origin);
    }

    private static boolean originShaped(PlutusData origin) {
        if (!isConstr0(origin)) {
            return false;
        }
        PlutusData o0 = Builtins.constrFields(origin);
        if (Builtins.nullList(o0)) {
            return false;
        }
        PlutusData o1 = Builtins.tailList(o0);
        if (Builtins.nullList(o1)) {
            return false;
        }
        PlutusData o2 = Builtins.tailList(o1);
        return !Builtins.nullList(o2) && Builtins.nullList(Builtins.tailList(o2))
                && bytesOfLength(Builtins.headList(o0), 32)
                && isUInt64(Builtins.headList(o1))
                && isUInt64(Builtins.headList(o2));
    }

    /** {@code Constr 0}: the canonical serialization starts with tag 121, {@code d879}. */
    private static boolean isConstr0(PlutusData data) {
        byte[] encoded = Builtins.serialiseData(data);
        return Builtins.indexByteString(encoded, 0) == 216 && Builtins.indexByteString(encoded, 1) == 121;
    }

    /** A byte string: CBOR major type 2 ({@code 0x40} to {@code 0x5f}). */
    private static boolean isBytes(PlutusData data) {
        long first = Builtins.indexByteString(Builtins.serialiseData(data), 0);
        return first >= 64 && first <= 95;
    }

    private static boolean bytesOfLength(PlutusData data, long length) {
        return isBytes(data) && Builtins.lengthOfByteString(Builtins.unBData(data)) == length;
    }

    /** An unsigned integer below 2^64: CBOR major type 0 ({@code 0x00} to {@code 0x1b}). */
    private static boolean isUInt64(PlutusData data) {
        return Builtins.indexByteString(Builtins.serialiseData(data), 0) <= 27;
    }

    /** Every vault input is deposit-shaped and holds only the vault asset and lovelace. */
    public static boolean allDepositShaped(JulcList<TxInInfo> vaults, byte[] plb, long networkId, byte[] policy,
                                           byte[] name) {
        if (vaults.isEmpty()) {
            return true;
        }
        TxOut out = vaults.head().resolved();
        return depositShaped(GatewayTx.inlineDatum(out.datum()), plb, networkId)
                && cleanValue(out.value(), policy, name)
                && allDepositShaped(vaults.tail(), plb, networkId, policy, name);
    }

    /**
     * The origin-keyed {@code DepositId} of a deposit-shaped datum:
     * {@code blake2b-256("deposit-cip113\0" ‖ [vault_id, [origin_tx_id, origin_index], output_index])}.
     */
    public static byte[] depositId(byte[] vaultId, PlutusData datum) {
        PlutusData origin = Builtins.constrFields(Builtins.headList(Builtins.tailList(Builtins.tailList(
                Builtins.tailList(Builtins.constrFields(datum))))));
        byte[] txId = Builtins.unBData(Builtins.headList(origin));
        PlutusData rest = Builtins.tailList(origin);
        BigInteger originIndex = Builtins.unIData(Builtins.headList(rest));
        BigInteger outputIndex = Builtins.unIData(Builtins.headList(Builtins.tailList(rest)));
        byte[] body = Builtins.appendByteString(Builtins.appendByteString(
                Builtins.consByteString(131, GatewayCodec.bytes(vaultId)),
                GatewayCodec.outRefCbor(txId, originIndex)), GatewayCodec.uint(outputIndex));
        return Builtins.blake2b_256(Builtins.appendByteString(depositTag(), body));
    }

    /** {@code refund_address} (field 1) of a deposit-shaped datum. */
    public static byte[] refundAddress(PlutusData datum) {
        return Builtins.unBData(Builtins.headList(Builtins.tailList(Builtins.constrFields(datum))));
    }

    /** {@code refund_key_hash} (field 2) of a deposit-shaped datum. */
    public static byte[] refundKeyHash(PlutusData datum) {
        return Builtins.unBData(Builtins.headList(Builtins.tailList(Builtins.tailList(Builtins.constrFields(datum)))));
    }

    // ------------------------------------------------------------------
    // Payouts, the continuation and the shard inserts

    /**
     * V5: the output pays the raw {@code recipient} exactly {@code quantity} of the vault asset plus at least
     * {@code vaultLovelace}, at a payable address (D7), with no datum hash and no reference script.
     */
    public static boolean paysTo(TxOut output, byte[] recipient, BigInteger quantity, BigInteger vaultLovelace,
                                 byte[] plb, byte[] policy, byte[] name, long networkId) {
        PlutusData address = output.address().toPlutusData();
        return ValuesLib.assetOf(output.value(), policy, name).equals(quantity)
                && ValuesLib.lovelaceOf(output.value()).compareTo(vaultLovelace) >= 0
                && payable(address, plb, quantity.signum() > 0)
                && Builtins.constrTag(output.datum().toPlutusData()) != 1
                && output.referenceScript().isEmpty()
                && Builtins.equalsByteString(GatewayCodec.addressBytes(address, networkId), recipient);
    }

    /**
     * V6, V7, V15 and V16: output {@code index} is the one output at the vault smart wallet {@code vault}, holds only
     * the vault asset and lovelace, carries exactly {@code in - debit} of each and at least {@code floor} lovelace, no
     * reference script and the inline {@code VaultContinuationV1(ids)} with at most {@code maxIds} ids; no output pays
     * {@code h} or {@code e}.
     */
    public static boolean continuationValid(JulcList<TxOut> outputs, long index, JulcList<TxInInfo> vaults,
                                            PlutusData vault, byte[] custodyHash, byte[] delegateHash,
                                            BigInteger quantity, BigInteger lovelace, PlutusData ids, long idCount,
                                            long maxIds, BigInteger floor, byte[] policy, byte[] name) {
        TxOut out = outputs.get(index);
        Value value = out.value();
        BigInteger outLovelace = ValuesLib.lovelaceOf(value);
        return idCount <= maxIds
                && outLovelace.equals(inputLovelace(vaults).subtract(lovelace))
                && ValuesLib.assetOf(value, policy, name).equals(inputAsset(vaults, policy, name).subtract(quantity))
                && outLovelace.compareTo(floor) >= 0
                && cleanValue(value, policy, name)
                && out.referenceScript().isEmpty()
                && Builtins.equalsData(out.address().toPlutusData(), vault)
                && vaultOutputs(outputs, vault, custodyHash, delegateHash, 1)
                && Builtins.equalsData(GatewayTx.inlineDatum(out.datum()),
                Builtins.constrData(1, Builtins.mkCons(Builtins.listData(ids), Builtins.mkNilData())));
    }

    /**
     * {@code Accept} and {@code Consolidate}: the vault inputs merge into one exact {@code VaultContinuationV1([])}
     * wherever it sits among the outputs; no shard and no other script input. The floor does not apply.
     */
    public static boolean mergeValid(TxInfo tx, JulcList<TxInInfo> vaults, PlutusData vault, byte[] custodyHash,
                                     byte[] delegateHash, byte[] shardPolicy, byte[] policy, byte[] name) {
        JulcList<TxOut> outputs = tx.outputs();
        long index = outputIndex(outputs, vault, 0);
        return foreignInputs(tx.inputs(), vault, shardPolicy).signum() == 0
                && index >= 0
                && continuationValid(outputs, index, vaults, vault, custodyHash, delegateHash, BigInteger.ZERO,
                BigInteger.ZERO, Builtins.mkNilData(), 0, 0, BigInteger.ZERO, policy, name);
    }

    private static long outputIndex(JulcList<TxOut> outputs, PlutusData address, long index) {
        if (outputs.isEmpty()) {
            return -1;
        }
        if (Builtins.equalsData(outputs.head().address().toPlutusData(), address)) {
            return index;
        }
        return outputIndex(outputs.tail(), address, index + 1);
    }

    /**
     * The ids that inputs carrying the shard thread token {@code [shardIndex]} insert, concatenated in redeemer
     * order; with exactly one shard input that is that shard's insert list.
     */
    public static byte[] shardInserts(TxInfo tx, byte[] shardPolicy, BigInteger shardIndex) {
        byte[] name = Builtins.consByteString(shardIndex.longValue(), Builtins.emptyByteString());
        return shardInsertsIn(tx, tx.inputs(), shardPolicy, name);
    }

    private static byte[] shardInsertsIn(TxInfo tx, JulcList<TxInInfo> inputs, byte[] shardPolicy, byte[] name) {
        if (inputs.isEmpty()) {
            return Builtins.emptyByteString();
        }
        TxInInfo input = inputs.head();
        byte[] rest = shardInsertsIn(tx, inputs.tail(), shardPolicy, name);
        return ValuesLib.assetOf(input.resolved().value(), shardPolicy, name).equals(BigInteger.ONE)
                ? Builtins.appendByteString(insertIds(Builtins.unListData(Builtins.headList(Builtins.constrFields(
                GatewayTx.spendRedeemer(tx, input.outRef()))))), rest)
                : rest;
    }

    private static byte[] insertIds(PlutusData inserts) {
        if (Builtins.nullList(inserts)) {
            return Builtins.emptyByteString();
        }
        return Builtins.appendByteString(
                Builtins.unBData(Builtins.headList(Builtins.constrFields(Builtins.headList(inserts)))),
                insertIds(Builtins.tailList(inserts)));
    }

    /** The id cap of V16: {@code floor((maxInlineDatumBytes - 6) / 34)}, and at most 64. */
    public static long maxIds(BigInteger maxInlineDatumBytes) {
        long cap = (maxInlineDatumBytes.longValue() - 6) / 34;
        return cap < 64 ? cap : 64;
    }

    static byte[] depositTag() {
        return Builtins.appendByteString("deposit-cip113".getBytes(),
                Builtins.consByteString(0, Builtins.emptyByteString()));
    }
}
