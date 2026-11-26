package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A Yano-normalized mpf-blake2b256-v1 inclusion proof in the shape {@code MpfOnChainVerifier.Proof} reads
 * ({@code inclusion_proof} of the on-chain CDDL): the exact key and value, the leaf's encoded suffix and the folds.
 * The relayer obtains it for an anchored root from the node's proof API or a saved proof; this record only carries
 * it into a redeemer.
 */
public record StateProof(byte[] key, byte[] value, byte[] leafSuffix, List<Fold> folds) {
    /** One fold: cursor, compressed prefix, nibble, four neighbor hashes, and the branch value hash (or empty). */
    public record Fold(long cursor, byte[] prefix, int nibble, List<byte[]> neighbors, byte[] branchValueHash) {
        public Fold {
            if (neighbors.size() != 4) throw new IllegalArgumentException("a fold has four neighbors");
            neighbors = List.copyOf(neighbors);
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(branchValueHash, "branch value hash");
        }

        PlutusData toPlutusData() {
            return ConstrPlutusData.of(0, BigIntPlutusData.of(cursor), BytesPlutusData.of(prefix),
                    BigIntPlutusData.of(nibble), BytesPlutusData.of(neighbors.get(0)),
                    BytesPlutusData.of(neighbors.get(1)), BytesPlutusData.of(neighbors.get(2)),
                    BytesPlutusData.of(neighbors.get(3)), BytesPlutusData.of(branchValueHash));
        }
    }

    public StateProof {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(leafSuffix, "leaf suffix");
        folds = List.copyOf(folds);
    }

    public PlutusData toPlutusData() {
        List<PlutusData> items = new ArrayList<>();
        for (Fold fold : folds) items.add(fold.toPlutusData());
        return ConstrPlutusData.of(0, BytesPlutusData.of(key), BytesPlutusData.of(value), BytesPlutusData.of(leafSuffix),
                GatewayPlutus.list(items));
    }

    /** A source of inclusion proofs under one anchored state root. */
    @FunctionalInterface
    public interface Source {
        /** The proof of {@code key} under the root the relayer references, or empty when unavailable. */
        java.util.Optional<StateProof> inclusion(byte[] key);
    }
}
