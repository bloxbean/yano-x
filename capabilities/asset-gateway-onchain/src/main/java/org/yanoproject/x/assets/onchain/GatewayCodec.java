package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.OnchainLibrary;

import java.math.BigInteger;

/**
 * The ADR-059 contract encodings, reproduced on chain byte for byte.
 *
 * <p>App-ledger leaves and identifiers use the strict deterministic CBOR subset of
 * {@code asset-gateway-v1.cddl}: definite arrays, byte strings and unsigned integers in shortest form. The scripts
 * build the expected bytes from redeemer fields and pinned parameters (never take them from the redeemer), so a
 * proof of any other key or value fails (V2, I27). The test suite checks every function here against the Java
 * contract types ({@code GatewayIds}, {@code ClaimLeafV1}, {@code ExitLeafV1}, {@code DepositRecordV1}).
 *
 * <p>Identifier tags are ASCII strings ending in NUL ({@code GatewayIds}).
 */
@OnchainLibrary
public final class GatewayCodec {
    /**
     * A {@code DomainStatusV1} leaf: {@code status} is 0 ACTIVE, 1 PAUSED, 2 HALTED, or {@code -1} when the bytes
     * are not one. ({@code DepositRecordV1.status} is 0 CREDITED, 1 UNCREDITED.)
     */
    public record DomainStatus(BigInteger status, BigInteger sinceMs, BigInteger exitDelayMs) {
    }

    /** An unsigned integer read at an offset, and the offset after it; {@code value} is -1 when invalid. */
    public record UInt(BigInteger value, long next) {
    }

    private GatewayCodec() {
    }

    // ------------------------------------------------------------------
    // Deterministic CBOR

    /** The shortest-form head of major type {@code major} with argument {@code n >= 0}. */
    public static byte[] head(long major, BigInteger n) {
        long base = major * 32;
        if (n.compareTo(BigInteger.valueOf(24)) < 0) {
            return Builtins.consByteString(base + n.longValue(), Builtins.emptyByteString());
        }
        if (n.compareTo(BigInteger.valueOf(256)) < 0) {
            return Builtins.consByteString(base + 24, Builtins.integerToByteString(true, 1, n));
        }
        if (n.compareTo(BigInteger.valueOf(65536)) < 0) {
            return Builtins.consByteString(base + 25, Builtins.integerToByteString(true, 2, n));
        }
        if (n.compareTo(BigInteger.valueOf(4294967296L)) < 0) {
            return Builtins.consByteString(base + 26, Builtins.integerToByteString(true, 4, n));
        }
        return Builtins.consByteString(base + 27, Builtins.integerToByteString(true, 8, n));
    }

    public static byte[] uint(BigInteger n) {
        return head(0, n);
    }

    public static byte[] bytes(byte[] value) {
        return Builtins.appendByteString(
                head(2, BigInteger.valueOf(Builtins.lengthOfByteString(value))), value);
    }

    /** {@code asset_value} of one vault asset: {@code []} for zero, else {@code [[policy, name, quantity]]}. */
    public static byte[] assetValue(byte[] policyId, byte[] assetName, BigInteger quantity) {
        if (quantity.signum() == 0) {
            return Builtins.consByteString(128, Builtins.emptyByteString());
        }
        byte[] entry = Builtins.appendByteString(
                Builtins.appendByteString(Builtins.consByteString(131, bytes(policyId)), bytes(assetName)),
                uint(quantity));
        return Builtins.consByteString(129, entry);
    }

    // ------------------------------------------------------------------
    // Identifiers (GatewayIds)

    /** {@code blake2b-256("yano-asset-gateway-vault-v1\0" ‖ [domain_id, [policy, name], ordinal])}. */
    public static byte[] vaultId(byte[] domainId, byte[] policyId, byte[] assetName, BigInteger ordinal) {
        byte[] asset = Builtins.appendByteString(Builtins.consByteString(130, bytes(policyId)), bytes(assetName));
        byte[] body = Builtins.appendByteString(
                Builtins.appendByteString(Builtins.consByteString(131, bytes(domainId)), asset), uint(ordinal));
        return Builtins.blake2b_256(Builtins.appendByteString(vaultTag(), body));
    }

