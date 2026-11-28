package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatusV1;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.util.List;
import java.util.Optional;

/**
 * The committed-state reads a consumer serves to the settlement executor and the exit relayer (ADR-059 D9): the
 * executor never trusts an effect payload or a co-sign request for claim fields; it reads them, with their status and
 * the domain status leaf, from the consumer's finalized state through {@code AppStateMachine.query}.
 *
 * <pre>
 * asset-gateway/claim             request claim_id (32 bytes)   response [leaf: bytes, status: bytes]  (empty = absent)
 * asset-gateway/domain-status     request empty                 response DomainStatusV1 bytes, or empty
 * asset-gateway/claim-count       request empty                 response [count: uint]
 * asset-gateway/claim-by-sequence request [sequence: uint]      response claim_id, or empty
 * asset-gateway/deposit           request deposit_id (32 bytes) response DepositRecordV1 bytes, or empty
 * asset-gateway/vault-counters    request vault_id (32 bytes)   response [committed_height: uint, VaultCounters bytes]
 * asset-gateway/vault-hold        request vault_id (32 bytes)   response [held: 0 / 1], or empty for an unknown vault
 * asset-gateway/cip113-wiring     request dispatcher (28 bytes) response [fields_key, transfer_delegate,
 *                                                               third_party_delegate, unfracking_delegate], or empty
 * </pre>
 *
 * <p>ADR-061: the vault hold is the CIP-113 asset's {@code ISSUER_HOLD} (D14), under which members refuse to co-sign
 * a {@code Settle}; the wiring query is the approved tuple whose dispatcher a vault transaction uses (D10 rule 6),
 * which builders and members read from committed state, never from a request.
 *
 * The claim index is dense: every reserved claim has one sequence, from 0, so a relayer can walk every claim without a
 * range scan. The deposit record is what an {@code Accept} co-signer checks (D8f, D8h §1 step 4): the deposit is
 * {@code CREDITED}, admitted at or below the referenced anchor's height. The vault counters, with the committed height
 * they were read at, are the consensus side of the node-local reconciliation (D15); a vault with no counters yet
 * answers {@link AssetAccounts.VaultCounters#ZERO}.
 */
public final class SettlementQueries {
    public static final String CLAIM = "asset-gateway/claim";
    public static final String DOMAIN_STATUS = "asset-gateway/domain-status";
    public static final String CLAIM_COUNT = "asset-gateway/claim-count";
    public static final String CLAIM_BY_SEQUENCE = "asset-gateway/claim-by-sequence";
    public static final String DEPOSIT = "asset-gateway/deposit";
    /**
     * ADR-061 D14: a vault's registry entry by its 32-byte {@code VaultId}, with its custody kind, its CIP-113 pins
     * and so its trust labels ({@code ISSUER_CONTROLLED} for a CIP-113 asset); empty for an unknown vault.
     */
    public static final String REGISTRY = "asset-gateway/registry";
    /** ADR-061 D6: a CIP-113 vault output's {@code vault-output/<outpoint>} record, by the outpoint's CBOR. */
    public static final String VAULT_OUTPUT = "asset-gateway/vault-output";
    public static final String VAULT_COUNTERS = "asset-gateway/vault-counters";
    public static final String VAULT_HOLD = "asset-gateway/vault-hold";
    public static final String CIP113_WIRING = "asset-gateway/cip113-wiring";

    /** {@code [held]}: whether a CIP-113 vault's asset is in {@code ISSUER_HOLD} (ADR-061 D14). */
    public static byte[] encodeHold(boolean held) {
        Array array = new Array();
        array.add(AssetCbor.uint(held ? 1 : 0));
        return AssetCbor.encode(array);
    }

    /** The hold flag; empty for an unknown vault (an empty answer) or an answer that does not decode. */
    public static Optional<Boolean> decodeHold(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return Optional.empty();
        try {
            long held = AssetCbor.uintLong(AssetCbor.decodeArray(bytes, 1).getDataItems().getFirst());
            return held > 1 ? Optional.empty() : Optional.of(held == 1);
        } catch (IllegalArgumentException | ArithmeticException malformed) {
            return Optional.empty();
        }
    }

    /** The approved tuple of a dispatcher, as {@link #CIP113_WIRING} answers it. */
    public static byte[] encodeWiring(Cip113Wiring.ApprovedTuple tuple) {
        Array array = new Array();
        array.add(AssetCbor.bstr(tuple.fields().key()));
        array.add(AssetCbor.bstr(tuple.transferDelegate()));
        array.add(AssetCbor.bstr(tuple.thirdPartyDelegate()));
        array.add(AssetCbor.bstr(tuple.unfrackingDelegate()));
        return AssetCbor.encode(array);
    }

