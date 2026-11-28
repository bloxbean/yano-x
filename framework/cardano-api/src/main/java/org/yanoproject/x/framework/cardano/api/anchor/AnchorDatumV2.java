package org.yanoproject.x.framework.cardano.api.anchor;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.yanoproject.x.framework.api.appchain.codec.internal.CborStructurePreflight;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Strict public codec for the opt-in script-anchor datum ABI v2 (ADR-059
 * framework addition 5; {@code framework/cardano-api/src/main/cddl/appchain/anchor-v2.cddl}).
 *
 * <p>The wire form is {@code Constr(0, [2, chain-id, chain-genesis-id,
 * application-id, commitment-profile-id, format-fingerprint, height, block-hash,
 * state-root, member-keys, threshold, updated-at-ms])}: the v1 fields with
 * version 2 and an appended POSIX-millisecond {@code updatedAtMs}. The first
 * eleven fields carry exactly the v1 field profiles ({@link AnchorDatumV1});
 * decoding rejects non-canonical CBOR, other versions and a negative
 * {@code updatedAtMs}.</p>
 *
 * @param base        the v1-profile fields (chain id, identity, height, block hash,
 *                    state root, members and threshold)
 * @param updatedAtMs POSIX milliseconds; on chain it equals the advancing
 *                    transaction's validity upper bound
 */
public record AnchorDatumV2(AnchorDatumV1 base, long updatedAtMs) {
    public static final long ABI_VERSION = 2;
    /** Maximum canonical v2 inline datum size accepted by the portable codec. */
    public static final int MAX_DATUM_BYTES = AnchorDatumV1.MAX_DATUM_BYTES;
    private static final CborStructurePreflight.Limits DATUM_CBOR_LIMITS =
            new CborStructurePreflight.Limits(MAX_DATUM_BYTES,
                    4, 1_024, 64, MAX_DATUM_BYTES);

    public AnchorDatumV2 {
        if (base == null || updatedAtMs < 0) {
            throw invalid();
        }
    }

    /** Convenience constructor taking every field in wire order. */
    public AnchorDatumV2(String chainId, byte[] chainGenesisId, String applicationId,
                         String commitmentProfileId, byte[] formatFingerprint, long height,
                         byte[] blockHash, byte[] stateRoot, List<byte[]> memberKeys,
                         int threshold, long updatedAtMs) {
        this(baseOf(chainId, chainGenesisId, applicationId, commitmentProfileId,
                formatFingerprint, height, blockHash, stateRoot, memberKeys, threshold),
                updatedAtMs);
    }

    public String chainId() {
        return base.chainId();
    }

    public byte[] chainGenesisId() {
        return base.chainGenesisId();
    }

    public String applicationId() {
        return base.applicationId();
    }

    public String commitmentProfileId() {
        return base.commitmentProfileId();
    }

    public byte[] formatFingerprint() {
        return base.formatFingerprint();
    }

    public long height() {
        return base.height();
    }

    public byte[] blockHash() {
        return base.blockHash();
    }

    public byte[] stateRoot() {
        return base.stateRoot();
    }

    public List<byte[]> memberKeys() {
        return base.memberKeys();
    }

    public int threshold() {
        return base.threshold();
    }

    public List<String> memberKeysHex() {
        return base.memberKeysHex();
    }

    /**
     * True when {@code next} is a pure heartbeat of this datum (anchor-v2.cddl rule 6):
     * same identity, height, block hash, state root, members and threshold, and a
     * strictly newer {@code updatedAtMs}.
     */
    public boolean isHeartbeatSuccessor(AnchorDatumV2 next) {
        Objects.requireNonNull(next, "next");
        AnchorDatumV1 nextBase = next.base();
        return base.equals(nextBase) && next.updatedAtMs() > updatedAtMs;
    }

    /** Encodes this datum as canonical inline-datum CBOR bytes. */
    public byte[] encode() {
        try {
            ConstrPlutusData v1 = (ConstrPlutusData) PlutusData.deserialize(base.encode());
            List<PlutusData> fields = new ArrayList<>(v1.getData().getPlutusDataList());
            fields.set(0, BigIntPlutusData.of(ABI_VERSION));
            fields.add(BigIntPlutusData.of(updatedAtMs));
            ListPlutusData list = ListPlutusData.builder().build();
            fields.forEach(list::add);
            return ConstrPlutusData.builder().alternative(0).data(list).build().serializeToBytes();
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    /** Decodes and validates canonical v2 inline-datum CBOR bytes. */
    public static AnchorDatumV2 decode(byte[] cbor) {
        if (cbor == null || cbor.length == 0 || cbor.length > MAX_DATUM_BYTES
                || !CborStructurePreflight.accepts(cbor, DATUM_CBOR_LIMITS)) {
            throw invalid();
        }
        try {
            PlutusData data = PlutusData.deserialize(cbor);
            if (!(data instanceof ConstrPlutusData constr) || constr.getAlternative() != 0) {
                throw invalid();
            }
            List<PlutusData> fields = constr.getData().getPlutusDataList();
            if (fields.size() != 12
                    || !(fields.get(0) instanceof BigIntPlutusData version)
                    || version.getValue().longValueExact() != ABI_VERSION
                    || !(fields.get(11) instanceof BigIntPlutusData updatedAt)) {
                throw invalid();
            }
            List<PlutusData> v1Fields = new ArrayList<>(fields.subList(0, 11));
            v1Fields.set(0, BigIntPlutusData.of(AnchorDatumV1.ABI_VERSION));
            ListPlutusData list = ListPlutusData.builder().build();
            v1Fields.forEach(list::add);
            AnchorDatumV1 base = AnchorDatumV1.decode(ConstrPlutusData.builder()
                    .alternative(0).data(list).build().serializeToBytes());
            AnchorDatumV2 datum = new AnchorDatumV2(base, updatedAt.getValue().longValueExact());
            if (!Arrays.equals(cbor, datum.encode())) {
                throw invalid();
            }
            return datum;
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    /**
     * The ABI version of untrusted inline-datum bytes (the first field of the
     * constructor-0 list), or {@code -1} when the bytes are not an anchor-shaped datum.
     * It only dispatches between {@link AnchorDatumV1#decode} and {@link #decode}; it
     * validates nothing else.
     */
    public static long versionOf(byte[] cbor) {
        if (cbor == null || cbor.length == 0 || cbor.length > MAX_DATUM_BYTES
                || !CborStructurePreflight.accepts(cbor, DATUM_CBOR_LIMITS)) {
            return -1;
        }
        try {
            PlutusData data = PlutusData.deserialize(cbor);
            if (data instanceof ConstrPlutusData constr && constr.getAlternative() == 0
                    && !constr.getData().getPlutusDataList().isEmpty()
                    && constr.getData().getPlutusDataList().get(0) instanceof BigIntPlutusData version) {
                return version.getValue().longValueExact();
            }
        } catch (Exception malformed) {
            return -1;
        }
        return -1;
    }

    private static AnchorDatumV1 baseOf(String chainId, byte[] chainGenesisId, String applicationId,
                                        String commitmentProfileId, byte[] formatFingerprint,
                                        long height, byte[] blockHash, byte[] stateRoot,
                                        List<byte[]> memberKeys, int threshold) {
        try {
            return new AnchorDatumV1(chainId, chainGenesisId, applicationId, commitmentProfileId,
                    formatFingerprint, height, blockHash, stateRoot, memberKeys, threshold);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid script-anchor datum v2");
    }
}