    /** {@code blake2b-256("yano-asset-gateway-deposit-v1\0" ‖ [vault_id, tx_id, output_index])}. */
    public static byte[] depositId(byte[] vaultId, byte[] txId, BigInteger outputIndex) {
        byte[] body = Builtins.appendByteString(
                Builtins.appendByteString(Builtins.consByteString(131, bytes(vaultId)), bytes(txId)),
                uint(outputIndex));
        return Builtins.blake2b_256(Builtins.appendByteString(depositTag(), body));
    }

    /**
     * {@code blake2b-256("yano-asset-gateway-claim-v1\0" ‖ [domain_id, vault_id, kind, withdrawal_key, recipient,
     * asset_value, vault_lovelace, sequence])}, for a claim on one vault asset.
     */
    public static byte[] claimId(byte[] domainId, byte[] vaultId, BigInteger kind, byte[] withdrawalKey,
                                 byte[] recipient, byte[] policyId, byte[] assetName, BigInteger quantity,
                                 BigInteger vaultLovelace, BigInteger sequence) {
        byte[] a = Builtins.appendByteString(Builtins.consByteString(136, bytes(domainId)), bytes(vaultId));
        byte[] b = Builtins.appendByteString(Builtins.appendByteString(a, uint(kind)), bytes(withdrawalKey));
        byte[] c = Builtins.appendByteString(Builtins.appendByteString(b, bytes(recipient)),
                assetValue(policyId, assetName, quantity));
        byte[] body = Builtins.appendByteString(Builtins.appendByteString(c, uint(vaultLovelace)), uint(sequence));
        return Builtins.blake2b_256(Builtins.appendByteString(claimTag(), body));
    }

    /** The escape nullifier {@code blake2b-256("escape\0" ‖ vault_id ‖ holder_id)}, a raw concatenation. */
    public static byte[] escapeId(byte[] vaultId, byte[] holderId) {
        return Builtins.blake2b_256(Builtins.appendByteString(
                Builtins.appendByteString(escapeTag(), vaultId), holderId));
    }

    /**
     * The CBOR of a Cardano {@code transaction_input}, {@code [transaction_id, index]}: {@code 82 5820 <txId>
     * <uint index>}. It is the outpoint encoding a SWEEP_UNATTRIBUTED claim's withdrawal key holds, and the
     * preimage of a forced request id.
     */
    public static byte[] outRefCbor(byte[] txId, BigInteger index) {
        return Builtins.appendByteString(Builtins.consByteString(130, bytes(txId)), uint(index));
    }

    /** {@code requestId = blake2b-256("forced\0" ‖ outRefCbor(first spent input))} (D8g R1). */
    public static byte[] forcedRequestId(byte[] txId, BigInteger index) {
        return Builtins.blake2b_256(Builtins.appendByteString(forcedTag(), outRefCbor(txId, index)));
    }

    // ------------------------------------------------------------------
    // Leaves

    /**
     * {@code ClaimLeafV1}: {@code [1, claim_id, vault_id, kind, asset_value, vault_lovelace, recipient,
     * reserved_height, reserved_at_ms]}.
     */
    public static byte[] claimLeaf(byte[] claimId, byte[] vaultId, BigInteger kind, byte[] policyId,
                                   byte[] assetName, BigInteger quantity, BigInteger vaultLovelace,
                                   byte[] recipient, BigInteger reservedHeight, BigInteger reservedAtMs) {
        byte[] a = Builtins.appendByteString(
                Builtins.consByteString(137, Builtins.consByteString(1, bytes(claimId))), bytes(vaultId));
        byte[] b = Builtins.appendByteString(Builtins.appendByteString(a, uint(kind)),
                assetValue(policyId, assetName, quantity));
        byte[] c = Builtins.appendByteString(Builtins.appendByteString(b, uint(vaultLovelace)), bytes(recipient));
        return Builtins.appendByteString(Builtins.appendByteString(c, uint(reservedHeight)), uint(reservedAtMs));
    }