    /** The approved tuple; empty when none is approved for the dispatcher, or the answer does not decode. */
    public static Optional<Cip113Wiring.ApprovedTuple> decodeWiring(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return Optional.empty();
        try {
            List<DataItem> items = AssetCbor.decodeArray(bytes, 4).getDataItems();
            return Optional.of(new Cip113Wiring.ApprovedTuple(Cip113WiringClaimV1.Fields.ofKey(AssetCbor.bytes(
                    items.get(0), Cip113WiringClaimV1.Fields.KEY_BYTES, Cip113WiringClaimV1.Fields.KEY_BYTES)),
                    AssetCbor.bytes(items.get(1), 28, 28), AssetCbor.bytes(items.get(2), 28, 28),
                    AssetCbor.bytes(items.get(3), 28, 28)));
        } catch (IllegalArgumentException | ArithmeticException malformed) {
            return Optional.empty();
        }
    }

    private SettlementQueries() {
    }

    /** One claim as committed: its immutable leaf and its status. */
    public record ClaimView(ClaimLeafV1 leaf, ClaimStatusV1 status) {
    }

    public static byte[] encodeClaim(Optional<byte[]> leaf, Optional<byte[]> status) {
        Array array = new Array();
        array.add(AssetCbor.bstr(leaf.orElse(new byte[0])));
        array.add(AssetCbor.bstr(status.orElse(new byte[0])));
        return AssetCbor.encode(array);
    }

    /**
     * The claim of a {@link #CLAIM} response, when both records are present, decode and name the requested id.
     * Anything else is absent: a consumer that cannot show both records has no payable claim.
     */
    public static Optional<ClaimView> decodeClaim(Hash32 claimId, byte[] response) {
        try {
            List<DataItem> items = AssetCbor.decodeArray(response, 2).getDataItems();
            byte[] leaf = AssetCbor.bytes(items.get(0), 0, AssetCbor.MAX_WIRE_BYTES);
            byte[] status = AssetCbor.bytes(items.get(1), 0, AssetCbor.MAX_WIRE_BYTES);
            if (leaf.length == 0 || status.length == 0) return Optional.empty();
            ClaimView view = new ClaimView(ClaimLeafV1.decode(leaf), ClaimStatusV1.decode(status));
            if (!view.leaf().claimId().equals(claimId) || !view.status().claimId().equals(claimId)) {
                return Optional.empty();
            }
            return Optional.of(view);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    public static Optional<DomainStatusV1> decodeDomainStatus(byte[] response) {
        try {
            return response == null || response.length == 0 ? Optional.empty()
                    : Optional.of(DomainStatusV1.decode(response));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    /**
     * The record of a {@link #DEPOSIT} response, when it decodes and names the requested id. Anything else is absent,
     * so a co-signer that cannot show the record refuses.
     */
    public static Optional<DepositRecordV1> decodeDeposit(Hash32 depositId, byte[] response) {
        try {
            if (response == null || response.length == 0) return Optional.empty();
            DepositRecordV1 record = DepositRecordV1.decode(response);
            return record.depositId().equals(depositId) ? Optional.of(record) : Optional.empty();
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    /** One vault's I4 counters at one committed height. */
    public record CountersView(long height, AssetAccounts.VaultCounters counters) {
    }

    public static byte[] encodeCounters(long height, AssetAccounts.VaultCounters counters) {
        Array array = new Array();
        array.add(AssetCbor.uint(height));
        array.add(AssetCbor.bstr(counters.encode()));
        return AssetCbor.encode(array);
    }

    /** The counters of a {@link #VAULT_COUNTERS} response; empty when it does not decode. */
    public static Optional<CountersView> decodeCounters(byte[] response) {
        try {
            List<DataItem> items = AssetCbor.decodeArray(response, 2).getDataItems();
            return Optional.of(new CountersView(AssetCbor.uintLong(items.get(0)),
                    AssetAccounts.VaultCounters.decode(AssetCbor.bytes(items.get(1), 1, 1_024))));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    public static byte[] encodeCount(long count) {
        Array array = new Array();
        array.add(AssetCbor.uint(count));
        return AssetCbor.encode(array);
    }

    public static long decodeCount(byte[] response) {
        return AssetCbor.uintLong(AssetCbor.decodeArray(response, 1).getDataItems().getFirst());
    }

    public static byte[] encodeSequence(long sequence) {
        return encodeCount(sequence);
    }

    public static long decodeSequence(byte[] request) {
        return decodeCount(request);
    }
}
