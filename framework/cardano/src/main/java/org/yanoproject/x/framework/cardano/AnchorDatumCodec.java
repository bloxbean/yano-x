package org.yanoproject.x.framework.cardano;

import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.yanoproject.x.framework.cardano.api.anchor.AnchorDatumV1;
import org.yanoproject.x.framework.cardano.api.anchor.AnchorDatumV2;

import java.util.ArrayList;
import java.util.List;

/**
 * Off-chain codec for the anchor datum (ABI:
 * {@code framework/cardano-api/src/main/cddl/appchain/anchor-v1.cddl}). Wire format is
 * {@code Constr(0, [version, chain-id, chain-genesis-id, application-id,
 * commitment-profile-id, format-fingerprint, height, block-hash, state-root,
 * member-keys, threshold])} — the same encoding the julc record codec and
 * Aiken types produce, and what the conformance vectors pin down.
 *
 * <p>The opt-in v2 ABI ({@code anchor-v2.cddl}, ADR-059 framework addition 5)
 * appends {@code updated-at-ms}; {@link AnchorDatum#version()} selects the wire
 * form and {@link #decode(byte[])} dispatches on the encoded version.</p>
 */
final class AnchorDatumCodec {

    static final long ABI_VERSION = AnchorDatumV1.ABI_VERSION;
    static final long ABI_VERSION_V2 = AnchorDatumV2.ABI_VERSION;

    /**
     * @param version    ABI version (1)
     * @param chainId    app-chain id (UTF-8 bytes on the wire)
     * @param height     app-block height this anchor attests
     * @param blockHash  blake2b-256 app-block hash at height (32B)
     * @param stateRoot  MPF root bound by that block (32B)
     * @param memberKeys Ed25519 member public keys (32B each), sorted ascending
     * @param threshold  advance threshold (1..len(memberKeys))
     * @param updatedAtMs v2 only: POSIX ms equal to the advancing tx's validity
     *                    upper bound; always 0 for v1
     */
    record AnchorDatum(long version,
                       String chainId,
                       byte[] chainGenesisId,
                       String applicationId,
                       String commitmentProfileId,
                       byte[] formatFingerprint,
                       long height,
                       byte[] blockHash,
                       byte[] stateRoot,
                       List<byte[]> memberKeys,
                       long threshold,
                       long updatedAtMs) {

        /** A v1-shaped datum (no {@code updatedAtMs}). */
        AnchorDatum(long version, String chainId, byte[] chainGenesisId, String applicationId,
                    String commitmentProfileId, byte[] formatFingerprint, long height,
                    byte[] blockHash, byte[] stateRoot, List<byte[]> memberKeys, long threshold) {
            this(version, chainId, chainGenesisId, applicationId, commitmentProfileId,
                    formatFingerprint, height, blockHash, stateRoot, memberKeys, threshold, 0L);
        }

        /** The same datum with another {@code updatedAtMs} (v2). */
        AnchorDatum withUpdatedAtMs(long value) {
            return new AnchorDatum(version, chainId, chainGenesisId, applicationId,
                    commitmentProfileId, formatFingerprint, height, blockHash, stateRoot,
                    memberKeys, threshold, value);
        }
    }

    private AnchorDatumCodec() {
    }

    static ConstrPlutusData encode(AnchorDatum datum) {
        if (datum.version() != ABI_VERSION && datum.version() != ABI_VERSION_V2) {
            throw new IllegalArgumentException("Unsupported anchor datum version");
        }
        AnchorDatumV1 publicDatum = new AnchorDatumV1(datum.chainId(),
                datum.chainGenesisId(), datum.applicationId(), datum.commitmentProfileId(),
                datum.formatFingerprint(), datum.height(), datum.blockHash(), datum.stateRoot(),
                datum.memberKeys(), Math.toIntExact(datum.threshold()));
        byte[] encoded = datum.version() == ABI_VERSION_V2
                ? new AnchorDatumV2(publicDatum, datum.updatedAtMs()).encode()
                : publicDatum.encode();
        try {
            return (ConstrPlutusData) PlutusData.deserialize(encoded);
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Anchor datum encode failed", malformed);
        }
    }

    static AnchorDatum decode(PlutusData data) {
        if (data == null) {
            throw new IllegalArgumentException("Anchor datum is required");
        }
        return decode(data.serializeToBytes());
    }

    /**
     * Decode untrusted L1 inline-datum bytes through the bounded public codecs,
     * dispatching on the encoded ABI version (1 or 2).
     */
    static AnchorDatum decode(byte[] cbor) {
        if (AnchorDatumV2.versionOf(cbor) == ABI_VERSION_V2) {
            AnchorDatumV2 decoded = AnchorDatumV2.decode(cbor);
            return new AnchorDatum(ABI_VERSION_V2, decoded.chainId(), decoded.chainGenesisId(),
                    decoded.applicationId(), decoded.commitmentProfileId(),
                    decoded.formatFingerprint(), decoded.height(),
                    decoded.blockHash(), decoded.stateRoot(), decoded.memberKeys(),
                    decoded.threshold(), decoded.updatedAtMs());
        }
        AnchorDatumV1 decoded = AnchorDatumV1.decode(cbor);
        return new AnchorDatum(ABI_VERSION, decoded.chainId(), decoded.chainGenesisId(),
                decoded.applicationId(), decoded.commitmentProfileId(),
                decoded.formatFingerprint(), decoded.height(),
                decoded.blockHash(), decoded.stateRoot(), decoded.memberKeys(),
                decoded.threshold());
    }

    /** Canonical member-key order (bytewise ascending, unsigned) — ABI requirement. */
    static List<byte[]> sortedKeys(List<byte[]> keys) {
        List<byte[]> sorted = new ArrayList<>(keys);
        sorted.sort(java.util.Arrays::compareUnsigned);
        return sorted;
    }

}