    /** {@code ExitLeafV1}: {@code [1, holder_key_hash, vault_id, amount, vault_lovelace]}. */
    public static byte[] exitLeaf(byte[] holderKeyHash, byte[] vaultId, BigInteger amount,
                                  BigInteger vaultLovelace) {
        byte[] a = Builtins.appendByteString(
                Builtins.consByteString(133, Builtins.consByteString(1, bytes(holderKeyHash))), bytes(vaultId));
        return Builtins.appendByteString(Builtins.appendByteString(a, uint(amount)), uint(vaultLovelace));
    }

    /**
     * The status of a {@code DepositRecordV1} whose {@code deposit_id} and {@code vault_id} are the expected ones;
     * {@code -1} otherwise. The record is {@code [1, deposit_id, vault_id, tx_id, output_index, slot, height, shape,
     * status, ...]}, a 17-element array.
     */
    public static BigInteger depositStatus(byte[] record, byte[] depositId, byte[] vaultId) {
        long length = Builtins.lengthOfByteString(record);
        if (length < 110
                || Builtins.indexByteString(record, 0) != 145
                || Builtins.indexByteString(record, 1) != 1
                || !Builtins.equalsByteString(Builtins.sliceByteString(2, 34, record), bytes(depositId))
                || !Builtins.equalsByteString(Builtins.sliceByteString(36, 34, record), bytes(vaultId))
                || Builtins.indexByteString(record, 70) != 88
                || Builtins.indexByteString(record, 71) != 32) {
            return BigInteger.valueOf(-1);
        }
        UInt outputIndex = readUInt(record, 104);
        UInt slot = readUInt(record, outputIndex.next());
        UInt height = readUInt(record, slot.next());
        UInt shape = readUInt(record, height.next());
        UInt status = readUInt(record, shape.next());
        return outputIndex.value().signum() < 0 || slot.value().signum() < 0 || height.value().signum() < 0
                || shape.value().signum() < 0 ? BigInteger.valueOf(-1) : status.value();
    }

    /** {@code DomainStatusV1}: {@code [1, status, since_height, since_ms, exit_delay_ms]}, exactly. */
    public static DomainStatus domainStatus(byte[] leaf) {
        long length = Builtins.lengthOfByteString(leaf);
        if (length < 6 || Builtins.indexByteString(leaf, 0) != 133 || Builtins.indexByteString(leaf, 1) != 1) {
            return new DomainStatus(BigInteger.valueOf(-1), BigInteger.ZERO, BigInteger.ZERO);
        }
        UInt status = readUInt(leaf, 2);
        UInt sinceHeight = readUInt(leaf, status.next());
        UInt sinceMs = readUInt(leaf, sinceHeight.next());
        UInt exitDelay = readUInt(leaf, sinceMs.next());
        boolean valid = exitDelay.next() == length
                && status.value().signum() >= 0 && status.value().compareTo(BigInteger.TWO) <= 0
                && sinceHeight.value().signum() >= 0 && sinceMs.value().signum() >= 0
                && exitDelay.value().signum() >= 0;
        return valid ? new DomainStatus(status.value(), sinceMs.value(), exitDelay.value())
                : new DomainStatus(BigInteger.valueOf(-1), BigInteger.ZERO, BigInteger.ZERO);
    }

    /** A shortest-form CBOR unsigned integer; value -1 on anything else. */
    public static UInt readUInt(byte[] encoded, long offset) {
        long length = Builtins.lengthOfByteString(encoded);
        if (offset < 0 || offset >= length) return new UInt(BigInteger.valueOf(-1), length + 1);
        long first = Builtins.indexByteString(encoded, offset);
        if (first < 24) return new UInt(BigInteger.valueOf(first), offset + 1);
        long width = first == 24 ? 1 : first == 25 ? 2 : first == 26 ? 4 : first == 27 ? 8 : 0;
        if (width == 0 || offset + 1 + width > length) return new UInt(BigInteger.valueOf(-1), length + 1);
        BigInteger value = Builtins.byteStringToInteger(true, Builtins.sliceByteString(offset + 1, width, encoded));
        BigInteger minimum = width == 1 ? BigInteger.valueOf(24) : width == 2 ? BigInteger.valueOf(256)
                : width == 4 ? BigInteger.valueOf(65536) : BigInteger.valueOf(4294967296L);
        return value.compareTo(minimum) >= 0 ? new UInt(value, offset + 1 + width)
                : new UInt(BigInteger.valueOf(-1), length + 1);
    }

