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
 * </pre>
 *
 * The claim index is dense: every reserved claim has one sequence, from 0, so a relayer can walk every claim without a
 * range scan.
 */
public final class SettlementQueries {
    public static final String CLAIM = "asset-gateway/claim";
    public static final String DOMAIN_STATUS = "asset-gateway/domain-status";
    public static final String CLAIM_COUNT = "asset-gateway/claim-count";
    public static final String CLAIM_BY_SEQUENCE = "asset-gateway/claim-by-sequence";

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
