package org.yanoproject.x.eutxo.zk.prover;

import org.yanoproject.x.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import org.yanoproject.x.eutxo.zk.contracts.EutxoZkProofArtifact;
import org.yanoproject.x.eutxo.zk.contracts.EutxoZkStatement;
import org.yanoproject.x.eutxo.zk.contracts.EutxoZkVerificationKey;

/** Pluggable node-local proof backend. It is never called by consensus apply. */
public interface EutxoProofBackend extends AutoCloseable {
    EutxoZkVerificationKey verificationKey();

    EutxoZkProofArtifact prove(
            EutxoZkStatement statement,
            EutxoKeyPaymentBatch witness,
            String proverId);

    boolean verify(EutxoZkProofArtifact artifact);

    @Override
    default void close() {
    }
}