    // ------------------------------------------------------------------
    // Addresses

    /**
     * The raw Shelley bytes of a Plutus address: base ({@code 0x0-0x3}) or enterprise ({@code 0x6}, {@code 0x7})
     * with network id {@code networkId}. Empty for a pointer address or a malformed credential.
     */
    public static byte[] addressBytes(PlutusData address, long networkId) {
        PlutusData fields = Builtins.constrFields(address);
        PlutusData payment = Builtins.headList(fields);
        PlutusData stake = Builtins.headList(Builtins.tailList(fields));
        long paymentTag = Builtins.constrTag(payment);
        byte[] paymentHash = Builtins.unBData(Builtins.headList(Builtins.constrFields(payment)));
        if (Builtins.lengthOfByteString(paymentHash) != 28 || paymentTag > 1) {
            return Builtins.emptyByteString();
        }
        if (Builtins.constrTag(stake) == 1) {
            return Builtins.consByteString((6 + paymentTag) * 16 + networkId, paymentHash);
        }
        PlutusData staking = Builtins.headList(Builtins.constrFields(stake));
        if (Builtins.constrTag(staking) != 0) {
            return Builtins.emptyByteString();
        }
        PlutusData credential = Builtins.headList(Builtins.constrFields(staking));
        long stakeTag = Builtins.constrTag(credential);
        byte[] stakeHash = Builtins.unBData(Builtins.headList(Builtins.constrFields(credential)));
        if (Builtins.lengthOfByteString(stakeHash) != 28 || stakeTag > 1) {
            return Builtins.emptyByteString();
        }
        return Builtins.consByteString((paymentTag + 2 * stakeTag) * 16 + networkId,
                Builtins.appendByteString(paymentHash, stakeHash));
    }

    /** The payment-key hash of an address whose payment credential is a key; empty otherwise. */
    public static byte[] paymentKeyHash(PlutusData address) {
        PlutusData payment = Builtins.headList(Builtins.constrFields(address));
        return Builtins.constrTag(payment) == 0
                ? Builtins.unBData(Builtins.headList(Builtins.constrFields(payment)))
                : Builtins.emptyByteString();
    }

    /** The script hash of an address whose payment credential is a script; empty otherwise. */
    public static byte[] paymentScriptHash(PlutusData address) {
        PlutusData payment = Builtins.headList(Builtins.constrFields(address));
        return Builtins.constrTag(payment) == 1
                ? Builtins.unBData(Builtins.headList(Builtins.constrFields(payment)))
                : Builtins.emptyByteString();
    }

    /** True when {@code address} is exactly the enterprise script address of {@code scriptHash} (no stake part). */
    public static boolean isEnterpriseScript(PlutusData address, byte[] scriptHash) {
        PlutusData fields = Builtins.constrFields(address);
        PlutusData payment = Builtins.headList(fields);
        PlutusData stake = Builtins.headList(Builtins.tailList(fields));
        return Builtins.constrTag(payment) == 1
                && Builtins.equalsByteString(Builtins.unBData(Builtins.headList(Builtins.constrFields(payment))),
                scriptHash)
                && Builtins.constrTag(stake) == 1;
    }

    // ------------------------------------------------------------------
    // Tags

    static byte[] vaultTag() {
        return Builtins.appendByteString("yano-asset-gateway-vault-v1".getBytes(), nul());
    }

    static byte[] depositTag() {
        return Builtins.appendByteString("yano-asset-gateway-deposit-v1".getBytes(), nul());
    }

    static byte[] claimTag() {
        return Builtins.appendByteString("yano-asset-gateway-claim-v1".getBytes(), nul());
    }

    static byte[] escapeTag() {
        return Builtins.appendByteString("escape".getBytes(), nul());
    }

    static byte[] forcedTag() {
        return Builtins.appendByteString("forced".getBytes(), nul());
    }

    private static byte[] nul() {
        return Builtins.consByteString(0, Builtins.emptyByteString());
    }
}
