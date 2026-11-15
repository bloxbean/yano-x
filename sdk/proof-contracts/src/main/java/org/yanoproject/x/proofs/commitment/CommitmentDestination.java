package org.yanoproject.x.proofs.commitment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Independent publication identity. Contains neither credentials nor an L1 implementation. */
public record CommitmentDestination(String networkId, String adapterId, byte[] configurationDigest) {
    private static final byte[] DOMAIN = "yano-app-commitment-receipt-v1\0".getBytes(StandardCharsets.US_ASCII);

    public CommitmentDestination {
        FinalizedAppCommitmentV1.textBytes(networkId);
        FinalizedAppCommitmentV1.textBytes(adapterId);
        configurationDigest = FinalizedAppCommitmentV1.digestBytes(configurationDigest);
    }

    @Override public byte[] configurationDigest() { return configurationDigest.clone(); }

    /** Stable idempotency/receipt key; retries share a key, distinct destinations cannot share a record. */
    public byte[] receiptKey(FinalizedAppCommitmentV1 commitment) {
        Objects.requireNonNull(commitment, "commitment");
        byte[] network = FinalizedAppCommitmentV1.textBytes(networkId);
        byte[] adapter = FinalizedAppCommitmentV1.textBytes(adapterId);
        byte[] encoded = ByteBuffer.allocate(DOMAIN.length + 8 + network.length + adapter.length + 64)
                .put(DOMAIN).putInt(network.length).put(network).putInt(adapter.length).put(adapter)
                .put(configurationDigest).put(commitment.digest()).array();
        return FinalizedAppCommitmentV1.sha256(encoded);
    }

    @Override public boolean equals(Object value) {
        return value instanceof CommitmentDestination other && networkId.equals(other.networkId)
                && adapterId.equals(other.adapterId) && Arrays.equals(configurationDigest, other.configurationDigest);
    }
    @Override public int hashCode() {
        return Objects.hash(networkId, adapterId, Arrays.hashCode(configurationDigest));
    }
}
